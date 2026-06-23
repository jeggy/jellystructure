# Phase 53 — Scanner data-quality fixes (FR-SQ1)

**Status:** ✓ Done (2026-06-23) · `:compileKotlinLinuxX64` builds. _Verify by re-syncing the reset DB:_
years populate, The Bad Guys (`बुरे बंदे`) + both Idiocracy now appear, Operation X episodes get numbers,
and the activity log shows a "Scan summary: N stored, M skipped (…)" line. _Filed from a post-reset
full-sync data review (296 scanned items vs 303 in Jellyfin)._

## Implementation
- **A** — `scanMovie`/`scanSeries` now split year: `searchYear = name ?? jItem.year` (Jellyfin
  `ProductionYear`) drives the TMDB search + slug; the stored `year` prefers TMDB
  (`releaseDate`/`firstAirDate`) then `searchYear`. All three return paths.
- **B** — new `Scanner.itemId(title, year, jellyfinId)` = slug, else `jf-<jellyfinId>` (kills the
  empty-slug collapse) — applies to full **and** auto scans. `MediaStore.disambiguateIds` appends a
  short stable `jellyfinId` token to every member of a colliding id-group in `update()` (deterministic,
  order-independent). _Residual:_ same-id collisions are only de-duped on the full-scan `update()` path,
  not the incremental auto-scan `addOrUpdate` (rare; empty-slug — the actual data loss — is fixed on both).
- **C** — `SEASON_EP_RE` season group `\d{1,2}` → `\d{1,4}` (S2025E01 parses; year-as-season still won't
  match a TMDB season — numbers only, as caveated).
- **D** — `runScan` computes `skipped = jellyfin items − stored − resume-skips`, classifies each via
  `Scanner.classifySkip`, logs `Scan summary: … skipped (reason=count…)` + per-item warns for
  **unexpected** reasons (file/dir-not-found, no-episode-files). Activity-log only for now; a Dashboard
  count is a follow-up.
- **E** — `scanSeries` warns when ffprobe returns no tracks for an episode. Untagged→`en` and `nb`/`no`
  left as-is (by-design / cosmetic).

## Problem
A fresh DB reset + full sync surfaced several scan-correctness issues. Some produce wrong/empty data on
every item; two **silently drop items** with no trace. Concrete evidence (this library, 214 movies / 82
series / 3797 episodes) is cited per item below.

## Findings & requirements

### A. Populate `year` on the full-scan path (FR-SQ1-A) — **high**
**Evidence:** `year` is null on **295 of 296** items; the one exception had `(2022)` literally in its
Jellyfin name.
**Root cause:** `Scanner.scanMovie`/`scanSeries` set `year` **only** from `parseTitleYear(jItem.name)`
(`Scanner.kt` ~L153/L210). They ignore two sources already on hand:
- Jellyfin **`ProductionYear`** — fetched (`Fields=…,ProductionYear`) and present on `JellyfinItem.year`,
  but never read.
- TMDB **release date** — available on `details` (`releaseDate`/`firstAirDate`) but not mapped on scan.

The recent fix (`90aaa40`) added TMDB-year mapping to the four **re-pull/sync** paths only (`syncMovie`
L401, `syncSeriesEpisodes` L496, `rescanMetadata` L594/L639), not the initial scan — so a clean sync is
year-less until every item is individually re-pulled.

**Requirement:** resolve year in **two stages** — TMDB's year isn't known until *after* the match, so a
single "name → TMDB → ProductionYear" precedence is circular (`scanMovie` L160–162 finds the TMDB id via
`searchMovie(title, year)` *before* `details` exists):
- **Search/id year (before the TMDB lookup):** `name-parsed ?? Jellyfin ProductionYear`. Use it for
  `searchMovie/searchTv(title, year)` and for `slugify` (a stable, match-independent year).
- **Stored/display year (after the match):** `TMDB releaseYear ?? name ?? ProductionYear`.

Apply to `scanMovie` and both `scanSeries` return paths (normal + language-mix). **Scope note:** the
search-precision gain only affects items **without** a Jellyfin-provided TMDB id (`providerIds.tmdb`) —
most items here have one, so the search is bypassed; the primary win is a populated `year` + a stabler
slug input, not better matching.

### B. Stable, unique item ids — stop silent overwrite/data-loss (FR-SQ1-B) — **high**
**Evidence:** **2 items silently lost.** `slugify(title, year)` = lowercase, `[^a-z0-9]+ → -`, trimmed
(`Scanner.kt` ~L670):
- Non-Latin titles collapse to an **empty id**: `बुरे बंदे` (The Bad Guys) and `楽しいムーミン一家`
  (Japanese Moomin) both → `id = ""`. They collide; `MediaStore` upserts by id, so last-write-wins — the
  surviving `id=""` row is "Moomin", and **The Bad Guys is gone**.
- Same-title items collide: Jellyfin has **two "Idiocracy"** entries → both → `id="idiocracy"` → one
  overwritten.

With year null (A), the slug also loses its `-year` disambiguator, widening collisions (remakes, etc.).

**Requirement:** ids must be non-empty, unique, **and stable across re-scans**. The fix must be
**deterministic per item** — never order-dependent. A naive "suffix the slug if it's already taken"
scheme is wrong here: the scan is multi-worker/concurrent and re-scans can process items in a different
order, so an item's id could flip between syncs and break detail URLs + `media_history` references. The
discriminator must come from the **item itself**, not scan order. Options to weigh in the plan:
- (a) when the slug is empty/degenerate, derive a stable id from **`jellyfinId`** (e.g. `item-<jellyfinId>`);
- (b) for genuine same-slug collisions, append a short **deterministic** discriminator from `jellyfinId`
  to the colliding items;
- (c) simplest/robust: make **`jellyfinId` the stored primary id** and keep the slug as a display field —
  `MediaStore.resolve()` already treats the Jellyfin UUID as canonical ("Jellyfin ID is the canonical URL
  form"), so this eliminates collisions by construction (migration/URL impact to be assessed).

With unique ids the duplicate "Idiocracy" appears twice — acceptable (surfaces a Jellyfin-side dup);
optionally de-dupe by `tmdbId` in a later pass.

### C. Episode `SxxExx` parsing — widen season match (FR-SQ1-C) — **medium**
**Evidence:** **34 episodes** have null season/episode → no TMDB episode lookup → no title/still/`tmdbEpisodeId`.
Two whole series lost all numbering:
- **Operation X** (6/6): files are `Operation.X.S2025E01…` — `SEASON_EP_RE = [Ss](\d{1,2})[Ee](\d{1,3})`
  (`Scanner.kt` L19) **caps the season at 2 digits**, so a year-as-season (`S2025`) never matches.
- **The Tonight Show / Jimmy Fallon** (16/16): date-based `Jimmy.Fallon.2026.03.05…` — no `SxxExx` at all.

**Requirement:** widen the season group to ≥4 digits (e.g. `[Ss](\d{1,4})[Ee](\d{1,3})`) so year-as-season
parses → episodes get season/episode numbers (sorting, NFO, display). **Caveat — limited benefit:** TMDB
organizes shows as seasons 1, 2, 3…, so a year-as-season (`S2025`) still won't match a TMDB season → those
episodes get *numbers* but **no TMDB title/still** unless a real date→season mapping is added (out of
scope). Optionally add a secondary date-based pattern (`YYYY.MM.DD`) for talk-show naming (Jimmy Fallon);
otherwise document that date-named episodes stay unparsed.

### D. Surface skipped / unmatched items (FR-SQ1-D) — **medium**
**Evidence:** 7 Jellyfin items were absent from jellystructure; all skips are **silent** (only a
`Logger.warn`), so a real drop looks identical to an intentional one:
- 2 lost to id collision (B).
- **4 LiveTV DVR recordings** (`/config/data/livetv/recordings/…`) — path matches no mapped library
  (`scanItem` returns null at `Scanner.kt` ~L40 "No matching library"). Reasonably skipped.
- **1 (Klovn)** — created in Jellyfin 4 s **after** the sync finished (timing); a re-sync picks it up.

**Requirement:** the scan must produce a **visible skip report** — count + reason for every Jellyfin item
returned but not stored. Separate **expected** skips (`no-matching-library` incl. LiveTV recordings,
libraries with `skip=true`) from **unexpected** ones (`file/dir-not-found-on-disk`, `no-episode-files`,
`id-collision` — which B should drive to ~0) and flag only the latter as problems. Emit a scan summary to
the activity log and expose a count the UI can show (e.g. Dashboard/Activity), so silent drops become
discoverable.

### E. Minor / low-hanging (FR-SQ1-E)
- **Untagged audio → `en` fallback (mostly by-design — likely keep).** **38 items** have audio that is
  *all* untagged (`language == null`) and resolve via the global `fallback_language = "en"`, mislabelling
  non-English ones (The 12th Man = Norwegian, Biblen = Danish). This is the **constitution's documented
  fallback** (untagged → triage, item uses fallback), not a scan bug. `resolvedLanguage` also feeds TMDB
  localization, the series-language card, and the re-pull override (`Scanner.kt` L575), so blanking it to
  "unknown" is **not** free. Optional honesty tweak only: surface that the value is fallback-derived
  (low-confidence), not change the resolution. Included for completeness — default **keep current**.
- **`nb` vs `no`** both present (Harald og Sonja=`nb`, Verdens verste menneske=`no`). Decide whether
  `LanguageResolver.normalize` should unify Norwegian variants (they are distinct ISO-639 codes — likely
  leave as-is, documented).
- **Zero-track episodes** (ffprobe returned nothing): Moomin `Mumi - S01E39` (.mp4), Jimmy Fallon
  Demi Lovato. Flag (and optionally one retry) rather than storing an episode with no tracks silently.

## Not bugs (verified correct — do not "fix")
- `resolvedLanguage = "arc"` (Aramaic) on *The Passion of the Christ* — the film is in Aramaic.
- Tag `damkjær-streaming` — a real user-defined Jellyfin tag; full scan pulls Jellyfin `Tags` (Phase 51),
  working as designed.
- 9 TMDB no-matches — Faroese/locally-dubbed titles (Grisa Peppa, Føroyaportal, Gud signi Føroyar…);
  genuinely unmatched. A's search-year may help a few marginally, but won't conjure TMDB entries that
  don't exist.

## Invariants / out of scope
- Renders/stores server-pushed + probed state only; no change to the tag lifecycle (Phase 51/52),
  language-resolution algorithm, or NFO writing beyond what year/id correctness requires.
- Not changing the triage model; D adds reporting, not a new editing surface.

## Design reference
`Scanner.kt` (`scanMovie`/`scanSeries`/`slugify`/`parseTitleYear`/`parseSeasonEpisode`/`SEASON_EP_RE`/`scanItem`),
`MediaStore.kt` (`addOrUpdate`/`update` upsert-by-id), `auth/Models.kt` (`JellyfinItem.year`),
`server/routes/MediaRoutes.kt` (`runScan` summary), `resolver/LanguageResolver.kt`.
