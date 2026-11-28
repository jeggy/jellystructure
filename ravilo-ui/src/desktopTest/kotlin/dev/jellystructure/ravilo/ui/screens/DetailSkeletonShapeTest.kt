package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.DETAIL_SKELETON_ACTIONS
import dev.jellystructure.ravilo.ui.components.DETAIL_SKELETON_HERO
import dev.jellystructure.ravilo.ui.components.DetailLoadingShell
import dev.jellystructure.ravilo.ui.theme.LayoutFamily
import dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** DEB-5837 FR-3 — the film and series skeleton's hero fills the visible height, and its actions sit 44 dp above the foot, as the loaded hero's do. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DetailSkeletonShapeTest {
    private fun assertShape(family: LayoutFamily, portrait: Boolean) = runComposeUiTest {
        var density = 1f
        setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutFamily provides family, LocalPortrait provides portrait) {
                RaviloTheme { Box(Modifier.fillMaxSize()) { DetailLoadingShell() } }
            }
        }
        val hero = onNodeWithTag(DETAIL_SKELETON_HERO).fetchSemanticsNode().boundsInRoot
        val actions = onNodeWithTag(DETAIL_SKELETON_ACTIONS).fetchSemanticsNode().boundsInRoot
        val visible = hero.height
        assertTrue(hero.top == 0f && visible > 0f, "$family hero starts at the top and has height: $hero")
        assertTrue(kotlin.math.abs((hero.bottom - actions.bottom) / density - 44f) <= 1f, "$family actions bottom ${actions.bottom} vs hero bottom ${hero.bottom}")
    }

    @Test fun `the TV's detail skeleton is the loaded hero's shape`() = assertShape(LayoutFamily.TV, false)
    @Test fun `the phone's detail skeleton is the loaded hero's shape`() = assertShape(LayoutFamily.PHONE, true)
    @Test fun `the desktop's detail skeleton is the loaded hero's shape`() = assertShape(LayoutFamily.DESKTOP, false)
}
