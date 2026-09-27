package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R268. What is pinned here is not the order for its own sake — it is that the order is declared in
 * **one** place and only ever filtered.
 *
 * Three functions used to encode it independently (`discoverSegments`, `defaultDiscoverSegment`,
 * `nextDiscoverSegment`) and they had already drifted once: `defaultDiscoverSegment` answered only the
 * first two cases, so a household with neither integration reached Discover and landed on a segment
 * that was not rendered — a bug R243's own dev review had to find by hand. With one list and a filter
 * that is unrepresentable, and `theDefaultIsAlwaysARenderedChip` is the assertion that says so for
 * every gating combination at once.
 */
class DiscoverSegmentOrderTest {

    private val both = discoverSegments(upcomingAvailable = true, discoverAvailable = true)

    @Test
    fun theDeclaredOrderIsLibraryFirst() {
        assertEquals(
            listOf(
                DiscoverSegment.NETWORKS,
                DiscoverSegment.STUDIOS,
                DiscoverSegment.GENRES,
                DiscoverSegment.COMING_SOON,
                DiscoverSegment.REQUEST,
            ),
            both,
        )
    }

    @Test
    fun theEnumsOwnOrderIsTheShippedOrder() {
        // Dev review item 2: leaving the enum in the old order while introducing a declared list puts
        // two orders in one file, and anything reaching for `entries` or an ordinal disagrees with the
        // bar. They are the same list on purpose.
        assertEquals(DISCOVER_SEGMENT_ORDER, DiscoverSegment.entries.toList())
    }

    @Test
    fun gatingFiltersAndNeverReorders() {
        val seerrOnly = discoverSegments(upcomingAvailable = false, discoverAvailable = true)
        assertEquals(
            listOf(DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES, DiscoverSegment.REQUEST),
            seerrOnly,
        )
        val arrOnly = discoverSegments(upcomingAvailable = true, discoverAvailable = false)
        assertEquals(
            listOf(DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES, DiscoverSegment.COMING_SOON),
            arrOnly,
        )
        val neither = discoverSegments(upcomingAvailable = false, discoverAvailable = false)
        assertEquals(
            listOf(DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES),
            neither,
        )
        // Every gated view is a subsequence of the declared order — the mechanical form of "filters,
        // never re-orders".
        for (view in listOf(seerrOnly, arrOnly, neither)) {
            assertEquals(view, DISCOVER_SEGMENT_ORDER.filter { it in view }, "gating re-ordered: $view")
        }
    }

    @Test
    fun theDefaultIsAlwaysARenderedChip() {
        // The bug this phase makes unrepresentable, asserted across every configuration.
        for (upcoming in listOf(false, true)) {
            for (discover in listOf(false, true)) {
                val rendered = discoverSegments(upcoming, discover)
                val default = defaultDiscoverSegment(upcoming, discover)
                assertTrue(
                    default in rendered,
                    "default $default is not rendered for (upcoming=$upcoming, discover=$discover)",
                )
                assertEquals(rendered.first(), default)
            }
        }
    }

    @Test
    fun theFirstChipIsNetworksOnEveryHouseholdThatHasNetworks() {
        // FR-R268-2: with every wall holding something (or no answer from the server: R310), entry is the
        // same everywhere. R310 gates the walls themselves; the tests below cover that.
        for (upcoming in listOf(false, true)) {
            for (discover in listOf(false, true)) {
                assertEquals(DiscoverSegment.NETWORKS, defaultDiscoverSegment(upcoming, discover))
            }
        }
    }

    @Test
    fun steppingWrapsThroughTheRenderedOrderOnly() {
        // R170's Discover-button step. It must walk the rendered list, not the declared one, or it
        // steps onto a chip that is not on screen.
        val neither = discoverSegments(upcomingAvailable = false, discoverAvailable = false)
        assertEquals(DiscoverSegment.STUDIOS, nextDiscoverSegment(neither, DiscoverSegment.NETWORKS))
        assertEquals(DiscoverSegment.NETWORKS, nextDiscoverSegment(neither, DiscoverSegment.GENRES))
        assertEquals(DiscoverSegment.REQUEST, nextDiscoverSegment(both, DiscoverSegment.COMING_SOON))
        assertEquals(DiscoverSegment.NETWORKS, nextDiscoverSegment(both, DiscoverSegment.REQUEST))
    }

    // ── R310 (FR-R310-7) — a wall with nothing on it has no chip ──

    private val net = DiscoverSegment.NETWORKS
    private val stu = DiscoverSegment.STUDIOS
    private val gen = DiscoverSegment.GENRES

    @Test
    fun noAnswerFromTheServerShowsEveryWall() {
        assertEquals(both, discoverSegments(upcomingAvailable = true, discoverAvailable = true, walls = null))
    }

    @Test
    fun aWallGatedOffAloneOrInPairsKeepsTheOrderAndLandsOnTheFirstLeft() {
        val cases = mapOf(
            setOf(stu, gen) to listOf(stu, gen),
            setOf(net, gen) to listOf(net, gen),
            setOf(net, stu) to listOf(net, stu),
            setOf(gen) to listOf(gen),
            setOf(stu) to listOf(stu),
            setOf(net) to listOf(net),
        )
        for ((walls, expected) in cases) {
            val segs = discoverSegments(upcomingAvailable = true, discoverAvailable = true, walls = walls)
            assertEquals(expected + listOf(DiscoverSegment.COMING_SOON, DiscoverSegment.REQUEST), segs, "walls $walls")
            assertEquals(DISCOVER_SEGMENT_ORDER.filter { it in segs }, segs, "re-ordered for $walls")
            assertEquals(expected.first(), defaultDiscoverSegment(true, true, walls))
        }
    }

    @Test
    fun allThreeOffLandsOnTheIntegrationsOrOnNothing() {
        val none = emptySet<DiscoverSegment>()
        assertEquals(DiscoverSegment.COMING_SOON, defaultDiscoverSegment(upcomingAvailable = true, discoverAvailable = true, walls = none))
        assertEquals(DiscoverSegment.REQUEST, defaultDiscoverSegment(upcomingAvailable = false, discoverAvailable = true, walls = none))
        assertEquals(emptyList(), discoverSegments(upcomingAvailable = false, discoverAvailable = false, walls = none))
        assertEquals(null, defaultDiscoverSegment(upcomingAvailable = false, discoverAvailable = false, walls = none))
    }

    @Test
    fun steppingNeverReachesAHiddenWall() {
        val segs = discoverSegments(upcomingAvailable = false, discoverAvailable = true, walls = setOf(stu))
        assertEquals(listOf(stu, DiscoverSegment.REQUEST), segs)
        var cur: DiscoverSegment = stu
        repeat(6) {
            cur = nextDiscoverSegment(segs, cur)!!
            assertTrue(cur in segs, "stepped onto $cur")
        }
    }

    @Test
    fun taxonomySegmentsAreAGatingSetNotAnOrder() {
        assertEquals(
            setOf(DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES),
            TAXONOMY_SEGMENTS,
        )
    }
}
