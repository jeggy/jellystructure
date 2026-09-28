package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phases 280/281 — the admin's `/api/audiobooks/…` shapes, shared by the backend and the admin frontend. Nothing here
// reaches a Ravilo app (the phone's book shapes are in `:shared`).

@Serializable
data class AudiobooksHealthDto(
    val books: Int = 0,
    val parts: Int = 0,
    val authors: Int = 0,
    @SerialName("missing_parts") val missingParts: Int = 0,
    @SerialName("two_in_one") val twoInOne: Int = 0,
    @SerialName("covers_missing") val coversMissing: Int = 0,
    @SerialName("no_narrator") val noNarrator: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val ebooks: Int = 0,
)

/** A cell of the Audiobooks kind (FR-280-8). [flag] is the corner chip: `missing_part` · `two_books` · null. */
@Serializable
data class AudiobookRow(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    @SerialName("duration_ms") val durationMs: Long = 0,
    val parts: Int = 0,
    val cover: Boolean = false,
    val v: Long = 0,
    val flag: String? = null,
    /** The first missing number, for *part 6 missing*. */
    @SerialName("missing_part") val missingPart: Int? = null,
    @SerialName("finished_by") val finishedBy: Int = 0,
    val series: String? = null,
)

@Serializable
data class AudiobookAuthorRow(val id: String, val name: String, val books: Int = 0, val picture: Boolean = false)

@Serializable
data class AudiobookSeriesRow(val name: String, val books: Int = 0)

@Serializable
data class AudiobooksBrowseDto(
    val mapped: Boolean,
    val scanned: Boolean,
    val health: AudiobooksHealthDto? = null,
    val libraries: List<MusicLibraryInfo> = emptyList(),
    /** `audiobooks` · `authors` · `series` (present only when any book has a series, M6·3). */
    val view: String,
    val views: List<String> = listOf("audiobooks", "authors"),
    val total: Int = 0,
    val facets: List<MusicFacet> = emptyList(),
    val books: List<AudiobookRow> = emptyList(),
    val authors: List<AudiobookAuthorRow> = emptyList(),
    val series: List<AudiobookSeriesRow> = emptyList(),
)

// ── Phase 281: the Audiobook and Author pages ──

@Serializable
data class AudiobookPartRow(
    val id: String,
    val number: Int? = null,
    val position: Int = 0,
    val title: String = "",
    @SerialName("length_ms") val lengthMs: Long? = null,
    val format: String = "",
    /** FR-281-5 — Jellyfin's own saved position for this part for the admin, null when none; `under5` when the part is
     *  shorter than Jellyfin's five-minute floor (it never saves one there). */
    @SerialName("jellyfin_position_ms") val jellyfinPositionMs: Long? = null,
    val under5: Boolean = false,
    val browser: Boolean = false,
    /** The part's `Album` tag — shown only when the folder names two books. */
    val album: String? = null,
)

@Serializable
data class AudiobookListenerRow(
    val name: String,
    @SerialName("left_ms") val leftMs: Long? = null,
    val part: Int? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    /** 0–1 of the book heard. */
    val progress: Double = 0.0,
)

@Serializable
data class AudiobookPageDto(
    val book: Audiobook,
    val parts: List<AudiobookPartRow> = emptyList(),
    val chapters: List<AudiobookChapter> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    val listeners: List<AudiobookListenerRow> = emptyList(),
    val library: String? = null,
    @SerialName("jellyfin_url") val jellyfinUrl: String? = null,
    /** FR-281-8 — whether Save also writes tags into the parts (the providers card's switch). */
    @SerialName("write_tags") val writeTags: Boolean = false,
    /** FR-280-3 — what *Split into two books…* would make, one group per `Album` tag (the first keeps this page);
     *  present only while the folder is flagged. */
    @SerialName("split_preview") val splitPreview: List<AudiobookSplitGroup> = emptyList(),
    /** On a book split off a folder, or the folder's own book after a split: the title of the other books. */
    @SerialName("split_siblings") val splitSiblings: List<AudiobookRow> = emptyList(),
    /** FR-281-1 — the head's source chip: *From the files* · *Edited here* · a provider's name. */
    val source: String = "From the files",
)

@Serializable
data class AudiobookSplitGroup(val title: String, val parts: List<String> = emptyList(), @SerialName("keeps_page") val keepsPage: Boolean = false)

/** FR-281-2 — one field typed on the Details tab; `value` is the text as shown (lists joined with `; `). */
@Serializable
data class AudiobookFieldEdit(val field: String, val value: String? = null)

@Serializable
data class AudiobookApplyRequest(val provider: String, val fields: List<String>)

@Serializable
data class AudiobookOrderRequest(@SerialName("part_ids") val partIds: List<String>)

@Serializable
data class AudiobookChaptersRequest(val source: String? = null, val titles: Map<String, String>? = null)

@Serializable
data class AudiobookDismissRequest(val what: String)

@Serializable
data class AudiobookAuthorPageDto(
    val author: AudiobookAuthor,
    val books: List<AudiobookRow> = emptyList(),
    @SerialName("picture_url") val pictureUrl: String? = null,
    /** FR-281-9 — what a provider knows about the author, in one sentence. */
    val suggestion: String? = null,
)

@Serializable
data class AudiobookAuthorEdit(val bio: String? = null)

/** FR-281-4 — the audiobook rows of the Metadata providers card. */
@Serializable
data class AudiobookProvidersDto(
    @SerialName("itunes_store") val itunesStore: String = "dk",
    @SerialName("audnexus_region") val audnexusRegion: String = "uk",
    @SerialName("google_books_key_set") val googleBooksKeySet: Boolean = false,
    @SerialName("write_tags") val writeTags: Boolean = false,
    /** Whether this server can write tags at all (a tagger in the image). */
    @SerialName("tagger_available") val taggerAvailable: Boolean = false,
    /** 2026-09-28 amendment — the saved key's last real check; null = no key, or this key not checked yet. */
    @SerialName("google_books_check") val googleBooksCheck: ProviderKeyCheck? = null,
)

@Serializable
data class AudiobookProvidersUpdate(
    @SerialName("itunes_store") val itunesStore: String? = null,
    @SerialName("audnexus_region") val audnexusRegion: String? = null,
    @SerialName("google_books_key") val googleBooksKey: String? = null,
    @SerialName("write_tags") val writeTags: Boolean? = null,
)
