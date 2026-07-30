# Phase R185 — Continue Watching shows already fully-watched titles (bug fix, FR-RV-CW1)

> A viewer reported already-finished series/episodes still showing in Ravilo's Continue Watching.
> Root-caused live against Jellyfin (jellyfin.example.net); this is a genuinely different bug from
> R184 (which fixed a *leaked* stale position landing on the wrong episode) — this one is about
> Jellyfin's own `Played` and `PlaybackPositionTicks` fields going out of sync on the SAME item, with
> nothing in jellystructure ever reconciling them before Continue Watching renders.

**Status:** Planned.

## Bug report
"I can often see already finished watched series in my continue watching section in Ravilo."
(2026-07-30.)

## Investigation
Continue Watching is **entirely Jellyfin-backed**, not jellystructure's own DB — the local
`config/jellystructure.db` has no playback-position/played columns at all. `HomeFeedService.buildContinueRow`
(`src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt:432-476`) just repackages
`jellyfinClient.getResumeItems()` (`Filters=IsResumable`, `auth/JellyfinClient.kt:273-289`), which Jellyfin
implements as **`PlaybackPositionTicks > 0`, with no `Played` check at all**. Both the global Home row and
every per-channel row (`HomeFeedService.getChannelFeed`, `HomeFeedService.kt:174-201`) resolve to this same
function — a channel just hands it a pre-filtered candidate list.

**Live evidence** (`GET /Users/{id}/Items/Resume?Filters=IsResumable` against jellyfin.example.net, all 7
users): user `jogvan`'s 115 "resumable" items include **62 (54%)** with `Played:true` **and**
`PlaybackPositionTicks > 0` simultaneously — a state that should never coexist. Smoking-gun pattern: within
one binge session, many *different* episodes share a byte-identical `PlaybackPositionTicks` — e.g. all 15
*Grísa Polly* (Polly Piglet) episodes played 07-20 07:11–08:27 carry exactly `513600000` (51.36s) despite
runtimes varying 301–302s; all 12 *Sóley og Gás* episodes on 07-21 carry exactly `3018020000`; 4 *Ruffy*
episodes watched ~7 minutes apart each land on the identical 73.9%. A real per-episode stop position varies;
an identical absolute value across many different items is a write from the **wrong item's** playhead, not
each episode's own true stop point. Corruption clusters 2026-07-10 through 07-25 (nothing after
07-25T17:05), consistent with the kids'-show binge-watching profile that exercises auto-advance heaviest.

### Root cause — two independent write paths that can disagree, with nothing reconciling them
- **`Played`** is set via `PlaybackService.mark()` (`PlaybackService.kt:298-306`, the player's ≥90% auto-mark
  on `advanceNext()`) and `PlaybackService.setPlayed()` (`PlaybackService.kt:314-358`, the detail-page manual
  watched toggle) — both call **only** `JellyfinClient.markPlayed()` (`JellyfinClient.kt:409-413`,
  `POST /Users/{id}/PlayedItems/{id}`, **no position field in the request at all**). Neither function ever
  touches `PlaybackPositionTicks`.
- **`PlaybackPositionTicks`** is set only via `stopPlayback`/`reportPlaybackProgress`
  (`/Sessions/Playing/Stopped` / `/Progress`, `JellyfinClient.kt:360-393`).
- Two concrete ways these diverge:
  1. **Manual "mark as watched" from the detail page** (`setPlayed`) has **zero** position handling —
     flipping a partially-watched item to watched via the browse UI leaves `Played:true` and whatever stale
     nonzero position it already had, permanently. 100% reproducible, no race required.
  2. **`advanceNext()`'s auto-mark on binge advance** (`PlayerScreen.kt:377-393`) calls `store.markWatched(itemId)`
     for the finished episode but never calls `stopSession`/`stopPlayback` for it directly. The *only* place
     that episode's position is ever reported to Jellyfin is indirectly, via the *old* `PlayerStore`'s
     `DisposableEffect(store) { onDispose { store.stopSession(positionMs, durationMs) } }`
     (`PlayerScreen.kt:863-864`) firing when `RaviloApp.kt:830`'s `remember(dest.itemId)` swaps in the new
     episode's store. `positionMs`/`durationMs` are screen-scoped, shared across the whole binge (the same
     staleness class R184 already documented) — whatever they hold at the moment that disposal races against
     the new episode's own `LaunchedEffect(itemId)` reset is what gets reported as the **old** episode's
     "final" position, and it isn't reliably that episode's own true near-100% value.
- `PlaybackTracker`'s stop-grace window (b877813) does **not** cover this: it only suppresses a *late*
  heartbeat arriving *after* an explicit stop was already recorded for that `(device,item)` key. Since
  `advanceNext()` never calls `stopPlayback`/`.stopped()` for the outgoing item at all, that protection is
  never armed for the dominant binge-advance trigger.
- Downstream, nothing checks the two fields against each other: `buildContinueRow` builds a card from any
  `IsResumable` result without ever looking at `play.userData?.played` (`JellyfinPlayItem.userData`,
  `auth/Models.kt:128-138`, `JellyfinUserData.played`, `Models.kt:121` — the field is already deserialized,
  just unread here).

## Requirements

### FR-RV-CW1-1 — Close the write gap at the one server choke point both paths share
`PlaybackService.mark()` and `PlaybackService.setPlayed()` must zero `PlaybackPositionTicks` (via
`JellyfinClient.stopPlaybackSession(..., positionTicks = 0, ...)`) for every target id whenever they write
`Played = true`, immediately before/alongside the `markPlayed` call. This fixes both divergence paths — the
manual toggle (which had no position handling at all) and the auto-advance mark (regardless of whatever the
client-side race resolves to) — at a single point, so future client-side races in either app surface can't
reproduce this corruption. `playSessionIdFor(device, id)` is a pure deterministic string, not a lookup
against a live session, so it's safe to reuse here exactly as `stopPlayback` already does.

### FR-RV-CW1-2 — Defense in depth: never surface a played item as resumable
`buildContinueRow`'s `resumeItems` loop (`HomeFeedService.kt:457-464`) must skip any entry where
`play.userData?.played == true`. This is what makes the fix robust against *any* future desync cause we
haven't found yet, and it immediately un-corrupts what the viewer sees today without needing a Jellyfin-side
data repair — already-corrupted historical rows (the 62 found live) stop appearing on the next Home/channel
load, no backfill required.

## Invariants
- **An item Ravilo shows as "in progress" always has `Played == false`.** No code path may present a
  finished title as resumable, regardless of what stale position data Jellyfin happens to be holding.
- **Marking an item watched (any path) always leaves it in a self-consistent state** — `Played == true` and
  `PlaybackPositionTicks == 0` — never one without the other.

## Out of scope
- Bulk-repairing the 62 already-corrupted Jellyfin `UserData` rows found live — FR-RV-CW1-2 already
  suppresses their display, and any operator can already fix a given title today via the existing
  unmark/re-mark watched toggle now that `setPlayed` correctly zeroes position.
- The channel-scoped "Mesterholdet missing from DanskTV" report — investigated separately and found to be an
  unrelated Jellyfin metadata gap (a single episode file with no `IndexNumber` in Jellyfin), not a
  played/position desync. See **Phase 152**.

## Source references
- Write gap: `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` (`mark`, `setPlayed`,
  `stopPlayback` for the existing pattern to mirror).
- Jellyfin calls: `src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt` (`markPlayed`,
  `stopPlaybackSession`).
- Continue row: `src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt` (`buildContinueRow`,
  `getChannelFeed`).
- Client-side contributing race: `ravilo-ui/src/commonMain/.../ui/screens/PlayerScreen.kt` (`advanceNext`,
  the `DisposableEffect(store)` at line ~863) — not changed by this phase; FR-RV-CW1-1 closes the gap
  server-side regardless.
- Related: **R184** (`phase-R184-autoplay-next-stale-position.md`, the leaked-outgoing-position bug this
  phase's investigation was originally mistaken for), **b877813** (`PlaybackTracker` stop-grace window,
  confirmed not to cover this case).
