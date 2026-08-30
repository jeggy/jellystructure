# Phase 181 — jellystructure must converge on Jellyfin's library, not predict it

> Requested 2026-08-30: *"can you check why jellystructure has still not gotten the newest episode from
> fjollerne? it is available in jellyfin and been there for many hours"* → *"no need to fix. we want to come
> up with a proper solution to this and spec it out properly… remember this is not a one time example,
> this is something that happens alot."*

**Status:** Planned — design-authored 2026-08-30, not yet dev-reviewed.

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

## 3. Design principle

> **Correctness must come from converging on Jellyfin's actual state, not from predicting which items
> deserve attention. Event delivery is a latency optimization and must never be the only thing standing
> between a new file and the catalog.**

Today the model is inverted: the scheduled scan *predicts* (year-based heuristics, recomputed statelessly
every run), and correctness *depends* on best-effort events that carry no delivery guarantee. Both halves
of that need to swap roles.

## 4. Functional requirements

### FR-181-1 — Watermark sweep: "what is new since I last looked" (the backstop)

Every scan cycle, before the freshness filter runs, query Jellyfin newest-first and walk down until
reaching items older than a persisted watermark:

```
GET /Items?Recursive=true&IncludeItemTypes=Episode,Movie&SortBy=DateCreated&SortOrder=Descending
          &Limit=<page>&Fields=DateCreated,Path
```

Verified live: this returns the whole library newest-first, and **Fjollerne S11E07 is the second result**.
Anything newer than the watermark that jellystructure does not already hold is fed into the existing
ingest path (for an `Episode`, that resolves to its parent series exactly as `RealtimeIngestService`
already does). Cost is O(new items), not O(library) — one paged call in the common case.

The watermark advances **only after** the items ahead of it have been successfully processed. A failure
leaves it where it is, so the next cycle retries rather than stepping over the gap. This is what makes
accumulated drift structurally impossible rather than merely unlikely.

> ⚠ **Implementation trap, verified live — do not use server-side date filters.** Both
> `MinDateCreated` and `MinDateLastSaved` are **silently ignored** by this Jellyfin: they return the
> entire unfiltered library (7 946 and 8 123 items respectively) in name order, with no error. Code
> written against them would appear to work while filtering nothing. The sweep must be
> sort + limit + walk-until-older-than-watermark. Likewise `DateLastMediaAdded`, `ChildCount`,
> `RecursiveItemCount` and `DateLastSaved` all came back `null` on a series item here and must not be
> relied on.

### FR-181-2 — Activity-based freshness, replacing premiere-year bucketing

The cadence tier must be chosen from **whether the title is active**, not when it premiered. A title is
*hot* when any of the following hold:

- it has a `sonarrNextAiringDate` in the future (already stored — see §2.1);
- it gained an episode within the last N days;
- Jellyfin reports a child count differing from ours (FR-181-3).

Hot titles take the `refresh_this_year` cadence regardless of premiere year; genuinely dormant titles
keep the existing age tiers. Fjollerne — premiered 2005, next episode 2026-09-06 — must land in the fast
bucket. The existing `[[scan.pipeline]]` cadence keys stay as they are; only tier *selection* changes,
so no config migration is required.

`isDueForRecheck`'s existing pure-function shape (and `FreshnessFilterTest`) should be preserved — this
is a change of inputs, not of structure.

### FR-181-3 — Episode-count drift detector

Compare stored `media.episode_count` against Jellyfin's episode count per series, in bulk. A mismatch
marks the series dirty (FR-181-5) and forces a rescan irrespective of cadence. This is cheap, and unlike
FR-181-1 it also catches **deletions, replacements and re-imports** — cases where nothing is "new" but
the two sides still disagree. It is the first mechanism in the system that can answer *"am I in sync?"*

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
persistent "needs work" set, written by every signal (watermark sweep, drift detector, webhook, manual
action, and a failed pipeline step), cleared **only on success**.

This is the architectural correction behind the other FRs: it gives the system memory of outstanding
work, so a failure retries instead of vanishing. It also subsumes the retry logic currently hand-rolled
in `RealtimeIngestService.enqueue` (`delay(60_000)` then one retry, then give up with a log line).

Sequencing note: FR-181-1 and FR-181-2 deliver the user-visible fix and can land first; FR-181-5 is the
larger change and may follow, provided the earlier FRs are written to record dirtiness through this
interface rather than around it.

## 5. Non-goals

- **Reinstating *arr webhooks as an ingest trigger.** Excluded by explicit instruction this session.
  ⚠ Flagged for the dev review, because the evidence gathered *after* that decision bears on it: the
  Sonarr webhook fired correctly at **04:09:02Z**, one second after the import, and is currently the
  **only realtime signal in this deployment that has ever actually delivered anything** (§2.3). Phase 165
  downgraded it to a nudge because its *path-mapping* was fragile — but a dirty-marking hint (FR-181-5)
  needs no path mapping at all. Reconsider only if FR-181-4's investigation cannot revive a Jellyfin-side
  path.
- Changing Jellyfin's own scanning, monitoring or plugin configuration.
- Reworking the pipeline steps themselves (`scan_files`, `pull_tmdb`, …) — this phase changes *which
  items reach them* and *how that set is decided*, not what they do.
- Removal detection remains scan-only (Phase 95's non-destructive invariant is unchanged); FR-181-3
  detects a count mismatch and rescans, it does not delete.

## 6. Open questions for dev review

1. Does Jellyfin 10.11.11 expose **any** subscribable library-change WS listener? If not, FR-181-4.1
   becomes a deletion and the webhook plugin is the only Jellyfin-side event path — which is itself
   known-broken here, which in turn strengthens the non-goal caveat above.
2. Sweep cadence: reuse the hourly `scan_schedule`, or run the sweep on its own faster timer? It is
   cheap enough (one paged request) to run every few minutes, which would close most of the latency gap
   that realtime ingest was supposed to cover.
3. FR-181-3 needs one bulk call for per-series episode counts on a server where `ChildCount` /
   `RecursiveItemCount` returned `null` — confirm the working shape live before building.
4. Should the watermark be global, or per-library? Per-library is more robust if one library's scanning
   stalls, at the cost of more state.
