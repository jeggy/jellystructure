# Phase R248 — Home must know what you just watched

> Stue TV, 2026-09-16: two episodes watched, Back to Home — *It's Always Sunny in Philadelphia* was not
> in Continue Watching at all. Not further down the row; absent. `am force-stop` and relaunch: tile 1,
> `S17:E8`. The server had it. The client never asked.

## Status

`✓ Built` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, finding F3), **implemented 2026-09-16**
(see §Implementation notes). Not dev-reviewed, not device-tested. Client-side with one small
server-side event; no admin change. `compileKotlinLinuxX64`, `:ravilo-ui:compileDebugKotlinAndroid`,
`:ravilo-ui:compileKotlinWasmJs` and the admin `compileKotlinWasmJs` clean; `ReturnRefreshGateTest` (4)
green, `PlaybackWriterTest` / `PlaybackTrackerTest` still green.

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

## Implementation notes (2026-09-16)

- **FR-R248-2 — the event, and *when* it is sent.** `TvEventBus.notifyHomeChanged(userId)` pushes
  `{"type":"home_changed","rev":n}` (its own counter, so the R141 config-rev poll never mistakes a stop
  for a layout change). `HomeFeedService.invalidatePlaystate` ends with it — after the cache drops, the
  playstate refresh and the Continue rebuild, success or failure — so `/tv/mark` and `/tv/played` push it
  too. **One deviation from the traced code, forced by phase 219:** with the `PlaybackWriter` in place the
  stop route has only *queued* the stop when it responds, so its post-respond invalidation would rebuild
  the row from Jellyfin's pre-stop state and push an event that says "correct" about a wrong answer. The
  route now invalidates only when no writer is in use (tests); in production `PlaybackService.onStopLanded`
  (wired in `Main`) runs the same invalidation — and therefore the push — the moment Jellyfin has
  acknowledged the stop, retries included. An abandoned stop pushes nothing; the client's return re-pull
  has already shown whatever the server had (FR-R248-5).
- **FR-R248-1 — the return path, one refresh not two.** `ReturnRefreshGate` (pure, tested) sits in
  `HomeStore` and `ChannelStore`: `onLeave()` when the screen leaves the composition (a `DisposableEffect`
  in `RaviloApp`'s Home/ChannelView blocks), `onHomeChanged()` when the push arrives, and `onReturn()` /
  R40's `load()` on re-entry re-pulls silently **unless** the push already refreshed the retained store
  while away. The push is collected at app level against the store registry (`liveHome`), not by the
  screens, so an event that lands while the player is still on top refreshes the feed the viewer is about
  to return to. R141's Home re-entry emit on `liveConfig` is replaced by `store.onReturn()` — it also
  re-pulled skin/lang on every return, which `config_changed` already covers. The feed on screen stays
  until the new one lands (`refresh(silent = true)`, unchanged).
- **FR-R248-3 — focus follows the item.** `StaticContentRow` records the focused tile's key and whether
  the row owns focus (read during the composition that applies the new items, before the removed tile's
  detach clears focus). On a swap: a surviving key stays focused (keyed items) and is scrolled back into
  view only if the reorder took it out; a vanished key sends focus to the row's first tile. Other rows'
  `LazyListState`s are untouched.
- **FR-R248-4** — `ChannelStore` gets the same gate and the same app-level push handling.
- **FR-R248-5** — nothing client-side moves a tile; every path is a re-pull of `/api/tv/home` or
  `/api/tv/channel/{id}`.
- **Not done:** FR-R248-6 and verification 1–3 (stue TV, logcat fetch count) — no device this session.
