package dev.jellystructure.media

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 316, FR-316-7 test 8 (DEB-1252) — the repair of films the mover bound to one wrong item, with fakes for
 * every port: sessions attributed by slug, queue entries rewritten one by one, samples by path, QoE by device and
 * window (mixed units), derived rows left to the build, a row with no evidence left and listed, the album's row reset
 * only on exactly one candidate with a `LastPlayedDate` at or before the write, the carry only onto an empty film
 * row, and a second run planning nothing.
 */
class LooseFilmRepairTest {
    private companion object { const val NEWER_EPISODES = 6000 }

    private val wrong = "wrongalbum"
    private val root = "/lib/films"

    private fun queue(vararg entries: Pair<String, String?>) = JsonArray(entries.map { (id, slug) ->
        JsonObject(buildMap {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
            put("title", kotlinx.serialization.json.JsonPrimitive("A title"))
            if (slug != null) put("artwork", kotlinx.serialization.json.JsonPrimitive("/api/tv/image/$slug/poster?v=1"))
        })
    }).toString()

    private fun ids(queueJson: String) = (Json.parseToJsonElement(queueJson) as JsonArray).map { (it as JsonObject)["id"]!!.jsonPrimitive.content }

    /**
     * Jellyfin's `/Items` list, newest first by `DateCreated`. A moved film keeps its file's creation time, so the
     * [films] sit behind [NEWER_EPISODES] later additions (DEB-1328: ranks 8 699–9 458 of 9 616 on prod). A read with
     * a `Limit` returns only that many of the newest; [all] pages to the end, as `getAllLibraryItemIds` does.
     */
    private class FakeJellyfin(private val films: () -> List<Pair<String, String>>) {
        private fun library() = (1..NEWER_EPISODES).map { "/lib/series/Show/S01E$it.mkv" to "episode-$it" } + films()
        fun recent(limit: Int) = library().take(limit)
        fun all(): List<Pair<String, String>> {
            val lib = library()
            val acc = ArrayList<Pair<String, String>>()
            while (acc.size < lib.size) acc += lib.drop(acc.size).take(200)
            return acc
        }
    }

    private class Fake(val wrong: String, val root: String) : RepairPorts {
        var entries = ArrayList<MovedEntry>()
        val media = HashMap<String, RepairMediaRow>()        // by media id
        val items = HashMap<String, String>()                // path → jellyfin id, as Jellyfin holds it
        /** Jellyfin as the server answers: films and a newer pile of episodes, and only a bounded window on a limit. */
        private val jellyfin = FakeJellyfin { items.toList() }
        private val index = RepairPathIndex { jellyfin.all() }
        val existing = HashSet<String>()
        var wrongItem: RepairItem? = RepairItem("MusicAlbum", "/lib/music/An Artist/An Album")
        val sessions = ArrayList<RepairSessionRow>()
        val itemOfSession = HashMap<String, String>()
        val samples = ArrayList<Pair<RepairSampleRow, String>>()   // row, current item id
        val qoe = ArrayList<Pair<RepairQoeRow, String>>()
        var derived = mutableMapOf("starter_list" to 13, "recommendation.reason_item_id" to 3)
        val viewers = listOf("u1", "u2", "u3")
        /** (user, item) → data; absent = cannot read. */
        val watch = HashMap<Pair<String, String>, ViewerData>()
        val writes = ArrayList<String>()
        val histories = ArrayList<Triple<String, String, String>>()
        var buildQueued = 0
        val handEdits = HashMap<String, List<String>>()

        override fun movedEntries() = entries
        override fun mediaRowById(id: String) = media[id]
        override suspend fun mediaRowAtPath(path: String) = media.values.firstOrNull { it.path == path }
        override fun fileExists(path: String) = path in existing
        override fun videoInFolder(folderName: String): String? = existing.filter { it.startsWith("$root/$folderName/") }.singleOrNull()
        override suspend fun jellyfinItem(id: String) = if (id == wrong) wrongItem else null
        override suspend fun itemIdAt(path: String) = index.idAt(path)
        override fun sessionsOn(id: String) = sessions.filter { itemOfSession[it.id] == id }
        override fun samplesOn(id: String) = samples.filter { it.second == id }.map { it.first }
        override fun qoeOn(id: String) = qoe.filter { it.second == id }.map { it.first }
        override fun derivedOn(id: String) = if (id == wrong) derived.filterValues { it > 0 } else emptyMap()
        override suspend fun viewers() = viewers
        override suspend fun watchData(userId: String, itemId: String) = watch[userId to itemId]
        override suspend fun writeWatchData(userId: String, itemId: String, data: ViewerData): Boolean {
            writes += "watch:$userId:$itemId:${data.played}"
            watch[userId to itemId] = data
            return true
        }

        override fun repairSession(id: String, oldId: String, newId: String, queueJson: String): Boolean {
            if (itemOfSession[id] != oldId) return false
            itemOfSession[id] = newId
            val i = sessions.indexOfFirst { it.id == id }
            sessions[i] = sessions[i].copy(queueJson = queueJson)
            return true
        }

        override fun repairSample(id: Long, oldId: String, newId: String): Boolean {
            val i = samples.indexOfFirst { it.first.id == id && it.second == oldId }
            if (i < 0) return false
            samples[i] = samples[i].first to newId
            return true
        }

        override fun repairQoe(deviceId: String, playSessionId: String, oldId: String, newId: String): Boolean {
            val i = qoe.indexOfFirst { it.first.deviceId == deviceId && it.first.playSessionId == playSessionId && it.second == oldId }
            if (i < 0) return false
            qoe[i] = qoe[i].first to newId
            return true
        }

        override suspend fun setMediaJellyfinId(rowId: String, jellyfinId: String): Boolean {
            val key = media.entries.firstOrNull { it.value.id == rowId }?.key ?: return false
            media[key] = media.getValue(key).copy(jellyfinId = jellyfinId)
            writes += "media:$rowId:$jellyfinId"
            return true
        }

        override fun history(mediaId: String, detail: String, beforeSnapshot: String) { histories += Triple(mediaId, detail, beforeSnapshot) }
        override fun handEditsSince(mediaId: String, sinceSec: Long) = handEdits[mediaId].orEmpty()
        override fun queueRecommendationsBuild() { buildQueued++; derived.clear() }   // the build rewrites them from real ids
    }

    private fun line(folder: String, rows: Int, extra: String = "") =
        "Moved into its own folder $folder: check ✓ Nobody is playing it · move ✓ 3 files moved · jellyfin ✓ Found it under a new id$extra · records ✓ Path updated; $rows rows moved to the new id · verify ✓ Plays"

    /** Three films moved on 10-15 and one renamed in place on 10-13, as the four history lines of DEB-1251 describe. */
    private fun world(): Fake {
        val f = Fake(wrong, root)
        f.entries.add(MovedEntry("film-a", 1_792_053_000L, line("Film A (2023)", 5)))
        f.entries.add(MovedEntry("film-b", 1_792_053_000L, line("Film B (2022)", 4)))
        f.entries.add(MovedEntry("film-c", 1_792_053_000L, line("Film C (2025)", 6)))
        f.entries.add(MovedEntry("film-d", 1_791_858_360L, "Renamed to D.mkv: check ✓ Nobody · jellyfin ✓ Found it under a new id; watch data put back for 1 viewer · records ✓ Path updated; 6 rows moved to the new id · verify ✓ ok"))
        for ((slug, folder, file) in listOf(Triple("film-a", "Film A (2023)", "A.mkv"), Triple("film-b", "Film B (2022)", "B.mkv"), Triple("film-c", "Film C (2025)", "C.mkv"))) {
            val path = "$root/$folder/$file"
            f.existing += path
            f.items[path] = "real-$slug"
            f.media[slug] = RepairMediaRow(slug, path, if (slug == "film-b") wrong else "real-$slug")   // B's row is bound to the album
        }
        val d = "$root/Film D (2024)/D.mkv"
        f.existing += d; f.items[d] = "real-film-d"; f.media["film-d"] = RepairMediaRow("film-d", d, "real-film-d")
        // Sessions: milliseconds.
        fun session(id: String, q: String, device: String = "dev1", created: Long = 10_000, ended: Long? = 20_000, index: Int = 0) {
            f.sessions += RepairSessionRow(id, device, null, q, index, created, ended ?: created, ended)
            f.itemOfSession[id] = wrong
        }
        session("s-c", queue(wrong to "film-c"), created = 10_000, ended = 20_000)
        session("s-mixed", queue(wrong to "film-c", wrong to "film-b", "other" to "film-a"), device = "dev2", created = 30_000, ended = 40_000)
        session("s-noslug", queue(wrong to null), device = "dev3", created = 50_000, ended = 60_000)
        session("s-unknown", queue(wrong to "film-zzz"), device = "dev3", created = 70_000, ended = 80_000)
        session("s-live", queue(wrong to "film-a"), device = "dev4", created = 90_000, ended = null)
        // Samples: file_id is the path at the time.
        f.samples += RepairSampleRow(1, "dev1", "$root/A.mkv") to wrong                       // A, before the move
        f.samples += RepairSampleRow(2, "dev1", "$root/Film A (2023)/A.mkv") to wrong         // A, after
        f.samples += RepairSampleRow(3, "dev1", "$root/nothing.mkv") to wrong                 // no film
        // QoE: seconds, against sessions in ms.
        f.qoe += RepairQoeRow("dev1", "ps1", 15) to wrong       // inside s-c's window → film C
        f.qoe += RepairQoeRow("dev9", "ps2", 15) to wrong       // no session on that device
        f.qoe += RepairQoeRow("dev3", "ps3", 55) to wrong       // only s-noslug (unattributed) holds it
        // Album and viewers.
        f.watch["u1" to wrong] = ViewerData(true, 0, false, 1, "2026-10-13T02:20:00.0000000Z")
        f.watch["u2" to wrong] = ViewerData(false, 0, false, 0, null)
        f.watch["u1" to "real-film-d"] = ViewerData(false, 0, false, 0, null)
        f.watch["u2" to "real-film-d"] = ViewerData(false, 0, false, 0, null)
        for (s in listOf("film-a", "film-b", "film-c")) f.watch["u1" to "real-$s"] = ViewerData(true, 0, false, 1, null)
        return f
    }

    @Test fun `a film behind a window of newer additions is still found at its path`() = runBlocking<Unit> {
        val f = world()
        val jellyfin = FakeJellyfin { f.items.toList() }
        val film = "$root/Film C (2025)/C.mkv"
        // The defect: a 5 000-item window of the newest additions holds none of the three films.
        assertEquals(null, RepairPathIndex { jellyfin.recent(5000) }.idAt(film))
        // The fix: no window, the exact path.
        val index = RepairPathIndex { jellyfin.all() }
        assertEquals("real-film-c", index.idAt(film))
        // The assertion stays: a path Jellyfin has no item at is nothing, never a neighbour.
        assertEquals(null, index.idAt("$root/Film C (2025)/other.mkv"))
        assertEquals(null, RepairPathIndex { emptyList() }.idAt(film))
    }

    @Test fun `the plan finds the right item for every film through the full read`() = runBlocking<Unit> {
        val (plan, _) = planRepair(world(), wrong)
        for (slug in listOf("film-a", "film-b", "film-c")) assertEquals("real-$slug", plan.films.single { it.mediaId == slug }.rightId)
    }

    private fun row(plan: RepairPlan, table: String, key: String) = plan.rows.single { it.table == table && it.key == key }

    @Test fun `sessions samples and QoE are attributed by the evidence each carries and nothing else`() = runBlocking<Unit> {
        val (plan, _) = planRepair(world(), wrong)
        assertTrue(plan.confirmed, plan.stop)
        assertEquals("film-c", row(plan, "playback_session", "s-c").film)
        assertEquals("move", row(plan, "playback_session", "s-c").action)
        assertEquals("real-film-c", row(plan, "playback_session", "s-c").to)
        // The current queue entry names film C, so the row is C's; its second entry is rewritten by its own slug.
        assertEquals("film-c", row(plan, "playback_session", "s-mixed").film)
        // No slug, an unknown slug: left where they are, and listed.
        assertEquals("unaccounted", row(plan, "playback_session", "s-noslug").action)
        assertEquals("unaccounted", row(plan, "playback_session", "s-unknown").action)
        // A live session is not rewritten under a player.
        assertEquals("keep", row(plan, "playback_session", "s-live").action)
        // Samples: the pre-move path (root + basename) and the path now both name film A.
        assertEquals("film-a", row(plan, "playback_start_sample", "1").film)
        assertEquals("film-a", row(plan, "playback_start_sample", "2").film)
        assertEquals("unaccounted", row(plan, "playback_start_sample", "3").action)
        // QoE: seconds against milliseconds. Only a session that is attributed counts.
        assertEquals("film-c", row(plan, "playback_qoe", "dev1|ps1").film)
        assertEquals("unaccounted", row(plan, "playback_qoe", "dev9|ps2").action)
        assertEquals("unaccounted", row(plan, "playback_qoe", "dev3|ps3").action)
    }

    @Test fun `a QoE row held by sessions of two films is not attributed`() = runBlocking<Unit> {
        val f = world()
        f.sessions += RepairSessionRow("s-b", "dev1", null, queue(wrong to "film-b"), 0, 12_000, 19_000, 19_000)
        f.itemOfSession["s-b"] = wrong
        val (plan, _) = planRepair(f, wrong)
        assertEquals("unaccounted", row(plan, "playback_qoe", "dev1|ps1").action)
    }

    @Test fun `derived rows are counted and handed to the build and never edited`() = runBlocking<Unit> {
        val f = world()
        val (plan, work) = planRepair(f, wrong)
        assertEquals(16, plan.derivedTotal)
        assertTrue(plan.rows.none { it.table == "starter_list" || it.table.startsWith("recommendation") })
        applyRepair(f, plan, work)
        assertEquals(1, f.buildQueued)
        assertEquals(wrong, f.itemOfSession["s-noslug"], "an unaccounted row stays")
    }

    @Test fun `the count check compares attributed plus derived with the history lines`() = runBlocking<Unit> {
        val f = world()
        val (plan, _) = planRepair(f, wrong)
        // moved: s-c, s-mixed, 2 samples, 1 QoE = 5; derived 16; the lines say 5+4+6+6 = 21.
        assertEquals(5, plan.attributed)
        assertEquals(21, plan.historyTotal)
        assertTrue(plan.countCheck.startsWith("attributed 5 + derived 16 = the history lines' 21"), plan.countCheck)
        f.derived["starter_list"] = 14
        val (mismatch, _) = planRepair(f, wrong)
        assertTrue(mismatch.countCheck.startsWith("MISMATCH"), mismatch.countCheck)
        assertTrue(mismatch.confirmed, "a mismatch is printed and does not block the rest")
    }

    @Test fun `apply rewrites each queue entry by its own slug and moves only the planned rows`() = runBlocking<Unit> {
        val f = world()
        val (plan, work) = planRepair(f, wrong)
        val report = applyRepair(f, plan, work)
        assertEquals(5, report.moved)
        assertEquals("real-film-c", f.itemOfSession["s-c"])
        assertEquals(listOf("real-film-c"), ids(f.sessions.single { it.id == "s-c" }.queueJson))
        // Entry 1 names C, entry 2 names B, entry 3 is another item and is not touched.
        assertEquals(listOf("real-film-c", "real-film-b", "other"), ids(f.sessions.single { it.id == "s-mixed" }.queueJson))
        assertEquals(wrong, f.itemOfSession["s-live"])
        assertEquals(wrong, f.itemOfSession["s-unknown"])
        assertEquals("real-film-a", f.samples.first { it.first.id == 1L }.second)
        assertEquals(wrong, f.samples.first { it.first.id == 3L }.second)
        assertEquals("real-film-c", f.qoe.first { it.first.playSessionId == "ps1" }.second)
        assertEquals(wrong, f.qoe.first { it.first.playSessionId == "ps2" }.second)
    }

    @Test fun `each film's history entry keeps the wrong id the right id and every row with its old value`() = runBlocking<Unit> {
        val f = world()
        val (plan, work) = planRepair(f, wrong)
        applyRepair(f, plan, work)
        val c = f.histories.single { it.first == "film-c" }
        val snap = Json.parseToJsonElement(c.third) as JsonObject
        assertEquals(wrong, snap["oldId"]!!.jsonPrimitive.content)
        assertEquals("real-film-c", snap["newId"]!!.jsonPrimitive.content)
        val rows = snap["rows"] as JsonArray
        assertEquals(setOf("s-c", "s-mixed", "dev1|ps1"), rows.map { (it as JsonObject)["key"]!!.jsonPrimitive.content }.toSet())
        val session = rows.map { it as JsonObject }.first { it["key"]!!.jsonPrimitive.content == "s-c" }
        val old = session["old"] as JsonObject
        assertEquals(wrong, old["item_id"]!!.jsonPrimitive.content)
        assertTrue("film-c" in old["queue_json"]!!.jsonPrimitive.content, "the old queue is kept so it can be undone by hand")
        assertTrue(f.histories.all { it.first != "film-b" }, "film B had no row attributed to it")
    }

    @Test fun `the album's single non-empty row is carried to its film and then reset in that order`() = runBlocking<Unit> {
        val f = world()
        val (plan, work) = planRepair(f, wrong)
        assertEquals("film-d", plan.album?.carryTo)
        assertTrue(plan.album!!.resetAlbumRow)
        applyRepair(f, plan, work)
        assertEquals(listOf("watch:u1:real-film-d:true", "watch:u1:$wrong:false"), f.writes.filter { it.startsWith("watch") })
        assertTrue(f.watch.getValue("u1" to wrong).isEmpty(), "the album's row reads empty again")
        assertTrue(f.watch.getValue("u1" to "real-film-d").played)
    }

    @Test fun `the carry only lands on an empty film row and nothing is reset when it does not`() = runBlocking<Unit> {
        val f = world()
        f.watch["u1" to "real-film-d"] = ViewerData(false, 5_000_000L, false, 0, null)   // the film's own resume point
        val (plan, work) = planRepair(f, wrong)
        val report = applyRepair(f, plan, work)
        assertTrue(f.writes.none { it.startsWith("watch") }, f.writes.toString())
        assertTrue(report.lines.any { "already holds other data" in it }, report.lines.toString())
        assertTrue(!f.watch.getValue("u1" to wrong).isEmpty())
    }

    @Test fun `a row played after the move is carried but not reset`() = runBlocking<Unit> {
        val f = world()
        f.watch["u1" to wrong] = ViewerData(true, 0, false, 2, "2026-10-14T20:00:00.0000000Z")
        // The history line of the film it came from is 2026-10-13 02:26Z: the album row was played after it, so it is left alone.
        val (plan, work) = planRepair(f, wrong)
        assertTrue(!plan.album!!.resetAlbumRow)
        applyRepair(f, plan, work)
        assertEquals(listOf("watch:u1:real-film-d:true"), f.writes.filter { it.startsWith("watch") })
    }

    @Test fun `several non-empty album rows are neither carried nor reset`() = runBlocking<Unit> {
        val f = world()
        f.watch["u2" to wrong] = ViewerData(false, 9_000L, false, 0, null)
        val (plan, work) = planRepair(f, wrong)
        assertEquals(2, plan.album!!.nonEmpty)
        assertEquals(null, work.album)
        applyRepair(f, plan, work)
        assertTrue(f.writes.none { it.startsWith("watch") })
    }

    @Test fun `no history line saying put back for one viewer means no carry`() = runBlocking<Unit> {
        val f = world()
        f.entries[3] = f.entries[3].copy(detail = f.entries[3].detail.replace("; watch data put back for 1 viewer", ""))
        val (plan, work) = planRepair(f, wrong)
        assertEquals(null, work.album)
        assertTrue("put back for 1 viewer" in plan.album!!.note, plan.album!!.note)
    }

    @Test fun `a film Jellyfin has no item for is waiting and nothing is moved for it`() = runBlocking<Unit> {
        val f = world()
        f.items.remove("$root/Film C (2025)/C.mkv")
        val (plan, work) = planRepair(f, wrong)
        assertEquals("waiting", plan.films.single { it.mediaId == "film-c" }.state)
        assertEquals("keep", row(plan, "playback_session", "s-c").action)
        assertEquals("waiting for the scan", plan.mediaRows.single { it.mediaId == "film-c" }.action)
        applyRepair(f, plan, work)
        assertEquals(wrong, f.itemOfSession["s-c"])
    }

    @Test fun `a media row bound to the wrong item is corrected and the others are left`() = runBlocking<Unit> {
        val f = world()
        f.media.remove("film-d")   // swept; ingest has not recreated it
        val (plan, work) = planRepair(f, wrong)
        assertEquals("correct jellyfinId", plan.mediaRows.single { it.mediaId == "film-b" }.action)
        assertEquals("ok", plan.mediaRows.single { it.mediaId == "film-a" }.action)
        assertEquals("waiting for the scan", plan.mediaRows.single { it.mediaId == "film-d" }.action)
        applyRepair(f, plan, work)
        assertEquals(listOf("media:film-b:real-film-b"), f.writes.filter { it.startsWith("media:") })
    }

    @Test fun `what the real items carry is reported and never reconstructed`() = runBlocking<Unit> {
        val f = world()
        val (plan, work) = planRepair(f, wrong)
        val c = plan.viewers.single { it.mediaId == "film-c" }
        assertEquals(1, c.withData)      // u1 holds data on the real item
        assertEquals(0, c.empty)
        assertEquals(2, c.unreadable)    // u2 and u3 cannot read it
        // No watch write other than the album's carry and reset: values are not derived from session rows.
        applyRepair(f, plan, work)
        assertTrue(f.writes.none { it.startsWith("watch") && "real-film-c" in it })
    }

    @Test fun `the wrong id is confirmed before anything is planned`() = runBlocking<Unit> {
        val film = world().also { it.wrongItem = RepairItem("Movie", "/lib/films/Somebody/S.mkv") }
        assertTrue(planRepair(film, wrong).first.stop!!.contains("as a film"))
        val atFilmPath = world().also { it.wrongItem = RepairItem("MusicAlbum", "$root/Film A (2023)/A.mkv") }
        assertTrue(planRepair(atFilmPath, wrong).first.stop!!.contains("moved film's path"))
        val unknown = world().also { it.wrongItem = null }
        assertTrue(planRepair(unknown, wrong).first.stop!!.contains("does not know"))
        assertTrue(!planRepair(unknown, wrong).first.confirmed)
    }

    @Test fun `the digest is stable for one plan and changes with it`() = runBlocking<Unit> {
        val a = planRepair(world(), wrong).first.digest
        assertEquals(a, planRepair(world(), wrong).first.digest)
        val f = world()
        f.sessions += RepairSessionRow("s-new", "dev1", null, queue(wrong to "film-a"), 0, 1, 2, 2)
        f.itemOfSession["s-new"] = wrong
        assertNotEquals(a, planRepair(f, wrong).first.digest)
    }

    @Test fun `an apply is refused on a stale digest an unconfirmed id or an empty plan`() = runBlocking<Unit> {
        val read = planRepair(world(), wrong).first
        assertEquals(null, repairRefusal(read, read.digest))
        // Something changed between the dry run and the press: a fresh plan has another digest.
        val f = world()
        f.sessions += RepairSessionRow("s-new", "dev1", null, queue(wrong to "film-a"), 0, 1, 2, 2)
        f.itemOfSession["s-new"] = wrong
        val fresh = planRepair(f, wrong).first
        assertTrue(repairRefusal(fresh, read.digest)!!.contains("has changed since it was read"))
        assertTrue(repairRefusal(planRepair(world().also { it.wrongItem = null }, wrong).first, read.digest)!!.contains("does not know"))
        val done = world()
        val (plan, work) = planRepair(done, wrong)
        applyRepair(done, plan, work)
        val after = planRepair(done, wrong).first
        assertEquals("Nothing to do", repairRefusal(after, after.digest))
    }

    @Test fun `a second run after the apply plans nothing`() = runBlocking<Unit> {
        val f = world()
        val (plan, work) = planRepair(f, wrong)
        applyRepair(f, plan, work)
        val (again, work2) = planRepair(f, wrong)
        assertTrue(again.nothingToDo, again.toString())
        assertEquals(0, again.attributed)
        assertTrue(work2.writes.isEmpty() && work2.album == null && work2.mediaFixes.isEmpty())
        // What the evidence could not account for is still listed — it might be the album's own row.
        assertTrue(again.unaccounted > 0)
    }
}
