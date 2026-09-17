package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phase 232 (FR-232-1) — which ink a captured logo is drawn in. */
class LogoInkTest {
    private fun raster(w: Int, h: Int, px: (Int) -> IntArray): ByteArray {
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) { val p = px(i); for (c in 0..3) out[i * 4 + c] = p[c].toByte() }
        return out
    }
    private val clear = intArrayOf(0, 0, 0, 0)

    @Test fun blackInkOnTransparencyIsDark() =
        assertEquals("dark", logoInkOf(raster(8, 8) { if (it % 3 == 0) intArrayOf(10, 10, 10, 255) else clear }, 8, 8))

    @Test fun whiteInkOnTransparencyIsLight() =
        assertEquals("light", logoInkOf(raster(8, 8) { if (it % 3 == 0) intArrayOf(255, 255, 255, 255) else clear }, 8, 8))

    @Test fun aSaturatedBlueMarkIsDarkInk() =   // Universal: reads fine on a light plate
        assertEquals("dark", logoInkOf(raster(8, 8) { if (it % 2 == 0) intArrayOf(20, 80, 200, 255) else clear }, 8, 8))

    @Test fun anOpaqueWhiteRectangleBringsItsOwnGround() =
        assertEquals("dark", logoInkOf(raster(8, 8) { intArrayOf(250, 250, 250, 255) }, 8, 8))

    @Test fun antiAliasedEdgesDoNotFlipTheVerdict() =
        assertEquals("light", logoInkOf(raster(8, 8) { if (it % 4 == 0) intArrayOf(255, 255, 255, 255) else if (it % 4 == 1) intArrayOf(255, 255, 255, 40) else clear }, 8, 8))

    @Test fun nothingVisibleIsUnknown() = assertNull(logoInkOf(raster(8, 8) { clear }, 8, 8))
    @Test fun aShortBufferIsUnknown() = assertNull(logoInkOf(ByteArray(10), 8, 8))

    // FR-232-5 — a title clearlogo may be re-inked by the client, so an opaque image must never say "dark".
    @Test fun anOpaqueClearlogoIsLeftAlone() = assertNull(clearlogoInkOf(raster(8, 8) { intArrayOf(20, 20, 20, 255) }, 8, 8))
    @Test fun aBlackWordmarkClearlogoIsDark() =
        assertEquals("dark", clearlogoInkOf(raster(8, 8) { if (it % 3 == 0) intArrayOf(8, 8, 8, 255) else clear }, 8, 8))
    @Test fun aWhiteWordmarkClearlogoIsLight() =
        assertEquals("light", clearlogoInkOf(raster(8, 8) { if (it % 3 == 0) intArrayOf(250, 250, 250, 255) else clear }, 8, 8))

    @Test fun aColourfulMidToneClearlogoIsNeverReInked() {        // Rex and Monty / Nosy Nick / The Simpsons
        // bright green reads "light" — also untouched; what must never happen is "dark"
        kotlin.test.assertNotEquals("dark", clearlogoInkOf(raster(8, 8) { if (it % 2 == 0) intArrayOf(120, 220, 60, 255) else clear }, 8, 8))
        assertNull(clearlogoInkOf(raster(8, 8) { if (it % 2 == 0) intArrayOf(40, 110, 170, 255) else clear }, 8, 8))   // a mid-tone blue
        assertNull(clearlogoInkOf(raster(8, 8) { if (it % 2 == 0) intArrayOf(150, 20, 20, 255) else clear }, 8, 8))   // dark RED is branding, not black ink
    }
}
