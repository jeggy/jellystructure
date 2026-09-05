# Phase 188 — a capped scan silently deletes every episode outside its sample

> Live bug report: Fjollerne (11 seasons, ~99 episodes) showed exactly one episode per season in the admin
> and in Ravilo. Reported as "seems like a general bug, not Fjollerne-specific" — confirmed against the
> production database: **135 of 184** TV shows are stuck at `episode_count = 8`, the exact value of
> prod's `scan_episode_cap`.

## Status
Implemented (2026-09-05). Compiles clean (`compileKotlinLinuxX64`). Not yet dev-reviewed. **Repair scan
not yet run**, and **prod's `scan_episode_cap` is still `8`** — the code fix stops further damage and
lets a future capped scan self-heal a series toward full coverage, but the 135 already-truncated shows
stay truncated in the DB until each is rescanned with the cap raised/cleared (see Non-goals).

**Config mitigation attempted and reverted:** `~/jellystructure/config/config.toml`'s
`scan_episode_cap` was hand-edited to `0` on 2026-09-05 while the prod backend was running, but the
live process holds config in memory and persists its own (still-`8`) value back to disk on its own
save trigger — the file read back `8` again within about a minute, with no edit made by anyone. A raw
file edit to a live instance's config.toml does not reliably stick; changing it for real needs the
app's own settings-save path (Settings UI or an authenticated config API call) or a restart after the
file edit, neither of which has been done.

### Implementation notes (2026-09-05)
- **Root cause confirmed directly against the prod DB**, not guessed: `fjollerne-2005`'s stored `episodes`
  array holds exactly 8 entries — S01E01, S02E04, S03E08, S05E03, S06E07, S08E01, S09E08, S10E05 — the
  exact evenly-spaced index set `selectSamples()` (`Scanner.kt:1315-1319`) produces for `maxSamples = 8`
  over a sorted ~99-file season tree. `select count(*) from media where kind='TV_SHOW' and
  episode_count=8` returned 135 (of 184 TV shows total); 46 more sit below 8 with genuinely short real
  runs (verified by title — e.g. `Y Talent` 2 seasons, `#CyberSleuths` 3-part miniseries); only 3 exceed
  8, all multi-episode-file shows where one probed file yields several `Episode` entries.
- **Mechanism**: `scanSeries` (`Scanner.kt:472-660`), which `scanItem` runs on **every** scan of an
  existing series (full library scans and scheduled scans alike — not just first discovery), applies
  `configStore.current.behavior.scanEpisodeCap` at `:491-497`. When the cap is positive and smaller than
  the series' file count, `filesToProbe = selectSamples(episodeFiles, cap)` — an evenly-spaced,
  **deterministic** subset (same sorted file list → same indices every time). The final `episodes` list
  (`:541-647`) is built by mapping **only** `filesToProbe`; nothing merges in a previously-stored episode
  for a file the cap left out. The resulting `MediaItem.episodes` — which fully replaces the prior value
  on write — therefore contains only the sampled subset, permanently, on every subsequent scan.
  Phase 183's `existingBySeasonEp` lookup (`:527-531`) already fetches the prior scan's episodes into
  memory at this exact call site, but only uses it to skip a redundant per-field re-fetch **within** an
  episode that got probed this pass (`:578-605`) — it was never extended to cover episodes the cap
  excluded from probing altogether.
- **This is the opposite of Phase 49's own intent** (`STATUS.md` row 49): that phase's entire point was
  fixing "the scan probed only a spread of 100 episodes" by defaulting the cap to `0` (unlimited) and
  adding an on-demand **uncapped** "Re-scan all episodes" escape hatch (FR-EP1) for when an operator
  deliberately wants a capped routine scan. A capped scan sampling a subset for a lighter probe is the
  intended trade-off; silently discarding the DB's record of every other episode, forever, on every pass,
  is not — it turns "probe a spread for the report" into "the show only has 8 episodes now," with no
  operator-visible signal that anything was lost (no warning banner, no triage flag, no History entry).
  Confirmed the config itself is exactly as expected: prod `config.toml`'s `scan_episode_cap = 8` (an
  operator choice, presumably against ffprobe/scan-load concerns per the 2026-08-31 scan/pacing
  incidents) is a legitimate value to set — the bug is entirely in how `scanSeries` treats episodes
  outside the sample, not in the config value itself.
- **Fix**: `scanSeries` now folds in the previous scan's episodes for every file the cap excluded this
  pass, instead of only building `episodes` from `filesToProbe`. Concretely: `existingByPath`, a
  `path → Episode` map built from the same `store?.resolveByJellyfinId(jItem.id)?.episodes` lookup
  already used for `existingBySeasonEp`; after the probed `episodes` list is built, every file in
  `episodeFiles` that is **not** in `filesToProbe` contributes `existingByPath[file]` if present (a file
  jellystructure has probed before, just not this pass) — carried over completely unchanged, tracks and
  all — or is silently dropped if there is no prior record (a file that has literally never been probed;
  Phase 49's original "scattered gaps until the operator runs an uncapped rescan" behaviour, unchanged,
  and the only case where a gap can still appear). A file that no longer exists on disk is never
  resurrected — `existingByPath` is filtered to `episodeFiles` (the current, live directory listing), so
  a genuinely deleted episode still disappears exactly as before. This does not touch the always-uncapped
  `syncSeriesEpisodes` path (Phase 49's FR-EP1 "Re-scan all episodes"), which was never affected.
- **No automated test added.** `scanSeries` is a private function wired directly to `FfprobeRunner`,
  `JellyfinClient`, `TmdbClient` and the real filesystem with no seam this codebase currently mocks
  (the one existing scanner test, `ScannerFilenameParsingTest`, only covers the pure filename-regex
  path). Exercising the carry-over logic properly would need that scaffolding built first — noted as a
  gap, not silently skipped.

## Problem
`scan_episode_cap` was designed as a probe-cost knob for a *scan pass*, not a retention policy for the
series' known episode list. Because `scanSeries` rebuilds `MediaItem.episodes` from scratch out of only
the files it chose to probe this pass, setting any positive cap converts every subsequent scan into a
one-way truncation: episode data (tracks, TMDB title/overview/still, cast, Jellyfin id, segment
eligibility) that was already known and correct gets overwritten with nothing, for every file outside
the deterministic sample, on every single scan from then on. Seasons & Episodes, the Artwork tab, and
every Ravilo surface (series detail, episode rails, Continue Watching entries referencing those
episodes) all read this same truncated list, so the damage is total and silent — no error, no triage
flag, `episode_count` just quietly reads low.

## Requirements

### FR-188-1 — A capped scan must never discard a previously known episode
For every file in the series directory that this pass's sample excluded, if jellystructure already has
a stored `Episode` for that exact file path from a prior scan, that episode must be carried into the
new `MediaItem.episodes` unchanged. Only files with no prior record at all (never yet probed) may be
absent from the result — matching Phase 49's original, intended "gap until an uncapped rescan" case.

### FR-188-2 — A deleted file is never resurrected
The carry-over in FR-188-1 is keyed against the current on-disk `episodeFiles` listing, not blindly
against every episode the DB previously held — a file removed from disk disappears from the result
exactly as it does today, regardless of cap.

### FR-188-3 — No behaviour change for `scan_episode_cap = 0` or an uncapped/per-item sync
The default (unlimited) path and `syncSeriesEpisodes` (Phase 49's FR-EP1 "Re-scan all episodes") already
probe every file and are untouched by this fix.

## Non-goals
- **Repairing the 135 already-truncated shows in the live DB.** This fix only stops the ongoing loss and
  lets a series self-heal (regain its full episode list) the next time it's scanned while the cap no
  longer excludes files it previously knew about — which, per FR-188-1, now happens automatically since
  the carry-over restores a show to its full known set the very first time a probed sample plus
  carried-over existing episodes covers everything again. It does not force that rescan, and does not
  by itself raise or clear prod's `scan_episode_cap` (still `8` as of this writing — see Status) —
  that's a deliberate operator action via the app's own settings path, not something this phase's code
  does on its own.
- **Any UI-visible warning that a capped scan is leaving files unprobed.** Phase 49 already accepted this
  as visible-by-gap-in-the-UI; this phase only fixes the part that made the gap permanent and
  cap-invisible-of-scale. A dedicated triage/banner signal is a candidate follow-up if the operator wants
  cap usage to stay visible going forward.
- Changing `selectSamples`'s spread algorithm, or making it non-deterministic/rotating across scans so a
  capped series eventually gets full coverage on its own. Not requested, and rotation would reintroduce
  the exact hazard this phase closes (a rotated-out file's episode would vanish again) unless paired with
  the same carry-over logic — at which point every file ends up covered anyway and rotation adds nothing.

## Acceptance
- A series with more files than `scan_episode_cap`, scanned twice in a row with the cap unchanged
  between runs, should end the second scan with the same full episode set it had after the first — not
  shrunk to the sampled subset. Not covered by an automated test (see implementation notes); reasoned
  through by inspection of the carry-over logic, not exercised live.
- A file deleted from disk between two capped scans should still be absent from the second scan's
  episode list even though FR-188-1's carry-over would otherwise still hold a stored record for it —
  same caveat, not test-covered.
- `compileKotlinLinuxX64` clean (verified).
- Not yet verified live against the 135 affected prod shows — needs prod's `scan_episode_cap` actually
  raised/cleared through the app's own settings path (a raw config.toml edit did not stick — see
  Status) plus a scan of each affected show, both pending explicit go-ahead per this project's
  no-unrequested-restart/scan convention.

## Source references
- `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt:472-660` (`scanSeries`)
- `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt:1315-1319` (`selectSamples`)
- `STATUS.md` row 49 (original cap + FR-EP1 intent)
- `~/jellystructure/config/config.toml` (prod `scan_episode_cap`, still `8` as of 2026-09-05 — a raw
  file edit to `0` did not survive the running process)
