# Phase 104 — Detail tab order: match the design files (Seeding 3rd, Artwork before Cast) (FR-UI1)

## Problem
On the Movie and Series detail pages the tab bar order does **not** match the design files. The
**Seeding** tab (added in Phase 97) is appended **last**, after History — but the design places it
**3rd**, immediately after Tracks/Episodes. A second, smaller divergence exists too: production orders
**Cast & crew before Artwork**, while the design orders **Artwork before Cast & crew**.

## Findings (design = source of truth, production diverges)
Tab order is driven by a single ordered Kotlin list — `tabItems` in
`src/wasmJsMain/.../ui/MediaDetail.kt:223–243` (movie and series both live here). The visible
left-to-right order is determined **entirely** by this list (`tabBarHtml = tabItems.joinToString(...)`,
`:247`). There is no enum ordinal and no sort. The `<div id="tab-*">` panels (`:449–503`) are
shown/hidden by `display:none`, so their DOM order is irrelevant — only `tabItems` order matters. Lazy
activation keys off the tab **id** string (`activeTab` `:245`, `tabIds.forEach` `:695`,
`if (tab == "seeding")` `:708`, `loadSeedingTab` `:725`), so a pure reorder is safe — nothing else
changes.

**Design order — `design/app/media.html` `#tabbar` (`:306–314`):**
Overview · Tracks & order · **Seeding** · Artwork · Cast & crew · NFO (raw) · History

**Design order — `design/app/series.html` `#tabbar` (`:177–185`):**
Overview · Seasons & episodes · **Seeding** · Artwork · Cast & crew · NFO (raw) · History

**Production order now — `MediaDetail.kt`:**
- Series (`:224–232`): Overview · Seasons & episodes · Cast & crew · Artwork · NFO raw · History · **Seeding**
- Movie (`:234–242`): Overview · Tracks & order · Cast & crew · Artwork · NFO raw · History · **Seeding**

Two differences per list: (a) Seeding is last instead of 3rd; (b) Cast precedes Artwork instead of
the reverse.

## Goal
The production tab bar matches the design files verbatim on both detail pages.

## Requirements
1. **Reorder `tabItems`** (`MediaDetail.kt:224–232` series, `:234–242` movie) to the design order:
   - **Series:** `overview` → `episodes` → `seeding` → `artwork` → `cast` → `nfo` → `history`
   - **Movie:** `overview` → `tracks` → `seeding` → `artwork` → `cast` → `nfo` → `history`
   This is two moves per list: pull `seeding` from last up to slot 3, and swap `cast`/`artwork` so
   `artwork` comes first. No other code changes — ids and lazy-load logic are position-independent.
2. **(Optional, low-risk) Tab label parity:** the design labels the NFO tab **"NFO (raw)"** while
   production uses **"NFO raw"** (`MediaDetail.kt:229/239`). Align the label text to the design while
   reordering, since it's the same lines.

## Scope
- `src/wasmJsMain/.../ui/MediaDetail.kt` — the two `listOf(...)` blocks at `:224–232` and `:234–242`
  (and optionally the NFO label text). Nothing else.

## Non-goals
- No change to tab **panels**, their content, or the lazy-load/activation machinery (id-keyed, order-
  independent).
- No change to which tabs exist or their gating (e.g. Seeding still only meaningful when qBittorrent is
  configured — unchanged).

## Acceptance
- Movie detail tab bar reads: Overview · Tracks & order · Seeding · Artwork · Cast & crew · NFO (raw) ·
  History.
- Series detail tab bar reads: Overview · Seasons & episodes · Seeding · Artwork · Cast & crew ·
  NFO (raw) · History.
- Switching tabs, deep-linking a tab by id, and the Seeding tab's lazy load all behave exactly as before
  — only the bar order changed.
