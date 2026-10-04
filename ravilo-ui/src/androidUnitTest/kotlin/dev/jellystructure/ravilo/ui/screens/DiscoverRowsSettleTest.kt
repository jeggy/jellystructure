package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.DiscoverEntry
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.DiscoverRow
import dev.jellystructure.shared.tv.FacetItem
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.RequestEntry
import dev.jellystructure.shared.tv.UpcomingFeed
import dev.jellystructure.shared.tv.UpcomingItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R367 — Discover's rows stop bouncing: after a focus move the page reaches one resting position and stays there.
 * Walks Down into a row with the clock paused after the last key (no `waitForIdle`: on the old rule the animation never
 * ended), advances 2 s, reads the heading's and the focused tile's bounds, advances 2 s more, and reads again.
 * Stand-in titles only. Each case prints the heights it measured (dev review item 3's estimates).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class DiscoverRowsSettleTest {
    @get:Rule val rule = createComposeRule()

    private fun entry(r: Int, i: Int) = DiscoverEntry(
        entry = RequestEntry(tmdbId = r * 100 + i, title = "Wish $r-$i", year = 2000 + i, genre = "Drama"),
        acquisition = AcquisitionRecord(itemKey = "tmdb:${r * 100 + i}"),
    )

    private val discover = DiscoverResponse(available = true, canRequest = true,
        rows = (1..3).map { r -> DiscoverRow(feedId = "feed$r", feedName = "Wishlist $r", entries = (1..10).map { entry(r, it) }) })

    private val facets = BrowseFacets(
        networks = (1..30).map { FacetItem(name = "Net $it", count = 40 - it) },
        studios = (1..30).map { FacetItem(name = "Lot $it", count = 40 - it) },
        genres = (1..30).map { FacetItem(name = "Kind $it", count = 40 - it) },
    )

    private val upcoming = UpcomingFeed(enabled = true, items = (1..3).flatMap { day ->
        (1..6).map { i -> UpcomingItem(id = "u$day-$i", title = "Soon $day-$i", date = "2026-10-1$day") }
    })

    private fun render(segment: DiscoverSegment) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val api = fakeTvApiClient { path ->
            when (path) {
                "/api/tv/discover" -> RaviloWireJson.encodeToString(DiscoverResponse.serializer(), discover)
                "/api/tv/discover/requests/mine" -> "[]"
                "/api/tv/upcoming" -> RaviloWireJson.encodeToString(UpcomingFeed.serializer(), upcoming)
                else -> null
            }
        }
        val request = DiscoverStore(api)
        val coming = UpcomingStore(api)
        rule.setContent {
            DiscoverScreen(
                segment = segment,
                segments = listOf(DiscoverSegment.COMING_SOON, DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES, DiscoverSegment.REQUEST),
                onSegment = {}, focusSegmentOnEntry = false, onFocusSegmentConsumed = {},
                displayName = "Olivar", onNavSelect = {}, onProfile = {}, onSearch = {},
                upcomingStore = coming, onUpcomingItemSelect = {}, requestStore = request, onEntrySelect = { _, _ -> },
                onSearchSeerr = {}, taxonomyStore = TaxonomyStore { facets }, onTileSelect = { _, _, _ -> },
            )
        }
        val probe = when (segment) {
            DiscoverSegment.REQUEST -> "Wishlist 1"
            DiscoverSegment.COMING_SOON -> "Soon 1-1"
            DiscoverSegment.NETWORKS -> "Net 1"
            DiscoverSegment.STUDIOS -> "Lot 1"
            DiscoverSegment.GENRES -> "Kind 1"
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(probe), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** The last key with the clock paused; then two readings 2 s apart. */
    private fun lastKeyThenSettle(key: Key, heading: String?, read: () -> Pair<Rect?, Rect>): Pair<Rect?, Rect> {
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.mainClock.advanceTimeBy(2_000)
        val first = read()
        rule.mainClock.advanceTimeBy(2_000)
        val second = read()
        assertEquals(first.first, second.first, "the heading moved after settling ($heading)")
        assertEquals(first.second, second.second, "the focused tile moved after settling")
        return second
    }

    private fun focusedBounds(): Rect = rule.onNode(isFocused(), useUnmergedTree = true).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun boundsOf(text: String): Rect? = rule.onAllNodes(hasText(text), useUnmergedTree = true).let { nodes ->
        if (nodes.fetchSemanticsNodes().isEmpty()) null else nodes.onFirst().getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    }

    private val screenH: Float get() = rule.onRoot().getBoundsInRoot().bottom.value

    private fun stripBottom(): Float = boundsOf("Request")?.bottom ?: 0f

    private fun requestWalk(toRow: Int, upAfter: Boolean = false) {
        render(DiscoverSegment.REQUEST)
        press(Key.DirectionDown)                 // the app bar → the selected tab (Request)
        val stripBottom = stripBottom()
        repeat(toRow - 1) { press(Key.DirectionDown) }   // row 1 … row (toRow − 1)
        val target = if (upAfter) toRow - 1 else toRow
        val last = if (upAfter) { press(Key.DirectionDown); Key.DirectionUp } else Key.DirectionDown
        val (heading, tile) = lastKeyThenSettle(last, "Wishlist $target") { boundsOf("Wishlist $target") to focusedBounds() }
        println("R367 Request row $target: heading=$heading tile=$tile screen=$screenH strip=$stripBottom")
        assertTrue(heading != null && heading.top >= stripBottom - 1f, "the heading is in the list, not under the strip")
        assertTrue(tile.top < screenH, "the focused tile is on screen")
    }

    @Test fun `a Request row below the first settles with its captions on screen`() = requestWalk(2)

    @Test fun `the last Request row settles`() = requestWalk(3)

    @Test fun `Up into a Request row brings its heading in`() = requestWalk(3, upAfter = true)

    private fun wallWalk(seg: DiscoverSegment) {
        render(seg)
        press(Key.DirectionDown)             // the tab
        press(Key.DirectionDown)             // the wall's first line
        val (_, tile) = lastKeyThenSettle(Key.DirectionDown, "$seg line 2") { null to focusedBounds() }
        println("R367 $seg second line: tile=$tile screen=$screenH")
        assertTrue(tile.top < screenH)
    }

    @Test fun `the Networks wall settles on a line below the first`() = wallWalk(DiscoverSegment.NETWORKS)
    @Test fun `the Studios wall settles on a line below the first`() = wallWalk(DiscoverSegment.STUDIOS)
    @Test fun `the Genres wall settles on a line below the first`() = wallWalk(DiscoverSegment.GENRES)

    @Test fun `Coming Soon settles on its second day`() {
        render(DiscoverSegment.COMING_SOON)
        press(Key.DirectionDown)                 // the tab
        press(Key.DirectionDown)                 // the filter chips
        press(Key.DirectionDown)                 // day 1
        val (_, tile) = lastKeyThenSettle(Key.DirectionDown, "day 2") { null to focusedBounds() }
        println("R367 Coming Soon day 2: tile=$tile screen=$screenH")
        assertTrue(tile.top < screenH)
    }

    @Config(qualifiers = "w1000dp-h600dp")
    @Test fun `a Request row settles in a 600 dp-tall window`() = requestWalk(2)
}
