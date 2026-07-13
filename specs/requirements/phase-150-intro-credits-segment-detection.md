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
