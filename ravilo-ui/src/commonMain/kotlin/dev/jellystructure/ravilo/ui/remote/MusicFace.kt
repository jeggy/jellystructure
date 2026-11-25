package dev.jellystructure.ravilo.ui.remote

import dev.jellystructure.ravilo.ui.music.BookPlayback
import dev.jellystructure.ravilo.ui.music.MusicContext
import dev.jellystructure.ravilo.ui.music.MusicPlayerState
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.SessionDetail

/**
 * DEB-2185 (FR-3, FR-4; revision 3) — what a song's or a book's remote draws beyond the status every remote has: the
 * track's own facts, where the queue came from, the queue, and the book. Both drivers fill it in the same shape, and a
 * part the place does not carry is null or empty, so the screen draws it **absent** (FR-5.3) and never guessed.
 *
 * [local] is a queue this app holds itself (this device's own playback, or a cast it sent): ♥, ⋯, the lyrics, the
 * cover's swipe and *Up next*'s reorder act on it. A session reached only through the server carries the queue's
 * titles and ids, so *Up next* and its tap are there and the rest is not.
 */
data class MusicFace(
    val track: MusicTrackItem? = null,
    val context: MusicContext? = null,
    val now: UpNextRow? = null,
    val upNext: List<UpNextRow> = emptyList(),
    val book: BookPlayback? = null,
    val local: Boolean = false,
) {
    /** The lyrics button shows only when the song has lyrics (FR-R322-4: absent, never greyed). */
    val hasLyrics: Boolean get() = local && track?.hasLyrics == true
    /** *Up next* is there when there is a queue to show: the song playing, and a song after it or before it. */
    val hasQueue: Boolean get() = now != null && (upNext.isNotEmpty() || book == null)
}

/** One line of *Up next*: [index] is the place in the queue a tap jumps to. */
data class UpNextRow(
    val index: Int,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val durationMs: Long? = null,
)

/** The playing song and the songs after it, from a queue this app holds. */
fun upNextOf(st: MusicPlayerState): Pair<UpNextRow?, List<UpNextRow>> {
    val cur = st.current ?: return null to emptyList()
    fun row(i: Int, t: MusicTrackItem) = UpNextRow(i, t.id, t.title, t.artists.joinToString(", ") { it.name }.ifBlank { null }, t.durationMs)
    return row(st.index, cur) to st.upNext.mapIndexed { i, t -> row(st.index + 1 + i, t) }
}

/**
 * The playing song and the songs after it, from the server's window of a session's queue. [SessionDetail.queueOffset] is
 * where the window starts in the whole queue and [SessionDetail.queueIndex] is the playing song's place in it, so a row's
 * index is `queueOffset + its place in the window`. Null/empty when the place reported no queue (an older app).
 */
fun upNextOf(d: SessionDetail): Pair<UpNextRow?, List<UpNextRow>> {
    if (d.queue.isEmpty()) return null to emptyList()
    val at = d.queueIndex - d.queueOffset
    if (at !in d.queue.indices) return null to emptyList()
    fun row(i: Int) = d.queue[i].let { UpNextRow(d.queueOffset + i, it.id, it.title.orEmpty(), it.subtitle, null) }
    return row(at) to (at + 1 until d.queue.size).map(::row)
}

/** FR-4 — the state line of the remote of a playback on this device: *Playing here* / *Paused here*. */
fun hereLineKey(playing: Boolean): String = if (playing) "cast.playing_here" else "cast.paused_here"
