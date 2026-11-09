package dev.jellystructure.ravilo.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.LayoutFamily
import dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-3983 (FR-14) — the count beside a TV row heading is `seedTotalCount ?: items.size`, hidden at one item or
 * fewer; the heading is 16 sp on the TV and 18 sp elsewhere; See All stays at the header's end on every family.
 * (tv-screen-scale-and-slim-header.md)
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RowHeadingCountTest {
    @Test fun theSeedsTotalWinsOverTheItemsHeld() = assertEquals(120, rowHeadingCount(120, 20))
    @Test fun withoutASeedTotalTheItemsAreCounted() = assertEquals(20, rowHeadingCount(null, 20))
    @Test fun twoItemsIsTheSmallestCount() = assertEquals(2, rowHeadingCount(null, 2))
    @Test fun oneItemOrFewerShowsNoCount() {
        assertNull(rowHeadingCount(null, 1))
        assertNull(rowHeadingCount(null, 0))
        assertNull(rowHeadingCount(1, 5))
    }

    @Test fun theTvHeadingIs16AndThePhoneAndDesktopKeep18() {
        assertEquals(16.sp, rowHeadingSize(LayoutFamily.TV))
        assertEquals(18.sp, rowHeadingSize(LayoutFamily.PHONE))
        assertEquals(18.sp, rowHeadingSize(LayoutFamily.DESKTOP))
    }

    private fun draw(family: LayoutFamily, count: Int?, test: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutFamily provides family) {
                StaticContentRow(
                    title = "Heading",
                    items = listOf(1, 2, 3),
                    headingCount = count,
                    seeAllLabel = "See All",
                    onSeeAll = {},
                ) { _, item, _ -> Text("tile$item") }
            }
        }
        test()
    }

    @Test fun theTvDrawsHeadingDotCountAndKeepsSeeAll() = draw(LayoutFamily.TV, 3) {
        onNodeWithText("Heading").assertExists()
        onNodeWithText(" · 3").assertExists()
        onNodeWithText("See All").assertExists()
    }

    @Test fun theTvDrawsNoCountForARowWithNone() = draw(LayoutFamily.TV, null) {
        onNodeWithText(" · 3").assertDoesNotExist()
        onNodeWithText("See All").assertExists()
    }

    @Test fun thePhoneAndDesktopDrawNoCountAndKeepSeeAll() {
        for (f in listOf(LayoutFamily.PHONE, LayoutFamily.DESKTOP)) draw(f, 3) {
            onNodeWithText(" · 3").assertDoesNotExist()
            onNodeWithText("See All").assertExists()
        }
    }
}
