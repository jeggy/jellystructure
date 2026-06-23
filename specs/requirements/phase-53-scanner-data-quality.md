# Phase 53 — Scanner data-quality fixes (FR-SQ1)

**Status:** Planned · _filed 2026-06-23 from a post-reset full-sync data review (296 scanned items vs 303
in Jellyfin)._

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

**Requirement:** resolve year on scan with precedence **name-parsed → TMDB release year → Jellyfin
`ProductionYear`**, mirroring the sync paths. Resolve it **before** the TMDB search so `searchMovie/searchTv(title, year)`
matches more precisely, and feed the resolved year into the id (see B). Apply to `scanMovie` and both
`scanSeries` return paths (normal + language-mix).

### B. Stable, unique item ids — stop silent overwrite/data-loss (FR-SQ1-B) — **high**
**Evidence:** **2 items silently lost.** `slugify(title, year)` = lowercase, `[^a-z0-9]+ → -`, trimmed
(`Scanner.kt` ~L670):
- Non-Latin titles collapse to an **empty id**: `बुरे बंदे` (The Bad Guys) and `楽しいムーミン一家`
  (Japanese Moomin) both → `id = ""`. They collide; `MediaStore` upserts by id, so last-write-wins — the
  surviving `id=""` row is "Moomin", and **The Bad Guys is gone**.
- Same-title items collide: Jellyfin has **two "Idiocracy"** entries → both → `id="idiocracy"` → one
  overwritten.

With year null (A), the slug also loses its `-year` disambiguator, widening collisions (remakes, etc.).

**Requirement:** ids must be **non-empty and unique per item**. When `slugify` yields empty or already-taken,
fall back to a stable token — append/derive from **`jellyfinId`** (always present, globally unique) or
`tmdbId`. Guarantee no two scanned items share an id within a scan. (Keep human-readable slugs where the
title is Latin; only degrade to the id-token fallback when needed.) Note: with unique ids the duplicate
"Idiocracy" will appear twice — acceptable (it surfaces a Jellyfin-side dup); optionally de-dupe by
`tmdbId` in a later pass.

### C. Episode `SxxExx` parsing — widen season match (FR-SQ1-C) — **medium**
**Evidence:** **34 episodes** have null season/episode → no TMDB episode lookup → no title/still/`tmdbEpisodeId`.
Two whole series lost all numbering:
- **Operation X** (6/6): files are `Operation.X.S2025E01…` — `SEASON_EP_RE = [Ss](\d{1,2})[Ee](\d{1,3})`
  (`Scanner.kt` L19) **caps the season at 2 digits**, so a year-as-season (`S2025`) never matches.
- **The Tonight Show / Jimmy Fallon** (16/16): date-based `Jimmy.Fallon.2026.03.05…` — no `SxxExx` at all.

**Requirement:** widen the season group to ≥4 digits (e.g. `[Ss](\d{1,4})[Ee](\d{1,3})`) so year-as-season
parses. Optionally add a secondary **date-based** pattern (`YYYY.MM.DD` → a synthetic season/episode or
date-titled episode) for talk-show naming; if out of scope, document that date-named episodes stay
unparsed.

### D. Surface skipped / unmatched items (FR-SQ1-D) — **medium**
**Evidence:** 7 Jellyfin items were absent from jellystructure; all skips are **silent** (only a
`Logger.warn`), so a real drop looks identical to an intentional one:
- 2 lost to id collision (B).
- **4 LiveTV DVR recordings** (`/config/data/livetv/recordings/…`) — path matches no mapped library
  (`scanItem` returns null at `Scanner.kt` ~L40 "No matching library"). Reasonably skipped.
- **1 (Klovn)** — created in Jellyfin 4 s **after** the sync finished (timing); a re-sync picks it up.

**Requirement:** the scan must produce a **visible skip report** — count + reason for every Jellyfin item
returned but not stored: `no-matching-library`, `file/dir-not-found-on-disk`, `no-episode-files`,
`id-collision` (once B exists this should be ~0). Emit to the activity log as a scan summary and expose a
count the UI can show (e.g. on Dashboard/Activity), so silent drops become discoverable.

### E. Minor / low-hanging (FR-SQ1-E)
- **Untagged audio → wrong `en` fallback.** **38 items** have audio tracks that are *all* untagged
  (`language == null`); they resolve via the global `fallback_language = "en"`, mislabelling non-English
  ones (The 12th Man = Norwegian, Biblen = Danish → shown `en`). They already raise `issueCount`/triage;
  the ask is to **not assert a confident language** for fully-untagged audio (e.g. mark resolved language
  as unknown/needs-review rather than silently `en`), keeping the value honest. Decide vs. keep current.
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
  genuinely unmatched. A+B make their search marginally better but won't conjure TMDB entries.

## Invariants / out of scope
- Renders/stores server-pushed + probed state only; no change to the tag lifecycle (Phase 51/52),
  language-resolution algorithm, or NFO writing beyond what year/id correctness requires.
- Not changing the triage model; D adds reporting, not a new editing surface.

## Design reference
`Scanner.kt` (`scanMovie`/`scanSeries`/`slugify`/`parseTitleYear`/`parseSeasonEpisode`/`SEASON_EP_RE`/`scanItem`),
`MediaStore.kt` (`addOrUpdate`/`update` upsert-by-id), `auth/Models.kt` (`JellyfinItem.year`),
`server/routes/MediaRoutes.kt` (`runScan` summary), `resolver/LanguageResolver.kt`.
