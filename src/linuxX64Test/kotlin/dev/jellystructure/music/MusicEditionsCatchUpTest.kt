package dev.jellystructure.music

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicOfficialList
import dev.jellystructure.model.MusicOfficialSong
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 305 (test 10, dev review 3/4) — `match_musicbrainz`'s catch-up pass against a counting fake MusicBrainz. */
class MusicEditionsCatchUpTest {
    private val base = "/tmp/jellystructure-test-editions-catchup-${getpid()}"
    private lateinit var store: MusicStore
    private lateinit var config: ConfigStore

    private inner class CountingMb(var down: Boolean = false) : MusicBrainzClient(config) {
        val calls = ArrayList<String>()
        val pressings = (1..8).map { Pressings.release("kw-gb-$it", Pressings.ELEVEN, "2006-0${it % 9 + 1}") } +
            Pressings.release("kw-jp", Pressings.ELEVEN + "kw-r12", "2006-03-08", country = "JP")
        override suspend fun releaseGroup(mbid: String): MbReleaseGroup? {
            calls += "rg:$mbid"; if (down) return null
            return when (mbid) {
                "rg-kw" -> Pressings.group()
                "rg-nl" -> MbReleaseGroup("rg-nl", "Northern Line", primaryType = "Single",
                    relations = listOf(MbRelation(type = "single from", direction = "forward", releaseGroup = MbReleaseGroup("rg-kw", "Kite Weather"))))
                "rg-ep" -> MbReleaseGroup("rg-ep", "Shoreline EP", primaryType = "EP")
                else -> null
            }
        }
        override suspend fun releasesOf(releaseGroupMbid: String): List<MbRelease>? { calls += "releases:$releaseGroupMbid"; return if (down) null else pressings }
        override suspend fun recording(mbid: String): MbRecording? {
            calls += "recording:$mbid"
            return MbRecording(mbid, "B-side", releases = listOf(MbRelease("promo-cd", "Kite Weather Promo", "DE", "2005", "Official")))
        }
        override suspend fun releaseWithRels(mbid: String): MbRelease? { calls += "rels:$mbid"; return null }
        override suspend fun release(mbid: String): MbRelease? = null
        override suspend fun searchReleaseGroups(artist: String, album: String, limit: Int): List<MbReleaseGroup>? = emptyList()
        override suspend fun artist(mbid: String): MbArtist? = null
    }

    @BeforeTest fun setUp() { runBlocking {
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        config = ConfigStore("$base.toml").also { it.load() }
        val lib = EditionsFixture.library()
        // As 276 left them: matched, versions read, no official list or relationships yet.
        val albums = lib.albums.filter { it.id in setOf("kw", "s-nl", "ep-0") }.map { a ->
            a.copy(official = null, extraOrigins = emptyMap(), singleFrom = null, relsReadAt = null, versionFactsAt = 1,
                releaseGroupMbid = when (a.id) { "kw" -> "rg-kw"; "s-nl" -> "rg-nl"; else -> "rg-ep" })
        }
        store.replaceLibrary(MusicLibraryRows(EditionsFixture.LIB, lib.artists, albums, lib.tracks.filter { it.albumId in setOf("kw", "s-nl", "ep-0") }))
    } }

    @AfterTest fun tearDown() { listOf("$base.db", "$base.db-wal", "$base.db-shm", "$base.toml").forEach { runCatching { platform.posix.remove(it) } } }

    private fun svc(mb: MusicBrainzClient) = MusicMatchService(store, mb, AcoustIdClient({ "" }), config, { null })

    @Test
    fun a_matched_album_gets_official_extras_and_origins_in_one_or_two_requests() = runBlocking<Unit> {
        val mb = CountingMb()
        svc(mb).matchAlbums(null, scopeAll = false)
        assertEquals(listOf("rg:rg-kw", "releases:rg-kw"), mb.calls.filter { "rg-kw" in it || it.startsWith("recording") })
        val kw = store.album("kw")!!
        assertEquals(11, kw.official!!.songs.size); assertEquals(8, kw.official!!.k); assertEquals(9, kw.official!!.m)
        assertEquals("JP", kw.extraOrigins["kw-r12"]?.country)
    }

    @Test
    fun a_locked_album_is_caught_up_too() = runBlocking<Unit> {
        store.putAlbum(store.album("kw")!!.copy(matchLocked = true))
        svc(CountingMb()).matchAlbums(null, scopeAll = false)
        assertEquals(11, store.album("kw")!!.official?.songs?.size)
    }

    @Test
    fun a_second_run_asks_nothing_unless_scope_all_or_the_group_changed() = runBlocking<Unit> {
        svc(CountingMb()).matchAlbums(null, scopeAll = false)
        val again = CountingMb()
        svc(again).matchAlbums(null, scopeAll = false)
        assertTrue(again.calls.none { it.startsWith("rg:") || it.startsWith("releases:") }, again.calls.toString())
        store.putAlbum(store.album("kw")!!.copy(official = store.album("kw")!!.official!!.copy(groupMbid = "rg-old")))
        val changed = CountingMb()
        svc(changed).matchAlbums(null, scopeAll = false)
        assertTrue("releases:rg-kw" in changed.calls)
        // An `all` run asks again for the locked ones too (matched unlocked ones are refreshed by the match itself).
        store.putAlbum(store.album("kw")!!.copy(matchLocked = true))
        val all = CountingMb()
        svc(all).matchAlbums(null, scopeAll = true)
        assertTrue("releases:rg-kw" in all.calls)
    }

    @Test
    fun an_extra_on_no_release_of_the_group_costs_one_recording_call() = runBlocking<Unit> {
        store.putTracks(listOf(EditionsFixture.track("kw-13", "kw", 13, "Promo Only", "kw-r99")))
        val mb = CountingMb()
        svc(mb).matchAlbums(null, scopeAll = false)
        assertEquals(listOf("recording:kw-r99"), mb.calls.filter { it.startsWith("recording") })
        assertEquals("DE", store.album("kw")!!.extraOrigins["kw-r99"]?.country)
    }

    @Test
    fun singles_and_eps_without_relationships_are_caught_up_once() = runBlocking<Unit> {
        val mb = CountingMb()
        svc(mb).matchAlbums(null, scopeAll = false)
        assertEquals("rg-kw", store.album("s-nl")!!.singleFrom)
        assertTrue(store.album("ep-0")!!.relsReadAt != null)
        val again = CountingMb()
        svc(again).matchAlbums(null, scopeAll = false)
        assertTrue(again.calls.none { it == "rg:rg-nl" || it == "rg:rg-ep" })
    }

    @Test
    fun an_unreachable_musicbrainz_leaves_the_official_list_as_it_was() = runBlocking<Unit> {
        val known = MusicOfficialList(listOf(MusicOfficialSong("kw-r1", "Kite Weather")), "old", "Kite Weather", 1, 1, "rg-old", "Kite Weather")
        store.putAlbum(store.album("kw")!!.copy(official = known))
        svc(CountingMb(down = true)).matchAlbums(null, scopeAll = false)
        assertEquals(known, store.album("kw")!!.official)
    }
}
