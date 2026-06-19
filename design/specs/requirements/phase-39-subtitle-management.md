# Phase 39 — Subtitle Management (FR-SUB1)

**Status:** Planned

## Problem
Track tooling is audio-centric: the language resolver walks audio, the default-flag/cascade fixes
target audio, and subtitles get only language-tagging. Subtitles are second-class — there's no way to
**fetch missing subtitles**, manage the **forced** flag (distinct from default), or set a **default
subtitle** with the same ergonomics as audio.

## Current state (as-is)
- `Track` carries `kind`, `language`, `default`, `forced` for subtitle tracks too (ffprobe).
- Media Detail / episode editors expose audio set-default + language; the Subtitles tab is thin.
- `mkvpropedit` already toggles `flag-default` / `flag-forced`; no subtitle *fetch* path exists.

## Requirements

### Forced + default flags
1. The **Subtitles** view of the track editor lists subtitle tracks with: language picker (Phase 11),
   a **forced** toggle, and **set default ★** — mirroring the audio editor, scoped to subtitle tracks.
2. **Forced** is its own flag, set/cleared via `mkvpropedit --set flag-forced` (through the
   `SeedingGuard`). It is independent of **default**; a track can be forced, default, both, or neither.
   Surface a short explainer: a forced track shows only foreign-dialogue lines.
3. Reuse `POST /api/media/{id}/tracks/default` semantics for the subtitle default (one default per
   kind); add a `forced` set/clear endpoint or extend the track-flag endpoint with a `flag` param.

### Fetch missing subtitles
4. `POST /api/media/{id}/subtitles/fetch?lang=<code>` (default = the item's resolved language) —
   download an external subtitle from a configured provider and write it beside the media file per
   Jellyfin convention (`{basename}.{lang}.srt`, `.forced` suffix when applicable). Returns what was
   written; triggers a Jellyfin refresh if configured.
5. Provider config (e.g. OpenSubtitles credentials) lives in `config.toml` (new optional section),
   consistent with other credentials — no UI this phase beyond enabling the action.
6. **Fetch missing subtitles ▾** action in the Subtitles view (per-language pick) and a batch variant
   later. Episodes get the same per-episode action.

## Invariants
- **Track/sidecar changes only by explicit action**; mkvpropedit edits pass the `SeedingGuard`.
- Fetched subtitles are **sidecar files**, not muxed in — least-destructive (no remux).
- **Frontend renders server state only** — re-read tracks/sidecars after a fetch.

## Out of scope
- OCR of image subtitles; subtitle **sync/timing** correction; translating subtitles.
- Muxing external subs into the container.
