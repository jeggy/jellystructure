package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.DownloadOptions
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.DownloadTicket
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4072 (DEB-3995 FR-11, FR-12, FR-13, FR-15, FR-16) — what the controller does after a spell offline, and what it offers while offline. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsReconnectTest {
    private class NoApi : DownloadApi {
        override suspend fun options(itemId: String, mediaSourceId: String?) = DownloadOptions()
        override suspend fun start(request: DownloadStartRequest): DownloadTicket = error("offline")
    }

    private class Engine : DownloadEngine {
        private val p = MutableStateFlow<Map<String, DownloadPhase>>(emptyMap())
        override val phases: StateFlow<Map<String, DownloadPhase>> = p.asStateFlow()
        override fun enqueue(job: DownloadJob) {}
        override fun pause(id: String) {}
        override fun resume(id: String) {}
        override fun remove(id: String) { p.value = p.value - id }
        override fun freeBytes() = 1L
        override fun totalBytes() = 2L
        override fun busy() = false
        fun done(user: String, vararg ids: String) { p.value = p.value + ids.associate { downloadId(user, it) to DownloadPhase.Downloaded } }
    }

    private class Sync : DownloadSyncApi {
        val play = HashMap<String, CardPlayState>()
        val titles = HashMap<String, String>()          // item id -> body; absent = 404
        val statusFor = HashMap<String, Int>()          // item id -> status to throw
        var playstateFails: Throwable? = null
        val sent = mutableListOf<String>()
        override suspend fun playstate(ids: List<String>): Map<String, CardPlayState> { playstateFails?.let { throw it }; return play.filterKeys { it in ids } }
        override suspend fun progress(itemId: String, positionMs: Long) { sent += "progress:$itemId:$positionMs" }
        override suspend fun mark(itemId: String, watched: Boolean) { sent += "mark:$itemId" }
        override suspend fun title(kind: String, itemId: String, seriesId: String?): String {
            statusFor[itemId]?.let { throw TvApiError.Http(it, "x") }
            return titles[itemId] ?: throw TvApiError.Http(404, "gone")
        }
    }

    private class Rig(scope: CoroutineScope) {
        val engine = Engine(); val sync = Sync(); val saved = HashMap<String, String>()
        val storage = InMemoryDownloadIndexStorage(); val library = DownloadLibrary(storage)
        val outbox = ProgressOutbox(InMemoryDownloadIndexStorage())
        var clock = 100L
        val controller = DownloadsController(
            userId = { "alice" }, api = NoApi(), engine = engine, library = library, prefs = InMemoryDownloadPrefs(), baseUrl = { "https://srv" },
            scope = scope, now = { clock++ }, sync = sync, outbox = outbox, saveItem = { u, id, t -> saved["$u|$id"] = t },
        )
        fun have(id: String, kind: String = "movie", season: Int? = null, episode: Int? = null, series: String? = null, watched: Boolean = false, resume: Long = 0) {
            library.upsert("alice", DownloadRecord(id, kind, "T$id", seriesId = series, season = season, episode = episode, quality = "original", bytes = 10, state = RecordState.STARTED, watched = watched, serverResumeMs = resume))
            engine.done("alice", id)
            controller.refresh()
        }
    }

    private fun TestScope.rig() = Rig(CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test fun `a title the server no longer offers is marked gone and stays playable`() = runTest {
        val r = rig(); r.have("a"); r.have("b")
        r.sync.titles["a"] = """{"id":"a"}"""     // b answers 404
        assertEquals(Reconnect.OK, r.controller.onReachable())
        assertFalse(r.library.get("alice", "a")!!.gone)
        assertTrue(r.library.get("alice", "b")!!.gone)
        assertEquals(DownloadPhase.Downloaded, r.controller.phases.value["b"])
        assertTrue(r.controller.playable("b") != null)
    }

    @Test fun `a 403 marks it gone too, and a title that comes back is cleared`() = runTest {
        val r = rig(); r.have("a")
        r.sync.statusFor["a"] = 403
        r.controller.onReachable()
        assertTrue(r.library.get("alice", "a")!!.gone)
        r.sync.statusFor.clear(); r.sync.titles["a"] = "{}"
        r.controller.onReachable()
        assertFalse(r.library.get("alice", "a")!!.gone)
    }

    @Test fun `the refreshed item is saved verbatim`() = runTest {
        val r = rig(); r.have("a")
        val body = """{"id": "a",  "x":[1, 2]}"""
        r.sync.titles["a"] = body
        r.controller.onReachable()
        assertEquals(body, r.saved["alice|a"])
    }

    @Test fun `the server's watched flag and resume position replace what the phone knew`() = runTest {
        val r = rig(); r.have("a")
        r.sync.titles["a"] = "{}"; r.sync.play["a"] = CardPlayState(resumeMs = 7_000, played = true)
        r.controller.onReachable()
        val rec = r.library.get("alice", "a")!!
        assertTrue(rec.watched); assertEquals(7_000L, rec.serverResumeMs)
    }

    @Test fun `a 500 on one title is neither gone nor a reason to stop`() = runTest {
        val r = rig(); r.have("a"); r.have("b")
        r.sync.statusFor["a"] = 500; r.sync.titles["b"] = "{}"
        assertEquals(Reconnect.OK, r.controller.onReachable())
        assertFalse(r.library.get("alice", "a")!!.gone)
    }

    @Test fun `a 401 sends the profile to sign-in and keeps its downloads and outbox`() = runTest {
        val r = rig(); r.have("a")
        r.controller.recordOfflineProgress("a", 5_000)
        r.sync.playstateFails = TvApiError.Http(401, "no")
        assertEquals(Reconnect.REJECTED, r.controller.onReachable())
        assertEquals(1, r.library.records("alice").size)
        assertEquals(listOf("a"), r.outbox.pendingItems("alice"))
    }

    @Test fun `a 401 on a title page is rejected as well`() = runTest {
        val r = rig(); r.have("a")
        r.sync.statusFor["a"] = 401
        assertEquals(Reconnect.REJECTED, r.controller.onReachable())
    }

    @Test fun `the outbox is sent before the refresh, against the state the phone knew`() = runTest {
        val r = rig(); r.have("a", resume = 0); r.have("b", resume = 0)
        r.controller.recordOfflineProgress("a", 4_000)
        r.controller.recordOfflineProgress("b", 9_000)
        r.controller.recordOfflineWatched("b")
        r.sync.titles["a"] = "{}"; r.sync.titles["b"] = "{}"
        // Another device finished "a" in the meantime.
        r.sync.play["a"] = CardPlayState(played = true)
        r.controller.onReachable()
        assertEquals(listOf("progress:b:9000", "mark:b"), r.sync.sent)
        assertTrue(r.outbox.isEmpty("alice"))
        assertTrue(r.library.get("alice", "a")!!.watched)   // and the refresh shows the server's value
    }

    @Test fun `the server going away leaves the outbox and nothing is refreshed`() = runTest {
        val r = rig(); r.have("a")
        r.controller.recordOfflineProgress("a", 4_000)
        r.sync.playstateFails = RuntimeException("no route")
        assertEquals(Reconnect.UNREACHABLE, r.controller.onReachable())
        assertEquals(listOf("a"), r.outbox.pendingItems("alice"))
    }

    @Test fun `offline play records progress and the offline resume shows only until the server answers`() = runTest {
        val r = rig(); r.have("a")
        assertNull(r.controller.offlineResumeMs("a"))
        r.controller.recordOfflineProgress("a", 12_000)
        assertEquals(12_000L, r.controller.offlineResumeMs("a"))
        r.sync.titles["a"] = "{}"
        r.controller.onReachable()
        assertNull(r.controller.offlineResumeMs("a"))   // sent: the screen shows the server's value now
    }

    @Test fun `the launch asks about downloads fully on the phone, not queued ones`() = runTest {
        val r = rig()
        assertFalse(r.controller.hasPlayableDownloads())
        r.library.upsert("alice", DownloadRecord("q", "movie", "Tq", quality = "original", state = RecordState.QUEUED))
        assertFalse(r.controller.hasPlayableDownloads())
        r.have("a")
        assertTrue(r.controller.hasPlayableDownloads())
    }

    @Test fun `the next episode is offered only when it is also on the phone`() = runTest {
        val r = rig()
        r.have("e1", "episode", 1, 1, "s"); r.have("e2", "episode", 1, 2, "s"); r.have("e4", "episode", 1, 4, "s"); r.have("s2e1", "episode", 2, 1, "s")
        assertEquals("e2", r.controller.nextDownloadedEpisode("e1")?.itemId)
        assertNull(r.controller.nextDownloadedEpisode("e2"))          // e3 is not on the phone; e4 is not "next"
        r.have("e3", "episode", 1, 3, "s")
        assertEquals("e4", r.controller.nextDownloadedEpisode("e3")?.itemId)
        assertNull(r.controller.nextDownloadedEpisode("e4"))          // season 2's first is not provably next
        assertNull(r.controller.nextDownloadedEpisode("s2e1"))
    }
}
