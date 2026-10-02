package dev.jellystructure.ravilo.ui.music

import androidx.media3.common.Player
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R356 (FR-R356-3, FR-R356-12, FR-R356-14a) — what each control on Ravilo's media card does while casting. The card is
 * a Media3 player mirroring the cast; the lock screen, the notification, a headset, a car or a watch drive it through
 * the platform's commands, and each must reach the device as the app's own button does. The ■ is *Stop casting*.
 */
@RunWith(RobolectricTestRunner::class)
class CastRemotePlayerTest {
    private val heard = mutableListOf<String>()
    private val actions = object : CastRemotePlayer.Actions {
        override fun play() { heard += "play" }
        override fun pause() { heard += "pause" }
        override fun next() { heard += "next" }
        override fun previous() { heard += "previous" }
        override fun seekTo(positionMs: Long) { heard += "seek $positionMs" }
        override fun setVolume(level: Double) { heard += "volume $level" }
    }

    private fun song(playing: Boolean = true, hasNext: Boolean = true) = CastCard(
        key = "s1", title = "First", subtitle = "Band", album = "Album One", artUrl = null, positionMs = 30_000, durationMs = 200_000,
        playing = playing, buffering = false, music = true, hasNext = hasNext, volume = 0.25,
    )

    private fun player(card: CastCard = song()) = CastRemotePlayer(actions).also { it.show(card); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the card's stop is its Stop casting button and dismissing the card stops nothing`() {
        // FR-R356-14a — the ■ is the custom action; it, and nothing else, ends the cast.
        assertTrue(CastSessionRemote.onCardAction(CastSessionRemote.STOP_CASTING_ACTION))
        assertFalse(CastSessionRemote.onCardAction("ravilo.book.back30"), "a book's -30 s is the engine's own")
        // The platform's Stop (what swiping a paused card away sends) is not offered: a dismissed card only hides,
        // as the mini bar's swipe does (R324 FR-R324-7) — the speaker is never stopped by tidying the shade.
        val p = player()
        assertFalse(p.isCommandAvailable(Player.COMMAND_STOP))
        p.stop(); idle()
        assertEquals(emptyList(), heard)
    }

    @Test
    fun `pause pauses and play plays, on the device`() {
        val p = player()
        p.pause(); idle()
        p.show(song(playing = false)); idle()
        p.play(); idle()
        assertEquals(listOf("pause", "play"), heard)
    }

    @Test
    fun `next and previous go to the device's queue`() {
        val p = player()
        p.seekToNext(); idle()
        p.seekToPrevious(); idle()
        assertEquals(listOf("next", "previous"), heard)
    }

    @Test
    fun `seek and the volume keys reach the device`() {
        val p = player()
        p.seekTo(90_000L); idle()
        p.increaseDeviceVolume(0); idle()
        assertEquals("seek 90000", heard[0])
        assertEquals("volume 0.3", heard[1])   // 25 % → one 5 % step up
    }

    @Test
    fun `the card carries the cast's route once it is known`() {
        val p = player()
        assertEquals(androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE, p.deviceInfo.playbackType)
        assertNull(p.deviceInfo.routingControllerId)
        p.routingControllerId = "cast-session-1"; idle()
        assertEquals("cast-session-1", p.deviceInfo.routingControllerId)
    }
}
