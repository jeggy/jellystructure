package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.X_OK
import platform.posix.access
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.time.Clock

/**
 * Phase 313 — our own encoder: decides per play whether it serves the transcode (FR-313-12), places the job on a card
 * (FR-313-8), keeps the streams behind `/api/tv/stream/{id}/…` (FR-313-7) and stops them with their play (phase 180).
 * The pure rules are in `EncoderPlan.kt`; the processes in [EncoderJobs].
 */
@OptIn(ExperimentalForeignApi::class)
class Encoder(
    private val config: () -> EncoderConfig,
    /** Tests only: the cards and the binary check, instead of `nvidia-smi` and the file system. */
    private val cardsOverride: (suspend () -> List<EncoderCard>)? = null,
    private val ffmpegOverride: (() -> Boolean)? = null,
) {
    private class Entry(val plan: EncoderPlan, val jellyfinPlaySessionId: String, val expiresAt: Long)

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val byPlay = mutableMapOf<String, MutableList<String>>()
    private var fallbacks = ArrayDeque<Pair<Long, String>>()
    private val cfg get() = config()
    private val jobs by lazy {
        // FR-313-9 — the configured work folder (a tmpfs in docker-compose.gpu.yml), else /tmp when it can't be made.
        val root = cfg.workDir.takeIf { mkdirs(it) } ?: "/tmp/js-encoder"
        mkdirs(cfg.cudaCacheDir)
        EncoderJobs(root) { "CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_CACHE_PATH='${cfg.cudaCacheDir}'" }
    }

    private var cardsCache: Pair<Long, List<EncoderCard>>? = null

    val ffmpegPath: String get() = "${cfg.ffmpegDir}/ffmpeg"
    fun ffmpegReady(): Boolean = ffmpegOverride?.invoke() ?: (access(ffmpegPath, X_OK) == 0)

    /** FR-313-11 — the cards the container can see (none without a GPU), cached 5 s. Their order is PCI bus order. */
    suspend fun cards(): List<EncoderCard> {
        cardsOverride?.let { return it() }
        cardsCache?.let { (at, c) -> if (nowMs() - at < 5_000) return c }
        val out = withContext(Dispatchers.IO) { run("nvidia-smi --query-gpu=index,name,encoder.stats.sessionCount --format=csv,noheader 2>/dev/null") }
        val c = parseNvidiaSmi(out)
        cardsCache = nowMs() to c
        return c
    }

    /**
     * FR-313-1..-8/-12 — the plan for one play, or the reason Jellyfin does it. [audio] is Jellyfin's audio list for
     * the item (the ticket's), [carriedIndex] the track the play starts with, [budgetBps] the bitrate the device's own
     * measurements allow (308/309, null = none), [sourceVideoRange] Jellyfin's `VideoRangeType` when known.
     */
    suspend fun planFor(
        caps: ClientCapabilities, deviceKind: String, path: String, durationMs: Long?, tracks: List<Track>,
        audio: List<AudioTrack>, carriedIndex: Int?, budgetBps: Long?, sourceVideoRange: String?, burnsSubtitle: Boolean,
    ): Pair<EncoderPlan?, String> {
        val video = tracks.firstOrNull { it.kind == TrackKind.VIDEO }
        val range = sourceVideoRange.orEmpty()
        val source = video?.let {
            EncoderSource(
                path = path, durationMs = durationMs ?: 0L, videoCodec = it.codec, width = it.width ?: 0, height = it.height ?: 0,
                videoBps = it.videoBitrate?.toLong(),
                hdr = it.videoRange.equals("HDR", true) || range.contains("HDR", true) || range.contains("HLG", true) || range.startsWith("DOVI", true),
                hlg = range.contains("HLG", true),
                dolbyVisionProfile5 = range.equals("DOVI", true),
            )
        }
        val cards = if (cfg.enabled) cards() else emptyList()
        encoderDecision(cfg.enabled, ffmpegReady(), cards, source, isLiveOrAudio = false)?.let { return null to it }
        source!!
        // 313d — a burned-in image subtitle is still Jellyfin's until the subtitle mapping is verified on a device.
        if (burnsSubtitle) return null to "image subtitle burn-in (313d)"
        val order = fileAudioOrder(audio, tracks) ?: return null to "audio tracks don't match the file (R382)"
        val codec = encoderCodecFor(caps.hlsHevc, caps.videoCodecs, caps.supportsHdr10, caps.supportsHlg, source)
        val ceiling = listOf(if (codec == EncoderCodec.HEVC) caps.maxHevcBitrate else caps.maxH264Bitrate, caps.maxVideoBitrate)
            .filter { it > 0 }.minOrNull()?.toLong()
        var rungs = encoderRungs(codec, source.width, source.height, source.videoBps, ceiling)
        var start = startRung(rungs, budgetBps)
        // Dev review item 8 / owner Q1 — a player that does not adapt (Ravilo 1.50) gets one quality: the start rung.
        if (!caps.hlsAdaptive) { rungs = listOf(rungs[start]); start = 0 }
        val load = running().groupBy { it.plan.cudaDevice ?: -1 }.mapValues { (_, js) -> js.sumOf { jobLoad(it.plan.codec, it.plan.rungs[0].boxHeight, it.plan.rungs.size) } }
        val placement = placeJob(cards, load, codec, rungs[0].boxHeight, rungs.size) ?: return null to "no encoder slot free on any card"
        if (placement.rungs < rungs.size) {
            val kept = trimRungs(rungs, start, placement.rungs)
            start = kept.indexOf(rungs[start]).coerceAtLeast(0)
            rungs = kept
        }
        val carried = audio.indexOfFirst { it.index == carriedIndex }.let { if (it < 0) audio.indexOfFirst { a -> a.isDefault }.coerceAtLeast(0) else it }
        val positions = if (caps.hlsAudioRenditions) audio.indices.toList() else listOf(carried)
        val encAudio = positions.filter { it in audio.indices }.map { pos ->
            val a = audio[pos]
            EncoderAudio(pos, order[pos], "aac", minOf(a.channels ?: 2, caps.maxAudioChannels.coerceAtLeast(1), 6), a.language, a.label, pos == carried)
        }
        val plan = EncoderPlan(
            source = source, codec = codec, mux = if (deviceKind == "cast") EncoderMux.TS else EncoderMux.FMP4,
            rungs = rungs, startRung = start, audio = encAudio, cudaDevice = placement.card,
        )
        return plan to "ours"
    }

    /** Registers [plan] behind a new stream id (the capability, FR-313-7); the job starts when a player reads it. */
    suspend fun register(plan: EncoderPlan, jellyfinPlaySessionId: String, expiresAt: Long): String {
        val id = secureHexId()
        mutex.withLock {
            val now = nowMs()
            entries.entries.removeAll { it.value.expiresAt < now }
            entries[id] = Entry(plan, jellyfinPlaySessionId, expiresAt)
            byPlay.getOrPut(jellyfinPlaySessionId) { mutableListOf() }.add(id)
        }
        return id
    }

    fun recordFallback(reason: String) {
        fallbacks.addLast(nowMs() to reason)
        while (fallbacks.size > 200) fallbacks.removeFirst()
    }

    suspend fun owns(id: String): Boolean = entry(id) != null

    suspend fun master(id: String): String? = entry(id)?.let { encoderMaster(it.plan) }

    /** A variant's playlist: [kind] `v` (rung index) or `a` (audio position). */
    suspend fun playlist(id: String, kind: String, index: Int): String? {
        val e = entry(id) ?: return null
        variantOf(e.plan, kind, index) ?: return null
        return encoderPlaylist(e.plan.source.durationMs, e.plan.mux)
    }

    suspend fun segment(id: String, kind: String, index: Int, k: Int): String? {
        val e = entry(id) ?: return null
        val v = variantOf(e.plan, kind, index) ?: return null
        if (k < 0 || k >= encoderSegmentCount(e.plan.source.durationMs)) return null
        return jobs.segment(id, e.plan, ffmpegPath, v, k)
    }

    suspend fun init(id: String, kind: String, index: Int, nearSegment: Int = 0): String? {
        val e = entry(id) ?: return null
        if (e.plan.mux != EncoderMux.FMP4) return null
        val v = variantOf(e.plan, kind, index) ?: return null
        return jobs.init(id, e.plan, ffmpegPath, v, nearSegment)
    }

    fun mux(id: String): EncoderMux? = entries[id]?.plan?.mux

    /** Phase 180 / FR-313-1 — every stream (and job) registered for [jellyfinPlaySessionId]; returns how many. */
    suspend fun stopFor(jellyfinPlaySessionId: String): Int {
        val ids = mutex.withLock { byPlay.remove(jellyfinPlaySessionId) } ?: return 0
        ids.forEach { jobs.stop(it, "playback stopped") }
        mutex.withLock { ids.forEach { entries.remove(it) } }
        return ids.size
    }

    internal suspend fun running(): List<EncoderJobs.Job> = if (cfg.enabled) jobs.running() else emptyList()

    /** FR-313-8 — `/api/health`'s `encoder` block. */
    suspend fun healthJson(): String {
        val c = cfg
        val cards = if (c.enabled) cards() else emptyList()
        val js = running()
        val hour = nowMs() - 3_600_000
        val fb = fallbacks.filter { it.first >= hour }
        val cardsJson = cards.joinToString(",") { card ->
            val mine = js.filter { it.plan.cudaDevice == card.index }
            """{"index":${card.index},"name":"${card.name.replace("\"", "")}","sessions":${card.liveSessions},"session_cap":${card.sessionCap ?: "null"},"jobs":${mine.size},"rungs":${mine.sumOf { it.plan.rungs.size }}}"""
        }
        return """{"enabled":${c.enabled},"ffmpeg":${ffmpegReady()},"gpu":${cards.isNotEmpty()},"cards":[$cardsJson],""" +
            """"jobs":${js.size},"paused":${js.count { it.paused }},"fallbacks_last_hour":${fb.size},""" +
            """"last_fallback":${fb.lastOrNull()?.second?.let { "\"" + it.replace("\"", "'") + "\"" } ?: "null"}}"""
    }

    /**
     * FR-313-11 — fetches jellyfin-ffmpeg's portable build into [EncoderConfig.ffmpegDir] when the encoder is on and the
     * binary is missing, verified against its pinned SHA-256 (not shipped in the public image: it contains libfdk_aac).
     */
    fun ensureFfmpeg(scope: CoroutineScope) {
        val c = cfg
        if (!c.enabled || !c.downloadFfmpeg || ffmpegReady()) return
        scope.launch(Dispatchers.IO) {
            val dir = c.ffmpegDir
            val cmd = "mkdir -p '$dir' && cd '$dir' && wget -q -O ff.tar.xz '$JELLYFIN_FFMPEG_URL' && " +
                "echo '$JELLYFIN_FFMPEG_SHA256  ff.tar.xz' | sha256sum -c - >/dev/null && tar xJf ff.tar.xz && rm -f ff.tar.xz && echo ok"
            val out = run("$cmd 2>&1")
            if (ffmpegReady()) Logger.info("encoder: jellyfin-ffmpeg $JELLYFIN_FFMPEG_VERSION ready in $dir", "tv")
            else Logger.warn("encoder: could not install jellyfin-ffmpeg ($out) — every transcode stays Jellyfin's", "tv")
        }
    }

    private fun variantOf(plan: EncoderPlan, kind: String, index: Int): Int? = when (kind) {
        "v" -> index.takeIf { it in plan.rungs.indices }
        "a" -> plan.audio.indexOfFirst { it.position == index }.takeIf { it >= 0 }?.let { plan.rungs.size + it }
        else -> null
    }

    private suspend fun entry(id: String): Entry? = mutex.withLock { entries[id]?.takeIf { it.expiresAt >= nowMs() } }

    private fun run(cmd: String): String = memScoped {
        val pipe = popen(cmd, "r") ?: return@memScoped ""
        val buf = allocArray<ByteVar>(512)
        val sb = StringBuilder()
        while (fgets(buf, 512, pipe) != null) sb.append(buf.toKString())
        pclose(pipe)
        sb.toString()
    }

    companion object {
        /** Pinned (FR-313-11): the same build Jellyfin's own container runs (8.1.2), portable GPL, verified by SHA-256. */
        const val JELLYFIN_FFMPEG_VERSION = "8.1.2-5"
        const val JELLYFIN_FFMPEG_URL = "https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/v8.1.2-5/jellyfin-ffmpeg_8.1.2-5_portable_linux64-gpl.tar.xz"
        const val JELLYFIN_FFMPEG_SHA256 = "1fd859927053c44a4f2dbf67ae8b9ba8d29fb3b8930df0dd57d91aa60589363d"
    }
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
