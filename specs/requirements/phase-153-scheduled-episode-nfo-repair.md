# Phase 153 — jellystructure actively repairs an unmatched episode's Jellyfin numbering (FR-SCAN2)

> Correction to Phase 152's framing: "Stormester's S10E07 has no IndexNumber in Jellyfin" is not an
> external Jellyfin problem to shrug off — jellystructure's constitution is explicit that it "fully
> replaces Jellyfin's built-in metadata scraper" and "the system calls Jellyfin's REST API to trigger
> a refresh — the user never has to do this manually." If an episode is stuck unmatched, that's a gap
> in jellystructure's own write/notify pipeline. This phase closes it: on the routine scheduled scan
> (not just a manual per-item click), jellystructure now writes a correct `<season>`/`<episode>` NFO
> for any episode it can't otherwise resolve to a Jellyfin id, and tells Jellyfin to re-read it.

**Status:** Planned.

## Investigation
Traced the full write→notify pipeline for this specific file
(`Stormester.S10E07.DANiSH.1080p.WEB.h264-STROMPEBUKSER.mkv`) and confirmed on disk
(`/mnt/series/jellyfin/Taskmaster (DK)/Season 10/`): every sibling episode (E01–E06) has a
jellystructure-written `.nfo` (content matches `NfoWriter.buildEpisodeDetailsBlock` exactly, e.g. E06's
`<season>10</season><episode>6</episode>`); **E07 has no `.nfo` at all.** The series' own `tvshow.nfo`
exists and is recent, but the DB's own `nfoWrittenAt`/`nfoHash`/`jfSyncedAt` for this item are all `null`.

Three compounding gaps, all in jellystructure's own pipeline:

1. **The scheduled `write_nfo` step never writes episode NFOs.** `PipelineStepOps.writeNfo`
   (`src/linuxX64Main/kotlin/dev/jellystructure/media/PipelineStepOps.kt:61-83`) takes
   `includeEpisodes: Boolean = false`; the scheduled pipeline's call site
   (`src/linuxX64Main/kotlin/dev/jellystructure/Main.kt:573-576`) never passes it, so every scheduled run
   only (re)writes `tvshow.nfo` — never `episodedetails.nfo`. The **only** automatic path that ever writes
   episode NFOs is `RealtimeIngestService` (`includeEpisodes = true`, event-driven off a Jellyfin
   `LibraryChanged`/*arr webhook) — for whatever reason (predates realtime ingest being enabled, or the
   event never reached it) that never fired for E07, and nothing else ever retries.
2. **`nfoWrittenAt`/`nfoHash`/`jfSyncedAt` are silently wiped to `null` on every `scan_files` run.**
   `Scanner.scanMovie`/`scanSeries` construct a brand-new `MediaItem(...)` that never sets these three
   fields (they default to `null`), and `MediaStore.addOrUpdate`
   (`src/linuxX64Main/kotlin/dev/jellystructure/media/MediaStore.kt:544-580`) — which already carries
   forward `createdAt`, JS tags, the artwork lock, and episode `createdAt` from `old` — does **not** carry
   these three forward. This breaks the Phase-115 drift-staleness state machine every single scheduled
   cycle: `write_nfo`'s content-hash compare (`wouldBeHash == current.nfoHash`) can never see a match once
   `nfoHash` is null, and `sync_jellyfin`'s gate (`nfoWrittenAt > jfSyncedAt`, `PipelineStepOps.kt:88`,
   `Main.kt:589`) is comparing against a freshly-nulled baseline instead of real history.
3. **The manual "Write NFO" button never triggers a Jellyfin refresh at all.** `POST /api/media/{id}/nfo`
   (`server/routes/MediaRoutes.kt:464-488`) writes the NFO(s) but has no call to
   `jellyfinClient.refreshItem`/`triggerLibraryRefresh` — contradicting `specs/plan.md:231`, which
   documents this route as *"Write NFO (+ episodedetails for TV); trigger Jellyfin refresh."*

`NfoWriter.buildEpisodeDetailsBlock` (`nfo/NfoWriter.kt:183-231`) already writes exactly the tags Jellyfin's
own NFO episode identifier reads (`<season>`/`<episode>`), and `Scanner.parseSeasonEpisodes` already derives
the right `(10, 7)` for this file from its filename — the content was never the problem, only that nothing
ever got it onto disk and told Jellyfin to look.

**Considered and rejected:** directly `POST /Items/{id}` to Jellyfin with a patched `IndexNumber` instead of
going through NFO+refresh. Jellyfin's item-update endpoint expects a full `BaseItemDto` round-trip; a
partial/incomplete body risks silently clobbering other Jellyfin-side fields this codebase doesn't model.
NFO+refresh is the architecturally sanctioned mechanism (`constitution.md`) and lower-risk — use it.

## Requirements

### FR-SCAN2-1 — Stop wiping NFO/sync tracking state on every scan
`MediaStore.addOrUpdate` carries `nfoWrittenAt`, `nfoHash`, and `jfSyncedAt` forward from `old` (the same
`stale ?: existing` predecessor Phase 151's artwork-lock guard already uses), matching the existing
`preserveJsTags`/`preserveLockedArtwork`/`stampEpisodeCreatedAt`/`stampTimestamps` pattern. This alone fixes
`write_nfo`'s false-foreign-NFO risk (comparing against a real hash instead of a reset-to-null one) and is a
prerequisite for FR-SCAN2-3 actually firing a refresh.

### FR-SCAN2-2 — The scheduled scan repairs any episode it can't otherwise resolve
`PipelineStepOps.writeNfo`, independent of the `includeEpisodes` bulk flag, additionally writes NFOs for any
episode whose Jellyfin join failed — `episodeNumber != null && jellyfinId == null` (exactly what Phase 152's
`unresolved_jellyfin_id` Triage type flags) — via the existing `NfoWriter.writeEpisodeNfos`. To keep a
multi-episode file's combined NFO complete, write the **whole file's** episode group (all `partIndex`
siblings) when *any* one of them is unresolved, not just the unresolved part — `writeEpisodeNfos` already
groups by shared path; the caller only needs to pass every episode belonging to an affected file, not the
narrower unresolved subset. This is deliberately narrower than turning on `includeEpisodes` for every
scheduled run (which would rewrite every episode NFO in the whole library every cycle) — it only ever
touches files that genuinely have no working Jellyfin join, so it's self-limiting: once Jellyfin picks up
the fix, the next scan's join succeeds and this stops firing for that file.

### FR-SCAN2-3 — Close the loop: a repair write must actually reach Jellyfin
Writing a repair NFO under FR-SCAN2-2 bumps the item's `nfoWrittenAt` (independent of whether the
series-level `tvshow.nfo` content hash also changed) so the existing `sync_jellyfin` staleness gate — now
meaningful again per FR-SCAN2-1 — fires a `refreshItem(full = true)` for the series on its next pipeline
step in the same scheduled run (`write_nfo` → `sync_jellyfin` run sequentially over the same `workingSet`).

### FR-SCAN2-4 — Fix the manual route to match its own spec
`POST /api/media/{id}/nfo` calls `jellyfinClient.refreshItem` after a successful write, matching
`plan.md`'s documented contract and the pattern `pushToJellyfin` already follows.

## Invariants
- **An episode jellystructure can confidently number from its own filename parse never stays
  Jellyfin-unmatched indefinitely without jellystructure itself attempting a fix.** No operator action is
  required for the common case (Jellyfin's own scanner placed the file but didn't number it).
- **This only ever writes NFOs for files with a genuine join failure** — an already-resolved library's
  scheduled `write_nfo` step touches exactly as many files as before this phase (zero episode NFOs, unless
  `includeEpisodes` is separately requested), so there's no library-wide extra I/O per cycle.

## Out of scope
- `behavior.tell_jellyfin` — found dead (a Settings toggle with zero call-site references) while auditing
  this area; real bug, but unrelated to this repair loop (the refresh calls this phase touches don't gate
  on it today either) — worth its own follow-up.
- Forcing Jellyfin to trust NFO over its own online metadata providers for a library where that's
  misconfigured — jellystructure has no way to detect or correct Jellyfin's own reader-priority config
  remotely; this phase assumes the constitution's stated architecture (Jellyfin reads jellystructure's NFO)
  holds, same as every other NFO-writing phase already assumes.
- Retrying `RealtimeIngestService`'s event-driven path for episodes it missed historically — FR-SCAN2-2's
  scheduled-scan repair is a superset that also catches this case on the next cycle, no separate backfill
  needed.

## Source references
- Tracking-field reset: `src/linuxX64Main/kotlin/dev/jellystructure/media/MediaStore.kt` (`addOrUpdate`,
  `stampTimestamps`).
- Repair write: `src/linuxX64Main/kotlin/dev/jellystructure/media/PipelineStepOps.kt` (`writeNfo`,
  `syncJellyfin`), `src/linuxX64Main/kotlin/dev/jellystructure/nfo/NfoWriter.kt` (`writeEpisodeNfos`).
- Pipeline wiring: `src/linuxX64Main/kotlin/dev/jellystructure/Main.kt` (`write_nfo`, `sync_jellyfin` step
  handlers).
- Manual route: `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/MediaRoutes.kt`
  (`POST /{id}/nfo`), `specs/plan.md:231` (the contract it was missing).
- Related: **Phase 152** (`phase-152-scanner-episode-path-fallback.md` — the Triage signal this phase acts
  on; its "out of scope" framing of the NFO-push idea is what this phase supersedes), **Phase 115** (the
  original drift-staleness design this phase restores), **Phase 149** (multi-episode NFO grouping this
  phase's repair write must stay compatible with).
