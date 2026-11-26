package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-5701 (DEB-5685; phase 313, FR-313-23, FR-313-24) — a busy card is shared in order. A 4K HEVC play with every rung costs 0.5 of a card, so a
 * card holds two: the tests fill it with prewarms and kept-warm streams and ask for the third. Every stream here is registered with `kickJobs = false`
 * (it counts as a pending placement) except where a test needs a running job, which is the fake ffmpeg of `EncoderWarmStopTest`.
 */
class EncoderBudgetTest {
    private val base = "/tmp/jellystructure-test-5701-${getpid()}"
    private val ffmpegDir = "$base/ff"
    private val dbPath = "/tmp/jellystructure-test-5701-plans-${getpid()}.db"
    private var runs = 0
    private var now = 1_000_000_000_000L

    @AfterTest fun cleanup() {
        runCatching { SystemFileSystem.list(Path(base)).forEach { rmTree(it.toString()) } }; platform.posix.rmdir(base)
        platform.posix.unlink(dbPath)
    }

    private fun rmTree(p: String) {
        runCatching { SystemFileSystem.list(Path(p)).forEach { rmTree(it.toString()) } }
        platform.posix.unlink(p); platform.posix.rmdir(p)
    }

    private fun write(path: String, text: String) { SystemFileSystem.sink(Path(path)).buffered().use { it.write(text.encodeToByteArray(), 0, text.length) } }

    private val script = """#!/bin/sh
while [ ${'$'}# -gt 0 ]; do
  case "${'$'}1" in
    -hls_segment_filename) pat="${'$'}2"; shift;;
    -var_stream_map) vm="${'$'}2"; shift;;
  esac
  shift
done
n=${'$'}(echo ${'$'}vm | wc -w)
i=0
while [ ${'$'}i -lt 100 ]; do
  v=0
  while [ ${'$'}v -lt ${'$'}n ]; do
    f=${'$'}(echo "${'$'}pat" | sed "s|%v|${'$'}v|; s|%d|${'$'}i|")
    echo x > "${'$'}f.tmp" && mv "${'$'}f.tmp" "${'$'}f"
    v=${'$'}((v+1))
  done
  echo progress=continue
  sleep 0.3
  i=${'$'}((i+1))
done
"""

    private val oneCard = listOf(EncoderCard(0, "Quadro P4000", null, 0))
    private val twoCards = listOf(EncoderCard(0, "Quadro P4000", null, 0), EncoderCard(1, "Quadro P2000", null, 0))
    private val caps = ClientCapabilities(hlsHevcCapable = true, videoCodecs = listOf("hevc", "h264"), supportsHdr10 = true, hlsAdaptive = true, maxAudioChannels = 6)
    private val tracks = listOf(
        Track(0, "v:0", TrackKind.VIDEO, "hevc", null, null, true, false, width = 3840, height = 2160, videoRange = "SDR", videoBitrate = 40_000_000, durationMs = 7_200_000),
        Track(1, "a:0", TrackKind.AUDIO, "eac3", "eng", null, true, false),
    )
    private val audio = listOf(AudioTrack(1, "eng", "English", "eac3", 6, true))

    private fun encoder(cards: List<EncoderCard> = oneCard, plans: StreamPlanStore? = null, jobsRun: Boolean = false): Encoder {
        val dir = "$base/work${runs++}"
        mkdirs(ffmpegDir); mkdirs(dir)
        write("$ffmpegDir/ffmpeg", script)
        platform.posix.chmod("$ffmpegDir/ffmpeg", 493u)
        return Encoder({ EncoderConfig(enabled = true, ffmpegDir = ffmpegDir, workDir = dir, cudaCacheDir = "$base/cuda") },
            cardsOverride = { cards }, ffmpegOverride = { true }, plans = plans, clock = { now }).also {
            it.kickJobs = jobsRun
            runBlocking { it.running(); if (jobsRun) delay(300) }   // the jobs sweep the work folder once, in the background
        }
    }

    /** A play's plan as `planFor` makes it for [device] and [item] — a prewarm's when [evict] is false. */
    private suspend fun planOf(enc: Encoder, device: String, item: String, evict: Boolean = true) =
        enc.planFor(caps, "tv", "/m/film.mkv", 7_200_000, tracks, audio, 1, takeBps = null, noRecord = true, sourceVideoRange = "SDR",
            evict = evict, forDevice = device, forItem = item)

    private suspend fun prewarm(enc: Encoder, device: String, item: String): String? {
        val plan = planOf(enc, device, item, evict = false).first ?: return null
        now += 10
        return enc.prewarm(plan, device, item, 0, Long.MAX_VALUE)
    }

    @Test fun `a play's own prewarm is not counted against it - it gets every rung and adopts it`() = runBlocking<Unit> {
        val enc = encoder()
        val other = prewarm(enc, "dev-a", "item-a")
        val own = prewarm(enc, "dev-b", "item-b")
        assertNotNull(other); assertNotNull(own)
        // The card is full (0.5 + 0.5). Counting its own prewarm the play would stop the other device's; it must stop nothing.
        val (plan, why) = planOf(enc, "dev-b", "item-b")
        assertEquals("ours", why)
        assertEquals(4, plan!!.rungs.size)
        assertEquals(0, enc.prewarmSegmentOf("dev-a", "item-a"), "the other device's prewarm was left alone")
        assertEquals(own, enc.adoptPrewarm("dev-b", "item-b", plan, 0, "play-b", Long.MAX_VALUE), "the play adopts its own warm stream")
    }

    @Test fun `a full card with two other devices' prewarms stops the older post and places the play with every rung`() = runBlocking<Unit> {
        val enc = encoder()
        prewarm(enc, "dev-a", "item-a")
        prewarm(enc, "dev-b", "item-b")
        val (plan, why) = planOf(enc, "dev-c", "item-c")
        assertEquals("ours", why)
        assertEquals(4, plan!!.rungs.size, "every rung, not a trim")
        assertNull(enc.prewarmSegmentOf("dev-a", "item-a"), "the older post is stopped")
        assertEquals(0, enc.prewarmSegmentOf("dev-b", "item-b"), "the newer one is left running")
    }

    @Test fun `a re-post is a newer post - the one nobody has posted for longest goes first`() = runBlocking<Unit> {
        val enc = encoder()
        prewarm(enc, "dev-a", "item-a")
        prewarm(enc, "dev-b", "item-b")
        now += 1_000
        assertNotNull(prewarm(enc, "dev-a", "item-a"), "a's page posts again")
        planOf(enc, "dev-c", "item-c")
        assertEquals(0, enc.prewarmSegmentOf("dev-a", "item-a"))
        assertNull(enc.prewarmSegmentOf("dev-b", "item-b"), "b is the older post now")
    }

    @Test fun `a prewarm post on a full card holding another device's kept-warm stream is answered none and touches nothing`() = runBlocking<Unit> {
        val enc = encoder(jobsRun = true)
        val kept = enc.register(planOf(enc, "dev-k", "item-k").first!!, "play-k", Long.MAX_VALUE, "dev-k", "item-k", startMs = 60_000)
        enc.startWithTicket(kept, 30)
        delay(500)
        assertNotNull(enc.segment(kept, "v", 0, 30))
        assertTrue(enc.keepWarm("play-k", 62_000))
        assertNotNull(prewarm(enc, "dev-z", "item-z"), "the card is full: 0.5 kept + 0.5 prewarm")
        val (plan, why) = planOf(enc, "dev-x", "item-x", evict = false)
        assertNull(plan, "a prewarm that does not fit is not made, and not trimmed")
        assertEquals("no encoder slot free on any card", why)
        assertEquals(31, enc.prewarmSegmentOf("dev-k", "item-k"), "the kept stream is still there")
        assertEquals(0, enc.prewarmSegmentOf("dev-z", "item-z"), "and so is the other prewarm")
        assertEquals(2, enc.running().size, "both jobs run")
        // A play does stop warm jobs, a page prewarm before a kept-warm stream.
        val (play, _) = planOf(enc, "dev-x", "item-x")
        assertEquals(4, play!!.rungs.size)
        assertNull(enc.prewarmSegmentOf("dev-z", "item-z"), "the page prewarm goes first")
        assertEquals(31, enc.prewarmSegmentOf("dev-k", "item-k"), "the kept stream stays: one warm job was enough")
        enc.stopFor("prewarm:dev-k:item-k")
    }

    @Test fun `a play with only a kept-warm stream in its way stops it`() = runBlocking<Unit> {
        val enc = encoder(jobsRun = true)
        val kept = enc.register(planOf(enc, "dev-k", "item-k").first!!, "play-k", Long.MAX_VALUE, "dev-k", "item-k", startMs = 60_000)
        enc.startWithTicket(kept, 30)
        delay(500)
        assertNotNull(enc.segment(kept, "v", 0, 30))
        assertTrue(enc.keepWarm("play-k", 62_000))
        assertNotNull(enc.register(planOf(enc, "dev-p", "item-p").first!!, "play-p", Long.MAX_VALUE, "dev-p", "item-p"), "a play holds the other half of the card")
        assertEquals(4, planOf(enc, "dev-x", "item-x").first!!.rungs.size, "every rung")
        assertNull(enc.prewarmSegmentOf("dev-k", "item-k"), "no page prewarm to stop: the kept-warm stream goes")
        enc.stopFor("play-p")
    }

    @Test fun `with no warm job left a play is trimmed as before`() = runBlocking<Unit> {
        val enc = encoder()
        enc.register(planOf(enc, "dev-a", "item-a").first!!, "play-a", Long.MAX_VALUE, "dev-a", "item-a")
        enc.register(planOf(enc, "dev-b", "item-b").first!!, "play-b", Long.MAX_VALUE, "dev-b", "item-b")
        val (plan, why) = planOf(enc, "dev-c", "item-c")
        assertNull(plan, "two plays hold the card: nothing to stop and nothing fits")
        assertEquals("no encoder slot free on any card", why)
    }

    // ── FR-313-24 ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `two plays placed one after the other see each other without a job running`() = runBlocking<Unit> {
        val enc = encoder()
        val a = planOf(enc, "dev-a", "item-a").first!!
        enc.register(a, "play-a", Long.MAX_VALUE, "dev-a", "item-a")
        val b = planOf(enc, "dev-b", "item-b").first!!
        enc.register(b, "play-b", Long.MAX_VALUE, "dev-b", "item-b")
        assertEquals(4, a.rungs.size); assertEquals(4, b.rungs.size)
        assertNull(planOf(enc, "dev-c", "item-c").first, "the third sees both: its jobs have not started and the card is full")
        now += 16_000
        assertNotNull(planOf(enc, "dev-c", "item-c").first, "a placement no job took up for 15 s counts no more")
    }

    @Test fun `a plan planFor placed counts before its stream is registered`() = runBlocking<Unit> {
        val enc = encoder()
        planOf(enc, "dev-a", "item-a")
        planOf(enc, "dev-b", "item-b")
        assertNull(planOf(enc, "dev-c", "item-c").first, "two plans are out and neither is registered yet")
    }

    @Test fun `streams restored after a restart and read at the same moment are placed one after the other`() = runBlocking<Unit> {
        val db = createDatabase(dbPath)
        val store = StreamPlanStore(db)
        val before = encoder(twoCards, plans = store)
        val ids = (1..3).map { before.register(planOf(before, "dev-$it", "item-$it").first!!.copy(cudaDevice = 0), "play-$it", now + 3_600_000, "dev-$it", "item-$it", startMs = 60_000) }
        val after = encoder(twoCards, plans = store, jobsRun = true)   // the backend restarted: nothing in memory
        ids.map { id -> async { after.segment(id, "v", 0, 0) } }.awaitAll()
        val cards = after.running().map { it.plan.cudaDevice }.sortedBy { it ?: -1 }
        assertEquals(listOf(0, 0, 1), cards, "two fit on card 0 and the third sees them: it goes to card 1")
        ids.indices.forEach { after.stopFor("play-${it + 1}") }
    }
}
