# Phase 45 — Fix the track-editor language picker (unstyled popup) (FR-LP1)

**Status:** ✓ Done (2026-06-21) · _the searchable language menu used by the "Tracks & order" editor
(movie tab + series per-episode modal) renders **completely unstyled** — give it the same styled,
searchable picker used everywhere else._

> **As built (route a):** `openTrkLangMenu` now emits the shared `.lp-dropdown`/`.lp-search`/`.lp-list`/
> `.lp-opt` structure and calls `injectPickerStyles()` (made `internal`); the dead `.langmenu`/`.lm-*`
> markup is gone. Body-mounted `position:fixed` so it isn't clipped by the episode modal. Behaviour
> unchanged (type-to-filter on name/code, ↑↓/Enter/Esc, `onPick` stages). Current-selection marker now
> compares `LanguageResolver.normalize(currentCode)` so 3-letter ffprobe codes (`fao`/`eng`) highlight
> the right 2-letter row; added a `.lp-opt.cur` ✓ marker.

## Problem
On Media Detail, the **Tracks & order** tab (movie) and the **per-episode track editor modal**
(series, episodes tab) let you click a track's language to open a searchable menu. That menu has **no
CSS at all** — it appears as bare, unstyled `<div>`s (no panel, no border, no search-box styling, no
hover/active states), floating at the bottom of the page.

### Root cause (confirmed)
There are **two** language-picker implementations in the frontend:

1. **Shared picker** — `LanguagePicker.kt` `installLanguagePicker(...)`. Uses `.lp-wrap` / `.lp-display`
   / `.lp-dropdown` / `.lp-search` / `.lp-list` / `.lp-opt` and **injects its own styles** via
   `injectPickerStyles()`. Works correctly; used by Settings (fallback language, per-library
   fallback) and Media Detail's language **override** input (`installLanguagePickerById(...)`).
2. **Track-editor picker** — `TrackEditor.kt` `openTrkLangMenu(anchor, currentCode, onPick)`. Builds a
   bespoke menu with classes **`.langmenu` / `.lm-search` / `.lm-list` / `.lm-opt` / `.lm-name` /
   `.lm-code` / `.lm-empty`** and appends it to `document.body`. **None of these classes are defined
   anywhere** — not in `injectTrackEditorStyles()` (which only styles `.trk*`, `.lang-pickwrap`,
   `.mini-toggle`, …), and not in the design CSS the app loads (`design/app/wf.css` /
   `design/app/app.css`, served verbatim via `syncDesignAssets` / the dev proxy). So the menu is
   unstyled.

This is the same class of bug as the Library Movies/TV switch (Phase 43): markup written against CSS
selectors that don't exist in the app's stylesheet.

## Goal
The track-editor language menu looks and behaves like the **shared** searchable language picker —
panel chrome, search box, scrollable option list, hover + keyboard-active highlight, and a marker on
the current language — in **both** the movie Tracks tab and the series per-episode editor modal.

## Current state (as-is)
- `TrackEditor.kt`:
  - `openTrkLangMenu` (line ~55) creates `<div class="langmenu">` with `.lm-*` children, appended to
    `document.body`; supports type-to-filter, ↑↓/Enter/Esc keyboard nav, and a `.cur`/`.active`
    marker — **the behaviour is fine; only the styling is missing.**
  - `injectTrackEditorStyles()` (line ~590) injects `#trk-editor-styles` but defines no `.langmenu` /
    `.lm-*` rules.
  - The menu is opened from the language chip in each track row; the wiring (`wireUnifiedTrackEditor`)
    is shared by the movie tab and the per-episode modal, so fixing it once fixes both surfaces.
- `LanguagePicker.kt`: `installLanguagePicker` + `injectPickerStyles()` is the **working reference** —
  `.lp-dropdown` is `position:fixed`, body-mounted, never clipped by `overflow:hidden` ancestors
  (important: the per-episode editor is a **modal**, so the menu must not be clipped by the modal's
  overflow — the shared picker already solves this).

## Requirements

### A. One styled, searchable language picker
1. The track-editor language menu must be **visually consistent** with the shared
   `installLanguagePicker` dropdown: a bordered panel (`var(--fill-3)`/`var(--line-2)`, the app's
   radius + shadow), a search input, a scrollable option list, per-row hover, a keyboard-active
   highlight, and a clear marker on the **currently-selected** language.
2. **Preferred approach — consolidate, don't duplicate.** Reuse the shared picker's styling (and, if
   practical, its rendering) rather than hand-maintaining a parallel `.lm-*` stylesheet. Two viable
   routes; implementer picks:
   - **(a)** Make `openTrkLangMenu` emit the shared `.lp-*` structure / class names and rely on
     `injectPickerStyles()` (call it from the track editor too), deleting the dead `.lm-*` markup.
     Note: `injectPickerStyles()` is currently `private` in `LanguagePicker.kt` — make it `internal`
     so the track editor can call it; or
   - **(b)** Refactor the click-to-open chip flow to use `installLanguagePicker` directly (it upgrades
     an `<input>`; the track row would host a hidden input per the existing pattern).
   Route (a) is the smaller change and keeps the existing chip-anchored interaction; route (b) is the
   cleaner long-term unification. **Do not** simply add a new `.langmenu` block that duplicates the
   `.lp-*` rules.
3. Whatever route is chosen, the menu must keep its current behaviour: type-to-filter on **name and
   code**, ↑↓ + Enter selection, Esc / outside-click dismiss, and selecting a code calls the existing
   `onPick(code)` (which stages the change — no behavioural change to staging/Apply).

### B. Works inside the series per-episode modal
1. The per-episode editor is a **modal/overlay**. The menu must render **above** the modal and **not
   be clipped** by the modal's scroll container (the shared picker's body-mounted, `position:fixed`
   dropdown already satisfies this — match it).
2. The menu must be positioned relative to the clicked language chip (anchor) and stay attached on
   scroll/resize consistent with how the shared picker positions against its display element.

### C. Current-language marker round-trips correctly
1. Track languages read from ffprobe are often **3-letter ISO-639-2** (e.g. `fao`, `eng`), while the
   picker's option list is **2-letter ISO-639-1** (`fo`, `en`). The "current language" marker must
   still highlight the right row when the track's stored code is 3-letter — compare via
   `LanguageResolver.normalize(currentCode)` against each option's code, not a raw
   `code == currentCode` string match. `normalize` already exists in `commonMain` and is reachable
   from the wasmJs UI, so **this item ships independently — it does not depend on Phase 46.** (Phase 46
   does the deeper write-side code mapping; this is only the picker's current-selection highlight.)

## Invariants
- **One language-picker look** across the whole app (Settings, language override, track editor, series
  episode editor). No surface renders an unstyled menu.
- **View/behaviour unchanged** — this is a styling/consolidation fix; selection still stages a change
  and only writes on Apply (Phases 27/41/42).
- Menu is never clipped by a modal or `overflow:hidden` ancestor.

## Out of scope
- The actual track-language **write correctness** (2↔3-letter mapping, verify-after-write, truthful
  command preview) — that is **[Phase 46](phase-46-track-language-write-persist.md)**.
- Any change to the staging/Apply/command-preview logic beyond the current-selection highlight (C).

## Design reference
`design/app/media.html` (Tracks & order tab) and `design/app/series.html` (per-episode editor) — the
language menu should match the searchable-dropdown styling already shown for the Settings/override
pickers. If the design CSS lacks a shared dropdown token set, align to the app's injected `.lp-*`
styling as the source of truth.
