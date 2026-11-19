package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.Dest
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.surpriseDest
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.SurpriseAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * DEB-3984 (FR-12, FR-13), DEB-4273 (Acceptance 13) — the TV bar has no Surprise me on any page, in either header style, and the
 * title Surprise me opens carries the run so far. (Home's own button is in HomeSurpriseMeTest.)
 * (tv-screen-scale-and-slim-header.md)
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SurpriseMeTest {
    @Test fun `the bar has no Surprise me, whether or not the frame provides the action`() = runComposeUiTest {
        var text = ""
        var action: (() -> Unit)? by mutableStateOf(null)
        var scrolled by mutableStateOf(false)
        setContent {
            text = str("nav.surprise")
            CompositionLocalProvider(LocalSurpriseMe provides action) { AppBar(navItems = listOf("A", "B"), onSearch = {}, onProfile = {}, scrolled = scrolled) }
        }
        assertTrue(text.isNotEmpty())
        // The two header styles (Standard at the top, Slim once the page has scrolled), with and without the gate.
        for (a in listOf<(() -> Unit)?>(null, {})) for (sc in listOf(false, true)) {
            runOnIdle { action = a; scrolled = sc }
            onAllNodesWithText(text).assertCountEquals(0)
        }
    }

    @Test fun `Right from the last tab goes to search and Left from search comes back`() = runComposeUiTest {
        var searched = 0
        setContent {
            CompositionLocalProvider(LocalSurpriseMe provides {}) { AppBar(navItems = listOf("A", "B"), onSearch = { searched++ }, onProfile = {}) }
        }
        onNodeWithText("B").performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        onRoot().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        waitForIdle()
        // Search is the next stop: Select on it opens search (nothing else sits between the last tab and it).
        onRoot().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        waitForIdle()
        assertEquals(1, searched)
        onRoot().performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithText("B").assertIsFocused()
    }

    @Test fun `an older server never sends surprise_me so the field is false`() {
        assertFalse(RaviloConfig().surpriseMe)
    }

    @Test fun `the page a surprise opens is the answer's kind and carries the run`() {
        val movie = surpriseDest(SurpriseAnswer("m2", MediaKind.MOVIE), listOf("s1", "m1"), "Ann")
        assertIs<Dest.MovieDetail>(movie)
        assertEquals(listOf("s1", "m1", "m2"), movie.surpriseShown)
        val series = surpriseDest(SurpriseAnswer("s9", MediaKind.SERIES), emptyList(), "Ann")
        assertIs<Dest.SeriesDetail>(series)
        assertEquals(listOf("s9"), series.surpriseShown)
        assertEquals("Ann", series.displayName)
    }

    @Test fun `a page Surprise me opened is a surprise page and an ordinary one is not`() {
        // Another one and the kicker key off a non-empty run, so a plain detail page (empty) shows neither.
        assertTrue(surpriseDest(SurpriseAnswer("a", MediaKind.MOVIE), emptyList(), "n").let { (it as Dest.MovieDetail).surpriseShown.isNotEmpty() })
        assertTrue(Dest.MovieDetail("a", "n").surpriseShown.isEmpty())
        assertTrue(Dest.SeriesDetail("a", "n").surpriseShown.isEmpty())
    }
}
