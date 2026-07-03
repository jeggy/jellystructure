package dev.jellystructure.ravilo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.jellystructure.shared.tv.Skin

val LocalRaviloColors = staticCompositionLocalOf { AuroraColors }
val LocalRaviloSkin   = staticCompositionLocalOf { Skin.AURORA }

class RaviloThemeState(initial: Skin) {
    var skin by mutableStateOf(initial)
    val colors get() = skin.colors()
}

@Composable
fun rememberRaviloTheme(initial: Skin = Skin.AURORA): RaviloThemeState =
    remember(initial) { RaviloThemeState(initial) }

@Composable
fun RaviloTheme(
    state: RaviloThemeState = rememberRaviloTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRaviloColors provides state.colors,
        LocalRaviloSkin provides state.skin,
    ) {
        content()
    }
}

object RaviloTheme {
    val colors: RaviloColors
        @Composable @ReadOnlyComposable get() = LocalRaviloColors.current
}
