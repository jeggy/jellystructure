package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals

/** R354 (FR-R354-7) — the Cast receiver's reading of every dashboard command, for a song and for a film. */
class ReceiverRemoteTest {
    private val song = ReceiverRemoteState(loaded = true, music = true, playing = true, positionMs = 60_000L, durationMs = 200_000L)
    private val episode = ReceiverRemoteState(loaded = true, music = false, playing = false, positionMs = 600_000L, durationMs = 1_500_000L, hasNextEpisode = true, hasPreviousEpisode = false)
    private val film = ReceiverRemoteState(loaded = true, music = false, playing = true, positionMs = 5_000L, durationMs = 6_000_000L)

    private fun act(c: String, s: ReceiverRemoteState, seek: Long? = null, v: RemoteVolume = RemoteVolume()) =
        receiverRemoteAction(remoteCommandOf(PlaystateCommandEnvelope("playstate_command", c, seek))!!, s, v)

    @Test
    fun aSongOnASpeaker() {
        assertEquals(ReceiverRemoteAction.Pause, act("Pause", song))
        assertEquals(ReceiverRemoteAction.Play, act("Unpause", song))
        assertEquals(ReceiverRemoteAction.Pause, act("PlayPause", song), "playing → pause")
        assertEquals(ReceiverRemoteAction.MusicNext, act("NextTrack", song))
        assertEquals(ReceiverRemoteAction.MusicPrevious, act("PreviousTrack", song))
        assertEquals(ReceiverRemoteAction.Seek(50_000L), act("Rewind", song))
        assertEquals(ReceiverRemoteAction.Seek(90_000L), act("FastForward", song))
        assertEquals(ReceiverRemoteAction.Seek(200_000L), act("Seek", song, seek = 999_000L), "clamped to the song")
        assertEquals(ReceiverRemoteAction.Stop, act("Stop", song))
    }

    @Test
    fun anEpisodeAndAFilmOnATv() {
        assertEquals(ReceiverRemoteAction.Play, act("PlayPause", episode), "paused → play")
        assertEquals(ReceiverRemoteAction.EpisodeNext, act("NextTrack", episode))
        assertEquals(ReceiverRemoteAction.Nothing, act("PreviousTrack", episode), "the first episode handed over")
        assertEquals(ReceiverRemoteAction.Nothing, act("NextTrack", film), "a film has no next")
        assertEquals(ReceiverRemoteAction.Seek(0L), act("Rewind", film), "never before the start")
        assertEquals(ReceiverRemoteAction.Stop, act("Stop", film))
    }

    @Test
    fun nothingLoadedNothingDone() {
        assertEquals(ReceiverRemoteAction.Nothing, act("Unpause", song.copy(loaded = false)))
    }

    @Test
    fun volumeMovesTheDevicesOwnLevel() {
        val v = RemoteVolume()
        v.sync(0.25f, false)   // what CastReceiverContext.getSystemVolume() said
        assertEquals(ReceiverRemoteAction.Volume(0.35f, false), receiverRemoteAction(RemoteCommand.VolumeStep(10), song, v))
        assertEquals(ReceiverRemoteAction.Volume(0.35f, true), receiverRemoteAction(RemoteCommand.Mute(null), song, v))
        assertEquals(ReceiverRemoteAction.Volume(0.8f, false), receiverRemoteAction(RemoteCommand.SetVolume(80), song, v))
    }
}
