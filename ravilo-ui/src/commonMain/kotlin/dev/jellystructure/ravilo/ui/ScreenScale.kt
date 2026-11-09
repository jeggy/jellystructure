package dev.jellystructure.ravilo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.jellystructure.shared.tv.snapUiScalePct

/**
 * DEB-3966 (FR-2) — the density a TV screen is drawn with at Screen scale [pct], or null when nothing is supplied.
 *
 * With `s = pct / 100` and `t = 1 − (1 − s) / 2`: every dp shrinks by `s` (`density × s`), and `fontScale × t / s`
 * makes text render at `t` of today's size — 97.5 % at 95, 95 % at 90, 92.5 % at 85. At 100 (or anything that
 * snaps to it) there is no provider at all, so the tree is today's byte for byte.
 */
internal fun screenScaleDensity(base: Density, pct: Int): Density? {
    val s = snapUiScalePct(pct) / 100f
    if (s >= 1f) return null
    val t = 1f - (1f - s) / 2f
    return Density(density = base.density * s, fontScale = base.fontScale * t / s)
}

/**
 * DEB-3966 (FR-3) — draws [content] at the admin's Screen scale on a TV platform, except where [enabled] is false
 * (the playing destinations). Must sit **inside** everything that classifies the device from window size × density
 * (FR-4: `isHandset`, `compact`, the layout family), which read the base density computed outside this call.
 */
@Composable
internal fun ScreenScale(pct: Int, enabled: Boolean, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scaled = if (enabled) remember(base.density, base.fontScale, pct) { screenScaleDensity(base, pct) } else null
    if (scaled == null) content() else CompositionLocalProvider(LocalDensity provides scaled, content = content)
}
