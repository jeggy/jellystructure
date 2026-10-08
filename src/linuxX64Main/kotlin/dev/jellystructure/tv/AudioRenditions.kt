package dev.jellystructure.tv

import dev.jellystructure.log.Logger
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.model.TrackKind
import dev.jellystructure.model.Track
import dev.jellystructure.shared.tv.AudioTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock

/**
 * R291 (FR-R291-2, mechanism 1) — every audio track of a transcode as an HLS rendition, so a player that
 * can switch renditions changes audio without a new stream.
 *
 * Jellyfin's HLS transcode carries one audio track. This composes the master playlist Jellyfin cannot: its
 * own video variant — with the audio the transcode already carries, muxed, as the default rendition — plus
 * one `EXT-X-MEDIA TYPE=AUDIO` per other track. Only a rendition the player selects is ever fetched, so an
 * unused track costs nothing.
 *
 * **The renditions are this server's own (2026-09-26).** They used to point at Jellyfin's
 * `/Audio/{id}/main.m3u8?AudioStreamIndex=N`, and that endpoint never maps the requested track: every
 * rendition job encoded the file's default audio (measured on the Pixel 9 and in Jellyfin's ffmpeg logs;
 * its source reads `mapArgs = state.IsOutputVideo ? … : string.Empty`). So each rendition is now
 * `audio/{position}/main.m3u8` next to the master, made by [AudioRenditionJobs] from the file on this
 * server's disk with the track mapped — relative, so it resolves against the address the player already
 * uses, and under the same capability id (see below).
 *
 * The playlists are served from `GET /api/tv/stream/{id}/…` (a player cannot attach a device token to an
 * HLS fetch, so the id is the capability: 128 random bits, living as long as the ticket).
 *
 * **308 — and a ladder of video variants.** For a player that adapts (`hls_adaptive`) and a transcode that re-encodes
 * the picture, the same master also lists the video variants of [LadderPlan] (see `VideoLadder.kt`), each Jellyfin's
 * own transcode under its own play session, started by Jellyfin only when the player reads it. The audio renditions,
 * when there are any, are shared by every variant (the carried track is muxed in each).
 */
class AudioRenditions(private val fetchPlaylist: suspend (String) -> String?) {
    /** [streamIndex] is Jellyfin's number for the track (it numbers external streams first); [audioOrder] is the
     *  track's place among the FILE's own audio streams, the only number ffmpeg may be given (R382). */
    data class Rendition(val position: Int, val streamIndex: Int, val label: String?, val language: String?, val uri: String?, val channels: Int? = null, val audioOrder: Int = position)

    /**
     * 308 (FR-308-1/-3) — one transcode's ladder: Jellyfin's negotiated URL as the [template] every variant is made
     * from, Jellyfin's own play session (each variant's is derived from it), the top variant's bitrate, the device's
     * decode ceiling and the bitrate budget its own measurements give (null: none measured yet).
     */
    class LadderPlan(val template: String, val jellyfinPlaySessionId: String, val topBps: Long, val ceilingBps: Long?, val budgetBps: Long?) {
        // Composed once, on the first master fetch: the order and the variants never change for this stream.
        internal var composed: List<Pair<MasterVariant, String>>? = null
        internal var topMaster: String? = null
    }

    private class Entry(val jellyfinMasterUrl: String, val renditions: List<Rendition>, val expiresAt: Long, val filePath: String, val durationMs: Long, val ladder: LadderPlan? = null) {
        // The codec the video variant's own audio is in, read from Jellyfin's master when it is first composed.
        var codec: String = "aac"
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val streamsByPlay = mutableMapOf<String, List<String>>()
    private val jobs by lazy { AudioRenditionJobs() }

    /**
     * Registers a master for one transcode; null when there is nothing to switch between (one audio
     * track, or the carried track is unknown), in which case the ticket keeps Jellyfin's own URL.
     * [jellyfinMasterUrl] is the absolute TranscodingUrl; [carriedIndex] the audio stream it carries;
     * [filePath] and [durationMs] the file on this server's disk the renditions are made from.
     *
     * 308 — [ladder] adds the video variants; [withRenditions] false (the player switches audio by restreaming, or
     * there is no file to make renditions from) registers the ladder alone. Null when neither applies.
     */
    suspend fun register(
        jellyfinPlaySessionId: String,
        jellyfinMasterUrl: String,
        audio: List<AudioTrack>,
        carriedIndex: Int?,
        expiresAt: Long,
        filePath: String,
        durationMs: Long,
        withRenditions: Boolean = true,
        ladder: LadderPlan? = null,
        /** R382 — the file's own tracks from our scan (ffprobe), to place each Jellyfin audio track in the file. */
        fileTracks: List<Track>? = null,
    ): String? {
        val carried = audio.indexOfFirst { it.index == carriedIndex }
        // R382 (FR-R382-2/-3) — every rendition needs its place among the file's own audio streams; no confident
        // match ⇒ no renditions (the switch then restreams, as on a player without them).
        val order = if (fileTracks != null) fileAudioOrder(audio, fileTracks) else audio.indices.toList()
        if (withRenditions && order == null && audio.size >= 2) Logger.info("audio renditions: Jellyfin's audio tracks don't match the file's (${audio.size} vs ${fileTracks?.count { it.kind == TrackKind.AUDIO }}): none offered (R382)", "tv")
        val renditions = if (!withRenditions || order == null || audio.size < 2 || carriedIndex == null || durationMs <= 0 || carried < 0) emptyList()
        else audio.mapIndexed { pos, a ->
            Rendition(pos, a.index, a.label, a.language, if (pos == carried) null else "audio/$pos/main.m3u8", a.channels, order[pos])
        }
        if (renditions.isEmpty() && ladder == null) return null
        val id = randomId()
        mutex.withLock {
            val now = nowMs()
            entries.entries.removeAll { it.value.expiresAt < now }
            entries[id] = Entry(jellyfinMasterUrl, renditions, expiresAt, filePath, durationMs, ladder)
            streamsByPlay[jellyfinPlaySessionId] = ((streamsByPlay[jellyfinPlaySessionId] ?: emptyList()) + id).distinct()
        }
        return id
    }

    /** The composed master for [id], or null when it is unknown, expired, or Jellyfin's own is unreachable. */
    suspend fun master(id: String): String? {
        val e = entry(id) ?: return null
        e.ladder?.let { return ladderMaster(e, it) }
        val jellyfin = fetchPlaylist(e.jellyfinMasterUrl) ?: return null
        e.codec = renditionAudioCodec(jellyfin)
        return composeMaster(jellyfin, variantBaseOf(e.jellyfinMasterUrl), e.renditions)
    }

    /** One rendition's playlist: the whole file in 3 s segments; null for an unknown id or the carried track. */
    suspend fun playlist(id: String, position: Int): String? {
        val e = entry(id) ?: return null
        if (e.renditions.getOrNull(position)?.uri == null) return null
        return renditionPlaylist(e.durationMs)
    }

    /** One segment of one rendition, made on demand; null when unknown or it could not be made in time. */
    suspend fun segment(id: String, position: Int, segment: Int): ByteArray? {
        val e = entry(id) ?: return null
        val r = e.renditions.getOrNull(position)?.takeIf { it.uri != null } ?: return null
        if (segment < 0 || segment * RENDITION_SEGMENT_MS >= e.durationMs) return null
        return jobs.segment("$id:$position", RenditionSource(e.filePath, r.audioOrder, r.channels, e.durationMs), e.codec, segment)
    }

    /**
     * 308 (FR-308-1) — the ladder master: the top variant is Jellyfin's negotiated transcode at the ladder's top
     * bitrate, each rung below it the same URL with its own bitrate, picture box and play session; every variant's
     * `BANDWIDTH` / `RESOLUTION` / `CODECS` are the ones Jellyfin itself writes for that request (fetching a master
     * starts no encode). A rung whose master cannot be had is left out; without the top there is no master.
     */
    private suspend fun ladderMaster(e: Entry, l: LadderPlan): String? {
        val variants = l.composed ?: run {
            val topUrl = variantUrl(l.template, variantSession(l.jellyfinPlaySessionId, 0), l.topBps, null)
            val top = fetchPlaylist(topUrl) ?: return null
            val topVariant = firstVariant(top) ?: return null
            val rungs = lowerRungs(l.topBps, topVariant.height ?: 1080, l.ceilingBps)
            val lower = coroutineScope {
                rungs.mapIndexed { i, r ->
                    async {
                        val url = variantUrl(l.template, variantSession(l.jellyfinPlaySessionId, i + 1), r.videoBps, r.height)
                        fetchPlaylist(url)?.let { firstVariant(it) }?.let { it to variantBaseOf(url) }
                    }
                }.awaitAll().filterNotNull()
            }
            val all = listOf(topVariant to variantBaseOf(topUrl)) + lower
            val ordered = startOrder(all.map { it.first.bandwidth }, l.budgetBps).map { all[it] }
            l.topMaster = top
            l.composed = ordered
            ordered
        }
        val top = l.topMaster ?: return null
        e.codec = renditionAudioCodec(top)
        return composeLadderMaster(top, variantBaseOf(e.jellyfinMasterUrl), variants, e.renditions)
    }

    /**
     * Phase 180 — stop every rendition job of every stream registered under [jellyfinPlaySessionId]; returns the
     * play sessions of the ladder variants those streams composed (308), which the caller stops in Jellyfin one by
     * one — one `DELETE /Videos/ActiveEncodings` stops one job (R291's measurement).
     */
    suspend fun stopFor(jellyfinPlaySessionId: String): List<String> {
        val ids = mutex.withLock { streamsByPlay.remove(jellyfinPlaySessionId) } ?: return emptyList()
        ids.forEach { jobs.stopStream(it) }
        return mutex.withLock {
            ids.flatMap { id ->
                val l = entries[id]?.ladder ?: return@flatMap emptyList()
                val n = l.composed?.size ?: 0
                (0 until n).map { variantSession(l.jellyfinPlaySessionId, it) }
            }
        }
    }

    private suspend fun entry(id: String): Entry? = mutex.withLock { entries[id]?.takeIf { it.expiresAt >= nowMs() } }

    private fun randomId(): String = buildString { repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) } }
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** The directory a relative URI in Jellyfin's master resolves against: `…/videos/{id}/`. */
internal fun variantBaseOf(masterUrl: String): String = masterUrl.substringBefore('?').substringBeforeLast('/') + "/"

/**
 * The master a player reads: Jellyfin's own lines, with every relative URI made absolute against
 * [variantBase], each variant joined to the `aud` group, and one `EXT-X-MEDIA` per audio track ahead of
 * them — the carried one without a URI (it is muxed in the variant) as the only DEFAULT, the others with
 * AUTOSELECT=NO so no player swaps track on its own by the device's locale. NAME is `a{position} {label}`:
 * unique (a player merges renditions that share a NAME) and a stable key the player maps back to the
 * ticket's audio position.
 *
 * Every rendition is made in the codec the variant's own audio is in ([renditionAudioCodec], recorded by
 * [AudioRenditions.master] for [AudioRenditionJobs]).
 * An `EXT-X-MEDIA` tag has no CODECS of its own, so a player takes every rendition's format from the
 * variant's CODECS — measured on the stue TV 2026-09-26: a start that carried an AC3 track (Jellyfin copies
 * it, `ac-3`) with AAC renditions failed the first switch with Media3's *"Unable to bind a sample queue to
 * TrackGroup with MIME type audio/ac3"*. Starts whose carried track Jellyfin re-encodes (TrueHD, DTS →
 * `mp4a`) had worked only because they happened to agree.
 */
internal fun composeMaster(jellyfinMaster: String, variantBase: String, renditions: List<AudioRenditions.Rendition>): String {
    fun abs(uri: String) = absoluteUri(uri, variantBase)
    val out = StringBuilder("#EXTM3U\n")
    appendRenditions(out, renditions)
    for (line in jellyfinMaster.lines()) {
        when {
            line.isBlank() || line == "#EXTM3U" -> continue
            line.startsWith("#EXT-X-STREAM-INF:") -> out.append(line).append(if (line.contains("AUDIO=")) "" else ",AUDIO=\"aud\"").append('\n')
            line.startsWith("#") -> out.append(Regex("URI=\"([^\"]+)\"").replace(line) { m -> "URI=\"${abs(m.groupValues[1])}\"" }).append('\n')
            else -> out.append(abs(line)).append('\n')
        }
    }
    return out.toString()
}

private fun absoluteUri(uri: String, base: String) = if (uri.startsWith("http://") || uri.startsWith("https://")) uri else base + uri.removePrefix("/")
private fun quoted(s: String) = s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')

/** R291 — one `EXT-X-MEDIA` per audio track, the carried one (no URI) the only DEFAULT; see [composeMaster]. */
private fun appendRenditions(out: StringBuilder, renditions: List<AudioRenditions.Rendition>) {
    for (r in renditions) {
        out.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"").append(quoted("a${r.position} ${r.label ?: r.language ?: ""}".trim())).append('"')
        r.language?.takeIf { it.isNotBlank() }?.let { out.append(",LANGUAGE=\"").append(quoted(it)).append('"') }
        if (r.uri == null) out.append(",DEFAULT=YES,AUTOSELECT=YES")
        else out.append(",DEFAULT=NO,AUTOSELECT=NO,URI=\"").append(quoted(r.uri)).append('"')
        out.append('\n')
    }
}

/**
 * 308 (FR-308-1) — the ladder master: R291's audio renditions (when there are any), the top transcode's own tags
 * other than its variants (its subtitle renditions, its version) once, then one `EXT-X-STREAM-INF` per variant in
 * [variants]' order — each Jellyfin's own line for that variant, joined to the `aud` group when there are
 * renditions — with every URI made absolute against the base its own master was fetched from.
 */
internal fun composeLadderMaster(topMaster: String, topBase: String, variants: List<Pair<MasterVariant, String>>, renditions: List<AudioRenditions.Rendition>): String {
    val out = StringBuilder("#EXTM3U\n")
    appendRenditions(out, renditions)
    for (line in topMaster.lines()) {
        when {
            line.isBlank() || line == "#EXTM3U" || line.startsWith("#EXT-X-STREAM-INF:") || !line.startsWith("#") -> continue
            else -> out.append(Regex("URI=\"([^\"]+)\"").replace(line) { m -> "URI=\"${absoluteUri(m.groupValues[1], topBase)}\"" }).append('\n')
        }
    }
    for ((v, base) in variants) {
        out.append(v.streamInf)
        if (renditions.isNotEmpty() && !v.streamInf.contains("AUDIO=")) out.append(",AUDIO=\"aud\"")
        out.append('\n').append(absoluteUri(v.uri, base)).append('\n')
    }
    return out.toString()
}

/**
 * The Jellyfin `AudioCodec` that matches the audio in the variant's CODECS — the codecs Jellyfin puts in a
 * TS segment: `mp4a.40.34` (MP3) · `mp4a` (AAC) · `ac-3` · `ec-3`. Anything else, or no audio codec at all,
 * is asked for as AAC, which is what Jellyfin re-encodes to when it cannot copy.
 */
internal fun renditionAudioCodec(jellyfinMaster: String): String {
    val codecs = Regex("CODECS=\"([^\"]*)\"").find(jellyfinMaster.lines().firstOrNull { it.startsWith("#EXT-X-STREAM-INF:") } ?: "")
        ?.groupValues?.get(1)?.split(',')?.map { it.trim().lowercase() } ?: emptyList()
    return when {
        codecs.any { it == "mp4a.40.34" || it == "mp4a.6b" } -> "mp3"
        codecs.any { it == "ac-3" } -> "ac3"
        codecs.any { it == "ec-3" } -> "eac3"
        else -> "aac"
    }
}

/**
 * R382 (FR-R382-2) — each Jellyfin audio track's place among the file's own audio streams, or null when they can't be
 * matched with confidence. Jellyfin numbers external streams first (2026-10-08: a film with three external subtitles
 * listed its embedded audio as 4 and 5, which are 1 and 2 in the file, so `-map 0:4` picked a PGS subtitle). Embedded
 * audio keeps the file's order in Jellyfin's list, so the n-th Jellyfin audio track is the file's n-th, provided the two
 * lists have the same length and agree track by track on codec and language. An external audio file (phase 314) makes
 * the lists differ, and then nothing is guessed.
 */
internal fun fileAudioOrder(jellyfin: List<AudioTrack>, file: List<Track>): List<Int>? {
    val fileAudio = file.filter { it.kind == TrackKind.AUDIO }.sortedBy { it.streamIndex }
    if (fileAudio.size != jellyfin.size) return null
    for ((i, a) in jellyfin.withIndex()) {
        val f = fileAudio[i]
        if (!sameAudioCodec(a.codec, f.codec)) return null
        val la = a.language?.let { LanguageResolver.toIso6392(it) }
        val lf = f.language?.let { LanguageResolver.toIso6392(it) }
        if (la != null && lf != null && la != lf) return null
    }
    return jellyfin.indices.toList()
}

private fun sameAudioCodec(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return true
    fun n(c: String) = when (val x = c.lowercase()) { "dca" -> "dts"; "e-ac-3", "ec-3" -> "eac3"; "a_aac", "mp4a" -> "aac"; else -> x }
    return n(a) == n(b)
}
