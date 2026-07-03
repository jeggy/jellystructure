# Phase 128 — Zero-audio-track diagnosis & repair tools in "Tracks & order" (FR-TK2)

## Goal
When a movie/episode has **no audio tracks at all**, the "Tracks & order" editor must stop rendering a
silent blank tab and stop mislabelling the item English. Instead it must **explain why** the tracks are
missing (the real ffprobe reason), **surface** the item (badge + Triage), and offer **actionable
remedies** — a universal `Re-probe file` plus, *only for Sonarr/Radarr-managed items*, a `Re-acquire`
trigger. Files acquired **manually / outside \*arr** (the common case) get manual-repair guidance, not a
dead Sonarr button.

## Root cause (verified against live data + the actual file)
Reported case: **Fumi S01E43** shows English audio and can't be changed to Faroese.

- The stored episode (`config/jellystructure.db`, `fumi-1990`) has **0 tracks total**, `resolvedLanguage
  = en`, `issueCount = 0`. Series is otherwise `fo`.
- Running ffprobe on the real file
  `/mnt/series/jellyfin/Fumi/Season 01/Fumi - S01E43 - Sirkusprinsessan.mp4` (638 MB, exists, readable,
  ffprobe present) returns **`moov atom not found` → `Invalid data found when processing input`** with
  empty `{}` streams. The MP4 is **corrupt/truncated** (missing `moov` index box — a truncated/incomplete
  download). It is *not* a path, permission, or "container has no audio" case; the file itself is broken,
  so no track editing can ever fix it — the file must be replaced.
- `FfprobeRunner.probe()` (`media/FfprobeRunner.kt:47-89`) runs
  `ffprobe … -show_streams '<file>' 2>/dev/null` and collapses **every** failure (corrupt, unreadable,
  parse error, genuinely audio-less) into `emptyList()` — the real reason is discarded.
- With no audio tracks, `LanguageResolver.priorityList([], fallback)` appends the `"en"` fallback
  (`resolver/LanguageResolver.kt:136-143`; same in `resolver/PrimaryAudioLanguage.kt:19-28`), so
  `resolvedLanguage` becomes `en` — an English flag the file never earned.
- `issueCount` counts only tracks whose `language == null`, so a **zero-track** item counts `0` → **no
  badge**, looks "clean", never reaches Triage.
- `TrackEditor.renderList` maps an empty audio model to `listEl.innerHTML = ""`
  (`ui/TrackEditor.kt:265-308`) — the tab is silently blank, no empty state.

Library-wide scope confirmed rare: **2 items** have zero audio tracks (Fumi S01E43; one unmatched
*Tonight Show* episode), both falsely `en`.

## Decisions (confirmed with product owner)
- Scope = **Diagnose + surface**. **No** manual `languageOverride` field / model change.
- Labeling = zero-track items resolve to **unknown (`null`)**, not English; the flag strip shows a
  neutral `?`. TMDB queries still use the fallback language **silently** (metadata keeps fetching).
- **Not every file is \*arr-managed** — Fumi S01E43 was a manual download. `Re-probe file` is the
  universal primary remedy (operator replaces the file, then re-probes); the exact on-disk **path is
  always shown + copyable**; `Re-acquire via Sonarr/Radarr` is rendered **only when the item is actually
  managed** by a configured \*arr, else manual-repair guidance is shown.

## Requirements

### A. Real diagnostic probe — `media/FfprobeRunner.kt`
1. Add `suspend fun diagnose(filePath): ProbeDiagnosis` that does **not** discard stderr — run
   `ffprobe -hide_banner -show_streams -print_format json '<file>' 2>&1` (a diagnostic variant of the
   existing gated `runCommand`; keep `probe()` unchanged on the scan hot path).
2. `data class ProbeDiagnosis(status: ProbeStatus, detail: String, streamCounts: Map<TrackKind,Int>,
   managed: Boolean)` and `enum ProbeStatus { OK, NO_AUDIO, CORRUPT, UNREADABLE, PROBE_MISSING, UNKNOWN }`.
   Classify: missing/`No such file`/`Permission denied` → `UNREADABLE`; `moov atom not found`/`Invalid
   data found`/`Invalid NAL` → `CORRUPT`; `ffprobe: not found` → `PROBE_MISSING`; parsed streams but 0
   audio → `NO_AUDIO`; parsed audio present → `OK`; else `UNKNOWN` (carry the first stderr line in
   `detail`). Resolve the local path via the same library-remap `probe()` callers use.
3. Optional: replace the bare `Logger.warn` at `Scanner.kt:253` with a `diagnose()`-derived reason so
   scan logs say *why*.

### B. Honest labeling — decouple display language from TMDB query language
1. Where a zero-audio track list is fed into the fallback — `Scanner.scanSeries` (~L257-259),
   `resolver/PrimaryAudioLanguage.kt`, and the episode/series re-derivation in `MediaRoutes.kt`
   (~L932-947) and `TrackRoutes.kt` (~L527-568) — when `tracks.none { kind == AUDIO }`, store
   `resolvedLanguage = null`. Keep a **separate local** `tmdbQueryLang` (with the existing fallback) for
   the actual TMDB request, so metadata still fetches. Pattern:
   `val audio = tracks.filter{it.kind==AUDIO}; val resolved = if (audio.isEmpty()) null else
   priorityList(audio.map{it.language}, fb).firstOrNull()`.
2. Consequence: the pagebar/episode audio-flag strip already early-returns for no-audio
   (`MediaDetail.kt:69`); with `resolvedLanguage = null` render a neutral `?`/"unknown" chip instead of
   an English flag.

### C. New endpoints — `server/routes/MediaRoutes.kt` (episode) + `TrackRoutes.kt` (movie)
Near the existing `/tracks/*` routes:
1. `GET  .../tracks/diagnose` → `FfprobeRunner.diagnose(path)` as JSON, including `managed` (true iff a
   Sonarr/Radarr is configured **and** the file resolves under an \*arr root folder / the item is found
   in \*arr).
2. `POST .../tracks/reprobe` → re-run `probe()`, update stored tracks + `issueCount` + `resolvedLanguage`
   (per §B) via `MediaStore.updateOne`, broadcast, return the fresh track list. Lets a repaired/replaced
   file pull its real tracks in without a full library scan.
3. `POST .../reacquire` → **managed-only** best-effort Sonarr/Radarr trigger; reuse `arrRescan?.nudge(item)`
   (`MediaRoutes.kt:887`) / `ArrClient.rescanSeries` (`arr/ArrClient.kt:137`). Return
   `{managed, ok, detail}`; when the item isn't found in \*arr respond `managed=false` (→ UI shows manual
   guidance, not a false "queued").

### D. Empty-state card — `ui/TrackEditor.kt`
In `renderList()` (~L265), when the current-kind model is empty, render an empty-state card instead of a
blank list:
- Headline `⚠ No audio tracks detected` (subs variant for subtitles).
- Reason line from `GET .../tracks/diagnose`, per-`ProbeStatus` friendly copy (e.g. CORRUPT → *"Corrupt
  container — 'moov atom not found'. The file is likely a truncated/incomplete download; replace it to
  recover the audio."*).
- **Always** show the on-disk **file path** (mono + copy button) — essential for manual/non-\*arr repair.
- Actions: **`Re-probe file`** (universal primary). **`Re-acquire via Sonarr/Radarr`** rendered **only
  when `diagnose.managed == true`**; unmanaged → manual-repair copy (*"This file isn't managed by Sonarr
  — replace it at the path above with a complete copy, then Re-probe."*). Keep the external
  `Open in Sonarr/Radarr` link only when managed.
- Hide the reorder hint / pending panel / command preview while empty.

The unified editor is shared by the movie tab (`prefix "trk"`) and the episode modal (`prefix "te"`,
`openEpisodeTrackModal` at `MediaDetail.kt:1926`), so both surfaces are covered by one change.

### E. Surface it — badge + Triage
1. Episode row (`buildEpisodeRow`, `MediaDetail.kt:1257`): when `ep.tracks.none { kind == AUDIO }`,
   render a distinct `⚠ no audio` badge (`--bad`) — derived from `ep.tracks` already in the payload, no
   new field.
2. Triage: surface zero-audio-track episodes as a **distinct** issue type in the existing triage list
   (compute from stored tracks at query time in the media-list path). **Do not** overload `issue_count`
   semantics.

## Scope / critical files
- `media/FfprobeRunner.kt` — `diagnose()` + `ProbeDiagnosis`/`ProbeStatus`.
- `resolver/LanguageResolver.kt`, `resolver/PrimaryAudioLanguage.kt`, `media/Scanner.kt` (~L253-259) —
  zero-audio → `null` display language, fallback only for TMDB query.
- `server/routes/MediaRoutes.kt` (episode) + `server/routes/TrackRoutes.kt` (movie) — diagnose / reprobe
  / reacquire endpoints.
- `arr/ArrClient.kt` (`rescanSeries` L137) — re-acquire.
- `ui/TrackEditor.kt` (`renderList` L265, shell L147), `ui/MediaDetail.kt` (flag chip L69, episode-row
  badge L1257, modal host L1926), `ui/MediaApi.kt` — client calls.

## Non-goals
- No `languageOverride` / manual language-set field (a corrupt file has nothing to tag; a valid
  audio-less file was not observed in the library). Revisit only if `NO_AUDIO`-on-a-valid-file becomes a
  real case.
- No change to reorder/remove/default flows or Phase-109 job queuing.
- No auto-deletion of broken files — surface for the operator to act.

## Acceptance
- Open Fumi → S01E43 → "Tracks & order": empty-state card with the corrupt-file reason, the **copyable
  file path**, and a `Re-probe` button — and because Fumi is **unmanaged**, manual-repair guidance (no
  Sonarr button, no false "queued"). Not a blank tab.
- The pagebar shows a `?`/unknown audio chip, not an English flag; the episode row shows `⚠ no audio`;
  the item appears in Triage.
- Replace the file with a healthy copy at that path → `Re-probe` → the real Faroese audio track appears
  and is editable.
- A Sonarr-managed series with a broken episode shows `diagnose.managed == true` and a working
  `Re-acquire via Sonarr` button.
- No regression on the 46 healthy Fumi episodes (`primaryAudioLanguage` still returns the primary
  language for a non-empty audio list).
