package dev.jellystructure.ravilo.ui

import dev.jellystructure.ravilo.ui.music.MusicCast
import dev.jellystructure.ravilo.ui.music.MusicEngine
import dev.jellystructure.ravilo.ui.music.MusicPlayback
import dev.jellystructure.ravilo.ui.music.MusicVolume
import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.RemotePlayer
import dev.jellystructure.shared.tv.RemoteVolume
import dev.jellystructure.shared.tv.VolumeReport
import dev.jellystructure.shared.tv.applyTo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/** R357 (FR-R357-4) — how long a volume must hold still before its player reports it at once. */
const val VOLUME_SETTLE_MS = 300L

/** R354 (FR-R354-3) — which player a remote command goes to. */
enum class RemoteTarget { VIDEO, MUSIC, NONE }

/** R354 (FR-R354-3) — the open film player; else the music player when it holds something; else nobody. */
fun remoteTargetFor(videoOpen: Boolean, musicActive: Boolean): RemoteTarget = when {
    videoOpen -> RemoteTarget.VIDEO
    musicActive -> RemoteTarget.MUSIC
    else -> RemoteTarget.NONE
}

/**
 * R354 — where the app's remote commands land (the Jellyfin dashboard through 299's bridge, Home Assistant, a phone
 * driving this device). Main thread only: [RaviloApp] collects the events socket's commands on it, because the music
 * engine is main-thread only (R353). The film player registers itself while composed ([attachVideo]); with none,
 * music takes the command. Each player's volume is kept across films and songs, so a level set from the dashboard
 * holds for the next one.
 */
object RemoteControl {
    private var video: RemotePlayer? = null
    val videoVolume = RemoteVolume()
    val musicVolume = RemoteVolume()

    /** Seams for the tests; the app keeps the defaults. */
    var musicPlayer: RemotePlayer = MusicRemotePlayer
    var musicActive: () -> Boolean = { MusicPlayback.state.value.active }
    var syncMusicVolume: (RemoteVolume) -> Unit = { v -> if (isDesktopPlatform && !v.muted) v.sync(MusicVolume.level.value, null) }
    var musicCasting: () -> Boolean = { MusicCast.linked.value }

    private val _volumeChanged = MutableSharedFlow<RemoteTarget>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /**
     * R357 (FR-R357-4) — a player's level or mute just changed (a dashboard command, the desktop bar's slider): that
     * player sends one progress report at once, so the dashboard does not wait for the next heartbeat. The film
     * player's store and the music engines listen ([onVolumeSettled]); no new heartbeat.
     */
    val volumeChanged: SharedFlow<RemoteTarget> = _volumeChanged.asSharedFlow()

    /**
     * R357 (FR-R357-4) — calls [report] each time [target]'s volume has settled: a burst (a dragged slider, a dashboard
     * slider sending several levels) is one report, not one per step. Suspends until its caller's scope ends.
     */
    suspend fun onVolumeSettled(target: RemoteTarget, report: suspend () -> Unit) {
        volumeChanged.filter { it == target }.collectLatest { delay(VOLUME_SETTLE_MS); report() }
    }

    /** R357 (FR-R357-3) — what the film player reports: the level the dashboard's commands move (R354's, kept across films). */
    fun videoVolumeReport(): VolumeReport = videoVolume.report()

    /**
     * R357 (FR-R357-3) — what the music player playing on this device reports: on a desktop the bar's level and the mute
     * R354 applies, on a phone the player's output level for the session (never the system stream its keys move,
     * FR-R357-5). Null while the music is cast to a speaker: then the progress is the receiver's to report.
     */
    fun musicVolumeReport(): VolumeReport? {
        if (musicCasting()) return null
        syncMusicVolume(musicVolume)
        return musicVolume.report()
    }

    /** R357 (FR-R357-4) — the desktop bar's slider moved: the level is the viewer's and the music is audible again. */
    fun musicLevelSet(level: Float) {
        musicVolume.sync(level, false)
        _volumeChanged.tryEmit(RemoteTarget.MUSIC)
    }

    /** The film player is open; returns its detach. A level set earlier from the dashboard applies at once. */
    fun attachVideo(player: RemotePlayer): () -> Unit {
        video = player
        if (videoVolume.percent != 100 || videoVolume.muted) player.setVolume(videoVolume.level, videoVolume.muted)
        return { if (video === player) video = null }
    }

    fun dispatch(cmd: RemoteCommand): RemoteTarget {
        val v = video
        val target = remoteTargetFor(v != null, musicActive())
        when (target) {
            RemoteTarget.VIDEO -> cmd.applyTo(v!!, videoVolume)
            RemoteTarget.MUSIC -> { syncMusicVolume(musicVolume); cmd.applyTo(musicPlayer, musicVolume) }
            RemoteTarget.NONE -> Unit
        }
        if (target != RemoteTarget.NONE && cmd.movesVolume) _volumeChanged.tryEmit(target)   // R357 (FR-R357-4)
        return target
    }
}

private val RemoteCommand.movesVolume: Boolean
    get() = this is RemoteCommand.SetVolume || this is RemoteCommand.VolumeStep || this is RemoteCommand.Mute

/**
 * R354 (FR-R354-4) — the music player as a remote command sees it: [MusicPlayback], the door every music screen uses,
 * so a command routes to the speaker while the phone casts and to the engine otherwise, exactly like a press.
 */
object MusicRemotePlayer : RemotePlayer {
    override fun play() = MusicPlayback.play()
    override fun pause() = MusicPlayback.pause()
    override fun toggle() = MusicPlayback.togglePlay()
    /** The song stops and the queue stays, paused where it was (FR-R322-12): the mini bar stays and Play resumes. */
    override fun stop() { if (MusicCast.linked.value) MusicCast.stop() else MusicEngine.stopForVideo() }
    /**
     * R369 (FR-R369-9, amendment 2026-10-14, DEB-1099) — a **book's** seek is book time, and crosses parts through the
     * door R323's own whole-book scrubber uses. Until this arm existed, `seekTo` applied a book's position *inside the
     * playing part*: a drag past the part's share of the remote's bar skipped the unheard rest of the part and rewrote
     * the stored place forward. Its sibling [seekBy] has had the arm since R323; the asymmetry is what hid this.
     */
    override fun seekTo(positionMs: Long) {
        if (doors.bookPlaying) doors.seekBook(positionMs.coerceAtLeast(0L)) else doors.seekTo(positionMs)
    }
    override fun seekBy(deltaMs: Long) {
        if (doors.bookPlaying) { doors.skipBy(deltaMs); return }
        val end = doors.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        doors.seekTo((doors.currentPositionMs() + deltaMs).coerceIn(0L, end))
    }

    /** The player doors a remote seek takes; [MusicPlaybackDoors] in the app, a recorder in the test that holds the two
     *  arms above to one predicate. Not a general seam — the seek pair only. */
    internal var doors: MusicSeekDoors = MusicPlaybackDoors
    /** R322's rules: previous restarts the song after 3 s; a book's next/previous are its ±30 s. */
    override fun next() = MusicPlayback.next()
    override fun previous() = MusicPlayback.previous()
    // R369 (dev review item 3) — a session's own commands: the same doors the music screens press.
    override fun jump(index: Int) { if (index in MusicPlayback.state.value.queue.indices) MusicPlayback.playAt(index) }
    override fun setShuffle(on: Boolean) { if (MusicPlayback.state.value.shuffle != on) MusicPlayback.toggleShuffle() }
    override fun setRepeat(mode: String) {
        val want = when (mode) { "all" -> dev.jellystructure.ravilo.ui.music.RepeatMode.ALL; "one" -> dev.jellystructure.ravilo.ui.music.RepeatMode.ONE; else -> dev.jellystructure.ravilo.ui.music.RepeatMode.OFF }
        repeat(3) { if (MusicPlayback.state.value.repeat != want) MusicPlayback.cycleRepeat() }
    }
    override fun queueMove(index: Int, to: Int) = MusicPlayback.move(index, to)
    override fun queueRemove(index: Int) = MusicPlayback.remove(index)
    /**
     * The desktop's level is the bar's own (its slider follows; a mute silences the player and leaves the slider and
     * the remembered level alone); a phone's is the player's output for this session (a phone's own volume is its
     * keys); while casting it is the speaker's.
     */
    override fun setVolume(level: Float, muted: Boolean) {
        val out = if (muted) 0f else level
        when {
            isDesktopPlatform && muted && !MusicCast.linked.value -> MusicEngine.setUserVolume(0f)
            isDesktopPlatform -> MusicVolume.set(out, byViewer = false)   // R357 — the dispatch says it changed
            MusicCast.linked.value -> MusicCast.setVolume(out.toDouble())
            else -> MusicEngine.setUserVolume(out)
        }
    }
}

/**
 * R369 (FR-R369-9) — the four player calls a remote seek can make, behind one interface so the book arm of `seekTo` and
 * of `seekBy` are held to the same predicate by a test. The app's implementation is [MusicPlaybackDoors]; nothing else
 * in the app goes through here.
 */
internal interface MusicSeekDoors {
    /** Is a book playing (not a song)? The player state's `book`. */
    val bookPlaying: Boolean
    /** The playing item's own length, for [MusicRemotePlayer.seekBy]'s clamp on a song. */
    val durationMs: Long
    fun currentPositionMs(): Long
    /** A position in the playing song. */
    fun seekTo(positionMs: Long)
    /** A position in the **book**, across its parts (R323's whole-book scrubber). */
    fun seekBook(bookMs: Long)
    /** A book's ±delta, in book time, R322's rule (the engine crosses the part boundary). */
    fun skipBy(deltaMs: Long)
}

internal object MusicPlaybackDoors : MusicSeekDoors {
    override val bookPlaying: Boolean get() = MusicPlayback.state.value.book != null
    override val durationMs: Long get() = MusicPlayback.state.value.durationMs
    override fun currentPositionMs(): Long = MusicPlayback.currentPositionMs()
    override fun seekTo(positionMs: Long) = MusicPlayback.seekTo(positionMs)
    override fun seekBook(bookMs: Long) = MusicPlayback.seekBook(bookMs)
    override fun skipBy(deltaMs: Long) = MusicPlayback.skipBy(deltaMs)
}
