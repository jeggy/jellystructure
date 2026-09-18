package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

actual val isWebPlatform: Boolean = false
actual val isIOSWebPlatform: Boolean = false

@Composable
actual fun rememberIsStandaloneWebApp(): Boolean = false

@Composable
actual fun rememberInstallPromptAvailable(): Boolean = false

actual fun triggerNativeInstall() {}

@Composable
actual fun rememberUpdateAvailable(): Boolean = false

actual fun reloadForUpdate() {}

actual fun installCardDismissed(): Boolean = true
actual fun dismissInstallCard() {}
