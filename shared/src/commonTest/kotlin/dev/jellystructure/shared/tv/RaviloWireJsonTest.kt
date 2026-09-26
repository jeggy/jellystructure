package dev.jellystructure.shared.tv

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R318 (FR-R318-3) — a value a newer server adds never fails the payload an app decodes. */
class RaviloWireJsonTest {
    private fun card(id: String) = """{"id":"$id","kind":"MOVIE","title":"$id","year":2020,"genre":null,"rating":null,"poster_url":null,"backdrop_url":null}"""

    @Test
    fun anUnknownRowKindBecomesAPlainRowAndTheRestOfTheFeedIsIntact() {
        val feed = """{"heroes":[],"channels":[{"id":"c","name":"C","logo_url":null,"style":"HOLOGRAM","brand_color":null}],
            "rows":[{"id":"a","title":"A","kind":"SOMETHING_NEW","items":[${card("m1")}]},
                    {"id":"b","title":"B","kind":"CONTINUE","items":[${card("m2")}]}],
            "tile_shape":"HEXAGON"}"""
        val decoded = RaviloWireJson.decodeFromString(HomeFeed.serializer(), feed)
        assertEquals(listOf(RowKind.CUSTOM, RowKind.CONTINUE), decoded.rows.map { it.kind })
        assertEquals(listOf("m1", "m2"), decoded.rows.flatMap { r -> r.items.map { it.id } })
        assertEquals(ChannelStyle.TEXT, decoded.channels.single().style)
        assertEquals(TileShape.POSTER, decoded.tileShape)
    }

    @Test
    fun anUnknownSkinFallsBackAndEveryOtherSettingIsKept() {
        val cfg = """{"default_skin":"SOMETHING_NEW","ui_language":"fo","skip_secs":8,
            "rows":[{"id":"r","kind":"RECOMMENDED","title":"For you"},{"id":"x","kind":"FROM_THE_FUTURE"}]}"""
        val decoded = RaviloWireJson.decodeFromString(RaviloConfig.serializer(), cfg)
        assertEquals(Skin.AURORA, decoded.defaultSkin)
        assertEquals("fo", decoded.uiLanguage)
        assertEquals(8, decoded.skipSecs)
        assertEquals(listOf(RowKind.RECOMMENDED, RowKind.CUSTOM), decoded.rows.map { it.kind }, "RECOMMENDED is known; an unknown kind is a plain row")
    }

    @Serializable private enum class Flavour { SWEET, SOUR }
    @Serializable private data class Basket(@Serializable(with = FlavourList::class) val flavours: List<Flavour> = emptyList())
    private object FlavourList : kotlinx.serialization.KSerializer<List<Flavour>> by LenientListSerializer(Flavour.serializer())

    @Test
    fun anUnknownElementIsDroppedAndTheRestOfTheListKept() {
        val decoded = RaviloWireJson.decodeFromString(Basket.serializer(), """{"flavours":["SWEET","UMAMI","SOUR"]}""")
        assertEquals(listOf(Flavour.SWEET, Flavour.SOUR), decoded.flavours)
    }

    @Test
    fun aRecommendedRowOffersSeeAllOnlyWhenItsListHoldsMoreThanItShows() {
        val items = (1..20).map { MediaCard(id = "m$it", title = "m$it", year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null) }
        val rec = Row(id = "r", title = "For you", kind = RowKind.CUSTOM, items = items, seedTotalCount = 50, recommendations = true)
        assertTrue(rec.offersSeeAll())
        assertFalse(rec.copy(seedTotalCount = 20).offersSeeAll(), "the row already shows the whole list")
        assertFalse(rec.copy(recommendations = false).offersSeeAll(), "an app before R318 saw a CUSTOM row with no seed: no See all")
    }
}

/** R318 hotfix (2026-09-26) — a field given a default for lenient READING must still be WRITTEN when it
 *  equals that default: the server encodes without `encodeDefaults`, and every app installed before R318
 *  has these fields as required. v1.41 omitted them and Home failed to load on those apps. */
class RaviloWireEncodeTest {
    private val serverLike = kotlinx.serialization.json.Json { }   // encodeDefaults = false, like the server

    @Test
    fun fieldsDefaultedForReadingAreAlwaysWritten() {
        val card = MediaCard(id = "m", kind = MediaKind.MOVIE, title = "m", year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null)
        val feed = HomeFeed(
            heroes = emptyList(),
            channels = listOf(Channel(id = "c", name = "C", logoUrl = null, style = ChannelStyle.TEXT, brandColor = null)),
            rows = listOf(Row(id = "r", title = "R", kind = RowKind.CUSTOM, items = listOf(card))),
        )
        val wire = serverLike.encodeToString(HomeFeed.serializer(), feed)
        assertTrue(""""kind":"CUSTOM"""" in wire, wire)
        assertTrue(""""kind":"MOVIE"""" in wire, wire)
        assertTrue(""""style":"TEXT"""" in wire, wire)
        val cfg = serverLike.encodeToString(RaviloConfig.serializer(), RaviloConfig(rows = listOf(RowConfig(id = "x", kind = RowKind.CUSTOM))))
        assertTrue(""""kind":"CUSTOM"""" in cfg, cfg)
    }
}
