package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-5837 FR-1 — the sweep is drawn in the draw phase: a skeleton never recomposes per animation frame. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ShimmerDrawPhaseTest {
    @Test fun `a skeleton recomposes zero times across 60 animation frames`() = runComposeUiTest {
        var compositions = 0
        setContent {
            SideEffect { compositions++ }
            Box(Modifier.size(100.dp).skeleton(RoundedCornerShape(10.dp)))
        }
        waitForIdle()
        val before = compositions
        mainClock.autoAdvance = false
        repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals(before, compositions)
    }
}
