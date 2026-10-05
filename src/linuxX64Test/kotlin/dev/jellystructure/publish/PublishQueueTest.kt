package dev.jellystructure.publish

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.server.routes.DashboardService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 307 — the publish queue against a real database and a fake LRCLIB that only counts. */
class PublishQueueTest {
    private val base = "/tmp/jellystructure-test-publish-${getpid()}"
    private lateinit var db: JellystructureDb

    /** Records every send, in order; answers from [fail] (subject → reason) or accepts. Never touches the network. */
    private class FakeLrclib(val fail: MutableMap<String, String> = HashMap()) : PublishSender {
        val sent = ArrayList<String>()
        var inFlight = 0; var maxInFlight = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun send(target: String, kind: String, payload: String): PublishSendResult {
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            gate?.await()
            yield()
            sent += payload
            inFlight--
            val subject = fail.keys.firstOrNull { payload.contains("\"trackName\":\"$it\"") }
            return if (subject != null) PublishSendResult(fail.getValue(subject), "") else PublishSendResult(null, "Accepted · 201")
        }
    }

    private fun proposal(id: String, title: String = "Song $id", album: String = "Kite Weather") = PublishProposal(
        PublishQueue.LRCLIB, PublishQueue.INSTRUMENTAL, id, "$title — Harbour Lights · $album",
        dev.jellystructure.music.Lrclib.instrumentalPayload("Harbour Lights", title, album, 221), "MusicBrainz says this recording has no words",
    )

    @BeforeTest fun setUp() {
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$base.db$s") }
        db = createDatabase("$base.db")
    }

    @AfterTest fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (s in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$base.db$s") }
    }

    @Test
    fun proposing_queues_once_and_sends_nothing() {
        val fake = FakeLrclib()
        val q = PublishQueue(db, fake)
        val r = q.propose(listOf(proposal("a"), proposal("b"), proposal("a")), "admin")
        assertEquals(2, r.queued); assertEquals(1, r.already)
        assertEquals(0, fake.sent.size, "FR-307-2 — proposing never publishes")
        assertEquals(2, q.waitingCount())
        // Again: nothing new.
        val again = q.propose(listOf(proposal("a"), proposal("b")), "admin")
        assertEquals(0, again.queued); assertEquals(2, again.already)
        // The unique index holds even if code tried to bypass the check.
        val dup = runCatching { db.publishQueries.insertItem("lrclib", "instrumental", "a", "x", "{}", "", 1L, "test") }
        assertTrue(dup.isFailure, "UNIQUE(target, kind, subject) while not dismissed")
    }

    @Test
    fun the_payload_is_frozen_and_sent_byte_for_byte() = runBlocking {
        val fake = FakeLrclib()
        val q = PublishQueue(db, fake)
        val p = proposal("a", title = "Fog Bank")
        q.propose(listOf(p), "admin")
        // The song changes after it was queued: a new proposal for it is not taken, the frozen one is what goes out.
        q.propose(listOf(proposal("a", title = "Fog Bank (renamed)")), "admin")
        val item = q.inState(PublishQueue.WAITING).single()
        assertEquals(p.payload, item.payload)
        assertEquals(1, q.publish(listOf(item.id), "admin"))
        q.drain()
        assertEquals(listOf(p.payload), fake.sent)
        val done = q.item(item.id)!!
        assertEquals(PublishQueue.PUBLISHED, done.state)
        assertNotNull(done.sent_at); assertEquals("Accepted · 201", done.answer); assertEquals(1L, done.attempts)
        assertEquals("admin", done.decided_by)
        // Published: never queued again, never sent twice.
        assertEquals(0, q.propose(listOf(p), "admin").queued)
        assertEquals(0, q.publish(listOf(item.id), "admin"))
        q.drain()
        assertEquals(1, fake.sent.size)
        // The receipt and the fields.
        val list = q.list()
        assertEquals(listOf(item.id), list.published.map { it.id })
        assertEquals("LRCLIB", list.targets.single().name)
        val f = list.published.single().fields.associate { it.label to it.value }
        assertEquals("Fog Bank", f["Track name"]); assertEquals("3:41", f["Length"]); assertEquals("none — marks it instrumental", f["Lyrics"])
    }

    @Test
    fun dont_publish_is_remembered_until_the_payload_changes() {
        val q = PublishQueue(db, FakeLrclib())
        q.propose(listOf(proposal("a")), "admin")
        val id = q.inState(PublishQueue.WAITING).single().id
        assertEquals(1, q.dismiss(listOf(id), "jeggy"))
        assertEquals(PublishQueue.DISMISSED, q.item(id)!!.state)
        assertEquals("jeggy", q.item(id)!!.decided_by)
        val same = q.propose(listOf(proposal("a")), "admin")
        assertEquals(0, same.queued); assertEquals(1, same.declined, "FR-307-5 — not proposed again")
        assertEquals(0, q.waitingCount())
        // The reason changed (the payload would differ): proposed again, as a new item.
        val changed = q.propose(listOf(proposal("a", album = "Signal Found")), "admin")
        assertEquals(1, changed.queued)
        // The old dismissed one can't be queued again while the new one is live.
        assertEquals(false, q.queueAgain(id, "admin"))
        q.dismiss(q.inState(PublishQueue.WAITING).map { it.id }, "admin")
        assertTrue(q.queueAgain(id, "admin"), "*Queue again* on a dismissed item")
        assertEquals(PublishQueue.WAITING, q.item(id)!!.state)
        assertEquals(2, q.list().dismissed.size + q.list().waiting.size)
    }

    @Test
    fun a_restart_turns_publishing_back_into_waiting() = runBlocking {
        val fake = FakeLrclib()
        val q = PublishQueue(db, fake)
        q.propose(listOf(proposal("a"), proposal("b")), "admin")
        val ids = q.inState(PublishQueue.WAITING).map { it.id }
        assertEquals(2, q.publish(ids, "admin"))   // no scope: the worker is never woken — as if the process died here
        assertEquals(2, q.inState(PublishQueue.PUBLISHING).size)
        val restarted = PublishQueue(db, fake)
        assertEquals(2, restarted.recover())
        assertEquals(2, restarted.waitingCount())
        restarted.drain()
        assertEquals(0, fake.sent.size, "a recovered item waits for a press again")
    }

    @Test
    fun the_worker_sends_one_at_a_time_in_queue_order_and_a_second_press_joins_the_run() = runBlocking {
        val fake = FakeLrclib()
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val q = PublishQueue(db, fake)
        q.propose(listOf(proposal("a"), proposal("b"), proposal("c")), "admin")
        val ids = q.inState(PublishQueue.WAITING).map { it.id }
        q.publish(ids.take(2), "admin")
        val run = async(Dispatchers.Default) { q.drain() }
        withContext(Dispatchers.Default) { while (fake.inFlight == 0) yield() }
        // A second press mid-run: adds to the same run, sends nothing twice, never two at once.
        assertEquals(0, q.publish(ids.take(2), "admin"))
        assertEquals(1, q.publish(ids.drop(2), "admin"))
        q.drain()   // returns at once: the worker is busy
        gate.complete(Unit)
        run.await()
        q.drain()
        assertEquals(1, fake.maxInFlight)
        assertEquals(listOf("Song a", "Song b", "Song c"), fake.sent.map { Regex("\"trackName\":\"([^\"]+)\"").find(it)!!.groupValues[1] })
        assertEquals(3, q.inState(PublishQueue.PUBLISHED).size)
    }

    @Test
    fun a_failure_is_failed_with_its_reason_and_can_be_tried_again() = runBlocking {
        val fake = FakeLrclib(mutableMapOf("Song a" to "LRCLIB did not accept it"))
        val outcomes = ArrayList<Pair<String, String?>>()
        val q = PublishQueue(db, fake) { item, err -> outcomes += item.subject to err }
        q.propose(listOf(proposal("a"), proposal("b")), "admin")
        q.publish(q.inState(PublishQueue.WAITING).map { it.id }, "admin")
        q.drain()
        val a = q.inState(PublishQueue.FAILED).single()
        assertEquals("a", a.subject); assertEquals("LRCLIB did not accept it", a.answer)
        assertEquals(listOf("a" to "LRCLIB did not accept it", "b" to null), outcomes)
        assertEquals(0, q.propose(listOf(proposal("a")), "admin").queued, "a failed item stays in the panel, not queued twice")
        fake.fail.clear()
        assertEquals(1, q.publish(listOf(a.id), "admin"))   // *Try again*
        q.drain()
        assertEquals(PublishQueue.PUBLISHED, q.item(a.id)!!.state)
        assertEquals(2L, q.item(a.id)!!.attempts)
    }

    @Test
    fun the_dashboard_row_shows_the_waiting_count_and_goes_at_zero() {
        val q = PublishQueue(db, FakeLrclib())
        assertNull(q.dashboardRow(), "FR-285-5 — no row at zero")
        q.propose(listOf(proposal("a"), proposal("b")), "admin")
        val row = assertNotNull(q.dashboardRow())
        assertEquals(2, row.count); assertEquals("thing", row.unit); assertEquals("public", row.domain)
        assertEquals("info", row.severity); assertEquals("here", row.fix); assertEquals("publish_queue", row.opens)
        assertEquals("Waiting to publish", row.label)
        assertTrue(DashboardService.DOMAINS.any { it == ("public" to "Public databases") }, "its own domain chip")
        q.dismiss(q.inState(PublishQueue.WAITING).map { it.id }, "admin")
        assertNull(q.dashboardRow())
    }
}
