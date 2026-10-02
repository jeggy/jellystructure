package dev.jellystructure.ravilo.ui

import dev.jellystructure.ravilo.ui.music.MusicCast
import dev.jellystructure.ravilo.ui.music.MusicEngine
import dev.jellystructure.ravilo.ui.music.MusicPlayback
import dev.jellystructure.ravilo.ui.music.MusicVolume
import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.RemotePlayer
import dev.jellystructure.shared.tv.RemoteVolume
import dev.jellystructure.shared.tv.applyTo

/** R354 (FR-R354-3) — which player a remote command goes to. */
enum class RemoteTarget { VIDEO, MUSIC, NONE }

/** R354 (FR-R354-3) — the open film player; else the music player when it holds something; else nobody. */
fun remoteTargetFor(videoOpen: Boolean, musicActive: Boolean): RemoteTarget = when {
    videoOpen -> RemoteTarget.VIDEO
    musicActive -> RemoteTarget.MUSIC
    else -> RemoteTarget.NONE
}

/**
 * R354 — where the app's remote commands land (the Jellyfin dashboard through 298's bridge, Home Assistant, a phone
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
        return target
    }
}

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
    override fun seekTo(positionMs: Long) = MusicPlayback.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) {
        val st = MusicPlayback.state.value
        if (st.book != null) { MusicPlayback.skipBy(deltaMs); return }
        val end = st.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        MusicPlayback.seekTo((MusicPlayback.currentPositionMs() + deltaMs).coerceIn(0L, end))
    }
    /** R322's rules: previous restarts the song after 3 s; a book's next/previous are its ±30 s. */
    override fun next() = MusicPlayback.next()
    override fun previous() = MusicPlayback.previous()
    /**
     * The desktop's level is the bar's own (its slider follows; a mute silences the player and leaves the slider and
     * the remembered level alone); a phone's is the player's output for this session (a phone's own volume is its
     * keys); while casting it is the speaker's.
     */
    override fun setVolume(level: Float, muted: Boolean) {
        val out = if (muted) 0f else level
        when {
            isDesktopPlatform && muted && !MusicCast.linked.value -> MusicEngine.setUserVolume(0f)
            isDesktopPlatform -> MusicVolume.set(out)
            MusicCast.linked.value -> MusicCast.setVolume(out.toDouble())
            else -> MusicEngine.setUserVolume(out)
        }
    }
}
