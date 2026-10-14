package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import dev.jellystructure.ravilo.ui.LocalTileScale
import dev.jellystructure.ravilo.ui.theme.LayoutFamily
import dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.SearchResults
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R304 FR-R304-7 — My List never moves the Profile page while it loads. The *Account* heading sits below the
 * My List section, so its top edge is the section's whole height: read before the answer, after it, and on a second
 * visit that starts from the kept answer. Phone layout and the desktop layout, at the operator's three content sizes.
 * Stand-in titles only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h900dp")
@OptIn(ExperimentalTestApi::class)
class ProfileMyListStableTest {
    @get:Rule val rule = createComposeRule()

    private fun card(i: Int) = MediaCard(id = "t$i", kind = MediaKind.MOVIE, title = "Keeper $i", year = 2010 + i, genre = null,
        rating = null, posterUrl = null, backdropUrl = null)

    private val titles = (1..5).map(::card)

    /** An api whose `/api/tv/browse` answers [body] once [gate] opens; a null [body] is a 404 (a failed read). */
    private fun api(gate: CountDownLatch, body: String?) = fakeTvApiClient { path ->
        if (path == "/api/tv/browse") { gate.await(10, TimeUnit.SECONDS); body } else null
    }

    private fun answer(items: List<MediaCard>) =
        RaviloWireJson.encodeToString(SearchResults.serializer(), SearchResults(query = "", items = items, total = items.size))

    private fun setProfile(api: TvApiClient, store: ProfileMyListStore, family: LayoutFamily, scale: Float) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent {
            CompositionLocalProvider(LocalLayoutFamily provides family, LocalTileScale provides scale) {
                ProfileScreen(
                    apiClient = api, myListStore = store, displayName = "Olivar", isAdmin = false, avatarUrl = null, serverHost = "stand-in.invalid",
                    onPhoto = {}, onMyListSeeAll = {}, onItemSelect = {}, onAppLanguage = {}, onChangePassword = {},
                    onSettings = {}, onSignedOut = {},
                )
            }
        }
    }

    /** Where everything below My List starts, and how tall My List itself is — the page moves iff either changes. */
    private fun geometry(): List<Dp> {
        val section = rule.onNodeWithTag(PROFILE_MY_LIST_TAG).getBoundsInRoot()
        val below = rule.onAllNodes(hasTestTag(PROFILE_ACCOUNT_TAG)).fetchSemanticsNodes().isNotEmpty()
        return listOf(section.top, section.height) + if (below) listOf(rule.onNodeWithTag(PROFILE_ACCOUNT_TAG).getBoundsInRoot().top) else emptyList()
    }

    private fun waitForTitle(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun loadingThenLoaded(family: LayoutFamily, scale: Float) {
        val gate = CountDownLatch(1)
        val client = api(gate, answer(titles))
        setProfile(client, ProfileMyListStore(client), family, scale)
        rule.waitForIdle()
        val loading = geometry()
        gate.countDown()
        waitForTitle("Keeper 1")
        assertEquals(loading, geometry(), "the page moved when My List arrived ($family, scale $scale)")
    }

    @Test fun `phone — the page does not move when the list arrives, at every content size`() {
        loadingThenLoaded(LayoutFamily.TV, 1f)
    }

    @Test fun `phone — smallest content size`() {
        loadingThenLoaded(LayoutFamily.TV, 0.82f)
    }

    @Test fun `phone — middle content size`() {
        loadingThenLoaded(LayoutFamily.TV, 0.91f)
    }

    @Test fun `desktop layout — the page does not move when the list arrives`() {
        loadingThenLoaded(LayoutFamily.DESKTOP, 1f)
    }

    @Test fun `a second visit draws the kept list on its first frame at the same height`() {
        val gate = CountDownLatch(1)
        val client = api(gate, answer(titles))
        val store = ProfileMyListStore(client)
        gate.countDown()
        runBlocking { assertTrue(store.refresh()) }   // the first visit's read
        setProfile(client, store, LayoutFamily.TV, 1f)
        // no waiting for a network answer: the kept answer is already on the first composed frame
        rule.onNode(hasText("Keeper 1"), useUnmergedTree = true).assertExists()
        val kept = geometry()
        rule.waitForIdle()
        assertEquals(kept, geometry())
    }

    @Test fun `the heading line is one height with and without a count`() {
        val tag = "heading"
        var total by mutableStateOf(0)
        var desk by mutableStateOf(false)
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent {
            Box(Modifier.testTag(tag)) { MyListHeadingLine(total, desk = desk, onSeeAll = {}) }
        }
        for (d in listOf(false, true)) {
            desk = d
            total = 0
            rule.waitForIdle()
            val without = rule.onNodeWithTag(tag).getBoundsInRoot().height
            total = 12
            rule.waitForIdle()
            assertEquals(without, rule.onNodeWithTag(tag).getBoundsInRoot().height, "heading line grew with a count (desk=$d)")
        }
    }

    @Test fun `a failed first read keeps nothing and a later read does`() {
        val gate = CountDownLatch(1)
        gate.countDown()
        val failing = ProfileMyListStore(api(gate, null))
        runBlocking { assertEquals(false, failing.refresh()) }
        assertNull(failing.cards)
        assertEquals(0, failing.total)

        val ok = ProfileMyListStore(api(gate, answer(titles)))
        runBlocking { assertTrue(ok.refresh()) }
        assertEquals(titles.map { it.id }, assertNotNull(ok.cards).map { it.id })
        assertEquals(5, ok.total)
    }
}
