package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.MusicTrackItem
import kotlin.math.pow
import kotlin.random.Random

/**
 * R322 (FR-R322-8) — the queue as plain data and arithmetic, so the rules are tested once and every platform's
 * engine follows them: what *next* and *previous* mean under each repeat mode, what shuffle does to the order and
 * how it undoes, and how the current song's position survives a move or a removal.
 */
class MusicQueue(private val random: Random = Random.Default) {
    var tracks: List<MusicTrackItem> = emptyList()
        private set
    var index: Int = -1
    /** The order before Shuffle, so turning it off restores it (the current song keeps playing). */
    private var unshuffled: List<MusicTrackItem>? = null
    val shuffled: Boolean get() = unshuffled != null

    val current: MusicTrackItem? get() = tracks.getOrNull(index)

    /** Replace everything. With [shuffle] the chosen song comes first and the rest follow in a random order. */
    fun set(list: List<MusicTrackItem>, start: Int, shuffle: Boolean) {
        val s = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (list.isEmpty()) { clear(); return }
        if (shuffle) {
            unshuffled = list
            tracks = listOf(list[s]) + (list.take(s) + list.drop(s + 1)).shuffled(random)
            index = 0
        } else {
            unshuffled = null
            tracks = list
            index = s
        }
    }

    fun clear() { tracks = emptyList(); index = -1; unshuffled = null }

    /** Where the queue goes when a song ends on its own. */
    fun nextIndex(repeat: RepeatMode): Int? = when {
        tracks.isEmpty() -> null
        repeat == RepeatMode.ONE -> index
        index < tracks.lastIndex -> index + 1
        repeat == RepeatMode.ALL -> 0
        else -> null
    }

    /** Where *next* goes when the viewer asks: a skip is never held by repeat-one. */
    fun skipIndex(repeat: RepeatMode): Int? = nextIndex(if (repeat == RepeatMode.ONE) RepeatMode.ALL else repeat)

    fun previousIndex(): Int? = if (index > 0) index - 1 else null

    fun setShuffle(on: Boolean) {
        val cur = current ?: return
        if (on == shuffled) return
        if (on) {
            unshuffled = tracks
            val rest = tracks.filterIndexed { i, _ -> i != index }.shuffled(random)
            tracks = listOf(cur) + rest
            index = 0
        } else {
            val original = unshuffled ?: tracks
            // Songs added while shuffled keep their place at the end.
            val extra = tracks.filter { t -> original.none { it === t } }
            tracks = original.filter { t -> tracks.any { it === t } } + extra
            index = tracks.indexOfFirst { it === cur }.coerceAtLeast(0)
            unshuffled = null
        }
    }

    /** Drag in the Queue tab: indices are the queue's own; the current song keeps playing wherever it lands. */
    fun move(from: Int, to: Int) {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        val cur = current
        val m = tracks.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        tracks = m
        index = tracks.indexOfFirst { it === cur }
    }

    /** Swipe to remove. The song that is playing is not removed this way. */
    fun remove(at: Int) {
        if (at !in tracks.indices || at == index) return
        tracks = tracks.filterIndexed { i, _ -> i != at }
        if (at < index) index--
        unshuffled = unshuffled?.let { u -> u.filter { t -> tracks.any { it === t } } }
    }

    /** *Play next*: straight after the current song. With nothing loaded it becomes the queue. */
    fun insertNext(track: MusicTrackItem) {
        if (index !in tracks.indices) { tracks = listOf(track); index = 0; return }
        tracks = tracks.take(index + 1) + track + tracks.drop(index + 1)
    }

    /** *Add to queue*: at the end. */
    fun append(track: MusicTrackItem) {
        if (index !in tracks.indices) { tracks = listOf(track); index = 0; return }
        tracks = tracks + track
    }

    /** Loaded as it was saved, with no shuffle memory. */
    fun restore(list: List<MusicTrackItem>, at: Int) {
        unshuffled = null
        tracks = list
        index = if (list.isEmpty()) -1 else at.coerceIn(0, list.lastIndex)
    }
}

/**
 * FR-R322-9 (dev review 11) — a volume scale, never above 1: a song louder than the target is turned down, a quieter
 * one cannot be raised without a limiter. Album gain for an album played in order, track gain for anything else.
 */
fun musicVolumeScale(track: MusicTrackItem, context: MusicContext?, shuffled: Boolean, evenVolume: Boolean): Float {
    if (!evenVolume) return 1f
    val gain = if (context?.kind == "album" && !shuffled) track.albumGainDb ?: track.trackGainDb else track.trackGainDb ?: track.albumGainDb
    gain ?: return 1f
    val v = 10.0.pow(gain / 20.0)
    return v.toFloat().coerceIn(0f, 1f)
}
