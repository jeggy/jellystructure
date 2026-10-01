package dev.jellystructure.ravilo.ui.seams

/**
 * R342 (dev review 4) — the mode the screen shows: `inMusic`, not the stored wish. `RaviloApp` calls it once at first
 * composition and on every switch. On the Mac the running Dock icon follows it. Everywhere else it does nothing: the
 * phone, the TVs, the web app and Linux keep one icon (Q5).
 */
expect fun reportListeningMode(music: Boolean)
