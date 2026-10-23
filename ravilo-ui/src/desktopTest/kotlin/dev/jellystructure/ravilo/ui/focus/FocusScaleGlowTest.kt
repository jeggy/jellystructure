package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-2204 (FR-2, FR-4) — the player's scale rides the soft spring in a graphicsLayer and jumps under reduced motion. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class FocusScaleGlowTest {
    private val tag = "stage"

    /** A 100 dp stage with a centred 40 dp white square scaled 2x when focused: 80 dp wide at the end. Sampled 30 dp from the centre. */
    private fun androidx.compose.ui.test.ComposeUiTest.outsideTheRestingSquare(): Float =
        onNodeWithTag(tag).captureToImage().toPixelMap().let { pm ->
            val density = pm.width / 100f
            pm[(50f * density + 30f * density).toInt(), pm.height / 2].red
        }

    private fun androidx.compose.ui.test.ComposeUiTest.stage(reduced: Boolean, focused: () -> Boolean) = setContent {
        CompositionLocalProvider(LocalReducedMotion provides reduced) {
            Box(Modifier.testTag(tag).size(100.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                Box(Modifier.focusScaleGlow(focused(), 2f, 0.dp, RectangleShape, Color.White).size(40.dp).background(Color.White))
            }
        }
    }

    @Test fun `the scale springs up over several frames and settles at its target`() = runComposeUiTest {
        var focused by mutableStateOf(false)
        mainClock.autoAdvance = false
        stage(reduced = false) { focused }
        mainClock.advanceTimeByFrame()
        assertEquals(0f, outsideTheRestingSquare(), 0.01f)
        focused = true
        mainClock.advanceTimeByFrame()
        assertEquals(0f, outsideTheRestingSquare(), 0.01f)         // the first frame has barely moved
        mainClock.advanceTimeBy(1_500)
        assertEquals(1f, outsideTheRestingSquare(), 0.01f)         // settled at 2x
        focused = false
        mainClock.advanceTimeBy(1_500)
        assertEquals(0f, outsideTheRestingSquare(), 0.01f)
    }

    @Test fun `reduced motion jumps to the end scale in the first frame`() = runComposeUiTest {
        var focused by mutableStateOf(false)
        mainClock.autoAdvance = false
        stage(reduced = true) { focused }
        mainClock.advanceTimeByFrame()
        focused = true
        mainClock.advanceTimeByFrame()
        assertEquals(1f, outsideTheRestingSquare(), 0.01f)
        focused = false
        mainClock.advanceTimeByFrame()
        assertTrue(outsideTheRestingSquare() < 0.01f)
    }
}
