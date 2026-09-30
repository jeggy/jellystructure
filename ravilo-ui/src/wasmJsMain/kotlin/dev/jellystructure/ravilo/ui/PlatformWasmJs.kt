package dev.jellystructure.ravilo.ui

/** R234 — ravilo-web is never a TV. */
actual val isTvPlatform: Boolean = false

actual val isDesktopPlatform: Boolean = false

/** R324 — no browser has a Cast sender (R265). */
actual val hasCastSdk: Boolean = false

actual val isMacPlatform: Boolean = false
