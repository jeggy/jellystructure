package dev.jellystructure.tv

import dev.jellystructure.model.ratingScore
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.RowOrder
import dev.jellystructure.shared.tv.RowSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Phase 225 — how a content row lines up: one resolver, shared by the server and the editor's preview. */
class RowOrderTest {
    private data class T(val id: String, val title: String, val added: Long, val year: Int?, val sortName: String? = null)
    private val lib = listOf(
        T("a", "The Bear", 50, 2022, "Bear, The"),
        T("b", "Alien", 40, 1979),
        T("c", "Zodiac", 60, 2007),
        T("d", "Arrival", 60, 2016),   // same `added` as Zodiac — the tie-break decides
        T("e", "Kalani", 10, null),
    )
    private fun order(sort: RowSort?, pinned: List<String> = emptyList(), limit: Int? = null, from: List<T> = lib) =
        RowOrder.resolve(from, sort, pinned, limit, id = { it.id }, added = { it.added }, year = { it.year }, title = { it.title }, sortName = { it.sortName }).map { it.id }

    @Test fun anAbsentSortIsTodaysOrderExactly() {   // acceptance 1 — newest first, ties by plain title
        val today = lib.sortedWith(compareByDescending<T> { it.added }.thenBy { it.title }).map { it.id }
        assertEquals(today, order(null))
        assertEquals(listOf("d", "c", "a", "b", "e"), order(null))
    }

    @Test fun titleUsesJellyfinsSortNameSoTheBearFilesUnderB() {
        assertEquals(listOf("b", "d", "a", "e", "c"), order(RowSort("title", descending = false)))
        assertEquals(listOf("c", "e", "a", "d", "b"), order(RowSort("title", descending = true)))
    }

    @Test fun yearBothWaysWithUnknownYearsLastWhenNewestFirst() {
        assertEquals(listOf("a", "d", "c", "b", "e"), order(RowSort("year", descending = true)))
        assertEquals(listOf("e", "b", "c", "d", "a"), order(RowSort("year", descending = false)))
    }

    @Test fun addedOldestFirst() = assertEquals(listOf("e", "b", "a", "d", "c"), order(RowSort("added", descending = false)))

    @Test fun handPicksAreAPrefixInTheirOwnOrder() =
        assertEquals(listOf("e", "b", "d", "c", "a"), order(RowSort("added", descending = true), pinned = listOf("e", "b")))

    @Test fun aStalePinIsSkippedNotAnError() =      // FR-225-5 — kept in config, absent from the served row
        assertEquals(listOf("b", "d", "c", "a", "e"), order(RowSort(), pinned = listOf("gone", "b")))

    @Test fun pinsResolveAgainstTheScopedSet() {     // FR-225-6 — a pin outside the collection is not a pin there
        val scoped = lib.filter { it.id != "e" }
        assertEquals(listOf("b", "d", "c", "a"), order(RowSort(), pinned = listOf("e", "b"), from = scoped))
    }

    @Test fun theLimitCapsTheRowAndDefaultsToThirty() {
        assertEquals(listOf("d", "c", "a"), order(null, limit = 3))
        assertEquals(30, RowOrder.effectiveLimit(null)); assertEquals(30, RowOrder.effectiveLimit(99)); assertEquals(12, RowOrder.effectiveLimit(12))
        assertEquals(30, order(null, from = (1..40).map { T("x$it", "T$it", it.toLong(), 2000) }).size)
    }

    private fun row(kind: RowKind = RowKind.CUSTOM, sort: RowSort? = null, pinned: List<String> = emptyList(), limit: Int? = null) =
        RowConfig(id = "r", kind = kind, title = "Row", sort = sort, pinned = pinned, limit = limit)

    @Test fun validation() {                          // FR-225-11 + FR-225-7
        assertNull(RowOrder.problem(row()))
        assertNull(RowOrder.problem(row(sort = RowSort("year", false), pinned = listOf("a", "unknown-id"), limit = 5)))
        assertNotNull(RowOrder.problem(row(sort = RowSort("popularity"))))   // DEB-2837: "rating" became a key
        assertNotNull(RowOrder.problem(row(limit = 2))); assertNotNull(RowOrder.problem(row(limit = 31)))
        assertNotNull(RowOrder.problem(row(pinned = listOf("a", "b", "c", "d"), limit = 3)))
        assertNotNull(RowOrder.problem(row(kind = RowKind.CONTINUE, limit = 10)))
        assertNotNull(RowOrder.problem(row(kind = RowKind.NEWLY_ADDED, sort = RowSort("title", false))))
        assertNull(RowOrder.problem(row(kind = RowKind.CONTINUE)))
    }

    @Test fun theRowListSaysItInWords() {             // FR-225-10 — never asc/desc; nothing for the default
        assertEquals("", RowOrder.summary(row()))
        assertEquals("title A → Z", RowOrder.summary(row(sort = RowSort("title", false))))
        assertEquals("newest release first", RowOrder.summary(row(sort = RowSort("year", true))))
        assertEquals("3 hand-picked, then newest first", RowOrder.summary(row(pinned = listOf("a", "b", "c"))))
        assertEquals("oldest first · shows 15", RowOrder.summary(row(sort = RowSort("added", false), limit = 15)))
    }

    // ─── DEB-2837 (FR-4): the rating key ────────────────────────────────────────

    private data class R(val id: String, val title: String, val score: Double?)
    private fun byRating(desc: Boolean, from: List<R>, pinned: List<String> = emptyList()) =
        RowOrder.resolve(from, RowSort("rating", desc), pinned, null, id = { it.id }, added = { 0L }, year = { null }, title = { it.title }, sortName = { null }, rating = { it.score }).map { it.id }
    private val rated = listOf(R("lo", "Beta", 7.4), R("none", "Alpha", null), R("hi", "Gamma", 8.1), R("hi2", "Delta", 8.1))

    @Test fun bestRatedFirstPutsTheUnratedLast() = assertEquals(listOf("hi2", "hi", "lo", "none"), byRating(true, rated))   // acceptance 4; ties by name

    @Test fun lowestRatedFirstStillPutsTheUnratedLast() = assertEquals(listOf("lo", "hi2", "hi", "none"), byRating(false, rated))

    @Test fun handPicksStillComeFirstUnderTheRatingKey() = assertEquals(listOf("none", "hi2", "hi", "lo"), byRating(true, rated, pinned = listOf("none")))

    @Test fun ratingIsAKeyWithItsWordsAndTheOtherKeysAreUnmoved() {
        assertEquals(listOf("added", "title", "year", "size", "rating"), RowOrder.KEYS)
        assertEquals("best-rated first", RowOrder.directionWords("rating", true)); assertEquals("lowest-rated first", RowOrder.directionWords("rating", false))
        assertEquals("best-rated first", RowOrder.summary(row(sort = RowSort("rating", true))))
        assertNull(RowOrder.problem(row(sort = RowSort("rating", true))))
    }

    @Test fun theRatingScoreIsNullBelowTheFloorSoAThinTitleSortsAsUnrated() {
        fun t(imdb: Pair<Double, Long>?, tmdb: Pair<Double, Int>?) = dev.jellystructure.model.MediaItem(
            id = "x", title = "x", year = null, kind = dev.jellystructure.model.MediaKind.MOVIE, path = "/x", tmdbId = null, originalLanguage = null,
            posterPath = null, overview = null, tracks = emptyList(), issueCount = 0, scannedAt = 0L,
            imdbRating = imdb?.let { dev.jellystructure.model.ImdbRating(it.first, it.second, 0L) }, tmdbVoteAverage = tmdb?.first, tmdbVoteCount = tmdb?.second,
        )
        val floor = dev.jellystructure.shared.tv.DEFAULT_MIN_VOTES
        assertEquals(8.1, t(8.1 to 5000, null).ratingScore(floor)); assertEquals(7.8, t(null, 7.8 to 300).ratingScore(floor))
        assertNull(t(8.1 to 150, 8.0 to 900).ratingScore(floor)); assertNull(t(null, 7.8 to 100).ratingScore(floor)); assertNull(t(null, null).ratingScore(floor))
    }
}
