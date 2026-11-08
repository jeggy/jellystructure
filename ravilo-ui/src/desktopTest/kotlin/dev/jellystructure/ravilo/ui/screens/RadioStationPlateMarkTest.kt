package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.LiveTvChannel
import kotlin.test.Test
import kotlin.test.assertTrue

/** DEB-3113 (DEB-2628 FR-8) — the mark on the logo plate reads on a logo with a white background. */
@OptIn(ExperimentalTestApi::class)
class RadioStationPlateMarkTest {
    private val station = LiveTvChannel(
        channelId = "ch-1", name = "Station One", number = 1, order = 1, shown = true,
        logoUrl = "/api/tv/image/ch-1/logo", category = "Radio", hasGuide = false, kind = "radio",
    )

    @Test
    fun theMarkHasADarkPlateOverAWhiteLogo() = runComposeUiTest {
        setContent {
            RaviloTheme {
                Box(Modifier.testTag("plate")) {
                    StationPlate(station, 312.dp, 1f, logoImage = { _, m -> Box(m.background(Color.White)) })
                }
            }
        }
        val pixels = onNodeWithTag("plate").captureToImage().toPixelMap()
        val scale = pixels.width / 312f
        assertTrue(pixels[(156 * scale).toInt(), (156 * scale).toInt()].red > 0.95f, "the logo is drawn")
        // Inside the mark's plate, in the margin left of the glyph: 84 from the right, 54 from the bottom.
        val margin = pixels[((312 - 84) * scale).toInt(), ((312 - 54) * scale).toInt()]
        assertTrue(margin.red < 0.35f, "the mark's plate is dark over a white logo, read ${margin.red}")
        // The glyph's centre dot, in the middle of that plate: 56 from the right, 54 from the bottom.
        val dot = pixels[((312 - 56) * scale).toInt(), ((312 - 54) * scale).toInt()]
        assertTrue(dot.red > 0.9f, "the glyph is white on its plate, read ${dot.red}")
    }
}
