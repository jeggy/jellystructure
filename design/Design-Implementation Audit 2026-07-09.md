# Design ↔ Implementation Audit — 2026-07-09

Full comparison of this project's mockups against `jeggy/jellystructure@main`
(commit `626030b`): the repo's `design/**` mirror, the Kotlin/WASM admin frontend
(`src/wasmJsMain`), and the Compose Ravilo app (`ravilo-ui/`).

## 1 · Mirror sync (repo `design/**` vs this project) — ✅ resolved

Every file was compared byte-for-byte. Result: **identical everywhere** except one
dev-side refactor, which we adopted:

- `media.html`, `series.html`, `metadata.html` — the repo extracted their inline
  `<style>` blocks into **`app/detail.css`** + **`app/metadata.css`** (new files) and
  moved the Phase-79 cast badges to `.person .ph .epb/.tag` in `wf.css`. Our copies
  now match the repo. **Keep these two CSS files** — deleting them on export
  re-breaks the shipped app (it serves these files verbatim via `syncDesignAssets`).
- `ravilo/Olivar Channel Logo.html` is design-side-only (new work, fine).
- The previously-reported wf.css / ravilo-builders.css strips are healed — both
  identical to the repo today.

## 2 · Fixed this session (design-side source, stops the revert loop)

- **Phase 138 mobile CSS** — `.dash-sidecol { display: none; }` added to
  `app/app.css` (≤980px block) + class on `index.html`'s side column. This rule had
  landed in the repo and **our export silently reverted it** (`885f0b3`); the dev
  team wrote `scripts/check-mobile-css.sh` as a fence. It's now sourced here, so the
  loop is closed.
- **CLAUDE.md export rules** — `STATUS.md` is now documented as **code-owned /
  never exported** (the 2026-07-04 export overwrote it and reverted ~14 phases);
  same for `specs/research-reports/` and `scripts/`. Stale status notes updated.

## 3 · Mockups lag shipped implementation (open design work)

1. **Dashboard triage breakdown (Phase 117, + 121/122/128/144 types)** —
   `Dashboard.kt` renders an `attention-breakdown` card: one row per issue type
   (label + description + count badge, zeros dimmed, click → Library
   `?filter=<type>`). Types: untagged · cascade_mismatch · multi_default ·
   language_mix · missing_from_source · missing_still · duplicate · zero_audio ·
   **cover_as_video**. `index.html` has none of this.
2. **Cover-as-video surfaces (Phase 144)** — nothing in any mockup: Library filter
   label "Cover art muxed as video", triage-dock subline "cover art muxed as video —
   playback-hostile, repairable", and the detail-page **"Fix cover track"** /
   **"Fix all (N)"** repair button (`MediaDetail.kt:515`).
3. **Ravilo request-language picker (R172)** — shipped
   (`RequestLanguagePicker.kt`): Request press opens an Original-vs-Nordic/Danish
   intent sheet with change-later. `ravilo-app.js`'s Request flow still requests
   directly; no picker, no i18n strings.
4. **Grid-columns controls (R174)** — shipped in `RaviloConfig.kt`: Behaviour card
   slider "Items per row on grids" (2–10, global scope) + portrait-config columns
   (1–4, default 2). `ravilo-config.html` has neither.
5. **Dashboard Quick actions drift (minor)** — impl has 6 buttons (View attention ·
   Manage tracks · Re-pull artwork · Sync NFOs to Jellyfin · Jellyfin rescan · View
   activity) with a feedback line; mockup shows 3 chips.
6. **Library page-sub copy drift (minor)** — impl explains the red/orange corner
   badges; sort dropdown is "recently added / title A–Z / year newest first";
   mockup copy differs slightly.

## 4 · Specs `Planned` — mockups added 2026-07-09

- **143** **Users & Devices overview** — designed: `app/settings.html?tab=users` (new
  “Users & devices” tab: per-user device + web-session tables, created/last-seen,
  connected badge, confirm-to-revoke, sign-out-everywhere).
- **R175** **Ravilo TV login screen** — designed in the main TV mockup: Add user /
  first-run now opens a username+password panel with D-pad on-screen keyboard
  (masked password, shift, signing-in + invalid-credentials states — demo the error
  with password “wrong”). The pairing-code panel is gone (`ravilo-app.js`,
  `ravilo.css`, i18n en/da/fo).
- **141** proxied login / **142** restricted filtering — backend; no design surface
  beyond the above (142's restricted badge appears in the Users & devices tab).

## 5 · Verified consistent (no action)

- **Ravilo skins**: `ravilo.css` tokens = `Colors.kt` exactly, all three skins
  (Aurora `#7b6ef0/#3fb6f5` on `#0a0c13`, Midnight `#19d6c6/#2a8cf0`, Noir
  `#f5b542/#e0792f`), incl. focus rings, gradients, card surfaces, tile radii.
- **R170/R171**: profile-hub nav (Home·Movies·Series·Discover + avatar menu) and
  the Seerr Request tab are in the main TV mockup, matching `AppBar.kt` /
  `ProfileMenu.kt` / `DiscoverScreen.kt`.
- **R134**: merged Audio+Subtitles flag line present in the mockup (the old
  "R78 subtitle flags are the open gap" note was stale — removed from CLAUDE.md).
- **Phase 136 Seerr settings card**, **Phase 135 Activity step chips/run badges**,
  **Phase 140 workbench blocks** (design was the source), **Phase 82 tag swatches**,
  **Phase 87/R75 flag strips** — all present and aligned.

## 6 · Process guards going forward

1. Re-pull `specs/`, `STATUS.md`, `CLAUDE.md` before any editing session.
2. Export sends `specs/` + `CLAUDE.md` to repo root and design files to `design/`
   — **never `STATUS.md`, `specs/research-reports/`, or `scripts/`**, and never
   delete repo-side files missing here.
3. After a push, `scripts/check-phases.sh` + `scripts/check-mobile-css.sh` must
   stay green; any rule they fence must have its source in THIS project.
