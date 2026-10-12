package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R372 (FR-R372-12, amendment 2026-10-12) — the stop watchdog must not force-stop a play that is alive.
 *
 * Both cases here were measured on prod on 2026-10-12 (DEB-758) and both stop a viewer's film under them:
 *  - the heartbeat test waits 90 s but the socket test waited for nothing, so a play started in the second before
 *    its events socket came up was force-stopped on the next 30 s tick — one row was reaped **1.2 s** after it
 *    started, and R372 then kept the corpse for hours;
 *  - a flapping socket reaped a place that was still reporting: one session logged 112 `offline` and 89
 *    `back_online` events in 6 h 23 m, because each late heartbeat re-created a socket-less tracked entry that the
 *    next tick reaped.
 *
 * The decision under test is [PlaybackTracker.shouldReap] — the exact call [PlaybackService.stopWatchdogTick]
 * makes per tracked entry, so the tick and these tests cannot drift apart.
 */
class PlaybackWatchdogTest {

    private var now = 1_000_000L
    private fun tracker() = PlaybackTracker { now }

    private fun device(id: String = "tv1", kind: String = "tv") = DeviceData(
        deviceId = id,
        deviceToken = "dt-$id",
        jellyfinUserId = "user-1",
        jellyfinUsername = "jeggy",
        jellyfinUserToken = "jt-$id",
        isAdmin = false,
        kind = kind,
    )

    /** The tick's own call for a place whose events socket is not up. */
    private suspend fun reapedWithSocketDown(t: PlaybackTracker) =
        t.tracked().single().let { t.shouldReap(it, socketOpen = false, reconnecting = false) }

    @Test
    fun aJustStartedPlayIsNotForceStoppedBecauseItsSocketIsNotUpYet() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "movie", 0L)

        // The prod row: 1.2 s in, on the first 30 s tick after the start.
        now += 1_200
        assertFalse(reapedWithSocketDown(t))
        // And every tick inside the 90 s the heartbeat test already waits.
        now += 30_000
        assertFalse(reapedWithSocketDown(t))
        now += 58_000   // 89.2 s in
        assertFalse(reapedWithSocketDown(t))
    }

    @Test
    fun pastTheGraceAClosedSocketStillEndsThePlay() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "movie", 0L)

        // The app kept reporting (so the heartbeat test is happy) but its socket is gone: FR-218-11's test still
        // reaps it once the start's grace is over. A TV that holds no socket at all is a different case.
        now += 60_000
        assertTrue(t.heartbeat(tv, "movie", 60_000L))
        now += 31_000   // 91 s since the start, 31 s since the last report
        assertTrue(reapedWithSocketDown(t))
    }

    @Test
    fun aSilentPlayIsStillReapedByItsHeartbeatInsideTheSocketGrace() = runBlocking {
        val t = tracker()
        val cast = device("cast-1", kind = "cast")   // judged by heartbeat alone (FR-236-8)
        t.started(cast, "movie", 0L)

        now += 89_000
        assertFalse(t.shouldReap(t.tracked().single(), socketOpen = true, reconnecting = false))
        now += 2_000   // > 90 s with no report: the app was killed, the network dropped, the HDMI went off
        assertTrue(t.shouldReap(t.tracked().single(), socketOpen = true, reconnecting = false))
    }

    @Test
    fun aReportForAReapedPlayBringsItBackAndSurvivesTheNextTick() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "movie", 0L)

        // The flap: the socket read as closed past the grace and the watchdog force-stopped the play.
        now += 95_000
        assertTrue(t.heartbeat(tv, "movie", 95_000L))
        assertTrue(reapedWithSocketDown(t))
        t.stopped(tv, "movie", 95_000L, reaped = true)
        assertTrue(t.tracked().isEmpty())

        // The viewer never left: their next 10 s report comes back under tracking at once (it used to be swallowed
        // by the post-stop grace, which exists for the viewer's OWN stop), and it is not reaped on the next tick
        // however long the socket stays down. "While reports keep arriving there is no force-stop."
        now += 10_000
        assertTrue(t.heartbeat(tv, "movie", 105_000L))
        assertFalse(reapedWithSocketDown(t))
        repeat(20) {
            now += 30_000
            assertTrue(t.heartbeat(tv, "movie", 105_000L + it * 30_000L))
            assertFalse(reapedWithSocketDown(t))
        }

        // And the heartbeat test still ends it when the place really does fall silent.
        now += 91_000
        assertTrue(reapedWithSocketDown(t))
    }

    @Test
    fun aLateTickForTheViewersOwnStopIsStillSwallowed() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "movie", 0L)
        t.stopped(tv, "movie", 42_000L)   // the viewer pressed Back; not a reap

        now += 1_000
        assertFalse(t.heartbeat(tv, "movie", 43_000L))
        assertTrue(t.tracked().isEmpty())
    }

    @Test
    fun anExplicitRestartAfterAReapIsJudgedByItsSocketAgain() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "movie", 0L)
        now += 95_000
        t.stopped(tv, "movie", 95_000L, reaped = true)

        // A new start is a new play, not a reaped one reporting back: it gets the 90 s grace from now, and the
        // socket test applies again afterwards.
        t.started(tv, "movie", 95_000L)
        now += 89_000
        assertFalse(reapedWithSocketDown(t))
        now += 2_000
        assertTrue(t.heartbeat(tv, "movie", 186_000L))   // reporting, so only the socket test can reap it
        assertTrue(reapedWithSocketDown(t))
    }

    @Test
    fun aReconnectingSessionIsUnaffected() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "movie", 0L)
        now += 95_000
        assertTrue(t.heartbeat(tv, "movie", 95_000L))

        // FR-R368-2 item 12 (a) — still judged by heartbeat alone while its session is reconnecting.
        assertFalse(t.shouldReap(t.tracked().single(), socketOpen = false, reconnecting = true))
        now += 91_000
        assertTrue(t.shouldReap(t.tracked().single(), socketOpen = false, reconnecting = true))
    }
}
