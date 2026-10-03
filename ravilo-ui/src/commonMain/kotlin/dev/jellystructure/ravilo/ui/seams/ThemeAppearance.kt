package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * R338 (FR-R338-4) — this device's own light/dark: `true` dark, `false` light, `null` when the platform gives none to
 * read. Live: a change recomposes with the new answer — nothing restarts. Whether the device *uses* it is not this
 * seam's call: a TV-layout device is dark-only whatever its system says (D6), which `RaviloApp` decides.
 */
@Composable
expect fun systemDarkAppearance(): Boolean?

/**
 * R338 — the system's own chrome follows the **resolved** theme, not the system: the status and navigation bar icons
 * on a phone, the window's appearance (traffic lights, menus) on a Mac. Nothing where there is no such chrome.
 * R338 amendment (2026-10-03) — [background] is the resolved theme's page colour: the web app paints the bands around its
 * canvas (an iPhone's status bar and home indicator) in it.
 */
@Composable
expect fun SystemBarsAppearance(light: Boolean, background: Color)
