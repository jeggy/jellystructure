# Phase 115 — NFO ⇄ Jellyfin sync correctness: make the drift banner rare and truthful (FR-DR2)

## Problem
The detail-page warning *"Jellyfin's metadata no longer matches the NFO Jellystructure wrote"* shows far
too often — routinely on healthy items — even with **Write NFO files** enabled in the scheduled pipeline.
Root-cause analysis (verified in code) found the warning is usually **self-inflicted staleness**, not
real drift, because the sync chain has three defects:

1. **The pipeline's `write_nfo` step skips existing NFOs.** `Main.kt:411-421` writes only when
   `step.overwrite || behavior.overwriteNfo` — with the default `overwrite=false`, an NFO written once
   is **never updated** by the pipeline, so every later DB change (TMDB freshness re-pull tweaking a
   title/year, an operator edit) diverges from the NFO and Jellyfin forever.
2. **The pipeline's `sync_jellyfin` step can't import metadata.** It calls `refreshItem(full=false)` →
   `MetadataRefreshMode=ValidationOnly` (`Main.kt:422-432`, `JellyfinClient.kt:138-154`) — a validation
   pass that does **not** re-read NFOs. Only the per-item button path (`pushToJellyfin`,
   `MediaRoutes.kt:1663-1709`) uses `FullRefresh + ReplaceAllMetadata=true`.
3. **"Drift" is a blunt three-field compare with no state model.** `GET /api/media/{id}/drift`
   (`MediaRoutes.kt:1237-1273`) compares live-Jellyfin `name/year/tmdbId` against the **DB item** with
   raw `!=` — it never looks at the NFO it claims to speak for, can't distinguish "we changed the DB and
   haven't written the NFO yet" from "Jellyfin hasn't re-read the NFO" from "someone edited it in
   Jellyfin", and nothing re-checks after a sync so the banner lingers until a manual reload.
   (The pipeline's `detect_drift` step is a **no-op** — `Main.kt:438-439` just logs "not yet wired".)

## Goal
The three artifacts — **DB** (jellystructure truth) → **NFO on disk** → **Jellyfin** — stay converged
automatically, and when they diverge the banner says **which link broke and what fixes it**. Seeing the
drift warning becomes the exception (a genuine external edit), not the norm.

## Requirements

### A. Track what was written (per item)
1. On every NFO write (button + pipeline), store per item: `nfoWrittenAt` + `nfoHash` (hash of the XML
   jellystructure generated — episodes roll up into one combined hash). On every Jellyfin refresh
   triggered for the item, store `jfSyncedAt`.
2. These are additive item fields (blob defaults, no migration).

### B. The pipeline writes stale NFOs (without clobbering foreign ones)
1. `write_nfo` becomes **content-aware**: regenerate the would-be XML; if its hash ≠ `nfoHash`
   (jellystructure's own last write) **and** the on-disk file matches `nfoHash` (i.e. it's still our
   file), rewrite it — regardless of the `overwrite` flag. The `overwrite` flag keeps its original
   meaning: *also* replace NFOs jellystructure didn't write (foreign/hand-edited files, detected as
   on-disk ≠ our last hash).
2. A skipped foreign NFO logs once per run (not per item, per the Phase 53 skip-summary pattern).

### C. The pipeline sync actually imports
1. `sync_jellyfin` refreshes with **`FullRefresh + ReplaceAllMetadata=true`** — but **only** items whose
   `nfoWrittenAt > jfSyncedAt` (something new to import). Unchanged items aren't touched, so a nightly
   run doesn't hammer Jellyfin with 300+ full refreshes.
2. Soft-validation hint (Phase 91 pattern): `sync_jellyfin` placed before `write_nfo` warns in the
   pipeline builder.

### D. A truthful three-state banner
Replace the single drift check with a state evaluation (same endpoint, richer response):
1. **NFO stale** — would-be XML hash ≠ `nfoHash`: info-level banner *"Your edits aren't in the NFO yet"*
   with **Save → NFO** CTA (this is the write-through model working as designed, not drift).
2. **Jellyfin behind** — NFO current, but `jfSyncedAt < nfoWrittenAt` (or the compare below fails right
   after a write): banner *"Jellyfin hasn't re-read the NFO yet"* with **Sync Jellyfin** CTA.
3. **External drift** — NFO current, a sync happened, and live Jellyfin still differs on the compared
   fields: the real warning *"Jellyfin's metadata no longer matches the NFO Jellystructure wrote"* with
   the existing **Re-assert** CTA.
4. The field comparison is **normalized**: trimmed strings, `LanguageResolver.sameLanguage` for language
   codes, set-compare for list fields — no false drift from whitespace/ordering. Compared fields stay
   the current trio (title / year / tmdbId) plus `mpaa`↔`OfficialRating` once Phase 106 lands; widening
   further is deliberate follow-up, after the sync chain is proven quiet.

### E. Verify-after-sync (banner clears itself)
1. After `Sync Jellyfin` / re-assert / pipeline sync, jellystructure polls the item briefly (a few
   seconds, bounded ~30 s — Jellyfin's refresh is async) and re-evaluates the state; the detail page
   receives the updated state (existing detail reload path) so the banner disappears without a manual
   refresh. Timeout leaves state 2 standing with a "still importing…" hint.

### F. Wire `detect_drift`
1. The pipeline's `detect_drift` step runs the state evaluation across the working set: Activity summary
   (counts per state), `notify_on_drift` webhook (exists, `Behavior.notifyOnDrift`) fires with the
   state-3 count. New step option `auto_reassert: Boolean = false` — when true, state-2 items are
   auto-fixed (write NFO if needed + full refresh) and only state-3 items are reported.

## Scope
- Backend: `Main.kt` pipeline steps (write_nfo/content-aware, sync_jellyfin/full+scoped, detect_drift/
  real), `MediaRoutes` drift endpoint → state model + verify-after-sync, item fields, `NfoWriter` hash
  helper.
- FE: `MediaDetail.kt` banner → three states/CTAs (`design/app/media.html` copy update).
- Config: `PipelineStep.auto_reassert`.

## Non-goals
- No change to field-lock handling (Phase 22) or the write-through editing model (Phase 74).
- No two-way merge — Jellyfin-side edits are still drift to re-assert, never imported.
- No widening of compared fields beyond D.4 this phase.

## Acceptance
- Fresh library, pipeline with Write NFO + Sync Jellyfin enabled: after an operator title edit +
  overnight run, the NFO contains the new title, Jellyfin shows it, and the detail page shows **no
  banner**.
- A TMDB freshness re-pull that changes a year converges the same way with zero manual action.
- Editing the title inside Jellyfin itself produces the state-3 warning (and only that item), and
  Re-assert fixes it; with `auto_reassert=true` the pipeline fixes state-2 items silently.
- Pressing Sync Jellyfin clears the banner by itself within seconds — no page reload.
- A hand-edited (foreign) NFO is never overwritten unless the overwrite flag is set, and is reported
  once per run.
