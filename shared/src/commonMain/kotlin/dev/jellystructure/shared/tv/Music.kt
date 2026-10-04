package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Phase 279 — what the phone's music player reads from `/api/tv/music/…`. A field is only ever added, and only as an
 * optional one (R319's check allows that: an installed app skips what it does not know, and a new app on an old
 * server sees the default). An app without music never asks for any of these. Kinds are plain strings, never enums,
 * so a later value never breaks an installed app. Nothing here names a provider, a codec
 * or a delivery (FR-279-10): a viewer never learns that a song is re-encoded.
 *
 * `MusicTrackItem` is the spec's `MusicTrack` — renamed so it cannot be confused with the admin's library row of
 * the same name in the backend.
 */

@Serializable
data class MusicArtistRef(val id: String, val name: String)

@Serializable
data class MusicAlbumCard(
    val id: String,
    val title: String,
    val artists: List<MusicArtistRef> = emptyList(),
    val year: Int? = null,
    /** Null: set the title as a wordmark (277 FR-277-3) — there is no "no cover" state on the viewer's side. */
    @SerialName("image_url") val imageUrl: String? = null,
    /** `album` · `single` · `compilation` · `live` · `soundtrack`. */
    val type: String = "album",
    @SerialName("track_count") val trackCount: Int = 0,
)

@Serializable
data class MusicArtistCard(
    val id: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("album_count") val albumCount: Int = 0,
    @SerialName("track_count") val trackCount: Int = 0,
)

@Serializable
data class MusicTrackItem(
    val id: String,
    val title: String,
    @SerialName("album_id") val albumId: String? = null,
    val album: String? = null,
    /** Everyone credited, in order; the first is the performer, the rest read as *feat.* when they differ from the album's. */
    val artists: List<MusicArtistRef> = emptyList(),
    val disc: Int? = null,
    val position: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("has_lyrics") val hasLyrics: Boolean = false,
    /** The album's cover (the same URL the album card carries). */
    @SerialName("image_url") val imageUrl: String? = null,
    /** FR-279-7 — the phone chooses: album gain on an album, track gain on a mix (R322 FR-R322-9). */
    @SerialName("track_gain_db") val trackGainDb: Double? = null,
    @SerialName("album_gain_db") val albumGainDb: Double? = null,
    val favorite: Boolean = false,
    /** R344 (FR-R344-1) — the song's version keys (`live`, `remix`, `session`…) in 292's order: the set as the admin
     *  shows it. Empty = no version, and what an old server sends. Plain strings: a key this app has no name for is
     *  drawn as nothing. */
    @SerialName("versions") val versions: List<String> = emptyList(),
    /** R373 (FR-R373-1/5, 305 owner decision 1) — *Bonus*: on an album's own tracks, this copy is an extra; on a row of
     *  a list that spans albums (one copy of every song), no copy of the song is on an official tracklist. */
    val extra: Boolean = false,
    /** R373 (FR-R373-4) — how many other releases this viewer may open hold the same song (*Also on 3 releases*);
     *  the rows themselves come from `GET /tv/music/track/{id}/copies` on a tap. */
    @SerialName("also_on") val alsoOn: Int = 0,
)

/** R373 (FR-R373-4) — one other copy of a song: the track and the release it sits on. */
@Serializable
data class MusicTrackCopy(
    val track: MusicTrackItem,
    val album: MusicAlbumCard? = null,
)

/** R373 — `GET /tv/music/track/{id}/copies`: the other copies this viewer may open, best first. */
@Serializable
data class MusicTrackCopies(
    val copies: List<MusicTrackCopy> = emptyList(),
)

/** R344 (FR-R344-1) — one version type's colour in this household (`#f0795b`). Names are the app's own strings. */
@Serializable
data class MusicVersionType(val key: String, val color: String)

/** R344 (dev review 2) — the nine types and their default colours, so a chip can be drawn before `MusicHome` answers
 *  and against an old server that sends none. The server's admin table (292) holds the same nine; a test keeps them
 *  equal. */
object MusicVersionDefaults {
    val TYPES: List<MusicVersionType> = listOf(
        MusicVersionType("live", "#f0795b"),
        MusicVersionType("demo", "#a3aec6"),
        MusicVersionType("remix", "#c67fe3"),
        MusicVersionType("instrumental", "#3fb6f5"),
        MusicVersionType("cover", "#2dd49a"),
        MusicVersionType("acoustic", "#d8ad62"),
        MusicVersionType("edit", "#9d95f7"),
        MusicVersionType("alternate", "#e9709f"),
        MusicVersionType("session", "#f2a65a"),
    )
}

/** One row of the Listen tab (FR-279-2). [key] is `recent` · `played` · `artists` · `mix` · `genre`; the phone
 *  titles the fixed rows in its own language, and a genre row by [title] (the genre's name). */
@Serializable
data class MusicRow(
    val key: String,
    val title: String = "",
    val albums: List<MusicAlbumCard> = emptyList(),
    val tracks: List<MusicTrackItem> = emptyList(),
    val artists: List<MusicArtistCard> = emptyList(),
)

@Serializable
data class MusicHome(
    val rows: List<MusicRow> = emptyList(),
    /** R344 (FR-R344-1, dev review 2) — the household's version colours, as the admin set them in Metadata → Versions.
     *  Empty from an old server: the app uses [MusicVersionDefaults]. */
    @SerialName("version_types") val versionTypes: List<MusicVersionType> = emptyList(),
)

/** A page of one browse list (FR-279-3); only the list asked for is filled. */
@Serializable
data class MusicList(
    val albums: List<MusicAlbumCard> = emptyList(),
    val artists: List<MusicArtistCard> = emptyList(),
    val tracks: List<MusicTrackItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    @SerialName("page_size") val pageSize: Int = 0,
)

@Serializable
data class MusicGenreCount(val name: String, @SerialName("album_count") val albumCount: Int)

@Serializable
data class MusicPlaylist(
    val id: String,
    val name: String,
    @SerialName("track_count") val trackCount: Int = 0,
    /** Up to four album covers from the start of the playlist. */
    val covers: List<String> = emptyList(),
)

@Serializable
data class MusicAlbumDetail(
    val album: MusicAlbumCard,
    /** The album's held files, in the files' order — what an installed app shows (R373 dev review 1). */
    val tracks: List<MusicTrackItem> = emptyList(),
    @SerialName("more_from_artist") val moreFromArtist: List<MusicAlbumCard> = emptyList(),
    val favorite: Boolean = false,
    /** R373 (FR-R373-1, dev review 1) — the official album's track ids in order; null = unmatched, today's page. */
    @SerialName("official_ids") val officialIds: List<String>? = null,
    /** The extras, in the held edition's order (unnumbered, below the divider). */
    @SerialName("extra_ids") val extraIds: List<String> = emptyList(),
    /** 305 owner decision 3 — the extras section's name: MusicBrainz's title or disambiguation as it is, else a
     *  country code the app names in the viewer's language, else neither (*Extras*). */
    @SerialName("edition_title") val editionTitle: String? = null,
    @SerialName("edition_country") val editionCountry: String? = null,
    /** The singles and EPs that live under this album, held and visible, in year order. */
    val singles: List<MusicAlbumCard> = emptyList(),
    /** Their B-sides as full items, in single order; [MusicTrackItem.album]/[MusicTrackItem.albumId] name the single. */
    @SerialName("bside_tracks") val bsideTracks: List<MusicTrackItem> = emptyList(),
    /** On a single's own page: the album it lives under (*Single from …*). */
    @SerialName("single_from") val singleFrom: MusicAlbumCard? = null,
)

/** R373 (FR-R373-7) — an album some of the artist's singles live under, and how many. */
@Serializable
data class MusicSinglesUnder(
    val album: MusicAlbumCard,
    val count: Int = 0,
)

@Serializable
data class MusicAlbumGroup(
    /** `album` · `single` · `compilation` · `live` · `soundtrack` — the phone titles it. */
    val type: String,
    val albums: List<MusicAlbumCard> = emptyList(),
)

/** An artist's music video or concert film — a video, so it opens the video player with [id] (a `media` id). */
@Serializable
data class MusicVideoCard(
    val id: String,
    val title: String,
    val year: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class MusicArtistDetail(
    val artist: MusicArtistCard,
    @SerialName("background_url") val backgroundUrl: String? = null,
    /** MusicBrainz's `Person` / `Group`… as a plain word; the phone may leave it out. */
    val type: String? = null,
    /** `1999–` — only what is known. */
    val span: String? = null,
    /** Plain text in the viewer's language when there is one, else English. The source is never named. */
    val biography: String? = null,
    val groups: List<MusicAlbumGroup> = emptyList(),
    /** Songs by this viewer's play count, most played first. */
    @SerialName("top_tracks") val topTracks: List<MusicTrackItem> = emptyList(),
    val videos: List<MusicVideoCard> = emptyList(),
    /** R373 (FR-R373-7) — singles and EPs that left [groups] because they live under an album. */
    @SerialName("singles_under") val singlesUnder: List<MusicSinglesUnder> = emptyList(),
)

/** FR-279-5 — three groups, each with its own total; an empty query answers recently played songs and artists. */
@Serializable
data class MusicSearch(
    val songs: List<MusicTrackItem> = emptyList(),
    val albums: List<MusicAlbumCard> = emptyList(),
    val artists: List<MusicArtistCard> = emptyList(),
    @SerialName("songs_total") val songsTotal: Int = 0,
    @SerialName("albums_total") val albumsTotal: Int = 0,
    @SerialName("artists_total") val artistsTotal: Int = 0,
)

/** FR-279-6 — start one song. [startPositionMs] is the phone's own (a queue resumed after the app came back);
 *  the server keeps no position for music.
 *
 *  281 FR-281-10 (dev review 4: one play shape) — or one part of an audiobook: [audiobookId] + [part] (0-based, our
 *  order) and [startPositionMs] inside that part. Exactly one of [trackId] / [audiobookId]. The answer is the part's
 *  own ticket; the part list rides `GET /music/audiobook/{id}`. (Never released with a required `track_id`.) */
@Serializable
data class MusicPlayRequest(
    @SerialName("track_id") val trackId: String? = null,
    val capabilities: ClientCapabilities = ClientCapabilities(),
    @SerialName("start_position_ms") val startPositionMs: Long? = null,
    @SerialName("audiobook_id") val audiobookId: String? = null,
    val part: Int? = null,
)

@Serializable
data class LyricLine(@SerialName("t_ms") val tMs: Long, val line: String)

/** FR-279-8 — timed lines, or plain text; a song without lyrics is a 404, never an empty object. */
@Serializable
data class TrackLyrics(
    val synced: List<LyricLine>? = null,
    val plain: String? = null,
)

/** FR-279-11 — the song this viewer played last and where it sits. *Where in it* lives on the phone (Jellyfin keeps
 *  no position for music). */
@Serializable
data class MusicLastPlayed(
    val track: MusicTrackItem,
    val album: MusicAlbumCard? = null,
)

@Serializable
data class MusicFavoriteRequest(@SerialName("item_id") val itemId: String, val favorite: Boolean)
