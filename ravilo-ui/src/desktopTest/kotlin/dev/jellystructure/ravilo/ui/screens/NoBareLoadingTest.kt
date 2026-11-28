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
}
