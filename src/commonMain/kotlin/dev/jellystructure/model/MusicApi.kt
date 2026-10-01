package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phases 275/276 — the admin's `/api/music/**` shapes, shared by the backend and the admin frontend so the two
// cannot drift. Nothing here reaches a Ravilo app (279 has its own DTOs in `:shared`).

/** FR-275-6 — the `/api/health` music block and the Library's status line. */
@Serializable
data class MusicHealth(
    val artists: Int,
    val albums: Int,
    val tracks: Int,
    val matched: Int,
    @SerialName("needs_you") val needsYou: Int,
    val unmatched: Int,
    @SerialName("covers_missing") val coversMissing: Int,
    @SerialName("artist_images_missing") val artistImagesMissing: Int,
    val reencodes: Int,
)

/** Where a matching pass stands — Activity and the Library's *Matching… n of 30*. */
@Serializable
data class MusicMatchStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    @SerialName("current_album") val currentAlbum: String? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("last_summary") val lastSummary: String? = null,
)

/** One row of Activity's *Outbound pacing* card for a fixed-rate host: what it ran at over the last minute. */
@Serializable
data class PacingStats(
    @SerialName("rate_per_sec") val ratePerSec: Double,
    @SerialName("ceiling_per_sec") val ceilingPerSec: Double,
    @SerialName("refused_last_minute") val refusedLastMinute: Int,
)

/** FR-276-5 — one recording offered by *Match this track…*. */
@Serializable
data class MusicRecordingOption(
    val mbid: String,
    val title: String,
    val artist: String,
    @SerialName("length_ms") val lengthMs: Long? = null,
    /** The first few releases it is on, as a reader would name them. */
    val releases: List<String> = emptyList(),
)

/** FR-276-4 — *Identify by sound*: one sentence and whatever candidates the sound pointed at. */
@Serializable
data class MusicSoundResult(val sentence: String, val candidates: List<MusicCandidate> = emptyList())

@Serializable
data class MusicStatusDto(
    val mapped: Boolean,
    val health: MusicHealth?,
    val match: MusicMatchStatus,
    @SerialName("musicbrainz_enabled") val musicbrainzEnabled: Boolean,
    @SerialName("acoustid") val acoustIdAvailable: Boolean,
)

@Serializable data class MusicMatchRequest(@SerialName("album_ids") val albumIds: List<String>? = null, val scope: String = "missing")
@Serializable data class MusicSearchRequest(val query: String? = null, val url: String? = null)
@Serializable data class MusicUseRequest(@SerialName("release_group") val releaseGroup: String, val release: String? = null, val lock: Boolean = false, val source: String = "manual")
@Serializable data class MusicLockRequest(val locked: Boolean)
@Serializable data class MusicGenresRequest(val genres: List<String>? = null)
@Serializable data class MusicRecordingRequest(val recording: String)

/** A candidate as Find match… shows it: the fit already in words (H2's lean). */
@Serializable
data class MusicCandidateDto(val candidate: MusicCandidate, val fit: String, val usable: Boolean)

/** FR-276-8 — the Metadata providers card. Keys never leave the server: `*_key_set` says whether one is saved. */
@Serializable
data class MusicProvidersDto(
    @SerialName("musicbrainz_enabled") val musicbrainzEnabled: Boolean,
    @SerialName("musicbrainz_contact") val musicbrainzContact: String,
    @SerialName("musicbrainz_contact_effective") val musicbrainzContactEffective: String,
    @SerialName("musicbrainz_rate") val musicbrainzRate: Double,
    @SerialName("musicbrainz_last") val musicbrainzLast: String? = null,
    @SerialName("acoustid_key_set") val acoustIdKeySet: Boolean,
    @SerialName("fanart_key_set") val fanartKeySet: Boolean,
    /** Phase 277 (FR-277-8) — the *Fetch lyrics* switch. */
    @SerialName("lyrics_enabled") val lyricsEnabled: Boolean = true,
    // Phase 284 (FR-284-8)
    @SerialName("write_tags") val writeTags: Boolean = true,
    @SerialName("keep_id3_version") val keepId3Version: Boolean = true,
    @SerialName("keep_unmanaged_frames") val keepUnmanagedFrames: Boolean = true,
    @SerialName("tagger_available") val taggerAvailable: Boolean = true,
    /** 2026-09-28 amendment — the saved key's last real check; null = no key, or this key not checked yet. */
    @SerialName("acoustid_check") val acoustIdCheck: ProviderKeyCheck? = null,
    @SerialName("fanart_check") val fanartCheck: ProviderKeyCheck? = null,
)

/** Phase 276/281 (2026-09-28 amendment) — what a provider answered when a key was really used: [ok] it accepted the
 *  key; [answered] false = it did not answer, so the key is untested. Never inferred from a key being saved. */
@Serializable
data class ProviderKeyCheck(
    val ok: Boolean,
    val answered: Boolean = true,
    val message: String,
    @SerialName("checked_at") val checkedAt: Long,
)

/** *Test* on a provider row: one sentence, plus the key's check for a keyed provider. */
@Serializable
data class ProviderTestResult(val result: String = "", val check: ProviderKeyCheck? = null)

/** Phase 277 — one picture a provider offers (FR-277-2). */
@Serializable
data class MusicArtCandidate(
    /** `front` / `back` / `booklet` / `albumcover` / `cdart` / `thumb` / `background` / `logo`. */
    val kind: String,
    val url: String,
    val thumb: String = url,
    /** `Cover Art Archive` / `fanart.tv` / `Wikimedia Commons`. */
    val source: String,
    val approved: Boolean = true,
    val credit: String? = null,
)

/** FR-277-2 — an album's Artwork tab: what is in use, and what the providers offer. */
@Serializable
data class MusicArtworkDto(
    @SerialName("in_use") val inUse: String? = null,
    @SerialName("in_use_bytes") val inUseBytes: Long? = null,
    val locked: Boolean = false,
    val source: String? = null,
    val candidates: List<MusicArtCandidate> = emptyList(),
)

/** FR-277-2 — an artist's Artwork tab: thumb (1:1), background (16:9), logo (transparent). */
@Serializable
data class MusicArtistArtworkDto(
    val thumb: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val locked: List<String> = emptyList(),
    val credit: String? = null,
    val candidates: List<MusicArtCandidate> = emptyList(),
    @SerialName("fanart_key") val fanartKey: Boolean = false,
)

@Serializable data class MusicArtUseRequest(val url: String, val kind: String = "front", val source: String = "manual", val credit: String? = null)
@Serializable data class MusicBiographyRequest(val text: String? = null)

/** FR-277-4/7 — the NFO tab: the path, the file as written, and how many fields differ if someone rewrote it. */
@Serializable
data class MusicNfoDto(val path: String? = null, val text: String? = null, val drift: Int? = null)

@Serializable
data class MusicProvidersUpdate(
    @SerialName("musicbrainz_enabled") val musicbrainzEnabled: Boolean? = null,
    @SerialName("musicbrainz_contact") val musicbrainzContact: String? = null,
    /** Absent = keep; "" = remove the saved key. */
    @SerialName("acoustid_key") val acoustIdKey: String? = null,
    @SerialName("fanart_key") val fanartKey: String? = null,
    @SerialName("lyrics_enabled") val lyricsEnabled: Boolean? = null,
    // Phase 284 (FR-284-8) — the switch and its two sub-choices.
    @SerialName("write_tags") val writeTags: Boolean? = null,
    @SerialName("keep_id3_version") val keepId3Version: Boolean? = null,
    @SerialName("keep_unmanaged_frames") val keepUnmanagedFrames: Boolean? = null,
)

// ── Phase 278 — the admin's music pages ──────────────────────────────────────────────────────────────────

/** One value of a Library facet, counted server-side against every other active facet (FR-278-3). */
@Serializable
data class MusicFacetValue(
    val value: String,
    val label: String,
    val count: Int,
    val on: Boolean = false,
    /** A warning line under the value (WMA: *plays on a phone only by re-encoding*). */
    val note: String? = null,
)

@Serializable
data class MusicFacet(val key: String, val label: String, val values: List<MusicFacetValue>)

/** An album cell (FR-278-2). [match] is `matched` · `needs_you` · `unmatched` · `locked` (a locked match). */
@Serializable
data class MusicAlbumRow(
    val id: String,
    val title: String,
    val artist: String = "",
    @SerialName("artist_id") val artistId: String? = null,
    val year: Int? = null,
    val songs: Int = 0,
    val match: String,
    val cover: Boolean = false,
    /** Changes whenever the row does — appended to the image URL so a replaced cover shows. */
    val v: Long = 0,
    /** `album` · `single` · `compilation` · `live` · `soundtrack` — the Artist page's groups. */
    val type: String = "album",
    /** Phase 283 — the album folder's name (a repeated title is told apart by it), the flags it carries, and the
     *  matcher's note (the chip's tooltip, FR-283-6). */
    val folder: String? = null,
    val flags: List<String> = emptyList(),
    val note: String? = null,
)

@Serializable
data class MusicArtistRow(
    val id: String,
    val name: String,
    val albums: Int = 0,
    val songs: Int = 0,
    val match: String,
    val picture: Boolean = false,
    /** Has a folder of its own; a credit-only artist has nowhere to put a picture. */
    val folder: Boolean = false,
    val v: Long = 0,
)

@Serializable
data class MusicSongRow(
    val id: String,
    @SerialName("album_id") val albumId: String? = null,
    val album: String? = null,
    val disc: Int? = null,
    val position: Int? = null,
    val title: String,
    val artists: List<MusicCredit> = emptyList(),
    @SerialName("length_ms") val lengthMs: Long? = null,
    /** `WMA · 128`, `MP3 · 320`, `FLAC`. */
    val format: String,
    val reencodes: Boolean = false,
    /** [MusicLyrics] or null. */
    val lyrics: String? = null,
    /** [MusicRecording] or null (album not matched). */
    val recording: String? = null,
    @SerialName("album_matched") val albumMatched: Boolean = false,
)

@Serializable
data class MusicLibraryInfo(
    val id: String,
    val name: String,
    @SerialName("jellyfin_path") val jellyfinPath: String = "",
    @SerialName("local_path") val localPath: String = "",
)

/** `GET /api/music/browse` — one view of the Music kind, its facets counted on the server (FR-278-1..4). */
@Serializable
data class MusicBrowseDto(
    val mapped: Boolean,
    /** A scan has read the library at least once since the server started, or rows exist. */
    val scanned: Boolean,
    val health: MusicHealth? = null,
    val match: MusicMatchStatus = MusicMatchStatus(),
    val libraries: List<MusicLibraryInfo> = emptyList(),
    val view: String,
    val total: Int = 0,
    val facets: List<MusicFacet> = emptyList(),
    val albums: List<MusicAlbumRow> = emptyList(),
    val artists: List<MusicArtistRow> = emptyList(),
    val songs: List<MusicSongRow> = emptyList(),
    @SerialName("musicbrainz_enabled") val musicbrainzEnabled: Boolean = true,
    /** Phase 293 (FR-293-2/4) — the Dashboard row's key this list is narrowed to, and the row's label for the chip.
     *  Absent when no key (or an unknown one) was asked for. */
    val filter: String? = null,
    @SerialName("filter_label") val filterLabel: String? = null,
    /** Phase 293 (FR-293-5) — *Write tags into music files* is on, so the page's work also reaches the files' tags. */
    @SerialName("write_tags") val writeTags: Boolean = false,
)

/** One row of the Album page's Tracks tab (FR-278-6). */
@Serializable
data class MusicTrackRow(
    val id: String,
    val disc: Int? = null,
    val position: Int? = null,
    val title: String,
    val artists: List<MusicCredit> = emptyList(),
    @SerialName("length_ms") val lengthMs: Long? = null,
    val format: String,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    val reencodes: Boolean = false,
    /** A browser can direct-play the file (the ▶ is offered). */
    val browser: Boolean = false,
    val recording: String? = null,
    @SerialName("mb_title") val mbTitle: String? = null,
    @SerialName("mb_length_ms") val mbLengthMs: Long? = null,
    val lyrics: String? = null,
    @SerialName("gain_db") val gainDb: Double? = null,
)

@Serializable
data class MusicAlbumPageDto(
    val album: MusicAlbum,
    val tracks: List<MusicTrackRow>,
    /** What the album shows and writes: the admin's pick, else MusicBrainz's top votes, else the tags. */
    val genres: List<String> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    /** Fields that differ from our last `album.nfo` write, when someone else rewrote it (FR-277-7). */
    val drift: Int? = null,
    @SerialName("lyrics_enabled") val lyricsEnabled: Boolean = true,
    @SerialName("acoustid") val acoustId: Boolean = false,
    @SerialName("jellyfin_url") val jellyfinUrl: String? = null,
    val library: String? = null,
    /** Jellyfin's own locked fields on the album (136's banner). */
    @SerialName("jellyfin_locked") val jellyfinLocked: List<String> = emptyList(),
    val type: String = "album",
    /** Phase 283 — what the folder and the songs disagree on; and the kinds the admin said *This is right* to. */
    val flags: List<MusicFlagDto> = emptyList(),
    @SerialName("dismissed_flags") val dismissedFlags: List<String> = emptyList(),
    /** Phase 284 (FR-284-7) — whether *Write tags into music files* is on (the split Save reads it). */
    @SerialName("write_tags") val writeTags: Boolean = false,
    /** Phase 290 (FR-290-1) — the year the album first came out: what the page shows. */
    val year: Int? = null,
)

/** Phase 283 (FR-283-3) — one flag on an album: the two sides quoted, the other folders, and what Find match… can
 *  search for instead ([search], the folder's cleaned name). */
@Serializable
data class MusicFlagDto(
    val kind: String,
    val sentence: String,
    val folder: String? = null,
    @SerialName("folder_artist") val folderArtist: String? = null,
    @SerialName("files_title") val filesTitle: String? = null,
    @SerialName("files_artist") val filesArtist: String? = null,
    val search: String? = null,
    val others: List<MusicFlagOther> = emptyList(),
    /** The cover and `album.nfo` in the folder came from the match this flag puts in doubt. */
    @SerialName("written_from_match") val writtenFromMatch: Boolean = false,
)

@Serializable
data class MusicFlagOther(val id: String, val title: String, val folder: String? = null)

@Serializable
data class MusicVideoRow(
    val id: String,
    val title: String,
    val year: Int? = null,
    @SerialName("duration_sec") val durationSec: Int? = null,
)

@Serializable
data class MusicArtistPageDto(
    val artist: MusicArtist,
    val albums: List<MusicAlbumRow> = emptyList(),
    @SerialName("credited_on") val creditedOn: List<MusicAlbumRow> = emptyList(),
    val songs: Int = 0,
    @SerialName("picture_url") val pictureUrl: String? = null,
    val videos: List<MusicVideoRow> = emptyList(),
    /** MusicBrainz's votes on the artist, summed over its matched albums when the artist has none of its own. */
    val genres: List<MusicGenreVote> = emptyList(),
    @SerialName("jellyfin_url") val jellyfinUrl: String? = null,
    /** The biography this page shows: the admin's own, else English, else the first language there is. */
    val biography: String? = null,
)

/** Metadata → Music genres (FR-278-13). */
@Serializable
data class MusicGenreRow(val name: String, val albums: Int, val songs: Int)

/** The Library's selection bar (FR-278-3): `match` · `covers` · `nfo` · `lock` · `unlock` · `clear`. */
@Serializable
data class MusicBulkRequest(val action: String, @SerialName("album_ids") val albumIds: List<String>)

// ── Phase 284 — the Files tab: what the files say, and what this page would write ──

/** One cell: [file] is what the file says, [page] what this page states when it differs, [mark] what will happen —
 *  `same` · `write` · `fileonly` (kept) · `empty` · `noreach` (written, Jellyfin won't read it from WMA) · `seed` (left as
 *  it is) · `hold` (locked; ours differs) · `junk` (kept) · `side` (a sidecar). */
@Serializable
data class MusicFileCell(val file: String? = null, val page: String? = null, val mark: String = "same")

@Serializable
data class MusicFileRow(
    val id: String, val file: String, val format: String, val seeding: Boolean = false, val wma: Boolean = false, val differs: Boolean = false,
    val cells: Map<String, MusicFileCell> = emptyMap(), val error: String? = null,
)

@Serializable
data class MusicFileColumn(val key: String, val label: String)

@Serializable
data class MusicFileColumnGroup(val label: String, val columns: List<MusicFileColumn>)

@Serializable
data class MusicFilesDto(
    /** `album` · `book` — one component, two field sets (FR-284-6). */
    val kind: String = "album",
    val rows: List<MusicFileRow> = emptyList(),
    val columns: List<MusicFileColumnGroup> = emptyList(),
    @SerialName("written_at") val writtenAt: Long? = null,
    @SerialName("differ_count") val differCount: Int = 0,
    @SerialName("seeding_count") val seedingCount: Int = 0,
    @SerialName("wma_count") val wmaCount: Int = 0,
    @SerialName("tagger_available") val taggerAvailable: Boolean = true,
    @SerialName("write_enabled") val writeEnabled: Boolean = true,
    val locked: Boolean = false,
    @SerialName("has_cover") val hasCover: Boolean = false,
    @SerialName("embed_cover") val embedCover: Boolean = false,
    /** Frames nothing manages, summed over the files (*PRIV ×11 · WCOM ×3*). */
    val junk: List<String> = emptyList(),
    /** Why *Write tags* is disabled, or null. */
    val reason: String? = null,
    /** Dev review 4 — *Lidarr manages this album · it matched …*, when Lidarr is connected and knows it. */
    val lidarr: String? = null,
)

@Serializable
data class MusicWriteTagsRequest(
    @SerialName("embed_cover") val embedCover: Boolean? = null,
    @SerialName("remove_junk") val removeJunk: Boolean = false,
    /** A locked album whose files disagree: `file` takes the files' facts first, `ours` writes ours. */
    val take: String? = null,
)

/** FR-284-10 — the bulk confirm line's counts before anything runs. */
@Serializable
data class MusicTagsPreview(val selected: Int = 0, val songs: Int = 0, val unmatched: Int = 0, val seeding: Int = 0, val wma: Int = 0, @SerialName("tagger_available") val taggerAvailable: Boolean = true, @SerialName("write_enabled") val writeEnabled: Boolean = true)

@Serializable
data class MusicBulkResult(val sentence: String)

/** FR-278-7 — Convert…: an album, some songs, or (both null) every song a phone re-encodes. */
@Serializable
data class MusicConvertRequest(
    @SerialName("album_id") val albumId: String? = null,
    @SerialName("track_ids") val trackIds: List<String>? = null,
)

/** What Convert… would do, asked before the confirmation says a number. */
@Serializable
data class MusicConvertPlan(
    val songs: Int,
    /** Seeding in qBittorrent — skipped. */
    val seeding: Int = 0,
    /** The formats involved, as the admin reads them (`WMA · 128`). */
    val formats: List<String> = emptyList(),
    val job: String? = null,
)

@Serializable
data class MusicStreamDto(val url: String)
