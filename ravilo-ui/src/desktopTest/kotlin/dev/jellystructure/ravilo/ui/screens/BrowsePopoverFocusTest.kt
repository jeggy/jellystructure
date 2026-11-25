package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jellystructure.ravilo.ui.theme.AuroraColors
import dev.jellystructure.ravilo.ui.theme.DaylightColors
import dev.jellystructure.ravilo.ui.theme.GraphiteColors
import dev.jellystructure.ravilo.ui.theme.LocalRaviloColors
import dev.jellystructure.ravilo.ui.theme.MidnightColors
import dev.jellystructure.ravilo.ui.theme.NoirColors
import dev.jellystructure.ravilo.ui.theme.RaviloColors
import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-4079 (R187, FR-RV-BROWSE1-5a) — a focused row in a browse popover is inverted, as the TV bar's focused tab is:
 * `colors.text` fill with `colors.background` ink, and readable on every theme.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class BrowsePopoverFocusTest {
    private val themes = mapOf(
        "Aurora" to AuroraColors, "Midnight" to MidnightColors, "Noir" to NoirColors,
        "Graphite" to GraphiteColors, "Daylight" to DaylightColors,
    )

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    @Test fun `the focused row meets 3 to 1 against the plate and 4_5 to 1 for its ink on every theme`() {
        for ((name, c) in themes) {
            val ink = popoverRowInk(c, 1f)
            assertEquals(c.text, ink.fill, "$name: the fill is colors.text")
            val plate = c.surfaceVariant
            assertTrue(contrast(ink.fill, plate) >= 3f, "$name: fill vs plate ${contrast(ink.fill, plate)}")
            for ((what, ink2) in listOf("label" to ink.label, "quiet" to ink.quiet, "mark" to ink.mark)) {
                assertEquals(c.background, ink2, "$name: the $what is colors.background")
                assertTrue(contrast(ink2, ink.fill) >= 4.5f, "$name: $what vs fill ${contrast(ink2, ink.fill)}")
            }
            // The count is 0.7 alpha of the background over the fill: it is the one line allowed to be quieter.
            val count = ink.count.compositeOver(ink.fill)
            assertEquals(0.7f, ink.count.alpha, 0.005f, "$name: the count is drawn at 0.7 alpha")
            assertTrue(contrast(count, ink.fill) >= 4.5f, "$name: count vs fill ${contrast(count, ink.fill)}")
        }
    }

    @Test fun `an unfocused row keeps its transparent fill and its resting ink`() {
        for ((name, c) in themes) {
            val ink = popoverRowInk(c, 0f)
            assertEquals(0f, ink.fill.alpha, 0.001f, "$name: transparent")
            assertEquals(c.text, ink.label, name)
            assertEquals(c.textSecondary, ink.quiet, name)
            assertEquals(c.textSecondary, ink.count, name)
            assertEquals(c.accent, ink.mark, name)
        }
    }

    private fun card(i: Int, genres: List<String>) = BrowseCard(
        card = MediaCard(id = "m$i", kind = MediaKind.MOVIE, title = "Stand-in $i", year = 2000 + i, genre = null,
            rating = null, posterUrl = null, backdropUrl = null),
        genres = genres, channels = emptyList(), quality = "HD", people = emptyList(),
    )

    @Test fun `the Genre popover paints its focused row in colors_text and an unfocused row on the plate`() {
        for ((name, c) in listOf("Aurora" to AuroraColors, "Daylight" to DaylightColors)) runComposeUiTest {
            val store = SeededBrowseStore(TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:1", { "token" }, platform = "tv"),
                seedQuery = null, seedMediaKind = "MOVIE", continueWatching = false)
            val cards = listOf(card(1, listOf("Action", "Comedy", "Drama")), card(2, listOf("Comedy", "Drama")), card(3, listOf("Drama")))
            var density = Density(1f)
            setContent {
                density = LocalDensity.current
                CompositionLocalProvider(LocalRaviloColors provides c) {
                    Box(Modifier.testTag("stage")) { FacetPopover(store, cards, BrowseFacetKey.GENRE, onClose = {}) }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            val labels = listOf("Action", "Comedy", "Drama").map { onAllNodesWithText(it)[0].fetchSemanticsNode().boundsInRoot }
                .sortedBy { it.top }
            val pm = onNodeWithTag("stage").captureToImage().toPixelMap()
            // 5 dp into the row's 10 dp left padding: left of the 18 dp check slot, so no glyph or label is under it.
            val x = (labels[0].left - density.density * (18f + 5f)).toInt()
            fun at(row: Int) = pm[x, ((labels[row].top + labels[row].bottom) / 2f).toInt()]
            assertEquals(c.text.red, at(0).red, 0.01f, "$name: the focused row is colors.text")
            assertEquals(c.text.green, at(0).green, 0.01f, "$name: the focused row is colors.text")
            assertEquals(c.surfaceVariant.red, at(1).red, 0.01f, "$name: an unfocused row is the plate")
            assertEquals(c.surfaceVariant.green, at(1).green, 0.01f, "$name: an unfocused row is the plate")
            assertEquals(c.surfaceVariant.blue, at(2).blue, 0.01f, "$name: an unfocused row is the plate")
        }
    }
}
