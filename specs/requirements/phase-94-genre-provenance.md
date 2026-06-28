# Phase 94 — Genre provenance: user genre edits survive TMDB re-sync (FR-GP1)

## Problem

A show is matched on TMDB; the operator adds a genre in the admin; the next `pull_tmdb` overwrites
`genres` from TMDB and the edit disappears. Genres were assigned **unconditionally** from TMDB at every
scan/sync point — while **tags already survive** re-sync (`Scanner.mergeRepullTags`, Phase 19). Genres
needed the same stickiness.

## Rule

- A genre the **user added** (not in TMDB) is sticky — a sync won't remove it.
- A genre the **user removed** (TMDB has it) is sticky-absent — a sync won't re-add it.
- A genre **neither touched** is **TMDB-owned** — a sync adds/removes it freely.

## Model — one baseline field, provenance derived

`MediaItem.tmdbGenres: List<String>` = TMDB's raw genre list, written **only** by scan/sync (never by a
user edit). `genres` stays the effective list (shown in the UI, written to the `.nfo`). Provenance is
*derived*, so the edit flow is unchanged:

- **user-added**  = `genres − tmdbGenres`
- **user-removed** = `tmdbGenres − genres`

On every TMDB re-sync (`Scanner.mergeUserGenres`, mirroring `mergeRepullTags`):

```
userAdded   = prior.genres − prior.tmdbGenres
userRemoved = prior.tmdbGenres − prior.genres
genres      = (newTmdb − userRemoved) ∪ userAdded      // TMDB order, then user adds; distinct
tmdbGenres  = newTmdb
```

Applied at `syncMovie`, `syncSeriesEpisodes` (keeping its "no details → keep current" guard),
`rescanMetadata` (movie + TV). Initial scans set `genres == tmdbGenres`.

**Migration-free & self-correcting.** Pre-feature rows have an empty baseline, so the first sync yields
`userAdded = all current genres`, `userRemoved = []` → `genres = newTmdb ∪ currentGenres` — the operator's
pending add is preserved, and `tmdbGenres` is populated so every later sync is precise. One-time
imprecision: a genre removed *before* this feature can't be detected, so it re-appears once; removing it
again makes it stick. (The headline bug is an *add*, which is preserved immediately.)

## UI (media detail)

- A user-added genre chip carries a small **accent dot** (`--hi`); TMDB genres are unmarked.
- TMDB genres the user removed render as **greyed strikethrough "restore" chips** with a `↺` that re-adds
  them. A one-line note: *"Your genre edits survive TMDB re-syncs."* No markers when the baseline is empty.
- No `MediaApi` change — add/remove still post the full `genres` list via `PATCH /metadata`.

## Out of scope

Scalar fields (title/overview/studio…) — those need a per-field "user-overridden" lock (the Jellyfin
field-lock banner hints at it), not this list-delta model.

## Files

- `src/commonMain/.../model/Media.kt` (`tmdbGenres`), `src/linuxX64Main/.../media/Scanner.kt`
  (`mergeUserGenres` + the 7 genre-assignment points), `src/wasmJsMain/.../ui/MediaDetail.kt`
  (provenance dot + restore chips + note).
