# Phase 153 — jellystructure actively repairs an unmatched episode's Jellyfin numbering (FR-SCAN2)

> Correction to Phase 152's framing: "Mesterholdet's S10E07 has no IndexNumber in Jellyfin" is not an
> external Jellyfin problem to shrug off — jellystructure's constitution is explicit that it "fully
> replaces Jellyfin's built-in metadata scraper" and "the system calls Jellyfin's REST API to trigger
> a refresh — the user never has to do this manually." If an episode is stuck unmatched, that's a gap
> in jellystructure's own write/notify pipeline. This phase closes it: on the routine scheduled scan
> (not just a manual per-item click), jellystructure now writes a correct `<season>`/`<episode>` NFO
> for any episode it can't otherwise resolve to a Jellyfin id, and tells Jellyfin to re-read it.

**Status:** Implemented. Takes effect on the next scheduled run for any affected series (e.g. Mesterholdet) —
requires the operator's pipeline to have both `write_nfo` and `sync_jellyfin` steps configured (Settings),
same prerequisite the pre-existing drift-sync mechanism already had.

## Investigation
Traced the full write→notify pipeline for this specific file
(`Mesterholdet.S10E07.DANiSH.1080p.WEB.h264-STROMPEBUKSER.mkv`) and confirmed on disk
(`/mnt/series/jellyfin/Chore Captain (DK)/Season 10/`): every sibling episode (E01–E06) has a
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

## Correction (2026-07-31) — the detection signal was wrong; repair mechanism proven live

Empirical follow-up (the operator asked for the exact cause, and for it to self-heal) invalidated
**FR-SCAN2-2's trigger condition** and produced hard evidence for the repair mechanism. Findings:

### The trigger condition never fires
FR-SCAN2-2 keys the repair off `episodeNumber != null && jellyfinId == null`. **Every affected episode
actually has a `jellyfinId`.** Verified against the live DB for all seven known-broken episodes
(Mesterholdet S10E07 `1ba13ecc…`, Tabu S04E06, Y Talent S19E21/E22, Mission Z S2026E01/E02, Jo færre jo
bedre S09E02, Góða ferð Føroyar S01E01 — all non-null). The defect is **not** "jellystructure couldn't
resolve a Jellyfin id"; it is "**Jellyfin's own item for this episode has no `IndexNumber`**", which
jellystructure was never looking at. As written, Phase 153 would have repaired nothing.

### What actually breaks, and why it never self-heals
- Jellyfin's episode item carries the right `ParentIndexNumber` (season, from the folder) but a null
  `IndexNumber` (episode, from the filename). Jellyfin assigns `IndexNumber` **at library-scan resolve
  time**, and **never retries a resolve that failed** — nothing in Jellyfin or jellystructure ever
  revisited it, which is exactly why it is "still happening" weeks later.
- It is **not** the file: `ffprobe` reads S10E07 cleanly (valid Matroska, 59:17, H264/AAC/subrip, decodes
  without error). It is **not** the filename pattern: `Mesterholdet.S10E01…` and `…S10E08…` are
  byte-identical in shape and both resolved fine. It is **not** TMDB: E08 has `ProviderIds: {}` (no TMDB
  match at all) yet still got `IndexNumber: 8`. The resolve failure is intermittent and Jellyfin-internal;
  the actionable fact is that it is **permanent once it happens**.
- **Scale: 53 episodes library-wide have a null `IndexNumber`** (of 7197). ~29 are Live TV `.ts`
  recordings under `/config/data/livetv/recordings/`, which are legitimately unnumbered and must be left
  alone. The remaining ~24 are real library files, most with cleanly parseable `SxxEyy` names — i.e. this
  is a recurring systemic failure, not a one-off.

### The repair mechanism is proven
Ran end-to-end live on S10E07: wrote an `episodedetails.nfo` carrying `<season>10</season>` +
`<episode>7</episode>`, then `POST /Items/{id}/Refresh?metadataRefreshMode=FullRefresh`. Result —
`IndexNumber: 7`, and Jellyfin additionally self-matched `ProviderIds: {Tmdb: 7353526}`. `GET
/Shows/NextUp?seriesId=…` went from `Items: []` to returning S10E07. So NFO+refresh is sufficient and is
the right mechanism; only the targeting was wrong.

### Corrected requirements (supersede FR-SCAN2-2 where they conflict)

**FR-SCAN2-5 — Detect from Jellyfin's own missing `IndexNumber`.** `Episode` gains
`jellyfinIndexMissing: Boolean = false` (additive, JSON blob, no migration), set during
`Scanner.scanSeries`/`syncSeriesEpisodes` from the already-fetched `jfEpsMeta` entry for this file:
true when the matched `JellyfinEpisodeItem.indexNumber == null` while jellystructure itself parsed a real
episode number from the filename. Phase 152's path fallback is what makes this matchable at all — an
unnumbered Jellyfin item keys into `jfBySeasonEp` under `(season, 0)` and can only be found by path.

**FR-SCAN2-6 — Repair on that signal.** The `write_nfo` repair pass fires for
`episodeNumber != null && (jellyfinId == null || jellyfinIndexMissing)`, superseding FR-SCAN2-2's
condition. Unchanged: it writes the whole file's episode group, and bumps `nfoWrittenAt`.

**FR-SCAN2-7 — Refresh the episode item itself, not just the series.** `sync_jellyfin` additionally calls
`refreshItem(episode.jellyfinId, full = true)` for each flagged episode — the exact call proven above. A
series-level refresh is not assumed to cascade to a child episode's numbering.

**FR-SCAN2-8 — Self-limiting, self-clearing.** The flag is re-derived from Jellyfin every `scan_files`
run, so a successful repair clears it on the next cycle and the work stops. An episode jellystructure
itself can't number (`episodeNumber == null` — e.g. the date-based `Jimmy.Fallon.2026.03.05.…` files) is
never flagged, so it can't loop. Live TV `.ts` recordings are never touched: they aren't part of a scanned
library item's episode list at all.

## Dev-review addendum (2026-07-30 — implementation notes)

1. **FR-SCAN2-2's repair write lives in an `else if` alongside the existing `includeEpisodes` branch** in
   `PipelineStepOps.writeNfo` — the two are mutually exclusive on purpose: a caller that already asked for
   every episode's NFO (event-driven ingest, manual push) doesn't need the narrower repair pass on top of
   that in the same call.
2. **The repair pass re-reads `store.get(current.id)` before bumping `nfoWrittenAt`**, not `current` itself
   — the series-level branch above it may have already called `store.updateOne` in the same invocation
   (the `WRITTEN` case), and re-fetching avoids stomping that write with a stale in-memory copy.
3. Verified via `compileKotlinLinuxX64`, `linuxX64Test` (full suite passes — FR-SCAN2-1's carry-forward
   didn't break any existing artwork-lock/timestamp/duplicate-id test), `compileKotlinWasmJs` (admin, no
   DTO shape changed). Not verified against a live scheduled run this session (would need to wait for/
   trigger the operator's actual scan cycle) — the closed loop (write → bump → sync_jellyfin → refresh →
   Jellyfin re-reads) is code-verified end-to-end but not yet observed live for Mesterholdet specifically.

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
