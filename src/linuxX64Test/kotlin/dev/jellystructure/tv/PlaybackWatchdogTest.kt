package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
        now += FRESH_REPORT_MS + 1_000   // DEB-3036 (FR-1): the report's own 30 s hold is over
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
        now += FRESH_REPORT_MS + 1_000   // DEB-3036 (FR-1): a report in the last 30 s holds the socket test, so let it age
        assertTrue(reapedWithSocketDown(t))
    }

    /**
     * R372 (FR-R372-11) — the socket-close path in Server.kt, both ways round, on exactly the case FR-R372-12's
     * grace would otherwise swallow: a play two seconds old whose place is a browser tab.
     *
     * A tab holds its events socket for as long as it exists (DEB-759 §4: `AppOnScreen` is `true` for the web
     * client), so an abrupt end is the place going away and is read at once — acceptance A1's row reads *offline ·
     * paused* within ~10 s instead of sitting at *playing* for 90 s. R293's deliberate `background` close is the
     * opposite: the app let its socket go off screen on purpose, and a minimised desktop keeps its film.
     */
    @Test
    fun aCleanBackgroundCloseDoesNotReapButAnAbruptOneDoes() = runBlocking {
        val t = tracker()
        val web = device("tab-1", kind = "web")   // judged by its socket (needsEventsSocket) — not cast, not screen
        t.started(web, "movie", 0L)
        now += 2_000
        val p = t.tracked().single()

        // The close that is NOT evidence: R293's own `background`, so Server.kt passes no abrupt device and the
        // grace stands. (Which close is which is EventsCloseCause.isAbrupt — see EventsSocketHealthTest.)
        assertFalse(t.shouldReap(p, socketOpen = false, reconnecting = false, socketEndedAbruptly = false))
        // The close that is: the tab was closed.
        assertTrue(t.shouldReap(p, socketOpen = false, reconnecting = false, socketEndedAbruptly = true))

        // An abrupt end also goes past the other hold, a fromReport entry — a play the watchdog reaped once and
        // whose place reported back would otherwise never be read as gone again.
        t.stopped(web, "movie", 2_000L, reaped = true)
        now += 10_000
        assertTrue(t.heartbeat(web, "movie", 12_000L))
        now += FRESH_REPORT_MS + 1_000   // DEB-3036 (FR-1): the report's own 30 s hold is over
        val back = t.tracked().single()
        assertFalse(t.shouldReap(back, socketOpen = false, reconnecting = false, socketEndedAbruptly = false))
        assertTrue(t.shouldReap(back, socketOpen = false, reconnecting = false, socketEndedAbruptly = true))

        // And an abrupt close is never read against a device that is connected after all: Server.kt still consults
        // isDeviceConnected, so a reconnect or a `replaced` slot (a newer socket of the same device) stops the reap.
        assertFalse(t.shouldReap(back, socketOpen = true, reconnecting = false, socketEndedAbruptly = true))
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

    /**
     * DEB-3036 (trouble-never-reaches-the-viewer FR-1) — a device that reported progress in the last 30 s is not reaped on
     * the socket test on any tick, the abrupt override included; a tab that never reported (two seconds into a film) still is.
     */
    @Test
    fun aFreshReportHoldsTheSocketTestEvenAgainstAnAbruptEnd() = runBlocking {
        val t = tracker()
        val web = device("tab-1", kind = "web")
        t.started(web, "movie", 0L)
        now += 2_000
        // Two seconds in, no report yet: the start's own heartbeat time is not a report.
        assertTrue(t.shouldReap(t.tracked().single(), socketOpen = false, reconnecting = false, socketEndedAbruptly = true))

        now += 8_000
        assertTrue(t.heartbeat(web, "movie", 8_000L))
        now += 20_000   // 20 s after the report: inside the 30 s
        val p = t.tracked().single()
        assertTrue(t.hasFreshReport(p))
        assertFalse(t.shouldReap(p, socketOpen = false, reconnecting = false, socketEndedAbruptly = true))
        assertFalse(t.shouldReap(p, socketOpen = false, reconnecting = false, socketEndedAbruptly = false))

        now += 11_000   // 31 s after it
        assertFalse(t.hasFreshReport(t.tracked().single()))
        assertTrue(t.shouldReap(t.tracked().single(), socketOpen = false, reconnecting = false, socketEndedAbruptly = true))
    }

    /** A reaped entry's play-session id comes back with the report that revives it, so the viewer's own stop can still release the stream. */
    @Test
    fun aReapedPlaysSessionIdSurvivesTheReportThatRevivesIt() = runBlocking {
        val t = tracker()
        val tv = device()
        t.started(tv, "movie", 0L, jellyfinPlaySessionId = "psid-9")
        now += 40_000
        assertEquals("psid-9", t.stopped(tv, "movie", 40_000L, reaped = true))
        now += 10_000
        assertTrue(t.heartbeat(tv, "movie", 50_000L))
        assertEquals("psid-9", t.tracked().single().jellyfinPlaySessionId)
        assertEquals("psid-9", t.stopped(tv, "movie", 60_000L))
    }

    /**
     * R372 (FR-R372-11, amended 2026-10-30, DEB-3036) — what the watchdog's `isDeviceConnected` answers ([TvEventBus.isPresent])
     * over the three cases the amendment names, on a play an hour old whose place stopped reporting 40 s before.
     */
    @Test
    fun anAbruptlyEndedSocketReadsAsGoneOnlyAfterFifteenSeconds() = runBlocking {
        val t = tracker()
        val tv = device()
        val absences = SocketAbsences { now }
        t.started(tv, "movie", 0L)
        now += 3_600_000
        assertTrue(t.heartbeat(tv, "movie", 3_600_000L))
        now += 40_000   // its last report is 40 s old: only the socket test can reap it
        var open = true
        suspend fun reap(abrupt: Boolean) = t.tracked().single().let {
            t.shouldReap(it, socketOpen = open || absences.heldFor(tv.deviceId, SOCKET_GONE_MS), reconnecting = false, socketEndedAbruptly = abrupt)
        }

        // Caddy reloads: the close, then the socket is back 1.1 s later. Neither the close's own read nor a periodic tick reaps.
        open = false; absences.left(tv.deviceId)
        assertFalse(reap(abrupt = true))
        now += 1_100
        open = true; absences.joined(tv.deviceId)
        assertFalse(reap(abrupt = true))
        assertFalse(reap(abrupt = false))

        // No socket for 15 s: read as gone at 15 s, not before, on the close's read and on a periodic tick alike.
        open = false; absences.left(tv.deviceId)
        now += SOCKET_GONE_MS - 1
        assertFalse(reap(abrupt = true))
        assertFalse(reap(abrupt = false))   // a periodic tick inside the 15 s
        now += 1
        assertTrue(reap(abrupt = true))
        assertTrue(reap(abrupt = false))
    }

    @Test
    fun aTabClosedTwoSecondsIntoAFilmIsStillReadAsGoneAtFifteenSeconds() = runBlocking {
        val t = tracker()
        val web = device("tab-1", kind = "web")
        val absences = SocketAbsences { now }
        t.started(web, "movie", 0L)
        now += 2_000
        absences.left(web.deviceId)
        now += SOCKET_GONE_MS - 1
        assertFalse(t.shouldReap(t.tracked().single(), socketOpen = absences.heldFor(web.deviceId, SOCKET_GONE_MS), reconnecting = false, socketEndedAbruptly = true))
        now += 1
        assertTrue(t.shouldReap(t.tracked().single(), socketOpen = absences.heldFor(web.deviceId, SOCKET_GONE_MS), reconnecting = false, socketEndedAbruptly = true))
        // A clean `background` close at the same moment keeps the grace: a minimised desktop keeps its film.
        assertFalse(t.shouldReap(t.tracked().single(), socketOpen = false, reconnecting = false, socketEndedAbruptly = false))
    }

    @Test
    fun aDeviceNeverSeenToLeaveIsNotHeld() {
        val absences = SocketAbsences { now }
        assertFalse(absences.heldFor("never-seen", SOCKET_GONE_MS))
    }
}
