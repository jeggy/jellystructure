package dev.jellystructure.ravilo.ui.screens

import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.jellystructure.ravilo.ui.remote.OneRemote
import dev.jellystructure.ravilo.ui.remote.RemoteExtras
import dev.jellystructure.ravilo.ui.remote.RemoteModel
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * DEB-2660 (DEB-2628 FR-11) — the remote's radio shape: ⏯ and the volume block, LIVE in the seek bar's place, and no shuffle,
 * previous, next, repeat, skip, seek bar, *Up next* or *Audio & Subs*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-mdpi")
class OneRemoteRadioTest {
    @get:Rule val rule = createComposeRule()

    private val station = CastRemoteStatus(
        itemId = "ch-7", title = "Morning Show", kicker = "Station One", loaded = true, playing = true, radio = true, hasNext = true,
        nextUpSecs = 5, nextTitle = "Next programme",
    )

    private fun draw(status: CastRemoteStatus = station, actions: RecordingActions = RecordingActions(), unreachable: Boolean = false) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val model = RemoteModel(sessionId = null, placeName = "Stue", placeIcon = "speaker", status = status, kind = "radio", unreachable = unreachable, leavesOnStop = true)
        val remote = OneRemote(model, actions, RemoteExtras(), sessionVolume = { Text("rooms", modifier = androidx.compose.ui.Modifier.testTag("rooms-stub")) })
        rule.setContent { OneRemoteScreen(remote = remote, onBack = {}, onPlayAgain = {}, onPlayHere = { _, _, _ -> }, desktop = false) }
        rule.waitForIdle()
    }

    private fun absent(vararg tags: String) = tags.forEach { rule.onAllNodesWithTag(it).assertCountEquals(0) }

    @Test fun `a playing station shows LIVE and play-pause alone and the volume block`() {
        draw()
        rule.onNodeWithTag("remote-live").assertExists()
        rule.onNodeWithTag("remote-play-pause").assertExists()
        rule.onNodeWithTag("rooms-stub").assertExists()
        absent("remote-shuffle", "remote-prev", "remote-next-song", "remote-repeat", "remote-skip-back", "remote-skip-fwd", "remote-next", "remote-tracks")
    }

    @Test fun `a station never shows a seek bar or an up next card even if the report carries a countdown`() {
        draw()
        // The film shape's two seek skips are labelled with their length; none is drawn, and the countdown's *Up next* is not.
        rule.onAllNodesWithTag("remote-transport").assertCountEquals(1)
        absent("remote-skip-back", "remote-skip-fwd")
        assertEquals(0, rule.onAllNodesWithText("Next programme").fetchSemanticsNodes().size)
    }

    @Test fun `a press on play-pause pauses a playing station`() {
        val actions = RecordingActions()
        draw(actions = actions)
        rule.onNodeWithTag("remote-play-pause").performClick()
        assertEquals(listOf("pause"), actions.calls)
    }

    @Test fun `a station that could not play offers try again and no play here`() {
        draw(station.copy(playing = false, failed = true))
        rule.onNodeWithTag("remote-retry-radio").assertExists()
        rule.onNodeWithTag("remote-stop").assertExists()
    }
}
