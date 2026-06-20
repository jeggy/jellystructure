# Phase R29 — TV control-plane contract review fixes (FR-RV29)

**Status:** ✓ Done (2026-06-21)

## Problem
A focused review of the "R01–R28 done" Ravilo surface found regressions/gaps against done phases —
two of them user-visible and silently swallowed by `runCatching`, plus contract drift and a latent
crash. Root cause for the two bugs was amplified by the server parsing JSON in strict mode.

## What was built

### Server JSON hardening
- `Server.kt` `ContentNegotiation` now installs `json(Json { ignoreUnknownKeys = true })`, matching
  the 16 other `Json` instances in the repo. Strict parsing had been turning any extra key into a 400.

### Bug 1 — `playback/stop` always 400'd (R08 regression)
- `TvApiClient.stopPlayback()` encoded a `PlaybackProgressRequest` (carries `is_paused`) while the
  route decodes `PlaybackStopRequest`. The mismatch threw under strict JSON, so Jellyfin never saw the
  stop event (Now Playing lingered) and the exact resume position was lost (only as fresh as the 10s
  heartbeat). Now encodes `PlaybackStopRequest`.

### Bug 2 — on-device viewer settings never saved (R15 regression)
- `TvApiClient.putSettings()` PUT an entire `RaviloConfig` to `/api/tv/settings`, but the route decodes
  a small `ViewerSettingsRequest` (`skin` / `show_continue_progress` / `tile_shape`) — and
  `RaviloConfig` serializes `default_skin`, not `skin`, so the change was dropped even with lenient
  JSON. Replaced with `TvApiClient.putViewerSettings(skin?, showContinueProgress?, tileShape?)` posting
  a `ViewerSettingsRequest`; `SettingsScreen` calls it with only the changed field.
- `ViewerSettingsRequest` is **promoted to `:shared`** (`Models.kt`) so client and server share one
  definition (was a `private` server-side class).

### Viewer skin override (correctness)
- New `RaviloConfig.viewerSkinOverride: Skin? = null` (`@SerialName("viewer_skin_override")`). A
  viewer's skin choice is stored here, **never in `defaultSkin`**, so a later operator change to the
  default still reaches viewers who never picked a skin.
- `RaviloConfig.effectiveSkin()` centralizes resolution: `if (allowSkinOverride) viewerSkinOverride ?:
  defaultSkin else defaultSkin`. Used by `RaviloApp` (theme apply) and `SettingsScreen` (active state).
- `RaviloConfigService.applyViewerSettings()` writes `viewerSkinOverride`; the web admin save preserves
  the field so an operator config write does not wipe a viewer's choice.

### Cleanups
- **`session_id` removed** from `PlaybackProgressRequest` / `PlaybackStopRequest` and the
  `reportProgress` / `stopPlayback` signatures. It was synthesized client-side (`itemId + "_" +
  expiresAt`) and ignored by the server, which keys progress/stop off the device + `itemId`.
- **`FocusGrid` empty-row/empty-grid guard.** `move`/`moveTo` did `coerceIn(0, grid[row].size - 1)`,
  which throws on a zero-length row (`size - 1 == -1`); now an empty row pins its column to 0 and an
  empty grid is a no-op.
- **`autoAdvanceSeconds` clamp widened to `[0, 120]`** in `normalize()` (was `[0, 30]`, silently
  capping admin values) to match the R26 spec.

### Doc reconciliation
- R26 spec corrected: `heroHeightPct` is Int percent `[30, 70]` (not fractional `[0.3, 1.0]`); the
  never-implemented UUID-v4 id-backfill claim removed (blank ids are rejected with 400).
- `:ravilo-player` marked **deferred / not built** in `plan.md`, `constitution.md`, and the
  requirements README intro, so all docs agree with `STATUS.md` (Android uses direct ExoPlayer/Media3).

## Not in scope
- **WASM/browser direct-play (R14 TODO):** `PlaybackService.startPlayback` still returns a static MKV
  direct-play URL and ignores `ClientCapabilities`, so the browser target likely can't play much.
  Tracked as the existing R14 codec-negotiation TODO — untouched here.
- Subtitle `.ass`/`streamIndex` mapping for image-based subs (PGS/VobSub) — noted, not addressed.

## Verification
- Build: `:shared`, server `linuxX64`, `:ravilo-ui` wasmJs, and `wasmJs` admin all compile.
- Stop: play → stop returns 200; Jellyfin Now Playing clears; resume equals the exact stop point.
- Settings: change skin / tile shape / continue-progress on the TV; reload and confirm it persisted.
- Skin override: set a viewer skin, then change operator `defaultSkin`; the override user keeps theirs,
  a never-overridden user picks up the new default.
- FocusGrid driven with a zero-length row throws nothing.
