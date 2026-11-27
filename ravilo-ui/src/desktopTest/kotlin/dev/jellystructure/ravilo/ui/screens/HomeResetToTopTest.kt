package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-5891 (ravilo-can-be-the-tvs-home-screen.md FR-3) — Home pressed from a player (or any page) lands on the top of Home. The Home
 * store is kept across navigation, so the reset has to clear the scroll and the tile the viewer left from.
 */
@OptIn(ExperimentalTestApi::class)
class HomeResetToTopTest {
    private fun card(id: String) = MediaCard(id = id, title = "Tile $id", year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null)
    private fun row(id: String) = Row(id = id, title = "Row $id", items = listOf(card("$id-1"), card("$id-2")))

    private fun store() = HomeStore(
        TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:1", { "token" }, platform = "tv"),
        seedFeed = HomeFeed(heroes = emptyList(), channels = emptyList(), rows = (1..8).map { row("r$it") }, autoAdvanceSeconds = 0),
    )

    @Test fun `a scrolled Home with a remembered tile is back at the top with no tile to return to`() = runComposeUiTest {
        val st = store()
        setContent {
            HomeScreen(store = st, apiClient = TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:1", { "token" }, platform = "tv"))
        }
        waitForIdle()
        runOnIdle { kotlinx.coroutines.runBlocking { st.listState.scrollToItem(4) } }
        waitForIdle()
        assertTrue(st.listState.firstVisibleItemIndex > 0, "the page is scrolled down before the reset")
        st.rememberReturn("r4", "r4-2", 4, 1)

        runOnIdle { kotlinx.coroutines.runBlocking { st.resetToTop() } }
        waitForIdle()

        assertEquals(0, st.listState.firstVisibleItemIndex)
        assertEquals(0, st.listState.firstVisibleItemScrollOffset)
        assertNull(st.focusRowKey)
        assertNull(st.focusItemKey)
        assertEquals(0, st.focusRowIndex)
        assertEquals(0, st.focusItemIndex)
    }
}
