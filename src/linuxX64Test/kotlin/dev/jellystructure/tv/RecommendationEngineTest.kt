package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinPlayItem
import dev.jellystructure.auth.JellyfinUserData
import dev.jellystructure.media.GenreCatalog
import dev.jellystructure.model.ImdbRating
import dev.jellystructure.model.Keyword
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Person
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 269 (FR-269-11) — the engine: determinism, a full list with and without history, every
 *  eligibility rule, the decay and the weak negative, the quality floor, and the diversity rules. */
class RecommendationEngineTest {
    private val now = 1_790_000_000L   // 2026-09-21

    @BeforeTest fun setUp() { GenreCatalog.replaceForTest(emptyMap()) }
    @AfterTest fun tearDown() { GenreCatalog.replaceForTest(emptyMap()) }

    /** 80 invented films in four "families" of shared genre/keywords/cast, plus ratings. */
    private fun library(): List<MediaItem> = (1..80).map { n ->
        val family = n % 4
        MediaItem(
            id = "m$n", title = "Film $n", year = 1990 + n % 30, kind = MediaKind.MOVIE, path = "/lib/m$n.mkv",
            tmdbId = 1000 + n, originalLanguage = if (family == 3) "da" else "en", posterPath = null, overview = null,
            tracks = emptyList(), issueCount = 0, scannedAt = 0L, jellyfinId = "jf$n",
            genres = listOf("G$family", "Common"), genreIds = listOf(null, null),
            keywords = listOf(Keyword(500 + family, "theme $family"), Keyword(900 + n, "own $n")),
            cast = listOf(Person(tmdbId = 7000 + family, name = "Actor $family", order = 0)),
            imdbRating = ImdbRating(aggregateRating = 6.0 + (n % 30) / 10.0, voteCount = 20_000, syncedAt = 0),
            createdAt = now - n * 86_400L * 3,
            tmdbRecommendations = if (n == 1) listOf(1044, 1080) else emptyList(),
        )
    }

    private fun played(id: String, daysAgo: Int = 1, playCount: Int = 1) = JellyfinPlayItem(
        id = id, type = "Movie", name = id,
        userData = JellyfinUserData(played = true, lastPlayedDate = iso(now - daysAgo * 86_400L), playCount = playCount),
    )

    private fun resume(id: String, pct: Double, daysAgo: Int) = JellyfinPlayItem(
        id = id, type = "Movie", name = id,
        userData = JellyfinUserData(playedPercentage = pct, lastPlayedDate = iso(now - daysAgo * 86_400L)),
    )

    private fun iso(sec: Long): String {
        var days = sec / 86_400; val rem = sec % 86_400
        // civil-from-days (Hinnant), enough for a test fixture
        days += 719_468
        val era = days / 146_097; val doe = days - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        fun p(v: Long) = v.toString().padStart(2, '0')
        return "$y-${p(m)}-${p(d)}T${p(rem / 3600)}:${p(rem % 3600 / 60)}:${p(rem % 60)}.0000000Z"
    }

    private fun byJf(lib: List<MediaItem>) = lib.associateBy { it.jellyfinId!! }

    private fun build(lib: List<MediaItem>, signals: RecommendationEngine.Signals, candidates: List<MediaItem> = lib) =
        RecommendationEngine.build(signals, candidates, lib, RecommendationEngine.starter(candidates, emptyMap(), now), now)

    @Test
    fun `decay halves every 120 days and a stopped-early film left 30 days is a weak negative`() {
        assertTrue(abs(RecommendationEngine.decay(120.0) - 0.5) < 1e-9)
        assertTrue(abs(RecommendationEngine.decay(240.0) - 0.25) < 1e-9)
        val lib = library()
        val s = RecommendationEngine.signals(emptyList(), listOf(resume("jf5", 4.0, 40), resume("jf6", 4.0, 3)), emptySet(), byJf(lib), now)
        assertTrue(s.weights.getValue("jf5") < 0, "under 10 % and untouched 40 days reads as 'not for me'")
        assertTrue(s.weights.getValue("jf6") > 0, "started three days ago is still in progress")
        assertTrue("jf5" in s.excluded && "jf6" in s.excluded)
        val rewatch = RecommendationEngine.signals(listOf(played("jf7", playCount = 3)), emptyList(), setOf("jf7"), byJf(lib), now)
        val once = RecommendationEngine.signals(listOf(played("jf7")), emptyList(), emptySet(), byJf(lib), now)
        assertTrue(rewatch.weights.getValue("jf7") > once.weights.getValue("jf7"), "a rewatch and a favourite count extra")
    }

    @Test
    fun `a viewer with no history gets a full list from the starter list`() {
        val lib = library()
        val list = build(lib, RecommendationEngine.Signals(emptyMap(), emptySet()))
        assertEquals(RecommendationEngine.LIST_SIZE, list.size)
        assertTrue(list.none { it.reason == RecommendationEngine.REASON_WATCHED })
    }

    @Test
    fun `three watched titles give a full list that leads with what is like them and never repeats them`() {
        val lib = library()
        val s = RecommendationEngine.signals(listOf(played("jf1"), played("jf5"), played("jf9")), emptyList(), emptySet(), byJf(lib), now)
        val list = build(lib, s)
        assertEquals(RecommendationEngine.LIST_SIZE, list.size, "topped up to 50")
        assertTrue(list.none { it.jellyfinId in setOf("jf1", "jf5", "jf9") }, "never what they watched")
        assertEquals(list.map { it.jellyfinId }.distinct(), list.map { it.jellyfinId }, "no title twice")
        // Family 1 (1, 5, 9 all share it) must dominate the top of the list, with reasons pointing at a watched title.
        val top = list.take(8)
        assertTrue(top.count { (it.jellyfinId.drop(2).toInt()) % 4 == 1 } >= 4, "similar titles lead: ${top.map { it.jellyfinId }}")
        assertTrue(top.all { it.reason == RecommendationEngine.REASON_WATCHED && it.reasonItem in setOf("jf1", "jf5", "jf9") })
        // TMDB's own recommendation from a watched title is on the list.
        assertTrue(list.any { it.jellyfinId == "jf44" && it.reasonItem == "jf1" })
    }

    @Test
    fun `the same inputs give the same list whatever order they arrive in`() {
        val lib = library()
        val s = RecommendationEngine.signals(listOf(played("jf2"), played("jf3", daysAgo = 200)), emptyList(), emptySet(), byJf(lib), now)
        val a = build(lib, s)
        val shuffled = lib.reversed()
        val b = RecommendationEngine.build(s, shuffled, shuffled, RecommendationEngine.starter(shuffled, emptyMap(), now), now)
        assertEquals(a, b)
    }

    @Test
    fun `eligibility - finished or in progress or in My List or a weak negative or under the floor or hidden is never listed`() {
        val lib = library().map { if (it.jellyfinId == "jf13") it.copy(imdbRating = ImdbRating(4.7, 5_000, 0)) else it }
        val s = RecommendationEngine.signals(
            played = listOf(played("jf1")),
            resume = listOf(resume("jf2", 50.0, 2), resume("jf3", 3.0, 60)),
            favorites = setOf("jf4"),
            byJellyfinId = byJf(lib), nowSec = now,
        )
        val hidden = setOf("jf17", "jf21")
        val list = build(lib, s, candidates = lib.filter { it.jellyfinId !in hidden })
        val ids = list.map { it.jellyfinId }.toSet()
        for (id in listOf("jf1", "jf2", "jf3", "jf4")) assertFalse(id in ids, "$id")
        assertFalse("jf13" in ids, "4.7 with 5,000 votes is under the floor")
        assertTrue(ids.none { it in hidden }, "only what the viewer may see")
        assertTrue(RecommendationEngine.starter(lib, emptyMap(), now).none { it.jellyfinId == "jf13" }, "the floor holds in the starter list too")
    }

    @Test
    fun `diversity - two per collection and no genre over half and never three in a row`() {
        val lib = library().map { m ->
            val n = m.jellyfinId!!.drop(2).toInt()
            if (n <= 6) m.copy(collectionId = 42) else m
        }
        val byJf = byJf(lib)
        val ordered = lib.sortedBy { it.jellyfinId!!.drop(2).toInt() }.map { RecommendationEngine.Pick(it.jellyfinId!!, 1.0, RecommendationEngine.REASON_NEW) }
        val out = RecommendationEngine.diversify(ordered, byJf, 20)
        assertEquals(20, out.size)
        assertTrue(out.count { byJf.getValue(it.jellyfinId).collectionId == 42 } <= 2)
        val firstGenre = out.map { byJf.getValue(it.jellyfinId).genres.first() }
        assertTrue(firstGenre.groupingBy { it }.eachCount().values.all { it <= 10 })
        assertTrue(firstGenre.windowed(3).none { it.distinct().size == 1 })
    }

    @Test
    fun `the household starter list leads with what the scope watches`() {
        val lib = library()
        val starter = RecommendationEngine.starter(lib, mapOf("jf30" to 3, "jf31" to 1), now)
        assertEquals(listOf("jf30", "jf31"), starter.take(2).map { it.jellyfinId })
        assertEquals(RecommendationEngine.REASON_HOUSEHOLD, starter.first().reason)
        assertEquals(RecommendationEngine.REASON_RATED, starter[2].reason)
    }

    @Test
    fun `the scope key is stable and order-free`() {
        val a = dev.jellystructure.auth.DeviceData("d", "t", "u", "u", "j", false, allowedLibraries = setOf("b", "a"), allowedTags = setOf("x"))
        val b = a.copy(allowedLibraries = linkedSetOf("a", "b"))
        assertEquals(RecommendationEngine.scopeKey(a), RecommendationEngine.scopeKey(b))
        assertFalse(RecommendationEngine.scopeKey(a) == RecommendationEngine.scopeKey(a.copy(allowedLibraries = null)))
    }
}
