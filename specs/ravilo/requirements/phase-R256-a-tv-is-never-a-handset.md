# Phase R256 — A TV is never a handset

> Found on the stue TV 2026-09-17: since v1.18 the television renders **the phone's player**. R244's
> thumb rail (Subtitles · Next · Lock), centred touch transport and bottom-sheet picker are on the
> living-room screen; there is no focus ring, Left/Right do not seek, and the picker says *"Applies on
> this phone only."* Discover's new walls are 2-up. One boolean is wrong.

## Status

`✓ Built` 2026-09-17 (`9e060653`) — written the same day from a live reproduction, not dev-reviewed, **not yet on a device**. `isHandset()` in `Platform.kt`, `IsHandsetTest` (7 cases); Android + Wasm compile clean. Client-only
(`ravilo-ui`). Corrects the gate **R244** and **R243** were built on; neither spec changes.

**Numbering:** verified against `STATUS.md` 2026-09-17 — Ravilo taken through **R255**. Backend
sibling from the same sweep: **229**; remaining TV defects: **R257**.

## Cause

`RaviloApp.kt` (`:620`) computes `LocalHandset` as *the window's shorter side is under 600 dp* —
Android's own `sw600dp` line between phone and tablet. **Every Android TV is 960 × 540 dp**: the stue
BRAVIA reports `1920x1080` at density `320`, so its shorter side is 540 dp and the flag is `true`.

That was latent for weeks. Until R244 the flag's only consumer was tap-to-toggle-chrome, which needs a
touch a TV never sends. R244 (the player), R243 (the walls) and the Live TV player then hung whole
layouts on it, and its dev review "confirmed the gate" on a phone, where it is right. `PlayerScreen`'s
own comment (`:1428`) states the assumption that failed: *"a TV window is never handset-sized."*

## Observed on the TV (build `1.18-3-g0a6b45ea`)

- **Player:** handset chrome instead of the TV transport. No visible focus; OK acts on an invisible
  focus target (paused once, opened the picker once); **Left/Right do not seek** (1:02 → 1:06 → 1:11
  across three Rights and a Left — playback time only); Back hides the chrome and leaves a paused
  frame with no indication; the picker is the bottom sheet with *Subtitle size* and the phone-only
  caption; auto-hide is 3 000 ms; the episode rail is the season sheet.
- **Discover → Studios / Networks / Genres:** 2-up and 3-up phone walls instead of R243's 4-up/5-up.
- **By code, not yet seen:** `LiveTvPlayerScreen` (`:105`, `:392`), the cold-start block's phone
  sizing (`PlayerScreen.kt:1518`), `PlayerImmersiveEffect(followSensor = true)`, haptic ticks.

## Requirements

- **FR-R256-1 — form factor first, size second.** `LocalHandset` is
  `!isTvPlatform && shorter side < 600 dp`. `isTvPlatform` is R234's seam (Android: runtime
  `UI_MODE_TYPE_TELEVISION`; web: constant `false`) — the same seam R254 names for the same reason.
  One change at the provider; no call site changes.
- **FR-R256-2 — the rule, written where the next phase will read it.** `LocalHandset`'s doc comment
  states that a TV is 960 × 540 dp and that *no dp threshold can tell a TV from a phone*; the stale
  comment at `PlayerScreen.kt:1428` is corrected.
- **FR-R256-3 — a pure, tested function.** The decision moves to
  `isHandset(isTv, widthPx, heightPx, density)` in `commonMain` with a test pinning: TV 1920×1080 @2.0
  → false; TV 3840×2160 @4.0 → false; Pixel 9 portrait and landscape → true; a 1280×800 dp tablet →
  false; a zero-sized window → false.
- **FR-R256-4 — `LocalCompact` is left alone.** It is width-only (`< 600 dp`), a TV's width is 960 dp,
  and it is correct today. `LocalPortrait` likewise.

## Non-goals

A browser on a TV (web reports `isTvPlatform = false`; its window is ≥ 600 dp on the short side in
practice). Tablets. Any change to the handset chrome itself — its one defect seen in passing (the
skip glyphs) is in R257.

## Acceptance (stue TV)

1. The player shows the TV transport with a focus ring; Left/Right seek; OK on the play button
   toggles; Down opens the episode rail; the picker is the R195 side panel with no *Subtitle size* row.
2. Studios/Networks are 4-up, Genres 5-up.
3. The Live TV player shows its TV chrome.
4. On the Pixel 9 nothing changes: handset chrome, sheets, 2-up walls.
