package dev.jellystructure.ravilo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloThemes
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.resolveTheme

val LocalRaviloColors = staticCompositionLocalOf { AuroraColors }
/** The drawn theme's family ([ThemeId.family]) — what every "Noir or not" branch reads. */
val LocalRaviloSkin   = staticCompositionLocalOf { Skin.AURORA }
/** R338 — the drawn theme itself. */
val LocalRaviloTheme  = staticCompositionLocalOf { ThemeId.AURORA }

/** R338 — the viewer's theme settings as the server resolved them (FR-R338-3). */
data class ThemeSettings(val follow: Boolean, val light: String, val dark: String, val single: String) {
    companion object {
        /** From a config: null when the server predates R338 (no `theme_follow`), and the app draws [RaviloConfig.effectiveSkin]. */
        fun of(cfg: RaviloConfig): ThemeSettings? {
            val follow = cfg.themeFollow ?: return null
            return ThemeSettings(
                follow = follow,
                light = cfg.themeLight ?: RaviloThemes.DAYLIGHT,
                dark = cfg.themeDark ?: RaviloThemes.fromSkin(cfg.effectiveSkin()),
                single = cfg.theme ?: RaviloThemes.fromSkin(cfg.effectiveSkin()),
            )
        }
    }
}

/**
 * What the app draws in. R338: the viewer's [settings] (synced across devices) and this device's appearance
 * ([deviceDark]: `null` = dark-only, D6) resolve to one [theme]; a server from before R338 sends no settings and the
 * app draws [skin], as it always did.
 */
class RaviloThemeState(initialSkin: Skin, initialSettings: ThemeSettings? = null) {
    var skin by mutableStateOf(initialSkin)
    var settings by mutableStateOf(initialSettings)
    var deviceDark by mutableStateOf<Boolean?>(null)

    val theme: ThemeId
        get() = settings?.let { s -> ThemeId.of(resolveTheme(s.follow, s.light, s.dark, s.single, deviceDark)) }
            ?: ThemeId.fromSkin(skin)
    val colors get() = theme.colors()

    /** A fresh config from the server (or its cached copy). */
    fun apply(cfg: RaviloConfig) {
        skin = cfg.effectiveSkin()
        settings = ThemeSettings.of(cfg)
    }
}

@Composable
fun rememberRaviloTheme(initial: Skin = Skin.AURORA, initialSettings: ThemeSettings? = null): RaviloThemeState =
    remember(initial) { RaviloThemeState(initial, initialSettings) }

@Composable
fun RaviloTheme(
    state: RaviloThemeState = rememberRaviloTheme(),
    content: @Composable () -> Unit,
) {
    val theme = state.theme
    CompositionLocalProvider(
        LocalRaviloColors provides theme.colors(),
        LocalRaviloSkin provides theme.family,
        LocalRaviloTheme provides theme,
    ) {
        content()
    }
}

/**
 * R338 (FR-R338-2) — a surface that sits on artwork or video keeps dark tokens in a light theme: the film player and
 * its sheets, and the hero. In a dark theme it changes nothing; in a light one it draws with Aurora's tokens, the dark
 * look the mockup gives these surfaces (`html[data-appearance="light"] .hcard, .mp`).
 */
@Composable
fun KeepDark(content: @Composable () -> Unit) {
    if (!LocalRaviloColors.current.isLight) { content(); return }
    CompositionLocalProvider(
        LocalRaviloColors provides AuroraColors,
        LocalRaviloSkin provides Skin.AURORA,
        LocalRaviloTheme provides ThemeId.AURORA,
    ) { content() }
}

object RaviloTheme {
    val colors: RaviloColors
        @Composable @ReadOnlyComposable get() = LocalRaviloColors.current
}
