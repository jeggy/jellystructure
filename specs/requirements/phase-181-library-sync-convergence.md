# Phase 181 — jellystructure must converge on Jellyfin's library, not predict it

> Requested 2026-08-30: *"can you check why jellystructure has still not gotten the newest episode from
> fjollerne? it is available in jellyfin and been there for many hours"* → *"no need to fix. we want to come
> up with a proper solution to this and spec it out properly… remember this is not a one time example,
> this is something that happens alot."*

**Status:** ✓ Built 2026-09-02 (not yet dev-reviewed, not yet live-verified against production traffic —
unit-tested and compiled clean). FR-181-2 was already built/test-verified 2026-08-31. FR-181-1, FR-181-1a,
FR-181-4 and FR-181-5 are now built too. **FR-181-3 (per-series count reconciliation) was removed
2026-09-13** — it was never built, and FR-181-1's id-level sweep already runs at the same cadence and
strictly subsumes what a count check would catch (see FR-181-1's build note and §6 Q2 for why).

**Build summary:** `sweepJellyfinLibrary()`/`computeLibraryDiff()` (new `media/LibrarySweep.kt`) run from
inside every `RunTarget.Library` pipeline run (`PipelineEngine.kt`, right where `computeFreshnessFilter`
already sat), feeding missing ids through the existing `RealtimeIngestService.enqueue()` path and stale
top-level ids into the item's own History tab. `JellyfinLibraryListener` is deleted outright (open
question 1, resolved — see below). A new `dirty_item` table (migration 36) plus `DirtyItemStore`
implements FR-181-5: `enqueue()` now marks an id dirty when both attempts fail, clears it on success, and
every Library run retries whatever the dirty-set is still holding. `RealtimeIngestService.enqueue()` is
deliberately **no longer gated on `[ingest] realtime`** — that setting only ever meant "react within
seconds" for the webhook path (which now checks it itself in `handleJellyfinWebhook`); the sweep and the
dirty-set retry are the correctness backstop and must keep working when an admin turns "instant" ingest
off. `IngestStatus`'s `listener_connected`/`last_event_at` fields are replaced with
`last_successful_ingest_at`/`outstanding_retry_count`, surfaced on both the `/api/health` check and the
Settings ▸ Download tools ▸ Realtime ingest card (`src/wasmJsMain/.../Settings.kt`). 8 new unit tests in
`LibrarySweepTest.kt` cover the Fjollerne case directly (a missing episode resolves to its series id, not the
episode id) and the same-size-swap case a per-series count comparison couldn't catch (id diff can).
`verifyCommonMainJellystructureDbMigration` and the full `linuxX64Test` suite are green.

**Update 2026-09-13:** FR-181-3 removed from this spec outright (was never built, and there is no plan to
build it — see the note above). Text throughout this document that referenced FR-181-3 as a deferred/future
mechanism has been trimmed accordingly.

## 1. The reported case

Fjollerne S11E07 was imported by Sonarr and visible in Jellyfin at **2026-08-30 04:09:01Z**. Fifteen hours
later jellystructure still had 100 episodes; Jellyfin had 101. Nothing was broken in the sense of
throwing an error — every subsystem behaved exactly as written. That is the problem this phase addresses.

Three independent mechanisms should each have caught this. All three failed, for three different
structural reasons, and **none of them could report that they had failed.**

## 2. What's there now — the three failures, with live evidence

All findings below were verified live against the running deployment (Jellyfin **10.11.11**) on
2026-08-30, not read from source.

### 2.1 The freshness filter buckets by premiere year, so ongoing shows are treated as archive

`FreshnessFilter.kt:26-34` picks a recheck cadence from the *release year*:

```kotlin
releaseYear >= currentYear      -> refreshThisYear   // daily
(currentYear - releaseYear) <= 5 -> refresh1To5y      // weekly
else                             -> refreshOlder      // monthly
```

Fjollerne premiered in **2005**, so it lands in `refresh_older` = **monthly**. Its row was last checked
2026-08-28, making it not due again until roughly 2026-09-28 — for a show airing a new episode weekly.
Tonight's scan log states it plainly:

```
Scan: 483 item(s) not due for a recheck yet — skipped by the freshness filter
```

This is not specific to Fjollerne. jellystructure **already stores, from Sonarr**, that the show is airing:

```
sonarrNextAiringDate = 2026-09-06   sonarrNextAiringSeason = 11   sonarrNextAiringEpisode = 8
```

It knows the next episode airs next week and still files the title as a 20-year-old archive item.
Across the library, **9 of the 16 series with a known future air date are in the slow bucket** — 56% of
provably-airing shows are systematically starved. That is the "happens a lot".

A second, subtler defect: for a series the freshness unit is wrong. `last_checked` on the *series* row
says nothing about whether an *episode* arrived. Even a correct cadence on the series row cannot express
"the parent is unchanged but gained a child".

### 2.2 Nothing ever verifies jellystructure's view against Jellyfin's

jellystructure holds `media.episode_count = 100`. Jellyfin returns 101 episodes for the same series. The
discrepancy is a single integer available on both sides, and **no code path compares them.** The system
can predict what it thinks needs looking at; it has no way to discover that its prediction was wrong.
Consequently a miss is silent and permanent until a human notices a missing episode.

### 2.3 Realtime ingest has never worked via Jellyfin, and its failure is invisible

`media_history` records **18** `realtime_ingest` events, ever. Every one of them predates
**2026-08-14**. Zero since:

```
BEFORE 2026-08-14 | 18
AFTER  2026-08-14 | 0
```

2026-08-14 is when Phase 165 (`b77d0aad`, *"Jellyfin Webhook plugin drives realtime ingest, not
Radarr/Sonarr"*) landed. Those 18 ingests were **all** the *arr direct-ingest path that Phase 165
deliberately removed. The Jellyfin-based path it was replaced with has delivered **nothing, ever**.

Both halves of that replacement are non-functional in this deployment:

- **Jellyfin Webhook plugin** — already documented as silently never delivering here despite correct
  configuration, network path and restart; root cause never pinned down inside the plugin binary
  (Phase 165's own 2026-08-14 amendment, and the `/settings/ingest/test-live` probe built to detect it).
- **`JellyfinLibraryListener` (the WS fallback) — cannot work as written, on any Jellyfin 10.11.x.**
  Proven live with three WebSocket probes:
  1. Jellyfin's socket delivers **only message types the client explicitly subscribes to**. Connecting
     and sending nothing yields `ForceKeepAlive` and nothing else; sending `SessionsStart` immediately
     starts a `Sessions` stream. `JellyfinLibraryListener.kt:83-88` sends **only** periodic `KeepAlive`
     and never subscribes to anything, so it can only ever receive `ForceKeepAlive`.
  2. `LibraryChangedStart` is **not a recognized subscription** — sending it makes Jellyfin close the
     socket (close code 1000).
  3. Behaving exactly like the repo's listener (valid `KeepAlive` only) across a **real** full metadata
     refresh of the Fjollerne series produced, over 60 seconds: `ForceKeepAlive` ×1, `KeepAlive` ×1, and
     **no `LibraryChanged`**.

  `parseItemsAdded` (`JellyfinLibraryListener.kt:133-141`) filters for `MessageType == "LibraryChanged"`,
  a frame this server never sends. The listener is dead code that reports itself as healthy — the
  Settings card's `listener_connected` is `true`, and the log line *"Jellyfin library listener connected"*
  appears regularly, because the socket genuinely is open. It just carries nothing.

The fallback was silently dead for 23 days (and structurally dead since it was written) and nothing
surfaced it, because "connected" was mistaken for "working".

### 2.4 `DateCreated` is the file's mtime — so no timestamp-ordered sweep can be trusted

This was found while pressure-testing an earlier draft of FR-181-1, which proposed a
`SortBy=DateCreated` watermark. **That design was invalid and has been replaced.** The measurements:

- Jellyfin's `DateCreated` **is the file's mtime**, matched to the nanosecond:
  `Fjollerne S11E07` → file mtime `2026-08-30 04:09:01.982425580 UTC`, Jellyfin
  `DateCreated 2026-08-30T04:09:01.9824255Z`.
- Scene releases routinely carry junk mtimes. Across the two libraries, **3 947 of 7 947 files (50%)
  have an mtime more than 7 days older than their ctime**, some by more than 26 years.
- Confirmed end-to-end against Jellyfin for a currently-airing show. Every one of these is a **2026**
  Simpsons episode added on 2026-07-08:

  ```
  DateCreated 2000-11-03  The.Simpsons.S37E09.1080p.WEB.h264-EDITH.mkv
  DateCreated 2003-04-14  The.Simpsons.S37E10.1080p.WEB.h264-EDITH.mkv
  DateCreated 2005-08-17  The.Simpsons.S37E07.1080p.WEB.h264-EDITH.mkv
  DateCreated 2005-08-26  The.Simpsons.S37E08.1080p.WEB.h264-EDITH.mkv
  DateCreated 2012-08-05  The.Simpsons.S37E06.1080p.WEB.h264-EDITH.mkv
  DateCreated 2017-03-07  The.Simpsons.S37E12.1080p.WEB.h264-EDITH.mkv
  ```

  **7 of the 15 EDITH-release episodes in that season would sort behind any watermark** — among items
  from 2000–2017, thousands of positions back — and would therefore be invisible to a
  `DateCreated`-ordered sweep, permanently.

The tell is the precision: `.0000000Z` (whole-second) is a preserved junk mtime; sub-second precision is
a real copy time. Both shapes are present throughout the library, so a timestamp sweep would appear to
work for some releases (Fjollerne's STROMPEBUKSER release sorted correctly, second in the list) while
silently failing for others — the worst available failure mode.

**Consequence for the design:** the only sound basis for convergence is a comparison that does not
involve timestamps at all. FR-181-1 is therefore a **set difference on ids** — the mechanism this phase
now converges on entirely.

## 3. Design principle

> **Correctness must come from converging on Jellyfin's actual state, not from predicting which items
> deserve attention. Event delivery is a latency optimization and must never be the only thing standing
> between a new file and the catalog.**

Today the model is inverted: the scheduled scan *predicts* (year-based heuristics, recomputed statelessly
every run), and correctness *depends* on best-effort events that carry no delivery guarantee. Both halves
of that need to swap roles.

## 4. Functional requirements

### FR-181-1 — Set-difference sweep: "what does Jellyfin have that I don't" (the backstop) — ✅ Built 2026-09-02

Every scan cycle, before the freshness filter runs, enumerate Jellyfin's item ids and diff them against
the set of Jellyfin ids jellystructure already holds:

```
GET /Items?Recursive=true&IncludeItemTypes=Episode,Movie&EnableImages=false&EnableUserData=false&Limit=<all>
```

Anything Jellyfin has that we do not is fed into the existing ingest path (for an `Episode`, that
resolves to its parent series exactly as `RealtimeIngestService` already does).

**This must be a set difference on ids, not a timestamp watermark** — see §2.4 for the measured reason.
Measured cost of the full enumeration on this deployment: **2.0 s, 5.4 MB, 7 946 items**, once per cycle.
That is O(library) rather than O(new), but it is affordable at this scale and, crucially, it is
*immune to timestamp semantics entirely*. Correctness beats cleverness here: a cheaper sweep that
silently skips half the library is worth nothing.

An item is only removed from the "needs work" set once it has been successfully processed (FR-181-5), so
a failure retries on the next cycle rather than being stepped over.

> ⚠ **Implementation traps, all verified live — three separate Jellyfin behaviours that make the
> "obvious" implementations silently wrong:**
> - `MinDateCreated` and `MinDateLastSaved` are **silently ignored**: they return the entire unfiltered
>   library (7 946 / 8 123 items) in name order, with no error. Code written against them appears to
>   work while filtering nothing.
> - `DateLastMediaAdded`, `ChildCount`, `RecursiveItemCount` and `DateLastSaved` all return `null` on a
>   series item even when requested via `Fields=`.
> - **`DateCreated` is the file's mtime, not Jellyfin's ingestion time** — §2.4. Any design that orders
>   or filters by it is unsound on this library.

**Shipped as designed**, with one deliberate deviation from the literal query in this section:
`IncludeItemTypes=Movie,Series,Episode` (not just `Episode,Movie`) — a `Series` with zero episodes so far
needs the same set-difference treatment a `Movie` gets, and FR-181-1a's reverse diff needs the full
Movie/Series set on the Jellyfin side to compare against anyway, so one enumeration serves both FRs. Live
size check against this deployment 2026-09-02: 8 135 items, 6.7 MB, ~0.5 s — Jellyfin returned the whole
library **unpaged** despite no `Limit` being sent (`getAllLibraryItemIds` still pages defensively to
`TotalRecordCount` rather than trusting that forever — R219 already paid for the "a bare Limit is a trap"
lesson once).

### FR-181-1a — Deletion/replacement detection (the other half of the diff) — ✅ Built 2026-09-02

The same enumeration yields the reverse difference for free: ids jellystructure holds that Jellyfin no
longer has. Under Phase 95's non-destructive invariant this must **not** auto-delete; it marks the item
for review and surfaces it, so a replaced or re-imported file is reconciled rather than leaving a stale
row that silently disagrees with Jellyfin forever.

**Shipped** as a `mediaHistory.record(item.id, "jellyfin_missing", …)` entry on the item's own existing
History tab — the same surfacing precedent Phase 170 set for segment-detection anomalies — rather than a
new admin page or badge, which this phase never asked for. Movie/Series only, not per-episode (an
episode-level reverse diff would fire on every ordinary removed/re-imported episode inside a show
jellystructure otherwise still holds correctly — noise, not signal).

### FR-181-2 — Activity-based freshness, replacing premiere-year bucketing — ✅ Built 2026-08-31

The cadence tier must be chosen from **whether the title is active**, not when it premiered. Three hot
signals were named in the original draft:

- it has a `sonarrNextAiringDate` in the future (already stored — see §2.1) — **implemented**;
- it gained an episode within the last N days — **not implemented**, see below.

**Shipped:** `isDueForRecheck` (`FreshnessFilter.kt`) gained an `isActivelyAiring: Boolean = false`
parameter, `true || releaseYear >= currentYear -> refreshThisYear` — the override sits ahead of the
premiere-year check so an active title always takes the fast tier regardless of age. `false` is a real
default, not a compat shim: a title with no Sonarr signal (movies; series Sonarr doesn't cover) correctly
falls through to the unchanged age-tiered behavior. `computeFreshnessFilter` computes it from
`item.sonarrNextAiringDate >= today` — **ISO date strings compare correctly lexicographically, so no date
parsing is needed** for the comparison itself; `today` still needs deriving from `store.nowMs()`, done via
a new file-private `dateStringFromEpochMs`, the same civil-calendar algorithm already duplicated in
`SonarrEnrichService.todayUtcDateString`/`UpcomingService`, parameterized on the injected epoch instead of
the wall clock so it stays testable (verified against Python's UTC-aware `datetime` across leap-day,
year-boundary and epoch-zero cases before compiling — the wall-clock version would have been correct too,
but not unit-testable). `isDueForRecheck`'s existing pure-function shape and all five pre-existing
`FreshnessFilterTest` cases are unchanged (all 7 tests pass — 5 original + 2 new). No config migration:
the existing `[[scan.pipeline]]` cadence keys are untouched, only tier *selection* changed.

**Verified against real data** (`config/jellystructure.db`, 2026-08-31): all **9 of the 9** series that
were stuck in `refresh_older` purely from premiere year despite a stored future `sonarrNextAiringDate`
are now correctly bucketed `refresh_this_year` — Fjollerne (2005), The Simpsons (1989), North Ridge (1997),
It's Always Rainy in Pittsburgh (2005), Blå Blink (2009), Chore Captain (2015), Kulsort (2019),
Beliggenhed beliggenhed beliggenhed (2014), Mark og mage (2015).

**Not implemented — "gained an episode within the last N days":** no reliable per-series signal for this
exists today. `updatedAt` bumps on *any* stored-content change (title edits, artwork, tags — Phase 108),
far more often than genuine episode additions, so it would flood most of the library into "hot"
permanently rather than narrowly targeting recent growth. This signal is better served by FR-181-1's
ingest events (a real "new episode arrived" marker) than by inventing a second, noisier proxy here — left
for when FR-181-1 lands rather than worked around now.

### FR-181-4 — Realtime path: fix, or fail loudly — ✅ Built 2026-09-02

1. **Fix `JellyfinLibraryListener` or remove it.** As written it cannot function on Jellyfin 10.11.x
   (§2.3). Investigate whether 10.11.11 exposes any subscribable library-change listener at all; if it
   does not, the listener must be **deleted rather than left running**, because its false
   `listener_connected = true` actively conceals the gap. Do not leave a permanently-silent socket
   presenting itself as a healthy fallback.
2. **Health signal.** Surface "last successful realtime ingest" alongside the existing
   `last_webhook_received_at`, and warn when a deployment that has realtime enabled has ingested nothing
   for an abnormal interval. A 23-day silence must be visible without a human noticing a missing episode.
3. **Demote realtime to an optimization.** With FR-181-1 as the guarantee, event delivery only reduces
   latency. Nothing about catalog correctness may depend on it.

**Shipped.** Point 1 — **deleted**, corroborated independently 2026-09-02 rather than taken only on the
2026-08-30 investigation's word: a live probe against this same Jellyfin (`/socket?api_key=…`, valid
`KeepAlive` only) reproduced `LibraryChangedStart` → immediate close code 1000, matching §2.3 exactly.
(A further live trigger — forcing a real library refresh to re-confirm zero `LibraryChanged` frames over
time — was not run this session; a full `/Library/Refresh` against the production Jellyfin was correctly
blocked by this environment's own safety classifier as too disruptive to attempt casually. The 2026-08-30
investigation already did that exact test once, live, and is not re-litigated here.) Point 2 —
`RealtimeIngestService.lastSuccessfulIngestAt`, set on every path that completes ingest successfully
(webhook, sweep, dirty-set retry alike, since all three funnel through the same `enqueue()`), replacing
the deleted listener's `listener_connected`/`last_event_at` on both `/api/health` and the Settings ingest
card — see FR-181-1's summary. Point 3 — `enqueue()` is no longer gated on `[ingest] realtime` at all; see
the phase's top-level build summary for why, and why the webhook route gates itself instead.

### FR-181-5 — Persistent dirty-set instead of stateless recompute — ✅ Built 2026-09-02 (narrower than drafted)

Today the worklist is recomputed from heuristics on every run, so an item that *should* have been
processed but was not is simply forgotten — there is no record that work is outstanding. Introduce a
persistent "needs work" set, written by every signal (set-difference sweep, count reconciliation, webhook, manual
action, and a failed pipeline step), cleared **only on success**.

This is the architectural correction behind the other FRs: it gives the system memory of outstanding
work, so a failure retries instead of vanishing. It also subsumes the retry logic currently hand-rolled
in `RealtimeIngestService.enqueue` (`delay(60_000)` then one retry, then give up with a log line).

**Shipped, scoped to what the other FRs actually need** rather than the fully general "written by every
signal" system drafted above — a new `dirty_item` table (`jellyfin_id` UNIQUE, `reason`, `created_at`;
migration 36) behind a small `DirtyItemStore` (`markDirty`/`clear`/`all`/`count`). `enqueue()`'s existing
retry-once-then-log ending now marks the id dirty on the second failure instead of only logging; every
`RunTarget.Library` run reads `dirtyItemStore.all()` and retries each one through the same `enqueue()`
path; a success from *any* trigger (retry, sweep, or an unrelated webhook reaching the same id first)
clears it. **Not built**, because FR-181-1's own sweep already makes it unnecessary: "set-difference
sweep" as a *source* of dirtiness, and "manual action"/"a failed pipeline step" as general write points beyond
`RealtimeIngestService`'s own retry exhaustion. A missing item is never silently forgotten even without
those extra write points, because FR-181-1 re-derives "what's missing" from Jellyfin's truth on every
cycle rather than depending on something having remembered to mark it dirty in the first place — the
dirty-set's real job under this design is narrower: remember an item that *is* known and was *attempted*
but failed for a reason a retry might fix (a TMDB hiccup, a transient Jellyfin timeout), which is exactly
what `enqueue()`'s existing double-failure path already identifies.

Sequencing note: FR-181-1 and FR-181-2 deliver the user-visible fix and can land first; FR-181-5 is the
larger change and may follow, provided the earlier FRs are written to record dirtiness through this
interface rather than around it.

## 5. Non-goals

- **Reinstating *arr webhooks as an ingest trigger.** Excluded, and the reasoning is sound: an *arr
  `Download` event fires when **Sonarr** finishes importing, which is *before* Jellyfin has scanned and
  identified the file. Acting on it means racing Jellyfin — which is exactly why the pre-165 path
  carried a **5-minute settle-time poll**, and why Phase 165's own rationale prefers the plugin's
  `ItemAdded` ("fires only once Jellyfin has actually identified the item… needs neither a path→id
  mapping nor a settle-time poll"). A signal that arrives before the data it refers to is not a usable
  ingest trigger.

  Timing measured for the reported case: Sonarr's webhook hit jellystructure at **04:09:02Z**; the file's
  own mtime was 04:09:01.98Z. The two are ~1 s apart *for this release*, but that says nothing about when
  **Jellyfin** identified it, which is the event that actually matters and which nothing here observes.

  Residual value, for dev review only: as a **dirty-marking hint** (FR-181-5) rather than an ingest
  trigger, it needs no path mapping and no settle poll — mark the series dirty, let FR-181-1's diff
  ingest it whenever Jellyfin is actually ready. That preserves the low-latency signal without the race.
  Worth considering only if FR-181-4 cannot revive a Jellyfin-side event path.
- Changing Jellyfin's own scanning, monitoring or plugin configuration.
- Reworking the pipeline steps themselves (`scan_files`, `pull_tmdb`, …) — this phase changes *which
  items reach them* and *how that set is decided*, not what they do.
- Removal detection remains scan-only (Phase 95's non-destructive invariant is unchanged); FR-181-1a
  surfaces a disappearance for review, it does not delete.

## 6. Open questions for dev review

1. ~~Does Jellyfin 10.11.11 expose **any** subscribable library-change WS listener?~~ **Answered: no.**
   The 2026-08-30 investigation's three live probes already concluded this; a fourth, independent probe
   run 2026-09-02 during the build reproduced the same `LibraryChangedStart` → close-code-1000 result
   against the same live server. `JellyfinLibraryListener` is deleted (FR-181-4).
2. **Sweep cadence — resolved by how it was built, not by picking a separate cadence.** The full
   enumeration measured **0.5 s / 6.7 MB / 8 135 items** live 2026-09-02 (this library has grown since the
   2 026-08-30 measurement) — cheap enough that FR-181-1 runs it on **every** `RunTarget.Library` trigger
   uniformly (scheduled scan, manual click, `SCAN_ON_START`) rather than giving it its own, separately-
   tuned schedule. Revisit only if a much larger library makes 0.5s/7MB non-trivial.
3. Can the enumeration payload be slimmed? `EnableImages=false&EnableUserData=false` still returned
   `ImageBlurHashes` and a dozen other fields per item. **Not pursued** — at 6.7 MB / 0.5 s this is not a
   real cost on this deployment, and Q2's resolution removed the reason (a faster cadence) that would have
   made slimming worth chasing.
4. Should the diff be global, or per-library? Per-library is more robust if one library's scanning
   stalls, at the cost of more state. **Left as designed (global)** — the sweep as built enumerates the
   whole configured scope in one call and filters by library path prefix client-side afterward
   (`sweepJellyfinLibrary`'s `inScope`), the same pattern `JellyfinLibraryListener.flush` used to use. A
   genuinely per-library sweep (one Jellyfin call per library, independent failure isolation) is a real
   change, not a build-time judgment call, and is left for dev review to decide is worth the added state.
