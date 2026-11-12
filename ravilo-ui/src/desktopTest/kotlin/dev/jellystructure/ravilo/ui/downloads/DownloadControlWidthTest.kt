package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.Sora
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-4070 (DEB-3995 FR-8 amended; acceptance 9) — in Danish and Faroese (and English) the Download control has one width through all five
 * states, so Play, which takes the rest of the row, never changes width while a download runs.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DownloadControlWidthTest {
    private val states = listOf(
        DownloadPhase.None, DownloadPhase.Queued, DownloadPhase.Running(42), DownloadPhase.Running(100), DownloadPhase.Downloaded, DownloadPhase.Failed(),
    )

    private fun widths(lang: String): Pair<List<Dp>, List<Dp>> {
        val controls = mutableListOf<Dp>()
        val plays = mutableListOf<Dp>()
        runComposeUiTest {
            val phase = mutableStateOf<DownloadPhase>(DownloadPhase.None)
            setContent {
                WithLocale(lang) {
                    val w = rememberDownloadControlWidth()
                    Row(Modifier.width(372.dp), horizontalArrangement = Arrangement.spacedBy(DownloadControlLayout.GAP_DP.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight().testTag("play"))
                        DownloadControl(phase.value, w, onClick = {}, modifier = Modifier.testTag("dl"))
                        Box(Modifier.size(DownloadControlLayout.MY_LIST_DP.dp))
                    }
                }
            }
            val density = this.density
            states.forEach { p ->
                phase.value = p
                waitForIdle()
                controls += with(density) { onNodeWithTag("dl").fetchSemanticsNode().size.width.toDp() }
                plays += with(density) { onNodeWithTag("play").fetchSemanticsNode().size.width.toDp() }
            }
        }
        return controls to plays
    }

    @Test fun `the control and Play keep their width through every state in en da and fo`() {
        listOf("en", "da", "fo").forEach { lang ->
            val (controls, plays) = widths(lang)
            assertEquals(1, controls.toSet().size, "$lang: the control's width changed with its state: $controls")
            assertEquals(1, plays.toSet().size, "$lang: Play's width changed with the control's state: $plays")
            assertTrue(plays.first() > controls.first(), "$lang: Play stays wider than the secondary control (${plays.first()} vs ${controls.first()})")
        }
    }

    @Test fun `phone Play Download and My List fit at 360 and 412 dp with stable Play width`() {
        for (screen in listOf(360, 412)) for (lang in listOf("en", "da", "fo")) runComposeUiTest {
            val phase = mutableStateOf<DownloadPhase>(DownloadPhase.None)
            val resume = mutableStateOf(false)
            setContent {
                WithLocale(lang) {
                    val width = rememberDownloadControlWidth()
                    val playWidth = DownloadControlLayout.playWidthDp(screen - 40f, width.value).dp
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val font = Sora
                    val fullResume = str("action.resume_mins_left", mapOf("resume" to str("action.resume"), "n" to "123"))
                    val label = if (resume.value) DownloadControlLayout.resumeLabel(fullResume, str("action.resume"), playWidth.value) {
                        with(density) { measurer.measure(it, TextStyle(fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)).size.width.toDp().value }
                    } else str("action.play")
                    Row(Modifier.width((screen - 40).dp), horizontalArrangement = Arrangement.spacedBy(DownloadControlLayout.GAP_DP.dp)) {
                        Box(Modifier.width(playWidth).height(44.dp).testTag("play")) { Text(label, maxLines = 1) }
                        DownloadControl(phase.value, width, onClick = {}, modifier = Modifier.testTag("dl"))
                        MyListIconButton(false, str("nav.my_list"), onClick = {}, modifier = Modifier.testTag("list"))
                    }
                }
            }
            var initialPlayWidth = -1f
            for (p in states) for (isResume in listOf(false, true)) {
                phase.value = p
                resume.value = isResume
                waitForIdle()
                val play = onNodeWithTag("play").fetchSemanticsNode().boundsInRoot
                val download = onNodeWithTag("dl").fetchSemanticsNode().boundsInRoot
                val list = onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
                assertTrue(play.right <= download.left && download.right <= list.left, "$screen dp $lang: action order")
                assertTrue(list.right <= with(this.density) { (screen - 40).dp.toPx() } + 1f, "$screen dp $lang: My List leaves the screen")
                if (initialPlayWidth < 0f) initialPlayWidth = play.width
                assertTrue(kotlin.math.abs(play.width - initialPlayWidth) < 1f, "$screen dp $lang: Play width changed")
            }
        }
    }
}
