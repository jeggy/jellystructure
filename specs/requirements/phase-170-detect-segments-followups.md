# Phase 170 — `detect_segments` follow-ups: dedicated process slots, source-precedence guard, history logging

> Three related gaps in the `detect_segments` pipeline (Phase 150/159/163/164), previously diagnosed and
> offered but never fixed or turned into a spec: the segment-detection lane's heavy-decode ffmpeg/fpcalc
> calls share the same 16-slot `ProcessGate` as every other process-spawning call in the backend
> (including request-serving work), so a big show's detect run can make an unrelated scan/artwork
> request wait; a `force=true` re-detect only ever checked a marker's `locked` flag, never its `source`,
> so a lower-precedence auto-detection could silently clobber a higher-precedence one (e.g. a fingerprint
> guess overwriting an exact chapter-title match); and no detect_segments write, of any kind, was ever
> recorded to a title's own History tab — only to the ephemeral, non-title-scoped Activity/pipeline log.

**Status:** Implemented 2026-08-21.

## §1 — Dedicated process-slot pool for the segment-detection lane

**Problem.** `FfprobeRunner`/`FfmpegRunner`'s heavy-decode calls (`detectCreditsStart`'s blackdetect/
silencedetect, `computeFingerprint`/`computeOutroFingerprint`'s Chromaprint decode, `computeWaveform`'s
raw-PCM extraction) already carry `nice -n 19 ionice -c3`/`-threads 2` CPU-priority protection (Phase
150/159) — that stops a segments run from starving *CPU*, but does nothing about *slot* contention.
Those calls shared the exact same 16-permit `ProcessGate` semaphore as every other `popen` call in the
backend: regular scan-time ffprobe, artwork resize, screengrab, track-editing remux. A big show's
`detect_segments` run (Phase 164's job lane, `behavior.segment_workers` concurrent jobs, each running
several of these calls per episode) could occupy enough of those 16 permits to make an unrelated scan or
artwork request queue up behind it.

**Fix.** New `ops/SegmentProcessGate.kt` — same shape as `ProcessGate` (a `Semaphore` + matching fixed
thread pool, `withPermit` suspend wrapper), sized to 4 (a small multiple of `segment_workers`' default
of 2, deliberately far below `ProcessGate`'s 16). `FfmpegRunner.kt`'s `captureCommand`/
`captureBinaryCommand` were split into a gate-agnostic raw popen/read core plus two thin wrappers —
`captureCommand` (shared `ProcessGate`, unchanged callers) and a new `captureCommandSegments`
(`SegmentProcessGate`). The three segment-detection functions (`detectCreditsStart`,
`computeFingerprint`, `computeOutroFingerprint`) and `computeWaveform` (via `captureBinaryCommand`, now
routed through `SegmentProcessGate` too — the trim view's own heavy-decode call, same starvation
profile) now go through the new gate; `probeDurationSeconds` (Phase 109 remux progress, unrelated
subsystem) stays on the shared `ProcessGate`. `FfprobeRunner.probe`/`chapters`/`duration` are
deliberately **not** moved — they're used by both general scanning and segment detection, are cheap
(no video/audio decode), and separating call-site-by-call-site there would be a much larger, riskier
change for uncertain benefit.

## §2 — Source-precedence guard on `force=true` re-detection

**Problem.** `detectForPath`'s `introWritable`/`creditsWritable` and both fingerprint functions'
`eligible()` only ever checked `existing?.locked` — never `existing?.source`. With `force=false`
(routine scheduled pass) this is harmless (an existing row of any source is never touched). With
`force=true` (an explicit operator re-detect), an unlocked row could be silently overwritten by a
*lower-quality* result: a season-wide fingerprint consensus (empirically strong, not authoritative)
overwriting an exact chapter-title match (an author-embedded editorial boundary), or a blackdetect/
silencedetect heuristic guess overwriting either.

**Fix.** `SegmentSource.precedence(source: String?): Int` (`MediaSegmentStore.kt`) — `MANUAL` (4) >
`CHAPTER` (3) > `FINGERPRINT` (2) > `HEURISTIC` (1) > everything else / null (0, including the
candidate-only `JELLYFIN`/`TMDB` sources, which no detection tier ever writes). An incoming write may
only replace an existing row when `precedence(incoming) >= precedence(existing.source)` — equal-tier
overwrites (a fresh chapter hit replacing an older one, a fresh fingerprint pass replacing an older one)
stay allowed, matching pre-fix same-source re-detection behavior. Wired into `detectForPath`'s two
`SegmentSource.CHAPTER` writes and one `SegmentSource.HEURISTIC` write, and both
`detectIntroFingerprintsForSeason`/`detectOutroFingerprintsForSeason`'s `eligible()` gates (checked
*before* the expensive fpcalc/correlation work, not just at write time — a non-precedence-eligible
episode is skipped the same way a locked one already was).

Note: `detectForPath` never had a CHAPTER-vs-HEURISTIC conflict internally (chapter tier is tried first
and returns immediately on any hit, HEURISTIC is only reached when chapter finds *nothing*) — the real
risk was always cross-tier, between the cheap tier (`detectChapterAndHeuristic`, per-item) and the
fingerprint tier (`detectIntroFingerprintsForSeason`/`detectOutroFingerprintsForSeason`, per-season),
which the bulk pipeline step runs as separate passes.

## §3 — History logging for auto-detected segment writes

**Problem.** `mediaHistory.record(...)` was only ever called from `reorder_tracks`/`remove_track`
(`MediaJobQueue.kt`) — never from anywhere in the segments lane, nor from `SegmentRoutes.kt`'s manual
per-marker edit endpoints. A title's own History tab had no record that its segment markers had changed
at all, auto-detected or manual — only the ephemeral, non-title-scoped Activity/pipeline log
(`Logger.info(..., "pipeline")`) mentioned it in passing.

**Fix (scoped to the auto-detection lane, matching the original bug report).** `detectChapterAndHeuristic`,
`detectIntroFingerprintsForSeason`, and `detectOutroFingerprintsForSeason` each gained an optional
`mediaHistory: MediaHistory? = null` parameter (nullable/defaulted so the already-dead-code single-item
`detectSegments` wrapper didn't need a forced update at every call site). **One roll-up entry per run**,
only when something was actually written — not one per episode: a season's fingerprint pass can touch
dozens of episodes in one call, and per-episode logging would flood the shared, 2000-row-capped,
revertable-edit-oriented history table. `MediaJobQueue.kt`'s five call sites (movie/season/episodes ×
chapter-heuristic, season × both fingerprint directions) now pass its own already-injected
`mediaHistory` field through.

**Deliberately out of scope:** `SegmentRoutes.kt`'s manual per-marker edit endpoints (`PUT
/{itemId}/{kind}`, the season "apply consensus" bulk action) still don't log to History either — that's
a distinct, newly-noticed gap (manual edits, not auto-detection), not what the original bug report
flagged. Left as a candidate for a future phase if wanted.

## Non-goals

- No change to `ProcessGate` itself, or to any of its other callers.
- No change to detection *algorithms* (chapter matching, the credits heuristic, fingerprint consensus) —
  purely gating/logging.
- No UI change — the History tab already renders whatever `MediaHistory.forItem()` returns; new
  `detect_segments`-action rows just start appearing there.
- Manual segment-editor history logging (see §3's out-of-scope note).

## Verification

- `compileKotlinLinuxX64` clean.
- `linuxX64Test` — new `SegmentSourcePrecedenceTest` (7 cases covering every precedence pairing) added;
  full suite (131 tests) green, no regressions.
- Manual: trigger a force re-detect on a title with an existing chapter-sourced marker and confirm the
  fingerprint/heuristic tiers leave it untouched; trigger detect_segments on a large show and confirm an
  unrelated scan/artwork request doesn't stall behind it; confirm a title's History tab shows a
  `detect_segments` entry after an auto-detection run wrote something — all left for the user (require a
  live library + backend restart, out of scope for this session).
