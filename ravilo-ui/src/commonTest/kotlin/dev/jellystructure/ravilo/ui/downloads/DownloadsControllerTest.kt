package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOption
import dev.jellystructure.shared.tv.DownloadOptions
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.DownloadTicket
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsControllerTest {
    private class FakeApi : DownloadApi {
        val starts = mutableListOf<DownloadStartRequest>()
        var optionsStatus: Int? = null
        var startStatus: Int? = null
        var retryAfter: Int? = null
        var startThrows: Throwable? = null
        /** DEB-4258 — the waiting 503's JSON body (`reason`, `streams`); the default is a bare answer with neither. */
        var startBody: String = "no"
        /** Items whose `start` is answered with their own body (a copy that cannot be made while the others can). */
        val startBodies = mutableMapOf<String, String>()
        /** Every item a `start` was asked for, in order, answered or not. */
        val asked = mutableListOf<String>()
        var originalBytes = 5_000_000_000L
        var optionsCalls = 0
        override suspend fun options(itemId: String, mediaSourceId: String?): DownloadOptions {
            optionsCalls++
            optionsStatus?.let { throw TvApiError.Http(it, "no") }
            return DownloadOptions(listOf(DownloadOption("original", 2160, originalBytes, exact = true)))
        }
        override suspend fun start(request: DownloadStartRequest): DownloadTicket {
            asked += request.itemId
            startThrows?.let { throw it }
            startStatus?.let { throw TvApiError.Http(it, startBodies[request.itemId] ?: startBody, retryAfterSeconds = retryAfter) }
            starts += request
            return DownloadTicket(
                kind = if (request.quality == "original") "file" else "hls", quality = request.quality, url = "/u/${request.itemId}/${starts.size}",
                bytes = 1L, expiresAt = 0L, itemKind = "movie", item = JsonNull,
            )
        }
    }

    private class FakeEngine : DownloadEngine {
        private val p = MutableStateFlow<Map<String, DownloadPhase>>(emptyMap())
        override val phases: StateFlow<Map<String, DownloadPhase>> = p.asStateFlow()
        val jobs = mutableListOf<DownloadJob>()
        val removed = mutableListOf<String>()
        var free = 50_000_000_000L
        var freedOnRemove = 0L
        override fun enqueue(job: DownloadJob) { jobs += job; p.value = p.value + (job.id to DownloadPhase.Running(0)) }
        override fun pause(id: String) { p.value = p.value + (id to DownloadPhase.Paused(0)) }
        override fun resume(id: String) { p.value = p.value + (id to DownloadPhase.Running(0)) }
        override fun remove(id: String) { removed += id; free += freedOnRemove; p.value = p.value - id }
        override fun freeBytes() = free
        override fun totalBytes() = 128_000_000_000L
        override fun busy() = p.value.values.any { it is DownloadPhase.Running || it == DownloadPhase.Queued || it == DownloadPhase.WaitingServer }
        fun set(id: String, phase: DownloadPhase) { p.value = p.value + (id to phase) }
    }

    private class Rig(scope: kotlinx.coroutines.CoroutineScope, user: String? = "alice") {
        val api = FakeApi(); val engine = FakeEngine(); val prefs = InMemoryDownloadPrefs(); val storage = InMemoryDownloadIndexStorage()
        val library = DownloadLibrary(storage)
        var user: String? = user
        var clock = 1L
        val controller = DownloadsController(
            userId = { this.user }, api = api, engine = engine, library = library, prefs = prefs, baseUrl = { "https://srv" },
            scope = scope, now = { clock++ }, waitingRetryMs = 5_000L,
        )
    }

    private fun target(id: String, kind: String = "movie") = DownloadTarget(itemId = id, kind = kind, title = "T$id", runtimeMinutes = 100)
    private val original = DownloadOption("original", 2160, 5_000_000_000L, exact = true)
    private val medium = DownloadOption("medium", 720, 1_000_000_000L)

    private val openRigs = mutableListOf<Rig>()
    private fun TestScope.rig(user: String? = "alice") = Rig(CoroutineScopeOf(this), user).also { openRigs += it }

    /** A test that leaves the server answering 503 would retry for ever when runTest drains the scheduler: the server says yes as the body ends. */
    private fun runWait(body: suspend TestScope.() -> Unit) = runTest {
        try { body() } finally { openRigs.forEach { it.api.startStatus = null; it.api.startBodies.clear() }; openRigs.clear() }
    }
    @Suppress("FunctionName") private fun CoroutineScopeOf(s: TestScope) = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(s.testScheduler))

    @Test fun `a 403 hides the control and throws not allowed`() = runTest {
        val r = rig(); r.api.optionsStatus = 403
        assertTrue(r.controller.allowed.value)
        assertFailsWith<DownloadNotAllowed> { r.controller.options("a") }
        assertFalse(r.controller.allowed.value)
        r.controller.resetAllowed()
        assertTrue(r.controller.allowed.value)
    }

    @Test fun `other failures pass through and leave the control alone`() = runTest {
        val r = rig(); r.api.optionsStatus = 404
        assertFailsWith<TvApiError.Http> { r.controller.options("a") }
        assertTrue(r.controller.allowed.value)
    }

    @Test fun `downloads run one at a time in the order asked for, each taking its ticket only on its turn`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("e1", "episode"), target("e2", "episode"), target("e3", "episode")), medium)
        advanceUntilIdle()
        // Only the first has a ticket; the rest wait in the queue with none (the server runs one download stream, FR-6).
        assertEquals(listOf("e1"), r.api.starts.map { it.itemId })
        assertEquals(DownloadPhase.Running(0), r.controller.phases.value["e1"])
        assertEquals(DownloadPhase.Queued, r.controller.phases.value["e2"])
        assertEquals(DownloadPhase.Queued, r.controller.phases.value["e3"])
        r.engine.set(downloadId("alice", "e1"), DownloadPhase.Downloaded); advanceUntilIdle()
        assertEquals(listOf("e1", "e2"), r.api.starts.map { it.itemId })
        r.engine.set(downloadId("alice", "e2"), DownloadPhase.Downloaded); advanceUntilIdle()
        assertEquals(listOf("e1", "e2", "e3"), r.api.starts.map { it.itemId })
        assertEquals(DownloadPhase.Downloaded, r.controller.phases.value["e1"])
    }

    @Test fun `the last quality picked is remembered per kind`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("m1")), medium); advanceUntilIdle()
        assertEquals("medium", r.controller.lastQuality("movie"))
        assertEquals(null, r.controller.lastQuality("episode"))
    }

    @Test fun `the engine gets the record, the ticket and the server base`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("m1")), original); advanceUntilIdle()
        val job = r.engine.jobs.single()
        assertEquals(downloadId("alice", "m1"), job.id)
        assertEquals("original", job.record.quality)
        assertEquals("https://srv", job.baseUrl)
        assertEquals("file", job.ticket.kind)
    }

    @Test fun `an expired ticket asks start again and resumes the same download`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("m1")), original); advanceUntilIdle()
        val id = downloadId("alice", "m1")
        r.engine.set(id, DownloadPhase.Failed(403)); advanceUntilIdle()
        assertEquals(2, r.api.starts.size)
        assertEquals(listOf(id, id), r.engine.jobs.map { it.id })   // same id: the engine merges the fresh URL into the one download
    }

    @Test fun `renewal gives up after a few tries and leaves the failure to Retry`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("m1")), original); advanceUntilIdle()
        val id = downloadId("alice", "m1")
        repeat(6) { r.engine.set(id, DownloadPhase.Failed(403)); advanceUntilIdle(); r.engine.set(id, DownloadPhase.Failed(403)); advanceUntilIdle() }
        assertTrue(r.api.starts.size <= 4, "1 start + at most 3 renewals, was ${r.api.starts.size}")
    }

    @Test fun `a smaller copy that waits for the server asks again with the segment it reached`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("m1")), medium); advanceUntilIdle()
        val id = downloadId("alice", "m1")
        r.engine.set(id, DownloadPhase.Running(50)); advanceUntilIdle()
        r.engine.set(id, DownloadPhase.WaitingServer); runCurrent()
        assertEquals(1, r.api.starts.size)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(2, r.api.starts.size)
        // 100 min = 3000 segments of 2 s; 50 % = 1500, less the 5-segment margin.
        assertEquals(1495, r.api.starts.last().fromSegment)
    }

    @Test fun `a server that is busy leaves the download queued and tries again`() = runTest {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5
        // The server keeps answering busy, so the retry never goes idle: step virtual time instead of waiting for it.
        r.controller.download(listOf(target("m1")), medium); runCurrent()
        // FR-6: the refused start says why it waits (DEB-4140: it read plain Queued, with nothing running, for as long as a play was live).
        assertEquals(DownloadPhase.WaitingServer, r.controller.phases.value["m1"])
        assertEquals(0, r.engine.jobs.size)
        r.api.startStatus = null
        advanceTimeBy(5_001); runCurrent()
        assertEquals(1, r.engine.jobs.size)
        assertEquals(DownloadPhase.Running(0), r.controller.phases.value["m1"])
    }

    private val full2 = """{"error":"busy","reason":"encoder_full","streams":2}"""
    private val unavailable = """{"error":"cannot","reason":"unavailable"}"""

    // ─── DEB-4258 (DEB-4256 FR-7): a waiting copy says why, follows every answer, and the queue goes round ───

    @Test fun `a waiting copy keeps the reason of its latest answer and the line follows it`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5; r.api.startBody = full2
        r.controller.download(listOf(target("m1")), medium); runCurrent()
        assertEquals(DownloadPhase.WaitingServer, r.controller.phases.value["m1"])
        assertEquals(WaitLine("download.wait_full", mapOf("n" to "2")), waitLine(r.controller.waits.value["m1"]))
        r.api.startBody = """{"reason":"encoder_full","streams":1}"""
        advanceTimeBy(5_001); runCurrent()
        assertEquals(WaitLine("download.wait_full_one"), waitLine(r.controller.waits.value["m1"]))
        r.api.startBody = unavailable
        advanceTimeBy(5_001); runCurrent()
        assertEquals(WaitLine("download.wait_unavailable"), waitLine(r.controller.waits.value["m1"]))
        // The server says yes: it starts with no tap, and nothing of the wait is left.
        r.api.startStatus = null
        advanceTimeBy(5_001); runCurrent()
        assertEquals(DownloadPhase.Running(0), r.controller.phases.value["m1"])
        assertTrue(r.controller.waits.value.isEmpty())
    }

    @Test fun `an answer with no reason reads waiting for the server for 10 seconds and then the busy line`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5   // body "no": an older server, or one this phone cannot read
        r.controller.download(listOf(target("m1")), medium); runCurrent()
        assertEquals("download.waiting_server", waitLine(r.controller.waits.value["m1"]).key)
        advanceTimeBy(9_000); runCurrent()
        assertEquals("download.waiting_server", waitLine(r.controller.waits.value["m1"]).key)
        advanceTimeBy(1_500); runCurrent()
        assertEquals("download.wait_busy", waitLine(r.controller.waits.value["m1"]).key)
        // An unknown reason from a newer server, and a count-less encoder_full, are the same generic wait.
        r.api.startBody = """{"reason":"something_new"}"""
        advanceTimeBy(5_001); runCurrent()
        assertEquals("download.wait_busy", waitLine(r.controller.waits.value["m1"]).key)
        assertEquals("download.wait_busy", waitLine(WaitInfo(dev.jellystructure.shared.tv.DownloadWaitReason.EncoderFull(null))).key)
    }

    @Test fun `a reason shows from the first answer, inside the first 10 seconds`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5; r.api.startBody = unavailable
        r.controller.download(listOf(target("m1")), medium); runCurrent()
        assertEquals("download.wait_unavailable", waitLine(r.controller.waits.value["m1"]).key)
    }

    @Test fun `a segment's 503 reads waiting for the server until the next start answer gives it a reason`() = runWait {
        val r = rig()
        r.controller.download(listOf(target("m1")), medium); advanceUntilIdle()
        val id = downloadId("alice", "m1")
        r.api.startStatus = 503; r.api.retryAfter = 5; r.api.startBody = full2
        r.engine.set(id, DownloadPhase.WaitingServer); runCurrent()
        assertEquals("download.waiting_server", waitLine(r.controller.waits.value["m1"]).key)
        advanceTimeBy(5_001); runCurrent()
        assertEquals("download.wait_full", waitLine(r.controller.waits.value["m1"]).key)
        // It keeps asking every Retry-After while it waits, and the line follows.
        r.api.startBody = unavailable
        advanceTimeBy(5_001); runCurrent()
        assertEquals("download.wait_unavailable", waitLine(r.controller.waits.value["m1"]).key)
    }

    @Test fun `encoder_full keeps asking for the oldest waiting copy only`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5; r.api.startBody = full2
        r.controller.download(listOf(target("m1"), target("m2")), medium); runCurrent()
        advanceTimeBy(5_001); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf("m1", "m1", "m1"), r.api.asked)
        assertEquals(DownloadPhase.Queued, r.controller.phases.value["m2"])
    }

    @Test fun `unavailable moves on to the next waiting copy and comes round again`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5; r.api.startBody = unavailable
        r.controller.download(listOf(target("m1"), target("m2")), medium); runCurrent()
        repeat(3) { advanceTimeBy(5_001); runCurrent() }
        assertEquals(listOf("m1", "m2", "m1", "m2"), r.api.asked)
        assertEquals(DownloadPhase.WaitingServer, r.controller.phases.value["m2"])
        // The encoder comes back: both start, one after the other.
        r.api.startStatus = null
        advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf("m1"), r.engine.jobs.map { downloadItemId(it.id) })
        r.engine.set(downloadId("alice", "m1"), DownloadPhase.Downloaded); advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), r.engine.jobs.map { downloadItemId(it.id) })
    }

    @Test fun `one copy that cannot be made does not hold the copy behind it`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5; r.api.startBodies["m1"] = unavailable
        r.controller.download(listOf(target("m1"), target("m2")), medium); runCurrent()
        r.api.startStatus = null; r.api.startBodies.clear()
        advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf("m1", "m2"), r.api.asked)   // m1 answered unavailable; the next turn went to m2, which started
        assertEquals(DownloadPhase.Running(0), r.controller.phases.value["m2"])
    }

    @Test fun `a waiting copy offers its original when it is listed and fits, and the offer asks options once`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5; r.api.startBody = full2
        r.controller.download(listOf(target("m1")), medium); runCurrent()
        assertEquals(5_000_000_000L, r.controller.originalOffers.value["m1"]?.bytes)
        advanceTimeBy(5_001); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(1, r.api.optionsCalls)
    }

    @Test fun `no offer when the original does not fit, when it is the original already, or when the copy is paused`() = runWait {
        // 50 GB free (the rig's engine); 1 GB is kept back (FR-3), so a 49.5 GB original does not fit.
        val big = rig(); big.api.startStatus = 503; big.api.retryAfter = 5; big.api.originalBytes = 49_500_000_000L
        big.controller.download(listOf(target("m1")), medium); runCurrent()
        assertTrue(big.controller.originalOffers.value.isEmpty())

        val orig = rig(); orig.api.startStatus = 503; orig.api.retryAfter = 5
        orig.controller.download(listOf(target("m1")), original); runCurrent()
        assertEquals(DownloadPhase.WaitingServer, orig.controller.phases.value["m1"])
        assertTrue(orig.controller.originalOffers.value.isEmpty())
        assertEquals(0, orig.api.optionsCalls)

        val paused = rig(); paused.api.startStatus = 503; paused.api.retryAfter = 5
        paused.controller.download(listOf(target("m1")), medium); runCurrent()
        assertEquals(setOf("m1"), paused.controller.originalOffers.value.keys)
        paused.engine.set(downloadId("alice", "m1"), DownloadPhase.Paused(0)); runCurrent()
        assertTrue(paused.controller.originalOffers.value.isEmpty())
    }

    @Test fun `what the copy already holds counts as free for the offer`() = runWait {
        val r = rig(); r.api.originalBytes = 49_800_000_000L
        r.controller.download(listOf(target("m1")), medium.copy(bytes = 10_000_000_000L)); advanceUntilIdle()
        val id = downloadId("alice", "m1")
        r.engine.set(id, DownloadPhase.Running(50)); advanceUntilIdle()
        r.api.startStatus = 503; r.api.retryAfter = 5
        r.engine.set(id, DownloadPhase.WaitingServer); runCurrent(); advanceTimeBy(5_001); runCurrent()
        // 50 GB free + 5 GB held (50 % of 10 GB) − 1 GB kept back = 54 GB: 49.8 GB fits.
        assertEquals(setOf("m1"), r.controller.originalOffers.value.keys)
    }

    @Test fun `one tap switches the entry to the original in the same place, frees the copy and leaves the remembered quality`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5; r.api.startBody = full2
        r.controller.download(listOf(target("m1"), target("m2")), medium); runCurrent()
        val queuedAt = r.library.get("alice", "m1")!!.queuedAt
        r.engine.set(downloadId("alice", "m1"), DownloadPhase.WaitingServer)   // the copy had been running and was ended for room
        r.controller.getOriginal("m1")
        val rec = r.library.get("alice", "m1")!!
        assertEquals("original", rec.quality); assertEquals(5_000_000_000L, rec.bytes); assertEquals(queuedAt, rec.queuedAt)
        assertEquals(RecordState.QUEUED, rec.state)
        assertEquals(listOf(downloadId("alice", "m1")), r.engine.removed)   // what the copy had saved is freed at once
        assertEquals("medium", r.controller.lastQuality("movie"))
        assertTrue(r.controller.originalOffers.value.isEmpty())
        // It starts as any queued original does: an original needs no encoder, so the server says yes.
        r.api.startStatus = null
        runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals("original", r.engine.jobs.first { it.id == downloadId("alice", "m1") }.record.quality)
        assertEquals(DownloadPhase.Running(0), r.controller.phases.value["m1"])
    }

    @Test fun `a waiting copy that never started has nothing to free when it becomes the original`() = runWait {
        val r = rig(); r.api.startStatus = 503; r.api.retryAfter = 5
        r.controller.download(listOf(target("m1")), medium); runCurrent()
        r.controller.getOriginal("m1")
        assertTrue(r.engine.removed.isEmpty())
        assertEquals("original", r.library.get("alice", "m1")!!.quality)
    }

    @Test fun `a start this phone could not read stays queued and keeps the reason`() = runTest {
        val r = rig(); r.api.startThrows = IllegalStateException("unreadable ticket")
        r.controller.download(listOf(target("m1")), medium); runCurrent()
        assertEquals(DownloadPhase.Queued, r.controller.phases.value["m1"])
        assertEquals("unreadable ticket", r.controller.lastStartError?.message)
    }

    @Test fun `a title the server no longer has is a failure the viewer can retry`() = runTest {
        val r = rig(); r.api.startStatus = 404
        r.controller.download(listOf(target("m1")), original); advanceUntilIdle()
        assertEquals(DownloadPhase.Failed(404), r.controller.phases.value["m1"])
        r.api.startStatus = null
        r.controller.retry("m1"); advanceUntilIdle()
        assertEquals(DownloadPhase.Running(0), r.controller.phases.value["m1"])
    }

    @Test fun `a 403 on start drops the queued download and hides the control`() = runTest {
        val r = rig(); r.api.startStatus = 403
        r.controller.download(listOf(target("m1")), original); advanceUntilIdle()
        assertFalse(r.controller.allowed.value)
        assertEquals(null, r.controller.phases.value["m1"])
    }

    @Test fun `delete removes the media and the record at once`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("m1")), original); advanceUntilIdle()
        r.controller.delete("m1"); advanceUntilIdle()
        assertEquals(listOf(downloadId("alice", "m1")), r.engine.removed)
        assertTrue(r.library.records("alice").isEmpty())
        assertEquals(null, r.controller.phases.value["m1"])
    }

    @Test fun `free space catches a Media3 removal that finishes after five seconds`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("m1")), original); advanceUntilIdle()
        r.engine.freedOnRemove = 4_096L // The index goes first; the media's blocks are still held.
        r.controller.delete("m1"); runCurrent()
        assertEquals(50_000_004_096L, r.controller.storageFreeBytes.value)

        // The removal command has returned, but Android still holds the blocks beyond the old five-second watch.
        advanceTimeBy(5_500); runCurrent()
        assertEquals(50_000_004_096L, r.controller.storageFreeBytes.value)
        r.engine.free = 55_000_000_000L
        advanceTimeBy(500); runCurrent()
        assertEquals(55_000_000_000L, r.controller.storageFreeBytes.value)
    }

    @Test fun `another profile's downloads are never in this viewer's phases`() = runTest {
        val r = rig()
        r.controller.download(listOf(target("m1")), original); advanceUntilIdle()
        r.user = "bob"
        r.engine.set(downloadId("alice", "m1"), DownloadPhase.Downloaded); advanceUntilIdle()
        assertTrue(r.controller.phases.value.isEmpty())
        r.user = "alice"
        r.controller.refresh()
        assertEquals(DownloadPhase.Downloaded, r.controller.phases.value["m1"])
    }

    @Test fun `segments resume a few before where the phone reached`() {
        assertEquals(0, fromSegmentFor(0, 100))
        assertEquals(0, fromSegmentFor(50, 0))
        assertEquals(25, fromSegmentFor(1, 100))   // 3000 segments, 1 % = 30, less the margin
        assertEquals(0, fromSegmentFor(0, 100))
        assertEquals(1495, fromSegmentFor(50, 100))
    }

    @Test fun `only 401 403 and 410 mean the ticket expired`() {
        assertTrue(isExpiredTicket(401) && isExpiredTicket(403) && isExpiredTicket(410))
        assertFalse(isExpiredTicket(404) || isExpiredTicket(503) || isExpiredTicket(null))
    }
}
