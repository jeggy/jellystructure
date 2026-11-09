package dev.jellystructure.tv

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.SurpriseAnswer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3984 (FR-12, FR-13) — Surprise me's pick pool: a movie never played and a series with no episode played, the run's
 * `exclude` ids left out, nothing (a `204`) when empty; and the capability field the apps gate the pill on.
 */
class SurprisePickerTest {
    private fun movie(id: String) = MediaItem(
        id = "m-$id", title = id, year = 2020, kind = MediaKind.MOVIE, path = "/lib/$id.mkv", tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, jellyfinId = id,
    )

    private fun series(id: String, vararg episodeIds: String) = MediaItem(
        id = "s-$id", title = id, year = 2020, kind = MediaKind.TV_SHOW, path = "/lib/$id", tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L, jellyfinId = id,
        episodes = episodeIds.mapIndexed { i, e ->
            Episode(filename = "$e.mkv", path = "/lib/$id/$e.mkv", seasonNumber = 1, episodeNumber = i + 1, tracks = emptyList(), issueCount = 0, jellyfinId = e)
        },
    )

    private val played = CardPlayState(played = true)
    private fun ids(pool: List<SurpriseAnswer>) = pool.map { it.id }.toSet()

    @Test
    fun `a played movie or one with a resume position is not offered`() {
        val items = listOf(movie("fresh"), movie("seen"), movie("half"))
        val ps = mapOf("seen" to played, "half" to CardPlayState(resumeMs = 600_000))
        assertEquals(setOf("fresh"), ids(SurprisePicker.pool(items, ps, emptySet())))
    }

    @Test
    fun `a series with a played episode is not offered but an untouched one is`() {
        val items = listOf(series("untouched", "u1", "u2"), series("started", "a1", "a2"), series("resumed", "r1"))
        val ps = mapOf("a2" to played, "r1" to CardPlayState(resumeMs = 1))
        val pool = SurprisePicker.pool(items, ps, emptySet())
        assertEquals(setOf("untouched"), ids(pool))
        assertEquals(dev.jellystructure.shared.tv.MediaKind.SERIES, pool.single().kind)
    }

    @Test
    fun `a series marked played on its own row is not offered`() {
        assertEquals(emptyList(), SurprisePicker.pool(listOf(series("all", "e1")), mapOf("all" to played), emptySet()))
    }

    @Test
    fun `exclude leaves the shown ids out so the pick is only from what is left`() {
        val items = listOf(movie("a"), movie("b"), movie("c"))
        val exclude = setOf("a", "c")
        repeat(20) { assertEquals("b", SurprisePicker.pick(items, emptyMap(), exclude, Random(it))?.id) }
    }

    @Test
    fun `nothing left is null which the route answers as 204`() {
        assertNull(SurprisePicker.pick(listOf(movie("a")), emptyMap(), setOf("a")))
        assertNull(SurprisePicker.pick(listOf(movie("a")), mapOf("a" to played), emptySet()))
        assertNull(SurprisePicker.pick(emptyList(), emptyMap(), emptySet()))
    }

    @Test
    fun `an item with no Jellyfin id is offered and excluded by its own id`() {
        val bare = movie("x").copy(jellyfinId = null)
        assertEquals("m-x", SurprisePicker.pick(listOf(bare), emptyMap(), emptySet())?.id)
        assertNull(SurprisePicker.pick(listOf(bare), emptyMap(), setOf("m-x")))
    }

    @Test
    fun `every eligible title can come up not just the first`() {
        val items = listOf(movie("a"), movie("b"), movie("c"), series("d", "d1"))
        val seen = (0 until 200).mapNotNull { SurprisePicker.pick(items, emptyMap(), emptySet(), Random(it))?.id }.toSet()
        assertEquals(setOf("a", "b", "c", "d"), seen)
    }

    @Test
    fun `the answer is id and kind on the wire`() {
        val json = RaviloWireJson.encodeToString(SurpriseAnswer.serializer(), SurpriseAnswer("abc", dev.jellystructure.shared.tv.MediaKind.SERIES))
        assertEquals("""{"id":"abc","kind":"SERIES"}""", json)
    }

    @Test
    fun `the config a client reads carries surprise_me true and an absent field decodes to false`() {
        assertEquals(false, RaviloConfig().surpriseMe)
        assertEquals(false, RaviloWireJson.decodeFromString<RaviloConfig>("{}").surpriseMe)
        val json = RaviloWireJson.encodeToString(RaviloConfig.serializer(), RaviloConfig().forClients())
        assertTrue(json.contains("\"surprise_me\":true"), json)
    }
}
