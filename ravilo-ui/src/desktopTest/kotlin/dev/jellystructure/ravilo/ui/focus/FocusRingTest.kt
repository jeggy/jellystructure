package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-2204 (FR-1, FR-4) — the ring is a permanent stroke whose alpha eases over [RaviloMotion.FOCUS_RING_MS], draws
 * only where focus is drawn, and jumps to its end value under reduced motion.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class FocusRingTest {
    private val tag = "ring"

    /** The red channel at the ring's left edge: black = no ring, white = a full ring (a white ring on black). */
    private fun androidx.compose.ui.test.ComposeUiTest.ringLevel(): Float =
        onNodeWithTag(tag).captureToImage().toPixelMap().let { it[2, it.height / 2].red }

    @Test fun `the ring is not there before focus and eases in over 200 ms then out`() = runComposeUiTest {
        var focused by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.testTag(tag).size(40.dp).background(Color.Black).focusRing(focused, RectangleShape, Color.White, 6.dp))
        }
        mainClock.advanceTimeByFrame()
        assertEquals(0f, ringLevel(), 0.01f)

        focused = true
        mainClock.advanceTimeByFrame()      // applies the change and starts the tween
        mainClock.advanceTimeBy(80)
        val early = ringLevel()
        assertTrue(early > 0.02f && early < 0.98f, "an early frame is between the ends, was $early")
        mainClock.advanceTimeBy(60)
        val mid = ringLevel()
        assertTrue(mid > early && mid < 0.99f, "later is brighter than early, was $early then $mid")
        mainClock.advanceTimeBy(RaviloMotion.FOCUS_RING_MS.toLong())
        assertEquals(1f, ringLevel(), 0.01f)

        focused = false
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(80)
        val fading = ringLevel()
        assertTrue(fading > 0.02f && fading < 0.98f, "easing out passes through the middle too, was $fading")
        mainClock.advanceTimeBy(RaviloMotion.FOCUS_RING_MS.toLong())
        assertEquals(0f, ringLevel(), 0.01f)
    }

    @Test fun `a ring that starts focused is drawn at once`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { Box(Modifier.testTag(tag).size(40.dp).background(Color.Black).focusRing(true, RectangleShape, Color.White, 6.dp)) }
        mainClock.advanceTimeByFrame()
        assertEquals(1f, ringLevel(), 0.01f)
    }

    @Test fun `reduced motion reaches the end value in the first frame in both directions`() = runComposeUiTest {
        var focused by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(Modifier.testTag(tag).size(40.dp).background(Color.Black).focusRing(focused, RectangleShape, Color.White, 6.dp))
            }
        }
        mainClock.advanceTimeByFrame()
        focused = true
        mainClock.advanceTimeByFrame()
        assertEquals(1f, ringLevel(), 0.01f)
        focused = false
        mainClock.advanceTimeByFrame()
        assertEquals(0f, ringLevel(), 0.01f)
    }

    @Test fun `a phone draws no ring because rememberFocusVisual reads false`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalFocusVisible provides false) {
                var focused by rememberFocusVisual()
                LaunchedEffect(Unit) { focused = true }   // real focus lands; nothing is drawn
                Box(Modifier.testTag(tag).size(40.dp).background(Color.Black).focusRing(focused, RectangleShape, Color.White, 6.dp))
            }
        }
        mainClock.advanceTimeBy(500)
        assertEquals(0f, ringLevel(), 0.01f)
    }

    @Test fun `a resting stroke stays and the wider focused one fades in over it`() = runComposeUiTest {
        var focused by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent {
            Box(
                Modifier.testTag(tag).size(40.dp).background(Color.Black)
                    .focusRing(focused, RectangleShape, Color.White, 6.dp, resting = Color.Red, restingWidth = 2.dp),
            )
        }
        mainClock.advanceTimeByFrame()
        val rest = onNodeWithTag(tag).captureToImage().toPixelMap().let { it[1, 20] }
        assertEquals(1f, rest.red, 0.01f); assertEquals(0f, rest.green, 0.01f)   // the 2 dp resting stroke is red
        // 4 dp in is inside the 6 dp focused stroke but outside the 2 dp resting one
        assertEquals(0f, onNodeWithTag(tag).captureToImage().toPixelMap().let { it[4, 20] }.red, 0.01f)
        focused = true
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(RaviloMotion.FOCUS_RING_MS + 50L)
        assertEquals(1f, onNodeWithTag(tag).captureToImage().toPixelMap().let { it[4, 20] }.green, 0.01f)
    }
}
