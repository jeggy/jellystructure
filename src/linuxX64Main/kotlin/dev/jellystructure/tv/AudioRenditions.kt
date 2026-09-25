package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.auth.withJellyfinToken
import dev.jellystructure.shared.tv.AudioTrack
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock

/**
 * R291 (FR-R291-2, mechanism 1) — every audio track of a transcode as an HLS rendition, so a player that
 * can switch renditions changes audio without a new stream.
 *
 * Jellyfin's HLS transcode carries one audio track, but it serves any audio track of the file on its own
 * (`/Audio/{id}/main.m3u8?AudioStreamIndex=N`, measured 2026-09-24). This composes the master playlist
 * Jellyfin cannot: its own video variant — with the audio the transcode already carries, muxed, as the
 * default rendition — plus one `EXT-X-MEDIA TYPE=AUDIO` per other track pointing at that audio-only
 * playlist. Only a rendition the player selects is ever fetched, so an unused track costs nothing.
 *
 * Two facts, measured on the household Jellyfin 12.1 before any of this was written:
 *  - **Each rendition needs its own `PlaySessionId`.** Jellyfin keys a job's output on media path · user
 *    agent · device · play session; an audio request under the video's own session is served the video
 *    job's segment. And one `DELETE /Videos/ActiveEncodings` stops one job, so [sessionsFor] hands phase
 *    180's teardown every rendition session it minted, to stop one by one.
 *  - The audio-only renditions run +9.957 s from media time against the video's +10.000 s, a constant
 *    43 ms lead — two AAC frames — inside lip-sync tolerance.
 *
 * The playlist itself is served from `GET /api/tv/stream/{id}/master.m3u8` (a player cannot attach a
 * device token to an HLS fetch, so the id is the capability: 128 random bits, living as long as the
 * ticket). Everything it lists is Jellyfin's own URL, carrying Jellyfin's credential exactly as the
 * ticket's `hls_url` already does — no new exposure.
 */
class AudioRenditions(private val fetchPlaylist: suspend (String) -> String?) {
    data class Rendition(val position: Int, val streamIndex: Int, val label: String?, val language: String?, val uri: String?)
    private class Entry(val jellyfinMasterUrl: String, val renditions: List<Rendition>, val expiresAt: Long)

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val sessionsByPlay = mutableMapOf<String, List<String>>()

    /**
     * Registers a master for one transcode; null when there is nothing to switch between (one audio
     * track, or the carried track is unknown), in which case the ticket keeps Jellyfin's own URL.
     * [jellyfinMasterUrl] is the absolute TranscodingUrl; [carriedIndex] the audio stream it carries.
     */
    suspend fun register(
        jellyfinBase: String,
        jellyfinId: String,
        mediaSourceId: String,
        token: String,
        identity: JellyfinDeviceIdentity,
        jellyfinPlaySessionId: String,
        jellyfinMasterUrl: String,
        audio: List<AudioTrack>,
        carriedIndex: Int?,
        expiresAt: Long,
    ): String? {
        if (audio.size < 2 || carriedIndex == null) return null
        val carried = audio.indexOfFirst { it.index == carriedIndex }.takeIf { it >= 0 } ?: return null
        val renditions = audio.mapIndexed { pos, a ->
            val uri = if (pos == carried) null else withJellyfinToken(
                "$jellyfinBase/Audio/$jellyfinId/main.m3u8?MediaSourceId=${mediaSourceId.encodeURLParameter()}" +
                    "&AudioStreamIndex=${a.index}&AudioCodec=aac&SegmentContainer=ts&MaxAudioChannels=6" +
                    "&PlaySessionId=${renditionSession(jellyfinPlaySessionId, a.index).encodeURLParameter()}" +
                    "&DeviceId=${identity.deviceId.encodeURLParameter()}",
                token,
            )
            Rendition(pos, a.index, a.label, a.language, uri)
        }
        val id = randomId()
        mutex.withLock {
            val now = nowMs()
            entries.entries.removeAll { it.value.expiresAt < now }
            entries[id] = Entry(jellyfinMasterUrl, renditions, expiresAt)
            sessionsByPlay[jellyfinPlaySessionId] =
                ((sessionsByPlay[jellyfinPlaySessionId] ?: emptyList()) + renditions.filter { it.uri != null }.map { renditionSession(jellyfinPlaySessionId, it.streamIndex) }).distinct()
        }
        return id
    }

    /** The composed master for [id], or null when it is unknown, expired, or Jellyfin's own is unreachable. */
    suspend fun master(id: String): String? {
        val e = mutex.withLock { entries[id]?.takeIf { it.expiresAt >= nowMs() } } ?: return null
        val jellyfin = fetchPlaylist(e.jellyfinMasterUrl) ?: return null
        return composeMaster(jellyfin, variantBaseOf(e.jellyfinMasterUrl), e.renditions)
    }

    /** Phase 180 — every rendition session minted for [jellyfinPlaySessionId], forgotten as it is handed over. */
    suspend fun sessionsFor(jellyfinPlaySessionId: String): List<String> =
        mutex.withLock { sessionsByPlay.remove(jellyfinPlaySessionId) ?: emptyList() }

    private fun randomId(): String = buildString { repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) } }
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** A rendition's own play session: the video's, suffixed — unique per track, and readable in Jellyfin's logs. */
internal fun renditionSession(jellyfinPlaySessionId: String, streamIndex: Int) = "${jellyfinPlaySessionId}a$streamIndex"

/** The directory a relative URI in Jellyfin's master resolves against: `…/videos/{id}/`. */
internal fun variantBaseOf(masterUrl: String): String = masterUrl.substringBefore('?').substringBeforeLast('/') + "/"

/**
 * The master a player reads: Jellyfin's own lines, with every relative URI made absolute against
 * [variantBase], each variant joined to the `aud` group, and one `EXT-X-MEDIA` per audio track ahead of
 * them — the carried one without a URI (it is muxed in the variant) as the only DEFAULT, the others with
 * AUTOSELECT=NO so no player swaps track on its own by the device's locale. NAME is `a{position} {label}`:
 * unique (a player merges renditions that share a NAME) and a stable key the player maps back to the
 * ticket's audio position.
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
