package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * DEB-3984 (FR-12, FR-13, Acceptance 10, 11) — the TV bar's Surprise me pill is there only when the frame provides the
 * action (a TV, on a server that sends `surprise_me`), and the title it opens carries the run so far.
 * (tv-screen-scale-and-slim-header.md)
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SurpriseMeTest {
    @Test fun `the pill is absent when the frame gives it no action`() = runComposeUiTest {
        var text = ""
        setContent {
            text = str("nav.surprise")
            CompositionLocalProvider(LocalSurpriseMe provides null) { AppBar(navItems = listOf("A", "B"), onSearch = {}, onProfile = {}) }
        }
        assertTrue(text.isNotEmpty())
        onAllNodesWithText(text).assertCountEquals(0)
    }

    @Test fun `the pill is drawn with its label and pressing it runs the action`() = runComposeUiTest {
        var text = ""
        var pressed = 0
        setContent {
            text = str("nav.surprise")
            CompositionLocalProvider(LocalSurpriseMe provides { pressed++ }) { AppBar(navItems = listOf("A", "B"), onSearch = {}, onProfile = {}) }
        }
        onNodeWithText(text).assertIsDisplayed().performClick()
        assertEquals(1, pressed)
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

    @Test fun `a page the pill opened is a surprise page and an ordinary one is not`() {
        // Another one and the kicker key off a non-empty run, so a plain detail page (empty) shows neither.
        assertTrue(surpriseDest(SurpriseAnswer("a", MediaKind.MOVIE), emptyList(), "n").let { (it as Dest.MovieDetail).surpriseShown.isNotEmpty() })
        assertTrue(Dest.MovieDetail("a", "n").surpriseShown.isEmpty())
        assertTrue(Dest.SeriesDetail("a", "n").surpriseShown.isEmpty())
    }
}
