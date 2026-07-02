# Phase 108 — Correct "Newly Added" sort (by JS created-at) + surface all timestamps on the detail page

## Goal
1. Sort Ravilo's **Newly Added** rows by the date a title was **added to Jellystructure** (its
   `createdAt`), **descending**. For a **series**, sort by the **most-recently-added episode's** added
   date (a new episode re-floats the series to the top), not the series record's own createdAt.
2. Make those timestamps **visible** on the jellystructure detail page so an admin can see exactly what
   drives the order: a **Timestamps** section on the Overview tab, per-episode timestamps in Seasons &
   episodes, a **newest-episode** panel, and a **"?"** explainer tying createdAt to Newly Added.

## Problem — what actually sorts today (verified in code)
Every recency surface sorts by **`addedAt ?: scannedAt`** (`HomeFeedService.kt:267,281,312-344`,
`BrowseService.kt:74-105`, `MediaStore.list:262`, hero auto `:136`), where `addedAt` is **Jellyfin's
`DateCreated`** captured at scan (`Scanner.kt:190,350,399`) with two rules layered in
`MediaStore.update` (`:167-176`): a series that *gained episodes* during a scan is bumped to
scan-time-now, and the value never moves backward. Two real defects follow:
1. **Jellyfin's `DateCreated` is not "when it arrived"** — Jellyfin commonly derives it from the media
   file's mtime, so a freshly-downloaded movie whose file carries an old modification date sorts as if
   it were added years ago. Sorting must key on **when Jellystructure first saw it**.
2. **Series re-float at scan granularity, without provenance** — the episode-gain bump stamps "now"
   (scan time, up to a whole schedule-interval late) on the series record, and there is **no
   per-episode added date at all** (`Episode`, `model/Media.kt:54-76`, has zero timestamps), so nothing
   can show *which* episode re-floated the series, and a scan that back-fills an old season re-floats
   the series exactly like a brand-new episode.
And none of the timestamps involved (JS/Jellyfin, item/episode) are surfaced anywhere, so the ordering
is unexplainable from the UI.

## Requirements

### A. Data — JS-owned timestamps
1. **Item:** new SQLite columns `created_at` / `updated_at` on the `media` table (epoch seconds; a
   `.sqm` migration adds them; they are **columns, not blob fields**, so `updated_at` can be maintained
   without self-referencing the JSON blob). `created_at` = stamped once when the row is first inserted;
   `updated_at` = stamped in `upsertItem` whenever the encoded item JSON actually changed (blob string
   compare — skip pure `last_checked` touches). Startup **backfill** (the `backfillSearchText` pattern,
   `MediaStore.kt:85-96`): existing rows get `created_at = addedAt ?: scannedAt` (best available
   history) and `updated_at = scannedAt`.
2. **Episode:** new field `Episode.createdAt: Long?` (epoch s, inside the item blob — episodes live
   there). The scanner stamps it when an episode **first appears** for the item (match by
   `path`/season-episode against the previous stored item, preserved on every later re-scan);
   pre-existing episodes backfill to the item's `created_at`. This replaces the coarse episode-gain
   bump in `MediaStore.update:167-176` (delete that rule — the sort key below derives it precisely).
3. **Jellyfin timestamps, kept for display:** `addedAt` (DateCreated) stays; additionally request
   `DateLastSaved` in the scanner's `Fields=` list (`JellyfinClient.kt:104-130`) and store
   `jellyfinUpdatedAt: Long?` beside it. Per-episode Jellyfin `DateCreated` is stored when the episode
   list fetch provides it (`Episode.jellyfinCreatedAt: Long?`, best-effort).

### B. Newly Added sort
1. One shared sort key: `MediaItem.recencyKey() = ` **series** → `max(episode.createdAt…, createdAt)`;
   **movie** → `createdAt`; fallback `scannedAt` while backfill hasn't run. Ties broken by title.
2. Replace `addedAt ?: scannedAt` with `recencyKey()` at **every** recency sort site: the Newly Added
   rows (split + merged + per-channel R143 variants, `HomeFeedService`), hero auto-pick, Browse default
   sort, `MediaStore.list` default, and `relatedByGenre` — one consistent notion of "recent".
3. Jellyfin's `DateCreated` no longer participates in ordering (display only).

### C. Detail page — Timestamps section (Overview tab, movie + series)
Full-width **Timestamps** section at the **bottom of the Overview tab**, grouped by system:
- **Jellystructure:** Created (`created_at`), Updated (`updated_at`), Last scanned (`scannedAt`), Last
  checked (`last_checked`, the Phase 91 freshness stamp).
- **Jellyfin:** Created (`addedAt`/DateCreated), Updated (`jellyfinUpdatedAt`/DateLastSaved).
Dates absolute (mono) with a relative "· N ago" hint. The detail payload is the raw `MediaItem` plus the
two new columns — expose them on the detail DTO/route.

### D. "?" explainer
Next to **Created** (Jellystructure), a small **"?"** icon whose hover tooltip explains: *Created in
Jellystructure is when the title was first added to your library — the timestamp Ravilo's Newly Added
rows sort by (for a series, the most-recently-added episode).*

### E. Series — Seasons & episodes tab
1. **Under each episode:** its timestamps — Added · JS (`ep.createdAt`), Created · Jellyfin (when
   held) — plus a compact "added <date>" chip on the episode row itself.
2. **Left side of the tab:** a **Newest episode** panel showing which episode is most-recently-added
   (the `recencyKey` winner) with its timestamps, the same "?" explainer, and a line noting this is what
   places the series in Ravilo's Newly Added.

## Scope
- `design/app/media.html` / `series.html` — Timestamps grid (`.ts-cols`), `.help-dot` tooltip, `.ep-ts`
  per-episode injection, `#ep-newest` side panel (**built**).
- Backend: `.sqm` migration + `MediaStore` (columns, backfill, `recencyKey`, drop the episode-gain
  bump), `Scanner` (episode first-seen stamping, `DateLastSaved` field), `model/Media.kt`
  (`Episode.createdAt/jellyfinCreatedAt`, `MediaItem.jellyfinUpdatedAt`), detail DTO/route exposure,
  `HomeFeedService`/`BrowseService`/`MediaStore.list` sort-site swap.
- FE: `MediaDetail.kt` Overview section + Seasons & episodes injection.

## Non-goals
- No "date added" column in the Library grid (detail page + Ravilo sort only this phase).
- No manual override of createdAt.
- No change to Continue Watching (Jellyfin resume/next-up data) or custom-row filters — only the
  *recency ordering* inside rows changes.

## Acceptance
- A freshly-imported movie whose file mtime is years old appears at the **top** of Newly Added.
- A series whose new episode arrives re-floats to the top, and the Seasons & episodes tab shows exactly
  that episode in the **Newest episode** panel with its JS added date.
- A full re-scan of an unchanged library does **not** reshuffle Newly Added (first-seen stamps are
  preserved; the old scan-order artefacts are gone).
- Overview shows the JS + Jellyfin timestamp groups; the "?" tooltip explains the Newly Added link.
- After migration, existing items sort no worse than before (backfill from `addedAt ?: scannedAt`).
