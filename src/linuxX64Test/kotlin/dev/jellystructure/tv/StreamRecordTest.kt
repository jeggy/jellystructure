package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.PlaybackQoeReport
import dev.jellystructure.shared.tv.QoeStall
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 309 (FR-309-1/-2/-3/-8) — the per-device stream record: what counts, what it lets a device take. */
class StreamRecordTest {
    private val path = "/tmp/jellystructure-test-309-record-${getpid()}.db"
    private val db = createDatabase(path)

    @AfterTest fun cleanup() { platform.posix.unlink(path) }

    private fun stall(after: Long, ms: Long, variant: Long? = null) =
        QoeStall(afterFirstFrameMs = after, positionMs = after, bufferedMs = 0, durationMs = ms, phase = "mid", variantBps = variant)

    @Test fun `a stall counts at 2 s — or two within a minute`() {
        assertEquals(emptyList(), countingStalls(listOf(stall(10_000, 1_999))))
        assertEquals(1, countingStalls(listOf(stall(10_000, 2_000))).size)
        assertEquals(2, countingStalls(listOf(stall(10_000, 300), stall(65_000, 400))).size)
        assertEquals(emptyList(), countingStalls(listOf(stall(10_000, 300), stall(80_000, 400))))
    }

    @Test fun `a two-minute hold with no stall proves the stream`() {
        val sample = RecordSample(8_000_000, emptyList(), perItem = true)
        val (r1, h1) = foldRecord(StreamRecord(), null, sample, now = 1_000)
        assertNull(r1.provenBps)
        val (r2, h2) = foldRecord(r1, h1, sample, now = 1_000 + PROOF_HOLD_SEC - 1)
        assertNull(r2.provenBps)
        val (r3, _) = foldRecord(r2, h2, sample, now = 1_000 + PROOF_HOLD_SEC)
        assertEquals(8_000_000, r3.provenBps)
        assertEquals(1_000 + PROOF_HOLD_SEC, r3.provenAt)
        // A switch restarts the hold.
        val (_, h4) = foldRecord(r3, h2, RecordSample(12_000_000, emptyList(), true), now = 1_200)
        assertEquals(12_000_000, h4!!.variantBps)
        assertEquals(1_200, h4.sinceSec)
    }

    @Test fun `a counting stall lowers the record for 24 h and restarts the hold`() {
        val rec = StreamRecord(provenBps = 20_000_000, provenAt = 900)
        val hold = HoldState(24_000_000, 500, 0)
        val (after, h) = foldRecord(rec, hold, RecordSample(24_000_000, listOf(stall(60_000, 3_000, 24_000_000)), true), now = 1_000)
        assertEquals(24_000_000, after.stalledBps)
        assertEquals(1_000, after.stalledAt)
        assertEquals(1, h!!.stallsSeen)
        assertEquals(1_000, h.sinceSec)
        assertEquals(19_200_000, canTake(after, 1_000))            // min(proven 20, stalled 24 × 0.8)
        assertEquals(20_000_000, canTake(after, 1_000 + STALL_HOLD_SEC + 1))   // the stall is forgotten after 24 h
        // The same stall posted again is not new.
        val (again, _) = foldRecord(after, h, RecordSample(24_000_000, listOf(stall(60_000, 3_000, 24_000_000)), true), now = 1_050)
        assertEquals(after, again)
        // A short stall restarts the hold but lowers nothing.
        val (short, h2) = foldRecord(StreamRecord(), HoldState(8_000_000, 0, 0), RecordSample(8_000_000, listOf(stall(10_000, 500)), true), now = 300)
        assertNull(short.stalledBps)
        assertEquals(300, h2!!.sinceSec)
    }

    @Test fun `a legacy row is never read`() {
        val rec = StreamRecord()
        val (after, hold) = foldRecord(rec, null, RecordSample(8_000_000, listOf(stall(10_000, 9_000)), perItem = false), now = 10)
        assertEquals(rec, after)
        assertNull(hold)
    }

    @Test fun `what a device can take`() {
        assertNull(canTake(null, 1_000))
        assertNull(canTake(StreamRecord(), 1_000))
        // A measurement × 0.7, a proof as is, the higher of the two.
        assertEquals(14_000_000, canTake(StreamRecord(measuredBps = 20_000_000, measuredAt = 900), 1_000))
        assertEquals(16_000_000, canTake(StreamRecord(provenBps = 16_000_000, provenAt = 900, measuredBps = 20_000_000, measuredAt = 900), 1_000))
        assertEquals(17_500_000, canTake(StreamRecord(provenBps = 4_500_000, provenAt = 900, measuredBps = 25_000_000, measuredAt = 900), 1_000))
        // Older than 30 days is none.
        assertNull(canTake(StreamRecord(provenBps = 16_000_000, provenAt = 0), RECORD_MAX_AGE_SEC + 1))
        // The newer of the probe and the QoE rows' measurement wins.
        assertEquals(7_000_000, canTake(StreamRecord(measuredBps = 20_000_000, measuredAt = 100), 1_000, qoeMeasuredBps = 10_000_000, qoeMeasuredAt = 900))
        assertEquals(14_000_000, canTake(StreamRecord(measuredBps = 20_000_000, measuredAt = 950), 1_000, qoeMeasuredBps = 10_000_000, qoeMeasuredAt = 900))
    }

    @Test fun `the probe runs when nothing was measured within 24 h`() {
        assertTrue(probeNeeded(null, 1_000_000, null))
        assertFalse(probeNeeded(StreamRecord(measuredBps = 20_000_000, measuredAt = 1_000_000 - 3_600), 1_000_000, null))
        assertTrue(probeNeeded(StreamRecord(measuredBps = 20_000_000, measuredAt = 1_000_000 - MEASUREMENT_FRESH_SEC - 1), 1_000_000, null))
        assertFalse(probeNeeded(null, 1_000_000, qoeAt = 1_000_000 - 60))
    }

    @Test fun `the negotiation's cap`() {
        assertEquals(NO_RECORD_DIRECT_PLAY_BPS, negotiationCap(adaptive = true, take = null))
        assertNull(negotiationCap(adaptive = false, take = null))   // a player that can't climb is never pinned low
        assertEquals(30_000_000, negotiationCap(adaptive = true, take = 30_000_000))
        assertEquals(30_000_000, negotiationCap(adaptive = false, take = 30_000_000))
    }

    @Test fun `a direct play proves the file's whole bitrate`() {
        // Found live 2026-10-09: a 2 h 4 min remux, 51.6 Mbps of picture + TrueHD, 52.9 GB on disk. Proving the picture
        // plus a little audio (54.5 Mbps) capped the same film's next start below Jellyfin's 56.7 Mbps: a transcode.
        val proof = directPlayStreamBps(52_921_788_821, 7_469_280, 51_625_050)!!
        assertTrue(proof in 57_000_000..58_500_000, "$proof")
        assertTrue(proof >= 52_921_788_821L * 8_000 / 7_469_280, "never below what Jellyfin compares")
        assertEquals((51_625_050 * 1.05).toLong() + 256_000L, directPlayStreamBps(null, 7_469_280, 51_625_050), "no size: the old estimate")
        assertEquals((51_625_050 * 1.05).toLong() + 256_000L, directPlayStreamBps(52_921_788_821, 0, 51_625_050))
        assertNull(directPlayStreamBps(null, null, null))
    }

    @Test fun `a TV app with no record plays what its decoder takes and not a guess`() {
        // Found live 2026-10-09: a Cast Connect start on the BRAVIA with no record was capped at 8 Mbps (a 1080p
        // transcode of a 4K film it plays directly). The decode ceiling (max_video_bitrate) decides instead.
        assertNull(negotiationCap(adaptive = true, take = null, tvApp = true))
        assertEquals(20_000_000, negotiationCap(adaptive = true, take = 20_000_000, tvApp = true), "a record still caps it")
        assertEquals(NO_RECORD_DIRECT_PLAY_BPS, negotiationCap(adaptive = true, take = null, tvApp = false), "a phone keeps the 8 Mbps start")
        fun dev(platform: String?, kind: String) = dev.jellystructure.auth.DeviceData("d", "t", "u", "anna", "jt", isAdmin = false, platform = platform, kind = kind)
        assertTrue(dev("tv", "tv").isTvApp)
        assertFalse(dev("tv", "cast").isTvApp, "the Cast web receiver is not the TV app")
        assertFalse(dev("cast", "cast").isTvApp)
        assertFalse(dev("phone", "phone").isTvApp)
        assertFalse(dev("mac", "tv").isTvApp)
        assertFalse(dev(null, "tv").isTvApp, "a device that never said what it is gets no exemption")
    }

    @Test fun `the store keeps one row per device`() {
        val store = StreamRecordStore(db)
        assertNull(store.get("tv"))
        store.put("tv", StreamRecord(provenBps = 8_000_000, provenAt = 10), now = 10)
        store.put("tv", StreamRecord(provenBps = 8_000_000, provenAt = 10, stalledBps = 24_000_000, stalledAt = 20), now = 20)
        assertEquals(StreamRecord(provenBps = 8_000_000, provenAt = 10, stalledBps = 24_000_000, stalledAt = 20), store.get("tv"))
        assertEquals(setOf("tv"), store.all().keys)
    }

    @Test fun `the QoE row keeps the start rung and the encoder`() {
        val qoe = PlaybackQoeStore(db)
        qoe.record("tv", "ps-1", PlaybackQoeReport(itemId = "film", directPlay = false, perItem = true, startVariantBps = 4_400_000), encoder = "ours")
        val row = qoe.recentForDevice("tv").single()
        assertEquals(4_400_000, row.startVariantBps)
        assertEquals("ours", row.encoder)
    }
}

/** 313d (FR-313-6) — the picked image subtitle's place among the file's own subtitle streams (what `0:s:N` names). */
class EmbeddedSubtitleOrderTest {
    private fun s(type: String, index: Int, external: Boolean = false) =
        dev.jellystructure.auth.JellyfinMediaStream(type = type, index = index, isExternal = external)

    @Test fun `sidecars and other streams are not counted`() {
        val streams = listOf(s("Video", 0), s("Audio", 1), s("Subtitle", 2), s("Subtitle", 3, external = true), s("Subtitle", 4), s("Audio", 5))
        assertEquals(0, embeddedSubtitleOrder(streams, 2))
        assertEquals(1, embeddedSubtitleOrder(streams, 4))
        assertNull(embeddedSubtitleOrder(streams, 3))   // a sidecar is not in the file
        assertNull(embeddedSubtitleOrder(streams, 9))
    }
}
