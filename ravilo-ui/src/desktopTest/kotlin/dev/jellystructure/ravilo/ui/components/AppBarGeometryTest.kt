package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.ScreenScale
import dev.jellystructure.ravilo.ui.theme.AppBarGeometry
import dev.jellystructure.ravilo.ui.theme.HeaderStyle
import dev.jellystructure.ravilo.ui.theme.LocalHeaderStyle
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-3967 (FR-8, FR-9, FR-10, FR-11, Acceptance 8, 9) — the TV bar's numbers per header style, the offset every page
 * puts under it, and that Slim composes with Screen scale.
 * (tv-screen-scale-and-slim-header.md)
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class AppBarGeometryTest {
    /** `RaviloDimens.appBarHeight` as a page reads it, under [style] (null = nothing provided, the default). */
    private fun barHeight(style: HeaderStyle?): Dp {
        var out = 0.dp
        runComposeUiTest {
            setContent {
                if (style == null) out = RaviloDimens.appBarHeight
                else CompositionLocalProvider(LocalHeaderStyle provides style) { out = RaviloDimens.appBarHeight }
            }
        }
        return out
    }

    @Test fun `Standard is today's numbers`() {
        val g = AppBarGeometry.of(HeaderStyle.STANDARD)
        assertEquals(60.dp, g.height)
        assertEquals(26.dp, g.brandMark); assertEquals(22.sp, g.wordmark); assertEquals((-1).sp, g.wordmarkTracking); assertEquals(8.dp, g.brandGap)
        assertEquals(15.sp, g.navType); assertEquals(12.dp, g.navPadH); assertEquals(6.dp, g.navPadV); assertEquals(11.dp, g.navCorner)
        assertEquals(18.dp, g.itemGap); assertEquals(40.dp, g.circle); assertEquals(22.dp, g.searchGlyph)
        assertEquals(14.sp, g.initials); assertEquals(15.sp, g.clock)
    }

    @Test fun `Slim is the adopted table`() {
        val g = AppBarGeometry.of(HeaderStyle.SLIM)
        assertEquals(48.dp, g.height)
        assertEquals(20.dp, g.brandMark); assertEquals(17.sp, g.wordmark); assertEquals((-0.8).sp, g.wordmarkTracking); assertEquals(7.dp, g.brandGap)
        assertEquals(14.sp, g.navType); assertEquals(11.dp, g.navPadH); assertEquals(5.dp, g.navPadV); assertEquals(9.dp, g.navCorner)
        assertEquals(14.dp, g.itemGap); assertEquals(32.dp, g.circle); assertEquals(18.dp, g.searchGlyph)
        assertEquals(12.sp, g.initials); assertEquals(13.sp, g.clock)
    }

    @Test fun `nothing provided draws the standard bar`() = assertEquals(60.dp, barHeight(null))

    @Test fun `the bar height a page reads follows the style`() {
        assertEquals(60.dp, barHeight(HeaderStyle.STANDARD))
        assertEquals(48.dp, barHeight(HeaderStyle.SLIM))
    }

    @Test fun `content starts 12 dp higher under Slim`() {
        // Discover, the detail pages, Browse and Search all start at `appBarHeight + 24.dp`.
        val standard = barHeight(HeaderStyle.STANDARD) + 24.dp
        val slim = barHeight(HeaderStyle.SLIM) + 24.dp
        assertEquals(12.dp, standard - slim)
    }

    @Test fun `an unknown wire value draws as standard`() {
        assertEquals(HeaderStyle.STANDARD, HeaderStyle.of("ultra-slim"))
        assertEquals(HeaderStyle.STANDARD, HeaderStyle.of(null))
        assertEquals(HeaderStyle.STANDARD, HeaderStyle.of("standard"))
        assertEquals(HeaderStyle.SLIM, HeaderStyle.of("slim"))
    }

    @Test fun `Slim composes with Screen scale - the geometry times 0 point 9`() {
        var px = 0f
        var basePx = 0f
        runComposeUiTest {
            setContent {
                basePx = with(LocalDensity.current) { 48.dp.toPx() }
                ScreenScale(pct = 90, enabled = true) {
                    CompositionLocalProvider(LocalHeaderStyle provides HeaderStyle.SLIM) {
                        px = with(LocalDensity.current) { RaviloDimens.appBarHeight.toPx() }
                    }
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
