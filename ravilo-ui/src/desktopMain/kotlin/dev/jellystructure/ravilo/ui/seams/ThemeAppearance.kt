package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import dev.jellystructure.ravilo.ui.desktop.DesktopAppearance

/** R338 — the Mac's `AppleInterfaceStyle` or Linux's Settings portal ([DesktopAppearance]); null until known. */
@Composable
actual fun systemDarkAppearance(): Boolean? = DesktopAppearance.dark.collectAsState().value

/** R338 — on the Mac the window takes the resolved theme's appearance, so its traffic lights and menus match it. */
@Composable
actual fun SystemBarsAppearance(light: Boolean) {
    LaunchedEffect(light) { DesktopAppearance.applyToWindow(light) }
}
