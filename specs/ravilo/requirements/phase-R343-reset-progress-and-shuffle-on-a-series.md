# Phase R343 — Reset progress and Shuffle on a series

> Owner, 2026-10-01: *"In ravilo when a series has been fully watched once, then a reset progress should be possible.
> This specifically makes sense for kid shows, as they are watching the same series many times. And let's also
> investigate a shuffle button on series. I don't want these two new buttons to clutter everything."* Then: *"Let's go
> with direction B"* and *"Let's change that to 9+ episodes."* After the dev review: *"If the user clicked on the
> episode S01E01, then it should just follow whatever logic jellyfin has. But if the user clicked 'Start over ·
> S01E01', start episode S01E01 and then after watching 5% it should in the background mark every episode in the series
> as unwatched and then we will rely on jellyfin progress tracking again, just as normal."*

## Status

`Planned` — written 2026-10-01 (design-authored) from `design/ravilo/Ravilo TV.html` and `design/ravilo/Ravilo
Mobile.html` (built there the same day: `ravilo-app.js` `seriesFinished` / `shuffleOrder` / `shuffleCtx`, the
`data-reset` / `data-shuffle` handlers; `.rs-chip`, `.spill-sh`, `.spill-sep`, `.dnext-ck` in `ravilo.css`; `.mshuf`,
`.mdone`, `.mreset` in the phone file). Directions canvas: `design/ravilo/Start over & Shuffle - Directions.html`
(direction **B** picked; A and C are kept on the canvas as declined). **Dev-reviewed 2026-10-01** (§Dev review). The
owner answered the review's three questions the same day and changed the reset (§Owner decisions): **Start over** on
the primary button is the reset, there is no separate *Reset progress* control, no in-order pointer of our own,
shuffle carries over to a cast, and the Episodes header moves above the season pills with a focus-path test. The FRs
below are the decided version. Number verified free on `main` (tree `f88c706e`, Ravilo tops at R342 locally;
re-checked on `ef52889` the same evening: `main` tops at R340). **Changes** R150-2 (which season a series opens on)
and the label and target of a finished series' primary button. **Applies to** the TV family (TV, the web app; R337)
and the phone. **Depends on** R346 (*the episodes that count*: specials and unplayable rows left out) and R347 (an
episode left at its credits is finished), both found in this review. Stand-in: *Lundin og vinir*, a fictional kids'
series, 3 × 13 × 11 min, all watched.

## Today

A series every episode of which this viewer has watched is a dead end:

- The mockup's resume pointer falls back to the **last** episode. The app already plays the first episode, but labels
  the button *Play · E1*, shows no hint, and opens the page on the **last** season (R150-2 picks the first unfinished
  season, else the last). See Dev review item 1.
- Getting back to an unwatched series means unticking every episode by hand (39 here). The TV has no *Mark all* since
  the 09-27 audit.
- There is no way to play a series in random order.

## Requirements

**FR-R343-1 — Finished.** A series is *finished* for a viewer when that viewer has watched every episode of it **that
is in the library**. Specials (season 0) and missing episodes don't count, nor does a row with no Jellyfin item (R346's
*episodes that count*). A new episode arriving ends the finished state, and the series behaves as it does today
(*Play · S04E01*). Finished is per viewer: another profile's ticks never count, and Start over's clear (FR-R343-4)
never touches them.

**FR-R343-2 — A finished series opens on Season 1 and offers Start over.** On a finished series:

- The page opens on **Season 1** (the first season with index ≥ 1), not on the last season. R150-2's fallback changes
  from *last* to *first*; its rule for an unfinished series is unchanged.
- The hint reads **✓ All {n} episodes watched** (`detail.all_watched`) in place of *Up next*. It replaces the
  *{w} of {n} episodes watched* line, so the fact is said once.
- The primary button reads **Start over · S01E01** (`detail.start_over` and the first counted episode's code; a
  multi-episode file reads *Start over · S01E01–E03*). It plays that episode from 0:00 and starts FR-R343-4's clear.
  The episode cards keep their ✓, and the *UP NEXT* ribbon marks that first episode.
- **Picking an episode by hand follows Jellyfin's normal logic** (owner). A watched episode starts from 0:00 (R306),
  Jellyfin ticks it and moves its last-played date as usual, and nothing is cleared. The page keeps reading *Start
  over* while every episode is still watched.
- On a server that can't do the clear (FR-R343-10), the button reads *Play · S01E01* and plays the same episode.

**FR-R343-3 — Removed (owner, 2026-10-01).** There is no separate *Reset progress* control (the two-press chip in the
Episodes header is gone). *Start over* is the reset. The mockup still draws the chip; the spec wins.

**FR-R343-4 — What Start over clears, and when.** *Start over* plays the first counted episode with `start_over: true`
on its playback start. Then:

- **Once 5 % of that episode has played** (5 % of the file for a multi-episode file), the server marks **every episode
  of the series unwatched for this viewer**, in the background, while playback goes on. Every season counts, specials
  included. It uses the existing `PUT /tv/played` path, which also clears each episode's resume position.
- **The playing episode keeps its live progress.** Right after the clear, the server writes the playing episode's
  current position back, and the stop that ends the session lands after the clear, never before it. Leaving at 2:00
  therefore reads *Resume · S01E01* on the page and in Continue watching.
- **Stopped before 5 %, nothing is cleared.** The ticks stay, the series is still finished, and the page still reads
  *Start over · S01E01*. (Jellyfin keeps no position under 5 % either.)
- After the clear, **Jellyfin's normal tracking governs everything**: ticks, resume points, Continue watching and
  next-up. Auto-advance to S01E02 is an ordinary in-order play.
- Other open screens update the usual way. The clear ends with the same invalidation `PUT /tv/played` runs (the
  Continue list is rebuilt, `home_changed` and `playstate_changed` are pushed). The detail page reads playstate again
  when the viewer comes back to it (R84).
- Nothing else changes: My List, other viewers, the series' own metadata. There is no toast: the viewer is in the
  player when it happens.

**FR-R343-5 — Shuffle.** On every series with **9 or more counted episodes** (all seasons together, specials excluded;
owner), finished or not:

- **TV:** the season pill row ends with a thin divider and a **Shuffle** pill (shuffle glyph + label,
  `detail.shuffle`), focusable like a season pill. OK starts playback. A one-season series with 9+ episodes draws the
  pill row too (one *Season 1* pill and Shuffle), as the mockup does.
- **Phone:** a **Shuffle** chip at the right of the Episodes header (36 dp tall, the whole header row is the hit
  target's height ≥ 44 dp).
- **The order:** every counted episode of the series in random order, each once (Q4: the whole series, not the page's
  season; Q6: when the last one ends, playback stops as at the end of a series). A **multi-episode file** (R179/R309) is
  one entry and plays whole. A new order is drawn on every press.
- **In the player:** the kicker reads **Shuffle · S02E07** (R303's top-right identity is unchanged). The next-up card's
  kicker reads **UP NEXT · SHUFFLED** (`player.up_next_shuffled`, in the case of today's `player.up_next`) with the next
  entry's code and title, at the usual 20 s / credits point (R111, R182). *Next* and auto-advance follow the shuffled
  order. The Episodes rail keeps the season in its own order, with the playing episode lit. Choosing an episode from
  the rail leaves shuffle and plays on in order from there.
- **No resume point (Q5):** a shuffled episode always starts at 0:00. It is ticked when it finishes (R347's rule). If
  the viewer stops early, its position is put back to what it was before the shuffle, so a shuffled play leaves no
  resume point and never wipes one.
- Shuffle lasts until the player is left. There is no shuffle state to switch off, and it is not remembered.

**FR-R343-6 — Removed (owner, 2026-10-01).** There is no in-order pointer of our own. Jellyfin's tracking decides
Resume and next-up. Consequence, accepted with that decision: a shuffled episode finished on an *unfinished* series is
ticked, and Jellyfin's next-up follows the highest-numbered watched episode, so next-up may jump past where the viewer
was (Dev review item 5).

**FR-R343-7 — Strings** × en · da · fo (da/fo drafts; the shipped table wins). Keys follow the shipped table:

| Key | en | da | fo |
|---|---|---|---|
| `detail.start_over` | Start over | Start forfra | Byrja av nýggjum |
| `detail.shuffle` | Shuffle | Bland | Blanda |
| `detail.all_watched` | All {n} episodes watched | Alle {n} afsnit set | Allir {n} partarnir sæddir |
| `player.up_next_shuffled` | UP NEXT · SHUFFLED | NÆSTE · BLANDET | NÆSTI · BLANDAÐ |

`detail.start_over` and `detail.shuffle` use the same words as the shipped `ab.start_over` and `music.shuffle`; reusing
those keys is also fine. The episode code is never written into a string: the button joins the label and the code
from the same formatter as *Play · …* today. The shipped Faroese for *episode* follows R288; `partarnir` is the draft's
word and is the implementer's to align. The mockup's `reset_progress`, `reset_confirm`, `reset_confirm_tap`,
`reset_done` and `shuffle_next` are not used.

**FR-R343-8 — A shuffle carries over to the TV (owner, 2026-10-01).** Casting from the page's Shuffle, or handing a
shuffled player over to a TV, keeps playing the same shuffled order on the TV, with no resume point (FR-R343-5). Both
kinds of receiver:

- **Chromecast:** the sender sends the shuffled order as the load's episode list, in play order, with
  `current_index` on the entry to start, and `episodes_shuffled: true` (new, additive). The receiver walks the list
  as it already does, sends `shuffle: true` on each playback start, and labels its next-up card *UP NEXT · SHUFFLED*.
- **Ravilo screen:** the play request carries the entries after this one as `shuffle_queue` (new, additive). The
  server keeps that order for the screen's session. The screen's next episode is the queue's next entry, and its starts
  count as shuffled. The screen's own *Next* already asks the server for the next play, so the screen needs no change
  to follow the order.
- *Start over* rides the same carriers (`start_over` on the load and on the play request), so FR-R343-4's clear also
  happens when the episode plays on a TV.

**FR-R343-9 — The Episodes header sits above the season pills, and the page's D-pad focus is guarded (owner,
2026-10-01).** As drawn: hero, then the Episodes header, then the season pills (with Shuffle last on the TV), then the
episode rail. The owner: *"let's make sure that we don't keep falling into these D-pad focus issues that we keep on
having."* So:

- The series page has **no `FocusRequester` attached inside a lazy item that focus is sent to from outside it** (R236
  FR-R236-5's rule). The season pills become a plain, always-composed row. Play and the selected pill are reached only
  through one helper that first awaits the scroll that composes them, then requests focus (R232's sequence). The
  episode rail is entered only through its `focusRestorer()`. No focus move waits on the playstate overlay's timing.
- **An automated focus-path test** walks the page with D-pad keys and asserts the focused node after every key: hero
  → season pills → Shuffle → episode rail → back up, on the fixtures that broke before (R138, R201, R232, R296). It
  runs headless on the JVM in CI's existing unit-test step. See Dev review item 13 for the exact shape.

**FR-R343-10 — Older servers.** `SeriesDetail` gains two additive flags, `start_over` and `shuffle`, set by a server
that can do FR-R343-4 and FR-R343-5/8 (`shuffle` only when the series has 9+ counted episodes). When a flag is absent
(an older server), the app shows *Play · S01E01* instead of *Start over*, and no Shuffle. No field is removed, and no
existing enum gains a value.

## Invariants

- Films are unchanged: *Play Again* and *Mark Watched* stay as they are.
- A series that is not finished looks and behaves as today, except for the Shuffle pill/chip (9+ episodes) and the
  header's new place above the season pills.
- No new dialog, no new menu, and no new button in the hero's action row (the reason B was picked: the hero stays as it
  is). *Start over* is the existing primary button's label on a finished series.
- Per-episode watched toggles (R07/R179) are unchanged. No *Mark all* per season comes back.
- The receiver-only TV app (R264/R269) has no detail page. It only gains the shuffle order through FR-R343-8, with no
  code change of its own.

## Acceptance

1. Olivar has watched all 39 episodes of *Lundin og vinir*. The page opens on Season 1, the primary button reads
   *Start over · S01E01*, the hint reads *All 39 episodes watched*, and every card is ticked. There is no *Reset
   progress* control.
2. Olivar presses *Start over* and stops at 0:20 (under 5 % of 11 minutes). Back on the page: every tick is there, and
   the button still reads *Start over · S01E01*.
3. Olivar presses *Start over* and stops at 2:00. Every tick is gone for Olivar only (Eyð's ticks on the same series
   are unchanged). The page reads *Resume · S01E01* with S01E01's resume bar, and Continue watching shows S01E01.
4. On the finished series, Olivar picks S02E04 from the rail. It plays from 0:00, nothing is cleared, and the page
   still reads *Start over · S01E01* afterwards.
5. A series with 8 episodes has no Shuffle. One with 3 seasons × 4 episodes (12) has it, and so does one season of 13.
6. In a shuffle, the next-up card says *UP NEXT · SHUFFLED* with a code that is not the next in order, and the last
   entry ends playback. Stopping a shuffled episode halfway leaves it with the resume point it had before.
7. Shuffle with a Chromecast connected: the TV plays the shuffled order and its card says *UP NEXT · SHUFFLED*. Same
   on a Ravilo screen, which plays the same order.
8. The series page's focus-path test passes in CI, and on the TV: Down from the hero focuses the selected season pill
   on the first press, Right reaches Shuffle, Down enters the rail, and Up returns the same way.

## Open questions (answered)

1. **The reset's route.** Answered by the review and the owner: no new route. Start over's clear reuses the
   `PUT /tv/played` fan-out on the server (Dev review item 3).
2. **Casting a shuffle.** Owner: the shuffle carries over to the TV, on both receivers (FR-R343-8).
3. **The desktop (R337).** Decided by the code: the series page is one composable that branches on `LocalCompact`, so
   the desktop gets the phone's shape below 600 dp and the TV family's above (Dev review item 8).
4. **Next Up on Home.** Owner: Jellyfin's tracking governs. A finished series is not in Continue watching (Jellyfin's
   next-up has nothing for it). After Start over passes 5 %, S01E01 is an ordinary resume item and appears there
   (Dev review item 5).

## Mockup notes

- The mockup's finished series shows *Play · S01E01* and the *Reset progress* chip. The spec wins: the button reads
  *Start over · S01E01*, and there is no chip.
- The phone mockup lists Season 1 only (no season chips), and its player is a stand-in that doesn't advance a shuffle.

## Dev review (2026-10-01, against `main` `44e26871`)

Read against the backend (`TvRoutes.kt`, `RemoteRoutes.kt`, `PlaybackService.kt`, `HomeFeedService.kt`,
`DetailService.kt`, `MediaStore.kt`, `JellyfinClient.kt`, the `.sq` files and migrations), `shared` (`Models.kt`,
`CastMessages.kt`, the wire test), `ravilo-ui` (`SeriesDetailScreen.kt`, `SeasonPicker.kt`, `DetailStore.kt`,
`WatchedBus.kt`, `PlayerScreen.kt`, `PlayerStore.kt`, `PlayerResume.kt`, `RaviloApp.kt`, `Cast.kt`), the receivers
(`ravilo-cast` `Receiver.kt`, `ravilo-screen` `Screen.kt`), `i18n/*.json` and the design files. The direction holds.
Most of it is client work on code that already exists, and nothing needs a new route. The review's first draft
proposed an in-order pointer of our own; the owner replaced it the same day with *Start over* and Jellyfin's own
tracking (§Owner decisions), and the items below are written to the decided version. Two shipped bugs were found and
are specced separately as R346 and R347.

1. **"Today" in the first draft described the mockup, not the app.** The mockup's `seriesProgressFrom`
   (`design/ravilo/ravilo-app.js:592–597`) falls back to `length - 1`. The app already plays the first episode on a
   finished series: `resumeEpId` ends in `?: allEps.firstOrNull()` (`SeriesDetailScreen.kt:325–336`, an earlier bug
   fix). What is true in the app: the page opens on the **last** season (`:295–303`, `?: (detail.seasons.size - 1)`),
   and the label is the literal *Play · E1* (`:627–632`), because `hasResume` is false once every episode is watched
   (`:625`). `SeriesProgress` (`Models.kt:627–632`, `plan.md`) is never filled: `DetailService.kt:220` sends
   `progress = null`, and nothing reads it. The page's pointer is the client's `resumeEpId`, fed by R306's
   `continue_episode_id` on the series' own playstate entry (`TvRoutes.kt:576–584`, `:1459–1468`). Leave
   `SeriesProgress` as it is (a DTO field is never deleted) and don't revive it.

2. **Specials come first — a shipped bug, now R346.** `DetailService.kt:135` puts Season 0 at `seasons[0]`, so the
   opening season, the Play fallback and the watched counts all walk specials first (R346 has the detail). R343 needs
   R346's *episodes that count* (season index ≥ 1, an id with a Jellyfin item) for *finished*, the 9+ count, the Start
   over target and the shuffle set. If R343 is built first, it introduces that helper, and R346 applies it to the
   remaining paths.

3. **Start over's clear: on the server, on the progress path, with no new route.** The fan-out exists:
   `PlaybackService.setPlayed` (`:809–866`, behind `PUT /tv/played`, `TvRoutes.kt:815–821`) unmarks every episode of a
   series (specials included; a multi-episode file's parts share one id and are deduped), bounded by `playedGate`.
   `markUnplayed` is `DELETE /UserPlayedItems/{id}` (`JellyfinClient.kt:1116–1120`). Per Jellyfin's own source
   (`BaseItem.MarkUnplayed`), that one write sets `Played=false`, `PlaybackPositionTicks=0`, `PlayCount=0` and
   `LastPlayedDate=null`, so no separate position zeroing is needed. This is from reading the source, not a live write:
   verify it on a test account, never on the household. What to build:
   - `PlaybackStartRequest` (`Models.kt:1311–1326`) gains `start_over: Boolean = false`. It is optional with a
     default, so `WireCompatTest` passes; an old server ignores it (`ignoreUnknownKeys`, `Server.kt:224`). The client
     sends it only from the *Start over* button, with `start_position_ms = 0`.
   - `startPlayback` (`:470`) puts `startOverSeriesId` and `durationMs` on `TrackedPlayback` (`:84–97`), only when the
     item is an episode of a series. The duration comes from the item details it already reads (`:505`), else the
     catalog runtime; if both are missing, the threshold is 60 s of playback.
   - `reportProgress` (`:660`) and `stopPlayback` (`:682`) check it: at the first position ≥ 5 % of the duration, latch
     it (once per session) and start the clear in the service's own scope. A heartbeat never waits for it, and a
     failure only logs. The clear runs `setPlayed(device, seriesId, played = false)`, then writes the playing episode's
     last known position back through the same writer the heartbeats use, then runs the invalidation the `/tv/played`
     route runs (`invalidatePlaystate`: Continue list rebuilt, `home_changed` pushed).
   - **Ordering.** The clear also unmarks the playing episode, which is intended: it was watched, and must become an
     ordinary in-progress episode. Its position is then written back at once, not at the next 10 s heartbeat. A stop
     for that session waits for a running clear (bounded, e.g. 15 s) before it queues its own write, so the stop's
     position is always the last write on that episode.
   - Below 5 % nothing happens. The latch is per session, so a second session (a hand-off to a TV that also carries
     `start_over`, item 11) may run the clear again. That is harmless: unmarking unwatched episodes changes nothing, and
     the playing episode's position is written back again.
   - `markUnplayed` swallows failures (it only logs). The page reads playstate again on return, so a partial clear
     shows as it really is; nothing on screen claims success.

4. **Picking an episode by hand on a finished series follows Jellyfin (owner).** Nothing changes in code: a watched
   episode starts at 0 (R306 FR-R306-2, `resolveStartPositionTicks` `PlaybackService.kt:1351`), Jellyfin keeps its tick
   and moves its last-played date, and the series stays finished. The first draft's question (resume inside a rewatch
   with the ticks kept) is gone with the pointer: after Start over passes 5 %, nothing is watched, and R185/R306 no
   longer get in the way.

5. **Jellyfin's next-up and Continue watching (answers open question 4).** `getNextUp` sends no `enableRewatching`
   (`JellyfinClient.kt:1455–1469`), so Jellyfin follows the *highest-numbered* watched episode and returns nothing for
   a finished series. Resume asks for `IsPlayed=false` (`:860`) and skips watched items (`HomeFeedService.kt:1031`). So
   a finished series is not in Continue watching, and after Start over passes 5 % S01E01 enters as an ordinary resume
   item. Both are Jellyfin's normal behaviour, as the owner asked. One consequence, accepted with that decision: a
   shuffled episode finished on an *unfinished* series is ticked, and next-up then follows the highest-numbered watched
   episode. A viewer at S01E03 who shuffles and finishes S02E07 sees next-up S02E08, and the page's Play follows it via
   `continue_episode_id`.

6. **Removed: the in-order pointer.** The first draft's `series_pointer` table, migration `64.sqm` and the fifth
   Continue-list source are dropped (owner decision 1). There is no per-viewer playback state of our own in this phase.

7. **Finished, the opening season, the hint and the button (FR-R343-1/2) are client work over server-pushed flags.**
   It is the same derivation the page already does for `watchedCount` (R84), using item 2's helper. *Finished* = every
   counted episode is `played`. On a finished series:
   - open on the first season with index ≥ 1, not on `size - 1`;
   - the primary button reads `"${str("detail.start_over")} · $code"`, `$code` from `episodeCode` for the first
     counted entry, and plays it through `buildEpisodeContext` with `startOver = true` carried on `Dest.Player` to
     `PlayerStore`'s start (only when `SeriesDetail.start_over`; else *Play · $code*, same target);
   - the hero's accent line (`:488–512`) reads `detail.all_watched`, and the *{w} of {n} episodes watched* line is
     hidden, so the fact is said once;
   - the *UP NEXT* ribbon (`EpisodeCard.kt:149`, `MultiEpisodeCard`) marks the first counted entry, because
     `resumeEpId` resolves to it on a finished series.

   The label always comes from `episodeCode`, never a literal. R292's `ResumeRecord` (`PlayerResume.kt:20–45`) gains
   `startOver: Boolean = false`, so a return from the background before 5 % still carries the flag.

8. **Where the controls go (FR-R343-5/9).** The app's page today draws the season picker (`SeriesDetailScreen.kt:683`)
   **before** the Episodes header (`:706–727`), and the picker only for 2+ seasons. Decided: match the mockup.
   - Put the header and the pill row in **one** lazy item (key `seasons`), header first. The hero stays item 0 and
     this item stays item 1, so Down from the hero still scrolls to item 1 and focuses the selected pill (`:608`).
     When there is no pill row (one season, under 9 episodes), the item is the header alone.
   - **TV:** the header has nothing focusable (title, the count, the bar). Shuffle is the pill row's last item after a
     divider, with its own key. A one-season series with 9+ episodes draws the row (one *Season 1* pill and Shuffle).
   - **Phone:** the same header under `LocalCompact`, with the Shuffle chip at its right.
   - **Desktop (open question 3, decided by the code):** `SeriesDetailScreen` is one composable for every family and
     branches on `LocalCompact` only, so the desktop gets the phone's shape below 600 dp and the TV's above it with no
     extra work.
   - No Reset chip, no arming, and no toast (FR-R343-3 removed; the clear happens in the player).

9. **A shuffled play never leaves a resume point — done on the server.** The client starts every shuffled entry with
   `start_position_ms = 0`, which overrides a resume point on any server. `PlaybackStartRequest` also gains
   `shuffle: Boolean = false` (optional, default). A final stop at 0 would not be enough: the heartbeats write the
   shuffled position into Jellyfin as the episode plays, and a 0 would also wipe a resume point the viewer had on that
   same episode. So:
   - at start, the server keeps the episode's position from before the shuffle (`priorPositionMs` on
     `TrackedPlayback`, from the user data it already reads at `:505`);
   - at stop, a shuffled session that is not finished (R347's rule) reports `priorPositionMs` instead of the playhead;
     a finished one reports the real position, and Jellyfin ticks it.

   This lives in `stopPlayback`, so the watchdog's forced stop (`:762–781`) and the R219 writer get it too.
   `LastPlayedDate` still moves, which can lift the series in Continue watching; that is Jellyfin's normal tracking.

10. **Old server and new app, and the reverse (FR-R343-10).** On an old server, `start_over` and `shuffle` on the
    start are ignored: nothing would be cleared, and a shuffled stop would leave a resume point. So the app shows
    *Start over* and Shuffle only when the server says it can: `SeriesDetail` gains `start_over: Boolean = false` and
    `shuffle: Boolean = false` (additive response fields). A new server sets `start_over` always and `shuffle` when the
    series has 9 or more counted episodes, which also puts the 9+ count on the server (render, don't compute). Count
    episodes, as the owner said, not files: three 3-part files get the pill and shuffle three entries. A new server
    with an old app changes nothing. `WireBaseline` gains the new fields; no field is removed and no enum gains a value.

11. **A shuffle carries over to the TV (open question 2, owner: carry it).** The two receivers advance differently,
    so each gets its own carrier. Both carriers are additive fields.
    - **Chromecast.** The receiver walks the sender's list: next = `episodes[current_index + 1]` (`Receiver.kt:687`,
      `:719`), and every entry is started through `/api/tv/playback/start` (`:353`). The sender
      (`RaviloApp.kt:1879–1883` from the page, `:1193–1199` for the in-player hand-off, `castEpisodes`) sends the
      shuffled order as `episodes`, in play order. `CastLoadData` (`CastMessages.kt:60–80`) gains `episodes_shuffled:
      Boolean = false` and `start_over: Boolean = false`. With `episodes_shuffled`, the receiver sends `shuffle: true`
      on each start and uses `player.up_next_shuffled` on its card. With `start_over`, it sends `start_over: true` on
      its first start only. Do **not** reuse the existing `shuffle` field: it is music's (286) and reorders `tracks`.
      The receiver bundle is served by the backend, so receiver and server always ship together.
    - **Ravilo screen.** The screen never decides what is next: the server's play push names it (`PlayPush.next`, from
      `nextEpisodeAfter`, `MediaStore.kt:669`, `:679–690`), and the screen's own `playNext` posts `/remote/play` for that
      id (`Screen.kt:526–535`). `RemotePlayRequest` (`Models.kt:1603–1607`) gains `shuffle_queue: List<String> =
      emptyList()` (the entries after this one) and `start_over: Boolean = false`. In `/remote/play`
      (`RemoteRoutes.kt:126–160`), a non-empty queue sets an in-memory context for that screen device: the series, the
      current id and the rest. The push's `next` becomes the queue's head (title and kicker resolved as
      `nextEpisodeAfter` does), and the push gains `shuffled: Boolean = false` for the card's words. When the screen
      posts `/remote/play` for the head id with no queue, the server advances the context. Any other id clears it (that
      leaves shuffle, like a rail pick). The context is also cleared after the last entry and when the watchdog reaps
      the device (`onDeviceReaped`). When that screen calls `/api/tv/playback/start` for the context's current id, the
      server treats it as `shuffle: true` (item 9), and as `start_over: true` when the play request carried it. The
      screen app needs no change to follow the order. Its card's *SHUFFLED* wording waits for its next build (Tizen
      work is paused).
    - The cast remote shows what the receiver reports; it needs no change.

12. **A short episode left at its credits is not ticked — a shipped bug, now R347.** `advanceNext` ticks only at
    ≥ 90 % (`PlayerScreen.kt:639`, `:670`), and on a short episode the credits can start earlier. R343 depends on it:
    *finished* (FR-R343-1), the shuffled stop's "finished" (item 9) and Q5's tick all use R347's rule (past 90 % or past
    the credits marker). Build R347 first, or in the same change.

13. **The focus guard (FR-R343-9, owner).** The owner asked that the header move not re-open the D-pad bugs that R138,
    R201, R232 and R296 each fixed on this page. Concretely:
    - **The pill row is not lazy.** `SeasonPicker` (`SeasonPicker.kt:48`) is a `LazyRow` with `firstFocusRequester` on
      the selected pill. That is a requester on a lazy item, the exact R201 failure, kept alive today by a
      `scrollToItem` workaround (`:65–71`). A series has few seasons, so make it a `Row` with `horizontalScroll`: every
      pill and Shuffle are always composed, and the workaround goes.
    - **One way to focus something inside the page's `LazyColumn`.** Play (hero, item 0) and the selected pill (item 1)
      are requested only through one helper that awaits the scroll that composes the item and then calls
      `requestFocusRetrying` (R232's sequence, `:600–612`, `FocusModifiers.kt:55`). No bare `requestFocus()` on them.
    - **The episode rail stays lazy** (a season can hold 100+ episodes), so it is entered only through its
      `focusRestorer()` (as today) and never through a per-card requester. R296's per-card Up bridge stays.
    - **No focus move depends on the overlay's timing.** The auto-season one-shot (`:295–303`) sets the selected season
      only; it never moves focus.
    - **The test.** The repo has no Compose UI test yet: every `ravilo-ui` test is a pure function in `commonTest` or
      `androidUnitTest`, and `desktopTest` is not an option on the dev host (Skiko). Add `SeriesDetailFocusTest` in
      `ravilo-ui/src/androidUnitTest`, run under **Robolectric** on the plain JVM: no emulator, no device, no Skiko. It
      runs in CI's existing step `./gradlew :ravilo-ui:testDebugUnitTest` (`.github/workflows/ci.yml:142`). Test-only
      dependencies: `androidx.compose.ui:ui-test-junit4`, `ui-test-manifest` and `org.robolectric:robolectric`, with
      `unitTests.isIncludeAndroidResources = true`. Nothing ships, and nothing reaches the Flatpak's offline sources.
      Make `SeriesDetailLoaded` `internal` so the test can render it with a fixed `SeriesDetail` and overlay, set a TV
      configuration (`@Config(qualifiers = "w960dp-h540dp")`, `LocalCompact` false), and tag Play, each pill, Shuffle,
      the first card and its Watched toggle. Each step is `onRoot().performKeyInput { pressKey(Key.DirectionDown) }`
      (or Up/Left/Right) followed by `assertIsFocused()` on exactly the expected tag. Fixtures and walks:
      - **R138 / R232:** 3 seasons × 13, overlay delivered one frame *after* the first composition. Play → Down focuses
        the selected pill on the **first** press → Right to the last pill → Right to Shuffle → Down enters the rail →
        Up returns to the pill row → Up returns to Play.
      - **R201:** 11 seasons, active season 11 (all earlier seasons watched). Down from Play focuses pill 11 on the
        first press.
      - **R296:** 1 season × 6 (no pill row). Down from Play enters the rail; Up from a card returns to Play; Up from a
        card's Watched toggle returns to that card.
      - **This phase:** a finished 3 × 13 series (opens on Season 1, Play reads *Start over*), and a 1 × 13 series (the
        one-pill row with Shuffle).
    - The sweep FR-R236-5 asked for (other screens) stays outside this phase.

14. **The player.** `Dest.Player` (`RaviloApp.kt:288`) gains an optional shuffle plan: the order (a season index and
    a group index per entry) and the place in it. A multi-episode file is one entry, played from its first id, as
    `groupEntryPoint` picks today.
    - The rail stays the playing entry's own season. Each step rebuilds `episodes` and `currentEpIndex` for that
      season with `buildEpisodeContext`.
    - In `onNavigateToEpisode` (`:1947–1985`), an id equal to the plan's next entry continues the shuffle. Any other id
      (a rail pick through `chooseEpisode`, `PlayerScreen.kt:687–690`) drops the plan and plays on in that season's
      order, as today.
    - The kicker is `"${str("detail.shuffle")} · $code"`. The next-up card's kicker today is `player.up_next`
      (`PlayerScreen.kt:3348`); in a shuffle it is `player.up_next_shuffled`.
    - Auto-advance follows the viewer's *Play next automatically* setting (`bk.autoplayNextEnabled`, `:1171`), as
      in-order play does. The last entry has no next, so the card offers *Skip credits* and playback ends (R182).
    - The in-player hand-off to a TV (`:1193–1199`) sends the rest of the plan (item 11), not the season rail, and
      `start_over` while the local session carries it.
    - `ResumeRecord` (`PlayerResume.kt:20–45`) gains `shuffle: Boolean = false`, so a return from the background
      restarts the session as a shuffled one. The rest of the plan is not saved (nor is the rail), so after a
      low-memory restore the shuffle ends with that entry.
    - Unchanged by R343: today's in-order binge stops at the end of a season, because the player's list is one season.

15. **Strings (FR-R343-7).** The shipped table uses dotted keys and its own words for *episode* (`partur` / `partar` in
    Faroese, R288's lexicon). `detail.start_over` and `detail.shuffle` repeat the shipped `ab.start_over` and
    `music.shuffle` words. Codes are never in a string: they come from `episodeCode`, so the button and every label
    agree. The `S1E5` / `S1 · E5` spelling on this page (`SeriesDetailScreen.kt:168–178`) is R346's FR, not this phase's.

16. **Build order.** Each step can ship alone:
    (a) R347 (finished = past 90 % or past the credits marker) and R346's counting helper;
    (b) the layout and the focus guard (items 8 and 13), with the test, before anything else on the page moves;
    (c) finished, the opening season, the hint and *Start over* (items 3 and 7), with the server's clear and the
    `start_over` flags;
    (d) Shuffle: items 9, 10 and 14;
    (e) Shuffle and Start over on the TV (item 11);
    (f) the strings (item 15), with each step.

### Owner decisions (2026-10-01)

1. **Start over replaces the pointer.** On a finished series the primary button reads *Start over · S01E01*. It plays
   S01E01, and once 5 % of it has played the server marks every episode of the series unwatched in the background, on
   the existing `PUT /tv/played` path, keeping S01E01's live progress. After that, Jellyfin's normal tracking governs
   everything. Stopped before 5 %, nothing is cleared. Picking an episode by hand follows Jellyfin's normal logic. The
   5 % trigger rides an additive `start_over` flag on the playback start, and the server acts on progress. The
   `series_pointer` table, migration 64 and the fifth Continue-list source are dropped. → FR-R343-2, FR-R343-4,
   FR-R343-6 (removed), items 3–7.
2. **No separate *Reset progress* control.** Start over is the reset. Its FR and strings are removed; the mockup still
   draws the chip, and the spec wins. → FR-R343-3 (removed), FR-R343-7, item 8.
3. **Casting a shuffle: the shuffle carries over to the TV**, on a Chromecast (the sender's list, `episodes_shuffled`)
   and on a Ravilo screen (the server keeps the order per session, `shuffle_queue`). → FR-R343-8, item 11.
4. **Header order: match the mockup** (the Episodes header above the season pills), and *"make sure that we don't keep
   falling into these D-pad focus issues"*: no requester on a lazy item, one scroll-then-focus helper, and a Compose UI
   focus-path test under Robolectric in CI. → FR-R343-9, items 8 and 13.
5. **The 9+ threshold and everything else stay as reviewed.**

## Build notes (2026-10-01)

Built 2026-10-01 (on `main` `f5d2b22b`), together with R346 (the episodes that count) and R347 (finished at the
credits), which it depends on. Not deployed, not device-tested. No migration.

**Server**
- `SeriesDetail.start_over` (always true now) and `shuffle` (9+ counted episodes: rows with a Jellyfin item in
  seasons ≥ 1) — FR-R343-10. `PlaybackStartRequest.start_over` / `shuffle`, `RemotePlayRequest.shuffle_queue` /
  `start_over`, `CastLoadData.episodes_shuffled` / `start_over`, `PlayItemEnvelope.shuffled` — all additive with
  defaults; no enum value added, nothing removed.
- `PlaybackService` keeps a per-session `SessionPlan` (`tv/SeriesReplay.kt`) from `startPlayback` to the stop:
  the series to clear (Start over), the file length and credits marker, the shuffle flag and the position the
  episode had before (0 for a Played one). A Start over or a shuffled entry starts at 0:00 on the server too.
- **Start over (FR-R343-4):** the first progress report (or the stop) at ≥ 5 % of the file (60 s with no length)
  latches once per session and, on the service's scope, runs `setPlayed(series, false)` (the `PUT /tv/played`
  fan-out, every season), writes the playing episode's live position back at once, then runs
  `invalidatePlaystate(device, seriesId)` (`onSeriesCleared`, wired in `Main.kt`: Continue list rebuilt,
  `playstate_changed` + `home_changed`). The latch and the job are registered under one lock; a stop waits for a
  running clear (15 s, bounded) before it queues its own write. Below 5 % nothing happens.
- **Shuffle (FR-R343-5, dev review 9):** a shuffled stop that is not finished (R347's rule) reports the prior
  position instead of the playhead; a finished one reports the real position (or the end, at the credits).
- **Ravilo screen (FR-R343-8):** `ScreenShuffles` (in memory, per screen): `/remote/play` with a queue starts a
  context, a play for the queue's head advances it, any other id ends it, the watchdog's reap clears it. The
  push's `next` is the queue's head (resolved like `nextEpisodeAfter`) with `shuffled: true`; the screen's own start
  for the current id counts as shuffled / Start over. The screen app is unchanged; its card keeps saying *UP NEXT*
  until its next build (Tizen work is paused).

**App**
- **Layout and focus (FR-R343-9):** hero → the Episodes header → the season pills (Shuffle last after a divider) →
  the rail. Header and pills are one lazy item (`seasons`, item 1); with no pill row the header stays in the rail's
  item. `SeasonPicker` is a plain `Row` with `horizontalScroll` (every pill composed; the R201 `scrollToItem`
  workaround is gone). Play and the selected pill are focused only through one helper (`focusInList`: await the
  scroll, then `requestFocusRetrying`); the auto-season one-shot sets the season only. A one-season series with
  Shuffle draws one *Season 1* pill (TV family); the phone's Shuffle is a chip at the right of the header.
- **Finished (FR-R343-1/2):** opens on Season 1; the accent line reads *✓ All {n} episodes watched* (drawn ✓) and
  the *{w} of {n}* line is hidden (space kept); the button reads *Start over · S01E01* and plays it with
  `start_over` (cast too); *Play · S01E01* when the server can't clear. Picking a card is unchanged.
- **Shuffle (FR-R343-5, dev review 14):** `buildShufflePlan` — every counted episode once, a multi-episode file one
  entry, random order per press; each entry is its own season's context with the kicker *Shuffle · S02E07* and its
  next = the next entry (none after the last). `Dest.Player` carries the plan; the plan's next continues it, any
  other id (a rail pick) plays on in that season's order. *UP NEXT · SHUFFLED* via a CompositionLocal
  (`LocalShuffledNextUp`), not a `PlayerScreen` parameter. `ResumeRecord` gains `startOver` / `shuffle`.
- **Cast (FR-R343-8):** from the page, a shuffle goes to a Chromecast as its episode list (`episodes_shuffled`) or to a
  screen as `shuffle_queue`; the in-player hand-off sends the rest of the plan and `start_over`. The Chromecast
  receiver sends `shuffle` on every start and `start_over` on the sender's first load only, from 0:00 or the
  handed-over position, and its card reads *UP NEXT · SHUFFLED*.
- **Strings (FR-R343-7):** `detail.start_over`, `detail.shuffle`, `detail.all_watched`, `player.up_next_shuffled`
  × en/da/fo (Faroese *Allir {n} partar sæddir*, `partar` per R288; lexicon regenerated).

**Deviations:** none from the decided FRs. The Episodes header's watched count on the TV moved into the `seasons`
item with the header (it is the header's own line). The page's Play label now always names its episode (R346).

**Verified:** `SeriesDetailFocusTest` — the repo's first Compose UI test, Robolectric (SDK 34, `w960dp-h540dp`), in
CI's existing `:ravilo-ui:testDebugUnitTest` — 6 walks pass: R138/R232 (overlay one frame late; Down → selected pill
first press → Right ×3 → Shuffle → Right stays → Down into the rail → Up → the pill row → Up → Play), R201 (11
seasons, pill 11 first press), R296 (no pill row; rail and back; toggle → its card), finished (Start over, *All 39
episodes watched*), an older server (*Play · S01E01*, no Shuffle), 1 × 13 (one pill + Shuffle). Two mutations were
checked to fail it: Down from Play handed to native search (4 walks fail), and the helper requesting focus once
without awaiting the scroll (5 walks fail). The R232 race itself depends on device timing; the test pins the
sequence, not the race.
Also `SeriesEpisodesTest` (the plan: every counted episode once, a 3-part file one entry, nexts chained, the last
has none), `SeriesReplayTest` (stop decisions, the 5 % threshold, `ScreenShuffles`), `SeriesDetailMultiEpisodeFileTest`
(the 9+ flag counts rows, not specials or unplayable rows). Compiles: backend, Android release, desktop, web,
receiver; `check-player-dex.sh` 238/250. ART verification on an emulator could not run here (no KVM); CI runs it.
Test-only dependencies added to the catalog: `androidx.compose.ui:ui-test-junit4` / `ui-test-manifest` 1.9.4 (the
androidx Compose UI that CMP 1.9.3 resolves to on Android) and Robolectric 4.14.1 (as `:ravilo-player`).

**Needs a device / deploy:** the server half (the clear, the shuffled stop, the screen queue) needs a deployed
backend; verify the clear on a **test account** first (dev review 3: from Jellyfin's source, not a live write).
Acceptance 1–8 on the Pixel 9, a Ravilo TV and a Chromecast; the D-pad walk of acceptance 8 on a TV.

**Found on the Pixel 9 (2026-10-02, the deployed build) and fixed the same night.** The clear worked (19 of 20 episodes
unwatched), but the playing episode came back **watched with a resume point** (`Played = true`, 3:47), so the page offered
*Resume · S01E02*. Jellyfin's playback session holds the user data it read at start — the episode was watched — and its
stop report writes that back over the clear. Fix: the stop that ends a cleared Start over session carries a flag
(`PlaybackWriter.PendingWrite.startOverUnplayed`, kept when a later stop replaces it); once Jellyfin has the stop, the
sink sets the episode to unwatched at the stop's position in one write (`JellyfinClient.setUserData` →
`POST /UserItems/{id}/UserData`, checked live on 12.1). Skipped when the stop is itself finished (R347's rule), so an
episode watched to the end during a Start over is still ticked. `PlaybackWriterTest` covers the flag.

### Amendment (2026-10-02) — the page right after a Start over stop

**Seen on the Mac (backend `v1.48-34-g0a5a7e59`):** right after the stop of a Start over, the series page showed
*1 of 20 · Resume · S01E02* for about 30 s, then corrected itself to *0 of 20 · Resume · S01E01*.

**Why.** Between Jellyfin's stop (which writes the stale *watched* flag back, see above) and our write-back, the
server's `PlaystateCache` and its Continue list can be refreshed from Jellyfin's state of that moment (a background
cycle, or the stop's own refresh racing a slow write-back), and the page reads them the moment the player closes. The
write-back itself fixed Jellyfin, but nothing put *its* result into the caches directly: the episode's row waited for
a refresh that might time out (5 s budget, phase 230) and the page, once drawn, never read again — the series page
did not listen to `home_changed`.

**FR-R343-11 — The write-back refreshes what `PUT /tv/played` refreshes.** Once the unwatched write-back has landed,
the server (1) writes the episode's new state (unwatched, at the stop's position) straight into `PlaystateCache`, then
(2) runs `onSeriesCleared` — the same invalidation the clear and `PUT /tv/played` run: the series' playstate re-read,
the Continue list rebuilt, `playstate_changed` and `home_changed` pushed. It replaces the stop's ordinary
`onStopLanded` refresh for that write, so the work is not done twice. The stop request itself also writes that state
into `PlaystateCache` before it is queued, so a page reading at once sees the episode unwatched.

**FR-R343-12 — The page follows the push.** An open series page (or one under the remote) re-reads its playstate on
`home_changed` (R351 FR-R351-7), so whatever the server corrects shows without leaving the page.
