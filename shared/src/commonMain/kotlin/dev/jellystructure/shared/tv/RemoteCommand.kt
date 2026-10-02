package dev.jellystructure.shared.tv

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/*
 * R354 (FR-R354-2) — one reading of a remote command for every Ravilo player: the app's film player and music player
 * (phone, TV, Mac, Linux, web) and the Cast receiver. The server sends a command on one of two carriers —
 * `playstate_command` (Jellyfin's own Playstate names, phase 110/R155) and `player_command` (phase 236's names, plus
 * 299's volume steps) — and every player used to read them its own way, or not at all. Pure, so it is tested once.
 */

/** What a remote command asks of a player. */
sealed interface RemoteCommand {
    data object Play : RemoteCommand
    data object Pause : RemoteCommand
    data object Toggle : RemoteCommand
    data object Stop : RemoteCommand
    data object Next : RemoteCommand
    data object Previous : RemoteCommand
    data class SeekTo(val positionMs: Long) : RemoteCommand
    data class SeekBy(val deltaMs: Long) : RemoteCommand
    /** 0–100. */
    data class SetVolume(val percent: Int) : RemoteCommand
    data class VolumeStep(val deltaPercent: Int) : RemoteCommand
    /** null = toggle. */
    data class Mute(val muted: Boolean?) : RemoteCommand
}

/** Rewind and FastForward move as far as the players' own skip buttons do (−10 s / +30 s). */
const val REMOTE_REWIND_MS = 10_000L
const val REMOTE_FORWARD_MS = 30_000L
/** VolumeUp / VolumeDown move the level by this many points. */
const val REMOTE_VOLUME_STEP = 10

/** `playstate_command` → a command, or null for one no player acts on. Case-insensitive. */
fun remoteCommandOf(env: PlaystateCommandEnvelope): RemoteCommand? = when (env.command.lowercase()) {
    "stop" -> RemoteCommand.Stop
    "pause" -> RemoteCommand.Pause
    "unpause" -> RemoteCommand.Play
    "playpause" -> RemoteCommand.Toggle
    "seek" -> env.seekPositionMs?.let { RemoteCommand.SeekTo(it.coerceAtLeast(0L)) }
    "nexttrack" -> RemoteCommand.Next
    "previoustrack" -> RemoteCommand.Previous
    "rewind" -> RemoteCommand.SeekBy(-REMOTE_REWIND_MS)
    "fastforward" -> RemoteCommand.SeekBy(REMOTE_FORWARD_MS)
    else -> null
}

/** `player_command` → a command, or null for one that is not about playback (track selection, subtitle size …). */
fun remoteCommandOf(env: PlayerCommandEnvelope): RemoteCommand? {
    val args = env.args
    fun long(key: String): Long? = (args?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    return when (env.command.lowercase()) {
        "seek" -> long("position_ms")?.let { RemoteCommand.SeekTo(it.coerceAtLeast(0L)) }
        "skip", "seek_relative" -> long("delta_ms")?.let { RemoteCommand.SeekBy(it) }
        "next" -> RemoteCommand.Next
        "previous" -> RemoteCommand.Previous
        "set_volume" -> long("volume")?.let { RemoteCommand.SetVolume(it.toInt().coerceIn(0, 100)) }
        "volume_up" -> RemoteCommand.VolumeStep(REMOTE_VOLUME_STEP)
        "volume_down" -> RemoteCommand.VolumeStep(-REMOTE_VOLUME_STEP)
        "mute" -> RemoteCommand.Mute((args?.get("muted") as? JsonPrimitive)?.booleanOrNull)
        else -> null
    }
}

/**
 * A player a remote command can drive. Each call is what the player's own control does, so a command looks exactly
 * like a press (FR-R354-4). [setVolume] gets the level the viewer chose and whether it is muted; a player without a
 * mute of its own plays a muted level as silence.
 */
interface RemotePlayer {
    fun play()
    fun pause()
    fun toggle()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun next()
    fun previous()
    /** [level] 0–1. */
    fun setVolume(level: Float, muted: Boolean)
}

/**
 * The volume a remote command moves: a level (0–100) and a mute, kept together so a step unmutes and an unmute brings
 * back the level from before. [sync] adopts what the device reports (a receiver's own volume) before a command.
 */
class RemoteVolume(percent: Int = 100, muted: Boolean = false) {
    var percent: Int = percent.coerceIn(0, 100)
        private set
    var muted: Boolean = muted
        private set

    fun sync(level: Float?, muted: Boolean?) {
        // R357 — rounded, not truncated: a device that stores 0.29 as 0.28999… reads back as 29, the level it was set to.
        level?.let { if (!it.isNaN()) percent = (it * 100).roundToInt().coerceIn(0, 100) }
        muted?.let { this.muted = it }
    }

    /** Applies a volume command; false when [cmd] is not one. */
    fun apply(cmd: RemoteCommand): Boolean {
        when (cmd) {
            is RemoteCommand.SetVolume -> { percent = cmd.percent.coerceIn(0, 100); muted = false }
            is RemoteCommand.VolumeStep -> { percent = (percent + cmd.deltaPercent).coerceIn(0, 100); muted = false }
            is RemoteCommand.Mute -> muted = cmd.muted ?: !muted
            else -> return false
        }
        return true
    }

    val level: Float get() = percent / 100f

    /** R357 (FR-R357-3) — this volume as a progress report carries it. */
    fun report(): VolumeReport = VolumeReport(percent, muted)
}

/**
 * R357 (FR-R357-1/-3) — what a player says about its volume in a progress report: its own level (0–100), the one the
 * dashboard's commands move, and whether it is muted. Jellyfin shows it as the session's `PlayState.VolumeLevel` and
 * `IsMuted`.
 */
data class VolumeReport(val percent: Int, val muted: Boolean)

/** R357 (FR-R357-3/-5) — a device's own level (0–1, a Cast receiver's system volume) as a report; null when the device
 *  did not say what it is (never a guessed 100). */
fun volumeReportOf(level: Double?, muted: Boolean?): VolumeReport? =
    level?.takeIf { !it.isNaN() }?.let { VolumeReport((it * 100).roundToInt().coerceIn(0, 100), muted ?: false) }

/** R357 (FR-R357-1) — the progress body: with [volume] its two fields, without it exactly the pre-R357 request. */
fun progressRequest(itemId: String, positionMs: Long, isPaused: Boolean, volume: VolumeReport?): PlaybackProgressRequest =
    PlaybackProgressRequest(itemId, positionMs, isPaused, volume?.percent?.coerceIn(0, 100), volume?.muted)

/** Drives [player] with this command; volume commands go through [volume] first. */
fun RemoteCommand.applyTo(player: RemotePlayer, volume: RemoteVolume) {
    when (this) {
        RemoteCommand.Play -> player.play()
        RemoteCommand.Pause -> player.pause()
        RemoteCommand.Toggle -> player.toggle()
        RemoteCommand.Stop -> player.stop()
        RemoteCommand.Next -> player.next()
        RemoteCommand.Previous -> player.previous()
        is RemoteCommand.SeekTo -> player.seekTo(positionMs)
        is RemoteCommand.SeekBy -> player.seekBy(deltaMs)
        is RemoteCommand.SetVolume, is RemoteCommand.VolumeStep, is RemoteCommand.Mute ->
            if (volume.apply(this)) player.setVolume(volume.level, volume.muted)
    }
}

/**
 * R354 (FR-R354-1) — the `remote=` list an app declares on its events socket (299 FR-299-1): it takes the dashboard's
 * *Play on* and messages, and every playback and volume command.
 */
const val REMOTE_DECLARATION_APP = "DisplayMessage,Play,PlayState,SetVolume,VolumeUp,VolumeDown,Mute,Unmute,ToggleMute"
/** R354 (FR-R354-7) — the Cast receiver obeys; it is never a *Play on* target. */
const val REMOTE_DECLARATION_RECEIVER = "PlayState,SetVolume,VolumeUp,VolumeDown,Mute,Unmute,ToggleMute"
/** R354 (FR-R354-9e) — a receiver with a screen (a TV, a Nest Hub, a Chromecast) also shows the dashboard's messages. */
const val REMOTE_DECLARATION_RECEIVER_DISPLAY = "DisplayMessage,$REMOTE_DECLARATION_RECEIVER"

/** R354 (FR-R354-9e) — what a receiver declares: a speaker (no screen) takes no message, so the dashboard offers none. */
fun receiverRemoteDeclaration(headless: Boolean): String = if (headless) REMOTE_DECLARATION_RECEIVER else REMOTE_DECLARATION_RECEIVER_DISPLAY
