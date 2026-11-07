package dev.jellystructure.tv

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.ImdbRating
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.model.franchiseDisplayName
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.QueryJoin
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.firstValueProblem
import dev.jellystructure.shared.tv.isLive
import dev.jellystructure.shared.tv.valueProblem
import dev.jellystructure.shared.tv.maxBlockDepth
import dev.jellystructure.shared.tv.migrateFlatQuery
import dev.jellystructure.shared.tv.pruned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 140 — the repo's first native (linuxX64Test) unit tests, covering the recursive
 * [ConditionEvaluator] and the `:shared` query-tree helpers (`QueryTree.kt`). Run via
 * `./gradlew linuxX64Test`.
 */
class ConditionEvaluatorTest {

    private fun item(
        id: String = "m1",
        kind: MediaKind = MediaKind.MOVIE,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        studio: String? = null,
        network: String? = null,
        tracks: List<Track> = emptyList(),
    ): MediaItem = MediaItem(
        id = id, title = id, year = null, kind = kind, path = "/x", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null,
        genres = genres, tags = tags, studio = studio, network = network,
        tracks = tracks, issueCount = 0, scannedAt = 0L,
    )

    private fun cond(facet: String, op: String = "is_any_of", values: List<String> = emptyList(), rows: List<RowConfig> = emptyList()) =
        Condition(facet, op, values, rows)

    private fun eval(it: MediaItem, root: ConditionGroup) = ConditionEvaluator.matches(it, root, emptySet())

    // ─── Join mixing ────────────────────────────────────────────────────────────

    @Test
    fun `AND of OR blocks matches only when every block has a hit`() {
        // (Tag nordic-noir OR Tag dansk-tv) AND (Network Kringvarp) — the spec's flagship query, minus Genre.
        val root = ConditionGroup(QueryJoin.AND, children = listOf(
            ConditionGroup(QueryJoin.OR, children = listOf(cond("tag", values = listOf("nordic-noir")), cond("tag", values = listOf("dansk-tv")))),
            ConditionGroup(QueryJoin.OR, children = listOf(cond("network", values = listOf("kringvarp")))),
        ))
        assertTrue(eval(item(tags = listOf("dansk-tv"), network = "Kringvarp"), root), "OR block hit + AND block hit -> match")
        assertFalse(eval(item(tags = listOf("dansk-tv"), network = "HBO"), root), "OR block hit but AND'd network block misses -> no match")
        assertFalse(eval(item(tags = listOf("staff-pick"), network = "Kringvarp"), root), "AND'd network hits but OR block misses -> no match")
    }

    @Test
    fun `OR of two conditions matches on either`() {
        val root = ConditionGroup(QueryJoin.OR, children = listOf(cond("genre", values = listOf("horror")), cond("genre", values = listOf("comedy"))))
        assertTrue(eval(item(genres = listOf("comedy")), root))
        assertTrue(eval(item(genres = listOf("horror")), root))
        assertFalse(eval(item(genres = listOf("drama")), root))
    }

    // ─── NOT ────────────────────────────────────────────────────────────────────

    @Test
    fun `NOT inverts the whole block`() {
        val root = ConditionGroup(QueryJoin.AND, not = true, children = listOf(cond("genre", values = listOf("horror"))))
        assertFalse(eval(item(genres = listOf("horror")), root), "matches horror -> NOT excludes it")
        assertTrue(eval(item(genres = listOf("comedy")), root), "doesn't match horror -> NOT lets it through")
    }

    // ─── Neutral-empty (incl. NOT) ──────────────────────────────────────────────

    @Test
    fun `empty group is neutral regardless of NOT`() {
        val emptyRoot = ConditionGroup(QueryJoin.AND, children = emptyList())
        assertTrue(eval(item(), emptyRoot), "no children at all -> matches everything")

        val emptyNotRoot = ConditionGroup(QueryJoin.AND, not = true, children = emptyList())
        assertTrue(eval(item(), emptyNotRoot), "empty + NOT must still be neutral (diverges from the design's literal matchNode)")

        val onlyDeadCondition = ConditionGroup(QueryJoin.AND, not = true, children = listOf(cond("genre", values = emptyList())))
        assertTrue(eval(item(genres = listOf("horror")), onlyDeadCondition), "a value-less condition is filtered out, leaving the group empty -> still neutral")
    }

    @Test
    fun `a fresh empty block does not zero an otherwise-live sibling block`() {
        val root = ConditionGroup(QueryJoin.AND, children = listOf(
            ConditionGroup(QueryJoin.OR, children = listOf(cond("genre", values = listOf("horror")))),
            ConditionGroup(QueryJoin.OR, children = emptyList()), // a fresh "+ Add block" with nothing picked yet
        ))
        assertTrue(eval(item(genres = listOf("horror")), root))
    }

    @Test
    fun `empty-values condition inside an AND block is skipped not evaluated false`() {
        // Old flat evaluator quirk: an empty is_any_of used to evaluate to false and veto the AND block.
        val root = ConditionGroup(QueryJoin.AND, children = listOf(
            cond("genre", values = listOf("horror")),
            cond("tag", values = emptyList()), // not yet filled in
        ))
        assertTrue(eval(item(genres = listOf("horror")), root))
    }

    // ─── Depth (sub-blocks) ─────────────────────────────────────────────────────

    @Test
    fun `sub-block nested inside a top-level block evaluates with its own join`() {
        // Top block: Genre horror AND (Tag a OR Tag b) — the sub-block uses OR, the parent uses AND.
        val root = ConditionGroup(QueryJoin.AND, children = listOf(
            ConditionGroup(QueryJoin.AND, children = listOf(
                cond("genre", values = listOf("horror")),
                ConditionGroup(QueryJoin.OR, children = listOf(cond("tag", values = listOf("a")), cond("tag", values = listOf("b")))),
            )),
        ))
        assertTrue(eval(item(genres = listOf("horror"), tags = listOf("b")), root))
        assertFalse(eval(item(genres = listOf("horror"), tags = listOf("c")), root), "genre matches but neither sub-block tag matches")
        assertFalse(eval(item(genres = listOf("drama"), tags = listOf("a")), root), "sub-block matches but the parent's own genre condition doesn't")
    }

    @Test
    fun `maxBlockDepth counts block sub-block one more as 1-2-3`() {
        val flat = ConditionGroup(QueryJoin.AND, children = listOf(ConditionGroup(QueryJoin.OR, children = listOf(cond("genre")))))
        assertEquals(1, flat.maxBlockDepth())

        val oneSub = ConditionGroup(QueryJoin.AND, children = listOf(
            ConditionGroup(QueryJoin.OR, children = listOf(ConditionGroup(QueryJoin.AND, children = listOf(cond("genre"))))),
        ))
        assertEquals(2, oneSub.maxBlockDepth())

        val twoSub = ConditionGroup(QueryJoin.AND, children = listOf(
            ConditionGroup(QueryJoin.OR, children = listOf(
                ConditionGroup(QueryJoin.AND, children = listOf(
                    ConditionGroup(QueryJoin.OR, children = listOf(cond("genre"))),
                )),
            )),
        ))
        assertEquals(3, twoSub.maxBlockDepth())
    }

    // ─── Legacy migration ───────────────────────────────────────────────────────

    @Test
    fun `flat ALL migrates to one OR-block per condition AND'd and evaluates identically`() {
        val flatConds = listOf(cond("genre", values = listOf("horror")), cond("network", values = listOf("hbo")))
        val tree = migrateFlatQuery(MatchMode.ALL, flatConds)
        assertEquals(QueryJoin.AND, tree.join)
        assertEquals(2, tree.children.size)

        val matching = item(genres = listOf("horror"), network = "HBO")
        val partial = item(genres = listOf("horror"), network = "Netflix")
        assertEquals(ConditionEvaluator.matches(matching, MatchMode.ALL, flatConds, emptySet()), eval(matching, tree))
        assertEquals(ConditionEvaluator.matches(partial, MatchMode.ALL, flatConds, emptySet()), eval(partial, tree))
        assertTrue(eval(matching, tree))
        assertFalse(eval(partial, tree))
    }

    @Test
    fun `flat ANY migrates to a single OR-block and evaluates identically`() {
        val flatConds = listOf(cond("genre", values = listOf("horror")), cond("network", values = listOf("hbo")))
        val tree = migrateFlatQuery(MatchMode.ANY, flatConds)
        assertEquals(QueryJoin.AND, tree.join)
        assertEquals(1, tree.children.size)
        val orBlock = tree.children.single() as ConditionGroup
        assertEquals(QueryJoin.OR, orBlock.join)
        assertEquals(2, orBlock.children.size)

        val eitherOnly = item(genres = listOf("horror"), network = "Netflix")
        val neither = item(genres = listOf("comedy"), network = "Netflix")
        assertEquals(ConditionEvaluator.matches(eitherOnly, MatchMode.ANY, flatConds, emptySet()), eval(eitherOnly, tree))
        assertEquals(ConditionEvaluator.matches(neither, MatchMode.ANY, flatConds, emptySet()), eval(neither, tree))
        assertTrue(eval(eitherOnly, tree))
        assertFalse(eval(neither, tree))
    }

    @Test
    fun `content_row condition migrates its embedded rows recursively`() {
        // A legacy-shaped RowConfig embedded in a content_row condition — migration must recurse into it.
        val embeddedRow = RowConfig(id = "r1", kind = RowKind.CUSTOM, match = MatchMode.ALL, conditions = listOf(cond("genre", values = listOf("horror"))))
        val flatConds = listOf(cond("content_row", "is_any_of", rows = listOf(embeddedRow)))
        val tree = migrateFlatQuery(MatchMode.ALL, flatConds)
        val migratedCond = tree.children.single().let { (it as ConditionGroup).children.single() as Condition }
        val migratedRow = migratedCond.rows.single()
        assertTrue(migratedRow.query != null, "embedded row's flat match/conditions must be migrated into its own query")

        assertTrue(eval(item(genres = listOf("horror")), tree), "membership in the embedded row's (migrated) filter")
        assertFalse(eval(item(genres = listOf("comedy")), tree))
    }

    // ─── R87 content_row via rowMatches ─────────────────────────────────────────

    @Test
    fun `content_row facet matches membership in a referenced row already as a tree`() {
        val row = RowConfig(id = "r1", kind = RowKind.CUSTOM, mediaKind = "MOVIE",
            query = ConditionGroup(QueryJoin.AND, children = listOf(cond("tag", values = listOf("staff-pick")))))
        val root = ConditionGroup(QueryJoin.AND, children = listOf(cond("content_row", "is_any_of", rows = listOf(row))))
        assertTrue(eval(item(kind = MediaKind.MOVIE, tags = listOf("staff-pick")), root))
        assertFalse(eval(item(kind = MediaKind.MOVIE, tags = listOf("other")), root))
        assertFalse(eval(item(kind = MediaKind.TV_SHOW, tags = listOf("staff-pick")), root), "row is MOVIE-only")
    }

    // ─── Audio / track facets sanity (unchanged behavior, now via the tree) ────

    @Test
    fun `audio_language and track_title facets still evaluate through the tree`() {
        val tracks = listOf(Track(0, "0:a:0", TrackKind.AUDIO, "aac", "da", "Commentary", true, false))
        val root = ConditionGroup(QueryJoin.AND, children = listOf(
            cond("audio_language", values = listOf("da")),
            cond("track_title", "contains", values = listOf("comment")),
        ))
        assertTrue(eval(item(tracks = tracks), root))
        assertFalse(eval(item(tracks = emptyList()), root))
    }

    // ─── Pruning ────────────────────────────────────────────────────────────────

    @Test
    fun `pruned drops empty conditions and empty groups but keeps the root`() {
        val messy = ConditionGroup(QueryJoin.AND, children = listOf(
            cond("genre", values = listOf("horror")),
            cond("tag", values = emptyList()),                              // dead leaf
            ConditionGroup(QueryJoin.OR, children = listOf(cond("network", values = emptyList()))), // dead sub-group
        ))
        val prunedTree = messy.pruned()
        assertEquals(1, prunedTree.children.size)
        assertTrue(prunedTree.isLive())

        val allDead = ConditionGroup(QueryJoin.AND, children = listOf(cond("genre", values = emptyList())))
        val prunedAllDead = allDead.pruned()
        assertTrue(prunedAllDead.children.isEmpty(), "root stays a group even when everything prunes away")
        assertFalse(prunedAllDead.isLive())
    }

    // ─── DEB-2837: year and rating ──────────────────────────────────────────────

    private fun titled(year: Int?, imdb: Pair<Double, Long>? = null, tmdb: Pair<Double, Int>? = null) = item().copy(
        year = year,
        imdbRating = imdb?.let { ImdbRating(it.first, it.second, 0L) },
        tmdbVoteAverage = tmdb?.first, tmdbVoteCount = tmdb?.second,
    )
    private fun one(c: Condition) = ConditionGroup(QueryJoin.AND, children = listOf(ConditionGroup(QueryJoin.OR, children = listOf(c))))
    private fun year(from: String, to: String) = cond("year", "between", listOf(from, to))
    private fun rating(vararg v: String) = cond("rating", "at_least", v.toList())

    @Test fun yearRangeIsInclusiveOnBothEndsAndAWinlessTitleMatchesNone() {   // acceptance 2
        val nineties = one(year("1990", "1999"))
        assertTrue(eval(titled(1990), nineties)); assertTrue(eval(titled(1999), nineties))
        assertFalse(eval(titled(1989), nineties)); assertFalse(eval(titled(2000), nineties))
        val before = one(year("", "1989"))
        assertTrue(eval(titled(1950), before)); assertFalse(eval(titled(1990), before))
        assertTrue(eval(titled(2021), one(year("2020", ""))))
        for (g in listOf(nineties, before, one(year("2020", "")))) assertFalse(eval(titled(null), g), "a title with no year matches no range")
    }

    @Test fun aBlankYearRangeIsNotLiveAndABlockHoldingOnlyThatMatchesEverything() {   // acceptance 2
        assertFalse(year("", "").isLive()); assertFalse(cond("year", "between").isLive())
        assertTrue(year("", "1989").isLive()); assertTrue(year("2020", "").isLive())
        val g = one(year("", ""))
        assertTrue(eval(titled(null), g)); assertTrue(eval(titled(1950), g))
        assertTrue(g.pruned().children.isEmpty(), "pruned() drops it on save")
    }

    @Test fun notOverAYearRangeIsOutsideTheRangeOrNoYear() {
        val outside = ConditionGroup(QueryJoin.AND, children = listOf(ConditionGroup(QueryJoin.OR, not = true, children = listOf(year("1990", "1999")))))
        assertTrue(eval(titled(1989), outside)); assertTrue(eval(titled(null), outside)); assertFalse(eval(titled(1995), outside))
    }

    @Test fun ratingNeedsTheVotesOfTheSourceThatGaveIt() {   // acceptance 3
        val g = one(rating("7.5", "200"))
        assertTrue(eval(titled(2000, imdb = 7.6 to 5000), g))
        assertFalse(eval(titled(2000, imdb = 7.6 to 150, tmdb = 8.0 to 900), g), "a thin IMDb rating does not fall through to TMDB")
        assertTrue(eval(titled(2000, tmdb = 7.8 to 300), g))
        assertFalse(eval(titled(2000, tmdb = 7.8 to 100), g))
        assertFalse(eval(titled(2000), g), "unrated does not match")
        assertFalse(eval(titled(2000, imdb = 7.4 to 5000), g)); assertTrue(eval(titled(2000, imdb = 7.5 to 5000), g))
    }

    @Test fun aBlankOrMissingVoteMinimumBehavesAs200() {   // acceptance 3
        val thin = titled(2000, imdb = 8.0 to 199); val ok = titled(2000, imdb = 8.0 to 200)
        for (g in listOf(one(rating("7.5", "")), one(rating("7.5")))) { assertFalse(eval(thin, g)); assertTrue(eval(ok, g)) }
        assertTrue(eval(thin, one(rating("7.5", "100"))))
    }

    @Test fun aRatingIsLiveByItsMinimumAlone() {
        assertTrue(rating("7.5").isLive()); assertTrue(rating("7.5", "").isLive())
        assertFalse(rating("").isLive()); assertFalse(rating("", "300").isLive()); assertFalse(cond("rating", "at_least").isLive())
    }

    @Test fun notOverARatingAlsoTakesInTheUnrated() {
        val below = ConditionGroup(QueryJoin.AND, children = listOf(ConditionGroup(QueryJoin.OR, not = true, children = listOf(rating("7.5")))))
        assertTrue(eval(titled(2000, imdb = 6.0 to 900), below)); assertTrue(eval(titled(2000), below)); assertFalse(eval(titled(2000, imdb = 8.0 to 900), below))
    }

    @Test fun yearAndRatingConditionsRoundTripThroughTheSharedSerializer() {   // acceptance 7, second half
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val tree = ConditionGroup(QueryJoin.AND, children = listOf(ConditionGroup(QueryJoin.OR, children = listOf(year("1990", "1999"), year("", "1989"), rating("7.5", "200"), rating("7.5", "")))))
        val wire = json.encodeToString(ConditionGroup.serializer(), tree)
        assertEquals(tree, json.decodeFromString(ConditionGroup.serializer(), wire))
        assertEquals(wire, json.encodeToString(ConditionGroup.serializer(), json.decodeFromString(ConditionGroup.serializer(), wire)), "byte for byte")
        assertTrue(wire.contains("\"values\":[\"1990\",\"1999\"]") && wire.contains("\"values\":[\"7.5\",\"\"]"))
    }

    @Test fun badYearAndRatingValuesAreNamedForTheValidator() {   // FR-8
        assertNull(year("1990", "1999").valueProblem()); assertNull(year("", "1989").valueProblem()); assertNull(year("", "").valueProblem())
        assertNotNull(year("19", "").valueProblem()); assertNotNull(year("1869", "").valueProblem()); assertNotNull(year("", "2101").valueProblem())
        assertNotNull(year("abc", "").valueProblem()); assertNotNull(year("2000", "1999").valueProblem())
        assertNull(rating("0").valueProblem()); assertNull(rating("10", "0").valueProblem()); assertNull(rating("7.5", "").valueProblem()); assertNull(rating("7.5").valueProblem())
        assertNotNull(rating("10.1").valueProblem()); assertNotNull(rating("-1").valueProblem()); assertNotNull(rating("x").valueProblem())
        assertNotNull(rating("7.5", "-1").valueProblem()); assertNotNull(rating("7.5", "2.5").valueProblem()); assertNotNull(rating("7.5", "many").valueProblem())
        assertNull(cond("genre", values = listOf("x")).valueProblem())
        assertNotNull(ConditionGroup(children = listOf(ConditionGroup(children = listOf(year("2000", "1999"))))).firstValueProblem())
    }

    // ─── DEB-2837: franchise and its display name ───────────────────────────────

    private fun inFranchise(id: Int?, kind: MediaKind = MediaKind.MOVIE) = item().copy(kind = kind, collectionId = id, collectionName = id?.let { "Series $it Collection" })

    @Test fun franchiseIsAnyOfMatchesThatFranchisesFilmsAndNoSeries() {   // acceptance 5
        val g = one(cond("franchise", "is_any_of", listOf("7")))
        assertTrue(eval(inFranchise(7), g))
        assertFalse(eval(inFranchise(8), g)); assertFalse(eval(inFranchise(null), g))
        assertFalse(eval(inFranchise(7, MediaKind.TV_SHOW), g), "a series has no franchise, even with a collection id on its record")
    }

    @Test fun franchiseIsNoneOfMatchesEverythingElse() {   // acceptance 5
        val g = one(cond("franchise", "is_none_of", listOf("7")))
        assertFalse(eval(inFranchise(7), g))
        assertTrue(eval(inFranchise(8), g)); assertTrue(eval(inFranchise(null), g)); assertTrue(eval(inFranchise(7, MediaKind.TV_SHOW), g))
        assertTrue(eval(inFranchise(8), one(cond("franchise", "is_any_of", listOf("7", "8")))), "any of several")
    }

    @Test fun theDisplayNameDropsATrailingCollectionOnly() {   // FR-6.3
        assertEquals("Alpha", franchiseDisplayName("Alpha Collection"))
        assertEquals("Alpha", franchiseDisplayName("Alpha collection"))
        assertEquals("Alpha", franchiseDisplayName("Alpha - Collection"))
        assertEquals("Alpha", franchiseDisplayName("  Alpha \u2013 COLLECTION  "))
        assertEquals("Alpha", franchiseDisplayName("Alpha"))
        assertEquals("Collection of Alpha", franchiseDisplayName("Collection of Alpha"), "only a trailing word")
        assertEquals("Alphacollection", franchiseDisplayName("Alphacollection"), "a whole word")
        assertNull(franchiseDisplayName("Collection")); assertNull(franchiseDisplayName(" - Collection")); assertNull(franchiseDisplayName(null)); assertNull(franchiseDisplayName("  "))
    }
}
