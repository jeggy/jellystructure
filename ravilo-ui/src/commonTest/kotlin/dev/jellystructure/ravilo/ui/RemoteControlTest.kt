package dev.jellystructure.ravilo.ui

import dev.jellystructure.ravilo.ui.seams.MediaSocketHold
import dev.jellystructure.ravilo.ui.seams.acceptsPlayerCommand
import dev.jellystructure.ravilo.ui.seams.acceptsRemoteCommand
import dev.jellystructure.ravilo.ui.seams.acceptsServerMessage
import dev.jellystructure.shared.tv.PlaystateCommandEnvelope
import dev.jellystructure.shared.tv.PlayerCommandEnvelope
import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.RemotePlayer
import dev.jellystructure.shared.tv.RemoteVolume
import dev.jellystructure.shared.tv.remoteCommandOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R354 (FR-R354-3/-4/-5) — which player a remote command reaches in the app, what it does there, and the socket rule. */
class RemoteControlTest {

    private class Recorder(val name: String, val log: MutableList<String>) : RemotePlayer {
        override fun play() { log += "$name play" }
        override fun pause() { log += "$name pause" }
        override fun toggle() { log += "$name toggle" }
        override fun stop() { log += "$name stop" }
        override fun seekTo(positionMs: Long) { log += "$name seekTo $positionMs" }
        override fun seekBy(deltaMs: Long) { log += "$name seekBy $deltaMs" }
        override fun next() { log += "$name next" }
        override fun previous() { log += "$name previous" }
        override fun setVolume(level: Float, muted: Boolean) { log += "$name volume ${(level * 100).toInt()}${if (muted) " muted" else ""}" }
    }

    private val log = mutableListOf<String>()
    private var musicHolds = false
    private val savedPlayer = RemoteControl.musicPlayer
    private val savedActive = RemoteControl.musicActive
    private val savedSync = RemoteControl.syncMusicVolume

    @BeforeTest fun setUp() {
        RemoteControl.musicPlayer = Recorder("music", log)
        RemoteControl.musicActive = { musicHolds }
        RemoteControl.syncMusicVolume = {}
    }
    @AfterTest fun tearDown() {
        RemoteControl.musicPlayer = savedPlayer
        RemoteControl.musicActive = savedActive
        RemoteControl.syncMusicVolume = savedSync
    }

    private fun ps(c: String, seek: Long? = null) = remoteCommandOf(PlaystateCommandEnvelope("playstate_command", c, seek))!!

    @Test
    fun theOpenFilmTakesTheCommandThenMusicThenNobody() {
        assertEquals(RemoteTarget.VIDEO, remoteTargetFor(videoOpen = true, musicActive = true))
        assertEquals(RemoteTarget.MUSIC, remoteTargetFor(videoOpen = false, musicActive = true))
        assertEquals(RemoteTarget.NONE, remoteTargetFor(videoOpen = false, musicActive = false))

        musicHolds = true
        val detach = RemoteControl.attachVideo(Recorder("film", log))
        assertEquals(RemoteTarget.VIDEO, RemoteControl.dispatch(ps("Pause")))
        assertEquals(RemoteTarget.VIDEO, RemoteControl.dispatch(ps("NextTrack")))
        detach()
        assertEquals(RemoteTarget.MUSIC, RemoteControl.dispatch(ps("Pause")))
        assertEquals(RemoteTarget.MUSIC, RemoteControl.dispatch(ps("Seek", 61_000L)))
        musicHolds = false
        assertEquals(RemoteTarget.NONE, RemoteControl.dispatch(ps("Unpause")), "nothing playing: dropped")
        assertEquals(listOf("film pause", "film next", "music pause", "music seekTo 61000"), log)
    }

    @Test
    fun aDetachOfAnOlderFilmDoesNotUnregisterTheNewOne() {
        val first = RemoteControl.attachVideo(Recorder("a", log))
        val second = RemoteControl.attachVideo(Recorder("b", log))
        first()   // the outgoing episode's dispose runs after the next one composed
        RemoteControl.dispatch(ps("PlayPause"))
        second()
        assertEquals(listOf("b toggle"), log)
    }

    @Test
    fun everyDashboardCommandReachesTheMusicPlayerAsAPress() {
        musicHolds = true
        listOf("Pause", "Unpause", "PlayPause", "NextTrack", "PreviousTrack", "Rewind", "FastForward", "Stop").forEach { RemoteControl.dispatch(ps(it)) }
        RemoteControl.dispatch(remoteCommandOf(PlayerCommandEnvelope("player_command", "set_volume", buildJsonObject { put("volume", 40) }))!!)
        RemoteControl.dispatch(remoteCommandOf(PlayerCommandEnvelope("player_command", "mute", null))!!)
        RemoteControl.dispatch(remoteCommandOf(PlayerCommandEnvelope("player_command", "volume_up", null))!!)
        assertEquals(
            listOf("music pause", "music play", "music toggle", "music next", "music previous", "music seekBy -10000", "music seekBy 30000",
                "music stop", "music volume 40", "music volume 40 muted", "music volume 50"),
            log,
        )
    }

    @Test
    fun aVolumeSetFromTheDashboardHoldsForTheNextFilm() {
        val detach = RemoteControl.attachVideo(Recorder("film1", log))
        RemoteControl.dispatch(RemoteCommand.SetVolume(30))
        detach()
        val detach2 = RemoteControl.attachVideo(Recorder("film2", log))
        detach2()
        RemoteControl.dispatch(RemoteCommand.SetVolume(100))   // nobody: dropped, the film level stays 30
        assertEquals(listOf("film1 volume 30", "film2 volume 30"), log)
        // Leave the shared object as found for other tests.
        RemoteControl.videoVolume.apply(RemoteCommand.SetVolume(100))
    }

    @Test
    fun playingMusicHoldsTheSocketOffScreenAndTenMinutesAfterAPause() {
        val hold = MediaSocketHold(tailMs = 600_000L)
        hold.update(active = true, playing = false, nowMs = 0L)
        assertFalse(hold.holds(0L), "a queue loaded paused that never played holds nothing")
        hold.update(active = true, playing = true, nowMs = 1_000L)
        assertTrue(hold.holds(1_000_000L), "playing holds it for as long as it plays")
        hold.update(active = true, playing = false, nowMs = 2_000L)
        hold.update(active = true, playing = false, nowMs = 300_000L)   // a later state change does not restart the tail
        assertTrue(hold.holds(601_999L))
        assertFalse(hold.holds(602_000L), "ten minutes after the pause it lets go")
        hold.update(active = true, playing = true, nowMs = 700_000L)
        hold.update(active = false, playing = false, nowMs = 701_000L)
        assertFalse(hold.holds(701_000L), "a cleared queue lets go at once")
    }

    @Test
    fun offScreenOnlyPlaybackCommandsAreAcceptedAndOnlyWhileMediaHoldsTheSocket() {
        assertTrue(acceptsPlayerCommand(onScreen = false, mediaHoldsSocket = true))
        assertFalse(acceptsPlayerCommand(onScreen = false, mediaHoldsSocket = false))
        assertTrue(acceptsPlayerCommand(onScreen = true, mediaHoldsSocket = false))
        assertFalse(acceptsRemoteCommand(onScreen = false, signedIn = true), "play_item and navigate keep R293's rule")
    }

    @Test
    fun aDashboardMessageIsShownOnlyOnScreen() {
        // FR-R354-9a — music playing here keeps the socket open off screen; a message that arrives then is dropped.
        assertTrue(acceptsServerMessage(onScreen = true))
        assertFalse(acceptsServerMessage(onScreen = false))
    }

    @Test
    fun remoteVolumeStartsFull() {
        val v = RemoteVolume()
        assertEquals(100, v.percent)
        assertFalse(v.muted)
    }
}
