package dev.jellystructure.shared

// R252 — nothing serves a runtime version here; the compiled BuildInfo is the truth.
internal actual fun runtimeVersionOverride(): String? = null
