package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicLyrics
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 292 — MusicBrainz's relationships read into facts (dev review 1–2, from a release answer shaped exactly as
 * musicbrainz.org sends it with `recording-level-rels+work-rels+artist-rels+recording-rels`, recorded 2026-10-01 and
 * renamed to stand-ins), and the admin's side of a version on a real store: the panel, a tick on one copy reaching
 * every copy, *Back to automatic*, *Set version…*, the Version facet's Only and Hide, and the Dashboard row.
 */
class MusicVersionFactsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val release = """
    {"id":"rel-1","title":"Tide Tables 1999–2012","media":[{"position":1,"track-count":6,"tracks":[
      {"id":"t1","position":1,"title":"Fog Bank","recording":{"id":"r-fog","title":"Fog Bank","disambiguation":"",
        "relations":[{"type":"performance","direction":"forward","target-type":"work","attributes":["cover","demo","instrumental"],"attribute-values":{},
          "work":{"id":"w1","title":"Fog Bank","language":"eng","languages":["eng"],"type":"Song"}},
          {"type":"instrumental","direction":"backward","target-type":"recording","attributes":[],
          "recording":{"id":"r-fog-sung","title":"Fog Bank","length":201000,"video":false}}]}},
      {"id":"t2","position":2,"title":"Prelude","recording":{"id":"r-prelude","title":"Prelude",
        "relations":[{"type":"performance","direction":"forward","target-type":"work","attributes":[],
          "work":{"id":"w2","title":"Prelude","language":"zxx","languages":["zxx"]}}]}},
      {"id":"t3","position":3,"title":"Salt on the Window","recording":{"id":"r-salt-live","title":"Salt on the Window",
        "relations":[{"type":"performance","direction":"forward","target-type":"work","attributes":["partial","live"],
          "work":{"id":"w3","title":"Intro","language":"zxx","languages":["zxx"]}},
          {"type":"performance","direction":"forward","target-type":"work","attributes":["live"],
          "work":{"id":"w4","title":"Salt on the Window","language":"eng","languages":["eng"]}}]}},
      {"id":"t4","position":4,"title":"Northern Line","recording":{"id":"r-north","title":"Northern Line",
        "relations":[{"type":"remix","direction":"forward","target-type":"recording","attributes":[],"recording":{"id":"r-north-orig","title":"Northern Line"}},
          {"type":"edit","direction":"forward","target-type":"recording","attributes":[],"recording":{"id":"r-north-orig","title":"Northern Line"}},
          {"type":"remixer","direction":"backward","target-type":"artist","attributes":[],"artist":{"id":"a9","name":"Lighthouse Keepers"}}]}},
      {"id":"t5","position":5,"title":"Kite Weather","recording":{"id":"r-kite","title":"Kite Weather","disambiguation":"instrumental demo","relations":[]}},
      {"id":"t6","position":6,"title":"Harbour","recording":{"id":"r-harbour","title":"Harbour",
        "relations":[{"type":"remix","direction":"backward","target-type":"recording","attributes":[],"recording":{"id":"r-harbour-rmx","title":"Harbour (club mix)"}},
          {"type":"instrumental","direction":"forward","target-type":"recording","attributes":[],"recording":{"id":"r-harbour-inst","title":"Harbour"}},
          {"type":"performance","direction":"forward","target-type":"work","attributes":[],"work":{"id":"w6","title":"Harbour","language":null,"languages":[]}}]}}
    ]}]}
    """.trimIndent()

    @Test
    fun a_release_answer_reads_into_facts() {
        val r = json.decodeFromString(MbRelease.serializer(), release)
        val f = MusicVersionFacts.fromRelease(r, 7)
        val fog = f.getValue("r-fog")
        assertTrue(fog.cover && fog.demo && fog.instrumental && !fog.live && !fog.noWords)
        assertEquals("r-fog-sung", fog.instrumentalOf?.mbid)
        val prelude = f.getValue("r-prelude")
        assertTrue(prelude.noWords); assertFalse(prelude.instrumental)
        val live = f.getValue("r-salt-live")
        assertTrue(live.live); assertFalse(live.instrumental, "a zxx intro piece does not make a sung song instrumental"); assertFalse(live.noWords)
        val north = f.getValue("r-north")
        assertTrue(north.remix && north.edit); assertEquals("r-north-orig", north.remixOf?.mbid)
        assertEquals("instrumental demo", f.getValue("r-kite").disambiguation)
        val harbour = f.getValue("r-harbour")
        assertFalse(harbour.remix, "the original of a remix is not a remix"); assertFalse(harbour.instrumental, "the sung original is not instrumental")
        assertFalse(harbour.noWords, "a work with no language is not a piece never sung")
    }

    // ── on a store ──

    private val base = "/tmp/jellystructure-test-versions-${getpid()}"
    private lateinit var store: MusicStore
    private lateinit var svc: MusicVersionService
    private val hl = MusicCredit("x", "Harbour Lights")

    @BeforeTest fun setUp() = runBlocking {
        runCatching { platform.posix.remove("$base.db") }
        store = MusicStore(createDatabase("$base.db"))
        val albums = listOf("live", "best", "box", "studio").map { MusicAlbum(id = it, libraryId = "l", title = "Album $it", albumArtists = listOf(hl), matchState = MusicMatch.MATCHED) }
        fun t(id: String, album: String, title: String, rec: String?, state: String? = MusicRecording.AGREES, lyrics: String? = null) =
            MusicTrack(id = id, albumId = album, libraryId = "l", title = title, artists = listOf(hl), recordingMbid = rec, recordingState = rec?.let { state }, lyricsState = lyrics)
        store.replaceLibrary(MusicLibraryRows("l", listOf(dev.jellystructure.model.MusicArtist(id = "x", name = "Harbour Lights", libraryId = "l")), albums, listOf(
            t("c1", "live", "Salt on the Window (live at the harbour, 2011)", "r-salt-live"),
            t("c2", "best", "Salt on the Window (live)", "r-salt-live"),
            t("c3", "box", "Salt on the Window", "r-salt-live"),
            t("s1", "studio", "Salt on the Window", "r-salt"),
            t("p1", "box", "Prelude", "r-prelude", lyrics = MusicLyrics.SYNCED),
            t("f1", "box", "Fog Bank", "r-fog", lyrics = MusicLyrics.PLAIN),
            t("u1", "studio", "Northbound", null),
        )))
        store.putFacts(MusicVersionFacts.fromRelease(json.decodeFromString(MbRelease.serializer(), release), 7).values.toList())
        svc = MusicVersionService(store)
    }

    @AfterTest fun tearDown() { runCatching { platform.posix.remove("$base.db") } }

    @Test
    fun one_recording_one_answer_and_the_panel_says_so() = runBlocking {
        val p = svc.panel("c3")!!
        assertTrue(p.rows.single { it.key == "live" }.on, "the box copy is Live through the other copies' titles and MusicBrainz")
        assertEquals(setOf("live", "best"), p.otherAlbums.map { it.albumId }.toSet())
        assertTrue(svc.panel("s1")!!.rows.none { it.on }, "the studio song is never touched")
        svc.set("c1", "live", false)
        listOf("c1", "c2", "c3").forEach { assertFalse(svc.panel(it)!!.rows.single { r -> r.key == "live" }.on, it) }
        assertTrue(svc.panel("c2")!!.rows.single { it.key == "live" }.removed)
        svc.reset("c2")
        listOf("c1", "c2", "c3").forEach { assertTrue(svc.panel(it)!!.rows.single { r -> r.key == "live" }.on, it) }
    }

    @Test
    fun session_ticked_by_hand_brings_live_and_moves_with_a_match() = runBlocking {
        svc.set("u1", "session", true)
        assertEquals(listOf("live", "session"), store.snapshot().versions.of(store.track("u1")!!).shown)
        svc.set("u1", "live", false)
        assertEquals(listOf("session"), store.snapshot().versions.of(store.track("u1")!!).shown, "Session stays")
        // The song gains its recording: its ticks go with it.
        store.moveChoices("trk:u1", "rec:r-north2")
        store.putTracks(listOf(store.track("u1")!!.copy(recordingMbid = "r-north2", recordingState = MusicRecording.AGREES)))
        assertEquals(listOf("session"), store.snapshot().versions.of(store.track("u1")!!).shown)
    }

    @Test
    fun set_version_on_a_selection_and_the_facet() = runBlocking {
        val pv = svc.preview(listOf("c1", "s1"))
        assertEquals(2, pv.songs); assertEquals(1, pv.counts["live"]); assertEquals(2, pv.otherCopies)
        svc.bulk(listOf("c1", "s1"), setOf("acoustic"), emptySet())
        assertTrue("acoustic" in store.snapshot().versions.of(store.track("c3")!!).shown)
        val snap = store.snapshot()
        val hide = MusicBrowse.browse(snap, MusicBrowse.SONGS, emptyMap(), null, emptyMap(), excluded = mapOf("version" to setOf("live")), artist = "x")
        assertEquals(setOf("s1", "p1", "f1", "u1"), hide.songs.map { it.id }.toSet())
        assertEquals("Songs by Harbour Lights · without Live", hide.sentence)
        val only = MusicBrowse.browse(snap, MusicBrowse.SONGS, mapOf("version" to setOf("none")), null, emptyMap())
        assertEquals(setOf("p1", "u1"), only.songs.map { it.id }.toSet(), "a piece never sung is No version")
        val both = MusicBrowse.browse(snap, MusicBrowse.SONGS, mapOf("version" to setOf("acoustic")), null, emptyMap(), excluded = mapOf("version" to setOf("live")))
        assertEquals(listOf("s1"), both.songs.map { it.id }, "Hide wins over Only")
        assertTrue(both.facets.single { it.key == "version" }.values.single { it.value == "live" }.off)
        assertTrue(MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap()).facets.none { it.key == "version" })
    }

    @Test
    fun the_dashboard_row_counts_its_list_and_the_lyrics_are_hidden() = runBlocking {
        val p = MusicTriage.Music(store.snapshot(), emptySet())
        assertEquals(2, p.count(MusicTriage.INSTRUMENTAL_LYRICS), "Fog Bank (Instrumental) and Prelude (no words)")
        val list = MusicBrowse.browse(store.snapshot(), MusicBrowse.SONGS, emptyMap(), null, emptyMap(), triage = MusicTriage.INSTRUMENTAL_LYRICS)
        assertEquals(setOf("f1", "p1"), list.songs.map { it.id }.toSet())
        assertTrue(svc.panel("f1")!!.lyricsBesideNoSinging)
        assertEquals("Fog Bank", svc.panel("f1")!!.instrumentalOf?.title)
        assertNull(svc.panel("f1")!!.instrumentalOf?.trackId, "the sung song is not in the library")
        // After *Remove the lyrics* (the mark), the row is gone at once.
        store.putTracks(listOf(store.track("f1")!!.copy(lyricsState = MusicLyrics.BLOCKED), store.track("p1")!!.copy(lyricsState = MusicLyrics.BLOCKED)))
        assertEquals(0, MusicTriage.Music(store.snapshot(), emptySet()).count(MusicTriage.INSTRUMENTAL_LYRICS))
        // A viewer's phone shows none either.
        val tv = MusicTvService(store, { null }, JellyfinClient(), ConfigStore("$base.toml").also { it.load() })
        val device = DeviceData("d", "tok", "u1", "viewer", "", isAdmin = false, allowedLibraries = null, kind = "phone")
        assertNull(tv.lyrics(device, "f1"))
        runCatching { platform.posix.remove("$base.toml") }; Unit
    }

    @Test
    fun metadata_counts_use_the_filters_reading() = runBlocking {
        svc.set("u1", "session", true); svc.set("u1", "live", false)
        val t = svc.types()
        assertEquals(4, t.types.single { it.key == "live" }.songs, "three live copies and one Session without Live")
        assertEquals(0, t.types.single { it.key == "live" }.removedByYou, "unticking a Live only the owner had set just takes the tick back")
        assertEquals(1, t.types.single { it.key == "session" }.setByYou)
        assertEquals(2, t.noVersion, "the studio song and the piece never sung")
        assertNotNull(svc.patchType(dev.jellystructure.model.MusicVersionTypePatch("live", color = "#112233")))
        assertNull(svc.patchType(dev.jellystructure.model.MusicVersionTypePatch("live", color = "red")))
        assertEquals("#112233", store.snapshot().versionTypes.single { it.key == "live" }.color)
        assertEquals(listOf("4 live", "1 demo", "1 instrumental", "1 cover", "1 session"), svc.artistCounts("x").map { it.label })
        assertEquals(MusicVersions.KEYS.size, t.types.size)
    }
}
