# Phase R07 — Detail API: movie/series, episodes, watched-state (FR-RV7)


## Problem
Ravilo's detail screens — especially **series** — must make it obvious what you've watched and what's
next. The backend must serve a movie/series detail payload that includes seasons/episodes, **per-item
watched/in-progress state**, the computed **resume / up-next** pointer, cast, and related items.

## Current state (as-is)
- jellystructure has per-episode metadata (titles, overviews, stills) and can read Jellyfin user-data
  (watched/played, resume positions). Phase 6 covered per-episode metadata; watched-state is in
  Jellyfin.
- No TV-facing detail endpoints.

## Requirements

### Movie
1. `GET /api/tv/movie/{id}` → **`MovieDetail`**: the `MediaCard` + synopsis, runtime, year, rating,
   genres, **`PlaybackState`** (`watched`, `positionMs`, `durationMs`, `pct`), `cast[]`, and
   `related[]` (a "More Like This" slice). Backdrop + logo URLs (Jellyfin) included.

### Series
2. `GET /api/tv/series/{id}` → **`SeriesDetail`**: card + synopsis + `seasons[]`, each
   `Season(index, name, episodes[])`; each **`Episode`** carries `n`, title, runtime, overview,
   `stillUrl`, and its own **`PlaybackState`**.
3. Include **`SeriesProgress`**: count watched / total, and a **resume pointer** — the episode the
   primary action should target, computed as: the in-progress episode if any, else the first unwatched
   episode (in season/episode order), else the last episode. Expose `{seasonIndex, episodeId,
   mode: RESUME|PLAY, label}` so the client's "Resume · E4" button and "Up Next" ribbon are
   server-driven, not client-guessed.
4. Watched/in-progress flags per episode drive the client's ✓ / progress-bar / dim treatments.

### Sourcing
5. Watched-state and resume positions are read from **Jellyfin user-data** (per the paired user),
   merged onto jellystructure's metadata. The payload is **authoritative** — the client renders it,
   never accumulates it.
6. Reasonable paging for very long series (e.g. episodes per requested season), so a 200-episode show
   doesn't ship at once.

## Invariants
- **Resume/up-next is computed server-side** and shipped in the payload (single source of truth).
- Per-item `PlaybackState` comes from Jellyfin via jellystructure — server-pushed, not derived.
- Image URLs target Jellyfin (data plane).

## Out of scope
- Starting playback / reporting progress (R08).
- The detail **UI** (R13).
