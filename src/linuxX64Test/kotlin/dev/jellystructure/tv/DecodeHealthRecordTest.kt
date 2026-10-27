package dev.jellystructure.tv

import dev.jellystructure.auth.deviceProfile
import dev.jellystructure.auth.maxStreamingBitrate
import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.PlaybackQoeReport
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2205 (FR-2, FR-3) — direct play whenever the path carries it: the advertised decoder bitrate is no longer a condition for a
 * client that reports decode health; what its decode has *failed at* is, per codec family, and a clean direct play lifts it.
 * An installed client keeps today's rule exactly.
 */
class DecodeHealthRecordTest {
    private val path = "/tmp/jellystructure-test-2205-decode-${getpid()}.db"
    private val db = createDatabase(path)

    @AfterTest fun cleanup() { platform.posix.unlink(path) }

    /** The stue TV as it advertises itself: 60 Mbps on both decoders, HDR10, a wired link. */
    private val advertised = ClientCapabilities(
        hlsAdaptive = true, supportsHdr10 = true, maxHevcBitrate = 60_000_000, maxH264Bitrate = 60_000_000, linkKind = "ethernet", linkMbps = 1000,
    )
    private val reporting = advertised.copy(reportsDecodeHealth = true)

    // ── take without the advertised term ──

    @Test fun `a client that reports decode health is not capped at the decoder it advertises`() {
        // The reported film: 69.6 Mbps video, ~71 Mbps as a file. The record says the path takes 90 Mbps.
        val take = canTake(StreamRecord(provenBps = 90_000_000, provenAt = 1_000), now = 1_000)
        val cap = negotiationCap(adaptive = true, take = take, tvApp = true)
        assertEquals(90_000_000, cap)
        // Reports decode health, so max_video_bitrate is an explicit cap and the app sends none: nothing advertised narrows the cap.
        assertEquals(90_000_000, maxStreamingBitrate(reporting.copy(maxVideoBitrate = 0), cap))
        // An installed client sent the advertised value there, and keeps being held to it.
        assertEquals(60_000_000, maxStreamingBitrate(advertised.copy(maxVideoBitrate = 60_000_000), cap))
    }

    @Test fun `a freeze recovery's explicit cap still narrows a client that reports decode health`() {
        assertEquals(12_000_000, maxStreamingBitrate(reporting.copy(maxVideoBitrate = 12_000_000), 90_000_000))
    }

    // ── the learned-ceiling gate ──

    @Test fun `nothing learned means no VideoBitrate condition for a client that reports decode health`() {
        val profile = deviceProfile(reporting, decodeFailed = emptyMap())
        assertFalse("VideoBitrate" in profile, profile)
    }

    @Test fun `a learned ceiling is the condition at nine tenths of the bitrate that failed — per family`() {
        val profile = deviceProfile(reporting, decodeFailed = mapOf("hevc" to 70_000_000L, "av1" to 40_000_000L))
        assertTrue(""""Codec":"hevc","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"63000000"""" in profile, profile)
        assertTrue(""""Codec":"av1","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"36000000"""" in profile, profile)
        assertFalse(""""Codec":"h264","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate"""" in profile, "h264 has learned nothing")
        // The advertised 60 Mbps appears nowhere: it is not a condition any more.
        assertFalse("54000000" in profile, profile)
    }

    @Test fun `an installed client keeps the advertised gate whatever the record says`() {
        val profile = deviceProfile(advertised, decodeFailed = mapOf("hevc" to 70_000_000L))
        assertTrue(""""Codec":"hevc","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"54000000"""" in profile, profile)
        assertTrue(""""Codec":"h264","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"54000000"""" in profile, profile)
        assertFalse("63000000" in profile, "an installed client cannot hand over, so a learned figure it never reported is not applied")
    }

    // ── what is learned, and the lift ──

    @Test fun `a decode failure is kept per family and only ever goes down`() {
        var r = noteDecodeFailed(StreamRecord(), "hevc", 70_000_000, now = 100)
        assertEquals(DecodeFailure(70_000_000, 100), r.decodeFailed["hevc"])
        r = noteDecodeFailed(r, "hevc", 50_000_000, now = 200)
        assertEquals(DecodeFailure(50_000_000, 200), r.decodeFailed["hevc"])
        r = noteDecodeFailed(r, "hevc", 90_000_000, now = 300)
        assertEquals(DecodeFailure(50_000_000, 300), r.decodeFailed["hevc"], "a failure at a higher bitrate says nothing new")
        r = noteDecodeFailed(r, "h264", 30_000_000, now = 400)
        assertEquals(mapOf("hevc" to 50_000_000L, "h264" to 30_000_000L), decodeFailedBps(r))
        assertEquals(r, noteDecodeFailed(r, "mpeg2", 1_000_000, now = 500), "an unknown family is not kept")
        assertEquals(r, noteDecodeFailed(r, "hevc", 0, now = 500))
    }

    @Test fun `the stored entry does not expire with time but negotiation stops reading it after 7 days`() {
        val r = noteDecodeFailed(StreamRecord(), "hevc", 70_000_000, now = 100)
        val yearLater = 100 + 365L * 86_400L
        assertEquals(mapOf("hevc" to 70_000_000L), decodeFailedBps(r), "the admin's view keeps the entry")
        assertEquals(emptyMap(), decodeFailedBps(r, yearLater), "negotiation treats the family as having nothing learned")
        assertEquals(70_000_000L, r.decodeFailed["hevc"]?.bps)
        assertEquals(null, canTake(r, now = yearLater), "the delivery take is untouched by it")
    }

    @Test fun `a failure just under 7 days old is a condition and one exactly 7 days old is not`() {
        val r = noteDecodeFailed(StreamRecord(), "hevc", 70_000_000, now = 1_000)
        val week = DECODE_RETRY_AFTER_SEC
        assertEquals(mapOf("hevc" to 70_000_000L), decodeFailedBps(r, 1_000 + week - 3_600))
        assertEquals(emptyMap(), decodeFailedBps(r, 1_000 + week))
    }

    @Test fun `a new failure on day 8 restarts the clock at the lower bitrate`() {
        val first = noteDecodeFailed(StreamRecord(), "hevc", 70_000_000, now = 0)
        val day8 = 8 * 86_400L
        assertEquals(emptyMap(), decodeFailedBps(first, day8))
        val again = noteDecodeFailed(first, "hevc", 50_000_000, now = day8)
        assertEquals(mapOf("hevc" to 50_000_000L), decodeFailedBps(again, day8))
        assertEquals(mapOf("hevc" to 50_000_000L), decodeFailedBps(again, day8 + DECODE_RETRY_AFTER_SEC - 1))
        assertEquals(emptyMap(), decodeFailedBps(again, day8 + DECODE_RETRY_AFTER_SEC))
    }

    @Test fun `a family past 7 days drops out while another that failed yesterday stays`() {
        val old = noteDecodeFailed(StreamRecord(), "hevc", 70_000_000, now = 0)
        val r = noteDecodeFailed(old, "h264", 30_000_000, now = 9 * 86_400L)
        assertEquals(mapOf("h264" to 30_000_000L), decodeFailedBps(r, 10 * 86_400L))
    }

    @Test fun `a clean direct play at a higher bitrate lifts the family and nothing else`() {
        val r = noteDecodeFailed(noteDecodeFailed(StreamRecord(), "hevc", 70_000_000, 1), "h264", 30_000_000, 1)
        assertEquals(r, liftDecodeFailed(r, "hevc", 70_000_000), "at the same bitrate it proves nothing")
        assertEquals(r, liftDecodeFailed(r, "hevc", 60_000_000))
        val lifted = liftDecodeFailed(r, "hevc", 75_000_000)
        assertNull(lifted.decodeFailed["hevc"])
        assertEquals(30_000_000, lifted.decodeFailed["h264"]?.bps)
        assertEquals(r, liftDecodeFailed(r, "av1", 90_000_000), "a family with no entry is unchanged")
    }

    @Test fun `what counts as a clean direct play`() {
        val tenMin = DECODE_LIFT_HOLD_SEC
        assertTrue(cleanDirectPlay(tenMin, droppedFrames = 10, renderedFrames = 20_000, stops = 0, frozenCount = 0))
        assertFalse(cleanDirectPlay(tenMin - 1, 0, 20_000, 0, 0), "under 10 min")
        assertFalse(cleanDirectPlay(tenMin, 20, 20_000, 0, 0), "0,1 % dropped is not under 0,1 %")
        assertFalse(cleanDirectPlay(tenMin, 0, 20_000, stops = 1, frozenCount = 0), "a stop of any cause")
        assertFalse(cleanDirectPlay(tenMin, 0, 20_000, stops = 0, frozenCount = 1))
        assertFalse(cleanDirectPlay(tenMin, 0, 0, 0, 0), "an older app that counts no rendered frames never lifts")
    }

    @Test fun `codec names fold into the four families`() {
        assertEquals("hevc", videoFamilyOf("HEVC")); assertEquals("hevc", videoFamilyOf("h265"))
        assertEquals("h264", videoFamilyOf("avc")); assertEquals("h264", videoFamilyOf("h264"))
        assertEquals("av1", videoFamilyOf("av1")); assertEquals("vp9", videoFamilyOf("vp9"))
        assertNull(videoFamilyOf("mpeg4")); assertNull(videoFamilyOf(null))
    }

    @Test fun `a ladder that replaces a direct play starts within what the path measured`() {
        // The record holds the direct play's own proof (73 Mbps) and a 24 h stall cap; the path measured 30 Mbps: start within 21.
        assertEquals(21_000_000, ladderTakeFor(leavingDirectPlay = true, take = 58_000_000, measuredBps = 30_000_000))
        assertEquals(58_000_000, ladderTakeFor(true, 58_000_000, measuredBps = 100_000_000), "never above the take")
        assertEquals(58_000_000, ladderTakeFor(true, 58_000_000, measuredBps = null), "nothing measured: the take stands")
        assertEquals(21_000_000, ladderTakeFor(true, null, 30_000_000), "no record at all: the measurement")
        assertEquals(58_000_000, ladderTakeFor(false, 58_000_000, 30_000_000), "any other restream is as it was")
    }

    // ── what the store keeps ──

    @Test fun `the record keeps every family's failure in its one row`() {
        val store = StreamRecordStore(db)
        val rec = StreamRecord(provenBps = 8_000_000, provenAt = 10, decodeFailed = mapOf("hevc" to DecodeFailure(70_000_000, 20), "h264" to DecodeFailure(30_000_000, 25)))
        store.put("tv", rec, now = 30)
        assertEquals(rec, store.get("tv"))
        store.put("tv", rec.copy(decodeFailed = rec.decodeFailed - "hevc"), now = 40)
        assertEquals(mapOf("h264" to DecodeFailure(30_000_000, 25)), store.get("tv")!!.decodeFailed)
        store.put("tv", rec.copy(decodeFailed = emptyMap()), now = 50)
        assertEquals(emptyMap(), store.get("tv")!!.decodeFailed)
        assertEquals(Triple(null, null, null), encodeDecodeFailed(emptyMap()))
        assertEquals(emptyMap(), parseDecodeFailed("1,2", "hevc", "5"), "lists of different lengths are read as nothing")
    }

    @Test fun `the QoE row keeps the rendered frames beside the dropped ones`() {
        val qoe = PlaybackQoeStore(db)
        qoe.record("tv", "ps-1", PlaybackQoeReport(itemId = "film", directPlay = true, perItem = true, droppedFrames = 12, renderedFrames = 86_400))
        val row = qoe.recentForDevice("tv").single()
        assertEquals(12, row.droppedFrames)
        assertEquals(86_400L, row.renderedFrames)
    }
}
