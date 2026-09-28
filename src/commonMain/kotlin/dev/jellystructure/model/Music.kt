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

/** Phase 276 — a MusicBrainz artist credit as a release names it (the id is an MBID, not a Jellyfin id). */
@Serializable
data class MusicMbCredit(
    val mbid: String,
    val name: String,
    val joinPhrase: String = "",
)

/** Phase 276 (FR-276-7) — one community genre vote count from MusicBrainz. */
@Serializable
data class MusicGenreVote(val name: String, val count: Int)

/** Phase 276 — one pressing of a release-group, and how many of the tracks on disk agree with it. */
@Serializable
data class MusicReleaseOption(
    val mbid: String,
    val title: String = "",
    val country: String? = null,
    val date: String? = null,
    val label: String? = null,
    val format: String? = null,
    val trackCount: Int = 0,
    val agreeing: Int = 0,
    /** The Cover Art Archive has a front for this release. */
    val hasFront: Boolean = false,
)

/** Phase 276 (FR-276-4) — one candidate album (a release-group) for Find match… and for *needs you*. */
@Serializable
data class MusicCandidate(
    val releaseGroupMbid: String,
    val title: String,
    val artist: String = "",
    val primaryType: String? = null,
    val secondaryTypes: List<String> = emptyList(),
    val firstReleaseDate: String? = null,
    /** MusicBrainz's own search score (0–100). */
    val score: Int = 0,
    /** Tracks on disk that agree on position and length (±3 s) with this candidate's best release. */
    val agreeing: Int = 0,
    val total: Int = 0,
    /** The largest length difference among the tracks that did not agree, in whole seconds. */
    val lengthOffMaxSec: Int? = null,
    val bestRelease: MusicReleaseOption? = null,
    /** `search` or `acoustid` (identified by sound). */
    val source: String = "search",
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
    /** `Person`, `Group`, `Orchestra`… */
    val type: String? = null,
    val country: String? = null,
    /** `1987–1994`, `1999–` — only what MusicBrainz knows. */
    val lifeSpan: String? = null,
    val disambiguation: String? = null,
    val mbSortName: String? = null,
    val aliases: List<String> = emptyList(),
    val mbGenres: List<MusicGenreVote> = emptyList(),
    /** MusicBrainz URL relationships by type (`wikidata`, `image`, `official homepage`, `discogs`, `wikipedia`…). */
    val urls: Map<String, String> = emptyMap(),
    // ── Phase 277: pictures, biography, NFO ──
    /** A backdrop (`backdrop.jpg`) and a logo (`logo.png`) in the artist folder — [imageState] is the picture. */
    val backdropState: String = MusicArt.NONE,
    val logoState: String = MusicArt.NONE,
    /** `fanart.tv` / `commons` / `upload` — where the picture on disk came from. */
    val imageSource: String? = null,
    /** A Commons picture's credit line (author · licence), written into `artist.nfo`. */
    val imageCredit: String? = null,
    /** FR-277-6 — the lead of the artist's Wikipedia article per language (`en`, `da`, `fo`), from MusicBrainz's URL
     *  relationships. The phone picks the viewer's language, else English. */
    val biographies: Map<String, String> = emptyMap(),
    /** `Wikipedia (da)` / `Wikidata` — named on the admin page only. */
    val biographySource: String? = null,
    /** The admin's own text; when set it wins everywhere and no run overwrites it. */
    val biographyEdited: String? = null,
    val bioFetchedAt: Long? = null,
    val nfoWrittenAt: Long? = null,
    val nfoHash: String? = null,
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
    /** Phase 284 — when jellystructure last wrote tags into this album's files (epoch seconds), and Q3's per-album *Also embed the cover*. */
    val tagsWrittenAt: Long? = null,
    val embedCover: Boolean = false,
    /** `tags` (ids already in the files) · `search` · `acoustid` · `manual` (chosen in Find match…). */
    val matchSource: String? = null,
    val matchedAt: Long? = null,
    /** When a run last tried to match this album. A `missing`-scope run retries an unmatched album once a day, not
     *  on every hourly run (the answer rarely changes, and each try costs MusicBrainz several requests). */
    val matchAttemptedAt: Long? = null,
    /** Why the album needs you, or why nothing matched — one sentence the admin reads. */
    val matchNote: String? = null,
    /** Stored while the album *needs you* (or had candidates that did not agree), so Find match… opens on them. */
    val candidates: List<MusicCandidate> = emptyList(),
    /** The chosen pressing: country · date · label · format · track count. */
    val release: MusicReleaseOption? = null,
    val primaryType: String? = null,
    val secondaryTypes: List<String> = emptyList(),
    val firstReleaseDate: String? = null,
    val mbArtists: List<MusicMbCredit> = emptyList(),
    val mbGenres: List<MusicGenreVote> = emptyList(),
    /** FR-276-7 — the admin's own tick/untick; survives every run. Null = take MusicBrainz's votes. */
    val genresOverride: List<String>? = null,
    val urls: Map<String, String> = emptyMap(),
    // ── Phase 277: cover, NFO, drift ──
    /** `caa` / `fanart.tv` / `upload` / `jellyfin` — where the cover on disk came from. */
    val coverSource: String? = null,
    /** FR-277-1 — matched, and the Cover Art Archive has no front for it: a triage item, not an error. */
    val coverMissingOnCaa: Boolean = false,
    val nfoWrittenAt: Long? = null,
    val nfoHash: String? = null,
    /** FR-277-7 — when `album.nfo` on disk was found rewritten by someone else, and how many fields differed. */
    val nfoDriftAt: Long? = null,
    val nfoDriftFields: Int = 0,
    /** Phase 278 — Jellyfin's own locked fields on the album (`All` when the whole item is locked); a scan
     *  refreshes it. Our NFO edits to a locked field are ignored by Jellyfin, so the page says so (136). */
    val jellyfinLocked: List<String> = emptyList(),
    /** Phase 283 (FR-283-4) — a flag the admin said *This is right* to, with what it was said for: when that changes
     *  (a folder renamed, a tag rewritten, another folder added) the flag comes back. */
    val flagsDismissed: Map<String, String> = emptyMap(),
    val addedAt: Long? = null,
    val missingSince: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Where a track stands against its album's chosen release (FR-276-5). */
object MusicRecording {
    /** The track sits where the release says, with the length it says (±3 s). */
    const val AGREES = "agrees"
    /** The release has a track at that position, but its length is off — often another version (a single's, a live cut). */
    const val DISAGREES = "disagrees"
    /** The admin picked this recording by hand in *Match this track…*. */
    const val MANUAL = "manual"
}

/** FR-276-7 — the genres an album shows: the admin's own choice, else MusicBrainz's top votes, else the files' tags. */
fun MusicAlbum.effectiveGenres(): List<String> = genresOverride ?: MusicGenrePick.pick(mbGenres).ifEmpty { genres }

object MusicGenrePick {
    /** The top four with at least 3 votes and at least a tenth of the top genre's votes. */
    fun pick(votes: List<MusicGenreVote>): List<String> {
        val top = votes.maxOfOrNull { it.count } ?: return emptyList()
        return votes.filter { it.count >= 3 && it.count * 10 >= top }.sortedByDescending { it.count }.take(4).map { it.name }
    }
}

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
    /** The track on the chosen release (Jellyfin's `MusicBrainzTrack`, Kodi's `musicBrainzTrackID`). */
    val releaseTrackMbid: String? = null,
    /** [MusicRecording] — null until the album is matched. */
    val recordingState: String? = null,
    /** What the release calls this track and how long it says it is. */
    val mbTitle: String? = null,
    val mbLengthMs: Long? = null,
    val mbArtists: List<MusicMbCredit> = emptyList(),
    // ── Phase 277: lyrics ──
    /** [MusicLyrics] — what the last lyrics lookup found (or a sidecar already there). Null = never looked. */
    val lyricsState: String? = null,
    val lyricsCheckedAt: Long? = null,
    val addedAt: Long? = null,
    val missingSince: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Phase 277 (FR-277-8) — the lyrics a track has beside it. */
object MusicLyrics {
    /** A `.lrc` with timestamps — the phone lights the current line. */
    const val SYNCED = "synced"
    /** A `.txt` — words, no timing. */
    const val PLAIN = "plain"
    /** LRCLIB says the track has no words. */
    const val INSTRUMENTAL = "instrumental"
    const val NONE = "none"
}

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
