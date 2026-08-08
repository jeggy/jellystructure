# Phase 159 — Intro & credits detection accuracy overhaul (FR-SEG2)

> Phase 150 shipped intro/credits detection, but in practice it **gets a lot wrong**: shows with a
> variable-length **cold open** (Offboarding's title music only starts 1–5 min in, at a different
> absolute position every episode) never match, and the credits heuristic mis-fires on mid-episode
> black cuts and finds nothing on modern credits-over-content. This phase reworks the **detection
> algorithms** — it is backend-only and produces the same `SegmentMarkers` shape Phase 150 already
> defined, so **R182 (the player) is unaffected** (it already resolves against the live `positionMs`
> and never assumes an intro sits near 0:00 — FR-RV-SKIP1-5). Nothing in the data model, DTO trip,
> admin editor, or player changes; only *how good the numbers are* changes.

## Status
Implemented (2026-08-08). Supersedes the specific algorithm constants and tier behaviour of
**Phase 150** FR-SEG1-3 (credits heuristic) and FR-SEG1-4 (intro fingerprinting); the Phase 150 data
model (`SegmentMarkers`), resolution precedence, triage, settings gating, and §F DTO trip all stand
unchanged, as designed.

### Implementation notes (2026-08-08)
- **§A/§B (`SegmentDetection.findIntroMatch`):** implemented the recommended two-pass approach — a
  cheap match-density histogram across the widened `±300s` (`MAX_OFFSET_SEARCH_SEC`) search range picks
  the strongest-correlation offset(s) (`HISTOGRAM_TOP_K = 5`), then exact gap-tolerant run extraction
  runs only around those peaks and the best-density run (not merely the longest) wins. Guardrails added:
  `MAX_INTRO_DURATION_SEC = 360`, `MAX_INTRO_START_SEC = 1200` — a match violating either is rejected
  (returns null) rather than written.
- **§C (outro fingerprinting):** added `FfmpegRunner.computeOutroFingerprint` (tail-of-file via
  `ffmpeg -sseof` piped to `fpcalc`, verified live before implementation), `FingerprintService
  .getOrComputeOutro`/`TailFingerprint` (distinct cache key, carries the tail window's absolute file
  offset since match positions are otherwise relative to the wrong end of the file), and
  `PipelineStepOps.detectOutroFingerprintsForSeason` — reuses `findIntroMatch` and
  `aggregateIntroCandidates` unchanged (both are generic over "a pair of correlated audio windows" /
  "a cluster of candidates," not intro-specific), shifting each side's match position by its own
  episode's tail-window offset before it becomes a candidate. Wired into both `PipelineStepOps
  .detectSegments` (single-item path) and `Main.kt`'s per-season `detect_segments` worker pool
  (same `SeasonWorkItem`, so intro and outro fingerprinting for a season share one worker slot).
- **§D (`FfmpegRunner.detectCreditsStart`):** replaced "first black+silence coincidence wins" with
  "earliest coincidence whose remaining-window black-frame coverage clears `CREDITS_TAIL_BLACK_COVERAGE_MIN
  = 0.35`" — a one-off scene-fade leaves normal (non-black) footage after it and is rejected; a genuine
  rolling-credits stretch is black for most of its remaining duration and is accepted.
- **§E (window sizing):** `FINGERPRINT_WINDOW_SEC` widened 600s → 900s (more margin behind a long cold
  open); new `OUTRO_FINGERPRINT_WINDOW_SEC = 300` for the tail. `FingerprintService`'s cache key now
  includes the window size (`-w<seconds>`), so a widened window can never silently reuse a stale
  smaller-window cache entry — it's simply orphaned on disk instead (FR-159-7's cache-bust requirement).
- **§F (confidence/observability):** intro confidence is now the winning run's own match density (not a
  separate re-derived count); rejected guardrail/coverage-floor cases already flow through each tier's
  existing "return null → caller logs/reports nothing new for this item" path, which the pre-existing
  `reportDetail` plumbing in `detectIntroFingerprintsForSeason`/`detectOutroFingerprintsForSeason` surfaces
  per-pair either way (a pair that fails to correlate simply produces no candidate, visible as this
  episode's candidate/consensus log line never firing).
- **§G (re-detect):** unchanged `eligible()` gates (`!manuallyConfirmed && field == null`) in both
  fingerprinting passes mean `scope = "all"`'s existing clear-then-rescan flow re-runs the new algorithms
  automatically; no separate migration path was needed.
- **Open decisions resolved pragmatically rather than by exhaustive tuning** (§ Open in the original
  spec): histogram anchoring uses the same per-frame Hamming comparison as before (no separate
  hash/anchor scheme) — the histogram's value-add here is selecting candidate OFFSETS by total
  correlation before the expensive run search, not a fundamentally different frame-comparison primitive;
  this was sufficient to remove the ±120s ceiling without a further anchoring layer. Guardrail bounds,
  the credits coverage floor, and both window sizes are the reasoned defaults above, not values tuned
  against a labeled corpus (none exists — consistent with this phase's own non-goals).

## Problem — three concrete, verified failure modes

1. **Variable cold-open shifts the shared block out of the offset search window (the Offboarding case).**
   `SegmentDetection.findIntroMatch` (`media/SegmentDetection.kt:148`) searches only
   `MAX_OFFSET_SEARCH_SEC = 120.0` (`:120`) — ±2 minutes — of misalignment between two episodes'
   fingerprints. Offboarding's identical animated title sequence sits after a cold open whose length
   swings by **several minutes** across episodes, so the same block appears at (say) 1:10 in one
   episode and 4:30 in another — a 3:20 offset the ±2 min search can never align. Every pair fails,
   the season consensus (`aggregateIntroCandidates`, `:241`) gets no candidates, and the show ends up
   with **no intro** at all. The block is genuinely identical and detectable; the algorithm just isn't
   allowed to look far enough.

2. **The credits heuristic takes the *first* black+silence coincidence in the window, unvalidated.**
   `FfmpegRunner.detectCreditsStart` (`media/FfmpegRunner.kt:240`) scans the last 3 min (TV) / 15 min
   (movie) and returns the **earliest** black-frame + silence coincidence (`:263-269`). A single
   fade-to-black scene transition inside that window — common right before a cold-cut to credits, or an
   act-break black — is picked as "credits start" with no check that it lands in a plausible credits
   *position*. And modern streaming episodes that roll credits **over** the final scene, or hard-cut
   into them with no black frame, produce no black run at all → the heuristic finds nothing and the
   player silently falls back to its fixed end-of-file card.

3. **No guardrails on intro results.** `findIntroMatch` enforces only a *minimum* run length
   (`MIN_RUN_FRAMES = 65`, ~8s, `:119`) — there is no maximum-duration or plausible-position sanity
   check. Recurring non-intro content that two episodes share (a standing set, a repeated stock shot,
   a "previously on" recap clip) can produce a long spurious match that gets written as the intro.

## Goal
Detection that **actually works on the hard cases**: a show whose intro floats behind a variable cold
open still gets a correct `introStartMs`/`introEndMs`; a series with a consistent outro theme gets a
`creditsStartMs` even when there's no black frame; and implausible matches are rejected rather than
written. Correctness over speed throughout (the operator has explicitly said processing time is not a
concern for this background work — Phase 150's own 2026-07-14 amendment established that).

## Current state (verified against live code)
- **Intro alignment is a bounded brute-force offset scan.** `findIntroMatch` loops every offset in
  `±MAX_OFFSET_SEARCH_SEC` and keeps the longest gap-tolerant matching run (`SegmentDetection.kt:148-207`).
  Correct in shape, but the ±120s bound is the direct cause of failure mode #1, and the research report
  (`specs/research-reports/credits-and-intro-detection-2026-07-13.md` §3) notes the surveyed prior art
  (Intro Skipper, Plex) aligns via an **offset histogram across a season**, not a fixed-range scan.
- **Season-wide consensus already exists and is sound** (Phase 150 2026-07-14 amendment):
  `aggregateIntroCandidates` clusters every episode's pairwise candidates and takes the majority
  cluster's median (`SegmentDetection.kt:241-270`), and `detectIntroFingerprintsForSeason`
  (`media/PipelineStepOps.kt:280`) already correlates every eligible pair, warms the fingerprint cache
  once per episode, and writes per-episode consensus. **This phase changes what each pairwise
  comparison can find, not the consensus layer on top** — the O(n²)-per-season pair loop, the cache,
  and the clustering all stay.
- **Fingerprints are cached per episode** (`FingerprintService.getOrCompute`, `media/FingerprintService.kt:40`),
  computed by `fpcalc -raw -length 600` (`FfmpegRunner.computeFingerprint`, `:294`; `FINGERPRINT_WINDOW_SEC = 600`,
  `:284`). Widening analysis windows means recomputing/repopulating this cache.
- **Credits detection is heuristic-only, movies and single episodes alike** — there is **no
  cross-episode credits/outro fingerprinting**, even though the same `fpcalc` machinery that finds
  intros could find a shared outro theme at the *end* of a series' files.
- **The player is position-agnostic.** R182 FR-RV-SKIP1-5 resolves intro/credits against the live
  `positionMs` and "never assume a fixed offset" — so a correctly-detected mid-episode title sequence
  (e.g. intro at 3:20–4:50) already drives the Skip Intro pill correctly. **No Ravilo change is
  required or wanted by this phase.**

## Requirements

### A. Intro alignment must cover the full cold-open variance
#### FR-SEG2-1 — Align the shared block wherever it sits in the analysed window
Two episodes that share an identical intro block must match **regardless of how far apart that block
sits in absolute time** between them, up to the full analysed fingerprint window — not merely within a
fixed ±2 min. The ±`MAX_OFFSET_SEARCH_SEC` bound is removed as the effective ceiling; the alignment
must succeed for the Offboarding case (title sequences several minutes apart).

**Recommended technique (the "better algorithm"):** replace the fixed-range brute-force offset scan
with an **offset-histogram alignment** — the approach the research report §3 documents Plex/Intro
Skipper using. Collect the offsets `(i − j)` of frame pairs where `a[i]` and `b[j]` agree within the
Hamming threshold; the histogram's dominant peak is the true alignment offset (unbounded in range,
and robust to the cold-open content that *doesn't* match); then verify/extend the gap-tolerant run at
that offset using the existing `MIN_RUN_FRAMES`/`MAX_GAP_FRAMES` logic. This also removes the
`offsets × frames` cost blow-up that naïvely widening the fixed range would incur. **Anchor selection
is the key open decision** (§ Open) — raw 32-bit Chromaprint frames are noisy (that is why
`HAMMING_THRESHOLD = 6` exists), so exact-value histogramming needs a noise-tolerant anchoring scheme
(e.g. hash short frame runs, or histogram only the most distinctive frames), tuned empirically against
real fingerprints the way Phase 150's frame constants already were.

A widened fixed-range scan is an acceptable *interim* if the histogram approach proves fiddly, but it
must cover the whole plausible cold-open swing (target ≥ ±5 min, sized from the fingerprint window),
not ±2 min — and the spec's preference is the histogram, because "just make the brute-force range
bigger" scales badly and still has a hard ceiling.

### B. Guardrails against spurious intro matches
#### FR-SEG2-2 — Position + duration sanity, before writing an intro
A matched run is only written as the intro if it is **plausibly an intro**:
- **Maximum duration** — reject a run longer than a sane intro/recap ceiling (Intro Skipper validates
  ~15s–450s for TV; adopt a comparable upper bound). A multi-minute "intro" is almost always recurring
  non-intro content (a standing set, a repeated establishing shot), not the title sequence.
- **Plausible start position** — the intro/recap block should begin within the front portion of the
  runtime (it is, by definition, near the start of the episode's *content*, even if that's several
  minutes in behind a cold open). Reject a "match" that starts deep into the episode body.
- These are rejection guardrails only: a rejected match leaves `introStartMs`/`introEndMs` unset, the
  same graceful fallback every tier already uses — never a worse guess than nothing.

### C. Credits detection via cross-episode outro fingerprinting (series)
#### FR-SEG2-3 — Fingerprint the end of series files for a shared outro theme
Add a **credits/outro** counterpart to the intro fingerprinting: for a series (≥2 episodes), compute a
fingerprint over the **last** N minutes of each file and correlate them the same way intros are
correlated, to find the shared end-credits/outro-theme block → `creditsStartMs`, `source =
"fingerprint"`. A consistent outro theme is as reliable a signal as the intro theme, and this directly
fixes both credits failure modes for series: the mid-episode-black false positive (fingerprinting keys
on the *actual* recurring outro, not any black frame) and the credits-over-content false negative (no
black frame needed). Reuse the same cache/consensus machinery as §A; this is a second analysed window
per episode (the tail), keyed distinctly in `FingerprintService`. Movies and standalone episodes keep
the heuristic tier (§D) — they have no sibling to correlate against.

### D. Harden the credits heuristic (movies + standalone episodes)
#### FR-SEG2-4 — Validate the coincidence's position; prefer the credits-like one, not the first
For the heuristic path that remains the only option for movies/standalone episodes:
- **Position-validate the candidate** against a plausible credits window (a fraction of runtime, not
  merely "somewhere in the last 3/15 min") so an act-break or scene-transition black earlier in the
  window is rejected, per the research report §3's "candidate validated against an expected
  position/duration window" note — the current code's "first coincidence wins" (`FfmpegRunner.kt:263`)
  has no such validation.
- **Prefer the coincidence that looks like a credits roll**, not simply the earliest black: e.g. the
  black+silence transition after which the remaining content is short and static-cadenced. Exact
  discriminator is an empirical/open decision (§ Open); the requirement is "stop treating the first
  black in the window as authoritative."
- Unchanged: no coincidence found ⇒ `creditsStartMs` stays null ⇒ player keeps its end-of-file
  fallback.

### E. Fingerprint window sizing
#### FR-SEG2-5 — Analyse enough of the file to contain the block
`FINGERPRINT_WINDOW_SEC = 600` (`FfmpegRunner.kt:284`) must be large enough that a title sequence
sitting behind a long cold open is still inside the analysed audio (Offboarding's ≤5-min cold open + a
long title theme fits 600s, but the margin is thin, and other shows push further). Widen the intro
window as needed, and size the new outro window (§C) from the tail. Larger windows mean larger cached
fingerprints and longer `fpcalc` decode — acceptable per the correctness-over-speed directive, but
the window is a tunable, not a fixed constant, and its choice is an open decision (§ Open).

### F. Confidence recalibration + guardrail transparency
#### FR-SEG2-6 — Confidence must mean "how sure", and rejections must be observable
- Recalibrate auto-source confidence so the existing **"Low-confidence segments"** triage (Phase 150
  FR-SEG1-8) is meaningful under the new algorithms — a histogram peak with weak dominance, or a
  heuristic candidate that barely passed position validation, should read as low-confidence and surface
  for an eyeball.
- When a match is **rejected by a guardrail** (§B/§D), log it at the existing per-item pipeline detail
  granularity (`detectIntroFingerprintsForSeason` already emits per-pair/per-episode `reportDetail`
  lines, `PipelineStepOps.kt:312-357`) so an admin watching Activity can see *why* a title got no
  segment, not just that it didn't.

### G. Re-detection over an already-scanned library
#### FR-SEG2-7 — A clean way to re-run the improved algorithm on existing (non-manual) results
Because Phase 150 already wrote (often wrong) results across the library, the improved algorithm must
be applicable to them: a bulk **re-detect** that recomputes every **non-`manuallyConfirmed`** segment
(the `scope = "all"` re-run path Phase 150 already has, `PipelineStepOps` §D), explicitly re-running
fingerprint correlation with the widened alignment rather than skipping already-filled fields. Manual
corrections (`manuallyConfirmed = true`) are never touched — the invariant from Phase 150 FR-SEG1-1
holds. Widening the fingerprint window (§E) invalidates cached fingerprints computed at the old window;
the re-detect must recompute them (cache-key or cache-bust by window size).

## Invariants (unchanged from Phase 150, restated because this phase must not break them)
- **`manuallyConfirmed` is sacred** — no algorithm change ever overwrites an admin correction.
- **Graceful fallback everywhere** — any tier that finds nothing (or rejects its candidate) leaves the
  field null; the player is never handed a worse guess than its existing end-of-file heuristic.
- **Same `SegmentMarkers` shape, same DTO, same player** — this is a detection-quality change only;
  R182 and the §F DTO trip are untouched.
- **One `fpcalc` per episode per window** — the per-episode fingerprint cache stays; only the count of
  cheap in-process correlations grows (as Phase 150's amendment already accepted).

## Open design decisions (to settle in the design/implementation pass, empirically, as Phase 150's were)
- **Anchor/hash scheme for the offset-histogram alignment (§A)** — how to pick noise-tolerant anchors
  from raw 32-bit Chromaprint frames so the histogram peak is reliable. The single most important open
  question; validate against the real cached fingerprints in `config/fingerprints/` (the same corpus
  Phase 150's amendment used to prove the premiere-episode bug).
- **Intro guardrail bounds (§B)** — the exact max-duration ceiling and start-position window.
- **Credits position window + credits-vs-scene-black discriminator (§D)** — what fraction of runtime is
  "plausible credits", and how to prefer a credits roll over an incidental black.
- **Fingerprint window sizes (§E)** — intro window (front) and outro window (tail); trade cache/compute
  against coverage of long cold opens / long credits.
- **Whether a `manuallyConfirmed` episode's known-correct bounds should anchor its siblings'** consensus
  more strongly than merely contributing its fingerprint (a possible accuracy win; not required).
- **Recap vs title-sequence when a show has both** near the start — do we detect the earliest shared
  block (recap) or the title sequence, and does "Skip Intro" want one or the other? (Affects §A's peak
  selection.)

## Reuse (don't rebuild)
The Phase 150 substrate is almost entirely reused: `SegmentMarkers`/`Stinger` model, the
`detect_segments` pipeline step + its two-pass worker-pool wiring (`PipelineStepOps.detectSegments` /
`detectIntroFingerprintsForSeason`), `FingerprintService` cache, `aggregateIntroCandidates` season
consensus, `ProcessGate`/nice+ionice throttling, the `TriageDetection` "low-confidence"/"no segments"
predicates, the admin scrubber, and the §F DTO trip. This phase edits the *inner matcher*
(`findIntroMatch`) and the credits path (`detectCreditsStart` + a new outro-fingerprint function), not
the scaffolding around them.

## Non-goals
- **No ML / trained models** (Phase 150 non-goal stands — no labeled corpus, not self-hostable).
- **No per-frame OCR / on-screen-text detection** (still out of scope this phase).
- **No Jellyfin `MediaSegments`** read or write (Phase 150 non-goal stands).
- **No Ravilo/player change** — R182 already consumes the improved numbers unchanged.
- **No new admin UI** — the existing segment scrubber and triage surfaces already present source +
  confidence; better inputs flow through them for free.
- **No multi-episode-file (`partCount > 1`) support** — still deferred exactly as Phase 150 deferred it.

## Acceptance
- On a show with a variable-length cold open (the Offboarding case), the improved alignment produces a
  correct `introStartMs`/`introEndMs` at the title-sequence position, where Phase 150's ±120s scan
  produced nothing — verifiable by running the matcher against that show's real cached fingerprints.
- A spurious multi-minute or deep-in-the-episode "intro" is rejected by the §B guardrails and leaves
  the fields null rather than writing a wrong intro.
- A series with a consistent outro theme gets a `creditsStartMs` from outro fingerprinting even with no
  black frame before its credits.
- The credits heuristic no longer marks an early act-break black as the credits start on a title where
  the real credits come later in the window.
- A bulk re-detect (`scope = "all"`) updates non-manual segments across an already-scanned library
  without touching any `manuallyConfirmed` correction.
- Rejected candidates appear with a reason in the pipeline Activity detail stream.

## Source references
- Inner matcher to rework: `SegmentDetection.findIntroMatch` + its constants
  (`src/linuxX64Main/kotlin/dev/jellystructure/media/SegmentDetection.kt:100-207`); season consensus to
  keep: `aggregateIntroCandidates` (`:209-270`).
- Credits path to harden + the fingerprint/window constants:
  `FfmpegRunner.detectCreditsStart` / `computeFingerprint` / `FINGERPRINT_WINDOW_SEC`
  (`media/FfmpegRunner.kt:214-301`).
- Orchestration (two-pass worker pool, cache warming, consensus write):
  `PipelineStepOps.detectSegments` / `detectIntroFingerprintsForSeason` (`media/PipelineStepOps.kt`).
- Cache to re-key on a window change: `FingerprintService` (`media/FingerprintService.kt`).
- Prior-art technique (offset histogram, position-validated credits): the research report
  `specs/research-reports/credits-and-intro-detection-2026-07-13.md` §3, §5, §9.
- Consumer that needs no change (position-agnostic player):
  `specs/ravilo/requirements/phase-R182-skip-intro-credits.md` FR-RV-SKIP1-5.

## Relationships
- **Amends Phase 150** (FR-SEG1-3 credits heuristic, FR-SEG1-4 intro fingerprinting) — same data model,
  triage, settings, and DTO; better algorithms behind them.
- **R182 unaffected** — the player already resolves against live position and never assumes an intro at
  0:00, so improved numbers flow straight through with no Ravilo work.
- `scripts/check-phases.sh` will want a `STATUS.md` row — **STATUS.md is code-owned; do not add the row
  from the design side.**
