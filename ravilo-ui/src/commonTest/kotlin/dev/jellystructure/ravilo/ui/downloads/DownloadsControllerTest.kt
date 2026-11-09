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
        override suspend fun options(itemId: String, mediaSourceId: String?): DownloadOptions {
            optionsStatus?.let { throw TvApiError.Http(it, "no") }
            return DownloadOptions(listOf(DownloadOption("original", 2160, 5_000_000_000L, exact = true)))
        }
        override suspend fun start(request: DownloadStartRequest): DownloadTicket {
            startStatus?.let { throw TvApiError.Http(it, "no", retryAfterSeconds = retryAfter) }
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
        override fun enqueue(job: DownloadJob) { jobs += job; p.value = p.value + (job.id to DownloadPhase.Running(0)) }
        override fun pause(id: String) { p.value = p.value + (id to DownloadPhase.Paused(0)) }
        override fun resume(id: String) { p.value = p.value + (id to DownloadPhase.Running(0)) }
        override fun remove(id: String) { removed += id; p.value = p.value - id }
        override fun freeBytes() = 50_000_000_000L
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

    private fun TestScope.rig(user: String? = "alice") = Rig(CoroutineScopeOf(this), user)
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
        assertEquals(DownloadPhase.Queued, r.controller.phases.value["m1"])
        assertEquals(0, r.engine.jobs.size)
        r.api.startStatus = null
        advanceTimeBy(5_001); runCurrent()
        assertEquals(1, r.engine.jobs.size)
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
