package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.components.DETAIL_SKELETON_HERO
import dev.jellystructure.ravilo.ui.components.DetailLoadingShell
import dev.jellystructure.ravilo.ui.components.HOME_SKELETON_FIRST_ROW
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.MUSIC_SEARCH_SKELETON
import dev.jellystructure.ravilo.ui.components.MusicSearchLoadingRows
import dev.jellystructure.ravilo.ui.components.SEARCH_SKELETON
import dev.jellystructure.ravilo.ui.components.SETTINGS_SKELETON
import dev.jellystructure.ravilo.ui.components.SearchLoadingGrid
import dev.jellystructure.ravilo.ui.components.SettingsLoadingRows
import dev.jellystructure.ravilo.ui.components.TV_CODE_SKELETON
import dev.jellystructure.ravilo.ui.components.TvCodeLoadingBox
import dev.jellystructure.ravilo.ui.components.LastShown
import androidx.compose.ui.test.assertCountEquals
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** DEB-5837 FR-2 — each page surface's Loading state is a skeleton of its content region, never the word "Loading...". */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class NoBareLoadingTest {
    private fun assertSkeleton(tag: String, content: @Composable () -> Unit) = runComposeUiTest {
        setContent { RaviloTheme { Box(Modifier.fillMaxSize()) { content() } } }
        onNodeWithTag(tag).assertIsDisplayed()
        assertTrue(onAllLoadingText() == 0, "no bare loading text on $tag")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllLoadingText(): Int =
        onAllNodesWithText("Loading...").fetchSemanticsNodes().size

    @Test fun `the collection page waits as the Home skeleton`() = assertSkeleton(HOME_SKELETON_FIRST_ROW) { HomeLoadingShell() }
    @Test fun `a Discover wall waits as a tile grid`() = assertSkeleton(DISCOVER_SKELETON) { DiscoverLoading() }
    @Test fun `request detail waits as the detail skeleton`() = assertSkeleton(DETAIL_SKELETON_HERO) { DetailLoadingShell() }
    @Test fun `Upcoming detail waits as the detail skeleton`() = assertSkeleton(DETAIL_SKELETON_HERO) { DetailLoadingShell() }
    @Test fun `the live guide waits as channel rows`() = assertSkeleton(GUIDE_SKELETON) { GuideLoadingRows() }

    @Test fun `Settings waits as setting rows`() = assertSkeleton(SETTINGS_SKELETON) { SettingsLoadingRows() }
    @Test fun `the TV code screen waits as the code's box`() = assertSkeleton(TV_CODE_SKELETON) { TvCodeLoadingBox() }
    @Test fun `a first title or request search waits as the result grid`() = assertSkeleton(SEARCH_SKELETON) { SearchLoadingGrid(4) }
    @Test fun `a first music search waits as result rows`() = assertSkeleton(MUSIC_SEARCH_SKELETON) { MusicSearchLoadingRows() }

    @Test fun `a second query keeps the results shown and draws no skeleton`() {
        val shown = LastShown<String>().also { it.items = listOf("a", "b") }
        val loading = true
        runComposeUiTest {
            setContent {
                RaviloTheme {
                    val items = if (loading) shown.items else emptyList()
                    if (loading && items.isEmpty()) SearchLoadingGrid(4)
                    items.forEach { androidx.compose.material3.Text(it) }
                }
            }
            onNodeWithTag(SEARCH_SKELETON).assertDoesNotExist()
            onAllNodesWithText("a").assertCountEquals(1)
        }
    }
}
