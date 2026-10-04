package dev.jellystructure.ravilo.ui.focus

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R367 (FR-R367-3) — the framing rule converges: for every caller's band, every list height that matters and every
 * target size, one step from any start reaches a resting position (distance 0). Pure; no Compose.
 *
 * List heights (dp): 540 (the TV), 338 (Discover's list on the TV, dev review item 3), 600 (a 600 dp-tall desktop
 * window) and 398 (Discover's list in that window); densities 1 and 2.
 */
class EdgeBandScrollDistanceTest {
    private val lists = listOf(540f, 338f, 600f, 398f)
    private val densities = listOf(1f, 2f)
    private val eps = 0.01f

    private fun f(band: EdgeBand, d: Float, offset: Float, size: Float, container: Float) =
        edgeBandScrollDistance(offset, size, container, band.topInsetDp * d, container * band.centerLineFraction, band.peekDp * d)

    /** 50 integer starting offsets spread over −1.5 × to +1.5 × the container. */
    private fun starts(container: Float) = (0 until 50).map { i -> (-1.5f * container + i * (3f * container / 49f)).toInt().toFloat() }

    private fun forEachCase(block: (band: EdgeBand, d: Float, container: Float, size: Float) -> Unit) {
        for (band in EDGE_BANDS) for (d in densities) for (listDp in lists) {
            val container = listDp * d
            var sizeDp = 40f
            while (sizeDp <= 1.5f * listDp) { block(band, d, container, sizeDp * d); sizeDp += 1f }
        }
    }

    @Test fun everyCallerRestsAfterOneStep() {
        var failures = 0
        var first: String? = null
        forEachCase { band, d, container, size ->
            for (start in starts(container)) {
                val next = start - f(band, d, start, size, container)
                val again = f(band, d, next, size, container)
                if (abs(again) > eps) {
                    failures++
                    if (first == null) first = "band=$band d=$d container=$container size=$size start=$start → $next, then $again"
                }
            }
        }
        if (failures > 0) fail("$failures cases move again after one step; first: $first")
    }

    @Test fun aTargetThatFitsRestsInTheBandWithItsBottomOnScreen() {
        forEachCase { band, d, container, size ->
            if (band.centerLineFraction <= 0f) return@forEachCase
            val top = band.topInsetDp * d
            if (size > container - top) return@forEachCase
            val center = container * band.centerLineFraction
            for (start in starts(container)) {
                val rest = start - f(band, d, start, size, container)
                assertTrue(rest >= top - eps && rest <= maxOf(top, minOf(center, container - size)) + eps, "$band rest=$rest size=$size")
                assertTrue(rest + size <= container + eps, "$band bottom on screen: rest=$rest size=$size container=$container")
            }
        }
    }

    @Test fun aTargetTooTallRestsAtTheInsetWithNoPeek() {
        forEachCase { band, d, container, size ->
            if (band.centerLineFraction <= 0f) return@forEachCase
            val top = band.topInsetDp * d
            if (size <= container - top) return@forEachCase
            for (start in starts(container)) {
                val rest = start - f(band, d, start, size, container)
                assertEquals(top, rest, eps, "$band size=$size container=$container start=$start")
            }
        }
        // Request's 357 dp row in a 338 dp list rests with its heading at the list's top.
        assertEquals(0f, 200f - f(EdgeBand.DISCOVER_REQUEST, 1f, 200f, 357f, 338f), eps)
    }

    @Test fun aBandOffRevealNeverPushesTheTopAboveTheInset() {
        forEachCase { band, d, container, size ->
            if (band.centerLineFraction > 0f) return@forEachCase
            val top = band.topInsetDp * d
            for (start in starts(container)) {
                val rest = start - f(band, d, start, size, container)
                assertTrue(rest >= top - eps, "$band rest=$rest top=$top size=$size")
            }
        }
    }

    @Test fun aDiscoverRowAndItsTileAgreeOnOnePosition() {
        // Discover's numbers (dev review item 3): a Request row of 357 dp whose tile (283 dp) starts 54 dp below it.
        for (listDp in listOf(338f, 398f)) {
            val band = EdgeBand.DISCOVER_REQUEST
            fun fRow(rowTop: Float) = f(band, 1f, rowTop, 357f, listDp)
            fun fTile(rowTop: Float) = f(band, 1f, rowTop + 54f, 283f, listDp)
            for (start in starts(listDp)) {
                var a = start; a -= fRow(a); a -= fTile(a); a -= fRow(a)      // row, then tile
                var b = start; b -= fTile(b); b -= fRow(b); b -= fTile(b)     // tile, then row
                assertEquals(a, b, eps, "list=$listDp start=$start")
                assertEquals(0f, fRow(a), eps); assertEquals(0f, fTile(a), eps)
            }
        }
        // At 338 the row's top rests at 0, its last caption line inside the list.
        assertEquals(0f, 300f - f(EdgeBand.DISCOVER_REQUEST, 1f, 300f, 357f, 338f), eps)
    }

    /** Today's body before R367, frozen here: where it had a resting point, the new rule answers the same. */
    private fun oldBody(offset: Float, size: Float, containerSize: Float, topPx: Float, centerPx: Float, peekPx: Float): Float = when {
        offset < topPx -> offset - topPx
        centerPx > 0f && offset > centerPx -> offset - centerPx
        offset + size > containerSize -> offset + size - containerSize + peekPx
        else -> 0f
    }

    @Test fun identicalToTodayWhereTodayHadARestingPoint() {
        for (band in listOf(EdgeBand.HOME, EdgeBand.CHANNEL, EdgeBand.DETAIL)) for (container in listOf(540f, 600f)) {
            val top = band.topInsetDp; val peek = band.peekDp; val center = container * band.centerLineFraction
            val maxSize = if (center > 0f) container - center else container - top - peek
            var size = 40f
            while (size <= maxSize) {
                var offset = -1.5f * container
                while (offset <= 1.5f * container) {
                    assertEquals(oldBody(offset, size, container, top, center, peek), edgeBandScrollDistance(offset, size, container, top, center, peek), eps,
                        "$band container=$container size=$size offset=$offset")
                    offset += 7f
                }
                size += 1f
            }
        }
    }
}
