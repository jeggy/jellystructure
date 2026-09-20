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
    fun theFirstChipIsNetworksOnEveryHousehold() {
        // FR-R268-2: the taxonomy segments cannot be gated off, so entry is the same everywhere.
        // ⚠ A real behaviour change for the household with neither integration, which used to land on
        // Studios — named here because it gets no other change from this phase.
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

    @Test
    fun taxonomySegmentsAreAGatingSetNotAnOrder() {
        assertEquals(
            setOf(DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES),
            TAXONOMY_SEGMENTS,
        )
    }
}
