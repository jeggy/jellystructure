package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinUserData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // DEB-2205 (FR-7) — Jellyfin decides when another app played the item since this client's own stop.
    private val stopAt = 1_800_000_000_000L   // epoch ms of this client's last stop
    private fun iso(ms: Long) = dev.jellystructure.util.epochTicksToIso(ms * 10_000L)

    @Test
    fun `a record written after the client's stop is newer`() {
        val saved = JellyfinUserData(playbackPositionTicks = 454_335L * 10_000L, lastPlayedDate = iso(stopAt + 60_000L))
        assertTrue(jellyfinIsNewerThanClientStop(saved, stopAt))
        assertEquals(454_335L * 10_000L, resolveStartPositionTicks(null, saved))
    }

    @Test
    fun `a record that is only the client's own stop write is not newer`() {
        val own = JellyfinUserData(playbackPositionTicks = 0L, lastPlayedDate = iso(stopAt - 200L))
        assertFalse(jellyfinIsNewerThanClientStop(own, stopAt))
        assertFalse(jellyfinIsNewerThanClientStop(JellyfinUserData(lastPlayedDate = iso(stopAt + CLIENT_STOP_SLACK_MS - 1_000L)), stopAt))
        assertEquals(2_866L * 10_000L, resolveStartPositionTicks(2_866L, own))
    }

    @Test
    fun `no known stop or no date keeps the client's position`() {
        assertFalse(jellyfinIsNewerThanClientStop(JellyfinUserData(lastPlayedDate = iso(stopAt + 60_000L)), null))
        assertFalse(jellyfinIsNewerThanClientStop(JellyfinUserData(playbackPositionTicks = 5L), stopAt))
        assertFalse(jellyfinIsNewerThanClientStop(null, stopAt))
    }

    @Test
    fun `Play Again still starts at zero when Jellyfin wins on a played item`() {
        val played = JellyfinUserData(playbackPositionTicks = 9 * minute, played = true, lastPlayedDate = iso(stopAt + 60_000L))
        assertTrue(jellyfinIsNewerThanClientStop(played, stopAt))
        assertEquals(0L, resolveStartPositionTicks(null, played))
    }

    @Test
    fun `the tracker remembers a landed stop per device and item`() = kotlinx.coroutines.runBlocking {
        var now = 5_000L
        val t = PlaybackTracker { now }
        val d = dev.jellystructure.auth.DeviceData(deviceId = "tv1", deviceToken = "dt", jellyfinUserId = "u", jellyfinUsername = "n", jellyfinUserToken = "jt", isAdmin = false)
        assertNull(t.lastStopLandedAt(d, "film"))
        t.stopLanded(d, "film")
        now = 9_000L
        assertEquals(5_000L, t.lastStopLandedAt(d, "film"))
        assertNull(t.lastStopLandedAt(d, "other"))
    }
}
