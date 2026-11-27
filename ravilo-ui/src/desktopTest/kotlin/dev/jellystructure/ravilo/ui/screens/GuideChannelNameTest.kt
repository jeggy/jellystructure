package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.AuroraColors
import dev.jellystructure.ravilo.ui.theme.LocalRaviloColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** DEB-3987 (DEB-2628 FR-7) — a long radio name in the guide's channel cell ends in an ellipsis on one line. */
@OptIn(ExperimentalTestApi::class)
class GuideChannelNameTest {
    private fun layout(name: String, isRadio: Boolean): TextLayoutResult {
        var result: TextLayoutResult? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalRaviloColors provides AuroraColors) {
                    Box(Modifier.width(110.dp)) { GuideChannelName(name, isRadio) { result = it } }
                }
            }
            waitForIdle()
        }
        return assertNotNull(result)
    }

    @Test fun `a long radio name is one ellipsized line`() {
        val r = layout("Abcdefghij Klmnopqrst", isRadio = true)
        assertEquals(1, r.lineCount)
        // Unfixed, Compose wraps at the space and the line ends at 10; fixed, the second word starts to show (12).
        assertTrue(r.getLineEnd(0, true) > "Abcdefghij ".length, "the second word starts to show")
        assertTrue(r.getLineEnd(0, true) < "Abcdefghij Klmnopqrst".length, "the line is cut")
    }

    @Test fun `a short radio name is not ellipsized`() {
        assertEquals(3, layout("Abc", isRadio = true).getLineEnd(0, true))
    }

    @Test fun `a tv channel name keeps up to two lines`() {
        assertTrue(layout("Abcdefghij Klmnopqrst", isRadio = false).lineCount <= 2)
    }
}
