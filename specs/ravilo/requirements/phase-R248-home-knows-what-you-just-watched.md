# Phase R248 — Home must know what you just watched

> Stue TV, 2026-09-16: two episodes watched, Back to Home — *It's Always Sunny in Philadelphia* was not
> in Continue Watching at all. Not further down the row; absent. `am force-stop` and relaunch: tile 1,
> `S17:E8`. The server had it. The client never asked.

## Status

`Planned` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, finding F3). Not dev-reviewed, not built.
Client-side with one small server-side event; no admin change.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — Ravilo taken through R245; R246/R247 by
sibling specs the same day.

## What the code does (traced against `main`, 2026-09-16)

**The server already does its half.** `HomeFeedService.kt:196-205` — on `/tv/playback/stop` it drops
`feedCache`, `continueListCache` (R219 FR-R219-1: "a stop must correct the row at once"),
`channelRailCache` and the user's channel content caches (206 FR-206-4), then runs
`PlaystateCache.refreshOne` and `refreshContinueListFor(device)`. That is why the restart showed the
right answer.

**The client never re-reads.** `RaviloApp.kt:723` keeps the `HomeStore` across navigation
(`keptStore("home:…")`) so the Home screen returns with its scroll, focus and **feed** exactly as
left. The only live refresh Home has is `LocalLiveConfig` (`HomeScreen.kt:113-114`, R33), which
fires on a *layout* push from the admin — never on playback. `HomeStore.refresh(silent = true)`
(`HomeStore.kt:171`) exists and is the right call; nothing calls it on the way back from the player.

**One race to respect.** The stop route (`TvRoutes.kt:598-606`) responds **before** it runs the
refresh ("runs after responding so the client's stop ack isn't delayed by it"). A client refresh
fired on the ack can therefore read the pre-stop cache and cement the wrong answer for another
refresh cycle.

## Requirements

**FR-R248-1 — Returning to Home from the player refreshes the feed, silently.** Whatever pops
`Dest.Player` — Back, the stop event, the credits card's own exit, auto-advance ending — the Home
store runs `refresh(silent = true)`. The feed on screen stays until the new one lands: no shimmer, no
blank row, no flicker (R212's invariant; the no-flicker rule).

**FR-R248-2 — Refresh on the server's word, not the client's guess.** `TvEventBus` gains
`notifyHomeChanged(userId)` (beside `notifyConfigChanged`, `TvEventBus.kt:68`), sent by the stop
handler **after** its post-respond invalidation and refresh complete — success or failure. The client
handles it on the same `/api/tv/events` collector `LocalLiveConfig` already uses. FR-R248-1's
refresh-on-return is the fallback for a device whose socket is down; when the event arrives first it
wins and the return refresh is skipped (one refresh, not two).

**FR-R248-3 — Focus follows the item, not the index.** The refresh may reorder Continue Watching; the
focused tile stays the focused *title* (by card id) when it survives, and falls to the row's first
tile when it does not. Scroll position of every other row is untouched (R139's restore target).

**FR-R248-4 — Channel pages get the same treatment.** A channel screen returned to from the player
(its own Continue row, 206) refreshes on the same event and the same return path.

**FR-R248-5 — FE reflects BE.** The client never moves a tile itself. If the server's stop-triggered
refresh failed (a lost write, phase 219's territory), the refreshed feed shows whatever the server
honestly has — the constitution's *server-pushed state only*.

**FR-R248-6 — Acceptance.** Play ≥ 2 minutes of an episode, Back to Home: the series is tile 1 of
Continue Watching with the right `S:E` badge, focus unchanged, no restart, within one refresh.

## Non-goals

- Server cache design (204/205/206 own it) and the refreshers' reliability (219).
- The Continue Watching badge collision seen on the same tile (R249).

## Verification

1. Stue TV, release build: FR-R248-6 on a series and on a movie; then the same from a channel page.
2. Pull the socket (airplane the TV, or stop the backend for ten seconds) and repeat: FR-R248-1's
   return path still refreshes once the server is back.
3. Logcat shows one `/api/tv/home` fetch per return, not two.

## Open questions

- Whether `notifyHomeChanged` should carry the row ids that changed so the client can skip the fetch
  when nothing visible moved. Not for this phase — one fetch per stop is cheap.
