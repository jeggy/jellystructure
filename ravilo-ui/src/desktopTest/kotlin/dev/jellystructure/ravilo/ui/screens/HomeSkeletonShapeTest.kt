package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.HOME_SKELETON_FIRST_ROW
import dev.jellystructure.ravilo.ui.components.HomeLoadingShell
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.components.TileVariant
import dev.jellystructure.ravilo.ui.theme.LayoutFamily
import dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.raviloRowGap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-5837 FR-3 — Home's skeleton measures what the loaded Home measures: the first row's top and height, within 1 dp. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class HomeSkeletonShapeTest {
    private val loadedRow = "loaded-first-row"

    /** The loaded page's first row at the default hero share: the hero band, the row gap, then a row of poster tiles. */
    @Composable
    private fun LoadedFirstRow() {
        Column {
            Spacer(Modifier.height(homeHeroHeight()))
            Spacer(Modifier.height(raviloRowGap))
            StaticContentRow(
                title = "Row",
                items = List(8) { it },
                modifier = Modifier.testTag(loadedRow),
            ) { _, i, _ -> Tile(title = "Title $i", posterUrl = null, variant = TileVariant.POSTER) }
        }
    }

    private fun assertSameShape(family: LayoutFamily, portrait: Boolean) = runComposeUiTest {
        var density = 1f
        var skeleton = false
        setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutFamily provides family, LocalPortrait provides portrait) {
                RaviloTheme { if (skeleton) HomeLoadingShell() else LoadedFirstRow() }
            }
        }
        val loaded = onNodeWithTag(loadedRow).fetchSemanticsNode().boundsInRoot
        skeleton = true
        setContent {
            CompositionLocalProvider(LocalLayoutFamily provides family, LocalPortrait provides portrait) {
                RaviloTheme { HomeLoadingShell() }
            }
        }
        val first = onNodeWithTag(HOME_SKELETON_FIRST_ROW).fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(first.top - loaded.top) / density <= 1f, "$family top: skeleton ${first.top} vs loaded ${loaded.top}")
        assertTrue(kotlin.math.abs(first.height - loaded.height) / density <= 1f, "$family height: skeleton ${first.height} vs loaded ${loaded.height}")
    }

    @Test fun `the TV's first skeleton row is the loaded first row`() = assertSameShape(LayoutFamily.TV, false)
    @Test fun `the phone's first skeleton row is the loaded first row`() = assertSameShape(LayoutFamily.PHONE, true)
    @Test fun `the desktop's first skeleton row is the loaded first row`() = assertSameShape(LayoutFamily.DESKTOP, false)
}
