package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-2496 (R350 FR-R350-7, amended for DEB-2495) — a paused-to-playing edge re-arms the chrome hide with no wake, and a
 * pause holds. The timer body mirrors `PlayerScreenState.chromeHideTimer`: wait 3.6 s, then hide unless paused.
 */
@OptIn(ExperimentalTestApi::class)
class ChromeHideEffectTest {
    private class Fake {
        var revision by mutableLongStateOf(1L)
        var playing by mutableStateOf(false)
        var chromeVisible by mutableStateOf(true)
        var runs = 0
        suspend fun timer() {
            if (revision == 0L) return
            runs++
            delay(3_600)
            if (playing) chromeVisible = false
        }
    }

    @Test
    fun `play with no wake hides the chrome 3600 ms later and a pause with no wake keeps it`() = runComposeUiTest {
        val f = Fake()
        mainClock.autoAdvance = false
        setContent { ChromeHideEffect(f.revision, f.playing) { f.timer() } }
        mainClock.advanceTimeBy(10_000)
        assertTrue(f.chromeVisible, "paused: the first run held the chrome")

        f.playing = true // the cast road: no chromeRevision bump
        mainClock.advanceTimeBy(100)
        assertTrue(f.chromeVisible)
        mainClock.advanceTimeBy(3_700)
        assertFalse(f.chromeVisible, "resumed with no wake: hidden after 3.6 s")

        f.chromeVisible = true
        f.playing = false
        mainClock.advanceTimeBy(10_000)
        assertTrue(f.chromeVisible, "paused again with no wake: the chrome stays")
    }

    @Test
    fun `a toggle that changes both keys restarts the timer once`() = runComposeUiTest {
        val f = Fake()
        mainClock.autoAdvance = false
        setContent { ChromeHideEffect(f.revision, f.playing) { f.timer() } }
        mainClock.advanceTimeBy(100)
        val before = f.runs
        f.revision++
        f.playing = true
        mainClock.advanceTimeBy(100)
        assertEquals(before + 1, f.runs)
    }

    @Test
    fun `no timer before the first-frame latch`() = runComposeUiTest {
        val f = Fake().also { it.revision = 0L }
        mainClock.autoAdvance = false
        setContent { ChromeHideEffect(f.revision, f.playing) { f.timer() } }
        f.playing = true
        mainClock.advanceTimeBy(10_000)
        assertTrue(f.chromeVisible)
        assertEquals(0, f.runs)
    }
}
