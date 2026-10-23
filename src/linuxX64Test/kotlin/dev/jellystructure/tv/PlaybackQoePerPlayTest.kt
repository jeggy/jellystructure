package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.PlaybackQoeReport
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** DEB-2205 (FR-8, Acceptance 7) — one QoE row per play, and the encoder's summary on it. */
class PlaybackQoePerPlayTest {
    private val path = "/tmp/jellystructure-test-deb2205-qoe-${getpid()}.db"
    private val store = PlaybackQoeStore(createDatabase(path))

    @AfterTest fun cleanup() { platform.posix.unlink(path) }

    private val summary = EncoderSummary(segments = 30, speedMin = 1.2, speedMedian = 1.4, longestWaitMs = 1_800, waitsOver1s = 2, inputMBps = 9.5, rungs = listOf("2160p@20000k"))

    @Test
    fun `a play that joins the live session still gets its own key`() {
        // A retry one second later joins the session the first start made: same session id, so the start time is part of the key.
        assertNotEquals(qoePlayKey("ps-1", "tv-film", 1_000), qoePlayKey("ps-1", "tv-film", 2_000))
        assertEquals("ps-1@1000", qoePlayKey("ps-1", "tv-film", 1_000))
        assertEquals("tv-film@1000", qoePlayKey(null, "tv-film", 1_000), "no session (a test, or the viewer left during the negotiation)")
    }

    @Test
    fun `two plays of one title leave two QoE rows`() {
        store.record("tv", qoePlayKey("ps-1", "tv-film", 1_000), PlaybackQoeReport(itemId = "film", rebufferCount = 3, perItem = true))
        store.record("tv", qoePlayKey("ps-1", "tv-film", 2_000), PlaybackQoeReport(itemId = "film", rebufferCount = 0, perItem = true))
        val rows = store.recentForDevice("tv")
        assertEquals(2, rows.size, "the retry did not overwrite the first play")
        assertEquals(setOf(3, 0), rows.map { it.rebufferCount }.toSet())
        // A later report of the SAME play advances its row, never adds one.
        store.record("tv", qoePlayKey("ps-1", "tv-film", 2_000), PlaybackQoeReport(itemId = "film", rebufferCount = 1, perItem = true))
        assertEquals(2, store.recentForDevice("tv").size)
    }

    @Test
    fun `the encoder summary lands on the play's row whichever comes first`() {
        val key = qoePlayKey("ps-1", "tv-film", 1_000)
        // Row first, then the job stops.
        store.record("tv", key, PlaybackQoeReport(itemId = "film", directPlay = false))
        assertNull(store.recentForDevice("tv").single().encoderSummary, "a play not (yet) served by our encoder has none")
        store.attachEncoderSummary("tv", "film", key, summary)
        assertEquals(summary, store.recentForDevice("tv").single().encoderSummary)
        // A later report rewrites the row and keeps the summary.
        store.record("tv", key, PlaybackQoeReport(itemId = "film", rebufferCount = 1, perItem = true))
        assertEquals(summary, store.recentForDevice("tv").single().encoderSummary)

        // Job stops first, the client's last report follows.
        val key2 = qoePlayKey("ps-2", "tv-film", 5_000)
        store.attachEncoderSummary("tv", "film", key2, summary)
        store.record("tv", key2, PlaybackQoeReport(itemId = "film"))
        assertEquals(summary, store.recentForDevice("tv").first { it.playSessionId == key2 }.encoderSummary)
    }

    @Test
    fun `a second stream of the same play merges into one summary`() {
        val key = qoePlayKey("ps-1", "tv-film", 1_000)
        store.record("tv", key, PlaybackQoeReport(itemId = "film"))
        store.attachEncoderSummary("tv", "film", key, summary)
        store.attachEncoderSummary("tv", "film", key, summary.copy(segments = 5, rungs = listOf("1080p@8000k")))
        val merged = store.recentForDevice("tv").single().encoderSummary!!
        assertEquals(35, merged.segments)
        assertEquals(listOf("2160p@20000k", "1080p@8000k"), merged.rungs)
    }
}
