package dev.jellystructure.shared.tv

/*
 * R354 (FR-R354-7) — what a remote command does on the Cast receiver (`ravilo-cast`: Chromecast, Google TV, Nest Hub,
 * an audio-only speaker). The receiver reaches CAF through `dynamic` and has no test source set, so the decision lives
 * here, pure and tested; the receiver only carries it out.
 */

/** What the receiver has loaded, as far as a command needs to know. */
data class ReceiverRemoteState(
    val loaded: Boolean,
    val music: Boolean,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val hasNextEpisode: Boolean = false,
    val hasPreviousEpisode: Boolean = false,
)

sealed interface ReceiverRemoteAction {
    data object Nothing : ReceiverRemoteAction
    data object Play : ReceiverRemoteAction
    data object Pause : ReceiverRemoteAction
    /** A song: the queue ends (the Stop key's path). A film: it stops and the idle view shows (*ended*, never *failed*). */
    data object Stop : ReceiverRemoteAction
    data class Seek(val positionMs: Long) : ReceiverRemoteAction
    /** By the queue's own rule (R286): repeat, the end of the queue. */
    data object MusicNext : ReceiverRemoteAction
    /** R322's rule: the start of the song after 3 s, the song before within them. */
    data object MusicPrevious : ReceiverRemoteAction
    data object EpisodeNext : ReceiverRemoteAction
    data object EpisodePrevious : ReceiverRemoteAction
    /** The device's own volume (`CastReceiverContext.setSystemVolumeLevel`/`Muted`), the one the senders' sliders show. */
    data class Volume(val level: Float, val muted: Boolean) : ReceiverRemoteAction
}

/** [volume] has been synced to the device's volume by the caller; a volume command moves it. */
fun receiverRemoteAction(cmd: RemoteCommand, s: ReceiverRemoteState, volume: RemoteVolume): ReceiverRemoteAction {
    if (!s.loaded) return ReceiverRemoteAction.Nothing
    fun seek(to: Long) = ReceiverRemoteAction.Seek(if (s.durationMs > 0) to.coerceIn(0L, s.durationMs) else to.coerceAtLeast(0L))
    return when (cmd) {
        RemoteCommand.Play -> ReceiverRemoteAction.Play
        RemoteCommand.Pause -> ReceiverRemoteAction.Pause
        RemoteCommand.Toggle -> if (s.playing) ReceiverRemoteAction.Pause else ReceiverRemoteAction.Play
        RemoteCommand.Stop -> ReceiverRemoteAction.Stop
        is RemoteCommand.SeekTo -> seek(cmd.positionMs)
        is RemoteCommand.SeekBy -> seek(s.positionMs + cmd.deltaMs)
        RemoteCommand.Next -> when {
            s.music -> ReceiverRemoteAction.MusicNext
            s.hasNextEpisode -> ReceiverRemoteAction.EpisodeNext
            else -> ReceiverRemoteAction.Nothing
        }
        RemoteCommand.Previous -> when {
            s.music -> ReceiverRemoteAction.MusicPrevious
            s.hasPreviousEpisode -> ReceiverRemoteAction.EpisodePrevious
            else -> ReceiverRemoteAction.Nothing
        }
        is RemoteCommand.SetVolume, is RemoteCommand.VolumeStep, is RemoteCommand.Mute ->
            if (volume.apply(cmd)) ReceiverRemoteAction.Volume(volume.level, volume.muted) else ReceiverRemoteAction.Nothing
    }
}

/** R354 (FR-R354-7) — a paused receiver reports progress this often, so the server's 90 s watchdog keeps its session. */
const val RECEIVER_PAUSED_BEAT_MS = 30_000L
