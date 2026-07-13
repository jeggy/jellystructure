# Netflix-level Skip Intro / Next Episode — investigation (2026-07-13)

**Date:** 2026-07-13

**Question:** Ravilo's "Next Episode" popup is a pure end-of-file heuristic (`NEXTUP_AT_MS = 20_000L` —
show the card whenever 20s remain, then run an 8s countdown) with no "Skip Intro" at all. A movie's
5-minute credits roll and a sitcom's 8-second tag get treated identically, and the card can't appear
any earlier than 20s from the file's actual end no matter when credits really start. To reach a
Netflix/Prime/Plex-level experience we need to know **where credits and intro segments actually start**
in each specific file, store that, let an admin review/correct it, and drive the player off it instead
of a fixed offset. This report investigates feasibility, prior art, and proposes a design.

**Status: research only — nothing built.** This is the record of the investigation; the durable parts
should graduate into a numbered phase spec (admin) + Ravilo phase spec (player) once scoped.

---

## 1. The gap, precisely

`ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`:

```kotlin
private const val NEXTUP_AT_MS   = 20_000L    // R111: show next-up card when this many ms remain
private const val COUNTDOWN_SECS = 8
...
if (playerLoadedForCurrentItem && currentNextEpisodeId != null && durationMs > 0 && !nextUpVisible && !nextUpDismissed && !player.isEnded) {
    if ((durationMs - positionMs) in 1..NEXTUP_AT_MS) {
        nextUpVisible = true; nuFocus = NuFocus.PLAY
    }
}
```

`durationMs`/`positionMs` are polled live from the player engine every 500ms (line ~461) — nothing
server-computed, nothing per-file. There is no "Skip Intro" affordance anywhere in the player. Both
gaps have the same root cause: jellystructure has never scanned a file for *where its intro or credits
segment actually is*, so the player has nothing better than "N seconds before the file ends" to go on.

## 2. What already exists to build on

An Explore pass over the codebase found real, reusable infrastructure — this is not a greenfield
feature:

- **Chapter reading already exists** (Phase 149, for multi-episode-file splitting):
  `FfprobeRunner.chapters(filePath)` (`src/linuxX64Main/kotlin/dev/jellystructure/media/FfprobeRunner.kt:171-184`)
  runs `ffprobe -show_chapters`, parsed into `ChapterMarker(startMs, endMs)`. It's called from
  `Scanner.kt:352-355` — but **only when a filename parses to >1 episode**, and it **discards each
  chapter's `tags.title`** (`FfprobeChapterEntry`, `FfprobeRunner.kt:56-59` only captures start/end).
  That title field is a zero-cost win sitting right there: some rips do literally name a chapter
  "Credits"/"Recap"/"Next Time" (see §3) and today jellystructure throws that string away.
- **No intro/credits/recap concept exists anywhere** — confirmed by a full-tree grep. This is genuinely
  net-new, not an extension of something half-built.
- **Storage needs zero migration.** `media.sq`'s `media` table stores the *entire* `MediaItem` (every
  nested `Episode`, its chapter fields, tracks, everything) as one `json TEXT` column; the other
  columns are denormalized filter indexes only. Phase 149's `chapterStartMs`/`chapterEndMs`/
  `hasChapters`/`partIndex`/`partCount` needed **no `.sqm` file** — new nullable fields on the `Episode`
  Kotlin data class are sufficient; the JSON blob just grows. A migration is only needed if a new field
  must be **indexed/queryable** (e.g. a fast "Triage: missing credits detection" library filter).
- **Process-spawning is already gated**: every ffmpeg/ffprobe shell-out must go through
  `ProcessGate.withPermit { ... }` (`src/linuxX64Main/kotlin/dev/jellystructure/ops/ProcessGate.kt`,
  a global `Semaphore(16)`) — a new detection call is one more call site in that same idiom.
  `FfprobeRunner.duration(filePath)` already gets exact runtime in seconds (used today only to pick a
  screengrab timestamp), which any position-window heuristic will also need.
- **A ready-made extension point for the scan itself**: `PipelineStepOps`
  (`src/linuxX64Main/kotlin/dev/jellystructure/media/PipelineStepOps.kt`) is a set of named, opt-in
  steps (`pull_tmdb`, `fetch_artwork`, `sync_imdb_ratings`, `write_nfo`, …) configured per-step in
  `AppConfig.ScanConfig.pipeline`, each with a scope (`missing`/`all`). Critically, the **same step
  logic runs on both the scheduled full scan and the realtime per-item webhook ingest**
  (`RealtimeIngestService`) — this is exactly the shape a `detect_segments` step needs, and it already
  has the config toggle plumbing.
- **The triage/detection UI pattern** (Phase 144, cover-art-as-video) is *not* a separate "detections"
  table — it's a pure predicate evaluated on demand over already-persisted fields
  (`TriageDetection.kt:63-87`), surfaced via `TriageRoutes.kt` into the Dashboard breakdown + a Library
  filter + the floating Triage dock. A "N items missing segment detection" triage type is a small
  addition to that existing machinery, not a new subsystem.
- **The plumbing gap that must be closed**: `chapterStartMs`/`hasChapters` already reach as far as the
  shared `Episode` DTO and `DetailService`'s `SeriesDetail` response, but
  `SeriesDetailScreen.buildEpisodeContext` (`ravilo-ui/.../screens/SeriesDetailScreen.kt:170-212`) drops
  them when building `PlayerEpisodeEntry` (`PlayerEpisodeEntry.kt:9-18`, no timestamp field at all
  besides a display string), so `PlayerScreen` never sees them. New `introStartMs`/`introEndMs`/
  `creditsStartMs` fields need this same fix — actually finishing a trip Phase 149 already started
  most of.

## 3. What Netflix/Prime/Plex/Jellyfin's own ecosystem actually do

Two independent research passes (Jellyfin plugin internals; commercial/OSS prior art), condensed —
full source list in §7.

**Skip Intro and Skip Credits are two different problems with two different techniques**, confirmed
across every implementation surveyed (Jellyfin's Intro Skipper, Plex):

- **Intro/recap detection = cross-episode audio fingerprinting.** Jellyfin's community
  [`intro-skipper/intro-skipper`](https://github.com/intro-skipper/intro-skipper) (the maintained fork
  of the archived `ConfusedPolarBear/intro-skipper`) uses **Chromaprint** (`fpcalc -raw`) per episode,
  compares fingerprints via Hamming distance to find the longest contiguous matching run against other
  episodes in the same season. Plex's Skip Intro (2020) is conceptually identical — audio-fingerprint/
  histogram comparison across a season. **This technique fundamentally requires ≥2 episodes of the same
  show to compare** — it cannot find an intro in a standalone movie or a show's only aired episode. No
  training data needed, cheap per-fingerprint (~100ms), and — importantly — it only needs to match
  *within one show's own episodes*, never a global catalog-scale database, so the usual "fingerprint
  database at scale" problem doesn't apply here.
- **Credits detection = a different, position-validated heuristic**, independent of any reference
  episode. Intro Skipper's method: ffmpeg black-frame detection (adaptive threshold) + silence
  detection, an entropy/saturation fallback for non-black title cards, chapter-marker matching as a fast
  first pass, with the *candidate* validated against an expected position/duration window (15s–450s for
  TV, up to 15 min for movies) to reject false positives. This works on **both movies and standalone
  episodes** since it needs no comparison episode. Plex's Skip Credits (2023) is described the same way
  (text detection + black-frame + undisclosed extras), which is exactly why it, too, works on movies.
- **Commercial-grade ML (Amazon Prime Video's WACV 2021 paper — commonly mis-attributed to Netflix)**
  uses a CNN fusing audio+video+on-screen-text features → Bi-LSTM → CRF boundary smoothing, trained on
  a large labeled corpus. **Not replicable** for a self-hosted project (no labeled training data, no
  training infrastructure). Netflix's own public engineering writing in this space is adjacent
  infrastructure (a small CRNN for speech/music classification, shot-cut detection for editorial match-
  cutting) — not the skip-intro mechanism itself.
- **No free universal metadata exists.** Chapter atoms in MKV/MP4 are untyped free text — "Credits"
  appears on some well-tagged rips but inconsistently, and naming has *declined* from DVD-era discs to
  Blu-ray rips. An unimplemented **draft** Matroska extension (`ChapterSkipType`, IETF cellar draft)
  defines exactly the enum we'd want (OpeningCredits/EndCredits/Recap/NextPreview/…) but zero tools
  (mkvtoolnix, ffmpeg, VLC) implement it. The one real, normative standard —
  SMPTE RDD52's `FFEC`/`FFMC` composition markers — is scoped to theatrical/IMF mastering and is
  effectively absent from consumer rips. **Conclusion: pattern-matching chapter titles is a legitimate,
  zero-cost first pass, but detection has to be the primary strategy — there's nothing free to lean on
  at scale.**
- **Jellyfin 10.10+ ships a native `MediaSegments` API** (`/MediaSegments/{itemId}`, typed
  Commercial/Preview/Recap/Outro/Intro/Unknown segments with a Skip/PromptToSkip/Mute action). Newer
  Intro Skipper releases write into this native API via `IMediaSegmentProvider` rather than a bespoke
  mechanism. **This is a genuine optional freebie**: if a user already runs Intro Skipper on their
  Jellyfin server, jellystructure could read already-computed segments for nothing. But making this a
  *dependency* would break the "Jellyfin is streaming-only, jellystructure owns catalog data" principle
  this codebase already follows deliberately (see `[[ravilo-off-jellyfin-data]]`) — it should be an
  optional, best-effort import, never the only path, since most users won't have Intro Skipper
  installed at all.

## 4. Proposed data model

Mirror the exact shape of Phase 149's chapter fields — nullable, JSON-blob-only, no migration:

```kotlin
// New, on both Episode and (top-level) MovieDetail/MediaItem — a movie has no episodes but still
// has its own intro (studio logos / cold-open-less features rarely need this, but its own credits).
data class SegmentMarkers(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    // "chapter" (title pattern match) | "heuristic" (ffmpeg black/silence/position) |
    // "fingerprint" (cross-episode audio match) | "manual" (admin-entered/edited) | "jellyfin" (native
    // MediaSegments import). Per-field in principle, but a single value covering the whole record is
    // simpler and matches how confident an admin needs to be before trusting it.
    val source: String? = null,
    // Set the moment an admin edits ANY field via the new management UI (§6) — a scan must never
    // silently overwrite a manual correction; re-detecting an item requires an explicit "Re-scan" action.
    val manuallyConfirmed: Boolean = false,
)
```

Embedded as `val segments: SegmentMarkers = SegmentMarkers()` on `Episode` (`model/Media.kt`) and the
movie-level model, exactly like `chapterStartMs` was added in Phase 149 — no `.sqm` needed. A future
"Triage: N items with no segment data" filter would need one denormalized indexed column (e.g.
`has_segments INTEGER NOT NULL DEFAULT 0` on `media`) — that part **does** want a small `.sqm`, following
the `19.sqm`/`20.sqm` `ALTER TABLE ADD COLUMN` pattern.

## 5. Proposed scanning pipeline (phased by cost/value)

A new `detect_segments` step in `PipelineStepOps`, scope `missing`/`all` like its siblings, **off by
default** (experimental, non-trivial compute) with a settings toggle. Runs in this order per item,
cheapest/most-certain first, stopping as soon as a field is filled unless `all` scope forces a re-run:

1. **Chapter-title pattern match** (near-zero cost — chapters are already fetched for multi-episode
   files, and cheap to fetch for any file otherwise). Regex against chapter titles for
   credits/recap/preview-adjacent words (language-aware, at minimum English + the library's own
   detected languages). Sets `source = "chapter"` on a hit. This alone catches SOME well-tagged rips for
   free and should ship even if nothing else does.
2. **Credits heuristic** (ffmpeg, works on movies AND single episodes — no reference file needed).
   Scan only the **last N minutes** of the file (bounded window: last ~3 min for TV runtimes, last
   ~15 min for movie-length runtimes, using `FfprobeRunner.duration()` to size the window) with
   `blackdetect` + `silencedetect` in one ffmpeg pass, take the black+silence coincidence nearest the
   file's end that also falls inside a sane position window, reject if none found (leave `creditsStartMs
   = null`, keep falling back to the existing 20s-before-end heuristic in the player). This is the
   single highest-value/lowest-risk piece to build first — it needs no other episodes, works for both
   movies and shows, and directly fixes the reported problem.
3. **Cross-episode audio fingerprinting** (Chromaprint via `fpcalc`, gated by `ProcessGate`, only
   attempted for series with ≥2 episodes in the same season not yet fingerprinted). Compute + persist a
   fingerprint per episode once (so adding a new episode later only computes its own, comparing against
   already-cached ones — never re-fingerprint a whole season per new episode), find the longest matching
   run near the start of the episode across episode pairs, store as `introStartMs`/`introEndMs`. This is
   the compute-heavy phase (external `fpcalc` binary, one ffmpeg decode pass per episode) — ship this
   after #2 is proven out, and keep it strictly opt-in with a "beta" label in the settings UI.
4. **(Optional, later) Jellyfin `MediaSegments` import** — before running #2/#3 for an item, best-effort
   check whether Jellyfin already has segments for it (from a user-run Intro Skipper) and import those
   with `source = "jellyfin"` instead of spending local compute. Purely additive, never required.

## 6. Proposed player UX changes

Two distinct affordances, matching how every prior-art implementation treats them as separate features:

- **Skip Intro** — a small, low-key, persistent pill (bottom-corner, not a full card), visible whenever
  `positionMs` is inside `[introStartMs, introEndMs]`. Auto-hides like the rest of the chrome, one press
  seeks to `introEndMs`. This is new — nothing like it exists in `PlayerScreen.kt` today.
- **Next Episode** — keep the existing card + 8s countdown UX (it's already close to Netflix's own
  shape), but retarget the trigger:
  ```kotlin
  val creditsAt = currentSegments?.creditsStartMs
  val nearEnd = if (creditsAt != null) positionMs >= creditsAt
                else (durationMs - positionMs) in 1..NEXTUP_AT_MS   // graceful fallback, unscanned content
  ```
  This is the critical design point: **content with no detected credits timestamp must keep behaving
  exactly as it does today** — the feature can only make things better, never regress an unscanned
  library down to "no next-up card at all."
- Both need the DTO trip finished: `Episode`'s `segments` → `DetailService`'s response → threaded
  through `SeriesDetailScreen.buildEpisodeContext`'s `PlayerEpisodeEntry` (currently drops
  `chapterStartMs` too — same fix, same place) → `EpisodePlayContext` → `Dest.Player` → `PlayerScreen`
  params. Movies need the equivalent single-item path (wherever `MovieDetail` currently builds its own
  `Dest.Player`).

## 7. Proposed jellystructure admin management UI

Per this session's explicit ask ("we also want to be able to manage this in jellystructure"), matching
this app's own established pattern of the **movie/series detail page as the single editing surface**
(the same principle behind the Artwork tab, Track editor, and NFO viewer):

- Extend the per-episode row (and the equivalent single row for a movie) with a **segment scrubber**: a
  thin timeline bar spanning the episode's runtime with two draggable range markers (intro) and one
  draggable point marker (credits start), each labeled with its `source` (chapter/heuristic/
  fingerprint/manual/jellyfin) as a small badge so the admin knows how much to trust it at a glance.
  Phase 149's own design mockup (`design/app/series-johnnybravo.html`) already plans an expandable
  per-file row with chapter info for multi-episode files — this is a natural, adjacent addition to that
  same row rather than a new screen.
- Dragging a marker (or typing an exact timestamp) writes it back immediately (write-through, matching
  this app's Phase 71/74 editing model — no staged "Save changes") and sets `manuallyConfirmed = true`
  so no future scan silently overwrites it.
- A **"Re-scan this episode"** button per row (forces scope=`all` for that one item, clears
  `manuallyConfirmed` only if the admin explicitly confirms — don't clear it by accident).
- A **Triage entry** ("N items missing segment detection" / "N items using low-confidence heuristic
  detection"), following the exact Phase 144 pattern (`TriageDetection.kt` predicate +
  `TriageRoutes.kt` wiring + Dashboard breakdown + Triage dock line) — gives the admin a global,
  actionable queue instead of having to open every title individually.
- A **Settings toggle** (Settings → Advanced or a new Playback-adjacent section) to enable/disable the
  `detect_segments` pipeline step at all, and separately gate the heavier cross-episode fingerprinting
  pass (§5.3) behind its own opt-in switch, both following the existing pipeline-step config shape in
  `AppConfig.ScanConfig`.

## 8. Recommended phasing

1. **Chapter-title pattern match + ffmpeg credits heuristic** (§5.1–5.2) for both movies and episodes,
   with the admin scrubber UI and the player's graceful-fallback trigger change. This alone is the
   direct fix for the reported problem, needs no cross-episode data, and is the lowest-risk slice.
2. **Cross-episode audio fingerprinting** for Skip Intro (§5.3), opt-in/beta, shipped once #1 is proven
   live.
3. **(Optional, low-priority) Jellyfin `MediaSegments` import** (§5.4) as a free-when-available
   enhancement, never a dependency.

## 9. Open questions for the design pass

- Exact regex/word list for chapter-title pattern matching, per supported UI language (en/da/fo at
  minimum, matching Ravilo's existing i18n set).
- Where exactly the Skip Intro pill and the segment scrubber should live visually — needs a design pass
  in the Cosmos design project, not decided here.
- Whether `fpcalc` (Chromaprint) needs to be vendored/installed as a new system dependency alongside the
  existing ffmpeg/ffprobe binaries, and what that means for the deployment story.
- Confidence threshold / minimum-runtime guardrails to avoid false positives on cold-opens, mid-episode
  black scenes, or shows that use silence artistically near the true end of an episode.

## Sources

- github.com/intro-skipper/intro-skipper (+ wiki: Settings-Analysis, Settings-Performance,
  Movies-and-Segment-Types, Edit-Timestamps-&-Fingerprints), github.com/ConfusedPolarBear/intro-skipper,
  forum.jellyfin.org/t-intro-skipper-project-dead
- jellyfin.org/docs/general/server/metadata/media-segments, github.com/jellyfin/jellyfin/pull/10530
- ayosec.github.io/ffmpeg-filters-docs (blackdetect/silencedetect/freezedetect/scdet),
  blog.gdeltproject.org (blackdetect for commercial-block detection),
  gist.github.com/Hellowlol/96e4e7b3591eebc3ec0a4de9f5883fdf
- github.com/hurdlea/Movie-Credits-Detect, github.com/yanglinz/detect-video-end-credits,
  github.com/PatrickKalkman/credit-scout, github.com/nielstenboom/recurring-content-detector,
  github.com/yocksers/EmbyCredits, arxiv.org/abs/2504.09738 (CLIP+attention credits classifier, 91% F1)
- amazon.science/publications/intro-and-recap-detection-for-movies-and-tv-series,
  docs.aws.amazon.com/rekognition/latest/dg/segments.html,
  about.netflix.com/en/news/looking-back-on-the-origin-of-skip-intro-five-years-later,
  netflixtechblog.com/detecting-speech-and-music-in-audio-content-afd64e6a5bf8
- support.plex.tv/articles/skip-content, support.plex.tv/articles/credits-detection,
  plex.tv/blog/go-ahead-and-skip-that-intro, plex.tv/blog/let-the-next-episode-roll
- matroska.org/technical/chapters.html, ietf.org/archive/id/draft-ietf-cellar-chapter-codecs-05.html
  (draft `ChapterSkipType`), pub.smpte.org/doc/rdd52/20200518-pub/rdd52-2020.pdf (FFEC/FFMC)
