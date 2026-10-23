package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2312 (DEB-2252, FR-R330-7) — when the receiver presses play itself under a stuck BUFFERING. */
class CastBufferingHealTest {
    private fun press(
        music: Boolean = true, paused: Boolean = false, state: String? = "BUFFERING",
        from: Long? = 0L, time: Long = 2_000L, healed: String? = null, key: String = "a#1",
    ) = castShouldPressPlay(music, paused, state, from, time, healed, key)

    @Test
    fun `time moved two seconds under buffering presses once`() {
        assertTrue(press(time = 2_000L))
        assertTrue(press(from = 5_000L, time = 9_300L))
    }

    @Test
    fun `less than two seconds of movement does not press`() {
        assertFalse(press(time = 1_999L))
        assertFalse(press(time = 0L))
    }

    @Test
    fun `no note of where buffering began does not press`() {
        assertFalse(press(from = null))
    }

    @Test
    fun `a paused song or a paused state never presses`() {
        assertFalse(press(paused = true))
        assertFalse(press(state = "PAUSED"))
        assertFalse(press(state = "PLAYING"))
        assertFalse(press(state = "IDLE"))
        assertFalse(press(state = null))
    }

    @Test
    fun `a film is never pressed for`() {
        assertFalse(press(music = false))
    }

    @Test
    fun `a song already pressed for is not pressed for twice but the next load is`() {
        assertFalse(press(healed = "a#1", key = "a#1"))
        // Repeat one: the same song, a new load, is a new song.
        assertTrue(press(healed = "a#1", key = "a#2"))
        assertTrue(press(healed = "a#1", key = "b#3"))
    }
}
