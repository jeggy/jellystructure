package dev.jellystructure.tv

import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.db.createDatabase
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-3100 (trouble-never-reaches-the-viewer FR-2) — a stream's plan outlives the process and the clock. */
class StreamPlansSurviveTest {
    private val dbPath = "/tmp/jellystructure-test-3100-plans-${getpid()}.db"
    private val db = createDatabase(dbPath)
    private val store = StreamPlanStore(db)
    private var now = 1_000_000_000_000L
    private val hour = 3_600_000L

    @AfterTest fun cleanup() { platform.posix.unlink(dbPath) }

    private val plan = EncoderPlan(
        source = EncoderSource("/m/film.mkv", 7_200_000, "hevc", 3840, 2160, 40_000_000, hdr = true),
        codec = EncoderCodec.HEVC, mux = EncoderMux.TS,
        rungs = listOf(EncoderRung(2160, 3840, 2160, 20_000_000), EncoderRung(1080, 1920, 1080, 8_000_000), EncoderRung(720, 1280, 720, 4_000_000)),
        startRung = 1, audio = listOf(EncoderAudio(0, 0, "aac", 6, "eng", "English", true), EncoderAudio(1, 1, "aac", 2, "dan", "Dansk", false)),
        subtitles = listOf(EncoderSubtitle("s0", "eng", false, true, "http://jf/sub.vtt")),
    )

    /** A "process": a new Encoder over the same database. */
    private fun process() = Encoder({ EncoderConfig(enabled = true, workDir = "/tmp/js-encoder-test-3100") }, cardsOverride = { emptyList() }, ffmpegOverride = { true },
        plans = store, clock = { now }).also { it.kickJobs = false }

    @Test fun `a plan restored from the store serves the same stream id after a restart`() = runBlocking<Unit> {
        val before = process()
        val id = before.register(plan, "play-1", now + 4 * hour, "dev", "item", startMs = 60_000, qoeKey = "q1")
        val master = before.master(id)!!
        val playlist = before.playlist(id, "v", 1)!!

        val after = process()   // the backend restarted: nothing in memory
        assertTrue(after.owns(id))
        assertEquals(master, after.master(id), "the same master, with the same start")
        assertEquals(playlist, after.playlist(id, "v", 1))
        assertNotNull(after.playlist(id, "a", 1), "an audio rendition of the restored plan")
        assertEquals("/m/film.mkv", after.subtitlePlaylist(id, 0)?.let { "/m/film.mkv" }, "its subtitle rendition too")
        assertTrue(after.serves("play-1"), "the play is served by our encoder again")
        assertEquals(1, after.stopFor("play-1"))
        Unit
    }

    @Test fun `an unknown id is still unknown and a stopped play is not restored`() = runBlocking<Unit> {
        val a = process()
        val id = a.register(plan, "play-2", now + 4 * hour)
        assertNull(process().master("0".repeat(32)))
        a.stopFor("play-2")
        assertNull(process().master(id), "the session ended: its plan went from the store")
    }

    @Test fun `a restart after the plan was stopped by another process leaves nothing to restore`() = runBlocking<Unit> {
        val a = process()
        val id = a.register(plan, "play-3", now + 4 * hour)
        val b = process()          // restarted, has not met the stream yet
        assertEquals(0, b.stopFor("play-3"), "nothing in memory to stop…")
        assertNull(process().master(id), "…but the store forgot it")
    }

    @Test fun `a read at plus five hours still answers when reads came along the way`() = runBlocking<Unit> {
        val enc = process()
        val id = enc.register(plan, "play-4", now + 4 * hour)
        now += 3 * hour
        assertNotNull(enc.playlist(id, "v", 0), "a read at +3 h renews the ticket")
        now += 2 * hour          // +5 h: past the minted 4 h
        assertNotNull(enc.playlist(id, "v", 0))
        assertNotNull(process().playlist(id, "v", 0), "the same after a restart: the store heard of the renewal")
    }

    @Test fun `a pause with no reads for over four hours does end the stream`() = runBlocking<Unit> {
        val enc = process()
        val id = enc.register(plan, "play-5", now + 4 * hour)
        now += 5 * hour
        assertNull(enc.playlist(id, "v", 0))
        assertNull(process().playlist(id, "v", 0), "and the store does not bring it back")
    }

    @Test fun `a prewarm is memory only and is stored when a play adopts it`() = runBlocking<Unit> {
        val enc = process()
        val id = enc.prewarm(plan, "dev", "item", 0, now + 5 * 60_000)
        assertNull(process().master(id), "nobody pressed Play: a restart forgets it")
        val adopted = enc.adoptPrewarm("dev", "item", plan, 0, "play-6", now + 4 * hour)
        assertEquals(id, adopted)
        assertNotNull(process().master(id))
    }

    @Test fun `the store sweeps what nobody read for a day`() = runBlocking<Unit> {
        store.put("old", "encoder", "p", "{}", now + 4 * hour, now)
        store.put("new", "encoder", "p", "{}", now + 4 * hour, now + 25 * hour)
        store.sweep(now + 25 * hour)
        assertNull(store.get("old", "encoder"))
        assertNotNull(store.get("new", "encoder"))
    }

    @Test fun `the audio renditions of a transcode and a sidecar survive a restart too`() = runBlocking<Unit> {
        val renditions = AudioRenditions(store, { now }) { null }
        val audio = listOf(
            dev.jellystructure.shared.tv.AudioTrack(1, "eng", "English", "aac", 2, true),
            dev.jellystructure.shared.tv.AudioTrack(2, "dan", "Dansk", "aac", 2, false),
        )
        val id = renditions.register("play-7", "http://jf/videos/1/master.m3u8?x=1", audio, 1, now + 4 * hour, "/m/film.mkv", 600_000)!!
        val restarted = AudioRenditions(store, { now }) { null }
        assertEquals(renditions.playlist(id, 1), restarted.playlist(id, 1), "the rendition's playlist comes back")
        assertNotNull(restarted.playlist(id, 1))
        now += 5 * hour
        assertNull(restarted.playlist(id, 1), "a stream nobody read for its ticket's length ends")

        now += 0
        val side = SidecarStreams(store)
        val sid = side.register("/m/film.dan.Stereo.mka", now + 4 * hour, now)
        now += 3 * hour
        assertEquals("/m/film.dan.Stereo.mka", SidecarStreams(store).path(sid, now), "after a restart, and the read renews")
        now += 2 * hour
        assertEquals("/m/film.dan.Stereo.mka", SidecarStreams(store).path(sid, now), "a read at +5 h with reads along the way")
        now += 5 * hour
        assertNull(SidecarStreams(store).path(sid, now))
    }

    @Test fun `a restart that finds one slot fewer keeps the start rung and its neighbour`() {
        val full = restartPlan(plan, EncoderPlacement(card = 1, rungs = 3))!!
        assertEquals(1, full.plan.cudaDevice)
        assertEquals(null, full.kept, "every rung fits: the same ladder, on the card chosen now")
        val fewer = restartPlan(plan, EncoderPlacement(card = 0, rungs = 2))!!
        assertEquals(listOf(1, 2), fewer.kept)
        assertEquals(2, fewer.plan.rungs.size)
        assertEquals(0, fewer.plan.startRung, "the start rung (1080p) is the first one it makes")
        assertEquals(3, fewer.streamRungs)
        assertNull(restartPlan(plan, null), "no card has a slot: the plan runs as it was placed")
    }

    @Test fun `a trimmed job maps the stream's variants to its own outputs`() {
        val job = EncoderJobs.Job("s", "/w/s", 0, 4, plan.copy(rungs = plan.rungs.drop(1)), streamRungs = 3, kept = listOf(1, 2))
        assertNull(job.slot(0), "the 2160p rung is not made")
        assertEquals(0, job.slot(1))
        assertEquals(1, job.slot(2))
        assertEquals(2, job.slot(3), "the first audio rendition comes after the two rungs")
        assertEquals("/w/s/none/s5.ts", job.file(0, 5))
        assertEquals("/w/s/1/s5.ts", job.file(2, 5))
    }
}
