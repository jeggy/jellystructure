package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-2349 (DEB-2204, FR-5) — loaded content fades in once over [RaviloMotion.IMAGE_CROSSFADE_MS] after a loading view,
 * never when a screen opens already loaded, never under reduced motion, and never in the same frame as the loading view.
 *
 * The screen is a white square (the content) over a black backdrop with a blue square as the loading view: the red
 * channel of the backdrop is the content's alpha, and 0 for the loading view.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class FadeInOnLoadTest {
    private val tag = "backdrop"
    private val contentTag = "content"

    private enum class Phase { Loading, Loaded, Failed }

    /** The red channel at the backdrop's centre: the loaded content's alpha over black. */
    private fun ComposeUiTest.level(): Float =
        onNodeWithTag(tag).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2].red }

    private fun ComposeUiTest.screen(phase: () -> Phase, onComposed: (Phase, Boolean) -> Unit = { _, _ -> }, focus: FocusRequester? = null) =
        setContent {
            Box(Modifier.testTag(tag).size(40.dp).background(Color.Black)) {
                Box(Modifier.fillMaxSize().fadeInOnLoad(loading = phase() == Phase.Loading)) {
                    when (phase()) {
                        Phase.Loading -> {
                            DisposableEffect(Unit) { onComposed(Phase.Loading, true); onDispose { onComposed(Phase.Loading, false) } }
                            Box(Modifier.fillMaxSize().background(Color.Blue))
                        }
                        Phase.Loaded -> {
                            DisposableEffect(Unit) { onComposed(Phase.Loaded, true); onDispose { onComposed(Phase.Loaded, false) } }
                            Box(
                                Modifier.testTag(contentTag).fillMaxSize().background(Color.White)
                                    .let { if (focus != null) it.focusRequester(focus).focusable() else it },
                            )
                        }
                        Phase.Failed -> Box(Modifier.fillMaxSize().background(Color.White))
                    }
                }
            }
        }

    @Test fun `content composed first time already loaded starts at alpha 1`() = runComposeUiTest {
        mainClock.autoAdvance = false
        screen({ Phase.Loaded })
        mainClock.advanceTimeByFrame()
        assertEquals(1f, level(), 0.01f)
        mainClock.advanceTimeBy(RaviloMotion.IMAGE_CROSSFADE_MS + 100L)
        assertEquals(1f, level(), 0.01f)
    }

    @Test fun `loading to loaded passes through intermediate alpha and reaches 1 by 220 ms`() = runComposeUiTest {
        var phase by mutableStateOf(Phase.Loading)
        mainClock.autoAdvance = false
        screen({ phase })
        mainClock.advanceTimeByFrame()
        assertEquals(0f, level(), 0.01f)   // the loading view is blue: no red

        phase = Phase.Loaded
        mainClock.advanceTimeByFrame()      // the first frame of the content: still invisible, no flash
        assertEquals(0f, level(), 0.01f)
        mainClock.advanceTimeBy(80)
        val early = level()
        assertTrue(early > 0.02f && early < 0.98f, "an early frame is between the ends, was $early")
        mainClock.advanceTimeBy(48)
        val mid = level()
        assertTrue(mid > early && mid < 0.99f, "later is brighter than early, was $early then $mid")
        mainClock.advanceTimeBy(RaviloMotion.IMAGE_CROSSFADE_MS - 80L - 48L + 32L)   // 220 ms from the first loaded frame, plus two frames
        assertEquals(1f, level(), 0.01f)
    }

    @Test fun `the loading view and the content are never composed in the same frame`() = runComposeUiTest {
        var phase by mutableStateOf(Phase.Loading)
        var loadingComposed = false
        var contentComposed = false
        mainClock.autoAdvance = false
        screen({ phase }, onComposed = { p, on -> if (p == Phase.Loading) loadingComposed = on else contentComposed = on })
        mainClock.advanceTimeByFrame()
        assertTrue(loadingComposed && !contentComposed, "loading first")

        phase = Phase.Loaded
        repeat(25) {   // the swap frame and the whole fade, one frame at a time
            mainClock.advanceTimeByFrame()
            assertFalse(loadingComposed && contentComposed, "two trees at once in frame $it")
        }
        assertTrue(contentComposed && !loadingComposed, "content replaced loading in one go")
    }

    @Test fun `with reduced motion loaded content is at alpha 1 in its first frame`() = runComposeUiTest {
        var phase by mutableStateOf(Phase.Loading)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(Modifier.testTag(tag).size(40.dp).background(Color.Black)) {
                    Box(Modifier.fillMaxSize().fadeInOnLoad(loading = phase == Phase.Loading)) {
                        if (phase == Phase.Loaded) Box(Modifier.fillMaxSize().background(Color.White))
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        phase = Phase.Loaded
        mainClock.advanceTimeByFrame()
        assertEquals(1f, level(), 0.01f)
    }

    @Test fun `an error state replacing the loading view fades in too`() = runComposeUiTest {
        var phase by mutableStateOf(Phase.Loading)
        mainClock.autoAdvance = false
        screen({ phase })
        mainClock.advanceTimeByFrame()
        phase = Phase.Failed
        mainClock.advanceTimeByFrame()
        assertEquals(0f, level(), 0.01f)
        mainClock.advanceTimeBy(80)
        val early = level()
        assertTrue(early > 0.02f && early < 0.98f, "the error state eases in, was $early")
        mainClock.advanceTimeBy(RaviloMotion.IMAGE_CROSSFADE_MS.toLong())
        assertEquals(1f, level(), 0.01f)
    }

    @Test fun `a retry arms the fade again`() = runComposeUiTest {
        var phase by mutableStateOf(Phase.Loading)
        mainClock.autoAdvance = false
        screen({ phase })
        mainClock.advanceTimeByFrame()
        phase = Phase.Failed
        mainClock.advanceTimeBy(RaviloMotion.IMAGE_CROSSFADE_MS + 100L)
        assertEquals(1f, level(), 0.01f)

        phase = Phase.Loading               // Retry
        mainClock.advanceTimeBy(64)
        assertEquals(0f, level(), 0.01f)
        phase = Phase.Loaded
        mainClock.advanceTimeByFrame()
        assertEquals(0f, level(), 0.01f)
        mainClock.advanceTimeBy(80)
        val early = level()
        assertTrue(early > 0.02f && early < 0.98f, "the second load fades in as well, was $early")
        mainClock.advanceTimeBy(RaviloMotion.IMAGE_CROSSFADE_MS.toLong())
        assertEquals(1f, level(), 0.01f)
    }

    @Test fun `the fade is draw only so bounds and focus are the same at alpha 0 as at alpha 1`() = runComposeUiTest {
        var phase by mutableStateOf(Phase.Loading)
        val focus = FocusRequester()
        mainClock.autoAdvance = false
        screen({ phase }, focus = focus)
        mainClock.advanceTimeByFrame()
        phase = Phase.Loaded
        mainClock.advanceTimeByFrame()
        val bounds = onNodeWithTag(contentTag).getBoundsInRoot()
        assertEquals(0f, level(), 0.01f)
        focus.requestFocus()                 // in the first loaded frame, while the content is still invisible
        mainClock.advanceTimeByFrame()
        onNodeWithTag(contentTag).assertIsFocused()
        mainClock.advanceTimeBy(RaviloMotion.IMAGE_CROSSFADE_MS + 100L)
        assertEquals(1f, level(), 0.01f)
        assertEquals(bounds, onNodeWithTag(contentTag).getBoundsInRoot())
        onNodeWithTag(contentTag).assertIsFocused()
    }
}
