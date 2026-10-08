package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinMediaSourceInfo
import dev.jellystructure.auth.JellyfinMediaStream
import dev.jellystructure.filefix.DV_VERSION_LABEL
import dev.jellystructure.filefix.JellyfinAudio
import dev.jellystructure.filefix.copySourcesOf
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.VideoVersion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 314b/c — what a play needs from the files phase 314 adds: the audio list in the order a player sees it, each
 * copy folded onto its source, a sidecar's URL, and which picture version a device plays.
 */

/**
 * Phase 314b (FR-314-4/-6) — the ticket's audio list. Jellyfin lists an external audio file (a `.mka` sidecar) BEFORE
 * the container's own audio; a player maps the ticket by position onto the tracks it sees — the container's, then any
 * file it merged — so the container's tracks come first, in their order, and external tracks after them.
 *
 * [includeExternal] false drops external tracks (a direct play to a client that can't open a sidecar: its player would
 * never see them, and a listed-but-absent track is a row that does nothing). [externalUrls] gives an external track's
 * URL by Jellyfin index on a direct play (none on a transcode, where Jellyfin's stream carries the track).
 */
internal fun ticketAudioTracks(
    streams: List<JellyfinMediaStream>,
    includeExternal: Boolean,
    externalUrls: Map<Int, String>,
): List<AudioTrack> {
    val audio = streams.filter { it.type.equals("Audio", ignoreCase = true) }
    val copies = copySourcesOf(audio.map { JellyfinAudio(it.index, it.codec, it.language, it.title, it.isDefault) })
    val ordered = audio.filter { !it.isExternal } + (if (includeExternal) audio.filter { it.isExternal } else emptyList())
    return ordered.map { s ->
        AudioTrack(
            index = s.index,
            language = s.language,
            // DisplayTitle is the fullest human string Jellyfin composes (lang + title + codec + layout, e.g.
            // "Dansk - Synstolkning - Dolby Digital - 5.1"); fall back to the raw Title, then to a composed
            // language+codec, then language alone.
            label = s.displayTitle?.takeIf { it.isNotBlank() }
                ?: s.title?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(s.language, s.codec?.uppercase()).joinToString(" · ").ifBlank { null },
            codec = s.codec,
            channels = s.channels,
            isDefault = s.isDefault,
            external = s.isExternal,
            externalUrl = if (s.isExternal) externalUrls[s.index] else null,
            copyOf = copies[s.index]?.takeIf { src -> ordered.any { it.index == src } },
        )
    }
}

/**
 * Phase 314b — the sidecar on this server's disk for an external stream Jellyfin lists. Jellyfin, Bazarr and
 * jellystructure mount the media under different roots (phase 273), so the file is found by name beside the video's
 * own local path; null when the stream has no path or is not a `.mka` (only phase 314's sidecars are served).
 */
internal fun localSidecarPath(videoLocalPath: String, jellyfinStreamPath: String?): String? {
    val name = jellyfinStreamPath?.substringAfterLast('/')?.takeIf { it.endsWith(".mka", ignoreCase = true) } ?: return null
    if (name.contains("..")) return null
    return videoLocalPath.substringBeforeLast('/') + "/" + name
}

/**
 * Phase 314c (FR-314-5) — the picture version a play uses. [requested] (a restream naming a version) wins when it is
 * one of the film's sources; otherwise a device that can't decode a dual-layer Dolby Vision original gets the profile
 * 8.1 version, and every other device the original (Jellyfin's first source). Null when the film has one source.
 */
internal fun chooseMediaSource(sources: List<JellyfinMediaSourceInfo>, requested: String?, deviceDecodesEl: Boolean): String? {
    val ids = sources.mapNotNull { it.id }
    if (ids.size < 2) return null
    if (requested != null && requested in ids) return requested
    val version = sources.firstOrNull { isDvVersion(it) }?.id
    return if (!deviceDecodesEl && version != null) version else sources.firstOrNull { !isDvVersion(it) }?.id ?: ids.first()
}

private fun isDvVersion(s: JellyfinMediaSourceInfo): Boolean =
    s.path?.substringAfterLast('/')?.substringBeforeLast('.')?.endsWith(" - $DV_VERSION_LABEL") == true ||
        s.name?.trim()?.equals(DV_VERSION_LABEL, ignoreCase = true) == true

/**
 * Phase 314c — the versions in plain words for the picker: our profile-8.1 version reads *Dolby Vision*, the original
 * of such a film *Dolby Vision, full detail* (its enhancement layer); any other version keeps Jellyfin's own name.
 * Empty when the film has one source.
 */
internal fun ticketVersions(sources: List<JellyfinMediaSourceInfo>, current: String?): List<VideoVersion> {
    val withId = sources.filter { it.id != null }
    if (withId.size < 2) return emptyList()
    val hasDv = withId.any { isDvVersion(it) }
    return withId.map { s ->
        val label = when {
            isDvVersion(s) -> DV_VERSION_LABEL
            hasDv -> "$DV_VERSION_LABEL, full detail"
            else -> s.name?.takeIf { it.isNotBlank() } ?: s.path?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Version"
        }
        VideoVersion(id = s.id!!, label = label, current = s.id == current)
    }
}

/**
 * Phase 314b — sidecar files a direct play may fetch, under `/api/tv/stream/{id}/sidecar.mka`. Public like every
 * stream path (a player can't attach a token), so the id is the capability: 128 random bits ([secureHexId]), living as
 * long as the ticket. Only paths registered from a ticket are served.
 */
class SidecarStreams {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Pair<String, Long>>()

    suspend fun register(localPath: String, expiresAt: Long, now: Long): String = mutex.withLock {
        entries.entries.removeAll { it.value.second < now }
        entries.entries.firstOrNull { it.value.first == localPath && it.value.second >= expiresAt }?.let { return@withLock it.key }
        val id = secureHexId()
        entries[id] = localPath to expiresAt
        id
    }

    suspend fun path(id: String, now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): String? =
        mutex.withLock { entries[id]?.takeIf { it.second >= now }?.first }
}

/**
 * One `Range: bytes=a-b` (or `a-`, or `-n`) over a file of [size] bytes, as an inclusive range; null when absent,
 * malformed, a multi-range, or unsatisfiable (the caller then answers 416 or the whole file).
 */
internal fun parseByteRange(header: String?, size: Long): LongRange? {
    val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.trim() ?: return null
    if (spec.contains(',') || size <= 0) return null
    val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return null
    val a = spec.substring(0, dash).trim()
    val b = spec.substring(dash + 1).trim()
    return when {
        a.isEmpty() -> b.toLongOrNull()?.takeIf { it > 0 }?.let { n -> (size - n).coerceAtLeast(0)..(size - 1) }
        else -> {
            val start = a.toLongOrNull() ?: return null
            val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(size - 1)
            if (start > end || start >= size) null else start..end
        }
    }
}
