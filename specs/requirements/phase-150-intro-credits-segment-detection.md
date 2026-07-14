# Phase 150 — Intro & credits segment detection + admin management (FR-SEG1)

> Detect **where each file's intro and credits segments actually start**, store it per item, let an admin
> review and correct it, and expose it to Ravilo — so the player can offer a Netflix/Plex-level **Skip
> Intro** / **Skip Credits** instead of the current fixed "N seconds before the file ends" heuristic. This
> is the jellystructure (admin + backend) half; the Ravilo player half is **R182**, which consumes the data
> this phase produces. Graduated from the research report
> `specs/research-reports/credits-and-intro-detection-2026-07-13.md` (read it for the full feasibility /
> prior-art investigation).

## Goal
Every eligible title gains **intro** and **credits** timestamps, found automatically at scan time and
correctable by an admin on the detail page. A movie's 5-minute credits roll and a sitcom's 8-second tag
stop being treated identically. Detection is layered cheapest-first (chapter titles → ffmpeg heuristic →
cross-episode audio fingerprint), each result carries a **source + confidence** an admin can trust at a
glance, a manual correction is **locked** against future scans, and titles TMDB flags with a mid/post-credits
scene are marked so Ravilo never auto-skips past them.

## Current state (verified — see research report §1–§2)
- **Nothing intro/credits exists** anywhere in the codebase (full-tree grep confirmed). This is net-new.
- **Chapter reading already exists** (Phase 149): `FfprobeRunner.chapters(filePath)` runs
  `ffprobe -show_chapters` → `ChapterMarker(startMs,endMs)`, called from `Scanner.kt` **only** for
  multi-episode files, and it **discards `tags.title`** (`FfprobeChapterEntry` captures start/end only).
  That discarded title string is a zero-cost first-pass signal (some rips literally name a chapter
  "Credits"/"Recap").
- **Storage needs no migration.** `media.sq`'s `media` table stores the whole `MediaItem` as one `json TEXT`
  column; new nullable fields on the `Episode`/movie Kotlin data class just grow the blob (exactly like
  Phase 149's `chapterStartMs`/`hasChapters`). A migration is only needed to make a field **queryable**
  (the "no segments" triage filter — one indexed column).
- **Process-spawning is gated**: every ffmpeg/ffprobe call goes through `ProcessGate.withPermit { … }`
  (`Semaphore(16)`); a new detection call is one more site in the same idiom. `FfprobeRunner.duration()`
  already gives exact runtime for any position-window heuristic.
- **A scan extension point exists**: `PipelineStepOps` (named, opt-in, per-step-configured steps that run
  on both the scheduled full scan **and** the realtime per-item webhook ingest) — the right shape for a
  `detect_segments` step.
- **The triage UI is a pure predicate** over persisted fields (Phase 144 `TriageDetection.kt` +
  `TriageRoutes.kt` → Dashboard breakdown + Library filter + floating dock), not a separate table — a new
  triage type is a small addition.
- **DTO plumbing gap**: `chapterStartMs`/`hasChapters` already reach the shared `Episode` DTO and
  `DetailService`'s `SeriesDetail`, but `SeriesDetailScreen.buildEpisodeContext` drops them building
  `PlayerEpisodeEntry` — the same trip the new segment fields must finish (see §F).

## Requirements

### A. Data model
#### FR-SEG1-1 — `SegmentMarkers`, nullable, JSON-blob only
Add a `SegmentMarkers` record on both `Episode` and the top-level movie model (a movie has no episodes but
still has its own credits, and possibly its own intro / studio logos):
```kotlin
data class SegmentMarkers(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    val stinger: Stinger? = null,             // mid/post-credits scene (see FR-SEG1-6)
    val source: String? = null,               // "chapter" | "heuristic" | "fingerprint" | "manual"
    val confidence: Double? = null,           // 0..1 for auto sources; null for chapter/manual
    val manuallyConfirmed: Boolean = false,   // set on any admin edit — a scan must never overwrite it
)
data class Stinger(val atMs: Long?, val kind: String)  // kind: "during" | "after"
```
Embedded as `val segments: SegmentMarkers = SegmentMarkers()`, exactly like Phase 149's chapter fields —
**no `.sqm`**. The "N items with no segment data" triage filter (§E) is the one part that wants a single
denormalized indexed column (`has_segments INTEGER NOT NULL DEFAULT 0`), following the `19.sqm`/`20.sqm`
`ALTER TABLE ADD COLUMN` pattern.

### B. Detection pipeline — a new `detect_segments` step
A new step in `PipelineStepOps`, scope `missing`/`all` like its siblings, **off by default** (experimental,
non-trivial compute), gated by a settings toggle. Runs per item cheapest/most-certain first, stopping once a
field is filled unless `all` scope forces a re-run:

#### FR-SEG1-2 — Chapter-title pattern match (near-zero cost)
Fetch chapters (already free for multi-episode files; cheap for any file) and regex their titles for
credits/recap/preview words, **language-aware** (English + the library's own detected languages; word list
is an open decision, §Open). Sets `source = "chapter"`. Optionally cross-check Jellyfin's **native** `Chapters`
field (`BaseItemDto.Chapters`, no plugin required — it also reads external chapter-XML sidecars jellystructure's
own ffprobe read can't see) and prefer whichever source has more/better-titled markers. Ships even if nothing
else does.

#### FR-SEG1-3 — Credits heuristic (ffmpeg; movies **and** single episodes)
Scan only the **last N minutes** (bounded window sized from `FfprobeRunner.duration()` — ~last 3 min for TV
runtimes, ~last 15 min for movie runtimes) with `blackdetect` + `silencedetect` in one pass; take the
black+silence coincidence nearest the file's end that falls inside a sane position window; reject if none
(leave `creditsStartMs = null` → player keeps its end-of-file fallback). `source = "heuristic"` + confidence.
Needs no reference episode — the single highest-value/lowest-risk slice; **build this first** (research §8).

#### FR-SEG1-4 — Cross-episode audio fingerprinting (Skip Intro; series ≥2 episodes)
jellystructure's own in-house equivalent of Intro Skipper: Chromaprint (`fpcalc -raw`) per episode via
`ProcessGate`, compute+persist a fingerprint once per episode (adding an episode later fingerprints only the
new one), find the longest matching run near the start across episode pairs → `introStartMs`/`introEndMs`,
`source = "fingerprint"`. **Ported/adapted from Intro Skipper** (GPL-3.0, matching jellystructure's own
LICENSE — see research §3): reuse their tuned thresholds/parameters as the starting point; add an in-code
attribution/credit block at the ported call site. Compute-heavy — sequence after §B-3 (more to build,
series-only), but ship it as a **supported feature**, not a permanent beta.

#### FR-SEG1-5 — Resolution, config gating
Per field, fill from the highest-confidence source available; skip if already filled (unless `all`). Two
independent settings gates (design in `design/app/settings.html` → Libraries → **Intro & credits detection**):
the cheap detection pass (§B-2/§B-3) and the heavier fingerprinting (§B-4), each its own toggle, following the
existing `AppConfig.ScanConfig.pipeline` shape.

### C. TMDB stinger merge
#### FR-SEG1-6 — Ingest `during/aftercreditsstinger`
At TMDB ingest, read the `duringcreditsstinger` / `aftercreditsstinger` keywords (already ingested as tags,
Phase 51) into `SegmentMarkers.stinger`. This is what lets Ravilo (R182) offer **"Skip to scene"** and **never
auto-skip past** a mid/post-credits scene. A "Trust TMDB stinger tags" settings toggle governs it.
**(Dev review: the keyword→tag substrate is real but this is only true on the *TMDB re-pull* path, not the
initial full scan; match by name (the id is discarded); "Phase 51" is a STATUS ledger entry, not a spec file
— see addendum §1.)**

### D. Admin management UI — on the detail page (the single editing surface)
#### FR-SEG1-7 — Per-episode / per-movie segment editor, write-through
On the Movie/Series detail page (matching the Artwork/Tracks pattern; Phase 149's `series-johnnybravo.html`
row is an adjacent home), each episode row (and the single movie row) gains an **Intro & credits** editor:
a runtime-spanning timeline with a **draggable intro range** (two handles) and a **draggable credits point**,
each labelled with its `source` badge (chapter/heuristic/fingerprint/manual) + confidence, plus exact numeric
timestamp fields. Dragging or typing writes back **immediately** (write-through, Phases 71/74 — no staged
Save) and sets `manuallyConfirmed = true`. Per-row **Re-scan** (forces `all` scope for that one item) and a
**Lock** affordance. The TMDB stinger surfaces here too, so admin and viewer see one truth. **Design:**
`design/app/series-simpsons.html` (+ `detail.css`).

### E. Triage + settings surfaces
#### FR-SEG1-8 — Triage entries + Dashboard breakdown
Following the Phase 144 predicate pattern (no new subsystem): **"Low-confidence segments"** (heuristic below a
threshold — worth an eyeball) and **"No intro/credits detected"** (falls back to end-of-file). Surfaced in the
Dashboard attention breakdown, the Library filter, and the floating Triage dock. **Design:**
`design/app/index.html`.

### F. DTO plumbing to Ravilo
#### FR-SEG1-9 — Thread `segments` through to the player
Carry `SegmentMarkers` on the shared `Episode` DTO → `DetailService`'s detail response → through
`SeriesDetailScreen.buildEpisodeContext`'s `PlayerEpisodeEntry` (which currently also drops `chapterStartMs`
— same fix, same place) → `EpisodePlayContext` → `Dest.Player` → `PlayerScreen`, plus the equivalent
single-item path for a movie. This finishes the trip Phase 149 started and is what R182 consumes.

## Implementation order (research §8)
1. §B-2 chapter match + §B-3 credits heuristic + §C stinger + §D admin editor + §E triage/settings + §F DTO —
   the direct fix, no cross-episode data, lowest risk.
2. §B-4 cross-episode fingerprinting for Skip Intro — once slice 1 is proven live.

## Open design decisions (research §9)
- Exact chapter-title regex/word list per supported language (en/da/fo at minimum).
- Whether `fpcalc` (Chromaprint) is vendored/installed as a new system dependency alongside ffmpeg/ffprobe,
  and what that means for the deployment story.
- Confidence thresholds / minimum-runtime guardrails against cold-opens, mid-episode black scenes, and shows
  that use silence artistically near the true end.
- Exact placement of the GPL-3.0 attribution to Intro Skipper (in-code comment minimum; whether a project-wide
  CREDITS/THIRD-PARTY-NOTICES file is wanted).

## Reuse (don't rebuild)
`FfprobeRunner.chapters()`/`.duration()`; `ProcessGate`; `PipelineStepOps` (config toggle + full-scan +
realtime ingest); the Phase 144 `TriageDetection`/`TriageRoutes` predicate pattern; the Phase 149 nullable-
JSON-field precedent; Jellyfin's native `Chapters` field (out-of-the-box, no plugin).

## Non-goals
- **No Jellyfin `MediaSegments` integration, read or write** (research §3): the write side is plugin-gated,
  the read side only returns data for the minority already running Intro Skipper. jellystructure owns catalog
  data; it builds its own detection.
- **No ML / trained models** (Amazon Prime's CNN+Bi-LSTM+CRF is not replicable self-hosted — no labeled corpus).
- **No per-frame OCR / text detection** in this phase.

## Acceptance
- A multi-audio movie gets a `creditsStartMs` from the heuristic; an admin can drag it and the source flips to
  `manual` + locked, and a subsequent scan does not overwrite it.
- A series with ≥2 episodes gets `introStartMs`/`introEndMs` from fingerprinting once that gate is enabled.
- A title tagged `aftercreditsstinger` carries a `stinger` marker.
- Titles with no detectable segment leave the fields `null` (Ravilo falls back cleanly).
- The Dashboard shows the two new triage rows; clicking one opens the Library filtered to those items.
- `Episode.segments` reaches the player context (verified through `buildEpisodeContext`).

## Status
Design-authored, **`Planned`** — foundational (R182 depends on it). Design complete across
`design/app/series-simpsons.html`, `settings.html`, `index.html` (+ `detail.css`); exploration in
`design/Skip Intro & Credits - Directions.html`. `scripts/check-phases.sh` will flag it for a `STATUS.md`
row — **STATUS.md is code-owned; do not add the row from the design side.** **Next admin number after this
is 151.**

## Source references
- Research: `specs/research-reports/credits-and-intro-detection-2026-07-13.md` (full prior-art + feasibility).
- Chapter reader to extend (capture `tags.title`): `FfprobeRunner.chapters` / `FfprobeChapterEntry`
  (`src/linuxX64Main/kotlin/dev/jellystructure/media/FfprobeRunner.kt`), called from `Scanner.kt`.
- Pipeline step host: `PipelineStepOps.kt`; process gate: `ProcessGate.kt`; duration: `FfprobeRunner.duration`.
- Triage pattern: `TriageDetection.kt` + `TriageRoutes.kt` (Phase 144).
- DTO trip to finish: `SeriesDetailScreen.buildEpisodeContext` → `PlayerEpisodeEntry` (drops `chapterStartMs`).
- Intro Skipper (GPL-3.0, algorithm/parameters to adapt): github.com/intro-skipper/intro-skipper.

## Dev-review addenda (2026-07-13) — reconciled with live code

1. **FR-SEG1-6 "already ingested as tags, Phase 51" — true substrate, three caveats that change the design.**
   TMDB keywords *are* fetched (`TmdbClient.getMovieKeywords`/`getTvKeywords` → `/movie/{id}/keywords` +
   `/tv/{id}/keywords`, `TmdbClient.kt:800-823`) and flattened into the single `MediaItem.tags` list
   (`model/Media.kt:143` — there is no separate `keywords` field; genres are separate) via `mergeRepullTags`
   (`Scanner.kt:103-111`). That much is the real Phase 51 / "Phase 19 §15" rule. But:
   - **Keywords enter `tags` only on the TMDB *re-pull* paths, never the initial full scan.** `scanMovie`
     sets `tags = jItem.tags` (`Scanner.kt:273,465,521` — Jellyfin's own tags), and sync-from-Jellyfin is a
     union (`:204`). TMDB keyword names arrive only via `pull_tmdb` → `rescanMetadata`
     (`PipelineStepOps.kt:23-26`; `Scanner.kt:824,846` movie / `:889,911` TV), `syncMovie` (`:555,578`), and
     `syncSeriesEpisodes` (`:704,730`). **Consequence:** a freshly-scanned title has *no* stinger tag until a
     `pull_tmdb`/sync has run for it, so the acceptance criterion "a title tagged `aftercreditsstinger`
     carries a `stinger` marker" holds only *after a re-pull*, not after a bare scan. Either accept and
     document that ordering, or add stinger-keyword fetching to the full-scan path.
   - **Match by name, not id.** `TmdbKeyword.id` is parsed then discarded (`.map { it.name }`,
     `TmdbClient.kt:248-257`). The merge must match the literal strings `"duringcreditsstinger"` /
     `"aftercreditsstinger"` by name.
   - **There is no `phase-51-*.md`.** Phase 51 is a `STATUS.md` ledger row (line 300) + a `constitution.md`
     reference (§ lines 231-235); the code attributes the rule to "Phase 19 §15" (`Scanner.kt:104`). Cite it
     as the generic keyword→tag mechanism, and be explicit that the stinger *extraction* itself is net-new
     (no `SegmentMarkers`/`stinger`/`duringcreditsstinger` symbol exists anywhere in `src/`/`shared/` today).
   - **Clean hook point:** the raw keyword-name list (`rescanTmdbTags`) is already in scope one line above the
     `tags = mergeRepullTags(...)` assignment inside `rescanMetadata`'s `item.copy(...)` block — read the
     stinger names there before they're flattened into `tags`; no extra TMDB request needed on those paths.

2. **§B/§E `detect_segments` settings surface — the standalone card and the pipeline-step model need one
   source of truth.** The design (`design/app/settings.html`) renders "Intro & credits detection" as a
   *standalone Libraries card* with three toggles (`seg-detect`/`seg-fp`/`seg-stinger`) + a chapter-keyword
   chip list. But §B specifies it as a single `PipelineStep` in `ScanConfig.pipeline`
   (`AppConfig.kt:41-65`), and the Settings→Libraries tab *already* has a pipeline-builder UI that
   adds/enables/configures steps (`Settings.kt:157+`, `pipelineSteps`/`renderPipeline`, `:565-698`). The
   card's controls must map onto that one step, not a parallel config: "Detect segments on scan" = the step
   present+enabled in the list; the other two toggles + the keyword chips = **new per-step option fields** on
   the flat `PipelineStep` data class (e.g. `detect_fingerprint: Boolean`, `trust_stinger_tags: Boolean`,
   `chapter_keywords: List<String>`, following the existing `scope`/`overwrite`/`auto_reassert` precedent at
   `AppConfig.kt:55-64`). Decide whether the card *is* that step's editor (with the pipeline builder showing
   it as a linked node) or a second surface writing the same step — don't let the two diverge into competing
   "does detect_segments run?" truths.

3. **FR-SEG1-2 chapter-title matching populates BOTH intro and credits, not just credits.** The design's
   default keyword chips mix segment kinds: `Credits`/`End Credits`/`Rulletekster`/`Endamál` are
   **credits-side** (→ `creditsStartMs`); `Recap`/`Previously` are **recap/intro-side** (a recap chapter near
   the start → `introStartMs`/`introEndMs`); `Next Time` is a **post-credits** next-episode preview. A single
   flat "if any keyword matches, set `source=chapter`" can't tell which field to fill. Give each configured
   keyword a segment kind (intro vs credits) and a position sanity check (a "Recap" at 0:00 is an intro; a
   "Credits" at 0:00 is noise) — i.e. split the keyword config by segment, don't lump it.

4. **FR-SEG1-5 "fill from the highest-confidence source" is undefined while chapter/manual carry
   `confidence = null`.** The model sets `confidence` to `0..1` for auto sources but `null` for chapter and
   manual. Comparing a null-confidence chapter hit against a 0.6 heuristic hit has no defined winner. Pin an
   explicit precedence: **manual (locked) > chapter (exact marker) > fingerprint/heuristic by numeric
   confidence** — so `null` never has to sort against a number.

5. **Confirmed accurate (no change needed):** migration is at `20.sqm`, so the `has_segments` indexed column
   is `21.sqm` following the `19/20.sqm` `ALTER TABLE ADD COLUMN` pattern; `ScanConfig.pipeline` /
   `PipelineStep(step, enabled, scope)` shape is as described (`pull_tmdb`/`download_artwork` already use
   `scope`); `FfprobeRunner.chapters` does discard `tags.title` (the zero-cost first-pass win is real); the
   §F DTO trip (`buildEpisodeContext` dropping `chapterStartMs`) is confirmed. `PipelineStepOps` does run on
   both the scheduled scan and realtime ingest.

Pairs with **[R182](../ravilo/requirements/phase-R182-skip-intro-credits.md)** (also dev-reviewed
2026-07-13 — see its own addenda; R182 consumes this phase's `segments` DTO + a separate viewer-settings lane).

## Amendment (2026-07-14) — FR-SEG1-4 reworked: season-wide consensus, not a single reference

**Bug confirmed via live testing** (not a hypothesis): on the real show "The Lucky Machine", the
season's premiere episode — FR-SEG1-4's fixed "reference" — fails to correlate with **any** other
episode in its own season (verified by running the actual `findIntroMatch` matching code directly
against the real cached Chromaprint fingerprints in `config/fingerprints/`: 8/8 comparisons failed
in season 1, 9/9 in season 2). Meanwhile ordinary non-reference pairs within the *same* seasons
matched cleanly (confidence 0.86–0.90), proving the intro is genuinely consistent and detectable —
the flaw is trusting one arbitrary episode (premieres commonly carry an extended cold open, bonus
footage, or a different edit) as the sole arbiter for a whole season.

**FR-SEG1-4 is amended** to replace "one fixed reference episode per season" with a **full pairwise,
season-wide consensus**: every eligible episode (`partCount == 1`, not `manuallyConfirmed`,
`introStartMs == null`) is compared against every other episode in its season — not just one fixed
reference — and each episode's final `introStartMs`/`introEndMs`/`confidence` is derived by
clustering that episode's successful pairwise matches by proximity (5s tolerance) and taking the
majority cluster's median bounds, weighting confidence by how much the cluster agrees. This means
one atypical episode (e.g. an unusual premiere) can no longer take down detection for the rest of
its season, and the result is robust against any single false-positive pairwise match rather than
resting on one comparison.

Priority is correctness over speed: this runs as a background pipeline step, and the user has
explicitly directed that processing time is not a concern here. This does **not** increase
`fpcalc`/`ProcessGate` load — each episode's fingerprint is still computed/cached exactly once
(`FingerprintService.getOrCompute`, unchanged); only the count of the cheap, in-process, no-I/O
pairwise correlation calls (`SegmentDetection.findIntroMatch`, algorithm itself unchanged) grows
from O(n) to (bounded) O(n²) per season.

Implementation: `SegmentDetection.aggregateIntroCandidates` (new pure function, tolerance-bucket
clustering + majority-cluster median) and a rewritten `PipelineStepOps.detectIntroFingerprints`
(same public entry point via `detectSegments`, no signature changes propagate to any caller).
