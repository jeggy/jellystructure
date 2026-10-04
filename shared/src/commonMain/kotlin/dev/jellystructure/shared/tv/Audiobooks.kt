package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase 281 (FR-281-10) — the phone's audiobooks: `/api/tv/music/audiobooks`, `/api/tv/music/audiobook/{id}` and
// its progress, speed, finished and bookmarks. New paths and new types only (R319: nothing existing changes shape).
// Facts, never sentences: the phone words *3 h 12 min left* and *Chapter 9 · Part 9 of 14* from its own table.
// No provider, format or file name reaches the phone (FR-281-10).

@Serializable
data class AudiobookAuthorRef(val id: String, val name: String)

/** One book on the shelf — a cell, or a *Continue listening* card when [leftMs] is set. */
@Serializable
data class AudiobookCard(
    val id: String,
    val title: String,
    val authors: List<AudiobookAuthorRef> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    /** In book time; null when this viewer has not started it. */
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("left_ms") val leftMs: Long? = null,
    val finished: Boolean = false,
    /** 1-based: the chapter and part the viewer is in (a started book only). */
    val chapter: Int? = null,
    val chapters: Int = 0,
    val part: Int? = null,
    val parts: Int = 0,
    val series: String? = null,
    @SerialName("series_position") val seriesPosition: String? = null,
)

@Serializable
data class AudiobookShelf(
    /** In progress, newest first by the viewer's last listen. */
    @SerialName("continue_listening") val continueListening: List<AudiobookCard> = emptyList(),
    /** Every book, in [sort]'s order. */
    val books: List<AudiobookCard> = emptyList(),
    /** `added` · `title` · `author` · `series`. */
    val sort: String = "added",
    /** Present only when more than one author has a book (FR-R323-1.3). */
    val authors: List<AudiobookAuthorRef> = emptyList(),
    /** Present only when any book has a series. */
    val series: List<String> = emptyList(),
)

/** A chapter in **book** time; [part] is the 0-based part (our order) it starts in, [partOffsetMs] where. */
@Serializable
data class AudiobookChapterItem(
    val title: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("length_ms") val lengthMs: Long,
    val part: Int = 0,
    @SerialName("part_offset_ms") val partOffsetMs: Long = 0,
)

/** One file of the book, in our order ([index] 0-based). The phone plays the parts in this order, one at a time. */
@Serializable
data class AudiobookPartItem(val id: String, val index: Int, @SerialName("duration_ms") val durationMs: Long = 0)

/** Where this viewer is: a part and a place in it, and the same in book time. */
@Serializable
data class AudiobookPosition(
    val part: Int = 0,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("book_position_ms") val bookPositionMs: Long = 0,
    val finished: Boolean = false,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

@Serializable
data class AudiobookBookmarkItem(
    val id: Long,
    /** Book time. */
    @SerialName("position_ms") val positionMs: Long,
    val note: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class AudiobookDetail(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<AudiobookAuthorRef> = emptyList(),
    val narrators: List<String> = emptyList(),
    val year: Int? = null,
    val description: String? = null,
    val series: String? = null,
    @SerialName("series_position") val seriesPosition: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val chapters: List<AudiobookChapterItem> = emptyList(),
    val parts: List<AudiobookPartItem> = emptyList(),
    /** Null when this viewer has not started it. */
    val position: AudiobookPosition? = null,
    /** This viewer's speed for this book (FR-R323-5: remembered per book only). */
    val speed: Double = 1.0,
    val bookmarks: List<AudiobookBookmarkItem> = emptyList(),
)

@Serializable
data class AudiobookAuthorDetail(
    val id: String,
    val name: String,
    val bio: String? = null,
    val books: List<AudiobookCard> = emptyList(),
)

/** A heartbeat: the part (our order, 0-based) and where in it. [paused] goes to Jellyfin's mirror only. */
@Serializable
data class AudiobookProgressRequest(
    val part: Int,
    @SerialName("position_ms") val positionMs: Long,
    val paused: Boolean = false,
    /** R357 (FR-R357-1/-3) — the music player's level (0–100) and mute, for Jellyfin's mirror only; absent from an
     *  app older than R357 or a player that cannot know them. An older server ignores them. */
    @SerialName("volume_percent") val volumePercent: Int? = null,
    val muted: Boolean? = null,
    /** R368 (dev review item 8) — the server's playback session (from the part's [StreamTicket.sessionId]); optional. */
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class AudiobookSpeedRequest(val speed: Double)

/** *Mark as finished* (true) or *Start over* (false). */
@Serializable
data class AudiobookFinishedRequest(val finished: Boolean)

/** A bookmark at [positionMs] in book time, with an optional one-line note. */
@Serializable
data class AudiobookBookmarkRequest(@SerialName("position_ms") val positionMs: Long, val note: String? = null)
