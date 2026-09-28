package dev.jellystructure.model

import kotlinx.serialization.Serializable

/**
 * Phase 275 — the music library as jellystructure keeps it: artists, albums and tracks, each keyed by its
 * Jellyfin item id. Deliberately **not** a [MediaKind]: an album has three levels and many-to-many credits, and
 * every film/series path branches on [MediaKind] with an `else` that would treat an album as a film (168's
 * blast radius). These rows are read only by music code.
 *
 * Every field the scan fills comes from Jellyfin; everything a match (276) or an admin decides is carried over
 * by the scan untouched (`MusicIngest.merge`). JSON-blob rows: adding a field needs no migration.
 */
@Serializable
data class MusicCredit(
    /** The Jellyfin MusicArtist id. */
    val artistId: String,
    val name: String,
)

/** Match states shared by albums, artists and (per track) recordings. */
object MusicMatch {
    const val MATCHED = "matched"
    /** Candidates were found but none clearly won — the admin chooses (276 FR-276-3). */
    const val NEEDS_YOU = "needs_you"
    const val UNMATCHED = "unmatched"
}

/** Where an album's cover (or an artist's picture) comes from. */
object MusicArt {
    /** A file jellystructure (or the admin) put in the folder — `cover.jpg`, `folder.jpg`… */
    const val FILE = "file"
    /** No file, but Jellyfin has an image (usually art embedded in a track). */
    const val JELLYFIN = "jellyfin"
    const val NONE = "none"
}

@Serializable
data class MusicArtist(
    val id: String,
    val libraryId: String? = null,
    val name: String,
    val sortName: String? = null,
    /** The artist's folder on jellystructure's side; null for an artist Jellyfin knows only as a credit. */
    val path: String? = null,
    val jellyfinProviderIds: Map<String, String> = emptyMap(),
    val imageState: String = MusicArt.NONE,
    // ── Phase 276: MusicBrainz ──
    val mbid: String? = null,
    val matchState: String = MusicMatch.UNMATCHED,
    val matchLocked: Boolean = false,
    val addedAt: Long? = null,
    /** Set when a scan no longer finds the artist in Jellyfin; the row is kept (the films' rule). */
    val missingSince: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class MusicAlbum(
    val id: String,
    val libraryId: String? = null,
    val title: String,
    val sortName: String? = null,
    val year: Int? = null,
    /** The album folder on jellystructure's side. */
    val path: String? = null,
    val albumArtists: List<MusicCredit> = emptyList(),
    /** The files' own genre tags (MusicBrainz's votes arrive with 276). */
    val genres: List<String> = emptyList(),
    /** Tracks of this album present on disk, and their summed length. */
    val trackCount: Int = 0,
    val durationMs: Long = 0,
    /** Jellyfin's `AlbumNormalizationGain` (dB, from its LUFS scan). */
    val albumGainDb: Double? = null,
    val coverState: String = MusicArt.NONE,
    val jellyfinProviderIds: Map<String, String> = emptyMap(),
    // ── Phase 276: MusicBrainz ──
    val releaseGroupMbid: String? = null,
    val releaseMbid: String? = null,
    val matchState: String = MusicMatch.UNMATCHED,
    val matchLocked: Boolean = false,
    val addedAt: Long? = null,
    val missingSince: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class MusicTrack(
    val id: String,
    val albumId: String? = null,
    val libraryId: String? = null,
    val title: String,
    val sortName: String? = null,
    /** `ParentIndexNumber` — the disc; null when the tags say nothing. */
    val disc: Int? = null,
    /** `IndexNumber` — the track's position on its disc. */
    val position: Int? = null,
    val durationMs: Long? = null,
    val year: Int? = null,
    val path: String? = null,
    val container: String? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val artists: List<MusicCredit> = emptyList(),
    val genres: List<String> = emptyList(),
    /** Jellyfin's `NormalizationGain` / `AlbumNormalizationGain` (dB). The phone evens out volume with them. */
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    /** Jellyfin's own `HasLyrics` (an embedded lyric or a sidecar it found). */
    val hasLyrics: Boolean = false,
    val jellyfinProviderIds: Map<String, String> = emptyMap(),
    // ── Phase 276: MusicBrainz ──
    val recordingMbid: String? = null,
    val addedAt: Long? = null,
    val missingSince: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Phase 275 (FR-275-6) — which files a phone cannot play as they are. Media3 has no ASF extractor, so a WMA file
 * is re-encoded by Jellyfin on every play (measured 2026-09-27: PlaybackInfo answers it with an HLS/AAC
 * `TranscodingUrl`). Only the admin ever reads this; a viewer is never told (279 FR-279-10).
 */
object MusicFormats {
    /** Containers the phone direct-plays (the audio device profile of 279 declares the same list). */
    val PHONE_CONTAINERS = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "mp4", "wav", "mka", "webm", "aac")
    private val WMA_CODECS = setOf("wmav1", "wmav2", "wmapro", "wmalossless", "wmavoice")

    fun reencodesOnPhone(container: String?, codec: String?): Boolean {
        val c = container?.lowercase()
        if (codec?.lowercase() in WMA_CODECS || c == "asf" || c == "wma") return true
        return c != null && c !in PHONE_CONTAINERS
    }

    /** What the admin reads: `WMA · 128`, `MP3 · 320`, `FLAC`. */
    fun label(container: String?, codec: String?, bitrate: Int?): String {
        val name = when {
            codec?.lowercase()?.startsWith("wma") == true || container?.lowercase() == "asf" -> "WMA"
            !codec.isNullOrBlank() -> codec.uppercase()
            !container.isNullOrBlank() -> container.uppercase()
            else -> "?"
        }
        val kbps = bitrate?.takeIf { it > 0 }?.let { (it + 500) / 1000 }
        return if (kbps != null && name != "FLAC" && name != "ALAC") "$name · $kbps" else name
    }
}

fun MusicTrack.reencodesOnPhone(): Boolean = MusicFormats.reencodesOnPhone(container, codec)
