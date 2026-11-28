package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.theme.DaylightColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5838 (FR-5) — every control of the phone's film player has a name, and the seek bar is a slider to a screen reader. */
@OptIn(ExperimentalTestApi::class)
class HandsetChromeSemanticsTest {
    private val seeks = mutableListOf<Long>()
    private var ends = 0

    private fun androidx.compose.ui.test.ComposeUiTest.chrome(playing: Boolean = true, positionMs: Long = 100_000L) = setContent {
        HandsetPlayerChrome(
            colors = DaylightColors, itemTitle = "Stand-in Title", itemKicker = null,
            positionMs = positionMs, durationMs = 3_600_000L, bufferedMs = 0L, scrubbing = false, scrubPos = 0L,
            isPlaying = playing, railItems = emptyList(), stallActive = false, seekMomentActive = false,
            showRotate = true, landscapeForced = false, onPictureInPicture = {},
            onBack = {}, onRotate = {}, onSkipBack = {}, onPlayPause = {}, onSkipFwd = {}, onRail = {},
            onSeekStart = { seeks += it }, onSeekDrag = {}, onSeekEnd = { ends++ },
        )
    }

    @Test fun `every control is named, though its label is hidden or only a number`() = runComposeUiTest {
        chrome()
        for (name in listOf("Back", "Picture in picture", "Back 10 seconds", "Pause", "Forward 30 seconds"))
            onNode(hasContentDescription(name)).assertExists("no node named \"$name\"")
        onNode(hasContentDescription("Position")).assertExists()
    }

    @Test fun `a paused film names its centre button Play`() = runComposeUiTest {
        chrome(playing = false)
        onNode(hasContentDescription("Play")).assertExists()
        onNode(hasContentDescription("Pause")).assertDoesNotExist()
    }

    @Test fun `the seek bar states its position, has a range, and increase and decrease step like the buttons`() = runComposeUiTest {
        chrome()
        val node = onNode(hasContentDescription("Position")).fetchSemanticsNode()
        assertEquals("1:40 of 1:00:00", node.config.getOrNull(SemanticsProperties.StateDescription))
        val range = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!
        assertEquals(100_000f, range.current); assertEquals(0f..3_600_000f, range.range)
        val set = node.config[SemanticsActions.SetProgress].action!!
        set(150_000f); set(50_000f)
        assertEquals(listOf(130_000L, 90_000L), seeks)   // +30 s, then -10 s
        assertEquals(2, ends)
    }

    @Test fun `steps stay inside the film and the chrome hides unless a screen reader is on`() {
        assertEquals(0L, handsetSeekStep(4_000L, 60_000L, -1))
        assertEquals(60_000L, handsetSeekStep(50_000L, 60_000L, 1))
        assertTrue(handsetChromeMayHide(screenReaderOn = false))
        assertFalse(handsetChromeMayHide(screenReaderOn = true))
    }
}
