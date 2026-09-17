# Phase 229 — A stop never takes Continue Watching off Home

> Found on the stue TV 2026-09-17, first press: play an episode for a minute, press Back twice, and
> the **Continue Watching row is gone from Home** — for five minutes, for every device of that user.
> Focus lands on the *Home* nav tab with the page half-scrolled, because the row the viewer came from
> no longer exists. The cause is an ordering error in the stop path that R248 made reachable on every
> single stop.

## Status

`Planned` — written 2026-09-17 from a live reproduction and a code trace, not dev-reviewed.
Backend-only (`HomeFeedService.kt`); no wire, config or client change. Partially corrects **R219**
FR-R219-1's implementation, restores **R231**'s invariant on the stop path, and completes **R248**
FR-R248-2.

**Numbering:** verified against `STATUS.md` 2026-09-17 — admin taken through **228**.

## What happened (measured)

| Local time | Event |
|---|---|
| 14:12 | Home on the stue TV shows *Continue Watching* (Tomgang first) |
| 14:15:03 | `PlaybackInfo: item=ffc190a6… directPlay=true` |
| ~14:18:30 | Back out of the player — the stop is reported |
| 14:19 | TV Home: no Continue Watching row; focus on the nav bar |
| 14:21:30 – 14:23:26 | `GET /api/tv/home` with the TV's own device token: first row is `NEWLY_ADDED`. Meanwhile the log reads `Continue Watching refresh: 4 refreshed, 0 failed` every ~70 s |
| 14:23:49 | The row is back — 5 min 19 s after the stop, i.e. `FEED_TTL_MS` |

## Cause (traced against `main`)

`HomeFeedService.invalidatePlaystate` (`:197`) runs, in order:

1. `feedCache.remove(userId)`
2. **`continueListCache.remove(userId)`**
3. `PlaystateCache.refreshOne(…)` — a Jellyfin round trip
4. `refreshContinueListFor(device)` — four more Jellyfin fetches, up to `CONTINUE_TIMEOUT_MS` (6 s)
5. `tvEventBus.notifyHomeChanged(userId)`

Between 2 and the end of 4 the user **has no Continue list at all**, and `canonicalContinueList`
(`:755`) answers a missing entry with `emptyList()`. Any `getHomeFeed` inside that window — and R248's
refresh-on-return makes the TV ask at exactly that moment — builds a feed **without the row** and
writes it to `feedCache` (step 1 had just emptied it), where it is valid for `FEED_TTL_MS` = 5 min.
Step 5 then tells every client to re-pull, and they are handed the same cached, rowless feed.

Nothing repairs it early: the background loop (`refreshAllContinueLists`) rewrites
`continueListCache` every 60 s but **never touches `feedCache`**, so a fresh list sits unused behind a
stale feed. The same is true with no stop involved: a list the loop has just changed is invisible on
Home until the feed's TTL runs out.

Second defect on the same lines: R231 says *a failed build never overwrites a good value*. Step 2
deletes the good value **before** step 4 tries to build, so a timed-out rebuild (Jellyfin answered
nothing for 5–6 s for all four users between 14:06 and 14:14 the same day) leaves the user with an
empty list until the next successful background cycle. R248's own comment at step 5 — "or its rebuild
failed and the previous value standing" — describes behaviour the code above it does not have.

## Requirements

- **FR-229-1 — never delete the list to refresh it.** `invalidatePlaystate` does not remove
  `continueListCache`. It rebuilds; on success the new list replaces the old one; on failure the old
  one stands (R231, now true on this path too).
- **FR-229-2 — structural caches are dropped *after* the rebuild, not before.** `feedCache`,
  `channelRailCache` and the user's `channelContentCache` entries are removed once steps 3–4 have
  finished (succeeded or not), immediately before `notifyHomeChanged`. A request that arrives during
  the rebuild is served the pre-stop feed — stale by seconds, and corrected by the push that follows.
- **FR-229-3 — a feed can never outlive the Continue list it was built from.** `FeedEntry` and the
  channel-content entries record the `builtAt` of the `continueListCache` entry they read (0 when there
  was none); a cached feed whose recorded value differs from the current entry's is a miss. This is
  what makes the background loop's work visible, and what closes the race for good rather than by
  ordering alone: even a feed built inside some future window is discarded the moment the list lands.
- **FR-229-4 — the loop only re-stamps a list that changed.** `refreshContinueListFor` keeps the
  existing entry (same `builtAt`) when the rebuilt cards are equal to the cached ones, so FR-229-3 does
  not turn a 60 s loop into a 60 s feed TTL. The per-cycle log line counts *refreshed* by attempt
  success, not by identity, so it stays truthful.
- **FR-229-5 — tests.** (a) a `getHomeFeed` issued while an `invalidatePlaystate` rebuild is suspended
  still carries the Continue row; (b) after a failed rebuild the previous list is served; (c) a list
  changed by the background path is on the next `getHomeFeed` without waiting for `FEED_TTL_MS`;
  (d) an unchanged rebuild does not invalidate the cached feed.

## Non-goals

- The Jellyfin timeouts of 14:06–14:14 themselves (every refresher, permit wait 0 ms, self-recovered).
  Recorded here as an observation; not investigated in this phase.
- Client focus recovery when a row disappears underneath it. With the row no longer disappearing the
  case is not reachable from a stop; a genuinely emptied row landing focus on the nav bar is acceptable.

## Acceptance

1. Play anything for a minute on a TV, Back to Home: Continue Watching is present, the title just
   watched is first, focus returns to a tile.
2. `GET /api/tv/home` polled every second across a stop never returns a feed without the row.
3. `linuxX64Test` green, including FR-229-5's four cases.
