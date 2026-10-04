package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R373 (test 1, FR-R373-1, dev review 1) — the album page's new fields are only ever added, and optional. */
class MusicEditionsWireTest {
    @Test
    fun todays_album_payload_decodes_as_an_unmatched_page() {
        val today = """{"album":{"id":"kw","title":"Kite Weather"},"tracks":[{"id":"kw-1","title":"Kite Weather","versions":["live"]}],"more_from_artist":[],"favorite":false}"""
        val d = RaviloWireJson.decodeFromString(MusicAlbumDetail.serializer(), today)
        assertNull(d.officialIds); assertTrue(d.extraIds.isEmpty()); assertTrue(d.singles.isEmpty()); assertTrue(d.bsideTracks.isEmpty()); assertNull(d.singleFrom)
        assertFalse(d.tracks.single().extra); assertEquals(0, d.tracks.single().alsoOn)
    }

    @Test
    fun a_matched_album_decodes_and_tracks_keep_file_order() {
        val body = """{"album":{"id":"kw","title":"Kite Weather"},"tracks":[{"id":"kw-12","title":"Lantern Swing","extra":true},{"id":"kw-1","title":"Kite Weather","also_on":2}],""" +
            """"official_ids":["kw-1"],"extra_ids":["kw-12"],"edition_country":"JP","singles":[{"id":"s-nl","title":"Northern Line","type":"single","year":2005}],""" +
            """"bside_tracks":[{"id":"s-nl-2","title":"Night Bus","album":"Northern Line","album_id":"s-nl"}]}"""
        val d = RaviloWireJson.decodeFromString(MusicAlbumDetail.serializer(), body)
        assertEquals(listOf("kw-12", "kw-1"), d.tracks.map { it.id })
        assertEquals(listOf("kw-1"), d.officialIds); assertEquals(listOf("kw-12"), d.extraIds)
        assertEquals("JP", d.editionCountry); assertNull(d.editionTitle)
        assertTrue(d.tracks.first().extra); assertEquals(2, d.tracks.last().alsoOn)
        assertEquals("Northern Line", d.bsideTracks.single().album)
    }

    @Test
    fun unknown_fields_from_a_newer_server_are_skipped() {
        val d = RaviloWireJson.decodeFromString(MusicAlbumDetail.serializer(), """{"album":{"id":"kw","title":"K"},"official_ids":[],"some_later_field":{"x":1}}""")
        assertEquals(emptyList(), d.officialIds)
    }

    @Test
    fun the_copies_answer_round_trips() {
        val c = MusicTrackCopies(listOf(MusicTrackCopy(MusicTrackItem("s-nl-1", "Northern Line", albumId = "s-nl"), MusicAlbumCard("s-nl", "Northern Line", year = 2005, type = "single"))))
        val text = RaviloWireJson.encodeToString(MusicTrackCopies.serializer(), c)
        assertEquals(c, RaviloWireJson.decodeFromString(MusicTrackCopies.serializer(), text))
        val a = RaviloWireJson.decodeFromString(MusicArtistDetail.serializer(), """{"artist":{"id":"hl","name":"Harbour Lights"}}""")
        assertTrue(a.singlesUnder.isEmpty())
    }
}
