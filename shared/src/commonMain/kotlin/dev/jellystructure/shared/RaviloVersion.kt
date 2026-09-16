package dev.jellystructure.shared

/** R252 / 224 — the two headers every Ravilo client sends on every request, and the backend reads. */
object RaviloHeaders {
    const val VERSION = "X-Ravilo-Version"
    const val PLATFORM = "X-Ravilo-Platform"
}

/**
 * R252 (FR-R252-1/3) — the version this client reports: the value the platform serving it injected at
 * runtime (web only — 224 FR-224-6's `window.__RAVILO_VERSION__`, set by web-static-server from the
 * image's RAVILO_VERSION), else the constant generated into this build. Never a caller-supplied value.
 */
fun raviloVersion(): String = runtimeVersionOverride()?.trim()?.ifBlank { null } ?: BuildInfo.version

/** The platform-served override; `null` on every platform where nothing serves one. */
internal expect fun runtimeVersionOverride(): String?
