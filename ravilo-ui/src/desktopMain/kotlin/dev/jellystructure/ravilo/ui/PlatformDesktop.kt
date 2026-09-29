package dev.jellystructure.ravilo.ui

/** R328 (D3) — the TV layout driven by a keyboard and a mouse; never the TV's key-only paths (R256). */
actual val isTvPlatform: Boolean = false

/** R328 — no Chromecast sender yet; R330 gives the Mac its own. */
actual val hasCastSdk: Boolean = false
