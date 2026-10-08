package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.AudiobookDetail
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/*
 * R322 — the music player. One engine per app (FR-R322-1): on Android it owns its own ExoPlayer and MediaSession
 * and lives on in `RaviloMusicService` when the app goes to the background; the web build has none yet (a later
 * phase), which is why [MusicEngine.supported] exists — music mode is absent where nothing can play it (R321
 * FR-R321-2). The queue is client state; the server only ever hears "this song started / is here / stopped".
 */

enum class RepeatMode { OFF, ALL, ONE }

/** Where a queue came from: *Playing from {label}*, and whether album gain applies (an album played in order). */
@Serializable
data class MusicContext(
    /** `album` · `artist` · `playlist` · `played` · `search` · `mix` · `songs` · `genre` · `queue`. */
    val kind: String,
    val label: String,
    val id: String? = null,
)

data class MusicPlayerState(
    val queue: List<MusicTrackItem> = emptyList(),
    val index: Int = -1,
    val context: MusicContext? = null,
    /** The viewer wants it playing — true through a buffer, false paused, at the end or failed. */
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** Where the song was at the last state change; screens poll [MusicEngine.currentPositionMs] for a live value. */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    /** FR-R322-5 — the song could not be played; the sheet offers Try again and Skip. */
    val failed: Boolean = false,
    /** FR-R322-5 — the queue's last song finished: paused at 0:00, and *next* is absent. */
    val ended: Boolean = false,
    /** R323 — a book instead of songs (one listening queue at a time). [positionMs]/[durationMs] are then the part's. */
    val book: BookPlayback? = null,
) {
    val current: MusicTrackItem? get() = queue.getOrNull(index)
    val active: Boolean get() = current != null || book != null
    val hasNext: Boolean get() = index in queue.indices && (index < queue.lastIndex || repeat == RepeatMode.ALL)
    val upNext: List<MusicTrackItem> get() = if (index in queue.indices) queue.drop(index + 1) else emptyList()
}

/**
 * The platform's music player. Every command is safe to call at any time (a no-op with nothing loaded), from the
 * main thread.
 */
expect object MusicEngine {
    /** False where this build has no music player: the mode card, the bar and the mini bar are then absent. */
    val supported: Boolean
    val state: StateFlow<MusicPlayerState>

    /** The client the engine asks for a song's stream with. The app passes its own; a cold start rebuilds one. */
    fun attach(api: TvApiClient)

    /** FR-R322-8 — replace the queue with [tracks] and play [startIndex] (Shuffle: that song first, the rest shuffled). */
    fun playQueue(tracks: List<MusicTrackItem>, startIndex: Int, context: MusicContext, shuffle: Boolean = false)

    /** FR-R322-3 — load a queue paused at [positionMs] without starting it (the Playing tab with nothing playing). */
    fun loadPaused(tracks: List<MusicTrackItem>, index: Int, positionMs: Long, context: MusicContext?)

    fun togglePlay()
    fun play()
    fun pause()
    /** Skips to the next song (repeat-one does not hold a skip). */
    fun next()
    /** FR-R322-4 — within the first 3 s: the previous song; after that: the start of this one. */
    fun previous()
    fun seekTo(positionMs: Long)
    fun playAt(index: Int)
    fun cycleRepeat()
    fun toggleShuffle()
    fun move(from: Int, to: Int)
    fun remove(index: Int)
    fun playNext(track: MusicTrackItem)
    fun addToQueue(track: MusicTrackItem)
    /**
     * R380 — the queue grew or changed around the song that plays (a cast's long queue arriving in parts, a cast's
     * queue edit): [tracks] is the whole queue and [index] the playing song's place in it. The song plays on.
     */
    fun replaceQueue(tracks: List<MusicTrackItem>, index: Int)
    /** FR-R322-10 — the mini bar's swipe-down: stop, and the queue is gone. */
    fun clear()
    /** FR-R322-12 — a video took the screen: the song stops (the queue stays, paused where it was). */
    fun stopForVideo()
    fun retry()
    fun skip()
    fun currentPositionMs(): Long
    /** FR-R322-9 — *Even out volume*. */
    fun setEvenVolume(on: Boolean)
    /** R337 (FR-R337-6) — the desktop bar's volume slider, 0..1; the phone has hardware keys (a no-op there). */
    fun setUserVolume(level: Float)

    // ── R323 — a book ──

    /** Load [detail] and play (or park) [part] at [positionMs] inside it. The songs' queue is dropped. */
    fun playBook(detail: AudiobookDetail, part: Int, positionMs: Long, play: Boolean = true)
    /** FR-R323-4 — −30 s / +30 s, across part boundaries. */
    fun skipBy(deltaMs: Long)
    /** A place in the book (a chapter, a bookmark, the chapter seek bar). */
    fun seekBook(bookMs: Long)
    /** FR-R323-5 — this book's speed (remembered per book on the server). */
    fun setSpeed(speed: Double)
    /** FR-R323-5 — the sleep timer; null turns it off. */
    fun setSleep(timer: SleepTimer?)
    /** FR-R323-9 — *Skip silences in audiobooks*. */
    fun setSkipSilence(on: Boolean)
    /** Book time now (the parts before, plus the place in this one); 0 with no book. */
    fun bookPositionMs(): Long
}

/** A queue as a device remembers it (dev review 7): the songs, which one, where in it, and for whom. */
@Serializable
data class MusicQueueSnapshot(
    val userId: String,
    val tracks: List<MusicTrackItem>,
    val index: Int,
    val positionMs: Long,
    val context: MusicContext? = null,
)
