package dev.jellystructure.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R308 (FR-R308-7) — which studio/network logos the client re-inks to show on the light plate. */
class LogoReinkTest {
    private fun raster(w: Int, h: Int, px: (Int) -> IntArray): ByteArray {
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) { val p = px(i); for (c in 0..3) out[i * 4 + c] = p[c].toByte() }
        return out
    }
    private val clear = intArrayOf(0, 0, 0, 0)
    private fun strokes(r: Int, g: Int, b: Int) = raster(16, 16) { if (it % 3 == 0) intArrayOf(r, g, b, 255) else clear }

    @Test fun whiteStrokesOnTransparencyAreReinked() = assertTrue(logoReinkOf(strokes(255, 255, 255), 16, 16))

    @Test fun aWhiteOpaqueShapeWithADarkOutlineIsLeftAlone() {
        // 16×16: a white body filling the square, a black ring round its edge — a white shape with its own outline.
        val px = raster(16, 16) { i -> val x = i % 16; val y = i / 16
            if (x == 0 || y == 0 || x == 15 || y == 15) intArrayOf(10, 10, 10, 255) else intArrayOf(250, 250, 250, 255) }
        assertFalse(logoReinkOf(px, 16, 16))
    }

    @Test fun saturatedYellowStrokesReadByHue() = assertFalse(logoReinkOf(strokes(250, 220, 20), 16, 16))
    @Test fun paleUnsaturatedGreenStrokesAreReinked() = assertTrue(logoReinkOf(strokes(200, 235, 200), 16, 16))
    @Test fun blackStrokesAreLeftAlone() = assertFalse(logoReinkOf(strokes(10, 10, 10), 16, 16))
    @Test fun anOpaqueWhiteRectangleIsLeftAlone() = assertFalse(logoReinkOf(raster(16, 16) { intArrayOf(250, 250, 250, 255) }, 16, 16))
    @Test fun nothingVisibleIsLeftAlone() {
        assertFalse(logoReinkOf(raster(16, 16) { clear }, 16, 16))
        assertNull(logoReinkMeasure(raster(16, 16) { clear }, 16, 16))
    }
    @Test fun aShortBufferIsLeftAlone() = assertFalse(logoReinkOf(ByteArray(10), 16, 16))

    /**
     * The (lost, opaque) pairs measured on production's four re-inks and nearest misses (dev review item 1),
     * rebuilt as generic 1,000-pixel rasters: white and black pixels, opaque (255) or semi (200), the rest
     * clear. No production logo enters the repository.
     */
    private fun measured(whiteOpaque: Int, blackOpaque: Int, whiteSemi: Int, blackSemi: Int): ByteArray {
        val kinds = List(whiteOpaque) { intArrayOf(255, 255, 255, 255) } + List(blackOpaque) { intArrayOf(10, 10, 10, 255) } +
            List(whiteSemi) { intArrayOf(255, 255, 255, 200) } + List(blackSemi) { intArrayOf(10, 10, 10, 200) }
        return raster(1000, 1) { i -> kinds.getOrElse(i) { clear } }
    }

    private fun check(lost: Double, opaque: Double, expected: Boolean, px: ByteArray) {
        val (l, o) = logoReinkMeasure(px, 1000, 1)!!
        assertTrue(abs(l - lost) < 0.01 && abs(o - opaque) < 0.01, "measured ($l, $o), fixture meant ($lost, $opaque)")
        assertEquals(expected, logoReinkOf(px, 1000, 1), "($lost, $opaque)")
    }

    @Test fun theFourProductionReinks() {
        check(0.99, 0.30, true, measured(300, 0, 70, 5))    // pale strokes on transparency (the network)
        check(1.00, 0.48, true, measured(480, 0, 0, 0))     // a white wordmark
        check(0.99, 0.13, true, measured(130, 0, 285, 5))   // a thin white wordmark
        check(0.67, 0.20, true, measured(200, 0, 10, 130))  // a white wordmark beside a coloured emblem
    }

    @Test fun theNearestMissesStayAsTheyAre() {
        check(0.61, 0.48, false, measured(480, 0, 0, 390))  // white shapes with their own outline or body
        check(0.64, 0.51, false, measured(510, 0, 0, 365))
        check(0.72, 0.54, false, measured(540, 0, 5, 270))
        check(0.56, 0.76, false, measured(531, 229, 0, 240)) // solid boxes
        check(0.66, 1.00, false, measured(660, 340, 0, 0))
    }

    @Test fun theThumbnailKeepsTheAspectAt128OnTheLongSide() {
        assertEquals(128 to 32, logoReinkThumbSize(800, 200))
        assertEquals(32 to 128, logoReinkThumbSize(200, 800))
        assertEquals(128 to 1, logoReinkThumbSize(5000, 10))
        assertEquals(128 to 128, logoReinkThumbSize(64, 64))
    }
}
