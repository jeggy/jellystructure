# Phase 160 — Scanner falls back to Jellyfin's own numbering when the filename can't be parsed (FR-SCAN3)

> Reported as "why are all episodes 0 in Ravilo for 'La Curva'?" Investigated live against Jellyfin,
> Sonarr, and jellystructure's own DB. Root cause is entirely jellystructure-side: its filename-parsing
> regexes don't recognize this show's naming convention, and unlike its Jellyfin-id join
> (**phase-152**), its season/episode *numbering* never falls back to the correct numbers Jellyfin's
> own scanner already assigned to the same files.

**Status:** Implemented.

## Bug report / investigation
"La Curva" (1978, claymation shorts) has 96 files named `La Curva - 101.mkv` … `La Curva - 225.mkv`,
sitting in real `Season 01`/`Season 02` folders. The embedded convention is a bare 3-digit run: the
first digit is the season, the last two are the zero-padded episode number within that season
(`101` → S1E1 … `156` → S1E56, `200` → S2E0 … `225` → S2E25) — no `SxxEyy` marker, no `x`-separated
Kodi form.

**Jellyfin — queried live** (`GET /Shows/{id}/Episodes?Fields=Path,IndexNumber,ParentIndexNumber`):
82 episodes, **zero** with a null `IndexNumber` — Season 1 = episodes 1–56, Season 2 = episodes 0–25,
fully sequential and correct. Jellyfin's own scanner recognizes this bare-digit form (folder structure
+ its own broader inference), independent of jellystructure.

**Sonarr — queried live** (`/api/v3/series`, `/api/v3/episode`, `/api/v3/episodefile`): also tracks
the show (id 347) via its own TVDB-sourced season/episode metadata — 97 episodes total (incl. a
Season-0 specials block), 82 with files. Its numbering is TVDB-derived, not filename-derived, and is
offset from the filename numbers in places (e.g. file `225.mkv` is Sonarr's S2E26 "The Dance", not
E25) — not the source jellystructure should trust for this fix; Jellyfin (which is keyed to the
files that are actually on disk) is.

**jellystructure's own DB:** all 96 episodes have `seasonNumber: null, episodeNumber: null`. Root
cause, in `Scanner.kt`:
- `parseSeasonEpisodes(path)` (`Scanner.kt:68-111`) only recognizes `SxxEyy` (`SEASON_EP_RE`) or the
  `x`-separated Kodi form (`ALT_SEASON_EP_RE`/`ALT_MULTI_EP_HEAD_RE`) — neither matches a bare `SEE`
  run, so it falls through to `Pair(null, emptyList())` for every La Curva file.
- `scanSeries` (`Scanner.kt:377-409`, pre-fix) and `syncSeriesEpisodes` (`Scanner.kt:644-` , pre-fix)
  get season/episode numbers **exclusively** from that filename parse. Both already fetch Jellyfin's
  own `IndexNumber`/`ParentIndexNumber` per episode (`jfEpsMeta`/`jfByPath`/`jfByPathSync`, built for
  **phase-152**'s Jellyfin-id join) but never consulted it as a numbering fallback — only for looking
  up `jellyfinId`. The resulting `null`s are coerced to `0` downstream for display
  (`DetailService.kt:87,91,92,104`, `TvRoutes.kt:144,734`), which is why Ravilo showed every episode
  as Season 0 / Episode 0.

This is a different failure mode than phase-152/153: those cover Jellyfin having **no** number for a
file jellystructure *could* number from the filename. Here it's the reverse — jellystructure can't
number the file at all, while Jellyfin already could.

## Requirements

### FR-SCAN3-1 — `resolveSeasonEpisode`: a pure fallback function
New top-level function in `Scanner.kt`, alongside `parseSeasonEpisodes`:

```kotlin
internal fun resolveSeasonEpisode(filenameParsed: Pair<Int?, List<Int>>, jfSeason: Int?, jfEpisode: Int?): Pair<Int?, List<Int>>
```

Returns `filenameParsed` unchanged whenever it found at least one episode number (the filename is the
more specific signal when it succeeds — never overridden). Only when `filenameParsed.second` is empty
**and** both `jfSeason` and `jfEpisode` are non-null does it return `Pair(jfSeason, listOf(jfEpisode))`.
Unit-tested directly (`ScannerFilenameParsingTest`), same pattern as `parseSeasonEpisodes`.

### FR-SCAN3-2 — Wire the fallback into `scanSeries`
In the per-file `async` block, compute the file's Jellyfin-path match (`jfByPath[translatedJfPath]`,
the same lookup already used for the `jellyfinId` join) *before* calling `parseSeasonEpisodes`, and
run the result through `resolveSeasonEpisode`. The existing `jellyfinId` join is unchanged in
behaviour but now reuses this one lookup instead of recomputing the translated path a second time.

### FR-SCAN3-3 — Wire the same fallback into `syncSeriesEpisodes`
Same shape, using the function's existing `jfByPathSync`/`toJellyfinPath` (already present for
**phase-153**'s `jellyfinIndexMissing` flag) — so a per-item re-sync doesn't regress a series' numbers
back to null relative to what a full `scanSeries` run would produce.

## Invariants
- **A file jellystructure's own filename parse can't number is never left unnumbered if Jellyfin has
  already numbered the same file on disk.** The fallback only leaves `season`/`episode` null when
  Jellyfin genuinely has no `IndexNumber`/`ParentIndexNumber` for that path either.
- **The filename parse always wins when it succeeds.** This phase never second-guesses a number
  `parseSeasonEpisodes` already derived, even if Jellyfin's own number happens to disagree — filename
  parsing has been the ground truth for jellystructure's own re-numbering/repair logic
  (phase-153's NFO writes) and stays that way.
- **No new filename regex.** A bare 3-digit convention like La Curva's is genuinely ambiguous on its
  own (collides with resolution tokens, dates, etc. far more easily than `SxxEyy` or `NxNN` do) — this
  phase deliberately leans on Jellyfin's already-correct, convention-agnostic numbering instead of
  trying to regex-match every possible bare-digit scheme.

## Out of scope
- `syncSeriesEpisodes`'s separate `jellyfinId` join (`Scanner.kt`, `jfBySeasonEp[seasonNum to epNum]?.id`)
  still doesn't fall back to a path match the way `scanSeries`'s does — a pre-existing gap distinct
  from this phase's numbering fix, not touched here.
- No write-back to Jellyfin or NFO changes — this phase only changes what jellystructure derives at
  scan time from data it already fetches, same boundary phase-152 drew.
- La Curva needs a re-scan (or a `scan_files` cycle) to pick this up; already-stored `null` episode
  rows keep showing as `0` until then, same caveat phase-152 documented.

## Dev-review addendum (2026-08-09 — implementation notes)
1. `resolveSeasonEpisode` requires **both** `jfSeason` and `jfEpisode` non-null before trusting
   Jellyfin's numbers — a partial match (season only) is treated as still-unresolved, matching how
   `parseSeasonEpisodes` itself never returns a season without at least trying for an episode.
2. Verified via `compileKotlinLinuxX64` and `linuxX64Test` (full suite green, incl. 4 new
   `resolveSeasonEpisode` cases). Not live-rescan-verified this session (would require triggering a
   re-scan of La Curva against the live library, a scan-triggering action left for the user).

## Source references
- `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt` — `parseSeasonEpisodes`,
  `resolveSeasonEpisode` (new), `scanSeries`, `syncSeriesEpisodes`.
- `src/linuxX64Test/kotlin/dev/jellystructure/media/ScannerFilenameParsingTest.kt` — new
  `resolveSeasonEpisode` cases.
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/DetailService.kt`,
  `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/TvRoutes.kt` — the `?: 0` display
  coercions that made the bug visible in Ravilo (unchanged by this phase; now see real numbers once
  a rescan lands).
- Related: **phase-152-scanner-episode-path-fallback.md** (the Jellyfin-id join this phase's fallback
  lookup reuses), **phase-153-scheduled-episode-nfo-repair.md** (the inverse direction — repairing
  Jellyfin's numbering from jellystructure's).
