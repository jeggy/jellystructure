# Phase 46 — Track language writes must actually persist (FR-TL1)

**Status:** Planned · _setting a track's audio/subtitle language reports success but the tag is never
written for MP4 (and is fragile for MKV); the previewed command is wrong; the API reports the
requested value instead of what's on disk._

## Problem
Setting a track's language from the **Tracks & order** editor (movie or series episode) **silently
fails to persist**, especially for `.mp4` files:

1. The UI previews `mkvpropedit "<file>" --edit track:a1 --set language=fo`.
2. The backend actually runs an **ffmpeg remux** (because the file is `.mp4`, not `.mkv`):
   `ffmpeg … -map 0 -c copy -metadata:s:1 language=fo …`.
3. The UI reports **success**; but the track still shows **no language**, and after a page refresh the
   change is gone — it never took effect on disk.

### Root causes (traced in code)
1. **Wrong language-code format for the container (primary; MP4 path confirmed).** The frontend/store
   use **2-letter ISO-639-1** codes (the picker emits `fo`, `da`, `en`). The write path passes that
   code **verbatim** to the tool:
   - **MP4 (ffmpeg) — confirmed.** `FfmpegRunner.setLanguage` runs `-metadata:s:N language=fo`.
     ffmpeg's MP4 muxer stores per-stream language in the `mdhd` box, which requires a **3-letter
     ISO-639-2** code; given a 2-letter code it can't pack, ffmpeg writes **`und`** (undefined).
     `FfprobeRunner` then treats `und`/blank as **untagged** (`language = null`, FfprobeRunner.kt
     ~L72-74) → the change "disappears." This is the user's reproduced case.
   - **MKV (mkvpropedit) — defensive (inferred, not reproduced).** `MkvpropeditRunner.setLanguage`
     runs `--set language=fo`. The legacy Matroska `Language` element is ISO-639-2; a 2-letter value
     is fragile — depending on mkvtoolnix version it may be rejected or stored in a form tools read
     inconsistently. We harden this path even though the confirmed failure is MP4.
   - The codebase has a **3→2** map (`LanguageResolver.ISO2TO1`, for TMDB) but **no 2→3 map** for
     writes — the documented assumption is literally "ffprobe tags tracks with ISO 639-2 three-letter
     codes; TMDB only accepts ISO 639-1 two-letter codes." The write boundary needs the inverse.
2. **Silent success — no write verification.** `FfmpegRunner.setLanguage` / `MkvpropeditRunner.setLanguage`
   return `true` on shell **exit 0**. ffmpeg exits 0 even when it wrote `und`. The route reports
   success without confirming the tag actually changed.
3. **API response diverges from disk.** The route re-probes (`FfprobeRunner.probe`) and stores the
   **true** (still-null) tracks, but responds `{"language": req.language}` — the **requested** value,
   not the probed result. (Both the movie route in `TrackRoutes.kt` and the episode route in
   `MediaRoutes.kt` `/episodes/{epFilename}/tracks/language` have this shape.)
4. **The frontend never adopts disk truth — it commits its optimistic guess.** Even if the route
   returned the probed value, the client would ignore it: `MediaApi.setTrackLanguage` returns only
   `String?` (an error, or `null` on 2xx) and **discards the response body**; on success
   `TrackEditor.applyChanges` commits the **staged model** (`audioModel`, holding the 2-letter `fo`)
   as the new baseline (TrackEditor.kt ~L480-482). So inside the popup it shows "Applied ✓" with `fo`,
   but closing the modal re-renders the episodes tab from the stored item (re-probed `null`) → the
   track shows **missing** again. This is the most direct cause of the reported symptom, and it means
   fixing the route alone is **not** sufficient.
5. **Misleading command preview.** `TrackEditor.kt` builds the previewed command **client-side**,
   always as `mkvpropedit "<file>" --edit <sel> --set language=<2-letter>` (TrackEditor.kt ~L309-315),
   regardless of container. There is **no** language plan on the backend — `…/tracks/plan` only
   computes the **set-default** command — so nothing server-side corrects it. For MP4 the real command
   is an ffmpeg remux, and the real written code is 3-letter — the preview is wrong on both counts.
   (Phase 41 already requires the preview to reflect the tool that actually runs.)

## Goal
Setting a track's language **actually writes a tag that survives re-probe**, on both MP4 and MKV; the
API and UI report **what is on disk** (not the request); and the previewed command matches the tool
and code that will really run. A write that didn't take effect surfaces as a **clear error**, never a
false success.

## Current state (as-is)
- Write path: `…/tracks/language` (movie: `TrackRoutes.kt`; episode: `MediaRoutes.kt`) → ext check →
  `MkvpropeditRunner.setLanguage` (mkv) or `FfmpegRunner.setLanguage` (else) by **absolute stream
  index** → re-probe → `store.updateOne(...)` → respond.
- `LanguageResolver.normalize` maps 3→2 via the partial `ISO2TO1` map (used for TMDB).
- `langDisplay` already renders both 2- and 3-letter codes (`LANGUAGES` + `LANGUAGES_3`).
- Validation regex on the route accepts `[a-zA-Z]{2,8}(-…)*` — i.e. it does **not** enforce/normalize
  a particular code length.

## Requirements

### A. Map the code to the container's required form before writing
1. Introduce a canonical **ISO-639-1 ↔ ISO-639-2 mapping** in **`commonMain`** (`LanguageResolver`), so
   frontend and backend share one source of truth. **Build the 2→3 direction fresh — do not just
   invert `ISO2TO1`:** that map has only ~64 entries while the picker's `LANGUAGES` list offers ~149,
   so the inverse would silently miss ~85 languages. The 2→3 map must cover **every** code the picker
   can emit (or, for any code with no mapping, **fail per §A2**). Fold the frontend's separate
   `LANGUAGES_3` list into / derive it from this shared map rather than leaving a third divergent list.
2. At the write boundary, **convert the incoming code to the form the target container/tool needs**:
   - **MP4 / ffmpeg `mdhd`** → 3-letter ISO-639-2 (e.g. `fo` → `fao`). Prefer the **ISO-639-2/T**
     ("terminological") code where B/T differ.
   - **MKV / mkvpropedit** → set the legacy `language` element to the 3-letter ISO-639-2 code **and**,
     where mkvtoolnix supports it, also set `language-ietf` to the BCP-47 tag (`fo`) so modern players
     and re-probes are unambiguous.
   - If a code has no known mapping, **fail with a clear error** rather than writing a guess or `und`.
3. Accept that **ffprobe will read the tag back as 3-letter** (e.g. `fao`); the store will hold the
   3-letter code. Display already normalizes for show (`langDisplay`), and Phase 45 §C fixes the
   picker's current-selection highlight to match a 3-letter stored value.

### B. Verify the write actually changed the tag
1. After running the tool, **re-probe and confirm** the target track's language now equals the
   intended language, comparing via `LanguageResolver.normalize()` so the check is **ISO-639-2 B/T-
   agnostic** (`ISO2TO1` maps both `ger`/`deu`→`de`, `fre`/`fra`→`fr`, etc.) and `fao` ≡ `fo`. If it
   does **not** match (e.g. ffmpeg wrote `und`), treat the operation as **failed**.
2. On failure, respond with a non-2xx and an actionable message (e.g. "ffmpeg did not apply the
   language tag — the file may use a container that doesn't support per-stream language, or the code
   has no ISO-639-2 mapping"). Do **not** report success, and do **not** leave a half-written temp
   file (the runners already `remove()` the tmp on failure — keep that).

### C. The whole pipeline reports what's on disk, not the request
1. **Server side.** Both language routes (movie + episode) must respond with the **re-probed** track
   language (the actual stored value), not `req.language`. The store update already uses the re-probed
   tracks — keep that; align the **response** (and the history-log detail) to the probed result.
2. **Client side (required — fixing the route alone is not enough).** `MediaApi.setTrackLanguage` /
   `setEpisodeTrackLanguage` currently return only `String?` and **discard the response body**; change
   them to surface the **re-probed language** on success. `TrackEditor.applyChanges` must then **adopt
   that returned value into the staged model** before committing the baseline — instead of committing
   its own optimistic 2-letter guess (TrackEditor.kt ~L480-482). After Apply, the track row shows the
   value that is **actually on disk**, so leaving/re-opening the editor or refreshing the page shows
   the same thing.
3. This is the constitution rule **FE renders server-pushed state only** (`fe-reflects-be-no-derived-state`)
   applied end-to-end: no optimistic echo survives an Apply.

### D. Truthful command preview
1. The previewed "exact command" must reflect the **tool that will actually run** for this file's
   container — `mkvpropedit … --set language=…` for MKV, the `ffmpeg … -map 0 -c copy -metadata:s:N
   language=…` remux for MP4/other — and the **resolved code that will be written** (3-letter), not
   the raw 2-letter picker value.
2. **There is no language plan today** — `…/tracks/plan` only computes the **set-default** command,
   and the language preview is built entirely client-side (TrackEditor.kt ~L309-315), hardcoded to
   `mkvpropedit` + 2-letter. Fix one of two ways: **(a)** extend the backend plan to cover the staged
   language op (container- and code-aware) and have the client render that string; or **(b)** make the
   client-side builder container-aware and code-aware (pick `mkvpropedit` vs `ffmpeg` by extension,
   show the resolved 3-letter code). Prefer (a) so the previewed command and the executed command come
   from one source.
3. Keep the **cost indicator** honest: `mkvpropedit` flag/language edits are "instant"; the ffmpeg
   path is a **remux** ("rewrites the file — minutes on large files"), matching Phase 41 §B4.

### E. Episode + movie parity, ownership, seeding guard unchanged
1. Apply identical handling to the **movie** track route and the **episode** track route (and the
   per-episode editor modal). Both must persist, verify, and report disk truth.
2. Preserve the existing **POSIX ownership/permission restoration** (`withOwnershipPreservation`) and
   the **qBittorrent seeding guard** (409/503) behaviour — no change.

## Invariants
- A reported **success means the tag is on disk** and survives re-probe; a write that didn't take
  effect is an **error**, never a silent success.
- The app and tools agree on language codes: **2-letter ISO-639-1 in the UI/metadata layer**,
  **container-correct (3-letter ISO-639-2, plus BCP-47 for MKV) at the file-tag boundary**, with one
  shared bidirectional map.
- **FE renders server-pushed state only** — the UI shows the re-probed language, not the request.
- **No re-encode** — language tags are written via `mkvpropedit` (MKV, in place) or `ffmpeg -c copy`
  remux (MP4/other); ownership + permissions preserved.

## Interacts with (not re-specified here)
- **Phase 45** fixes the picker's styling and its current-selection highlight for 3-letter codes.
- **Phases 21/27/39/41/42** (default flag, subtitle forced, unified/episode editors) share the same
  write path — the verify-and-report-truth principle (B/C) should extend to those ops too, but this
  phase is scoped to **language**.
- The **language resolver / NFO / TMDB** layer keeps using 2-letter codes; only the **file-tag write**
  boundary converts.

## Out of scope
- Re-tagging existing libraries in bulk; a migration to rewrite `und`/2-letter tags already on disk.
- Changing how scanning stores codes beyond what re-probe already returns.
- Container conversion (e.g. remuxing MP4→MKV to gain better language support).

## Design reference
No new UI surface — `design/app/media.html` (Tracks & order) and `design/app/series.html` (episode
editor) already show the command preview + cost; this phase makes the **command text and the result
state truthful** to what the backend does.
