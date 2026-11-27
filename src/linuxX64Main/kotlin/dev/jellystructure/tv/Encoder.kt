package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.ops.boundedOrNull
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.io.files.Path
import platform.posix.X_OK
import platform.posix.access
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.Platform
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
    /** 313d — a text fetch (Jellyfin's VTT conversion of a subtitle stream, by its tokened URL); null = none. */
    private val fetchText: (suspend (String) -> String?)? = null,
    /** DEB-3100 (trouble-never-reaches-the-viewer FR-2) — where a stream's plan is kept, so a restart does not end the play; null = memory only. */
    private val plans: StreamPlanStore? = null,
    /** Tests only: the clock (epoch ms) every expiry and renewal is read from. */
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Tests only: the jobs' free-space reading and its floor (see [EncoderJobs]). */
    private val freeBytes: (() -> Long)? = null,
    /** DEB-3629 (FR-313-12) — tests only: the first-segment deadline of a stream's first job, and of the first job on a card since start. */
    private val firstSegmentDeadlinesMs: Pair<Long, Long> = EncoderJobs.FIRST_SEGMENT_MS to EncoderJobs.COLD_FIRST_SEGMENT_MS,
    /** DEB-5647 (FR-313-21) — tests only: how long a job may go unread, and how often that is looked at. */
    private val idleTimingMs: Pair<Long, Long> = EncoderJobs.IDLE_MS to EncoderJobs.IDLE_SWEEP_MS,
) {
    private fun nowMs(): Long = clock()

    /**
     * One stream behind an id. [deviceId]/[itemId] say whose play it is (309's prewarm is adopted by the play that matches
     * it); [prewarmSegment] is non-null while it is a prewarm nobody has pressed Play on yet (FR-313-1 / 309 FR-309-6).
     */
    private class Entry(var plan: EncoderPlan, var jellyfinPlaySessionId: String, var expiresAt: Long,
                        val deviceId: String = "", val itemId: String = "", var prewarmSegment: Int? = null,
                        /** 313 (found live 2026-10-09) — where the play starts: the master says so (`EXT-X-START`) and the
                         *  init segment starts its job there, so a resumed play never encodes from 0:00 first. */
                        var startMs: Long = 0,
                        /** DEB-2205 (FR-8) — the play's QoE row key (PlaybackService.qoePlayKey) the stream's summary is filed under. */
                        var qoeKey: String = "",
                        /** DEB-3100 (FR-2) — a job has been handed this stream before (or it was restored from the store): the next start is a restart. */
                        var started: Boolean = false,
                        /** When the store last heard of a read (a write at most once a minute per stream). */
                        var persistedAtMs: Long = 0,
                        /** DEB-4062 (DEB-3995 FR-6) — a download stream, not a play: [downloadOwner] is the phone that holds it, [lastReadMs] its last segment request. */
                        var downloadOwner: String? = null, var lastReadMs: Long = 0,
                        /** DEB-5647 (FR-313-21) — a stopped play's stream kept as the device's prewarm ([keepWarm]); false again once a play adopts it. */
                        var keptWarm: Boolean = false,
                        /** DEB-5685 (FR-313-23) — when this prewarm was last posted, or its play stopped (a kept-warm stream): the older goes first when a play needs the room. */
                        var warmAtMs: Long = 0,
                        /** DEB-5685 (FR-313-24) — a placement that has no job yet: the card, its load and when it was made. It counts for [PENDING_MS], or until a job has the stream. */
                        var pendingCard: Int? = null, var pendingLoad: Double = 0.0, var pendingAtMs: Long = 0) {
        val download: Boolean get() = downloadOwner != null
    }

    /** DEB-3100 (FR-2) — what the store keeps of an [Entry] (the stream id is the row's key, the expiry its own column). */
    @Serializable
    private class StoredStream(val plan: EncoderPlan, val playSessionId: String, val deviceId: String = "", val itemId: String = "", val startMs: Long = 0, val qoeKey: String = "")

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val byPlay = mutableMapOf<String, MutableList<String>>()
    private var fallbacks = ArrayDeque<Pair<Long, String>>()
    private val cfg get() = config()
    private val jobs by lazy {
        // FR-313-9 — the configured work folder (a tmpfs in docker-compose.gpu.yml), else /tmp when it can't be made.
        val root = cfg.workDir.takeIf { mkdirs(it) } ?: "/tmp/js-encoder"
        mkdirs(cfg.cudaCacheDir)
        EncoderJobs(root, freeBytes = freeBytes ?: { freeBytesOf(root) },
            firstSegmentDeadlineMs = firstSegmentDeadlinesMs.first, coldFirstSegmentDeadlineMs = firstSegmentDeadlinesMs.second,
            idleMs = idleTimingMs.first, idleSweepMs = idleTimingMs.second, envPrefix = { "CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_CACHE_PATH='${cfg.cudaCacheDir}'" },
            onRefused = { plan, rc -> refused.markRefused(plan.source.path, plan.codec, rc, clock()); onRefusedFile?.invoke(plan.source.path) },
            onStopped = { id, summary ->
                // DEB-2205 (FR-8) — a PLAY's job stopped: its summary goes to the play's QoE row. A prewarm nobody played has none.
                val e = mutex.withLock { entries[id] }
                if (e != null && e.prewarmSegment == null && e.deviceId.isNotBlank()) onSummary?.invoke(e.deviceId, e.itemId, e.qoeKey, summary)
                // DEB-5647 (FR-313-21) — a kept stream whose job idled out has nothing left to adopt: its entry goes with its folder.
                if (e != null && e.keptWarm && e.prewarmSegment != null) mutex.withLock {
                    entries.remove(id)
                    byPlay[e.jellyfinPlaySessionId]?.let { l -> l.remove(id); if (l.isEmpty()) byPlay.remove(e.jellyfinPlaySessionId) }
                }
            })
    }

    /** DEB-2205 (FR-8) — set by the playback service: (device, item, the play's QoE key, the encoder's summary of it) when its job stops. */
    @kotlin.concurrent.Volatile
    var onSummary: (suspend (String, String, String, EncoderSummary) -> Unit)? = null

    /** DEB-3642 (FR-5) — set by the playback service: a file whose first job was refused (FR-313-12, or ffmpeg's own exit). */
    @kotlin.concurrent.Volatile
    var onRefusedFile: ((String) -> Unit)? = null

    /** 313 (2026-10-09) — files ffmpeg refused: their next plays go to Jellyfin instead of retrying the same command. */
    internal val refused = RefusedSources()

    private var cardsCache: Pair<Long, List<EncoderCard>>? = null

    /** DEB-5685 (FR-313-24) — sessions each placement has added to its card since the last `nvidia-smi` read (the cached count is up to 5 s old). */
    private val sessionBumps = mutableMapOf<Int, Int>()

    /** Serialises placements: the one that is deciding sees every earlier one (a reservation or a registered stream), not an empty card. Taken before [mutex], never inside it. */
    private val placementMutex = Mutex()

    /** DEB-5685 (FR-313-24) — a plan [planFor] has placed that no stream holds yet; [register] (or an adoption) turns it into the stream's own pending load. */
    private class Reservation(var plan: EncoderPlan?, val card: Int, val load: Double, val atMs: Long)
    private val reservations = mutableListOf<Reservation>()

    val ffmpegPath: String get() = "${cfg.ffmpegDir}/ffmpeg"
    fun ffmpegReady(): Boolean = testFfmpeg ?: ffmpegOverride?.invoke() ?: (access(ffmpegPath, X_OK) == 0)

    /** Tests only: the cards and the binary check of an encoder a [PlaybackService] built (its constructor takes neither). */
    internal var testCards: List<EncoderCard>? = null
    internal var testFfmpeg: Boolean? = null

    /** FR-313-11 — the cards the container can see (none without a GPU), cached 5 s. Their order is PCI bus order. */
    suspend fun cards(): List<EncoderCard> {
        testCards?.let { return it }
        cardsOverride?.let { return it() }
        cardsCache?.let { (at, c) -> if (clock() - at < 5_000) return c.withBumps() }
        val out = withContext(Dispatchers.IO) { run("nvidia-smi --query-gpu=index,name,encoder.stats.sessionCount --format=csv,noheader 2>/dev/null") }
        val c = parseNvidiaSmi(out)
        cardsCache = clock() to c
        sessionBumps.clear()
        return c
    }

    private fun List<EncoderCard>.withBumps(): List<EncoderCard> = map { c -> sessionBumps[c.index]?.let { c.copy(liveSessions = c.liveSessions + it) } ?: c }

    /**
     * FR-313-1..-8/-12 — the plan for one play, or the reason Jellyfin does it. [audio] is Jellyfin's audio list for
     * the item (the ticket's), [carriedIndex] the track the play starts with, [takeBps] what this device has shown it
     * can take (309's record, in the master's own units; null = nothing), [noRecord] true when it has no record at all
     * (309 FR-309-2: start on 720p, never the top), [sourceVideoRange] Jellyfin's `VideoRangeType` when known,
     * [burnSubtitleOrder] the picked image subtitle's place among the file's subtitle streams (313d; null = none,
     * [burnRequested] true when one was asked for but could not be mapped), [subtitles] text subtitles as WebVTT
     * renditions (313d, only for a player that takes them from the manifest).
     */
    suspend fun planFor(
        caps: ClientCapabilities, deviceKind: String, path: String, durationMs: Long?, tracks: List<Track>,
        audio: List<AudioTrack>, carriedIndex: Int?, takeBps: Long?, noRecord: Boolean, sourceVideoRange: String?,
        burnSubtitleOrder: Int? = null, burnRequested: Boolean = burnSubtitleOrder != null, subtitles: List<EncoderSubtitle> = emptyList(),
        platform: String? = null,
        /** DEB-2205 (FR-5.2) — a freeze recovery: only rungs whose BANDWIDTH is under this (see [rungsBelowBandwidth]). */
        belowBandwidthBps: Long? = null,
        /** DEB-3101 (FR-313-5c) — Jellyfin's `BitRate` of each audio stream, by its stream index (a [AudioTrack.index]). */
        audioBps: Map<Int, Long> = emptyMap(),
        /** DEB-3104 (FR-7) — the stall's cap is what holds this device's start under what its measurements allow. */
        stallCapped: Boolean = false,
        /** DEB-3338 (FR-313-18) — the tallest box the top rung may use: set when the play reaches us only by the size-cap rule. */
        boxLimit: Int? = null,
        /** DEB-4541 (FR-2) — a live stall hold for this device: with no record or a take under 6 Mb/s, the floor rungs come first under the four-rung limit. */
        stallHold: Boolean = false,
        /** DEB-3642 (FR-2) — the file's streams that hold no packet (its current packet timeline): none of them is mapped, offered or carried. */
        emptyStreams: Set<Int> = emptySet(),
        /** DEB-5988 (FR-313-28) — the picture's frames per second (Jellyfin's `RealFrameRate`): each rung's level holds it; null = chosen as for 60 fps. */
        sourceFrameRate: Double? = null,
        /** DEB-4256 (DEB-3995 FR-6) — a download stream's plan: the cards are tried in the reverse of a play's order, so the card plays prefer keeps its room. */
        cardsLastFirst: Boolean = false,
        /** DEB-5685 (FR-313-23) — false for a prewarm: nothing running is stopped for it and it is never trimmed (answered "no encoder slot free" instead). */
        evict: Boolean = true,
        /** …and the (device, item) the plan is for: the warm stream it may adopt is not counted against it. */
        forDevice: String? = null, forItem: String? = null,
    ): Pair<EncoderPlan?, String> {
        val source = encoderSourceOf(path, durationMs, tracks, sourceVideoRange, emptyStreams, sourceFrameRate)
        val cards = if (cfg.enabled) cards() else emptyList()
        encoderDecision(cfg.enabled, ffmpegReady(), cards, source, isLiveOrAudio = false, deviceKind = deviceKind, castReceivers = cfg.castReceivers)?.let { return null to it }
        source!!
        refused.reason(path, clock())?.let { return null to it }
        // 313d (FR-313-6) — a picked image subtitle is burned in by this job (composited once, before the split). One
        // the scan can't place among the file's subtitle streams stays Jellyfin's.
        if (burnRequested && burnSubtitleOrder == null) return null to "image subtitle not found among the file's subtitle streams (313d)"
        if (burnSubtitleOrder != null && tracks.count { it.kind == TrackKind.SUBTITLE } <= burnSubtitleOrder)
            return null to "image subtitle not found among the file's subtitle streams (313d)"
        val order = fileAudioOrder(audio, tracks, emptyStreams) ?: return null to "audio tracks don't match the file (R382)"
        val real = audio.filter { it.index !in emptyStreams }
        if (audio.isNotEmpty() && real.isEmpty()) return null to "every audio track holds no packets (DEB-3642)"
        // FR-2 — an empty carried track is swapped for its twin (else the default real one); said once, where it is made.
        val pick = pickRealAudio(audio, carriedIndex, emptyStreams)
        pick.logLine?.let { Logger.info(it, "tv") }
        // overlay_cuda composites 8-bit frames only: a burn-in play is H.264 SDR (tone-mapped once when HDR).
        val codec = if (burnSubtitleOrder != null) EncoderCodec.H264
            else encoderCodecFor(caps.hlsHevc, caps.hlsHevcCapable, caps.videoCodecs, caps.supportsHdr10, caps.supportsHlg, source)
        val ceiling = listOf(if (codec == EncoderCodec.HEVC) caps.maxHevcBitrate else caps.maxH264Bitrate, caps.maxVideoBitrate)
            .filter { it > 0 }.minOrNull()?.toLong()
        val carried = real.indexOfFirst { it.index == pick.index }.let { if (it < 0) real.indexOfFirst { a -> a.isDefault }.coerceAtLeast(0) else it }
        val positions = if (caps.hlsAudioRenditions) real.indices.toList() else listOf(carried)
        // DEB-3101 (FR-313-5a) — one codec per master: the film's own Dolby codec when this device decodes it, else AAC.
        val family = audioFamilyFor(real.getOrNull(carried)?.codec, caps.platformAudioDecoders)
        val encAudio = positions.filter { it in real.indices }.map { pos ->
            val a = real[pos]
            audioRenditionFor(pos, order[pos], family, a.codec, a.channels, caps.maxAudioChannels, audioBps[a.index], a.language, a.label, pos == carried)
        }
        val audioPeak = encAudio.maxOfOrNull { audioBitrate(it) } ?: 0L
        var rungs = encoderRungs(codec, source.width, source.height, source.videoBps, ceiling, boxLimit = boxLimit,
            floorFirst = floorFirst(takeBps, stallHold), takeBps = takeBps, audioPeakBps = audioPeak)
        if (belowBandwidthBps != null) rungs = rungsBelowBandwidth(rungs, audioPeak, belowBandwidthBps)
        // 309 (FR-309-2) — where this device starts: what it has shown it can take, else (no record, a player that
        // climbs) the 720p 4 Mbps rung, else (a player that can't climb) the top.
        var start = startRungFor(rungs, audioPeak, takeBps, noRecord && caps.hlsAdaptive)
        val plannedRungs = rungs.size
        val plannedStart = start
        // Dev review item 8 / owner Q1 — a player that does not adapt (Ravilo 1.50) gets one quality: the start rung.
        if (!caps.hlsAdaptive) { rungs = listOf(rungs[start]); start = 0 }
        val tryOrder = if (cardsLastFirst) cards.reversed() else cards
        val own = if (forDevice != null && forItem != null) forDevice to forItem else null
        var reserved: Reservation? = null
        val placement = placementMutex.withLock {
            // DEB-5685 (FR-313-23) — a play that does not fit with every rung stops warm jobs one at a time before it is trimmed; a prewarm stops none.
            var snap = loadSnapshot(own)
            var p = placeJob(tryOrder, snap.load, codec, rungs[0].boxHeight, rungs.size)
            while (evict && (p == null || p.rungs < rungs.size) && evictOne(snap, if (cardsLastFirst) "a download stream needs the slot" else "a play needs the slot")) {
                snap = loadSnapshot(own)
                p = placeJob(tryOrder, snap.load, codec, rungs[0].boxHeight, rungs.size)
            }
            if (!evict && p != null && p.rungs < rungs.size) p = null
            if (p != null) mutex.withLock {
                // The sessions the card's cached count has not seen yet, and the load nobody holds until [register] makes it the stream's.
                sessionBumps[p.card] = (sessionBumps[p.card] ?: 0) + p.rungs
                reserved = Reservation(null, p.card, jobLoad(codec, rungs[0].boxHeight, p.rungs), clock()).also { reservations += it }
            }
            p
        } ?: return null to "no encoder slot free on any card"
        if (placement.rungs < rungs.size) {
            val kept = trimRungs(rungs, start, placement.rungs)
            start = kept.indexOf(rungs[start]).coerceAtLeast(0)
            rungs = kept
        }
        val heldBy = heldByReasons(
            plannedRungs = plannedRungs, keptRungs = rungs.size, plannedStart = plannedStart, keptStart = start, adaptive = caps.hlsAdaptive,
            noRecord = noRecord, stallCapped = stallCapped, floorRungs = rungs.any { it.boxHeight < 480 },
        )
        val plan = EncoderPlan(
            source = source, codec = codec, mux = encoderMuxFor(deviceKind, platform),
            rungs = rungs, startRung = start, audio = encAudio, cudaDevice = placement.card,
            burnSubtitleOrder = burnSubtitleOrder, subtitles = if (caps.hlsSubtitles) subtitles else emptyList(),
            heldBy = heldBy,
        )
        reserved?.plan = plan   // the stream [register] makes for this plan takes the reservation over
        return plan to "ours"
    }

    /** What the budget counts: [load] per card, the streams behind it ([counted]) and the warm stream of the play being placed ([ownIds]), which is left out. */
    private class LoadSnapshot(val load: Map<Int, Double>, val counted: Set<String>, val ownIds: Set<String>)

    /**
     * FR-313-8 / DEB-5685 (FR-313-23, -24) — every running job's load, plus a stream registered or restarted in the last [PENDING_MS] whose job has not
     * started, plus a plan placed that no stream holds yet. The warm stream of [own] (device, item), the one the play may adopt, counts for nothing.
     */
    private suspend fun loadSnapshot(own: Pair<String, String>?): LoadSnapshot {
        val js = running()
        val now = clock()
        val (ownIds, pending, held) = mutex.withLock {
            reservations.removeAll { now - it.atMs >= PENDING_MS }
            val ownIds = if (own == null) emptySet() else entries.filter { (_, e) -> e.prewarmSegment != null && e.deviceId == own.first && e.itemId == own.second }.keys.toSet()
            Triple(ownIds, entries.filter { (id, e) -> e.pendingCard != null && now - e.pendingAtMs < PENDING_MS && id !in ownIds }.toList(), reservations.toList())
        }
        val load = mutableMapOf<Int, Double>()
        val counted = mutableSetOf<String>()
        for (j in js) {
            if (j.streamId in ownIds) continue
            counted += j.streamId
            load.add(j.plan.cudaDevice ?: -1, jobLoad(j.plan.codec, j.plan.rungs[0].boxHeight, j.plan.rungs.size))
        }
        for ((id, e) in pending) {
            if (id in counted) continue   // its job is running: counted above (between a job's stream and its process the stream's pending load is the only count)
            counted += id
            load.add(e.pendingCard!!, e.pendingLoad)
        }
        for (r in held) load.add(r.card, r.load)
        return LoadSnapshot(load, counted, ownIds)
    }

    private fun MutableMap<Int, Double>.add(card: Int, by: Double) { this[card] = (this[card] ?: 0.0) + by }

    /**
     * DEB-5685 (FR-313-23) — stops ONE warm job for a play that does not fit: another device's page prewarm (the oldest post first), else a kept-warm
     * stream (the oldest stop first), else a side job. False when none is left. Only streams the budget counts are stopped: one that holds nothing frees nothing.
     */
    private suspend fun evictOne(snap: LoadSnapshot, why: String): Boolean {
        val (prewarms, kept) = mutex.withLock {
            val warm = entries.filter { (id, e) -> e.prewarmSegment != null && id in snap.counted && id !in snap.ownIds }.values.sortedBy { it.warmAtMs }
            warm.filter { !it.keptWarm }.map { prewarmKey(it.deviceId, it.itemId) } to warm.filter { it.keptWarm }.map { prewarmKey(it.deviceId, it.itemId) }
        }
        prewarms.firstOrNull()?.let { stopFor(it); Logger.info("encoder: stopped 1 prewarm(s) — $why (313/309, DEB-5685 FR-313-23)", "tv"); return true }
        kept.firstOrNull()?.let { stopFor(it); Logger.info("encoder: stopped 1 kept-warm stream(s) — $why (DEB-5685 FR-313-23)", "tv"); return true }
        if (jobs.stopOneSideJob()) { Logger.info("encoder: stopped 1 side job(s) — $why (DEB-5685 FR-313-23)", "tv"); return true }
        return false
    }

    /** Registers [plan] behind a new stream id (the capability, FR-313-7); a play's job starts with its ticket ([startWithTicket], DEB-5646), a stream with no ticket kick when a player reads it. */
    suspend fun register(plan: EncoderPlan, jellyfinPlaySessionId: String, expiresAt: Long, deviceId: String = "", itemId: String = "", startMs: Long = 0, qoeKey: String = ""): String {
        val id = secureHexId()
        mutex.withLock {
            val now = clock()
            entries.entries.removeAll { it.value.expiresAt < now }
            val e = Entry(plan, jellyfinPlaySessionId, expiresAt, deviceId, itemId, startMs = startMs.coerceIn(0, plan.source.durationMs), qoeKey = qoeKey)   // DEB-3642 (FR-7) — a start past the content end is at it
            entries[id] = e
            takePending(e, plan, now)
            byPlay.getOrPut(jellyfinPlaySessionId) { mutableListOf() }.add(id)
            // A prewarm nobody has pressed Play on is memory-only; the play that adopts it stores it ([adoptPrewarm]).
            if (!jellyfinPlaySessionId.startsWith(PREWARM_PREFIX)) persist(id, e, now)
            if (plans != null && now - lastSweepMs > 3_600_000L) { lastSweepMs = now; plans.sweep(now) }
        }
        return id
    }

    /** DEB-5685 (FR-313-24) — the reservation [planFor] made for [plan] becomes [e]'s own pending load (its job has not started yet). Called with the lock held. */
    private fun takePending(e: Entry, plan: EncoderPlan, now: Long) {
        val r = reservations.firstOrNull { it.plan === plan } ?: return
        reservations.remove(r)
        e.pendingCard = r.card; e.pendingLoad = r.load; e.pendingAtMs = now
    }

    // ── 309 (FR-309-6, owner 2026-10-07/08) — the early encode on a detail page ───────────────────────────────────

    /**
     * Starts [plan] for (device, item) at [startSegment] before Play (the viewer has been on the detail page > 2 s), or
     * touches the one already running for the same plan so its idle timer doesn't end it while the viewer still reads
     * the page. A prewarm posts nothing to Jellyfin (no `/Sessions/Playing`, no transcode job) and warms no R291
     * rendition job (its audio is in this one process). Returns the stream id.
     */
    suspend fun prewarm(plan: EncoderPlan, deviceId: String, itemId: String, startSegment: Int, expiresAt: Long): String {
        val key = prewarmKey(deviceId, itemId)
        val existing = mutex.withLock {
            entries.entries.firstOrNull { (_, e) -> e.prewarmSegment != null && e.deviceId == deviceId && e.itemId == itemId }?.toPair()
        }
        if (existing != null) {
            val (id, e) = existing
            if (samePlay(e.plan, plan) && kotlin.math.abs((e.prewarmSegment ?: 0) - startSegment) <= 1) {
                mutex.withLock {
                    e.expiresAt = expiresAt; e.warmAtMs = clock(); reservations.removeAll { it.plan === plan }
                    if (e.plan.startRung != plan.startRung) { e.plan = e.plan.copy(startRung = plan.startRung); persist(id, e, clock()) }   // DEB-5685 (FR-313-27) — a moved start rung keeps the stream and the master lists the new one first
                }
                if (kickJobs) scope.launch { jobs.segment(id, e.plan, ffmpegPath, 0, e.prewarmSegment ?: startSegment, warm = true) }
                return id
            }
            stopFor(key)
        }
        val id = register(plan, key, expiresAt, deviceId, itemId)
        mutex.withLock { entries[id]?.let { it.prewarmSegment = startSegment; it.startMs = startSegment * ENCODER_SEGMENT_MS; it.warmAtMs = clock() } }
        Logger.info("encoder: prewarm device=$deviceId item=$itemId at segment $startSegment ${plan.codec} rungs=${plan.rungs.size} start=${plan.startRung} (309)", "tv")
        if (kickJobs) scope.launch { jobs.segment(id, plan, ffmpegPath, 0, startSegment, warm = true) }
        return id
    }

    /**
     * Play adopts the prewarm for (device, item) when it is the same play: same plan (codec, rungs, audio, subtitles,
     * burn-in, muxing; not the start rung, FR-313-27) and a start within one segment. The stream id (and its running job) then belongs to
     * [jellyfinPlaySessionId], so phase 180 stops it with the play. Otherwise the prewarm is stopped first (never two
     * jobs for one play) and null is returned. Owner, 2026-10-08: the early encode becomes the play's own job.
     */
    suspend fun adoptPrewarm(deviceId: String, itemId: String, plan: EncoderPlan, startSegment: Int, jellyfinPlaySessionId: String, expiresAt: Long, qoeKey: String = ""): String? {
        val key = prewarmKey(deviceId, itemId)
        val adopted = mutex.withLock {
            val hit = entries.entries.firstOrNull { (_, e) -> e.prewarmSegment != null && e.deviceId == deviceId && e.itemId == itemId }
                ?: return@withLock null
            val e = hit.value
            reservations.removeAll { it.plan === plan }   // DEB-5685 (FR-313-24) — the adopted stream already holds the load
            if (!samePlay(e.plan, plan) || kotlin.math.abs((e.prewarmSegment ?: 0) - startSegment) > 1) return@withLock null
            e.prewarmSegment = null
            e.keptWarm = false
            if (e.plan.startRung != plan.startRung) e.plan = e.plan.copy(startRung = plan.startRung)   // DEB-5685 (FR-313-27) — the stream takes the play's start rung; the job's command does not depend on it
            e.startMs = startSegment * ENCODER_SEGMENT_MS
            e.jellyfinPlaySessionId = jellyfinPlaySessionId
            e.qoeKey = qoeKey
            e.expiresAt = expiresAt
            byPlay[key]?.remove(hit.key)
            if (byPlay[key]?.isEmpty() == true) byPlay.remove(key)
            byPlay.getOrPut(jellyfinPlaySessionId) { mutableListOf() }.add(hit.key)
            persist(hit.key, e, clock())
            hit.key
        }
        if (adopted != null) {
            jobs.rearmHead(adopted, startSegment)   // DEB-5685 (FR-313-22)
            Logger.info("encoder: play adopted the prewarm for item=$itemId (309)", "tv")
        }
        else stopFor(key)
        return adopted
    }

    /**
     * DEB-2205 (FR-4) — a hand-over ahead of time: starts stream [id]'s job at [startSegment] now and returns when that segment of the
     * start rung exists, or after [waitMs] (whichever is first) — true only when it exists. The caller answers the player's
     * restream with it, so a ticket that arrives means the first segment the player will read is already made. The wait is the
     * caller's own bound: the job itself carries on either way.
     */
    suspend fun warmAndWait(id: String, startSegment: Int, waitMs: Long): Boolean {
        val e = entry(id) ?: return false
        if (!kickJobs) return false
        val made = boundedOrNull(waitMs, "encoder warm-and-wait (DEB-2205 FR-4)") {
            jobs.segment(id, e.plan, ffmpegPath, e.plan.startRung, startSegment, warm = true)
        }
        return made != null
    }

    /**
     * DEB-5646 (FR-313-19) — a play's ticket names stream [id]: its first job starts now, at the ticket's [startSegment], as a prewarm's does,
     * so the encode overlaps the player's own open. Nothing waits for it. The job runs under the rules it has (40 s ahead, [EncoderJobs.IDLE_MS]
     * unread from now, FR-313-12's deadline from its own start); a ticket that adopted a prewarm already has its job and does not call this.
     */
    suspend fun startWithTicket(id: String, startSegment: Int) {
        if (!kickJobs) return
        val e = entry(id) ?: return
        val k = encoderSeekSegment(e.plan.source.durationMs, startSegment * ENCODER_SEGMENT_MS)   // the segment the player's first read and init name (DEB-3642 FR-7)
        Logger.info("encoder $id: the ticket starts the job at segment $k (DEB-5646 FR-313-19)", "tv")
        scope.launch { jobs.segment(id, e.plan, ffmpegPath, 0, k, warm = true) }
    }

    /**
     * DEB-5685 (FR-313-22) — a play adopted stream [id] ([adoptPrewarm]) but no job of this process has it any more (stopped by idle or by the budget, its
     * entry still there): the ticket starts one at the adopted start, as for a stream with nothing adopted. True when it did.
     */
    suspend fun startAdoptedIfGone(id: String, startSegment: Int): Boolean {
        if (!kickJobs || jobs.hasStream(id)) return false
        startWithTicket(id, startSegment)
        return true
    }

    /**
     * DEB-5647 (FR-313-21) — the play behind [jellyfinPlaySessionId] stopped at [atMs] and leaves a resume point: its stream is not deleted but becomes
     * the device's prewarm for the item (the entry [prewarm] makes, keyed by device and item), at the stop's segment, so Back then Play again, or the
     * app hidden and brought back, is adopted ([adoptPrewarm]) and starts from what is on disk. What it made stays and its job stays under FR-313-1's
     * rules; unread for [EncoderJobs.IDLE_MS] from now it stops and its folder goes. The play's [onSummary] is filed now, as at a stop. Returns false
     * (nothing kept, the caller's [stopFor] deletes the stream) for a stream that is no play, a download, one with no job or one ffmpeg refused (FR-313-12).
     */
    suspend fun keepWarm(jellyfinPlaySessionId: String, atMs: Long): Boolean {
        val (id, e) = mutex.withLock {
            byPlay[jellyfinPlaySessionId]?.lastOrNull { i -> entries[i]?.let { it.deviceId.isNotBlank() && !it.download && it.prewarmSegment == null } == true }
                ?.let { it to entries.getValue(it) }
        } ?: return false
        if (!jobs.hasStream(id) || jobs.isRefused(id)) return false
        val key = prewarmKey(e.deviceId, e.itemId)
        stopFor(key)   // one prewarm per (device, item): the viewer's page prewarm, if any, is replaced by this stream
        val summary = jobs.warmStop(id) ?: return false
        runCatching { onSummary?.invoke(e.deviceId, e.itemId, e.qoeKey, summary) }
        val segment = (atMs.coerceAtLeast(0) / ENCODER_SEGMENT_MS).toInt()
        val kept = mutex.withLock {
            if (entries[id] !== e || byPlay[jellyfinPlaySessionId]?.remove(id) != true) return@withLock false
            if (byPlay[jellyfinPlaySessionId]?.isEmpty() == true) byPlay.remove(jellyfinPlaySessionId)
            e.prewarmSegment = segment
            e.keptWarm = true
            e.warmAtMs = clock()
            e.startMs = segment * ENCODER_SEGMENT_MS
            e.jellyfinPlaySessionId = key
            e.expiresAt = clock() + EncoderJobs.IDLE_MS
            byPlay.getOrPut(key) { mutableListOf() }.add(id)
            true
        }
        if (kept) Logger.info("encoder $id: stays warm as the prewarm for device=${e.deviceId} item=${e.itemId} at segment $segment (DEB-5647 FR-313-21)", "tv")
        return kept
    }

    /** DEB-5647 (FR-313-21) — the segment (device, item)'s prewarm waits at, or null when it has none. */
    internal suspend fun prewarmSegmentOf(deviceId: String, itemId: String): Int? = mutex.withLock {
        entries.values.firstOrNull { it.prewarmSegment != null && it.deviceId == deviceId && it.itemId == itemId }?.prewarmSegment
    }

    /** DEB-5647 (FR-313-21) — [deviceId] starts [itemId]: a stream kept warm for any other item of the device is stopped. Returns how many. */
    suspend fun stopKeptWarmExcept(deviceId: String, itemId: String): Int {
        val keys = mutex.withLock { entries.values.filter { it.keptWarm && it.prewarmSegment != null && it.deviceId == deviceId && it.itemId != itemId }.map { prewarmKey(it.deviceId, it.itemId) }.distinct() }
        keys.forEach { stopFor(it) }
        return keys.size
    }

    /** 309 — the viewer left the detail page without pressing Play: the prewarm stops at once (owner). */
    suspend fun cancelPrewarm(deviceId: String, itemId: String): Int = stopFor(prewarmKey(deviceId, itemId))

    // ── DEB-4062 / DEB-4256 (DEB-3995 FR-6) — a download stream: a copy a phone saves, which is no play but is admitted as one ──────

    /** A download stream as the policy ([DownloadStreams]) reads it. */
    class DownloadInfo(val id: String, val ownerDeviceId: String, val itemId: String, val lastReadMs: Long)

    /**
     * Registers [plan] as [ownerDeviceId]'s download stream for [itemId]; its job starts when the phone reads a segment (at the first
     * one it asks for, [startSegment] for the init). It has no play session, so no play's stop ends it ([stopFor]), no QoE row, no
     * encoder summary and no server-fault note (its entry names no device); it is memory only. One per phone (DEB-4256): the one the
     * same device holds is replaced (a resume, or its next download); other phones' streams are not touched, and there is no limit
     * server-wide — [planFor]'s budget is the only one.
     */
    suspend fun registerDownload(plan: EncoderPlan, ownerDeviceId: String, itemId: String, expiresAt: Long, startSegment: Int = 0): String {
        val id = DOWNLOAD_PREFIX + secureHexId()
        val replaced = mutableListOf<String>()
        mutex.withLock {
            val now = clock()
            entries.entries.removeAll { it.value.expiresAt < now }
            entries.filter { it.value.download && it.value.downloadOwner == ownerDeviceId }.keys.forEach { entries.remove(it); replaced += it }
            entries[id] = Entry(plan, id, expiresAt, itemId = itemId,
                startMs = encoderSeekSegment(plan.source.durationMs, startSegment * ENCODER_SEGMENT_MS) * ENCODER_SEGMENT_MS,
                downloadOwner = ownerDeviceId, lastReadMs = now).also { takePending(it, plan, now) }
        }
        replaced.forEach { jobs.stop(it, "the same phone asked for a new download stream") }
        Logger.info("encoder: download stream $id item=$itemId ${plan.codec} ${plan.rungs[0].height}p@${plan.rungs[0].videoBps / 1000}k from segment $startSegment (DEB-4062)", "tv")
        return id
    }

    /**
     * DEB-4256 (DEB-3995 FR-6) — the `streams` of a waiting `start`: how many jobs the encoder is running at this moment for plays and
     * for other phones' download streams. A prewarm is not one (it is stopped for a job that needs its slot), and neither is a stream
     * of [exceptDownloadOwner]'s own (its `start` replaces it).
     */
    suspend fun streamsBusy(exceptDownloadOwner: String): Int {
        val jobs = running()
        return mutex.withLock {
            jobs.count { j ->
                val e = entries[j.streamId]
                !j.side && (e == null || (e.prewarmSegment == null && e.downloadOwner != exceptDownloadOwner))
            }
        }
    }

    suspend fun downloadStreams(): List<DownloadInfo> = mutex.withLock {
        entries.filter { it.value.download }.map { (id, e) -> DownloadInfo(id, e.downloadOwner.orEmpty(), e.itemId, e.lastReadMs) }
    }

    /** Ends download stream [id]: its job is killed (nothing is awaited: the signal is sent now and the files go afterwards). False when it is not one. */
    suspend fun endDownload(id: String, why: String): Boolean {
        val was = mutex.withLock { if (entries[id]?.download == true) { entries.remove(id); true } else false }
        if (was) jobs.stop(id, why)
        return was
    }

    /** DEB-4062 — null when this encoder would serve [source] for a device of [deviceKind], else why not (the rule a play plans under). */
    internal suspend fun reasonNotServed(source: EncoderSource, deviceKind: String): String? {
        val cards = if (cfg.enabled) cards() else emptyList()
        return encoderDecision(cfg.enabled, ffmpegReady(), cards, source, isLiveOrAudio = false, deviceKind = deviceKind, castReceivers = cfg.castReceivers)
            ?: refused.reason(source.path, clock())
    }

    /** DEB-5646 (FR-313-20) — a side job never holds a slot a play needs; stopped after the prewarms. Returns how many. */
    internal suspend fun stopSideJobs(): Int = jobs.stopSideJobs()

    /** DEB-5685 (FR-313-27) — no `startRung`: `encoderCommand` never reads it, only the master's order does. */
    private fun samePlay(a: EncoderPlan, b: EncoderPlan): Boolean =
        a.source.path == b.source.path && a.codec == b.codec && a.mux == b.mux && a.rungs == b.rungs &&
            a.audio == b.audio && a.burnSubtitleOrder == b.burnSubtitleOrder && a.subtitles.map { it.name } == b.subtitles.map { it.name }

    private fun prewarmKey(deviceId: String, itemId: String) = "$PREWARM_PREFIX$deviceId:$itemId"

    // ── DEB-3100 (trouble-never-reaches-the-viewer FR-2) — a stream outlives a restart and the clock ──────────────

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var lastSweepMs = 0L

    /** Stores [e]'s plan under [id]. Called with the lock held. */
    private suspend fun persist(id: String, e: Entry, now: Long) {
        val store = plans ?: return
        e.persistedAtMs = now
        runCatching { StoredStream(e.plan, e.jellyfinPlaySessionId, e.deviceId, e.itemId, e.startMs, e.qoeKey) }
            .map { json.encodeToString(StoredStream.serializer(), it) }
            .onSuccess { store.put(id, KIND, e.jellyfinPlaySessionId, it, e.expiresAt, now) }
    }

    /** A read of a play's stream (not a prewarm): the ticket runs [StreamPlanStore.RENEW_MS] from now. Called with the lock held. */
    private suspend fun renew(id: String, e: Entry, now: Long) {
        if (e.prewarmSegment != null) return
        e.expiresAt = maxOf(e.expiresAt, now + StreamPlanStore.RENEW_MS)
        if (e.download) return   // DEB-4062 — a download stream is memory only: a restart ends it, and the phone asks `start` again
        if (plans != null && now - e.persistedAtMs >= StreamPlanStore.TOUCH_EVERY_MS) { e.persistedAtMs = now; plans.touch(id, e.expiresAt, now) }
    }

    /** The stream [id] from the store when the process does not know it; null when it was never stored, has ended or its ticket ran out. Lock held. */
    private suspend fun restore(id: String, now: Long): Entry? {
        val row = plans?.get(id, KIND) ?: return null
        if (row.expiresAt < now) return null
        val st = runCatching { json.decodeFromString(StoredStream.serializer(), row.payload) }.getOrNull() ?: return null
        val e = Entry(st.plan, st.playSessionId, row.expiresAt, st.deviceId, st.itemId, startMs = st.startMs, qoeKey = st.qoeKey, started = true)
        entries[id] = e
        byPlay.getOrPut(st.playSessionId) { mutableListOf() }.let { if (id !in it) it.add(id) }
        Logger.info("encoder: stream $id restored from the store (item=${st.itemId}) — its next request restarts the job (DEB-3100 FR-2)", "tv")
        return e
    }

    /**
     * What a job restart for [e] is placed as: through the normal placement (FR-313-8) at this moment, on fewer rungs when only that fits
     * ([restartPlan]). Null when the first start needs no placing (it was placed with the plan), or a job of this process still has the stream.
     */
    private suspend fun restartFor(id: String, e: Entry): EncoderJobs.Restart? {
        val was = mutex.withLock { e.started.also { e.started = true } }
        if (!was || jobs.hasStream(id)) return null
        // DEB-3104 (FR-7) — the stream is asked for again after a restart or after its jobs went: a stall around it is this server's.
        if (e.deviceId.isNotEmpty()) onServerFault?.invoke(e.deviceId)
        if (!cfg.enabled) return null
        val cards = cards()
        if (cards.isEmpty()) return null
        // DEB-5685 (FR-313-24) — the re-asks after a deploy are placed one after the other, each counting the one before.
        return placementMutex.withLock {
            val snap = loadSnapshot(null)
            val placement = placeJob(cards, snap.load, e.plan.codec, e.plan.rungs[0].boxHeight, e.plan.rungs.size)
            if (placement != null) mutex.withLock {
                sessionBumps[placement.card] = (sessionBumps[placement.card] ?: 0) + placement.rungs
                e.pendingCard = placement.card; e.pendingLoad = jobLoad(e.plan.codec, e.plan.rungs[0].boxHeight, placement.rungs); e.pendingAtMs = clock()
            }
            restartPlan(e.plan, placement)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Tests: register prewarms without starting ffmpeg. */
    internal var kickJobs: Boolean = true

    // ── 313d (FR-313-6) — WebVTT renditions ───────────────────────────────────────────────────────────────────

    private val vttCache = mutableMapOf<String, String>()

    suspend fun subtitlePlaylist(id: String, index: Int): String? {
        val e = entry(id) ?: return null
        if (index !in e.plan.subtitles.indices) return null
        return encoderSubtitlePlaylist(e.plan.source.durationMs)
    }

    /** The rendition's WebVTT: Jellyfin's conversion of that stream, fetched once and kept (at most 64 texts). */
    suspend fun subtitleVtt(id: String, index: Int): String? {
        val e = entry(id) ?: return null
        val t = e.plan.subtitles.getOrNull(index) ?: return null
        mutex.withLock { vttCache[t.sourceUrl] }?.let { return it }
        val text = fetchText?.invoke(t.sourceUrl)?.takeIf { it.trimStart().startsWith("WEBVTT") } ?: return null
        mutex.withLock {
            vttCache[t.sourceUrl] = text
            while (vttCache.size > 64) vttCache.remove(vttCache.keys.first())
        }
        return text
    }

    /** 313 (FR-313-13) — whether the play behind [jellyfinPlaySessionId] is served by our encoder. */
    suspend fun serves(jellyfinPlaySessionId: String): Boolean = mutex.withLock {
        if (byPlay[jellyfinPlaySessionId].isNullOrEmpty()) plans?.forPlay(jellyfinPlaySessionId, KIND)?.forEach { restore(it.id, clock()) }
        byPlay[jellyfinPlaySessionId]?.isNotEmpty() == true
    }

    fun recordFallback(reason: String) {
        fallbacks.addLast(nowMs() to reason)
        while (fallbacks.size > 200) fallbacks.removeFirst()
    }

    suspend fun owns(id: String): Boolean = entry(id) != null

    suspend fun master(id: String): String? = entry(id)?.let { encoderMaster(it.plan, if (it.download) 0 else it.startMs) }   // a download's master says no start: the phone saves the whole file

    /** 313 (found live 2026-10-09) — ffmpeg refused this stream's plan: its URLs answer 410 so the player gives up on
     *  it (and the client's watch restreams, which the server now answers from Jellyfin) instead of retrying a 404. */
    suspend fun refusedStream(id: String): Boolean = entry(id) != null && jobs.isRefused(id)

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
        if (e.download) e.lastReadMs = clock()
        return jobs.segment(id, e.plan, ffmpegPath, v, k, restartFor(id, e))?.also { if (!e.download) onSegmentServed?.invoke(e.jellyfinPlaySessionId) }
    }

    /** What an unanswered request for [id]'s variant means (the route's status). [k] is the segment asked for, null for the init. */
    enum class Miss { /** Not a stream of ours (or not a thing it has): 404. */ UNKNOWN, /** ffmpeg refused the plan: 410. */ GONE,
        /** A rung a restart left out: 404 for that rung. */ UNMADE, /** The job is running or will be again: 503, Retry-After 1. */ RETRY }

    suspend fun miss(id: String, kind: String, index: Int, k: Int?): Miss {
        val e = entry(id) ?: return Miss.UNKNOWN
        if (jobs.isRefused(id)) return Miss.GONE
        val v = variantOf(e.plan, kind, index) ?: return Miss.UNKNOWN
        if (k == null && e.plan.mux != EncoderMux.FMP4) return Miss.UNKNOWN
        if (k != null && (k < 0 || k >= encoderSegmentCount(e.plan.source.durationMs))) return Miss.UNKNOWN
        return if (jobs.makes(id, v)) Miss.RETRY else Miss.UNMADE
    }

    /** DEB-3100 (FR-8) — a segment request was answered: how long it took and whether it was a 503 (the encoder summary's figures). */
    suspend fun recordResponse(id: String, ms: Long, was503: Boolean) {
        jobs.recordResponse(id, ms, was503)
        // DEB-3104 (FR-7) — a segment this server did not have ready after its wait: the viewer's stall that follows is not the path's fault.
        if (was503) mutex.withLock { entries[id]?.deviceId }?.takeIf { it.isNotEmpty() }?.let { onServerFault?.invoke(it) }
    }

    /** DEB-3104 (FR-7) — told with the device id each time this server restarted a stream or answered a segment with 503 (see [ServerFaults]). */
    @kotlin.concurrent.Volatile
    var onServerFault: ((String) -> Unit)? = null

    /** DEB-3036 (trouble-never-reaches-the-viewer FR-1) — told with the play-session id each time a segment of its stream is
     *  read; a replacement uses the first one to release the stream it replaced. Cheap when nothing waits. */
    @kotlin.concurrent.Volatile
    var onSegmentServed: (suspend (String) -> Unit)? = null

    suspend fun init(id: String, kind: String, index: Int, nearSegment: Int? = null): String? {
        val e = entry(id) ?: return null
        if (e.plan.mux != EncoderMux.FMP4) return null
        val v = variantOf(e.plan, kind, index) ?: return null
        if (e.download) e.lastReadMs = clock()
        // 313 (found live 2026-10-09) — a player reads the init before its first segment: the job starts where the play
        // starts, not at 0:00 (a resumed play on the Mac and in Safari encoded from 0 first, then again at the seek).
        return jobs.init(id, e.plan, ffmpegPath, v, encoderSeekSegment(e.plan.source.durationMs, (nearSegment?.toLong() ?: (e.startMs / ENCODER_SEGMENT_MS)) * ENCODER_SEGMENT_MS), restartFor(id, e))   // DEB-3642 (FR-7) — never a segment past the last real one
    }

    /**
     * DEB-1432 (FR-313-7, build review item 11) — a job's fMP4 on the file's own clock, whichever job wrote it: the init
     * without its edit list, and the first bytes of a segment with `tfdt` moved by the delay that edit list held (see
     * `EncoderTimeline.kt`). Null when the file needs nothing (a TS job, or a track that starts at 0).
     */
    suspend fun initOnFileClock(path: String): ByteArray? = runCatching { fmp4WithoutEditList(FileIo.readBytes(Path(path))) }.getOrNull()

    suspend fun segmentHeadOnFileClock(path: String): ByteArray? {
        val ticks = jobs.trackStartTicks(path)
        if (ticks == 0L) return null
        val head = runCatching { FileIo.readHead(Path(path), TIMELINE_HEAD_BYTES) }.getOrNull() ?: return null
        return head.takeIf { fmp4ShiftBaseTime(it, ticks) }
    }

    fun mux(id: String): EncoderMux? = entries[id]?.plan?.mux

    /** Phase 180 / FR-313-1 — every stream (and job) registered for [jellyfinPlaySessionId]; returns how many. */
    suspend fun stopFor(jellyfinPlaySessionId: String): Int {
        // The session is over: its plans go from the store whether or not this process has them in memory (DEB-3100 FR-2).
        plans?.deleteForPlay(jellyfinPlaySessionId, KIND)
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
            // 313e — on by default: a machine with no GPU in the container never downloads the build (every
            // transcode there is Jellyfin's anyway, FR-313-11).
            if (cards().isEmpty()) { Logger.info("encoder: no GPU in the container — every transcode stays Jellyfin's (313)", "tv"); return@launch }
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

    /** The stream behind [id] (from the store when this process does not know it), its ticket renewed by this read; null when unknown or expired. */
    private suspend fun entry(id: String): Entry? = mutex.withLock {
        val now = clock()
        val e = entries[id]?.let { if (it.expiresAt >= now) it else return@withLock null } ?: restore(id, now) ?: return@withLock null
        renew(id, e, now)
        e
    }

    private fun run(cmd: String): String = memScoped {
        val pipe = popen(cmd, "r") ?: return@memScoped ""
        val buf = allocArray<ByteVar>(512)
        val sb = StringBuilder()
        while (fgets(buf, 512, pipe) != null) sb.append(buf.toKString())
        pclose(pipe)
        sb.toString()
    }

    companion object {
        /** The head of a segment read to move its `tfdt` ([segmentHeadOnFileClock]): `moof` is first and `tfdt` is near its start. */
        const val TIMELINE_HEAD_BYTES = 2048
        private const val KIND = "encoder"
        private const val PREWARM_PREFIX = "prewarm:"
        /** DEB-5685 (FR-313-24) — how long a placement with no job yet counts against the card. */
        private const val PENDING_MS = 15_000L
        /** DEB-4062 — a download stream's id starts with this; the routes answer an unknown one "waiting", never 404. */
        const val DOWNLOAD_PREFIX = "dl-"
        /** Pinned (FR-313-11): the same build Jellyfin's own container runs (8.1.2), portable GPL, verified by SHA-256. */
        const val JELLYFIN_FFMPEG_VERSION = "8.1.2-5"
        private const val JELLYFIN_FFMPEG_BASE = "https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/v$JELLYFIN_FFMPEG_VERSION/jellyfin-ffmpeg_${JELLYFIN_FFMPEG_VERSION}_portable"
        /** DEB-1490 FR-4 — the build for the architecture this binary was compiled for: `linux64` on x86-64, `linuxarm64` on arm64. */
        @OptIn(ExperimentalNativeApi::class)
        private val arm64 = Platform.cpuArchitecture == CpuArchitecture.ARM64
        val JELLYFIN_FFMPEG_URL: String = JELLYFIN_FFMPEG_BASE + (if (arm64) "_linuxarm64-gpl.tar.xz" else "_linux64-gpl.tar.xz")
        val JELLYFIN_FFMPEG_SHA256: String =
            if (arm64) "1bd4fafaf4c309cad896d3479152c31d6b7604d4be8b8526882ea16cc6d5f589"
            else "1fd859927053c44a4f2dbf67ae8b9ba8d29fb3b8930df0dd57d91aa60589363d"
    }
}

/**
 * DEB-3100 (FR-2) — the plan a restarted job runs: the card [placement] chose, and the rungs that fit there. Fewer rungs than the stream
 * has (the master still lists them all) keep the start rung's neighbours ([trimRungs]); the others answer 404 and a player moves on. Null
 * placement (no card has even one slot) leaves the plan as it was placed: a restart that has nowhere better to go still plays.
 */
internal fun restartPlan(plan: EncoderPlan, placement: EncoderPlacement?): EncoderJobs.Restart? {
    placement ?: return null
    if (placement.rungs >= plan.rungs.size) return EncoderJobs.Restart(plan.copy(cudaDevice = placement.card))
    val kept = trimRungs(plan.rungs, plan.startRung, placement.rungs)
    val keptIdx = kept.map { plan.rungs.indexOf(it) }
    return EncoderJobs.Restart(plan.copy(rungs = kept, startRung = kept.indexOf(plan.rungs[plan.startRung]).coerceAtLeast(0), cudaDevice = placement.card), plan.rungs.size, keptIdx)
}

/**
 * The picture a job reads, from our scan (no probe). DEB-3642 (FR-2) — the plan's picture is always a stream with packets: the first
 * video stream that has any, else (every one empty, or no timeline) the first as before. `0:v:N` counts every video stream of the
 * input, empty ones included. Null when the file has no video stream.
 */
internal fun encoderSourceOf(path: String, durationMs: Long?, tracks: List<Track>, sourceVideoRange: String?, emptyStreams: Set<Int> = emptySet(), frameRate: Double? = null): EncoderSource? {
    val videos = tracks.filter { it.kind == TrackKind.VIDEO }.sortedBy { it.streamIndex }
    val video = if (emptyStreams.isEmpty()) tracks.firstOrNull { it.kind == TrackKind.VIDEO } else videos.firstOrNull { it.streamIndex !in emptyStreams } ?: videos.firstOrNull()
    val videoOrder = if (emptyStreams.isEmpty()) 0 else videos.indexOf(video).coerceAtLeast(0)
    val range = sourceVideoRange.orEmpty()
    return video?.let {
        EncoderSource(
            path = path, durationMs = durationMs ?: 0L, videoCodec = it.codec, width = it.width ?: 0, height = it.height ?: 0,
            videoBps = it.videoBitrate?.toLong(),
            hdr = it.videoRange.equals("HDR", true) || range.contains("HDR", true) || range.contains("HLG", true) || range.startsWith("DOVI", true),
            hlg = range.contains("HLG", true),
            dolbyVisionProfile5 = range.equals("DOVI", true), videoOrder = videoOrder, frameRate = frameRate?.takeIf { it > 0 },
        )
    }
}

/** DEB-4062 (DEB-3995 FR-6) — whether [id] names a download stream (live or not). */
internal fun isDownloadStreamId(id: String): Boolean = id.startsWith(Encoder.DOWNLOAD_PREFIX)
