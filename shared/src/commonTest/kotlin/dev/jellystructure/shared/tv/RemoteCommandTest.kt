package dev.jellystructure.shared.tv

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R354 (FR-R354-2) — one reading of every remote command, and what it does to a player. */
class RemoteCommandTest {

    private fun ps(command: String, seek: Long? = null) = remoteCommandOf(PlaystateCommandEnvelope("playstate_command", command, seek))
    private fun pc(command: String, block: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) =
        remoteCommandOf(PlayerCommandEnvelope("player_command", command, block?.let { buildJsonObject(it) }))

    @Test
    fun playstateCommandsReadAsThePlayersOwnControls() {
        assertEquals(RemoteCommand.Stop, ps("Stop"))
        assertEquals(RemoteCommand.Pause, ps("Pause"))
        assertEquals(RemoteCommand.Play, ps("Unpause"))
        assertEquals(RemoteCommand.Toggle, ps("PlayPause"))
        assertEquals(RemoteCommand.SeekTo(123_456L), ps("Seek", 123_456L))
        assertNull(ps("Seek"), "a seek without a position is nothing")
        assertEquals(RemoteCommand.Next, ps("NextTrack"))
        assertEquals(RemoteCommand.Previous, ps("PreviousTrack"))
        assertEquals(RemoteCommand.SeekBy(-10_000L), ps("Rewind"))
        assertEquals(RemoteCommand.SeekBy(30_000L), ps("FastForward"))
        // R155's lower-case spelling from /api/remote/command reads the same.
        assertEquals(RemoteCommand.Pause, ps("pause"))
        assertNull(ps("Explode"))
    }

    @Test
    fun playerCommandsCoverPhase236AndThe296VolumeSteps() {
        assertEquals(RemoteCommand.SeekTo(5_000L), pc("seek") { put("position_ms", 5_000) })
        assertEquals(RemoteCommand.SeekBy(-15_000L), pc("skip") { put("delta_ms", -15_000) })
        assertEquals(RemoteCommand.Next, pc("next"))
        assertEquals(RemoteCommand.Previous, pc("previous"))
        assertEquals(RemoteCommand.SetVolume(35), pc("set_volume") { put("volume", 35) })
        assertEquals(RemoteCommand.SetVolume(100), pc("set_volume") { put("volume", 140) })
        assertEquals(RemoteCommand.VolumeStep(10), pc("volume_up"))
        assertEquals(RemoteCommand.VolumeStep(-10), pc("volume_down"))
        assertEquals(RemoteCommand.Mute(true), pc("mute") { put("muted", true) })
        assertEquals(RemoteCommand.Mute(false), pc("mute") { put("muted", false) })
        assertEquals(RemoteCommand.Mute(null), pc("mute"))
        assertNull(pc("set_subtitle") { put("index", 2) }, "track selection is not a playback command")
        assertNull(pc("seek"), "a seek without a position is nothing")
    }

    private class Recorder : RemotePlayer {
        val calls = mutableListOf<String>()
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun toggle() { calls += "toggle" }
        override fun stop() { calls += "stop" }
        override fun seekTo(positionMs: Long) { calls += "seekTo $positionMs" }
        override fun seekBy(deltaMs: Long) { calls += "seekBy $deltaMs" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun setVolume(level: Float, muted: Boolean) { calls += "volume $level ${if (muted) "muted" else "on"}" }
    }

    @Test
    fun eachCommandDrivesThePlayerOnce() {
        val p = Recorder()
        val v = RemoteVolume()
        listOf(
            RemoteCommand.Pause, RemoteCommand.Play, RemoteCommand.Toggle, RemoteCommand.SeekTo(9_000L), RemoteCommand.SeekBy(30_000L),
            RemoteCommand.Next, RemoteCommand.Previous, RemoteCommand.Stop,
        ).forEach { it.applyTo(p, v) }
        assertEquals(listOf("pause", "play", "toggle", "seekTo 9000", "seekBy 30000", "next", "previous", "stop"), p.calls)
    }

    @Test
    fun volumeKeepsLevelAndMuteTogether() {
        val p = Recorder()
        val v = RemoteVolume(percent = 60)
        RemoteCommand.SetVolume(35).applyTo(p, v)
        RemoteCommand.Mute(null).applyTo(p, v)        // toggle → muted, level kept
        RemoteCommand.Mute(null).applyTo(p, v)        // toggle → back at 35
        RemoteCommand.Mute(true).applyTo(p, v)
        RemoteCommand.VolumeStep(10).applyTo(p, v)    // a step unmutes
        RemoteCommand.VolumeStep(-100).applyTo(p, v)  // clamped at 0
        assertEquals(listOf("volume 0.35 on", "volume 0.35 muted", "volume 0.35 on", "volume 0.35 muted", "volume 0.45 on", "volume 0.0 on"), p.calls)
    }

    @Test
    fun aReceiverSyncsTheDevicesOwnVolumeFirst() {
        val v = RemoteVolume()
        v.sync(0.2f, false)
        v.apply(RemoteCommand.VolumeStep(10))
        assertEquals(30, v.percent)
        v.sync(null, true)
        v.apply(RemoteCommand.Mute(null))
        assertEquals(false, v.muted)
    }
}
