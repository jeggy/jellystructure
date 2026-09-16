package dev.jellystructure.shared

// R252 (FR-R252-3) — set by web-static-server (224 FR-224-6) only when the image carries RAVILO_VERSION;
// `undefined` (absent) when it does not, which reads as null here and falls through to BuildInfo —
// the same shape as R225's window.__RAVILO_DEFAULT_SERVER__ read in ravilo-ui.
private fun jsRaviloVersion(): String? = js("window.__RAVILO_VERSION__")

internal actual fun runtimeVersionOverride(): String? = jsRaviloVersion()
