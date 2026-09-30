package dev.jellystructure.ravilo.ui

/** R328 (D3) — the TV layout driven by a keyboard and a mouse; never the TV's key-only paths (R256). */
actual val isTvPlatform: Boolean = false

actual val isDesktopPlatform: Boolean = true

/** R330 (dev review 9) — the Mac speaks Cast v2 itself (`:ravilo-castv2`), so R324's *needs the Android app* line is absent. */
actual val hasCastSdk: Boolean = true
