package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.tv.ConditionEvaluator
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 271 (FR-271-9) — the label rule, identity matching, the provenance merge and the backfill. */
class GenreCatalogTest {
    private val labels = mapOf(
        35 to mapOf("en" to "Comedy", "da" to "Komedie", "it" to "Commedia"),
        18 to mapOf("en" to "Drama", "da" to "Drama"),
        10751 to mapOf("en" to "Family", "da" to "Familie"),
        10762 to mapOf("da" to "Børn"),   // no English label: the rule's last step
    )

    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        GenreCatalog.replaceForTest(labels, mapOf(35 to setOf("movie", "tv"), 18 to setOf("movie", "tv"), 10751 to setOf("movie", "tv"), 10762 to setOf("tv")))
        dbPath = "/tmp/jellystructure-test-genres-${getpid()}.db"
    }

    @AfterTest
    fun tearDown() {
        GenreCatalog.replaceForTest(emptyMap())
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun title(
        id: String, genres: List<String>, resolved: String? = null, original: String? = null,
        tmdbGenres: List<String> = genres, tmdbGenreIds: List<Int?> = emptyList(), kind: MediaKind = MediaKind.MOVIE,
    ) = MediaItem(
        id = id, title = id, year = 2020, kind = kind, path = "/lib/$id.mkv", tmdbId = null,
        originalLanguage = original, resolvedLanguage = resolved, posterPath = null, overview = null,
        tracks = emptyList(), issueCount = 0, scannedAt = 0L, genres = genres, tmdbGenres = tmdbGenres,
        tmdbGenreIds = tmdbGenreIds,
    )

    @Test
    fun `the label rule falls from app language to the title's languages to English to anything`() {
        assertEquals("Komedie", GenreCatalog.label(35, "da"), "1. the viewer's app language")
        assertEquals("Commedia", GenreCatalog.label(35, "fo", listOf("it")), "2. no Faroese label ⇒ the title's own language")
        assertEquals("Comedy", GenreCatalog.label(35, "fo", listOf("sv")), "3. neither ⇒ English")
        assertEquals("Børn", GenreCatalog.label(10762, "fo"), "4. no English either ⇒ whatever label there is")
        assertNull(GenreCatalog.label(99999, "da"), "an id the table has never heard of")
        assertEquals("Komedie", GenreCatalog.label(35, "da-DK"), "a regional tag reads as its language")
    }

    @Test
    fun `a Faroese viewer sees the title's own language on its detail page and English on the wall`() {
        val danish = title("dk", listOf("Komedie"), resolved = "da")
        val english = title("en", listOf("Comedy"), resolved = "en")
        assertEquals(listOf("Komedie"), GenreCatalog.displayNames(danish, "fo", withTitle = true))
        assertEquals(listOf("Comedy"), GenreCatalog.displayNames(english, "fo", withTitle = true))
        assertEquals(listOf("Comedy"), GenreCatalog.displayNames(danish, "fo", withTitle = false), "across titles: no title step")
        assertEquals(listOf("Komedie"), GenreCatalog.displayNames(english, "da", withTitle = true), "a Danish app says Komedie whatever the title's language")
    }

    @Test
    fun `a name resolves to its id in any language whatever its spacing or case`() {
        assertEquals(35, GenreCatalog.idFor("  komedie "))
        assertEquals(35, GenreCatalog.idFor("Commedia"))
        assertNull(GenreCatalog.idFor("Anime"), "a hand-added genre has no id")
        assertEquals(setOf("#35", "anime"), GenreCatalog.keys(title("x", listOf("Komedie", "Anime"))))
        assertEquals("#35", GenreCatalog.keyOf("#35"), "the Metadata page's own link form")
    }

    @Test
    fun `a filter saved as Komedie matches an English-labelled comedy`() {
        val comedy = title("c", listOf("Comedy"), resolved = "en")
        val drama = title("d", listOf("Drama"))
        val saved = ConditionGroup(children = listOf(Condition(facet = "genre", op = "is_any_of", values = listOf("Komedie"))))
        assertTrue(ConditionEvaluator.matches(comedy, saved, emptySet()))
        assertFalse(ConditionEvaluator.matches(drama, saved, emptySet()))
        val excluded = ConditionGroup(children = listOf(Condition(facet = "genre", op = "is_none_of", values = listOf("Komedie"))))
        assertFalse(ConditionEvaluator.matches(comedy, excluded, emptySet()))
    }

    @Test
    fun `a hand-added genre survives a re-sync and a removed one stays removed even in another language`() {
        // Fetched in Danish: TMDB said Komedie + Familie; the admin removed Familie and added Anime.
        val prior = title("p", genres = listOf("Komedie", "Anime"), tmdbGenres = listOf("Komedie", "Familie"), tmdbGenreIds = listOf(35, 10751))
        // Re-synced in English, and TMDB now also says Drama.
        val merged = GenreCatalog.mergeUserGenres(prior, listOf(35 to "Comedy", 10751 to "Family", 18 to "Drama"))
        assertEquals(listOf("Comedy", "Drama", "Anime"), merged)
    }

    @Test
    fun `the title's own TMDB pairing gives an id the label table does not know yet`() {
        val kids = title("k", listOf("Kids"), tmdbGenres = listOf("Kids"), tmdbGenreIds = listOf(10762), kind = MediaKind.TV_SHOW)
        assertEquals(listOf<Int?>(10762), GenreCatalog.normalize(kids).genreIds)
        assertEquals(listOf("Børn"), GenreCatalog.displayNames(kids, "fo", withTitle = false))
    }

    @Test
    fun `normalize maps a pre-271 row once and is idempotent`() {
        val old = title("o", listOf("Komedie", "Anime"), tmdbGenres = listOf("Komedie"))
        val n = GenreCatalog.normalize(old)
        assertEquals(listOf(35, null), n.genreIds)
        assertEquals(listOf<Int?>(35), n.tmdbGenreIds)
        assertEquals(n, GenreCatalog.normalize(n))
        assertFalse(GenreCatalog.needsNormalize(n))
    }

    @Test
    fun `relabel turns the cached English label into the viewer's`() {
        assertEquals("Komedie", GenreCatalog.relabel("Comedy", "da"))
        assertEquals("Anime", GenreCatalog.relabel("Anime", "da"), "a hand-added genre reads as typed")
        assertEquals("Comedy", GenreCatalog.relabel("Comedy", "fo"), "no Faroese label ⇒ English stays")
    }

    @Test
    fun `the backfill is one batched write and one feed bump`() = runBlocking {
        val db = createDatabase(dbPath)
        val store = MediaStore(db, JsTagStore("/tmp/jellystructure-test-genres-${getpid()}-tags.json"), ConfigStore("/tmp/jellystructure-test-genres-${getpid()}.toml"))
        // Written before the lists were fetched: nothing maps.
        GenreCatalog.replaceForTest(emptyMap())
        store.addOrUpdate(title("a", listOf("Komedie"), resolved = "da"))
        store.addOrUpdate(title("b", listOf("Comedy", "Anime")))
        assertEquals(listOf<Int?>(null), store.get("a")!!.genreIds)

        GenreCatalog.replaceForTest(labels)
        val feedBefore = store.feedVersion
        assertEquals(2, store.normalizeGenres(labelsChanged = true))
        assertEquals(feedBefore + 1, store.feedVersion, "one bump for the whole batch")
        assertEquals(listOf<Int?>(35), store.get("a")!!.genreIds)
        assertEquals(listOf(35, null), store.get("b")!!.genreIds)

        assertEquals(0, store.normalizeGenres(), "nothing left to map")
        assertEquals(feedBefore + 1, store.feedVersion, "and nothing to bump")

        // A scan's copy carries stale ids; the write re-derives them before comparing, so no bump.
        val feed = store.feedVersion
        store.addOrUpdate(store.get("a")!!.copy(genreIds = emptyList()))
        assertEquals(feed, store.feedVersion)

        // The Library's genre filter finds both comedies by either name.
        assertEquals(setOf("a", "b"), store.list(genres = listOf("Komedie")).items.map { it.id }.toSet())
    }
}
