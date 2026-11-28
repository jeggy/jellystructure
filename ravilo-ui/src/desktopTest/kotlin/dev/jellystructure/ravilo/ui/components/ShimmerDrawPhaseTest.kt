package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** DEB-5837 FR-1 — the sweep is drawn in the draw phase: a skeleton never recomposes per animation frame. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ShimmerDrawPhaseTest {
    @Test fun `a skeleton recomposes zero times across 60 animation frames`() = runComposeUiTest {
        var compositions = 0
        setContent {
            SideEffect { compositions++ }
            Box(Modifier.testTag("sk").size(100.dp).skeleton(RoundedCornerShape(10.dp)))
        }
        waitForIdle()
        val before = compositions
        mainClock.autoAdvance = false
        repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals(before, compositions)
    }

    /** The harness cancels infinite animations, so the progress is driven by a state the composable never reads. */
    @Test fun `the sweep is read at draw time and moves the pixels without recomposing`() = runComposeUiTest {
        var compositions = 0
        var p by mutableStateOf(-1.5f)
        val brush = SweepBrush({ p }, Color.Black, Color.White)
        setContent {
            SideEffect { compositions++ }
            Box(Modifier.testTag("sk").size(100.dp).skeleton(RoundedCornerShape(10.dp), brush))
        }
        waitForIdle()
        val before = compositions
        fun frame() = onNodeWithTag("sk").captureToImage().toPixelMap().let { m -> (10 until 90 step 10).map { m[it, it] } }
        val first = frame()
        p = -0.5f
        waitForIdle()
        assertNotEquals(first, frame())
        assertEquals(before, compositions)
    }
}
