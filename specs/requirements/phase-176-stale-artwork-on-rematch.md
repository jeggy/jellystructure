# Phase 176 — An on-disk poster/backdrop must never disagree with the current TMDB match

> Reported live: a movie was auto-matched to the wrong TMDB title and downloaded that title's poster.
> The match was then corrected via **Find / fix match…** + a re-pull. `item.posterPath` now correctly
> pointed at the right film — the Artwork tab's candidate gallery badged the right TMDB candidate
> **"ON DISK"** — but the physical `poster.jpg` was untouched: still the wrong film's poster. The
> gallery's **"Local"** tile (which renders the literal on-disk bytes) and the **"ON DISK"** badge on a
> TMDB candidate (which compares `candidate.file_path == item.posterPath`, a metadata string, and is
> rendered from the TMDB CDN, not the file) had quietly become two different sources of truth that could
> disagree. **An item can only ever have one poster; the file and the metadata must always describe the
> same one.** Phase 174 already covers this for its own single path — an explicit "Clear TMDB match"
> deletes the poster/backdrop files as part of the clear — but the same disagreement can arise from an
> *ordinary* re-match (no clear involved at all), and nothing closes it there.

**Status:** Implemented 2026-08-26 (backend only, no admin UI change needed). Not yet live-tested against
the reported item (needs a backend restart — owner's call).

## Root cause

Three independent gaps, found by tracing every writer of `poster.jpg`/`fanart.jpg` and every reader of
"is this on disk correct":

1. **`ArtworkDownloader.fetch()`'s skip-if-present check has no concept of "current."** It only asks
   *"does a file already exist at this path,"* never *"does the file that exists match what
   `item.posterPath`/`item.backdropPath` says it should be right now."* Any writer that changes
   `posterPath`/`backdropPath` to a **different** TMDB `file_path` — a corrected match, or simply a
   rescan landing on a different top hit — leaves the old file exactly where it was, because the
   presence check alone is satisfied.
2. **Poster/backdrop carry no provenance.** Episode stills (R131) and clearlogo (`readAssetSrc`/
   `writeAssetSrc`) already record *which* source produced the file on disk, specifically so a later
   pass can tell "the file's origin still matches" from "it doesn't." Poster/backdrop never got the same
   treatment — `fetch()`'s automatic download writes no sidecar, and even the *explicit* candidate-picker
   save (`POST /candidates/save`) only calls `writeAssetSrc` for `clearlogo`, never for `poster`/
   `backdrop` (`MediaRoutes.kt:759-761`). There is nothing for a staleness check to compare against even
   if one existed.
3. **The "missing artwork" scan scope can't see a wrong-but-present file either.** `isArtworkIncomplete()`
   decides whether the "Download artwork (missing)" pipeline scope touches an item at all, and it only
   asks `!posterExists`/`!fanartExists` — a stale file is present, so a missing-only run would skip the
   item outright even after gap 1 and 2 are fixed, and the wrong poster would only ever get corrected by
   a full (non-missing-only) artwork run.

The self-heal trigger itself already exists and needs no new wiring: every route that rescans metadata
(`/repull`, `/repull-jellyfin`, `/sync`) calls `pushToJellyfin(updated, ...)` with the **freshly rescanned**
item (confirmed at every call site — `MediaRoutes.kt:1358,1600,1635,1713,1735`), which itself calls
`artwork.fetch(item)` (`MediaRoutes.kt:2084`) immediately, no scheduled scan required. Gap 1 alone is why
that call does nothing useful today.

## Requirements

### FR-176-1 — Provenance sidecar for poster/backdrop, on every writer

Extend the existing `.src` sidecar convention (already used for clearlogo and episode stills) to
poster/backdrop:

- `ArtworkDownloader.fetch()`'s automatic poster/fanart download writes `poster.jpg.src` /
  `fanart.jpg.src` with the TMDB `file_path` it just downloaded (`item.posterPath`/`item.backdropPath`
  at download time), on every successful download — not only when a candidate is explicitly picked.
- `POST /{id}/artwork/candidates/save` (`MediaRoutes.kt:743-781`) writes the same sidecar for `poster`/
  `backdrop`, not only `clearlogo` — closing the second half of gap 2. A non-TMDB source (uploaded file,
  pasted URL) records a sentinel (`"upload"` / the pasted URL) rather than a `file_path`, matching how
  `saveEpisodeStill` already labels a manual pick `"manual"`.
- A file predating this phase has no sidecar. **Absence means "trust it, don't force a re-download"** —
  the same additive/no-backfill posture Phase 151 (FR-ART2) already committed to; a one-time mass
  re-download across an existing library on upgrade is not this phase's job, and an operator with an
  already-known-wrong poster still has Phase 174's Clear button for it.

### FR-176-2 — Skip-if-present becomes skip-if-current

`fetch()`'s poster/fanart download gate changes from *"skip when the file exists"* to *"skip when the
file exists **and** is not stale."* A non-manual (`!isManual`) file is **stale** when it has a recorded
`.src` and that value differs from the item's current `posterPath`/`backdropPath`. A manually-locked file
(`.manual` marker present, Phase 151) is **never** stale regardless of `.src` — an operator's pick still
beats a corrected match, exactly as it beats an ordinary re-pull today.

When stale, `fetch()` deletes the old file and its `.src` sidecar first, then downloads as if the slot
had been empty all along — the same delete-then-fill shape `ArtworkDownloader.clearAsset` already uses
for the operator-facing Clear button, just reached automatically instead of only on request. This is
what actually closes the reported bug: the very next `pushToJellyfin` after a corrected match now
replaces the file, with no separate action required.

### FR-176-3 — "Missing artwork" scope must see a stale file as incomplete too

`isArtworkIncomplete()` gains the same staleness check as FR-176-2 for poster/backdrop, alongside its
existing screengrab-upgrade check for stills: a non-manual poster/backdrop whose `.src` disagrees with
the item's current path counts as incomplete. Without this, a scheduled "Download artwork (missing)" run
— the common pipeline configuration — would keep skipping a wrong-but-present poster forever, and only a
full (non-missing-only) artwork run would ever reach FR-176-2's fix.

## Invariants

- **The on-disk poster/backdrop always corresponds to the item's current TMDB match, going forward from
  this phase.** The only file that may legitimately disagree with current metadata is one an operator
  explicitly locked (Phase 151) — that is a deliberate override, not drift.
- **No new mass re-download.** A pre-existing file with no `.src` sidecar is assumed correct until the
  next time its slot is genuinely rewritten (a save, an upload, or a future stale-detection once a
  sidecar exists) — FR-176-1's backfill is lazy, not retroactive.
- **An operator's manual pick is never second-guessed by this mechanism.** Staleness detection only ever
  applies to the automatic/candidate-picker path; `isManual` short-circuits it exactly as it already
  short-circuits every other automatic writer (Phase 151).

## Out of scope

- **Season posters and episode stills**, beyond the screengrab-upgrade check that already exists for
  stills (R131). Both lack a fixed "expected value" to compare a sidecar against the way poster/backdrop
  have `item.posterPath`/`item.backdropPath` — a season poster's "best pick" is recomputed generically
  from whatever TMDB serves for that season, not pinned to a stored candidate. A TV-show re-match leaving
  a stale season poster or episode still is the same bug class, but closing it needs its own design
  (what does "expected" mean when there's no stored target field) and is left for a follow-up phase.
- **Clearlogo's automatic path.** `fetch()` never auto-downloads a clearlogo (only the explicit candidate
  picker does, which already has `.src` tracking) — there is no automatic-download staleness window to
  close for it.
- **Retroactively fixing already-wrong libraries.** Phase 174's Clear TMDB match / Clear asset buttons
  remain the operator's tool for a poster that's already wrong today; this phase only stops it from
  happening again.

## Source references

- `media/ArtworkDownloader.kt` — `fetch()` (skip-if-present → skip-if-current), `isArtworkIncomplete()`,
  new `.src` writes alongside the existing `readAssetSrc`/`writeAssetSrc`/`readStillSrc`/`writeStillSrc`
  precedent.
- `server/routes/MediaRoutes.kt` — `/candidates/save` (extend the existing clearlogo-only `writeAssetSrc`
  call to poster/backdrop).
- Related: **Phase 151** (`.manual` marker — the override this phase must never bypass), **Phase 174**
  (Clear TMDB match / Clear asset — the operator-facing tool for an already-wrong file; this phase is the
  automatic counterpart for a *future* re-match), **R131** (the `.src` provenance precedent this phase
  extends from stills/clearlogo to poster/backdrop).

## Verification

The staleness decision (FR-176-2/FR-176-3's shared logic) was extracted into a pure top-level
`isStaleArtworkSrc(recordedSrc, expectedSrc, manual)` in `ArtworkDownloader.kt` — same shape as
`preserveLockedArtwork`/`clearTmdbMatch` — so it's unit-testable without a real filesystem/TmdbClient/
Screengrabber, matching this codebase's existing pure-function test convention rather than introducing
filesystem mocking. `fetch()` and `isArtworkIncomplete()` both call it through the private
`isStaleAutoAsset(item, asset, expectedSrc)` wrapper, which supplies the real `readAssetSrc`/
`isAssetManual` file reads.

- New `ArtworkStalenessTest` (5 cases): a disagreeing `.src` is stale; a matching one isn't; no `.src` at
  all is trusted, not stale; a manual file is never stale regardless of `.src`; no current expected
  source (e.g. after a match clear) means nothing to compare against.
- `compileKotlinLinuxX64` + `compileKotlinWasmJs` clean; `linuxX64Test` 154/154 green (`--max-workers=1`,
  same pre-existing Gradle test-report-writer concurrency crash as Phase 175 — not a real test failure).
- Not yet live-tested against the reported item (needs a backend restart — owner's call, per standing
  instruction not to restart the backend without asking).
