# Phase 152 — Scanner falls back to Path when Jellyfin has no episode IndexNumber (FR-SCAN1)

> Reported as "Stormester should be in Continue Watching but isn't." Investigated live: the specific
> episode is a genuine Jellyfin-side metadata gap (that file has no `IndexNumber` in Jellyfin at all),
> which is outside jellystructure's control to force — Jellyfin's own NextUp/IsResumable algorithm
> can't place an unnumbered episode no matter what jellystructure does. What IS in scope, and what
> this phase fixes: jellystructure's own scanner has the exact same single-point-of-failure — it only
> ever joins an episode to its Jellyfin id by `(season, episode)` index, so when Jellyfin's own index
> is missing, jellystructure silently drops the join too, even though it can usually determine the
> right season/episode itself from the filename. That silent `jellyfinId = null` then breaks every
> jellystructure-side feature keyed on it (playstate overlay, detail page, "next episode" resolution).

**Status:** Implemented. Requires a re-scan of any affected series (e.g. Stormester) to take effect — the
fallback runs at scan time, so already-stored episode rows keep `jellyfinId: null` until their series is
next scanned/re-pulled.

> **Correction (2026-07-30, Phase 153):** this spec's framing above and its "Out of scope" section below
> were wrong to treat Jellyfin's missing `IndexNumber` as purely an external/operator problem.
> jellystructure is the metadata authority for Jellyfin (`constitution.md`) — it already owns the
> write-NFO-and-refresh mechanism, and Phase 153 found that mechanism simply never ran for this class of
> episode on the routine scheduled scan. **Phase 153 closes that gap**: jellystructure now writes a
> corrective episode NFO and triggers a Jellyfin refresh automatically for exactly the population this
> phase's own `unresolved_jellyfin_id` Triage type flags, no operator action required. This phase's own
> fallback join (FR-SCAN1-2) and Triage signal (FR-SCAN1-3) remain correct and are what Phase 153 builds
> on — only the "this is outside our control" framing was superseded.

## Bug report / investigation
"Clicking on the DanskTV channel, there is only one item in Continue Watching, but there should be a
bunch — Stormester should be in there but isn't." (2026-07-30.)

Channel Continue Watching is not a separate/stale code path — `HomeFeedService.getChannelFeed()`
(`src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt:174-201`) calls the same
`buildContinueRow()` Home uses, just handed a channel-filtered candidate list. "Stormester" **is** correctly
a member of the DanskTV channel (`network = "TV 2"` matches the channel's configured network condition,
verified against `config/jellystructure.db`) — channel membership isn't the bug.

Querying Jellyfin directly for the series: `UserData.UnplayedItemCount: 1`, and that one remaining episode
(**S10E07 "Lugten af skam"**, Jellyfin id `1ba13ecc0dde509a58d9de80cf768bd6`) has **no `IndexNumber` field at
all** in Jellyfin (only `ParentIndexNumber: 10`) and **empty `ProviderIds: {}`** — Jellyfin's own metadata
match never numbered this file
(`Stormester.S10E07.DANiSH.1080p.WEB.h264-STROMPEBUKSER.mkv`). `GET /Shows/NextUp?seriesId=…` for this series
returns **`Items: []`** — confirmed empty even scoped directly to it. Jellyfin's own Next-Up/Resume
algorithms require a valid episode ordinal to place a series at all; without one, the *entire* series drops
out of both, independent of anything jellystructure does.

**This means jellystructure cannot make "Stormester" itself appear in Continue Watching** — that requires
Jellyfin to have a valid `IndexNumber` for the file, which is a Jellyfin library-metadata fix outside this
codebase (e.g. renaming the file to match Jellyfin's own naming rules, or manually identifying/renumbering
the episode in Jellyfin, followed by a Jellyfin library refresh).

**What jellystructure's own bug is:** `Scanner.kt`'s episode→Jellyfin-id join has the identical weakness.
`jfBySeasonEp = jfEpsMeta.associateBy { (it.parentIndexNumber ?: 0) to (it.indexNumber ?: 0) }`
(`src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt:327`) keys purely on Jellyfin's own
`IndexNumber` — when that's missing, the lookup misses and `jellyfinId` stays `null`
(`Scanner.kt:382,399`), even though jellystructure's own `parseSeasonEpisodes(file)` (`Scanner.kt:358`)
correctly derives `S10E07` from the filename for this exact file (confirmed against the local scan: the
episode's own `season 10` list already has the right title "Lugten af skam" from filename-derived TMDB
lookup, just `jellyfinId: null`). A `null` id then falls out of every id-keyed jellystructure feature:
`DetailService`'s playstate overlay filters out any id that doesn't come from Jellyfin (any id starting with
`/`, the synthetic fallback), so the episode row can't show its own watch state; `groupEntryPoint`-style
"pick the next unwatched episode" logic can't target it either. This is a second, more general instance of
the same failure mode `phase-R184-autoplay-next-stale-position.md`'s "out of scope" note flagged for
multi-episode files specifically (`Scanner.kt:327` there too) — the underlying join fragility is broader
than that note scoped it to.

## Requirements

### FR-SCAN1-1 — Fetch Jellyfin's own Path alongside episode metadata
`JellyfinClient.getSeriesEpisodesMeta` (`auth/JellyfinClient.kt:440-451`) adds `Path` to its `Fields=` query
param; `JellyfinEpisodeItem` (`auth/Models.kt:250-260`) gains `@SerialName("Path") val path: String? = null`.

### FR-SCAN1-2 — Fall back to a path match when the season/episode join misses
In `scanSeries` (`Scanner.kt`), when `jfBySeasonEp[seasonNum to epNum]` misses (Jellyfin never numbered the
file), fall back to matching by path: translate the episode's own local `file` path to its Jellyfin-side
equivalent using the same per-library `jellyfinPath`/`localPath` prefix substitution `scanItem` already
computes for the series' own directory (`Scanner.kt:134-148`, `LibraryMapping`) — reversed
(`file.replaceFirst(lib.localPath, lib.jellyfinPath)`) — and look that up in a new `jfByPath` map built from
`jfEpsMeta`'s `path` field. `scanSeries` needs `lib: LibraryMapping` threaded in from `scanItem` for this
(currently only the already-translated `localPath` string is passed). This resolves `jellyfinId` for any
file Jellyfin's own scanner placed correctly on disk but failed to number/identify — Stormester's S10E07
included, once jellystructure re-scans that series — restoring the local playstate overlay, detail page, and
next-episode targeting for it, even though (per the investigation above) it still can't make Jellyfin's own
NextUp/IsResumable include the series until the Jellyfin-side metadata gap is separately fixed.

### FR-SCAN1-3 — Surface the gap instead of failing silently
Add a `TriageDetection.unresolvedJellyfinIdCount(item)` (mirroring `duplicateEpisodeCount`,
`TriageDetection.kt`): counts episodes where `episodeNumber != null` (jellystructure successfully parsed a
season/episode from the filename) but `jellyfinId == null` (neither the index nor the new path join
resolved it) — i.e. genuinely unresolvable, not just an unparseable filename. Wire a new
`unresolved_jellyfin_id` `TriageTypeCount` into `/triage/count` (`TriageRoutes.kt`, alongside
`duplicate_episode`) so a future case like this shows up on the Triage dock instead of only being found by
chance report. Message should point at the real fix: check/fix the episode's numbering in Jellyfin itself.

## Invariants
- **A file jellystructure can confidently place at (season, episode) from its own filename parse is never
  silently left with `jellyfinId = null`** if Jellyfin has *any* record of that file at all (by index or by
  path) — the fallback only fails when Jellyfin genuinely has no matching item on disk.
- **This phase never writes to Jellyfin.** It only reads more fields during the existing scan and surfaces
  what it finds; fixing Jellyfin's own missing `IndexNumber` is an operator action outside this codebase.

## Out of scope
- Writing season/episode metadata back to Jellyfin to force it to number the file (would need an NFO push +
  Jellyfin refresh cycle, and Jellyfin's *reason* for not matching this file — likely the release-group
  suffix in `Stormester.S10E07.DANiSH.1080p.WEB.h264-STROMPEBUKSER.mkv` confusing its own parser — wasn't
  root-caused; a targeted rename/manual-identify in Jellyfin is the direct fix and is on the operator).
- The played/`PlaybackPositionTicks` desync bug also reported this session — unrelated root cause, see
  **R185**.

## Dev-review addendum (2026-07-30 — implementation notes)

1. **`unresolved_jellyfin_id` counts by episode, not by title, same as `duplicate_episode`.** A title with
   several unresolved episodes counts each instance; `unresolvedIdTitles` counts how many titles have at
   least one, matching the existing dashboard convention.
2. Verified via `compileKotlinLinuxX64`, `linuxX64Test` (full suite passes), `compileKotlinWasmJs` (admin —
   no DTO shape changed, `TriageTypeCount` already had the right fields). Not on-device/live-scan verified
   this session (would need a re-scan of Stormester against the live Jellyfin, which is a scan-triggering
   action — left for the user to run alongside the Jellyfin-side metadata fix noted above).

## Source references
- Join: `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt` (`scanItem`, `scanSeries`,
  `jfBySeasonEp`).
- Jellyfin fetch: `src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt`
  (`getSeriesEpisodesMeta`), `auth/Models.kt` (`JellyfinEpisodeItem`).
- Triage: `src/linuxX64Main/kotlin/dev/jellystructure/media/TriageDetection.kt`,
  `server/routes/TriageRoutes.kt`.
- Related: **phase-R184-autoplay-next-stale-position.md** (out-of-scope note that first flagged the
  narrower multi-episode-file instance of this same join weakness), **R185** (the unrelated bug reported
  alongside this one).
