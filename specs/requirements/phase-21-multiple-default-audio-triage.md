# Phase 21 — Flag Multiple Default Audio Tracks (FR-DA1)

## Problem
Some movies and TV episodes have **more than one audio track marked as `default`**. This is invalid
(a container should have exactly one default audio track) and causes unpredictable playback. There is
currently no way to spot these.

## Current state (as-is)
- `Track.default: Boolean` comes from ffprobe `disposition.default`. Nothing checks for *multiple*
  defaults.
- Triage already has two issue categories, computed in `TriageRoutes.kt`:
  - **untagged** — AUDIO/SUBTITLE tracks with `language == null`.
  - **cascadeMismatch** — resolved-language audio track is not the current default
    (`MediaItem.detectCascadeMismatch()`).
- `TriageCount = { untagged, mismatch, total }`; `TriageItem` carries `untaggedTracks`,
  `cascadeMismatch`, and (for TV) `episodeIssues`.
- The sidebar Triage badge uses `GET /api/triage/count` `.total`.

## Requirements

### Backend — detection
1. Add a third detection: an item (movie) or episode (TV) has **two or more AUDIO tracks with
   `default == true`**.
2. New model on the triage DTOs:
   - `MultiDefaultIssue { defaultSpecifiers: List<String> }` (the specifiers/labels of the offending
     audio tracks).
   - Add `multiDefault: MultiDefaultIssue? = null` to `TriageItem`.
   - For TV, add `multiDefault: MultiDefaultIssue? = null` to `EpisodeTriageItem`, and an episode is
     surfaced if it has untagged tracks **or** missing overview **or** multiple default audio tracks.
3. `MediaItem.detectMultiDefaultAudio()` helper: returns the offending specifiers when
   `tracks.filter { kind == AUDIO && default }.size > 1`, else null. TV evaluates per episode.
4. `TriageCount` gains a `multiDefault` field: `{ untagged, mismatch, multiDefault, total }` where
   `total = untagged + mismatch + multiDefault`. The sidebar badge keeps using `total`.
5. `GET /api/triage` includes items that have only a multi-default issue (no untagged / mismatch).

### Frontend — Triage UI
6. In `Triage.kt` (movies) and `SeriesTriage.kt` (per episode), render a distinct **error card/row**
   for the multi-default case:
   - Red badge "Multiple default audio tracks".
   - List the offending tracks (specifier · language · title · codec) each shown as `default ✓`.
   - A one-line explanation: "A file should have exactly one default audio track. Pick the one to
     keep as default — the others will be cleared."
7. Resolution action: a control to choose **which single audio track stays default**; applying it
   sets that track default and clears `default` on the other audio tracks. Reuse the existing
   set-default plumbing:
   - `mkvpropedit` for `.mkv` (`flag-default` 1 on chosen, 0 on the rest), `ffmpeg -c copy` otherwise
     — the existing `setDefault` helpers already set one and clear the siblings, so a single
     set-default call on the chosen track resolves the issue.
   - After the operation, re-`ffprobe`, recompute `issueCount`, persist, and (if configured) trigger
     a Jellyfin item refresh — mirroring the existing triage language-assign flow.
8. The Library poster badge already shows issue/mix state; multi-default items will surface through
   the existing `issueCount`/attention filter only if counted there. **Decision:** keep `issueCount`
   as the untagged-track count (unchanged, it has NFO-coverage meaning); surface multi-default purely
   through Triage and the `attention` filter. Extend the `attention` filter in `MediaStore.list` to
   also include items where `detectMultiDefaultAudio()` is non-null.

## Invariants
- Track flags are still only changed by **explicit user action** (constitution §1) — detection is
  automatic, the fix is a button.
