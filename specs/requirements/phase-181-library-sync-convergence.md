# Phase 181 — jellystructure must converge on Jellyfin's library, not predict it

> Requested 2026-08-30: *"can you check why jellystructure has still not gotten the newest episode from
> klovn? it is available in jellyfin and been there for many hours"* → *"no need to fix. we want to come
> up with a proper solution to this and spec it out properly… remember this is not a one time example,
> this is something that happens alot."*

**Status:** Partially implemented. **FR-181-2 built and test-verified 2026-08-31.** FR-181-1, FR-181-1a,
FR-181-3, FR-181-4, FR-181-5 remain Planned — and a second live case (§2.5, 2026-08-31) confirms the gap
FR-181-1 exists to close is still open in production: a new episode inside an already-known, correctly
"hot"-bucketed series is *still* invisible for up to a day. Not yet dev-reviewed.

## 1. The reported case

Klovn S11E07 was imported by Sonarr and visible in Jellyfin at **2026-08-30 04:09:01Z**. Fifteen hours
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

Klovn premiered in **2005**, so it lands in `refresh_older` = **monthly**. Its row was last checked
2026-08-28, making it not due again until roughly 2026-09-28 — for a show airing a new episode weekly.
Tonight's scan log states it plainly:

```
Scan: 483 item(s) not due for a recheck yet — skipped by the freshness filter
```

This is not specific to Klovn. jellystructure **already stores, from Sonarr**, that the show is airing:

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
     refresh of the Klovn series produced, over 60 seconds: `ForceKeepAlive` ×1, `KeepAlive` ×1, and
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
  `Klovn S11E07` → file mtime `2026-08-30 04:09:01.982425580 UTC`, Jellyfin
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
work for some releases (Klovn's STROMPEBUKSER release sorted correctly, second in the list) while
silently failing for others — the worst available failure mode.

**Consequence for the design:** the only sound basis for convergence is a comparison that does not
involve timestamps at all. FR-181-1 is therefore a **set difference on ids**, and FR-181-3's drift
detection is promoted from safety-net to a first-class part of the same mechanism.

### 2.5 — confirmed live again 2026-08-31 (Lanterns S01E03): the series-level skip blocks *local file
discovery*, not just the expensive steps

Reported as *"i cant see lanterns in my newly added section"*. Investigation found the show itself was a
red herring — Lanterns S01E01/E02 were already scanned and the series correctly ranked #15/30 in the live
`newly-all-series` row, so "not visible in Newly Added" was really "hasn't scrolled that far". The real
finding is what happened to **S01E03**, which had already finished downloading (size-stable, confirmed via
`lsof`) and which **Jellyfin had already noticed** — its trickplay folder existed on disk, proof Jellyfin
itself had scanned and identified the file — while jellystructure's `media` row for Lanterns still held
only 2 episodes.

This is not a Jellyfin-lag case like Klovn (§2.1) was — Jellyfin was not the bottleneck here.
FR-181-2 (✅ built) worked exactly as designed: `sonarrNextAiringDate = 2026-09-07` correctly bucketed
Lanterns into `refresh_this_year` (daily), not the old monthly archive tier. **And it still didn't help**,
because of a mechanism neither this spec nor FR-181-2's build note previously called out:

`computeFreshnessFilter`'s skip-set (`FreshnessFilter.kt:93-101`) is built by iterating
`store.allItems()` — one entry **per series**, not per episode, for a `TV_SHOW`. That skip-set is then
applied in `runScan` (`MediaRoutes.kt:2230-2232`) as a filter over `jellyfinItems`, which for a TV library
is Jellyfin's **Series**-type listing, not episodes — so a series inside its cadence window is dropped
from the worklist *before* `scanner.scanItem()`/`scanSeries()` is ever called on it. Confirmed live:
Lanterns' series row had `last_checked` ≈20 minutes before S01E03 finished downloading, so the very next
scan cycle (and the one after, up to 24h out under the daily "hot" cadence) skips the series entirely.

The part worth being explicit about: **`scanSeries` is also where the cheap, local, no-network step
lives** — walking the on-disk episode files and diffing them against what jellystructure already has is
not an expensive TMDB-shaped operation, but it never runs either, because the skip happens one level
above it, at the per-series Jellyfin-item filter. The freshness cooldown was designed to save expensive
re-enrichment work (§1's `refresh_this_year`/`refresh_1_5y`/`refresh_older` cadences all originally gate
TMDB/artwork/NFO-shaped steps), but as wired today it also gates the one step that's supposed to be the
correctness backstop for "did a new file show up".

**This reconfirms FR-181-1 is the correct fix, not a new one.** FR-181-1's enumeration is explicitly
`IncludeItemTypes=Episode,Movie` (§4) — it diffs at the *episode* level against Jellyfin, so it runs
**independently of, and prior to,** the per-series freshness skip described above; a new episode inside an
already-known series is exactly the shape of gap it closes. FR-181-1 remains fully unbuilt, so as of this
second live occurrence the gap is confirmed still open, not hypothetical.

**Confirmed workaround, no code needed:** `POST /api/media/{id}/sync` (`MediaRoutes.kt:1611`, admin
per-item Re-sync) calls `scanner.syncSeriesEpisodes` directly under `RunTarget.SingleItem`, which
`computeFreshnessFilter` explicitly bypasses (§ its own doc comment: *"target is a RunTarget.SingleItem …
'is it due for a periodic recheck' doesn't apply to it"*). A manual per-item Re-sync on the series page
always picks up new episodes regardless of cadence; the **manual "Scan library" button does not**, since
Phase 175 made it share this same cooldown.

## 3. Design principle

> **Correctness must come from converging on Jellyfin's actual state, not from predicting which items
> deserve attention. Event delivery is a latency optimization and must never be the only thing standing
> between a new file and the catalog.**

Today the model is inverted: the scheduled scan *predicts* (year-based heuristics, recomputed statelessly
every run), and correctness *depends* on best-effort events that carry no delivery guarantee. Both halves
of that need to swap roles.

## 4. Functional requirements

### FR-181-1 — Set-difference sweep: "what does Jellyfin have that I don't" (the backstop)

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

### FR-181-1a — Deletion/replacement detection (the other half of the diff)

The same enumeration yields the reverse difference for free: ids jellystructure holds that Jellyfin no
longer has. Under Phase 95's non-destructive invariant this must **not** auto-delete; it marks the item
for review and surfaces it, so a replaced or re-imported file is reconciled rather than leaving a stale
row that silently disagrees with Jellyfin forever.

### FR-181-2 — Activity-based freshness, replacing premiere-year bucketing — ✅ Built 2026-08-31

The cadence tier must be chosen from **whether the title is active**, not when it premiered. Three hot
signals were named in the original draft:

- it has a `sonarrNextAiringDate` in the future (already stored — see §2.1) — **implemented**;
- it gained an episode within the last N days — **not implemented**, see below;
- Jellyfin reports a child count differing from ours (FR-181-3) — deferred with FR-181-3 itself.

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
are now correctly bucketed `refresh_this_year` — Klovn (2005), The Simpsons (1989), South Park (1997),
It's Always Sunny in Philadelphia (2005), Politijagt (2009), Taskmaster (2015), Helt sort (2019),
Beliggenhed beliggenhed beliggenhed (2014), Landmand søger kærlighed (2015).

**Not implemented — "gained an episode within the last N days":** no reliable per-series signal for this
exists today. `updatedAt` bumps on *any* stored-content change (title edits, artwork, tags — Phase 108),
far more often than genuine episode additions, so it would flood most of the library into "hot"
permanently rather than narrowly targeting recent growth. This signal is better served by FR-181-1's
ingest events (a real "new episode arrived" marker) than by inventing a second, noisier proxy here — left
for when FR-181-1 lands rather than worked around now.

### FR-181-3 — Per-series count reconciliation (cheap continuous check)

FR-181-1's id diff is authoritative and already catches everything this would, so this is **not** the
primary detector — it is the cheap check that can run more often than a full enumeration if FR-181-1
turns out to be too heavy to run at the desired frequency (see §6 Q2).

Compare stored `media.episode_count` against Jellyfin's per-series episode count. A mismatch marks the
series dirty (FR-181-5) and forces a rescan irrespective of cadence. Klovn was `100` vs Jellyfin's `101`
at the time of the report.

Note the known weakness that stops this from replacing FR-181-1: **counts miss same-size changes** — one
episode deleted and another added nets to an identical count while the two sides genuinely disagree.
Only the id diff catches that, which is why FR-181-1 is the backstop and this is the optimisation.

### FR-181-4 — Realtime path: fix, or fail loudly

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

### FR-181-5 — Persistent dirty-set instead of stateless recompute

Today the worklist is recomputed from heuristics on every run, so an item that *should* have been
processed but was not is simply forgotten — there is no record that work is outstanding. Introduce a
persistent "needs work" set, written by every signal (set-difference sweep, count reconciliation, webhook, manual
action, and a failed pipeline step), cleared **only on success**.

This is the architectural correction behind the other FRs: it gives the system memory of outstanding
work, so a failure retries instead of vanishing. It also subsumes the retry logic currently hand-rolled
in `RealtimeIngestService.enqueue` (`delay(60_000)` then one retry, then give up with a log line).

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

1. Does Jellyfin 10.11.11 expose **any** subscribable library-change WS listener? If not, FR-181-4.1
   becomes a deletion and the webhook plugin is the only Jellyfin-side event path — which is itself
   known-broken here, which in turn strengthens the non-goal caveat above.
2. Sweep cadence. The full FR-181-1 enumeration measured **2.0 s / 5.4 MB / 7 946 items**. Hourly
   (reusing `scan_schedule`) is clearly fine. Running it every few minutes — which would close most of
   the latency gap realtime ingest was supposed to cover — means ~5 MB per run against Jellyfin; decide
   whether that is acceptable, or whether FR-181-3's lighter per-series counts should carry the fast
   cadence with the full id diff hourly.
3. Can the enumeration payload be slimmed? `EnableImages=false&EnableUserData=false` still returned
   `ImageBlurHashes` and a dozen other fields per item. If Jellyfin can be made to return ids alone the
   sweep gets materially cheaper and Q2 mostly answers itself.
4. FR-181-3 needs one bulk call for per-series episode counts on a server where `ChildCount` /
   `RecursiveItemCount` returned `null` — confirm the working shape live before building.
5. Should the diff be global, or per-library? Per-library is more robust if one library's scanning
   stalls, at the cost of more state.
