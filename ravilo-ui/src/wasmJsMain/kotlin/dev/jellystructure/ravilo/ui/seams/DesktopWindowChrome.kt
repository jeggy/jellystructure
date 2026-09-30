package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** R337 — no window of Ravilo's own here. */
@Composable
actual fun DesktopTitleStrip(modifier: Modifier, startControls: Boolean, drag: Boolean) {}

@Composable
actual fun WindowControlButtons(start: Boolean, modifier: Modifier, compact: Boolean) {}

@Composable
actual fun windowControlsWidth(start: Boolean, compact: Boolean): androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)

@Composable
actual fun DesktopWindowFrame() {}

actual fun showAboutWindow() {}

actual fun Modifier.windowDragArea(): Modifier = this

actual fun placeWindowControls(x: Float, y: Float) {}

@Composable
actual fun DesktopSettingsWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {}
