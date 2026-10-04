package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.MusicAlbumCard
import dev.jellystructure.shared.tv.MusicAlbumDetail
import dev.jellystructure.shared.tv.MusicTrackItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** R373 (test 4, FR-R373-2, dev review 3) — what the album page plays, from the wire's ids. Stand-ins only. */
internal object KiteWeather {
    val TITLES = listOf("Kite Weather", "Northern Line", "Fog Bank", "Salt on the Window", "Low Tide", "Tidewater",
        "Harbour Song", "Grey Gulls", "Paper Boats", "Slow Ferry", "Last Light")
    fun track(id: String, title: String, pos: Int, album: String = "Kite Weather", albumId: String = "kw", extra: Boolean = false) =
        MusicTrackItem(id, title, albumId = albumId, album = album, position = pos, durationMs = 240_000L + pos * 1000, extra = extra)
    val official = TITLES.mapIndexed { i, t -> track("kw-${i + 1}", t, i + 1) }
    val lantern = track("kw-12", "Lantern Swing", 12, extra = true)
    val singles = listOf("Northern Line" to 2005, "Fog Bank" to 2006, "Salt on the Window" to 2006, "Low Tide" to 2007, "Tidewater" to 2007)
        .mapIndexed { i, (t, y) -> MusicAlbumCard("s-$i", t, year = y, type = "single") }
    val bsides = singles.flatMapIndexed { si, s -> (1..(if (si < 3) 3 else 2)).map { b -> track("${s.id}-b$b", "${s.title} B-side $b", b + 1, s.title, s.id) } }
    fun detail(officialIds: List<String>? = official.map { it.id }) = MusicAlbumDetail(
        album = MusicAlbumCard("kw", "Kite Weather", year = 2006), tracks = official + lantern,
        officialIds = officialIds, extraIds = if (officialIds != null) listOf("kw-12") else emptyList(), editionCountry = "JP",
        singles = singles, bsideTracks = bsides,
    )
}

class AlbumPlayOrderTest {
    private val d = KiteWeather.detail()

    @Test fun play_album_is_the_official_order() {
        assertEquals((1..11).map { "kw-$it" }, albumQueue(d, AlbumPick.ALBUM).map { it.id })
    }

    @Test fun play_album_plus_extras_is_official_then_extras_then_b_sides() {
        val q = albumQueue(d, AlbumPick.EXTRAS)
        assertEquals(25, q.size)
        assertEquals((1..11).map { "kw-$it" } + "kw-12" + KiteWeather.bsides.map { it.id }, q.map { it.id })
    }

    @Test fun a_tap_inside_the_pick_plays_from_that_row() {
        val (q, i) = queueForTap(d, AlbumPick.ALBUM, "kw-5")
        assertEquals(11, q.size); assertEquals(4, i)
    }

    @Test fun a_tap_on_an_extra_or_b_side_under_the_plain_pick_plays_the_extended_order_and_keeps_the_pick() {
        val (q, i) = queueForTap(d, AlbumPick.ALBUM, "kw-12")
        assertEquals(25, q.size); assertEquals(11, i)
        val (q2, i2) = queueForTap(d, AlbumPick.ALBUM, "s-4-b2")
        assertEquals(25, q2.size); assertEquals("s-4-b2", q2[i2].id)
    }

    @Test fun an_unmatched_album_plays_tracks_in_file_order_whatever_was_stored() {
        val plain = KiteWeather.detail(officialIds = null)
        assertEquals(plain.tracks.map { it.id }, albumQueue(plain, AlbumPick.EXTRAS).map { it.id })
        assertEquals(AlbumPick.ALBUM, effectivePick(plain, AlbumPick.EXTRAS))
    }

    @Test fun the_header_length_sums_the_official_ids() {
        assertEquals(KiteWeather.official.sumOf { it.durationMs!! }, headerLengthMs(d))
    }

    @Test fun an_id_missing_from_the_payload_is_skipped() {
        val short = d.copy(officialIds = d.officialIds!! + "kw-gone")
        assertEquals(11, albumQueue(short, AlbumPick.ALBUM).size)
    }
}

class EditionLabelTest {
    @Test fun musicbrainz_title_or_disambiguation_as_it_is() {
        assertEquals("Extras · 20th Anniversary", editionLabel("20th Anniversary", "GB", "en"))
        assertEquals("Ekstra · super deluxe", editionLabel("super deluxe", null, "da"))
    }

    @Test fun else_the_country_in_the_viewers_language() {
        assertEquals("Extras · Japan", editionLabel(null, "JP", "en"))
        assertEquals("Extras · Germany", editionLabel(null, "DE", "en"))
        assertEquals("Ekstra · Tyskland", editionLabel(null, "DE", "da"))
        assertEquals("Eyka · Týskland", editionLabel(null, "DE", "fo"))
    }

    @Test fun an_unknown_code_or_nothing_is_extras_alone() {
        assertEquals("Extras", editionLabel(null, "ZZ", "en"))
        assertEquals("Extras", editionLabel(null, null, "en"))
        assertEquals("Eyka", editionLabel("  ", null, "fo"))
    }
}
