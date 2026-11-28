package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5838 (FR-7) — under reduced motion the hero stays on one slide, does not drift and changes slide as a cut. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class HeroReducedMotionTest {
    private fun hero(id: String, title: String) = Hero(
        item = MediaCard(id = id, kind = MediaKind.MOVIE, title = title, year = 2020, genre = null, rating = null,
            posterUrl = null, backdropUrl = null),
        taglineKicker = null, backdropUrl = "", logoUrl = null, badge = null,
    )

    private val slides = listOf(hero("a", "Stand-in Lighthouse"), hero("b", "Stand-in Ferry"))

    @Test fun `the policy asks for no auto-advance under reduced motion and none for one slide`() {
        assertTrue(heroAutoAdvances(7, 2, reducedMotion = false))
        assertFalse(heroAutoAdvances(7, 2, reducedMotion = true))
        assertFalse(heroAutoAdvances(0, 2, reducedMotion = false))
        assertFalse(heroAutoAdvances(7, 1, reducedMotion = false))
    }

    @Test fun `a slide change is a cut under reduced motion and a crossfade otherwise`() {
        val cut = heroSlideTransition(true)
        assertEquals(EnterTransition.None, cut.targetContentEnter)
        assertEquals(ExitTransition.None, cut.initialContentExit)
        assertTrue(heroSlideTransition(false).targetContentEnter != EnterTransition.None)
    }

    @Test fun `the hero stays on one slide for 20 s under reduced motion, and Right changes slide in one frame`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                val fr = remember { FocusRequester() }
                Box { HeroCarousel(items = slides, focusRequester = fr, autoAdvanceSeconds = 7) }
                androidx.compose.runtime.LaunchedEffect(Unit) { fr.requestFocus() }
            }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(20_000)
        onNode(hasText("Stand-in Lighthouse")).assertExists()
        onNode(hasText("Stand-in Ferry")).assertDoesNotExist()

        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        mainClock.advanceTimeByFrame()
        onNode(hasText("Stand-in Ferry")).assertExists()
        onNode(hasText("Stand-in Lighthouse")).assertDoesNotExist()   // a cut: the old slide is gone in the same frame
    }

    @Test fun `with the setting off the hero still advances by itself`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            val fr = remember { FocusRequester() }
            HeroCarousel(items = slides, focusRequester = fr, autoAdvanceSeconds = 7)
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(7_500)
        onNode(hasText("Stand-in Ferry")).assertExists()
    }
}
