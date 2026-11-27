package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.jellystructure.shared.tv.RemotePlayer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-5843 (FR-2) — what counts as input for "Are you still watching?": a key, a tap and a remote command reset the count;
 * a pointer move does not; a play the app advanced to leaves it alone and a hand-started one resets it, so the count
 * survives an advance.
 */
@OptIn(ExperimentalTestApi::class)
class StillWatchingInputTest {
    /** Two unattended advances counted, then the content under test. */
    private fun primed(): StillWatchingTracker = StillWatchingTracker(tvClass = false).also {
        it.decide(); it.decide()
        assertEquals(2, it.unattended)
    }

    private fun ComposeUiTest.show(tracker: StillWatchingTracker) = setContent {
        Box(Modifier.size(100.dp).testTag("p").observeStillWatchingInput(tracker).focusable())
    }

    @Test
    fun `a key press resets the count`() = runComposeUiTest {
        val t = primed(); show(t)
        onNodeWithTag("p").requestFocus()
        onRoot().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        assertEquals(0, t.unattended)
    }

    @Test
    fun `a tap resets the count`() = runComposeUiTest {
        val t = primed(); show(t)
        onRoot().performTouchInput { click() }
        assertEquals(0, t.unattended)
    }

    @Test
    fun `a pointer move alone does not`() = runComposeUiTest {
        val t = primed(); show(t)
        onRoot().performMouseInput { moveTo(center); moveTo(topLeft) }
        assertEquals(2, t.unattended)
    }

    @Test
    fun `a click resets the count`() = runComposeUiTest {
        val t = primed(); show(t)
        onRoot().performMouseInput { click(center) }
        assertEquals(0, t.unattended)
    }

    @Test
    fun `a remote command resets the count`() {
        val t = primed()
        val seen = mutableListOf<String>()
        val inner = object : RemotePlayer {
            override fun play() { seen += "play" }
            override fun pause() {}
            override fun toggle() {}
            override fun stop() {}
            override fun seekTo(positionMs: Long) {}
            override fun seekBy(deltaMs: Long) {}
            override fun next() { seen += "next" }
            override fun previous() {}
            override fun setVolume(level: Float, muted: Boolean) {}
        }
        val remote = inputCounting(inner) { t.onInput() }
        remote.next()
        assertEquals(0, t.unattended)
        assertEquals(listOf("next"), seen, "the command still reaches the player")
    }

    @Test
    fun `the count survives an advance and a hand-started play resets it`() {
        val t = StillWatchingTracker(tvClass = false)
        t.onPlayStarted("e1")           // the viewer pressed Play
        t.willAdvanceTo("e2"); assertEquals(false, t.decide()); t.onPlayStarted("e2")
        t.willAdvanceTo("e3"); assertEquals(false, t.decide()); t.onPlayStarted("e3")
        assertEquals(2, t.unattended, "two advances, no input since the Play")
        t.onPlayStarted("other")        // a rail pick, a cast or a hand-over
        assertEquals(0, t.unattended)
    }
}
