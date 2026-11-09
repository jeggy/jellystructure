package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertIs

/** DEB-4091 FR-2 — the shared brush used by Music, Home and Detail is static under reduced motion. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ShimmerReducedMotionTest {
    @Test fun `skeleton brush has no sweep under reduced motion`() = runComposeUiTest {
        var brush: Brush? = null
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                brush = rememberShimmerBrush()
            }
        }
        waitForIdle()
        assertIs<SolidColor>(brush)
    }

    @Test fun `hidden app skeleton brush has no sweep`() = runComposeUiTest {
        var brush: Brush? = null
        setContent {
            CompositionLocalProvider(LocalShimmerOnScreen provides false) {
                brush = rememberShimmerBrush()
            }
        }
        waitForIdle()
        assertIs<SolidColor>(brush)
    }

}
