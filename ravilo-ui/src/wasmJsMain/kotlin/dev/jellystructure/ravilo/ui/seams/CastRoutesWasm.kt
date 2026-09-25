package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

/** R265 — a browser has no Cast SDK sender, so it never lists a Chromecast (FR-R265-3: Android only). */
@Composable
actual fun rememberCastRoutes(appId: String?, discovering: Boolean): List<CastRoute> = emptyList()
