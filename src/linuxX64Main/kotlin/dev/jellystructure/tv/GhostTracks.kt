package dev.jellystructure.tv

import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.media.TrackCoverage
import dev.jellystructure.shared.tv.AudioTrack

/**
 * DEB-3652 (a-damaged-file-still-plays, DEB-3642 FR-2–FR-5) — what the file checks say about a file, as the playback
 * plan reads them. Implemented over the stored deep check; a test hands in a fake.
 */
interface PlaybackFileChecks {
    /** The stream indexes of [path] that carry no packet (FR-1), or null while the file has no current packet timeline. */
    fun emptyStreams(path: String): Set<Int>?

    /** FR-4 — the current deep check found damage at a byte position before the file's first Cluster. */
    fun damagedBeforeFirstCluster(path: String): Boolean

    /** FR-5 — the file has a current deep check (damage and packet timeline both). */
    fun hasCurrentDeepCheck(path: String): Boolean

    /** FR-5 — put the file's `verify_files` job at the head of the queue (still deferred while a play is on). */
    fun verifyFirst(path: String)
}

/**
 * What the checks make of one file for one negotiation: the streams to leave out ([empty]) and whether Jellyfin is asked
 * with direct play and direct stream off ([noDirect], FR-4). Per file, never written to a device's record.
 */
internal data class GhostFacts(val empty: Set<Int> = emptySet(), val noDirect: Boolean = false) {
    companion object { val NONE = GhostFacts() }
}

/** FR-4 — a picture or sound stream the file lists and holds no packet of; an empty subtitle never counts, nor does a cover picture. */
internal fun emptyAvStreams(tracks: List<Track>, empty: Set<Int>): Set<Int> =
    tracks.filter { it.streamIndex in empty && (it.kind == TrackKind.AUDIO || (it.kind == TrackKind.VIDEO && it.codec.lowercase() !in TrackCoverage.IMAGE_CODECS)) }
        .mapTo(mutableSetOf()) { it.streamIndex }

/** FR-4 — a header-damaged file: an empty picture or sound stream, or damage before the first Cluster. */
internal fun ghostFactsFrom(tracks: List<Track>, empty: Set<Int>?, damagedBeforeCluster: Boolean): GhostFacts {
    val ghosts = empty.orEmpty()
    return GhostFacts(ghosts, noDirect = damagedBeforeCluster || emptyAvStreams(tracks, ghosts).isNotEmpty())
}

/** The track [index] resolved: which audio track to carry, and which empty one it replaced (for the log line). */
internal data class AudioPick(val index: Int?, val replaced: Int? = null) {
    /** FR-2 — logged once per play, where the swap is made. */
    val logLine: String? get() = if (replaced != null && index != null) "audio $replaced holds no packets — carrying $index" else null
}

/**
 * FR-2/FR-3 — the audio track a negotiation or a plan carries. A track with packets stays as it is. An empty one is
 * swapped for its **twin**, the first non-empty track with the same codec, language and channel layout, else the default
 * non-empty track (else the first). No pick (null) becomes the default non-empty track when the file has empty streams, so
 * Jellyfin is never left to choose a ghost. A file with no empty stream returns [wanted] untouched.
 */
internal fun pickRealAudio(audio: List<AudioTrack>, wanted: Int?, empty: Set<Int>): AudioPick {
    if (empty.isEmpty()) return AudioPick(wanted)
    val real = audio.filter { it.index !in empty }
    if (real.isEmpty()) return AudioPick(wanted)
    if (wanted != null && wanted !in empty) return AudioPick(wanted)
    val fallback = real.firstOrNull { it.isDefault } ?: real.first()
    if (wanted == null) return AudioPick(fallback.index)
    val ghost = audio.firstOrNull { it.index == wanted }
    val twin = ghost?.let { g -> real.firstOrNull { sameAudio(it, g) } }
    return AudioPick((twin ?: fallback).index, replaced = wanted)
}

private fun sameAudio(a: AudioTrack, b: AudioTrack): Boolean {
    if (a.codec.orEmpty().lowercase() != b.codec.orEmpty().lowercase()) return false
    if (a.channels != b.channels) return false
    val la = a.language?.takeIf { it.isNotBlank() }
    val lb = b.language?.takeIf { it.isNotBlank() }
    return if (la != null && lb != null) LanguageResolver.sameLanguage(la, lb) else la == lb
}
