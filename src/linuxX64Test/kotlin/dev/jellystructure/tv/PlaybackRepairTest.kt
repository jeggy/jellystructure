package dev.jellystructure.tv

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 310 (FR-310-7) / 312 (FR-312-5) — the dry run lists exactly the damaged, unfinished films and episodes. */
class PlaybackRepairTest {
    private val hour = 3_600_000L
    private fun c(id: String, pos: Long, at: String, src: String = "312") = RepairCandidate(id, "u1", pos, at, src)

    @Test fun `only damaged unfinished items that were not played again are put back`() = runBlocking {
        val now = mapOf(
            "wiped" to RepairItemNow("Wiped", 2 * hour, null, played = false, positionMs = 0L, lastPlayedDate = "2026-10-07T17:32:03Z"),
            "guessed" to RepairItemNow("Guessed", hour, null, played = true, positionMs = 0L, lastPlayedDate = "2026-10-06T20:30:00Z"),
            "finished" to RepairItemNow("Finished", hour, null, played = true, positionMs = 0L, lastPlayedDate = "2026-10-06T20:30:00Z"),
            "again" to RepairItemNow("Played again", hour, null, played = false, positionMs = 0L, lastPlayedDate = "2026-10-08T09:00:00Z"),
            "fine" to RepairItemNow("Fine", hour, null, played = false, positionMs = 600_000L, lastPlayedDate = "2026-10-06T20:00:00Z"),
        )
        val plan = planRepair(listOf(
            c("wiped", 1_117_014L, "2026-10-07T17:32:33Z"),
            c("guessed", 1_200_000L, "2026-10-06T20:24:58Z", "310"),
            c("finished", hour * 95 / 100, "2026-10-06T20:25:00Z", "310"),
            c("again", 900_000L, "2026-10-06T20:00:00Z"),
            c("fine", 600_000L, "2026-10-06T20:00:00Z"),
            c("song", 100_000L, "2026-10-06T20:00:00Z", "310"),
            c("shuffle0", 0L, "2026-10-06T20:00:00Z", "310"),
        )) { now[it.jellyfinId] }
        assertEquals(listOf("guessed", "wiped"), plan.map { it.jellyfinId })
        assertEquals(1_117_014L, plan.single { it.jellyfinId == "wiped" }.positionMs)
        assertEquals("2026-10-07T17:32:33Z", plan.single { it.jellyfinId == "wiped" }.lastPlayed)
    }

    @Test fun `the latest stop of an item wins`() = runBlocking {
        val plan = planRepair(listOf(c("x", 100_000L, "2026-10-06T10:00:00Z"), c("x", 700_000L, "2026-10-06T11:00:00Z"))) {
            RepairItemNow("X", 2 * hour, null, played = false, positionMs = 0L, lastPlayedDate = "2026-10-06T11:00:00Z")
        }
        assertEquals(listOf(700_000L), plan.map { it.positionMs })
    }
}
