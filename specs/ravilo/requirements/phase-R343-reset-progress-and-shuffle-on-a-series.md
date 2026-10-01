# Phase R343 — Reset progress and Shuffle on a series

> Owner, 2026-10-01: *"In ravilo when a series has been fully watched once, then a reset progress should be possible.
> This specifically makes sense for kid shows, as they are watching the same series many times. And let's also
> investigate a shuffle button on series. I don't want these two new buttons to clutter everything."* Then: *"Let's go
> with direction B"* and *"Let's change that to 9+ episodes."*

## Status

`Planned` — written 2026-10-01 (design-authored) from `design/ravilo/Ravilo TV.html` and `design/ravilo/Ravilo
Mobile.html` (built there the same day: `ravilo-app.js` `seriesFinished` / `shuffleOrder` / `shuffleCtx`, the
`data-reset` / `data-shuffle` handlers; `.rs-chip`, `.spill-sh`, `.spill-sep`, `.dnext-ck` in `ravilo.css`; `.mshuf`,
`.mdone`, `.mreset` in the phone file). Directions canvas: `design/ravilo/Start over & Shuffle - Directions.html`
(direction **B** picked; A and C are kept on the canvas as declined). **Dev-reviewed 2026-10-01** (§Dev review;
items 4, 11 and 13 need the owner, leans given). Number verified free on `main`
(tree `f88c706e`, Ravilo tops at R342 locally; re-checked on `ef52889` the same evening: `main` tops at R340). **Changes** R150-2 (which season a series opens on) and the series
resume pointer behind Play (`SeriesProgress.resumeEpisodeId`, `plan.md`). **Applies to** the TV family (TV, the web
app; R337) and the phone. Stand-in: *Lundin og vinir*, a fictional kids' series, 3 × 13 × 11 min, all watched.

## Today

A series every episode of which this viewer has watched is a dead end:

- The resume pointer finds nothing in progress and nothing unwatched and falls back to the **last** episode. Play reads
  *Play · S03E13*, the hint *Up next · S03E13*, and the page opens on the **last** season (R150-2 picks the first
  unfinished season, else the last).
- Getting back to S01E01 means unticking every episode by hand (39 here). The TV has no *Mark all* since the 09-27
  audit.
- There is no way to play a series in random order.

## Requirements

**FR-R343-1 — Finished.** A series is *finished* for a viewer when that viewer has watched every episode of it **that
is in the library**. Specials (season 0) and missing episodes don't count. A new episode arriving ends the finished
state, and the series behaves as it does today (*Play · S04E01*). Finished is per viewer: another profile's ticks
never count, and a reset (FR-R343-4) never touches them.

**FR-R343-2 — A finished series opens on Season 1, and Play follows on.** On a finished series:

- The page opens on **Season 1**, not on the last season (R150-2's fallback changes from *last* to *first*; its rule
  for an unfinished series is unchanged).
- The hint pill reads **✓ All {n} episodes watched** (`all_watched`) in place of *Up next*. On a finished series it
  replaces the meta row's *✓ Watched*, so the fact is said once.
- **Play does not go to the last episode.** The resume pointer on a finished series is, in order:
  1. an episode with a resume position (started, not finished) ⇒ **Resume · SxxEyy**, as today;
  2. else the episode **after the one most recently played in order** (FR-R343-6), so a rewatch carries on from where
     it is. After the series' last episode it wraps to S01E01;
  3. else **Play · S01E01**.

  The ticks stay (Q1, owner): a rewatch is not a reset. The episode cards keep their ✓, and the *UP NEXT* ribbon
  marks the pointer's episode.

**FR-R343-3 — Reset progress (finished series only).** The Episodes section's header gains one control once the
series is finished, after *{w} of {n} watched* and the bar:

- **TV:** a chip **↺ Reset progress** (`reset_progress`). It makes the header a focus row between the hero's actions
  and the season pills. The first OK **arms** it: the chip shows a warn-coloured ring and reads *Press again · all {n}
  back to unwatched* (`reset_confirm`) for **4 s**, then goes back. A second OK inside the 4 s resets (FR-R343-4).
  There is no dialog and no toast to reach.
- **Phone:** under the Episodes header, one row: *✓ All {n} episodes watched* on the left, **↺ Reset progress** on the
  right (a 44 dp text button). The first tap arms it as on the TV (*Tap again · all {n} back to unwatched*), and the
  second tap resets.

When the series is not finished, the control is absent, not greyed.

**FR-R343-4 — What a reset does.** For **this viewer only**, every episode of the series (every season, specials
included) becomes unwatched with no resume position, and the in-order pointer (FR-R343-6) is cleared. Then:

- the page re-renders as a series nobody has started (*Play · S01E01*, Season 1, no ticks), with focus on Play;
- a toast confirms: *Progress reset · S01E01 is up next* (`reset_done`);
- every other open screen updates through `WatchedBus` (R147/R176): tiles, Continue watching, the series' own card.
  The series leaves Continue watching / Next Up until something is played again (R219 already covers the re-entry).

Nothing else changes: My List, other viewers, the series' own metadata.

**FR-R343-5 — Shuffle.** On every series with **9 or more episodes in the library** (all seasons together, specials
excluded; owner), finished or not:

- **TV:** the season pill row ends with a thin divider and a **Shuffle** pill (shuffle glyph + label, `shuffle`),
  focusable like a season pill. OK starts playback.
- **Phone:** a **Shuffle** chip at the right of the Episodes header (36 dp tall, the whole header row is the hit
  target's height ≥ 44 dp).
- **The order:** every episode of the series in random order, each once (Q4: the whole series, not the page's season;
  Q6: when the last one ends, playback stops as at the end of a series). A **multi-episode file** (R179/R309) is one
  entry and plays whole. A new order is drawn on every press.
- **In the player:** the kicker reads **Shuffle · S02E07** (`shuffle`; R303's top-right identity is unchanged). The
  next-up card's kicker reads **Next · shuffled** (`shuffle_next`) with the next entry's code and title, at the usual
  20 s / credits point (R111, R182). *Next* and auto-advance follow the shuffled order. The Episodes rail keeps the
  season in its own order, with the playing episode lit. Choosing an episode from the rail leaves shuffle and plays on
  in order from there.
- **No resume point (Q5):** a shuffled episode always starts at 0:00. It is ticked when it finishes (the ≥ 90 % rule).
  If the viewer stops early, it **writes no resume position**, so *Resume* on the page and in Continue watching still
  means "where you were in order".
- Shuffle lasts until the player is left. There is no shuffle state to switch off, and it is not remembered.

**FR-R343-6 — Shuffled plays don't move the in-order pointer.** FR-R343-2's step 2 uses the episode most recently
played **in order**. Jellyfin's `LastPlayedDate` moves on every play, shuffled ones included, so it can't be used on
its own. The server keeps, per viewer and series, the last episode played in order (set on every non-shuffled play's
stop/finish, cleared by a reset). A shuffled play is reported with `shuffle: true` and does not update it.

**FR-R343-7 — Strings** × en · da · fo (da/fo drafts; the shipped table wins). Mockup keys:

| Key | en | da | fo |
|---|---|---|---|
| `shuffle` | Shuffle | Bland | Blanda |
| `shuffle_next` | Next · shuffled | Næste · blandet | Næsti · blandað |
| `all_watched` | All {n} episodes watched | Alle {n} afsnit set | Allir {n} partarnir sæddir |
| `reset_progress` | Reset progress | Nulstil | Nullstilla |
| `reset_confirm` | Press again · all {n} back to unwatched | Tryk igen · alle {n} bliver usete | Trýst aftur · allir {n} verða ósæddir |
| `reset_confirm_tap` (phone) | Tap again · all {n} back to unwatched | Tryk igen · alle {n} bliver usete | Trýst aftur · allir {n} verða ósæddir |
| `reset_done` | Progress reset · S01E01 is up next | Nulstillet · S01E01 er næste | Nullstilla · S01E01 er næstur |

`shuffle` may reuse the music table's `music.shuffle` (same three words). The shipped Faroese for *episode* follows
R288; `partarnir` is the draft's word and is the implementer's to align.

## Invariants

- Films are unchanged: *Play Again* and *Mark Watched* stay as they are.
- A series that is not finished looks and behaves exactly as today, except for the Shuffle pill/chip (9+ episodes).
- No new dialog, no new menu, and no new button in the hero's action row (the reason B was picked: the hero stays as
  it is).
- Per-episode watched toggles (R07/R179) are unchanged. No *Mark all* per season comes back.
- The receiver-only TV app (R264/R269) has no detail page and is untouched. A cast started from a shuffled episode
  casts that episode only (open question 2).

## Acceptance

1. Olivar has watched all 39 episodes of *Lundin og vinir*. The page opens on Season 1, Play reads *Play · S01E01*, the
   pill reads *All 39 episodes watched*, every card is ticked, and the Episodes header shows *Reset progress*.
2. Olivar plays S01E01–S01E04 through, leaves, and comes back. Play reads *Play · S01E05*, and the ticks are still
   there.
3. Olivar shuffles, watches S02E07 to the end and stops S01E11 halfway. Play still reads *Play · S01E05*. S01E11 has
   no resume bar, and Continue watching doesn't show it.
4. OK on *Reset progress* once: it reads *Press again · all 39 back to unwatched*. Waiting 4 s puts it back unchanged.
   OK twice: every tick is gone for Olivar only (Eyð's ticks on the same series are unchanged), Play reads *Play ·
   S01E01*, the chip is gone, and the toast says *Progress reset · S01E01 is up next*.
5. A series with 8 episodes has no Shuffle. One with 3 seasons × 4 episodes (12) has it.
6. In a shuffle, the next-up card says *Next · shuffled* with a code that is not the next in order, and the last entry
   ends playback.

## Open questions (leans)

1. **The reset's route.** Lean: one server route, `POST /tv/series/{id}/reset-progress`. It loops the episode ids
   server-side (`DELETE /Users/{u}/PlayedItems/{ep}` plus R185's zeroed position per id), clears FR-R343-6's pointer
   and returns the new `SeriesProgress`, rather than 39 client calls.
2. **Casting a shuffle.** Lean: round 1 casts the current episode only, and the TV shows no shuffle. A shuffled queue
   on the receiver is later, if asked.
3. **The desktop (R337).** Lean: it follows the phone's shape at Compact and the TV family's at Expanded and above,
   whichever detail page that width already uses.
4. **Next Up on Home.** Lean: Home's Next Up / Continue watching use the same in-order pointer (FR-R343-6), so a
   rewatch shows *S01E05*, not Jellyfin's last-played guess. Dev to confirm what `enableRewatching` returns today.

## Mockup notes

- The mockup's Play on a finished series always shows *Play · S01E01*; FR-R343-2's follow-on pointer is not
  simulated.
- The phone mockup lists Season 1 only (no season chips), and its player is a stand-in that doesn't advance a shuffle.

## Dev review (2026-10-01, against `main` `44e26871`)

Read against the backend (`TvRoutes.kt`, `PlaybackService.kt`, `HomeFeedService.kt`, `DetailService.kt`,
`MediaStore.kt`, `JellyfinClient.kt`, the `.sq` files and migrations), `shared` (`Models.kt`, the wire test),
`ravilo-ui` (`SeriesDetailScreen.kt`, `DetailStore.kt`, `WatchedBus.kt`, `PlayerScreen.kt`, `PlayerStore.kt`,
`PlayerResume.kt`, `RaviloApp.kt`, `Cast.kt`), `i18n/*.json` and the design files. The direction holds. Most of it is
client work on code that already exists, and the reset needs no new route. But the in-order pointer is a bigger thing
than the spec says. R185 and R306 make every rewatched episode *Played*, so FR-R343-2's step 1 never fires as written.
And a shuffled finish moves Jellyfin's next-up on an *unfinished* series too. Sixteen items. Three need the owner
(items 4, 11 and 13, leans given); the rest are the build's. Two shipped bugs were found on the way (items 2 and 12).

1. **"Today" describes the mockup, not the app.** The mockup's `seriesProgressFrom`
   (`design/ravilo/ravilo-app.js:592–597`) falls back to `length - 1`. The app already plays the first episode on a
   finished series: `resumeEpId` ends in `?: allEps.firstOrNull()` (`SeriesDetailScreen.kt:325–336`, an earlier bug
   fix). What is true in the app: the page opens on the **last** season (`:295–303`, `?: (detail.seasons.size - 1)`),
   and the label is the literal *Play · E1* (`:627–632`), because `hasResume` is false once every episode is watched
   (`:625`). `SeriesProgress` (`Models.kt:627–632`, `plan.md`) is never filled: `DetailService.kt:220` sends
   `progress = null`, and nothing reads it. The pointer the page really uses is the client's `resumeEpId`, fed by
   R306's `continue_episode_id` on the series' own playstate entry (`TvRoutes.kt:576–584`, `:1459–1468`). Leave
   `SeriesProgress` as it is (a DTO field is never deleted) and don't revive it: it would be a second answer beside
   `continue_episode_id`.

2. **Shipped bug: specials come first.** `DetailService.kt:135` sorts the season numbers ascending and files a missing
   season number under 0, so a series with specials has Season 0 at `seasons[0]`. Three client paths then walk the
   specials first:
   - the opening season: the first season with an unwatched episode (`SeriesDetailScreen.kt:298–302`);
   - the Play fallback: `allEps.firstOrNull { not played }` (`:330`);
   - `watchedCount` and the *{w} of {n} episodes watched* line (`:313`, `:470`), which count specials.

   So a series with an unwatched special opens on Specials, and *Play · E1* plays a special. This is from reading the
   code, not checked against live data. R343 needs one helper anyway, *the episodes that count*: season index ≥ 1, and
   an id that is not a fallback path (an id starting with `/`, `DetailService.kt:165`, has no Jellyfin item, so it can
   never be played or ticked). Use it for *finished*, both counts, the opening season, the Play fallback and the shuffle
   set. The server has the same rule in `MediaStore.nextEpisodeAfter` (`MediaStore.kt:679–690`: season ≥ 1, has a
   Jellyfin id, ordered by season, episode, part).

3. **The reset needs no new route (open question 1).** `PUT /tv/played` with the series' own id and `played: false`
   already does FR-R343-4 (`TvRoutes.kt:815–821` → `PlaybackService.setPlayed`, `:809–866`):
   - it fans out to every episode of the series (specials included; a multi-episode file's parts share one id and are
     deduped), bounded by `playedGate`;
   - it re-reads the result from Jellyfin and returns it for the series and every episode;
   - it calls `invalidatePlaystate`, which rebuilds the Continue list and pushes `home_changed`.

   `markUnplayed` is `DELETE /UserPlayedItems/{id}` (`JellyfinClient.kt:1116–1120`). Per Jellyfin's own source
   (`BaseItem.MarkUnplayed`), that one write sets `Played=false`, `PlaybackPositionTicks=0`, `PlayCount=0` and
   `LastPlayedDate=null`. So R185's zeroing is not needed, and the series also drops out of R219's *finished* and
   *touched* sources. This is from reading the source, not a live write: verify it on a test account, never on the
   household. The one server change: the same call (series id, `played=false`, no `episode_ids`) also deletes the
   pointer row (item 6). An old server does the reset too; it has no pointer to clear.
   Client: `SeriesDetailStore.resetProgress()` beside `setEpisodePlayed` (`DetailStore.kt:165–172`). It calls
   `apiClient.setPlayed(seriesId, false)`, merges the answer into the overlay and passes it to `WatchedBus.publish`.
   Two cautions:
   - `markUnplayed` swallows a failure (it only logs). Show the toast only when the returned map has every counted
     episode unplayed. Otherwise show no toast, and the page shows what came back.
   - The series' own overlay entry is replaced by one without `continue_episode_id`. That is what we want.

4. **FR-R343-2 step 1 never fires as written — needs the owner.** On a finished series every episode is *Played*.
   R185 and R306 treat a position on a Played item as not a resume point:
   - the page's in-progress test is `!ps.played && ps.resumeMs > 0` (`SeriesDetailScreen.kt:329`);
   - Continue Watching asks Jellyfin for `IsPlayed=false` (`JellyfinClient.kt:860`) and skips Played items
     (`HomeFeedService.kt:1031`);
   - the server starts a Played item at 0 (`PlaybackService.kt:506–510`, `resolveStartPositionTicks` `:1351`).

   R306 FR-R306-2 accepted exactly this: *a re-watch … left part-way starts again from the top*. So during a rewatch
   with the ticks kept (Q1), an episode stopped half-way starts from 0 next time. Jellyfin still holds its position
   (a stop under 90 % leaves *Played* true and saves the position). **Lean: resume it** — only for the pointer's own
   episode, and only when the pointer row says the viewer stopped there part-way, playing in order (item 6). Then the
   position is one we know is real, not an R185 leak. The client sends it as `start_position_ms` (which
   `resolveStartPositionTicks` already lets win), labels Play *Resume · S01E05*, and draws the resume bar on that one
   card. If the owner says no, drop step 1: Play reads *Play · S01E05* and starts from 0.

5. **Jellyfin's next-up can't be the pointer (answers open question 4).** `getNextUp` sends no `enableRewatching`
   (`JellyfinClient.kt:1455–1469`), so Jellyfin's default (off) applies. Next-up then follows the *highest-numbered*
   played episode and returns nothing for a finished series. So today a finished series is not in Continue Watching
   at all, rewatched or not. `enableRewatching=true` orders by date played instead, and every shuffled play moves
   that — exactly what FR-R343-6 rules out. The numbering rule hurts an unfinished series too. Say a viewer has watched
   S01E01–E03, shuffles, and finishes S02E07: Jellyfin's next-up becomes S02E08. R306 hands that to the page through
   `continue_episode_id`, so Play jumps to S02E08. The pointer is therefore needed on any series that has been
   shuffled, not only on finished ones (item 6).

6. **The pointer: one table, written on the stop path, read by R219.** Build it like `audiobook_progress`
   (`Audiobooks.sq`), the precedent for per-viewer state that we keep ourselves.
   - Migration `64.sqm` (63 is the latest) and `SeriesPointer.sq`: `series_pointer(user_id, series_id, episode_id,
     finished INTEGER, updated_at, PRIMARY KEY (user_id, series_id))`.
   - **Written** in `PlaybackService.stopPlayback` (`:682–693`). The client's stop, the watchdog and a superseded
     session all go through it. Write the row **before** `releaseSession` queues the stop, so the Continue rebuild
     that follows sees it. Only for an episode of season ≥ 1, and only for a session not started with
     `shuffle: true`. `finished` = past 90 % of the runtime, or past the episode's credits marker (item 12).
     `TrackedPlayback` (`:84–97`) gains `seriesId`, `durationMs`, `shuffle` and `priorPositionMs` (item 9). All four
     are filled in `startPlayback`, which already reads the item's details (`:505`).
   - **The answer:** if the row is finished, the episode after it (`nextEpisodeAfter`), wrapping to the first counted
     episode after the last. If not finished, the row's own episode.
   - **Read** by `buildCanonicalContinueList` (`HomeFeedService.kt:960…`) as a fifth source: a next-up candidate for
     that series, with `lastActivityAt = updated_at`. On a finished series it always applies. On an unfinished series
     it applies only while its answer is still unplayed. If the viewer went on in another Jellyfin client, the row is
     stale, and Jellyfin's next-up is used instead. A wrap back to S01E01 makes no entry, so a series rewatched to the
     end leaves Continue Watching, as it does today.
   - The page reads the answer through the existing `continue_episode_id`. **No new wire field.** Home and the page
     then agree, which is open question 4's lean.
   - **Deleted** by the reset (item 3). Never written for specials, so playing a special on its own moves nothing.

7. **Finished, the opening season and the hint (FR-R343-1/2) are client work over server-pushed flags.** It is the
   same derivation the page already does for `watchedCount` (R84), using item 2's helper. *Finished* = every counted
   episode is `played`. On a finished series:
   - open on the first season with index ≥ 1, not on `size - 1`;
   - `resumeEpId` = `continue_episode_id` when present, else the first counted episode;
   - build the Play label from `resumeEpId` with `episodeCode`, always (no more literal `E1`);
   - the hero's accent line (`:488–512`) reads `detail.all_watched`, and the *{w} of {n} episodes watched* line above
     it is hidden, so the fact is said once;
   - the *UP NEXT* ribbon already follows `resumeEpId` (`EpisodeCard.kt:149`, `MultiEpisodeCard`).

   For an unfinished series, also fix item 2's opening season: the first unfinished season with index ≥ 1, and
   Specials only when nothing else is left.

8. **Where the controls go in the shipped layout.** The Compose page is not laid out like the mockup. Its season
   picker (`SeriesDetailScreen.kt:683`) comes **before** the Episodes header (`:706–727`), and the picker is drawn
   only for 2+ seasons.
   - **Reset on the TV:** a chip at the end of the existing header row. Focus order becomes hero → season pills →
     Reset → episode rail, not "between the hero's actions and the season pills". Down from the hero still lands on
     the season pills (R138/R232's scroll-then-focus, `:608`). On a one-season series the chip sits between the hero
     and the rail, so R296's Up bridge on each card must pass through it. Moving the header above the pills, as
     drawn, is item 13.
   - **Arming:** the first OK arms it for 4 s (`reset_confirm`). Moving focus off it disarms it too, so no armed
     control is left behind on a TV. When the reset is confirmed, move focus to Play (`playFR`) and scroll to the
     hero *before* the chip leaves composition. A focused node that disappears strands focus (the R200/R201 class).
   - **Shuffle on the TV:** a trailing item in `SeasonPicker`'s row (`SeasonPicker.kt:48`), after a divider, with its
     own key. A one-season series with 9+ episodes needs the row too: draw the picker whenever Shuffle shows (one
     *Season 1* pill and Shuffle), as the mockup does.
   - **Phone:** the same header row under `LocalCompact`, with the Shuffle chip at its right, and the
     *All {n} episodes watched · Reset progress* row under it.
   - **Desktop (open question 3, decided by the code):** `SeriesDetailScreen` is one composable for every family. It
     branches on `LocalCompact` only. So the desktop gets the phone's row below 600 dp and the TV's chip above it, with
     no extra work. With a pointer, "Press again" is wrong: use the `_tap` wording (*Tap again* on touch, a *Click
     again* variant on a pointer) and keep *Press again* for the D-pad.
   - **Toast:** the TV has no local toast host. `MusicToastHost` is drawn only when `handset` (`RaviloApp.kt:2451`).
     Draw it in every family (or reuse R152's `ServerMessageHost`). No new component.

9. **A shuffled play never leaves a resume point — done on the server.** The client starts every shuffled entry with
   `start_position_ms = 0`, which overrides a resume point on any server. `PlaybackStartRequest` (`Models.kt:1311–1326`)
   gains `shuffle: Boolean = false`. It is optional with a default, so `WireCompatTest` passes, and an old server
   ignores it (`ignoreUnknownKeys`, `Server.kt:224`). A final stop at 0 would not be enough: the progress heartbeats
   write the shuffled position into Jellyfin as the episode plays, and a 0 would also wipe an in-order resume point on
   that same episode. So:
   - at start, the server keeps the episode's position from before the shuffle (`priorPositionMs`, from the user data
     it already reads at `:505`);
   - at stop, a shuffle session that is not finished reports `priorPositionMs` instead of the playhead; a finished one
     reports the real position, and Jellyfin marks it Played.

   This lives in `stopPlayback`, so the watchdog's forced stop (`:762–781`) and the R219 writer get it too.
   `LastPlayedDate` still moves, but nothing reads it for the pointer any more (item 6).

10. **Old server and new app, and the reverse.** On an old server, the reset works (item 3), and *finished*,
    *Play · S01E01* and the opening season are client-only, so they work. Shuffle does not: an old server keeps the
    shuffled position and moves Jellyfin's next-up. So show Shuffle only when the server says so. `SeriesDetail` gains
    `shuffle: Boolean = false` (an additive response field), set by a new server when the series has 9 or more counted
    episodes. That also puts the 9+ count on the server (render, don't compute). Count episodes, as the owner said,
    not files: a series of three 3-part files gets the pill and shuffles three entries. A new server with an old app
    changes nothing: no field is removed, and no enum gains a value. `WireBaseline` gains the two new fields.

11. **Casting a shuffle (open question 2) — needs the owner.** The two kinds of receiver advance differently. A
    Chromecast gets the sender's episode list and walks it (`RaviloApp.kt:1879–1883`, the in-player hand-off at
    `:1193–1199`, `castEpisodes`). A Ravilo screen (R264/R265) gets its next episode from the server, in order
    (`MediaStore.kt:669`, `PlayPush.next`). Neither knows about shuffle, so a cast play leaves a resume point and moves
    the pointer like any in-order play. *Casts that episode only* would hold for a Chromecast given a one-entry list,
    but not for a screen. **Lean:** casting leaves shuffle, as picking from the rail does. The TV plays the current
    episode and goes on in order, on both kinds of receiver (the Chromecast gets the season list, as today), and it
    counts as an in-order play. That is one rule, no receiver change, and no shuffle on the remote. A shuffled queue
    on the receiver stays a later phase.

12. **Shipped bug: a short episode left at its credits is not ticked.** `advanceNext` marks the episode played only
    at ≥ 90 % (`PlayerScreen.kt:639`; the same test at `:670`). It fires at R182's credits marker or the 20 s card. On
    an 11-minute kids' episode with a minute and a half of credits, that is about 86 %. So the episode is not ticked,
    its stop saves an 86 % resume point, and it shows as in progress. From reading the code. R343 depends on
    *finished* being right (the finished state, the pointer, Q5's tick), so fix it here. Finished = past 90 % **or**
    past the episode's `credits_start_ms` when it has one. Use the same test in `advanceNext`, `skipCredits` and the
    server's pointer write (item 6).

13. **Should the Episodes header move above the season pills, as drawn? — needs the owner (small).** The mockup
    draws the Episodes header, then the season pills, then the rail (`ravilo-app.js:824–842`). The app draws the
    pills, then the header, then the rail (item 8). **Lean: keep the app's order.** Moving the header re-opens focus
    bridges that four phases settled (R138, R201, R232, R296), and nothing in R343 needs it.

14. **The player.** `Dest.Player` (`RaviloApp.kt:288`) gains an optional shuffle plan: the order (a season index and
    a group index per entry) and the place in it. A multi-episode file is one entry, played from its first id, as
    `groupEntryPoint` picks today.
    - The rail stays the playing entry's own season. Each step rebuilds `episodes` and `currentEpIndex` for that
      season with `buildEpisodeContext`.
    - In `onNavigateToEpisode` (`:1947–1985`), an id equal to the plan's next entry continues the shuffle. Any other id
      (a rail pick through `chooseEpisode`, `PlayerScreen.kt:687–690`) drops the plan and plays on in that season's
      order, as today.
    - The kicker is `"${str("detail.shuffle")} · $code"`. The next-up card's kicker is `player.up_next` today
      (`PlayerScreen.kt:3348`), in capitals; in a shuffle use a new `player.up_next_shuffled`, in the same case.
    - Auto-advance follows the viewer's *Play next automatically* setting (`bk.autoplayNextEnabled`, `:1171`), as
      in-order play does. The last entry has no next, so the card offers *Skip credits* and playback ends (R182).
    - R292's `ResumeRecord` (`PlayerResume.kt:20–45`) gains `shuffle: Boolean = false`, so a return from the
      background restarts the session as a shuffled one. The rest of the plan is not saved (nor is the rail), so after
      a low-memory restore the shuffle ends with that entry.
    - Unchanged by R343: today's in-order binge stops at the end of a season, because the player's list is one season.

15. **Strings.** The shipped table uses dotted keys and its own words for *episode* (`partur` / `partar` in Faroese,
    R288's lexicon). Keys: `detail.shuffle` (or reuse `music.shuffle` — *Shuffle · Bland · Blanda*, the same three
    words), `detail.all_watched`, `detail.reset_progress`, `detail.reset_confirm`, `detail.reset_confirm_tap`,
    `detail.reset_done` and `player.up_next_shuffled`. `reset_done` must not hard-code *S01E01*: the first counted
    episode may be a three-part file, or the series may start at Season 2. Write it as *Progress reset · {code} is up
    next*, filled by the same `episodeCode` as the Play button. `all_watched`'s `{n}` is the counted episodes. A note
    outside R343: the series page and the player print `S1E5` and `S1 · E5` (`SeriesDetailScreen.kt:168–178`), not
    the house spelling S01E05. The new strings take whatever the formatter prints, so they always match the button.

16. **Build order.** Each step can ship alone:
    (a) client: item 2's helper and item 7 (finished, the opening season, the Play label, the hint). This also fixes
    the specials bug;
    (b) Reset: item 3, plus item 8's controls and the toast;
    (c) server: item 12's finished test, then the pointer (item 6) and its R219 source;
    (d) Shuffle: items 9, 10 and 14, with item 11's cast rule;
    (e) the strings (item 15), with each step.
