package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinUserData
import kotlin.test.Test
import kotlin.test.assertEquals

/** R306 (FR-R306-2) — *Play Again* plays from the start; a return from the background keeps its own place. */
class StartPositionTest {
    private val minute = 60_000L * 10_000L

    @Test
    fun `an unwatched item resumes where Jellyfin saved it`() {
        assertEquals(9 * minute, resolveStartPositionTicks(null, JellyfinUserData(playbackPositionTicks = 9 * minute, played = false)))
    }

    @Test
    fun `a watched item starts at zero whatever position it carries`() {
        assertEquals(0L, resolveStartPositionTicks(null, JellyfinUserData(playbackPositionTicks = 9 * minute, played = true)))
    }

    @Test
    fun `the client's own position wins over both`() {
        assertEquals(120_000L * 10_000L, resolveStartPositionTicks(120_000L, JellyfinUserData(playbackPositionTicks = 9 * minute, played = true)))
        assertEquals(0L, resolveStartPositionTicks(-5L, null))
    }

    @Test
    fun `no user data starts at zero`() {
        assertEquals(0L, resolveStartPositionTicks(null, null))
    }
}
