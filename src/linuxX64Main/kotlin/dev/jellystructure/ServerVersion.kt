package dev.jellystructure

/**
 * Phase 224 (FR-224-1) — the version this backend reports: in `/api/health` and in its own Jellyfin
 * `MediaBrowser` header (which used to be a literal `0.1.0`). The runtime override, `JELLYSTRUCTURE_VERSION`
 * — set by the image from publish.yml's BUILD_VERSION — wins over the compiled-in [BuildInfo], because
 * inside an image BuildInfo is always "dev" (no .git in the build context, by design: FR-224-6). A dev
 * checkout has no override and reports its `git describe`.
 */
object ServerVersion {
    val current: String by lazy { env("JELLYSTRUCTURE_VERSION", "").trim().ifBlank { null } ?: BuildInfo.version }
}
