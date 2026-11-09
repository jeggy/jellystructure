package dev.jellystructure.ravilo.ui

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DEB-3966 (FR-2) — layout shrinks by s, text by t = 1 − (1 − s)/2; 100 % supplies nothing. */
class ScreenScaleTest {
    private val tv = Density(density = 2f, fontScale = 1f)

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertEquals(expected, actual, 1e-4f, what)

    /** The px one `sp` draws at, relative to today's. */
    private fun textRatio(base: Density, scaled: Density) =
        (scaled.density * scaled.fontScale) / (base.density * base.fontScale)

    @Test fun at100NoDensityIsSupplied() = assertNull(screenScaleDensity(tv, 100))

    @Test fun aboveAndAroundOneHundredSnapsToNothing() {
        assertNull(screenScaleDensity(tv, 140))
        assertNull(screenScaleDensity(tv, 98))
    }

    @Test fun layoutShrinksByS() {
        for ((pct, s) in listOf(95 to 0.95f, 90 to 0.90f, 85 to 0.85f)) {
            val d = assertNotNull(screenScaleDensity(tv, pct), "$pct")
            assertNear(tv.density * s, d.density, "$pct density")
        }
    }

    @Test fun textShrinksByHalfAsMuch() {
        for ((pct, t) in listOf(95 to 0.975f, 90 to 0.95f, 85 to 0.925f)) {
            val d = assertNotNull(screenScaleDensity(tv, pct), "$pct")
            assertNear(t, textRatio(tv, d), "$pct text")
        }
    }

    @Test fun aSystemFontScaleIsKept() {
        val big = Density(density = 3f, fontScale = 1.3f)
        val d = assertNotNull(screenScaleDensity(big, 90))
        assertNear(3f * 0.9f, d.density, "density")
        assertNear(0.95f, textRatio(big, d), "text")
    }

    @Test fun aStrayValueSnapsBeforeItScales() {
        val d = assertNotNull(screenScaleDensity(tv, 7))   // below 85 → 85
        assertNear(2f * 0.85f, d.density, "density")
    }

    @Test fun aTvAt85StillReadsAsATv() {
        // FR-4: classification reads the base density, so a 1080p TV (density 2) is never a handset either way.
        assertEquals(false, isHandset(isTv = true, widthPx = 1920, heightPx = 1080, density = tv.density))
        val d = assertNotNull(screenScaleDensity(tv, 85))
        // ... and the scaled tree sees a wider window (≈ 1129 dp), not a narrower one.
        assertEquals(1129f, 1920f / d.density, 1f)
    }
}
