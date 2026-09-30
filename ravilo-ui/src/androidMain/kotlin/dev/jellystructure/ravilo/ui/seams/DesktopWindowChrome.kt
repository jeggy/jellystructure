package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** R337 — no window of Ravilo's own here. */
@Composable
actual fun DesktopTitleStrip(modifier: Modifier) {}

@Composable
actual fun DesktopWindowFrame() {}

actual fun showAboutWindow() {}

actual fun Modifier.windowDragArea(): Modifier = this

actual fun placeWindowControls(x: Float, y: Float) {}
