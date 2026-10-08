package dev.jellystructure.music

import co.touchlab.sqliter.DatabaseConfiguration
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicAlbum
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 305 (FR-305-1 as replaced by dev review 3) — the owner's rows, the migration, and the album JSON's new fields. */
class MusicEditionsStoreTest {
    private val base = "/tmp/jellystructure-test-editions-store-${getpid()}"
    private val files = ArrayList<String>()
    private fun db(name: String): String = "$base-$name.db".also { f -> files += f; listOf("", "-wal", "-shm").forEach { runCatching { platform.posix.remove(f + it) } } }

    @AfterTest fun tearDown() { for (f in files) listOf("", "-wal", "-shm").forEach { runCatching { platform.posix.remove(f + it) } } }

    @Test
    fun a_database_one_version_old_upgrades_and_keeps_its_music() = runBlocking<Unit> {
        val file = db("upgrade")
        val store = MusicStore(createDatabase(file))
        val lib = EditionsFixture.library()
        store.replaceLibrary(lib.rows())
        store.setChoices(listOf("rec:kw-r2"), mapOf("live" to true), 5)
        // Back to the version before 305: the four tables gone, user_version one lower.
        val v = JellystructureDb.Schema.version.toInt()
        val raw = app.cash.sqldelight.driver.native.NativeSqliteDriver(DatabaseConfiguration(
            name = file.substringAfterLast('/'), version = v, create = {}, upgrade = { _, _, _ -> },
            extendedConfig = DatabaseConfiguration.Extended(basePath = file.substringBeforeLast('/')),
        ))
        for (t in listOf("music_official_pick", "music_single_home", "music_same_song", "music_sound_pair")) raw.execute(null, "DROP TABLE $t", 0)
        // The migrations after 305's (66.sqm) run again too: their tables go with it (R368/R369's 67 and 68, 307's 69).
        for (t in listOf("playback_session", "playback_session_event", "playback_session_controller", "publish_item")) raw.execute(null, "DROP TABLE IF EXISTS $t", 0)
        // …and 310's 71 creates playback_outbox.
        raw.execute(null, "DROP TABLE IF EXISTS playback_outbox", 0)
        // …and R266's 72 creates cast_connect_launch.
        raw.execute(null, "DROP TABLE IF EXISTS cast_connect_launch", 0)
        // …and 308's 70 adds four columns to playback_qoe.
        for (c in listOf("variant_switches_down", "variant_switches_up", "variant_bandwidth_bps", "variant_height")) raw.execute(null, "ALTER TABLE playback_qoe DROP COLUMN $c", 0)
        raw.execute(null, "PRAGMA user_version = ${MIGRATION_305}", 0)
        raw.close()

        val again = MusicStore(createDatabase(file))
        val snap = again.snapshot()
        assertEquals(lib.albums.size, snap.albums.size)
        assertEquals(lib.tracks.size, snap.tracks.size)
        assertEquals(true, snap.choices["rec:kw-r2"]?.get("live")?.on)
        again.putSameSong("kw-1", "kw-3", MusicSameSong.SAME, 9)
        again.putHome("s-nl", null, 9)
        again.putSoundPairs(listOf(MusicSoundPair("kw-5", "s-lt-1", 0.1, 0.99, 0, 9)))
        assertEquals(1, MusicStore(createDatabase(file)).snapshot().soundPairs.size)
    }

    @Test
    fun same_song_is_stored_with_a_less_than_b_whatever_order_it_is_given() = runBlocking<Unit> {
        val store = MusicStore(createDatabase(db("pairs")))
        store.putSameSong("zz", "aa", MusicSameSong.NOT_SAME, 3)
        assertEquals(mapOf(("aa" to "zz") to MusicSameSong.NOT_SAME), store.snapshot().sameSong)
        val reread = MusicStore(createDatabase(files.last()))
        assertEquals(mapOf(("aa" to "zz") to MusicSameSong.NOT_SAME), reread.snapshot().sameSong)
        store.putSameSong("aa", "zz", null, 4)
        assertTrue(store.snapshot().sameSong.isEmpty())
    }

    @Test
    fun an_album_json_written_before_305_decodes_with_no_official_list() {
        val old = """{"id":"kw","title":"Kite Weather","matchState":"matched","releaseGroupMbid":"rg-kw","versionFactsAt":5}"""
        val a = Json { ignoreUnknownKeys = true }.decodeFromString(MusicAlbum.serializer(), old)
        assertNull(a.official); assertNull(a.userOfficial); assertNull(a.singleFrom); assertTrue(a.extraOrigins.isEmpty())
    }

    @Test
    fun the_owners_pick_survives_a_rescan_and_a_rematch_to_the_same_group() = runBlocking<Unit> {
        val store = MusicStore(createDatabase(db("pick")))
        val lib = EditionsFixture.library()
        store.replaceLibrary(lib.rows())
        val kw = store.album("kw")!!
        val pick = MusicOfficialPick("kw", "kw-rel-jp", "rg-kw", 7)
        store.putPick(pick, kw.copy(userOfficial = kw.official!!.copy(releaseMbid = "kw-rel-jp")))
        // A rescan: the scan's carry keeps everything Jellyfin does not own.
        val rows = MusicIngest.build(
            dev.jellystructure.config.LibraryMapping(jellyfinId = EditionsFixture.LIB, name = "Music", collectionType = "music", jellyfinPath = "/music", localPath = "/music"),
            dev.jellystructure.auth.JellyfinMusicLibrary(artists = emptyList(), tracks = emptyList(), albums = listOf(dev.jellystructure.auth.JellyfinMusicItem(id = "kw", name = "Kite Weather", type = "MusicAlbum", path = "/music/kw"))),
            emptyMap(), mapOf("kw" to store.album("kw")!!), emptyMap(), 100, { false },
        )
        store.replaceLibrary(rows.copy(albums = rows.albums.filter { it.id == "kw" }, tracks = emptyList(), artists = emptyList()))
        val reread = MusicStore(createDatabase(files.last())).snapshot()
        assertEquals(pick, reread.picks["kw"])
        assertEquals("kw-rel-jp", reread.albums["kw"]?.userOfficial?.releaseMbid)
    }

    @Test
    fun a_rematch_to_another_group_or_clear_match_drops_the_pick_with_a_history_line() = runBlocking<Unit> {
        val file = db("drop")
        val database = createDatabase(file)
        val store = MusicStore(database)
        store.replaceLibrary(EditionsFixture.library().rows())
        val kw = store.album("kw")!!
        store.putPick(MusicOfficialPick("kw", "kw-rel-jp", "rg-kw", 7), kw.copy(userOfficial = kw.official))
        val history = dev.jellystructure.media.MediaHistory(database)
        val cfg = dev.jellystructure.config.ConfigStore("$base-drop.toml").also { files += "$base-drop.toml"; it.load() }
        val matcher = MusicMatchService(store, MusicBrainzClient(cfg), AcoustIdClient({ "" }), cfg, { null }, history)
        matcher.clear("kw")
        assertNull(store.snapshot().picks["kw"])
        assertNull(store.album("kw")?.userOfficial)
        assertNotNull(history.forItem("kw").firstOrNull { it.detail.contains("Official album choice dropped") })
    }
}

/** 305's migration is 66.sqm: a database at user_version 66 runs it (and every later one) again. */
private const val MIGRATION_305 = 66
