package dev.jellystructure.shared.tv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R359 — a long queue fits the Cast: the window, the parts, and the receiver's replies, on the real encoders. */
class CastQueuePartsTest {

    /** The receiver's own `Json` (ravilo-cast's `Receiver.kt`): defaults and nulls written out. */
    private val receiverJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    private fun hex(i: Int, salt: Int) = (i * 2654435761L + salt).toString(16).padStart(16, '0').takeLast(16).repeat(2)

    /** A song as the music API hands it over: 32-hex ids, a server-relative cover URL full of slashes. */
    private fun songs(n: Int, long: Boolean = false): List<CastTrackItem> = List(n) { i ->
        val word = if (long) "Ein sehr langer Liedtitel – Live at the Harbour (Remastered ${2000 + i % 20}) · ".repeat(4) else "Song number $i"
        CastTrackItem(
            id = hex(i, 1),
            title = "$word$i",
            artist = if (long) "The Long-Named Orchestra and Choir of the North Atlantic Islands feat. Everyone" else "Harbour Lights",
            album = if (long) "Tide Tables 1999–2012 (Deluxe Anniversary Box Set, Disc ${i % 9 + 1})" else "Signal Found",
            albumArtist = "Harbour Lights",
            year = 2011,
            coverUrl = "/api/tv/music/albums/${hex(i / 12, 7)}/cover?v=1727890000",
            durationMs = 215_000L + i,
            hasLyrics = i % 3 == 0,
        )
    }

    private fun load(tracks: List<CastTrackItem>, current: Int) = CastLoadData(
        serverUrl = "https://media.example.org", code = "ABC123", itemId = tracks[current].id, title = tracks[current].title,
        kicker = tracks[current].artist, artUrl = "https://media.example.org" + tracks[current].coverUrl, positionMs = 30_000L,
        deviceName = "Guest room", receiverId = "rcv-0001", lang = "fo", tracks = tracks, currentIndex = current, repeat = "off", shuffle = true,
    )

    private fun loadBytes(d: CastLoadData) = castWireBytes(RaviloWireJsonWithDefaults.encodeToString(CastLoadData.serializer(), d))
    private fun partBytes(c: CastCommand) = castWireBytes(RaviloWireJsonWithDefaults.encodeToString(CastCommand.serializer(), c))

    /** The receiver's side: the LOAD's window, then every part as it arrives (in [order]), held until it joins. */
    private fun receive(plan: CastLoadPlan, order: (List<CastCommand>) -> List<CastCommand> = { it }): Pair<List<CastTrackItem>, Int> {
        val waiting = mutableListOf<CastCommand>()
        var run = plan.load.tracks to plan.load.queueStart
        for (p in order(plan.parts)) {
            waiting += p
            run = castQueueAttachAll(run.first, run.second, plan.load.queueId, waiting)
        }
        assertTrue(waiting.isEmpty(), "every part joined")
        return run
    }

    @Test
    fun everyMessageFitsAndTheQueueArrivesWholeAndInOrder() {
        for (n in listOf(1, 30, 487, 5_000)) for (long in listOf(false, true)) for (cur in listOf(0, n / 2, n - 1)) {
            val tracks = songs(n, long)
            val plan = castLoadPlan(load(tracks, cur), "q1")
            val what = "$n songs${if (long) ", long titles" else ""}, at $cur"
            // FR-R359-1 — the LOAD's data leaves the envelope its room; every part fits the budget.
            assertTrue(loadBytes(plan.load) <= CAST_MESSAGE_BUDGET_BYTES - CAST_LOAD_ENVELOPE_BYTES, "$what: LOAD ${loadBytes(plan.load)} B")
            plan.parts.forEach { assertTrue(partBytes(it) <= CAST_MESSAGE_BUDGET_BYTES, "$what: part ${partBytes(it)} B") }
            // The window starts on the current song, with the one before and after it when there are such.
            val w = plan.load
            assertEquals(tracks[cur].id, w.tracks[w.currentIndex].id, what)
            if (cur > 0) assertTrue(w.currentIndex > 0, "$what: previous is in the window")
            if (cur < n - 1) assertTrue(w.currentIndex < w.tracks.lastIndex, "$what: next is in the window")
            // FR-R359-3 — the receiver ends up with the sender's queue, whole and in order, starting at 0.
            val (whole, start) = receive(plan)
            assertEquals(0, start, what)
            assertEquals(tracks, whole, what)
            // A queue that fits is one LOAD exactly as before: no window, no parts.
            if (n <= 30) { assertNull(w.queueTotal, what); assertTrue(plan.parts.isEmpty(), what); assertEquals(tracks, w.tracks, what) }
        }
    }

    @Test
    fun the487SongQueueThatClosedTheConnection() {
        val tracks = songs(487)
        val data = load(tracks, 212)
        // Before R359: the whole queue, as the media's customData AND the request's — twice in one LOAD.
        val before = 2 * loadBytes(data)
        val plan = castLoadPlan(data, "q487")
        println("R359: 487 songs — before ${before / 1024} KB in one LOAD; after ${castLoadLog(487, loadBytes(plan.load), plan.parts.size)} (largest part ${plan.parts.maxOf { partBytes(it) } / 1024} KB)")
        assertTrue(before > 64 * 1024, "the old LOAD was over the frame")
        assertEquals(487, plan.load.queueTotal)
        assertTrue(plan.parts.isNotEmpty())
        // The parts after the window come first, in order, then the ones before it, last first.
        val offsets = plan.parts.map { it.offset!! }
        val after = offsets.takeWhile { it > plan.load.queueStart }
        assertEquals(after.sorted(), after)
        val before0 = offsets.drop(after.size)
        assertEquals(before0.sortedDescending(), before0)
        assertTrue(plan.parts.all { it.queueId == "q487" && it.type == "queue_part" })
    }

    @Test
    fun partsThatArriveOutOfOrderStillMakeTheWholeQueue() {
        val tracks = songs(5_000, long = true)
        val plan = castLoadPlan(load(tracks, 2_500), "q")
        val (whole, start) = receive(plan) { it.reversed() }
        assertEquals(0, start)
        assertEquals(tracks, whole)
    }

    @Test
    fun aPartOfAnotherQueueNeverJoins() {
        val plan = castLoadPlan(load(songs(487), 100), "new")
        val stale = castLoadPlan(load(songs(487), 100), "old").parts
        val waiting = (stale + plan.parts).toMutableList()
        val (whole, _) = castQueueAttachAll(plan.load.tracks, plan.load.queueStart, "new", waiting)
        assertEquals(487, whole.size)
        assertEquals(stale, waiting, "the old queue's parts are left alone")
    }

    @Test
    fun aFilmIsUntouched() {
        val film = CastLoadData(serverUrl = "https://media.example.org", code = "C", itemId = "f", title = "A film")
        val plan = castLoadPlan(film, "q")
        assertEquals(film, plan.load)
        assertTrue(plan.parts.isEmpty())
    }

    @Test
    fun nextAcrossTheWindowsEdgeWaitsForThePart() {
        val tracks = songs(487)
        val plan = castLoadPlan(load(tracks, 0), "q")
        val w = plan.load
        // At the window's last song, with songs still coming: wait, never end.
        assertEquals(CastNext.Wait, castNextIndex(w.tracks.lastIndex, w.tracks.size, w.queueStart, w.queueTotal, "off", byViewer = true))
        assertEquals(CastNext.Wait, castNextIndex(w.tracks.lastIndex, w.tracks.size, w.queueStart, w.queueTotal, "off", byViewer = false))
        // The next part arrives: next goes on to it.
        val waiting = mutableListOf(plan.parts.first())
        val (run, start) = castQueueAttachAll(w.tracks, w.queueStart, "q", waiting)
        assertEquals(CastNext.To(w.tracks.size), castNextIndex(w.tracks.lastIndex, run.size, start, w.queueTotal, "off", byViewer = true))
        assertEquals(tracks[w.tracks.size], run[w.tracks.size])
        // The whole queue in: the last song ends it; repeat all wraps.
        assertEquals(CastNext.End, castNextIndex(486, 487, 0, null, "off", byViewer = false))
        assertEquals(CastNext.To(0), castNextIndex(486, 487, 0, null, "all", byViewer = false))
        assertEquals(CastNext.To(5), castNextIndex(5, 487, 0, null, "one", byViewer = false))
    }

    @Test
    fun repeatAllAndPreviousWaitForTheSongsBeforeTheWindow() {
        val plan = castLoadPlan(load(songs(487), 486), "q")
        val w = plan.load
        assertTrue(w.queueStart > 0)
        // The window reaches the queue's end; wrapping needs song 0, which is still coming.
        assertEquals(CastNext.Wait, castNextIndex(w.tracks.lastIndex, w.tracks.size, w.queueStart, w.queueTotal, "all", byViewer = false))
        assertEquals(CastNext.End, castNextIndex(w.tracks.lastIndex, w.tracks.size, w.queueStart, w.queueTotal, "off", byViewer = false))
        assertTrue(castPreviousWaits(0, w.queueStart, w.queueTotal))
        assertTrue(!castPreviousWaits(0, 0, null))
    }

    @Test
    fun theReceiversReplyIsSplitOnlyWhenItMustBe() {
        for (n in listOf(1, 30, 487, 5_000)) for (long in listOf(false, true)) {
            val q = songs(n, long)
            val status = CastReceiverMessage(type = "status", itemId = q[0].id, title = q[0].title, queue = q, queueIndex = 0, repeat = "off", shuffle = false, queueRev = 7, queueSize = n)
            val sent = castQueueReply(status, receiverJson)
            sent.forEach { assertTrue(castWireBytes(receiverJson.encodeToString(CastReceiverMessage.serializer(), it)) <= CAST_MESSAGE_BUDGET_BYTES, "$n: ${it.type}") }
            if (sent.size == 1) { assertEquals(status, sent.single()); continue }
            // The status first, without the queue, saying how many parts follow; a sender puts them back together.
            val head = sent.first()
            assertNull(head.queue)
            assertEquals(sent.size - 1, head.queueParts)
            assertEquals(7, head.queueRev)
            val asm = CastQueueAssembly()
            val parts = sent.drop(1)
            parts.dropLast(1).forEach { assertNull(asm.part(it)) }
            assertEquals(q, asm.part(parts.last()))
        }
        // 30 songs is one status, as before R359.
        val thirty = songs(30)
        assertEquals(1, castQueueReply(CastReceiverMessage(type = "status", queue = thirty, queueRev = 1, queueSize = 30), receiverJson).size)
    }

    @Test
    fun theAssemblyFollowsTheNewestRevision() {
        val q = songs(5_000, long = true)
        val old = castQueueReply(CastReceiverMessage(type = "status", queue = q, queueRev = 1, queueSize = q.size), receiverJson).drop(1)
        val q2 = q.reversed()
        val new = castQueueReply(CastReceiverMessage(type = "status", queue = q2, queueRev = 2, queueSize = q2.size), receiverJson).drop(1)
        val asm = CastQueueAssembly()
        assertNull(asm.part(old.first()))
        // A newer revision starts over; the old one's parts never finish it.
        new.dropLast(1).forEach { assertNull(asm.part(it)) }
        assertEquals(q2, asm.part(new.last()))
        // Out of order works too.
        val again = CastQueueAssembly()
        assertEquals(q, old.reversed().map { again.part(it) }.last())
    }

    @Test
    fun theEndedReportLeavesALongQueueOut() {
        val long = CastReceiverMessage(type = "ended", queue = songs(487), queueIndex = 3)
        assertNull(castQueueIfFits(long, receiverJson).queue)
        val short = CastReceiverMessage(type = "ended", queue = songs(30), queueIndex = 3)
        assertEquals(short, castQueueIfFits(short, receiverJson))
    }

    @Test
    fun attachJoinsAtEitherEndOnly() {
        val q = songs(10)
        assertEquals(q.subList(3, 8) to 3, castQueueAttach(q.subList(3, 6), 3, 6, q.subList(6, 8)))
        assertEquals(q.subList(1, 6) to 1, castQueueAttach(q.subList(3, 6), 3, 1, q.subList(1, 3)))
        assertNull(castQueueAttach(q.subList(3, 6), 3, 8, q.subList(8, 10)))
        assertEquals(q.subList(3, 6) to 3, castQueueAttach(q.subList(3, 6), 3, 4, q.subList(4, 5)))
    }

    @Test
    fun theLogLine() {
        assertEquals("487 in the queue, 31 KB + 4 parts", castLoadLog(487, 31 * 1024, 4))
        assertEquals("26 in the queue, 6 KB", castLoadLog(26, 6 * 1024, 0))
        assertEquals("200 in the queue, 40 KB + 1 part", castLoadLog(200, 40 * 1024, 1))
    }

    @Test
    fun theWireFieldsAreAdditive() {
        // A receiver or sender older than R359 reads the new messages with the fields it knows.
        val c = RaviloWireJson.decodeFromString(CastCommand.serializer(), """{"type":"queue_add","index":null}""")
        assertNull(c.tracks); assertNull(c.queueId); assertNull(c.offset)
        val d = RaviloWireJson.decodeFromString(CastLoadData.serializer(), """{"server_url":"s","code":"","item_id":"i","title":"t"}""")
        assertNull(d.queueTotal); assertNull(d.queueId); assertEquals(0, d.queueStart)
        assertIs<CastNext.To>(castNextIndex(0, 2, 0, null, "off", byViewer = true))
    }
}
