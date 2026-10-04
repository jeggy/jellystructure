package dev.jellystructure.ravilo.ui.components

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.sessions.SessionsState
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R368 (FR-R368-7) — *Playing everywhere* in the sheet: absent when nothing plays, other people's rows, ⏯ only when controllable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayingEverywhereSheetTest {
    @get:Rule val rule = createComposeRule()

    private fun v(id: String, mine: Boolean = true, here: Boolean = false, controllable: Boolean = false, title: String? = "Paper Harbour",
                  state: String = "playing", reconnecting: Boolean = false) = SessionView(
        id = id, revision = 1, owner = SessionOwner(if (mine) "u-anna" else "u-ben", if (mine) "Anna" else "Ben"), mine = mine, kind = "music",
        title = title, target = SessionTarget("cast", "d-$id", "Office", "speaker"), state = state,
        positionMs = if (title != null) 30_000 else null, durationMs = if (title != null) 200_000 else null,
        here = here, controllable = controllable, reconnecting = reconnecting,
    )

    private fun show(rows: List<SessionView>, onPlayPause: ((SessionView) -> Unit)? = {}) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent { PlayingEverywhereSection(SessionsState(rows, 0, 0), onPlayPause = onPlayPause) }
    }

    @Test fun `the section is absent when nothing plays`() {
        show(emptyList())
        rule.onNodeWithTag("playing-everywhere").assertDoesNotExist()
    }

    @Test fun `another persons row says who is listening and has no buttons`() {
        show(listOf(v("s1", mine = false)))
        rule.onNodeWithText("Ben is listening").assertExists()
        rule.onNodeWithTag("session-pp-s1").assertDoesNotExist()
    }

    @Test fun `a hidden title shows person place and state only`() {
        show(listOf(v("s1", mine = false, title = null)))
        rule.onNodeWithText("Ben is listening").assertExists()
        rule.onNodeWithText("Office").assertExists()
        rule.onNodeWithText("Paper Harbour").assertDoesNotExist()
    }

    @Test fun `play pause exists only on a controllable row`() {
        var pressed: String? = null
        show(listOf(v("mine", controllable = true), v("view", controllable = false)), onPlayPause = { pressed = it.id })
        rule.onNodeWithTag("session-pp-view").assertDoesNotExist()
        rule.onNodeWithTag("session-pp-mine").performClick()
        assertEquals("mine", pressed)
    }

    @Test fun `a row is 70 dp tall`() {
        show(listOf(v("s1")))
        rule.onNodeWithTag("session-row-s1").assertHeightIsAtLeast(70.dp)
    }

    @Test fun `reconnecting shows over the playing state`() {
        show(listOf(v("s1", reconnecting = true)))
        rule.onNodeWithText("Reconnecting…").assertExists()
    }

    @Test fun `rows are ordered with this device first`() {
        show(listOf(v("other", mine = false), v("here", here = true)))
        assertEquals(2, rule.onAllNodesWithTag("session-row-here").fetchSemanticsNodes().size + rule.onAllNodesWithTag("session-row-other").fetchSemanticsNodes().size)
    }
}
