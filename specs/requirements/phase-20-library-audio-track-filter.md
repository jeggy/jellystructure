# Phase 20 — Library Audio-Track Filter (FR-LF1)


## Problem
Some media files carry audio tracks that are easy to overlook but annoying to manage — e.g. a Danish
audio-description track that Jellyfin displays as
`"Dansk Synstolkning - Danish - AAC - Stereo - Default"`. There is no way to find all items that have
such a track. The Library only filters by All / Needs attention / Missing artwork, plus kind/sort/search.

## Current state (as-is)
- `Track` (commonMain) holds the component parts locally: `language` (e.g. `dan`), `title` (e.g.
  `Dansk Synstolkning`), `codec`, `default`, `forced`, `kind`. ffprobe maps `tags.title` → `title`.
  See [`_investigation-findings.md`](_investigation-findings.md).
- `MediaStore.list(kind, filter, search, sort, page, pageSize)` supports only `filter ∈ {attention,
  missing_artwork}`. Movies store all tracks in `item.tracks`; TV stores only the first episode's
  tracks in `item.tracks` (full per-episode tracks live in `item.episodes[].tracks`, **stripped from
  list responses**).
- `Library.kt` renders the filter chips and calls `MediaApi.list(...)`.
- The Library list endpoint strips `episodes`, so episode-level track data is not available to a
  list-time filter for TV unless the store computes it server-side from the full item.

## Investigate-first note
This can get complicated quickly (the user explicitly flagged this). The realistic scope: filter on
the **structured track attributes we already store** (language, title substring, codec, default/forced,
track kind) — not on Jellyfin's composed display string. Decide the axes below before building UI.

## Requirements

### Backend — track filtering in `MediaStore.list`
1. Add optional query params to `GET /api/media`, all server-side, combinable (AND):
   - `audioLang=<code>` — item has at least one AUDIO track whose normalized `language == code`.
   - `trackTitle=<substring>` — item has at least one track whose `title` contains the substring (case-insensitive). This is what catches "Synstolkning", "Commentary", "SDH", etc.
   - `audioCodec=<codec>` — item has at least one AUDIO track with that codec.
   - `untaggedAudio=true` — item has at least one AUDIO track with `language == null`.
2. For TV shows the filter must consider **all episodes' tracks** (`item.episodes[].tracks`), not just
   `item.tracks`. The store has full items in memory, so it can evaluate this before episodes are
   stripped for the response.
3. Provide a discovery endpoint `GET /api/media/track-facets` returning the distinct values present
   across the library, so the UI can populate dropdowns instead of free text:
   ```json
   {
     "audioLanguages": [{ "value": "dan", "count": 12 }, { "value": "eng", "count": 340 }],
     "audioCodecs":    [{ "value": "aac", "count": 200 }, { "value": "ac3", "count": 120 }],
     "trackTitles":    [{ "value": "Dansk Synstolkning", "count": 4 }, { "value": "Commentary", "count": 9 }]
   }
   ```
   Counts are number of media items (not tracks) matching. `trackTitles` aggregates distinct non-blank
   track titles across audio + subtitle tracks.

### Frontend — Library "Filter by audio track" control
4. Add a **"Audio track ▾"** dropdown/popover to the Library filter bar (next to the existing chips).
   It contains, populated from `/api/media/track-facets`:
   - Language select (`audioLang`) — labelled with the Phase 11 language picker names, e.g. "Danish (dan)".
   - Track title select or search (`trackTitle`) — lists distinct titles with counts; this is the
     primary tool for finding "Synstolkning" / audio-description / commentary tracks.
   - Codec select (`audioCodec`).
   - "Untagged audio only" checkbox (`untaggedAudio`).
5. Selecting any of these sets the corresponding query param, resets to page 1, and reloads the grid
   via `MediaApi.list(...)`. Active audio filters show as removable chips with a "clear" affordance.
6. The audio-track filter combines with the existing kind/attention/artwork filters and search.
7. Empty facet categories are hidden. If the library is unscanned, the control shows a muted "scan to
   populate" hint.

## Out of scope
- Parsing or reconstructing Jellyfin's composed display string.
- Acting on the matched tracks (setting default, retagging) from the Library — that remains in
  Triage / Track editor. This phase is discovery/filtering only.
