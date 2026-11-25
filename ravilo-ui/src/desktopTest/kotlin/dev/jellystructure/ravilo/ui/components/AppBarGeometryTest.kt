package dev.jellystructure.ravilo.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.ScreenScale
import dev.jellystructure.ravilo.ui.theme.AppBarGeometry
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-3967 (FR-9, FR-10, FR-11), DEB-4270 (FR-15, FR-16, FR-17) — the TV bar's two ends and the points between them, the
 * 60 dp every page reads, when the bar collapses, and that the collapse composes with Screen scale.
 * (tv-screen-scale-and-slim-header.md)
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class AppBarGeometryTest {
    /** `RaviloDimens.appBarHeight` as a page reads it. */
    private fun barHeight(): Dp {
        var out = 0.dp
        runComposeUiTest { setContent { out = RaviloDimens.appBarHeight } }
        return out
    }

    @Test fun `Standard is today's numbers`() {
        val g = AppBarGeometry.Standard
        assertEquals(60.dp, g.height)
        assertEquals(26.dp, g.brandMark); assertEquals(22.sp, g.wordmark); assertEquals((-1).sp, g.wordmarkTracking); assertEquals(8.dp, g.brandGap)
        assertEquals(15.sp, g.navType); assertEquals(12.dp, g.navPadH); assertEquals(6.dp, g.navPadV); assertEquals(11.dp, g.navCorner)
        assertEquals(18.dp, g.itemGap); assertEquals(40.dp, g.circle); assertEquals(22.dp, g.searchGlyph)
        assertEquals(14.sp, g.initials); assertEquals(15.sp, g.clock)
    }

    @Test fun `Slim is the adopted table, the collapsed bar`() {
        val g = AppBarGeometry.Slim
        assertEquals(48.dp, g.height)
        assertEquals(20.dp, g.brandMark); assertEquals(17.sp, g.wordmark); assertEquals((-0.8).sp, g.wordmarkTracking); assertEquals(7.dp, g.brandGap)
        assertEquals(14.sp, g.navType); assertEquals(11.dp, g.navPadH); assertEquals(5.dp, g.navPadV); assertEquals(9.dp, g.navCorner)
        assertEquals(14.dp, g.itemGap); assertEquals(32.dp, g.circle); assertEquals(18.dp, g.searchGlyph)
        assertEquals(12.sp, g.initials); assertEquals(13.sp, g.clock)
    }

    @Test fun `the height every page reads is 60 dp, always`() = assertEquals(60.dp, barHeight())

    @Test fun `lerp at 0 and 1 is Standard and Slim`() {
        assertEquals(AppBarGeometry.Standard, AppBarGeometry.lerp(AppBarGeometry.Standard, AppBarGeometry.Slim, 0f))
        assertEquals(AppBarGeometry.Slim, AppBarGeometry.lerp(AppBarGeometry.Standard, AppBarGeometry.Slim, 1f))
    }

    @Test fun `lerp at 0 point 5 is halfway in every number`() {
        val g = AppBarGeometry.lerp(AppBarGeometry.Standard, AppBarGeometry.Slim, 0.5f)
        assertEquals(54.dp, g.height)
        assertEquals(23.dp, g.brandMark); assertEquals(19.5.sp, g.wordmark); assertEquals(7.5.dp, g.brandGap)
        assertEquals(36.dp, g.circle); assertEquals(20.dp, g.searchGlyph); assertEquals(14.sp, g.clock)
    }

    @Test fun `the collapse line is past 24 dp or past the first item`() {
        val line = 24f
        assertFalse(pastCollapseLine(0, 0, line))
        assertFalse(pastCollapseLine(0, 24, line))
        assertTrue(pastCollapseLine(0, 25, line))
        assertTrue(pastCollapseLine(1, 0, line))
    }

    @Test fun `the line is 24 dp in the density it is read in`() {
        var px = 0f
        var baseline = 0f
        runComposeUiTest {
            setContent {
                baseline = with(LocalDensity.current) { 24.dp.toPx() }
                ScreenScale(pct = 90, enabled = true) { px = with(LocalDensity.current) { BAR_COLLAPSE_AFTER.toPx() } }
            }
        }
        assertEquals(baseline * 0.9f, px, 0.01f)
    }

    @Test fun `collapsed on a TV with the page scrolled and focus outside the bar`() {
        assertTrue(appBarCollapsedTarget(tvPlatform = true, pageScrolled = true, barHasFocus = false))
    }

    @Test fun `focus inside the bar draws Standard`() {
        assertFalse(appBarCollapsedTarget(tvPlatform = true, pageScrolled = true, barHasFocus = true))
    }

    @Test fun `the top of the page and every non-TV platform draw Standard`() {
        assertFalse(appBarCollapsedTarget(tvPlatform = true, pageScrolled = false, barHasFocus = false))
        assertFalse(appBarCollapsedTarget(tvPlatform = false, pageScrolled = true, barHasFocus = false))
    }

    @Test fun `content starts at the same offset collapsed or not`() {
        // Discover, the detail pages, Browse and Search all start at `appBarHeight + 24.dp`; a collapsed bar uncovers 12 dp, nothing moves.
        assertEquals(84.dp, barHeight() + 24.dp)
    }

    @Test fun `the collapse composes with Screen scale - the geometry times 0 point 9`() {
        var px = 0f
        var basePx = 0f
        runComposeUiTest {
            setContent {
                basePx = with(LocalDensity.current) { AppBarGeometry.Slim.height.toPx() }
                ScreenScale(pct = 90, enabled = true) {
                    px = with(LocalDensity.current) { AppBarGeometry.Slim.height.toPx() }
                }
            }
        }
        assertEquals(basePx * 0.9f, px, 0.01f)
    }

    @Test fun `the focus ring on the round buttons is held 2 dp clear of the fill`() {
        // 2 dp stroke + 2 dp gap: the stroke's outer edge sits 4 dp outside the circle (RaviloButton's is 2 + 3).
        assertEquals(4.dp, BAR_RING_OUTSET)
    }
}
