package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.screens.HOME_SNAPSHOT_STALE_AFTER_MS
import dev.jellystructure.shared.tv.MusicAlbumCard
import dev.jellystructure.shared.tv.MusicGenreCount
import dev.jellystructure.shared.tv.MusicHome
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicPlaylist
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4091 FR-5 — the music entry pages are saved per user, seed a cold start, and are bounded. */
class MusicSnapshotTest {
    private class MemoryStorage : MusicSnapshotStorage {
        val files = HashMap<String, String>()
        override fun read(userId: String) = files[userId]
        override fun write(userId: String, json: String) { files[userId] = json }
        override fun delete(userId: String) { files.remove(userId) }
    }

    private var now = 1_000_000_000_000L
    private val storage = MemoryStorage()
    private val snapshots = MusicSnapshots(storage) { now }
    private val user = UserMusicSnapshots("u1", snapshots)

    private fun albums(n: Int, total: Int = n) =
        MusicList(albums = List(n) { MusicAlbumCard(id = "a$it", title = "t$it") }, total = total)

    @Test fun `a part is fresh for 7 days and a negative age is stale`() {
        val saved = now
        assertTrue(isMusicPartFresh(saved, saved))
        assertTrue(isMusicPartFresh(saved, saved + HOME_SNAPSHOT_STALE_AFTER_MS))
        assertFalse(isMusicPartFresh(saved, saved + HOME_SNAPSHOT_STALE_AFTER_MS + 1))
        assertFalse(isMusicPartFresh(saved, saved - 1))
    }

    @Test fun `a copy older than 7 days counts as absent, part by part`() = runBlocking<Unit> {
        user.saveGenres(listOf(MusicGenreCount("rock", 3)))
        now += HOME_SNAPSHOT_STALE_AFTER_MS - 1
        user.savePlaylists(listOf(MusicPlaylist("p1", "Mix")))
        now += 2   // genres are now 7 days and 1 ms old, playlists are 3 ms old
        val loaded = assertNotNull(user.load())
        assertNull(loaded.genres)
        assertEquals("p1", loaded.playlists?.value?.single()?.id)
    }

    @Test fun `each store writes its own part and the others are kept`() = runBlocking<Unit> {
        user.saveHome(MusicHome())
        user.saveList("albums", "added", albums(2))
        user.saveGenres(listOf(MusicGenreCount("rock", 3)))
        user.savePlaylists(listOf(MusicPlaylist("p1", "Mix")))
        val loaded = assertNotNull(user.load())
        assertNotNull(loaded.home)
        assertEquals(2, loaded.listFor("albums", "added")?.albums?.size)
        assertEquals("rock", loaded.genres?.value?.single()?.name)
        assertEquals("p1", loaded.playlists?.value?.single()?.id)
    }

    @Test fun `a list seeds only when the saved sort is the sort being asked for`() = runBlocking<Unit> {
        user.saveList("albums", "added", albums(2))
        val loaded = assertNotNull(user.load())
        assertNotNull(loaded.listFor("albums", "added"))
        assertNull(loaded.listFor("albums", "title"))
        assertNull(loaded.listFor("songs", "added"))
    }

    @Test fun `a user's copy is not another user's`() = runBlocking<Unit> {
        user.saveGenres(listOf(MusicGenreCount("rock", 3)))
        assertNull(UserMusicSnapshots("u2", snapshots).load())
    }

    @Test fun `a copy that fails to decode is absent and the next write replaces it`() = runBlocking<Unit> {
        storage.files["u1"] = "{not json"
        assertNull(user.load())
        user.saveGenres(listOf(MusicGenreCount("rock", 3)))
        assertNotNull(user.load()?.genres)
    }

    @Test fun `a copy written by a newer build with unknown fields still loads`() {
        storage.files["u1"] = """{"future":1,"genres":{"savedAtEpochMs":$now,"value":[{"name":"rock","album_count":3,"later":true}],"x":1}}"""
        assertEquals("rock", user.load()?.genres?.value?.single()?.name)
    }

    @Test fun `a write over the 2 MB cap is skipped and the previous copy kept`() = runBlocking<Unit> {
        user.saveGenres(listOf(MusicGenreCount("rock", 3)))
        val before = storage.files["u1"]
        user.savePlaylists(List(40_000) { MusicPlaylist("p$it", "x".repeat(60)) })
        assertEquals(before, storage.files["u1"])
        assertEquals("rock", user.load()?.genres?.value?.single()?.name)
    }

    @Test fun `clear removes the copy`() = runBlocking<Unit> {
        user.saveGenres(listOf(MusicGenreCount("rock", 3)))
        snapshots.clear("u1")
        assertNull(user.load())
        assertNull(storage.files["u1"])
    }

    @Test fun `a seeded paged list starts Ready, refreshes to the fresh page 0 and saves it`() = runBlocking<Unit> {
        val saved = albums(2, total = 50)
        val savedFirst = ArrayList<MusicList>()
        val fresh = albums(3, total = 50)
        val list = MusicPagedList(saved, { savedFirst += it }) { fresh }
        assertEquals(saved, (list.state.value as Load.Ready).value)
        list.load()
        withTimeout(3_000) { list.state.first { (it as? Load.Ready)?.value == fresh } }
        withTimeout(3_000) { while (savedFirst.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf(fresh), savedFirst)
    }

    @Test fun `a failed quiet refresh keeps the saved copy on screen`() = runBlocking<Unit> {
        val saved = albums(2, total = 50)
        val list = MusicPagedList(saved) { error("offline") }
        list.load()
        withTimeout(3_000) { while (list.lastFailure == null) kotlinx.coroutines.delay(10) }
        assertEquals(saved, (list.state.value as Load.Ready).value)
    }

    @Test fun `a more page is never saved and paging continues from the fresh page 0`() = runBlocking<Unit> {
        val saved = ArrayList<MusicList>()
        val asked = ArrayList<Int>()
        val list = MusicPagedList(null, { saved += it }) { p -> asked += p; albums(2, total = 6) }
        list.load()
        withTimeout(3_000) { list.state.first { it is Load.Ready } }
        withTimeout(3_000) { while (saved.isEmpty()) kotlinx.coroutines.delay(10) }
        list.more()
        withTimeout(3_000) { while ((list.state.value as Load.Ready).value.albums.size < 4) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(50)
        assertEquals(listOf(0, 1), asked)
        assertEquals(1, saved.size)
    }

    @Test fun `more on a list that still shows only its saved copy refreshes page 0 instead of fetching page 1`() = runBlocking<Unit> {
        val asked = ArrayList<Int>()
        val list = MusicPagedList(albums(2, total = 6)) { p -> asked += p; albums(2, total = 6) }
        list.more()
        withTimeout(3_000) { while (asked.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf(0), asked)
    }

    @Test fun `a seeded loader starts Ready, keeps the copy when the refresh fails and saves each answer`() = runBlocking<Unit> {
        val failing = MusicLoader<String>("saved") { error("offline") }
        assertEquals("saved", (failing.state.value as Load.Ready).value)
        failing.load()
        withTimeout(3_000) { while (failing.lastFailure == null) kotlinx.coroutines.delay(10) }
        assertEquals("saved", (failing.state.value as Load.Ready).value)

        val savedAnswers = ArrayList<String>()
        val ok = MusicLoader<String>("saved", { savedAnswers += it }) { "fresh" }
        ok.load()
        withTimeout(3_000) { ok.state.first { (it as? Load.Ready)?.value == "fresh" } }
        withTimeout(3_000) { while (savedAnswers.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("fresh"), savedAnswers)
    }

    @Test fun `a saving failure never reaches the page`() = runBlocking<Unit> {
        val loader = MusicLoader<String>(null, { error("disk full") }) { "fresh" }
        loader.load()
        withTimeout(3_000) { loader.state.first { it is Load.Ready } }
        assertIs<Load.Ready<String>>(loader.state.value)
    }

    @Test fun `the Listen loader seeds from the saved home and saves the fresh one`() = runBlocking<Unit> {
        user.saveHome(MusicHome())
        val loader = musicListenLoader(user) { MusicHome() }
        assertIs<Load.Ready<MusicHome>>(loader.state.value)
        assertIs<Load.Loading>(musicListenLoader(UserMusicSnapshots("nobody", snapshots)) { MusicHome() }.state.value)
    }
}
