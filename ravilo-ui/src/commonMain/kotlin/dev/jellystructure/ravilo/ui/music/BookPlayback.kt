package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.AudiobookChapterItem
import dev.jellystructure.shared.tv.AudiobookDetail

/*
 * R323 — the book player shares the music engine (one listening queue at a time, FR-R323-7): the same ExoPlayer, the
 * same MediaSession and the same service, with a book loaded instead of a queue of songs. The book's parts play one
 * at a time in our order (281); every time a viewer sees is **book** time — the parts before, end to end, plus the
 * place in this one — worked out here, once, so no screen does its own arithmetic.
 */

/** A sleep timer: [endsAtMs] (wall clock) for minutes, or the end of [chapter] — the one playing when it was set. */
data class SleepTimer(val endsAtMs: Long? = null, val endOfChapter: Boolean = false, val minutes: Int = 0, val chapter: Int = -1)

/** The book loaded in the engine: which one, which part, the speed, the timer and whether it ended. */
data class BookPlayback(
    val detail: AudiobookDetail,
    val part: Int = 0,
    val speed: Double = 1.0,
    val sleep: SleepTimer? = null,
    /** FR-R323-6 — the last part played to its end: *Finished · Start over* instead of the transport. */
    val finished: Boolean = false,
) {
    val id: String get() = detail.id
}

/** Book time, chapters and parts — pure, and the only place they are computed on the phone. */
object BookMath {
    /** Where [part] starts in the book. */
    fun partStart(d: AudiobookDetail, part: Int): Long = d.parts.take(part.coerceAtLeast(0)).sumOf { it.durationMs }

    fun bookPosition(d: AudiobookDetail, part: Int, positionMs: Long): Long = partStart(d, part) + positionMs.coerceAtLeast(0L)

    /** The book's length: the parts end to end (the head's own length when the parts are unknown). */
    fun length(d: AudiobookDetail): Long = d.parts.sumOf { it.durationMs }.takeIf { it > 0 } ?: d.durationMs

    /** (part, position in it) for a place in the book, clamped to the book. */
    fun locate(d: AudiobookDetail, bookMs: Long): Pair<Int, Long> {
        if (d.parts.isEmpty()) return 0 to bookMs.coerceAtLeast(0L)
        var left = bookMs.coerceIn(0L, length(d))
        for ((i, p) in d.parts.withIndex()) {
            if (left < p.durationMs || i == d.parts.lastIndex) return i to left.coerceAtMost(p.durationMs)
            left -= p.durationMs
        }
        return d.parts.lastIndex to 0L
    }

    /** The chapter (0-based) a place in the book is in; -1 when the book has none. */
    fun chapterAt(d: AudiobookDetail, bookMs: Long): Int = d.chapters.indexOfLast { it.startMs <= bookMs }.let { if (it < 0 && d.chapters.isNotEmpty()) 0 else it }

    fun chapter(d: AudiobookDetail, bookMs: Long): AudiobookChapterItem? = d.chapters.getOrNull(chapterAt(d, bookMs))

    /** A chapter's end in book time (the next chapter's start, or the book's end). */
    fun chapterEnd(d: AudiobookDetail, index: Int): Long = d.chapters.getOrNull(index + 1)?.startMs ?: length(d)
}

/** FR-R323-9 — the two book settings, per device. */
object BookPrefs {
    /** M7·5's lean — off unless turned on. */
    var skipSilence: Boolean
        get() = runCatching { MusicDeviceStore.get("book_skip_silence") }.getOrNull() == "1"
        set(on) { runCatching { MusicDeviceStore.put("book_skip_silence", if (on) "1" else "0") } }
    /** On unless turned off: the last 10 s of a sleep timer fade out. */
    var sleepFade: Boolean
        get() = runCatching { MusicDeviceStore.get("book_sleep_fade") }.getOrNull() != "0"
        set(on) { runCatching { MusicDeviceStore.put("book_sleep_fade", if (on) "1" else "0") } }
}

/** The book a device resumes after a restart: which book, for which viewer. The place in it is the server's. */
object BookLastStore {
    fun save(userId: String, bookId: String) { runCatching { MusicDeviceStore.put("book_last", "$userId\n$bookId") } }
    fun load(): Pair<String, String>? = runCatching { MusicDeviceStore.get("book_last")?.split('\n')?.takeIf { it.size == 2 }?.let { it[0] to it[1] } }.getOrNull()
    fun clear() { runCatching { MusicDeviceStore.put("book_last", null) } }
}

/** Wall-clock milliseconds (the sleep timer's end is a wall-clock time). */
@OptIn(kotlin.time.ExperimentalTime::class)
fun bookNowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
