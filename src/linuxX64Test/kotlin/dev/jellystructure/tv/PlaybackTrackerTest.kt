package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for [PlaybackTracker] — the register behind the Phase 110 (FR B.2) stop watchdog and
 * the Phase 111 (FR B.1) "now playing" device list.
 *
 * Every case here is a bug that shipped: the tracking was keyed by deviceId alone, so a binge's earlier
 * episodes fell out of it (phantom, unreapable "Now Playing" sessions in the Jellyfin dashboard), a stop
 * removed whatever that device had tracked rather than the item that actually stopped, and a progress
 * tick arriving after a stop resurrected the finished session and overwrote its final resume position —
 * i.e. the wrong Continue Watching rows.
 */
class PlaybackTrackerTest {

    private var now = 1_000_000L
    private fun tracker() = PlaybackTracker { now }

    private fun device(id: String = "tv1") = DeviceData(
        deviceId = id,
        deviceToken = "dt-$id",
        jellyfinUserId = "user-1",
        jellyfinUsername = "jeggy",
        jellyfinUserToken = "jt-$id",
        isAdmin = false,
    )

    @Test
    fun tracksEveryConcurrentPlaybackOfOneDevice() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "ep1", 0L)
        now += 1_000
        t.started(tv, "ep2", 0L)

        // Both are live as far as Jellyfin is concerned (the play-session id is per item), so both must
        // stay reapable — episode 1 used to be overwritten and leak forever.
        assertEquals(setOf("ep1", "ep2"), t.tracked().map { it.jellyfinId }.toSet())
        // The one actually on screen is the most recently heartbeated one.
        assertEquals("ep2", t.nowPlaying(tv.deviceId))
    }

    @Test
    fun stoppingOneItemKeepsTheOtherTracked() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "ep1", 0L)
        now += 1_000
        t.started(tv, "ep2", 0L)

        // A late stop for episode 1 must not wipe the episode that is actually playing.
        t.stopped(tv, "ep1")

        assertEquals(listOf("ep2"), t.tracked().map { it.jellyfinId })
        assertEquals("ep2", t.nowPlaying(tv.deviceId))
    }

    @Test
    fun progressAfterStopIsRejectedAndDoesNotResurrectTheSession() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "ep1", 0L)
        t.stopped(tv, "ep1")

        now += 1_000  // a tick that was already in flight when the stop landed
        assertFalse(t.heartbeat(tv, "ep1", 42_000L))
        assertTrue(t.tracked().isEmpty())
        assertNull(t.nowPlaying(tv.deviceId))
    }

    @Test
    fun anExplicitRestartEndsThePostStopGraceWindow() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "ep1", 0L)
        t.stopped(tv, "ep1")

        // Returning from the background re-arms the same item — that is a real session again.
        t.started(tv, "ep1", 42_000L)
        now += 1_000
        assertTrue(t.heartbeat(tv, "ep1", 52_000L))
        assertEquals("ep1", t.nowPlaying(tv.deviceId))
    }

    @Test
    fun heartbeatGoesStaleAfterTheWatchdogWindow() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "ep1", 0L)

        val tracked = t.tracked().single()
        now += 89_000
        assertFalse(t.isHeartbeatStale(tracked))
        now += 2_000  // > 90s without a progress report: app killed, network dropped, HDMI off
        assertTrue(t.isHeartbeatStale(tracked))
    }

    @Test
    fun tracksDevicesIndependently() = runBlocking {
        val t = tracker()
        val livingRoom = device("tv1")
        val bedroom = device("tv2")
        t.started(livingRoom, "movie", 0L)
        t.started(bedroom, "ep1", 0L)

        t.stopped(bedroom, "ep1")

        assertEquals("movie", t.nowPlaying(livingRoom.deviceId))
        assertNull(t.nowPlaying(bedroom.deviceId))
    }

    // ─── Phase 180 ────────────────────────────────────────────────────────────

    @Test
    fun stoppedReturnsTheJellyfinPlaySessionIdSoTheEncodeCanBeReleased() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "movie", 0L, jellyfinPlaySessionId = "jf-abc")

        assertEquals("jf-abc", t.stopped(tv, "movie"))
    }

    @Test
    fun stoppingSomethingNeverStartedReturnsNull() = runBlocking {
        val t = tracker()
        val tv = device()

        assertNull(t.stopped(tv, "movie"))
    }

    @Test
    fun aStopThatArrivesBeforeStartedFlagsTheNextStartedCall() = runBlocking {
        // FR-180-3 — Back pressed during negotiation: the client's stop reaches the server before
        // startPlayback's own started() call does.
        val t = tracker()
        val tv = device()

        t.stopped(tv, "movie")
        val result = t.started(tv, "movie", 0L, jellyfinPlaySessionId = "jf-abc")

        assertTrue(result.stopAlreadyArrived)
        assertNull(result.superseded)  // nothing else was active for this key
    }

    @Test
    fun aStopThatArrivesBeforeStartedIsConsumedOnce() = runBlocking {
        // The pending-stop flag must not leak into a LATER, unrelated start for the same key.
        val t = tracker()
        val tv = device()

        t.stopped(tv, "movie")
        t.started(tv, "movie", 0L, jellyfinPlaySessionId = "jf-abc")
        t.stopped(tv, "movie")  // the abandon-teardown's own stopped() call, per PlaybackService
        val result = t.started(tv, "movie", 0L, jellyfinPlaySessionId = "jf-xyz")

        assertFalse(result.stopAlreadyArrived)
    }

    @Test
    fun aPendingStopExpiresAfterItsTtl() = runBlocking {
        // Bounded per FR-180-3's own open question — a start that never completes must not leave a
        // stop pending forever.
        val t = tracker()
        val tv = device()

        t.stopped(tv, "movie")
        now += 31_000  // > PENDING_STOP_TTL_MS (30s)
        t.tracked()  // the janitor tick that prunes it (mirrors the watchdog's own cadence)
        val result = t.started(tv, "movie", 0L)

        assertFalse(result.stopAlreadyArrived)
    }

    @Test
    fun aSecondStartForTheSameKeyReportsTheSupersededSession() = runBlocking {
        // FR-180-1 — a re-play without an intervening stop must not leak the first session's encode.
        val t = tracker()
        val tv = device()

        t.started(tv, "movie", 0L, jellyfinPlaySessionId = "jf-first")
        val result = t.started(tv, "movie", 5_000L, jellyfinPlaySessionId = "jf-second")

        assertEquals("jf-first", result.superseded?.jellyfinPlaySessionId)
        assertFalse(result.stopAlreadyArrived)
        // The tracker itself now reflects only the new session.
        assertEquals("jf-second", t.stopped(tv, "movie"))
    }
}
