# Phase 193 — "Jellyfin hasn't re-read the NFO yet" is shown constantly, is usually false, and never clears

> Live report: *"In jellystructure I very often see this warning 'Jellyfin hasn't re-read the NFO yet.'
> on the media details page. I'm not sure if it's true? And if it is, why am I seeing it so often?"*

## Status
Planned (spec'd 2026-09-06). Not dev-reviewed. Root-caused by reading the write paths against the
production database — the answer to *"is it true?"* is **usually no**, and to *"why so often?"* is
**because the most-used button on the page causes it**.

## The two answers

### It is usually not true

`POST /api/media/{id}/nfo` — the split button's **Save → NFO** — does this
(`MediaRoutes.kt:524-553`):

```kotlin
NfoWriter.writeTracked(item, …).onSuccess { result ->
    mediaHistory.record(id, "nfo_write", result.path)
    store.updateOne(item.copy(nfoWrittenAt = result.writtenAt, nfoHash = result.hash))
    …
    // Phase 153 — plan.md documents this route as "Write NFO … trigger Jellyfin refresh";
    // the trigger was missing, so a manual write never actually got Jellyfin to re-read it
    if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
        jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
    }
    call.respond(mapOf("path" to result.path))
}
```

It stamps `nfoWrittenAt`. It **triggers the Jellyfin refresh** — Phase 153 fixed exactly that. It
never stamps `jfSyncedAt`, and it does not even look at what `refreshItem` returned.

The banner's predicate is `jfSyncedAt < nfoWrittenAt` (`DriftEvaluator.kt:36-38`). So every single
"Save → NFO" leaves the item permanently reporting *"Jellyfin hasn't re-read the NFO yet"* — about a
Jellyfin refresh that **did** run, seconds earlier, on the same request. The state is a bookkeeping
omission, not a real divergence.

That is the whole "why so often": the banner is the guaranteed outcome of the most ordinary action on
the page. `pushToJellyfin` — the path behind Save & Sync, `/sync`, `/repull-jellyfin` and realtime
ingest — gets it right (`MediaRoutes.kt:2213`), which is why some saves leave a clean page and some
don't, with nothing to distinguish them from the operator's side.

### When it *is* true, it does not "clear itself"

The copy says (`MediaDetail.kt:2852`):

> *"The NFO on disk is current, but Jellyfin hasn't re-read it yet. **This usually clears itself within
> a few seconds.**"*

Nothing clears it within a few seconds. Nothing polls. `jfSyncedAt` is stamped only by

- `PipelineStepOps.syncJellyfin` (the `sync_jellyfin` pipeline step), or
- `detectDrift(autoReassert = true)` — **off** in production config, or
- `pushToJellyfin` / the batch routes,

and `sync_jellyfin`'s candidate set is not "everything that needs syncing". It is the pipeline's
`workingSet` (`PipelineEngine.kt:356`), which is the **freshness-filtered** output of `scan_files`
(`PipelineEngine.kt:220-268`, `computeFreshnessFilter`). Production's write_nfo/sync_jellyfin steps are
configured `refresh_this_year = weekly`, `refresh_1_5y = monthly`, `refresh_older = 6months`.

So an item whose NFO was written but whose sync did not follow — a run cancelled or deferred between
the two steps (Phase 178's defer, Phase 182's cancel, Phase 188's capped scans), a `refreshItem` that
returned false, or a route that writes without syncing — waits for the next run whose freshness filter
admits it. For an older title that is up to six months.

Measured over the production database, 2026-09-06:

```
items:                       516
jfSyncedAt < nfoWrittenAt:    30  (5.8 %)
  under a minute behind:      10
  1–60 minutes:                5
  1–24 hours:                  9
  more than a day:             6   ← longest: 19.4 days (boligk-b-i-blinde-2017,
                                     comedy-central-stand-up-uden-gr-nser-2017, vejens-helte-2025)
```

**Half of the currently-affected items have been "behind" for more than an hour**, and six for over a
day, all of them being told it will sort itself out shortly.

## Problem, stated plainly

The banner conflates three different things under one message and one promise:

1. a sync that happened but wasn't recorded (the common case — a lie),
2. a sync that is genuinely a few seconds away because a pipeline run is mid-flight (the case the copy
   describes),
3. a sync that will not happen for weeks because the item is not due for a freshness recheck (the case
   the copy actively misleads about).

An operator who learns the banner is usually wrong stops reading it — and then misses case 3, which is
the one that matters.

## Goal

The banner appears when, and only when, Jellyfin really has not re-read the NFO; it says something the
operator can act on; and an item cannot sit in that state indefinitely because of a freshness window
that has nothing to do with it.

## Requirements

### FR-193-1 — Record the sync that actually happened

`POST /api/media/{id}/nfo` stamps `jfSyncedAt` when `refreshItem` returns true, and logs a warning when
it returns false, exactly as `pushToJellyfin` already does (`MediaRoutes.kt:2211-2216`). The return
value must stop being discarded.

Audit every other site that stamps `nfoWrittenAt` for the same omission — `MediaRoutes.kt:532`,
`:1628`, `:2142`, `:2186`, `PipelineStepOps.kt:124`, `:153`, `:196`. Each either triggers a refresh and
records it, or deliberately does not refresh and says so in a comment. No site may refresh without
recording.

### FR-193-2 — `sync_jellyfin` is not limited to this run's freshness window

The `sync_jellyfin` step's candidate set becomes **every item with `nfoWrittenAt > jfSyncedAt` and a
`jellyfinId`**, not `workingSet ∩ that`. The freshness filter exists to bound the *expensive* work
(probing files, calling TMDB); a Jellyfin refresh for an item we already know has an unsynced NFO is
neither expensive nor speculative — it is the completion of work already begun.

This is bounded by construction: the set can only contain items a previous run already wrote an NFO
for, and each item leaves the set the moment its refresh succeeds. Today's production figure is 30.

The step must still respect the outbound gates (Phase 182/183) — it is now potentially a larger batch
and must be paced like one, not fired as an unbounded fan-out.

### FR-193-3 — A failed refresh is visible, not silent

`syncJellyfin` returning false currently leaves no trace on the item. When a refresh genuinely fails,
record it on the item's History (`jellyfin_refresh_failed`) so an item stuck behind has an explanation
an operator can find, rather than a banner with no history behind it.

### FR-193-4 — The copy tells the truth

Replace *"This usually clears itself within a few seconds"* — a claim no mechanism supports — with what
is actually true and what the operator can do:

> **The NFO on disk is current; Jellyfin hasn't been asked to re-read it since.**
> *Written {n} ago. The next scan will sync it, or* **[Sync Jellyfin now]** *·* [why this happens]

The elapsed time is the load-bearing part: "written 4 seconds ago" and "written 19 days ago" are
different situations and the current copy renders them identically. The action is the existing
`Sync Jellyfin` split-button option — the banner should offer it inline rather than describing a wait.

### FR-193-5 — Do not show a banner for a state that is seconds old

A pipeline run that is mid-flight between `write_nfo` and `sync_jellyfin` genuinely is a transient. The
banner suppresses itself while a scan is running **and** the item is in the current run's working set —
the drift endpoint already has `ScanTracker` available. This removes case 2 from the banner entirely
rather than papering over it with copy.

## Non-goals

- No change to `DriftEvaluator`'s three-state model (Phase 115). NFO_STALE and EXTERNAL_DRIFT are
  correct and untouched; only JELLYFIN_BEHIND's production and presentation change.
- No polling or background reconciliation loop. FR-193-2 makes the existing pipeline step sufficient.
- No change to the freshness filter itself, or to any other step's scoping.
- Not turning on `auto_reassert`. That flag has its own semantics (silently rewrite + refresh) and a
  latent bug of its own — see Open questions — and this phase deliberately does not depend on it.

## Acceptance

1. On a converged item, press **Save → NFO**. Reload the page: **no banner**. `jfSyncedAt >=
   nfoWrittenAt` in the database.
2. With Jellyfin's URL blanked in config, press **Save → NFO**: the banner appears, and the item's
   History carries a `jellyfin_refresh_failed` entry.
3. Take an item stuck behind for days (three exist today). Run a normal scheduled scan whose freshness
   filter does **not** select it: it is nonetheless synced, and the banner clears.
4. The banner shows the elapsed time and a working inline **Sync Jellyfin now**.
5. During a running scan, an item in the current working set shows no banner; the same item shows one
   afterwards if its sync genuinely failed.
6. A count of `jfSyncedAt < nfoWrittenAt` across the library trends to ~0 between runs instead of
   accumulating.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/nfo/DriftEvaluator.kt:24-45` — the three states and the
  `jfSyncedAt < nfoWrittenAt` predicate.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/MediaRoutes.kt:524-553` — `POST /{id}/nfo`:
  refreshes, never stamps. `:2173-2220` — `pushToJellyfin`: refreshes and stamps.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/PipelineStepOps.kt:160-178` — `syncJellyfin`;
  `:112-156` — `writeNfo`, including the Phase 153 repair that bumps `nfoWrittenAt` on every run for a
  series with an unnumbered episode.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/PipelineEngine.kt:220-268` — `workingSet` =
  freshness-filtered `scan_files` output; `:355-364` — `sync_jellyfin` scoped to it.
- `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt:2852` — the copy.
- `src/commonMain/kotlin/dev/jellystructure/model/Media.kt:254-260` — the two timestamps and what they
  were designed to distinguish.
- `~/jellystructure/config/config.toml` — the production `write_nfo` / `sync_jellyfin` step config
  (`scope = "missing"`, `refresh_older = "6months"`, `auto_reassert = false`).

## Open questions

1. `detectDrift(autoReassert = true)` (`PipelineStepOps.kt:190-201`) rewrites the NFO and then does
   `store.updateOne(current.copy(jfSyncedAt = …))` using the `current` it read **before** the rewrite —
   which writes the pre-rewrite `nfoWrittenAt`/`nfoHash` back over the values the rewrite just stamped.
   That looks like a real bug, but `auto_reassert` is off in production so it has never fired. Confirm
   and fix inside this phase, or split it out — but do not leave it unstated.
2. `pushToJellyfin` launches `artwork.fetch(item)` in `appScope` and that coroutine does its own
   `store.updateOne`, racing the `jfSyncedAt` stamp on the same item. Last write wins and either
   outcome is survivable, but it is a real read-modify-write race on the same row. Worth confirming
   whether it can be the cause of any of the six long-stuck items.
3. Should the banner distinguish "never synced at all" (`jfSyncedAt == null`) from "synced, then the
   NFO changed"? Production has zero of the former today, so it is theoretical, but the copy would
   differ.
