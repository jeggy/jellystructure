package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.ravilo.ui.components.APP_BAR_AVATAR_TAG
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.SearchResults
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * R364 — My List on a TV, key by key, over one kept [BrowseStore]: a title added since the last visit shows on the next
 * arrival (FR-1), a title removed while it was the one opened lands focus on its neighbour (R361's grid rule), and an
 * empty list says how to add one and puts focus on the avatar (FR-3). Also R362 FR-5 on `BrowseGrid`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-television")
@OptIn(ExperimentalTestApi::class)
class MyListFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun card(i: Int) = MediaCard(id = "t$i", kind = MediaKind.MOVIE, title = "Keeper $i", year = 2010 + i, genre = null,
        rating = null, posterUrl = null, backdropUrl = null)

    @Volatile private var served: List<MediaCard> = emptyList()
    private var shown by mutableStateOf(true)
    private var opened: MediaCard? = null
    private var profileOpened = 0
    private var navSelected = 0
    private lateinit var store: BrowseStore
    @Volatile private var gate: CountDownLatch? = null
    private val entered = AtomicInteger()

    private fun render(initial: List<MediaCard>) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        served = initial
        val api = fakeTvApiClient { path ->
            when (path) {
                // R361 (FR-R361-7) — a test that holds the refetch picks the frame it lands in.
                "/api/tv/browse" -> gate?.let { entered.incrementAndGet(); it.await(10, TimeUnit.SECONDS) }.let {
                    RaviloWireJson.encodeToString(SearchResults.serializer(), SearchResults(query = "", items = served, total = served.size))
                }
                "/api/tv/facets" -> RaviloWireJson.encodeToString(BrowseFacets.serializer(), BrowseFacets())
                else -> null
            }
        }
        store = BrowseStore(api)
        rule.setContent {
            if (shown) BrowseScreen(
                kind = BrowseKind.MY_LIST, store = store, displayName = "Olivar", onBack = {},
                onNavSelect = { navSelected++ }, onItemSelect = { opened = it; shown = false },
                onProfile = { profileOpened++ }, onSearch = {},
            )
        }
        waitForTitles(initial.size)
    }

    private fun waitForTitles(n: Int) {
        val text = if (n == 1) "1 title" else "$n titles"
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focused(text: String) =
        rule.onNode(isFocused() and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true).assertExists()

    @Test fun `acceptance 1 — a title added since the last visit shows on the next arrival`() {
        render(emptyList())
        shown = false
        rule.waitForIdle()
        served = listOf(card(1))
        shown = true
        waitForTitles(1)
        rule.onNode(hasText("Keeper 1"), useUnmergedTree = true).assertExists()
    }

    @Test fun `acceptance 2 — the opened title removed lands on its neighbour, not the app bar`() {
        render(listOf(card(1), card(2)))
        press(Key.DirectionDown)                   // the avatar → the grid
        focused("Keeper 1")
        press(Key.Enter)
        assertEquals("t1", opened?.id)
        served = listOf(card(2))                   // − My List on its page
        shown = true
        waitForTitles(1)
        focused("Keeper 2")
    }

    /** Open "Keeper 1", then return with the refetch held until the restore has aimed (R361 FR-R361-7). */
    private fun returnWithRefetchInFlight(next: List<MediaCard>) {
        press(Key.DirectionDown)
        focused("Keeper 1")
        press(Key.Enter)
        assertEquals("t1", opened?.id)
        val latch = CountDownLatch(1)
        gate = latch
        entered.set(0)
        served = next
        rule.mainClock.autoAdvance = false
        shown = true
        // Frame by frame until the refetch is asked for (the old list is on screen, the restore has aimed and focus
        // has not landed), then answer it.
        var frames = 0
        while (entered.get() == 0 && frames++ < 10) { rule.mainClock.advanceTimeByFrame(); org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        check(entered.get() > 0) { "the refetch was never asked for" }
        latch.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while ((store.state.value as? BrowseState.Loaded)?.results?.items?.size != next.size) {
            check(System.currentTimeMillis() < deadline) { "the refetch never landed" }
            Thread.sleep(10)
        }
        gate = null
        rule.mainClock.advanceTimeBy(500)         // the bound, from the last list change
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertNull(store.focusItemKey, "the restore spent the key")
    }

    @Test fun `R361 FR-7 — the opened title gone while the restore is in flight lands on its neighbour`() {
        render(listOf(card(1), card(2)))
        returnWithRefetchInFlight(listOf(card(2)))
        focused("Keeper 2")
    }

    @Test fun `R361 FR-7 — an empty refetch while the restore is in flight lands on the avatar`() {
        render(listOf(card(1), card(2)))
        returnWithRefetchInFlight(emptyList())
        rule.onNodeWithTag(APP_BAR_AVATAR_TAG, useUnmergedTree = true).assertIsFocused()
    }

    @Test fun `acceptance 4 — an empty My List shows the hint and focus is on the avatar, whose OK opens the profile menu`() {
        render(emptyList())
        rule.onNode(hasText("+ My List", substring = true), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(APP_BAR_AVATAR_TAG, useUnmergedTree = true).assertIsFocused()
        press(Key.Enter)
        assertEquals(1, profileOpened)
        assertEquals(0, navSelected)
    }

    @Test fun `R362 FR-5 on My List — Down from column 4 keeps the column`() {
        render((1..12).map { card(it) })
        press(Key.DirectionDown)
        focused("Keeper 1")
        repeat(3) { press(Key.DirectionRight) }
        focused("Keeper 4")
        press(Key.DirectionDown)
        focused("Keeper 10")
    }
}
