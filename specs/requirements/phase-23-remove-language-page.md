# Phase 23 — Remove the Language Page (FR-RL1)

## Problem
The sidebar has a **Language** page that brings almost no value. Its only real control (the global
fallback language) already lives — or will live — in Settings. Remove the page and its nav entry
entirely.

## Current state (as-is)
- Nav: `Shell.kt` `NAV` list contains `NavLink("/language", "Language", "language")` under the
  "Setup" group, plus a `"language"` icon in `ICONS`.
- Route: `App.handleRoute` maps `route == "/language" -> renderLanguage(...)`.
- File: `ui/Language.kt` (`renderLanguage`, ~226 lines) — settings + live in-WASM preview.
- Import in `Main.kt`: `import dev.jellystructure.ui.renderLanguage`.
- Backend: `LanguageRoutes.kt` exposes `GET/PATCH /api/language/settings` (just `fallback_language`),
  wired in `Server.kt` as `languageRoutes(configStore)`.

## Requirements
1. Remove the `NavLink("/language", …)` entry from `NAV` in `Shell.kt`. (Keep or drop the `"language"`
   ICON entry depending on reuse — drop if unused after removal.)
2. Remove the `/language` route case from `App.handleRoute` and the `renderLanguage` import in
   `Main.kt`.
3. Delete `ui/Language.kt`.
4. Ensure the global **fallback language** remains editable — it moves to Settings → Metadata section
   (see [`phase-18`](phase-18-settings-page-cleanup.md)), using the Phase 11 language picker. If
   Settings already covers `fallback_language` (it does, via `fallback-language`), no data is lost.
5. Backend `LanguageRoutes` (`/api/language/settings`) becomes unused by the frontend. **Decision:**
   leave the endpoint in place for now (harmless, still backs nothing critical) OR remove it and its
   `Server.kt` wiring. Recommended: remove it to avoid dead routes, since Settings reads/writes the
   full config via `/api/config`. Confirm nothing else calls it before deleting.
6. After removal, verify no other page links to `/language` (the "Setup" group label in `NAV` may need
   adjusting if it now only contains Settings).

## Out of scope
- The language **resolution algorithm** and per-track language pickers are unaffected — only the
  standalone page is removed.
