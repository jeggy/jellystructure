package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

/** A browser tab is its own lifecycle (R293 non-goals): the app is always "on screen" here, and only
 *  FR-R293-4's backoff applies. */
@Composable
actual fun rememberAppOnScreen(): Boolean = true

@Composable
actual fun rememberDeviceStateProbe(): () -> String = { "web;-;-;-" }
