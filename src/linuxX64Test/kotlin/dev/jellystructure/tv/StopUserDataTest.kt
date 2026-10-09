package dev.jellystructure.tv

import dev.jellystructure.auth.jellyfinStartBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 310 (dev review items 6–7) — the start body carries `PositionTicks`; one user-data write per stop, from the stop's decision. */
class StopUserDataTest {
    private val twoHours = 2 * 3_600_000L

    @Test fun `the start body carries PositionTicks — which Jellyfin reads`() {
        val body = jellyfinStartBody("film-1", 11_170_140_000L, "film-1", "psid")
        assertTrue("\"PositionTicks\":11170140000" in body, body)
        assertTrue("\"StartPositionTicks\":11170140000" in body, body)
    }

    @Test fun `an ordinary unfinished stop is unwatched at its place`() =
        assertEquals(StopUserData(false, 1_117_014L, null), stopUserData(1_117_014L, SessionPlan(durationMs = twoHours, playedAtStart = false), startOverUnplayed = false))

    @Test fun `an unfinished replay of a watched film stays watched — at its place`() =
        assertEquals(StopUserData(null, 1_117_014L, null), stopUserData(1_117_014L, SessionPlan(durationMs = twoHours, playedAtStart = true), false))

    @Test fun `a finished stop is watched with no place`() =
        assertEquals(StopUserData(true, 0L, null), stopUserData(twoHours * 95 / 100, SessionPlan(durationMs = twoHours), false))

    @Test fun `a stop at the trusted credits marker is finished · R347`() {
        val d = stopUserData(twoHours * 86 / 100, SessionPlan(durationMs = twoHours, creditsStartMs = twoHours * 85 / 100), false)
        assertEquals(true, d.played)
    }

    @Test fun `a stop before 5 percent keeps no place — as Jellyfin's own rule`() =
        assertEquals(StopUserData(false, 0L, null), stopUserData(60_000L, SessionPlan(durationMs = twoHours, playedAtStart = false), false))

    @Test fun `an unfinished shuffled entry keeps its place and flag from before the shuffle`() {
        val plan = SessionPlan(durationMs = 660_000L, shuffle = true, priorPositionMs = 0L, priorLastPlayed = "2026-09-01T10:00:00.0000000Z", watchedAtStart = true)
        assertEquals(StopUserData(null, 0L, "2026-09-01T10:00:00.0000000Z"), stopUserData(300_000L, plan, false))
    }

    @Test fun `a cleared Start over that did not finish is unwatched at its place · R343`() =
        assertEquals(StopUserData(false, 300_000L, null), stopUserData(300_000L, SessionPlan(durationMs = 660_000L, playedAtStart = true), startOverUnplayed = true))

    @Test fun `a plan rebuilt after a restart does not know the flag and leaves it alone`() =
        assertEquals(StopUserData(null, 1_117_014L, null), stopUserData(1_117_014L, SessionPlan(durationMs = twoHours), false))

    // 312 — found live 2026-10-09: a mark watched pressed within the read-back's delay was undone by the read-back.
    @Test fun `the read-back stands down when the viewer marked the item after the stop`() {
        assertTrue(readBackStillOurs(stopLandedAtMs = 1_000L, viewerMarkedAtMs = null))
        assertTrue(readBackStillOurs(stopLandedAtMs = 1_000L, viewerMarkedAtMs = 900L))    // marked before the stop: ours
        kotlin.test.assertFalse(readBackStillOurs(stopLandedAtMs = 1_000L, viewerMarkedAtMs = 2_500L)) // marked since: theirs
    }
}
