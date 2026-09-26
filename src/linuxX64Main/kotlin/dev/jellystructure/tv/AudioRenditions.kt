package dev.jellystructure.tv

import dev.jellystructure.shared.tv.AudioTrack
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
 */
class AudioRenditions(private val fetchPlaylist: suspend (String) -> String?) {
    data class Rendition(val position: Int, val streamIndex: Int, val label: String?, val language: String?, val uri: String?, val channels: Int? = null)
    private class Entry(val jellyfinMasterUrl: String, val renditions: List<Rendition>, val expiresAt: Long, val filePath: String, val durationMs: Long) {
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
     */
    suspend fun register(
        jellyfinPlaySessionId: String,
        jellyfinMasterUrl: String,
        audio: List<AudioTrack>,
        carriedIndex: Int?,
        expiresAt: Long,
        filePath: String,
        durationMs: Long,
    ): String? {
        if (audio.size < 2 || carriedIndex == null || durationMs <= 0) return null
        val carried = audio.indexOfFirst { it.index == carriedIndex }.takeIf { it >= 0 } ?: return null
        val renditions = audio.mapIndexed { pos, a ->
            Rendition(pos, a.index, a.label, a.language, if (pos == carried) null else "audio/$pos/main.m3u8", a.channels)
        }
        val id = randomId()
        mutex.withLock {
            val now = nowMs()
            entries.entries.removeAll { it.value.expiresAt < now }
            entries[id] = Entry(jellyfinMasterUrl, renditions, expiresAt, filePath, durationMs)
            streamsByPlay[jellyfinPlaySessionId] = ((streamsByPlay[jellyfinPlaySessionId] ?: emptyList()) + id).distinct()
        }
        return id
    }

    /** The composed master for [id], or null when it is unknown, expired, or Jellyfin's own is unreachable. */
    suspend fun master(id: String): String? {
        val e = entry(id) ?: return null
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
        return jobs.segment("$id:$position", RenditionSource(e.filePath, r.streamIndex, r.channels, e.durationMs), e.codec, segment)
    }

    /** Phase 180 — stop every rendition job of every stream registered under [jellyfinPlaySessionId]. */
    suspend fun stopFor(jellyfinPlaySessionId: String) {
        val ids = mutex.withLock { streamsByPlay.remove(jellyfinPlaySessionId) } ?: return
        ids.forEach { jobs.stopStream(it) }
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
    fun abs(uri: String) = if (uri.startsWith("http://") || uri.startsWith("https://")) uri else variantBase + uri.removePrefix("/")
    fun quoted(s: String) = s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')
    val out = StringBuilder("#EXTM3U\n")
    for (r in renditions) {
        out.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"").append(quoted("a${r.position} ${r.label ?: r.language ?: ""}".trim())).append('"')
        r.language?.takeIf { it.isNotBlank() }?.let { out.append(",LANGUAGE=\"").append(quoted(it)).append('"') }
        if (r.uri == null) out.append(",DEFAULT=YES,AUTOSELECT=YES")
        else out.append(",DEFAULT=NO,AUTOSELECT=NO,URI=\"").append(quoted(r.uri)).append('"')
        out.append('\n')
    }
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
