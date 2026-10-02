package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.LocalServerMessages
import dev.jellystructure.ravilo.ui.seams.ServerMessageCard
import dev.jellystructure.ravilo.ui.seams.ServerMessageCardStyle
import dev.jellystructure.ravilo.ui.seams.ServerMessageOverVideo
import dev.jellystructure.ravilo.ui.seams.VideoOverApp
import dev.jellystructure.shared.tv.ServerMessageEnvelope
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R354 (FR-R354-10) — a Jellyfin dashboard message while the player is up is drawn above the player, keeps the D-pad
 * with the player, and a tap on it never reaches the player. On the web (a video layer over the app) the same message
 * goes to the layer above the video and comes back with its remaining time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class ServerMessageOverPlayerTest {
    @get:Rule val rule = createComposeRule()

    private val messages = MutableSharedFlow<ServerMessageEnvelope>(replay = 0, extraBufferCapacity = 8)
    private var playerTaps = 0

    @After fun reset() { VideoOverApp.covers = false }

    /** The app root's order: the page (here a stand-in player with a focused control), then the message host. */
    private fun setPlayerWithHost(overVideo: ServerMessageOverVideo? = null) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent {
            CompositionLocalProvider(LocalServerMessages provides messages) {
                Box(Modifier.fillMaxSize()) {
                    val playFR = remember { FocusRequester() }
                    Box(Modifier.fillMaxSize().testTag("player").pointerInput(Unit) { detectTapGestures { playerTaps++ } }) {
                        // A transport control at the bottom right: Up from it would reach a focusable toast top right.
                        Box(Modifier.align(Alignment.BottomEnd).size(60.dp).testTag("play").focusRequester(playFR).focusable())
                    }
                    ServerMessageHost(overVideo = overVideo)
                    LaunchedEffect(Unit) { playFR.requestFocus() }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false   // the toast's countdown must not run to its end inside waitForIdle
    }

    /** A state write from the test: let the main looper deliver it, then a few frames. */
    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(100)
    }

    private fun send() {
        assertTrue(messages.tryEmit(ServerMessageEnvelope(type = "server_message", text = "Dinner is ready", header = "Mum", timeoutMs = 10_000)))
        rule.mainClock.advanceTimeBy(500)
    }

    @Test fun `a message while the player is up is drawn above it and leaves the D-pad with the player`() {
        setPlayerWithHost()
        send()
        rule.onNodeWithText("Mum").assertIsDisplayed()
        rule.onNodeWithText("Dinner is ready").assertIsDisplayed()
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))   // never a focus target
        rule.onNodeWithTag("play").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("play").assertIsFocused()
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertIsDisplayed()   // still there: a key press never dismisses it

        // Above the player: the tap lands on the toast (it dismisses) and never on the player under it.
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertDoesNotExist()
        assertEquals(0, playerTaps)
        rule.onNodeWithTag("play").assertIsFocused()
    }

    @Test fun `the message leaves on its own at the end of its time`() {
        setPlayerWithHost()
        send()
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(9_000)
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertDoesNotExist()
    }

    @Test fun `while a video layer covers the app the message goes above the video, then comes back`() {
        val layer = FakeLayer()
        VideoOverApp.covers = true
        setPlayerWithHost(layer)
        send()
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertDoesNotExist()   // not drawn twice
        val card = layer.cards.single()
        assertEquals("Mum", card.header)
        assertEquals("Dinner is ready", card.text)
        assertEquals(10_000L, card.durationMs)

        VideoOverApp.covers = false   // a Compose overlay brought the canvas back on top
        settle()
        assertTrue(layer.cards.isEmpty())
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertIsDisplayed()

        VideoOverApp.covers = true
        settle()
        rule.onNodeWithTag(SERVER_MESSAGE_TOAST_TAG).assertDoesNotExist()
        layer.dismiss(layer.cards.single().id)   // a click on the DOM card
        settle()
        assertTrue(layer.cards.isEmpty())
    }

    private class FakeLayer : ServerMessageOverVideo {
        var cards: List<ServerMessageCard> = emptyList()
        var dismiss: (Long) -> Unit = {}
        override fun show(cards: List<ServerMessageCard>, style: ServerMessageCardStyle, onDismiss: (Long) -> Unit) {
            this.cards = cards
            dismiss = onDismiss
        }
        override fun hide() { cards = emptyList() }
    }
}
