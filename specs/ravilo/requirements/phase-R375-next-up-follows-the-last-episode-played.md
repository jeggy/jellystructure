# Phase R375 — Next up follows the last episode played

> Owner, 2026-10-04 (choosing option **b** of the TV D-pad sweep's open question): when you finish an episode of a
> series you are re-watching, Continue Watching and the series page's primary button offer **the next episode in
> order after the one you last played** — finishing S03E14 offers S03E15, even though S06E06 was once watched — not
> Jellyfin's Next Up, which follows the furthest episode ever watched.

## Status

`Planned` — written 2026-10-04 (dev-authored) from the owner's decision on
[`tv-dpad-sweep-2026-10-04.md`](../../research-reports/tv-dpad-sweep-2026-10-04.md) (§ *Open question for the owner:
what comes next after an episode you finish*, options a/b/c). Server first (`HomeFeedService`), plus a small client
change on the series page and one write at a shuffled stop. No new route, no new DTO field, no table, no migration,
no string.

**Amends:**
- **R343 FR-R343-6** ("no in-order pointer of our own — Jellyfin's tracking decides Resume and next-up"). There is
  still no table of our own: the position is read from Jellyfin's own last-played dates every time. But the next
  episode is now ours to choose, not `/Shows/NextUp`'s. R343's dev review item 5 consequence (*"next-up then follows
  the highest-numbered watched episode"*) no longer holds.
- **R343 FR-R343-2 and open question 4** ("a finished series is not in Continue watching"). A fully watched series
  whose last-played episode is not its last one is in Continue Watching, and its page offers that next episode
  (FR-R375-5). *Start over · S01E01* stays for a finished series whose last play was its last episode.
- **R219 §3** (the conflict rule). The rule stays *the most recent activity wins*; the next-up side of it is now
  FR-R375-2's episode, not `/Shows/NextUp`'s, whenever there is a finished episode to follow.

## What happens today

The sweep (2026-10-04, living-room TV): a series was in progress at S03E14 and Continue Watching showed it. Finishing
S03E14 made Continue Watching and the series page's primary button jump to **S06E07** — an episode half watched in
August — instead of **S03E15**. Anyone re-watching an earlier season, or watching out of order (children do), is
pulled forward to wherever they once got to.

Why, in the code:

1. **Continue Watching takes its next episode from Jellyfin.** `HomeFeedService.buildCanonicalContinueList`
   (`tv/HomeFeedService.kt:962`) fetches `getNextUp` (`:984`, `auth/JellyfinClient.kt:1501–1515`, no
   `enableRewatching`) beside Resume, the finished list and the touched list. The next-up card per series is
   Jellyfin's answer (`:1048–1057`). The conflict rule (`:1069–1081`) then shows that card whenever the newest
   finish is newer than the resume point — so finishing S03E14 (newer than S06E07's August resume point) shows
   Jellyfin's next-up, S06E07.
2. **Jellyfin's Next Up follows the highest-numbered watched episode** (read in Jellyfin's source, tag `v12.1`, which
   is the household's version; identical on `master`): `NextUpService.GetNextUpEpisodesBatch` orders a series'
   played episodes by `ParentIndexNumber`, then `IndexNumber`, descending, and takes the first as *last watched*; the
   next is the first **unplayed** episode after it. S06E06 is watched, so S06E07 wins.
3. **The series page follows Continue Watching.** The page's primary button plays `primaryEpisodeId`
   (`ravilo-ui/.../screens/SeriesEpisodes.kt:54`), which takes the series' `continue_episode_id` first. That id comes
   from the same cached list (`HomeFeedService.continueEpisodes`, `:853`; laid over `/api/tv/playstate` by
   `withContinueEpisodes`, `server/routes/TvRoutes.kt:582`, `:1486`). On a **finished** series the page ignores it
   and plays the first counted episode (*Start over*, R343).
4. **Every Continue Watching surface reads that one list** (R219): the Home row, every channel's Continue row and
   See all are views over `canonicalContinueList`, and the Home tile's `nextUpLabel` is the entry's own card. So one
   change in the build changes them all.
5. **The player is already in order.** Its next-up card and auto-advance (R111, R184) use the next file in the
   playing episode's season (`SeriesDetailScreen.kt:225–248` `buildEpisodeContext`; `RaviloApp.kt:2040–2075`), never
   Jellyfin's Next Up. A cast or a Ravilo screen gets its next from `MediaStore.nextEpisodeAfter`
   (`media/MediaStore.kt:704`): season then episode order across seasons, specials never, a multi-episode file once,
   nothing after the last.

### Does Jellyfin's `enableRewatching` do it? No.

With `enableRewatching=true`, `/Shows/NextUp` (v12.1, `TVSeriesManager.GetNextUpBatched` +
`NextUpService.GetNextUpEpisodesBatch`) returns a **second** entry per series: the next episode after the most
recently played one by `LastPlayedDate` — but only among episodes the viewer has **already watched**
(`IsPlayedBy(userId)`), and none when that episode has a resume point (`includeResumable: false`). Its first entry
(the furthest-watched rule) is still returned too. So:

- if S03E15 was never watched (watching out of order, a new episode), the rewatch entry skips it to the next
  *watched* episode, or gives nothing;
- the caller still has to choose between two entries per series;
- the "last played" it follows includes a watched episode merely re-opened for a minute.

It is the right input (it is what we already fetch, below), but not the right answer. This phase computes the
answer itself from data the build already has, and keeps `/Shows/NextUp` (without the flag) only as the fallback for
a series with no finished episode to follow.

### What else Jellyfin does that matters here (v12.1 source)

- Starting playback sets `LastPlayedDate = now` and leaves `Played` as it was (`SessionManager.OnPlaybackStart`).
- A stop past `MaxResumePct` (90 %) sets `Played = true` and the position to 0; a stop between 5 % and 90 % keeps
  the position and does **not** clear `Played` (`UserDataManager.UpdatePlayState`). So a watched episode re-opened
  and left halfway reads *watched, with a position, last played now* — which R185 already refuses to show as
  in-progress.
- `getRecentlyPlayedAll` (`JellyfinClient.kt:899`, already fetched by the build) returns every watched episode, newest
  `DatePlayed` first, with `ParentIndexNumber`, `IndexNumber` and `PlaybackPositionTicks`. Nothing new needs fetching.

## Requirements

**FR-R375-1 — The last finished episode.** For a viewer and a series, the *last finished episode* is the episode with
the newest `LastPlayedDate` among that viewer's episodes of the series that:

- are **watched** (`Played`) with **no resume position** (position 0) — a watched episode re-opened and left partway
  keeps a position, so its last play was not a finish and it is skipped;
- **count** (R346): season 1 or later in our catalog, with a Jellyfin item. A special is skipped, so finishing a
  special never moves the position;
- are in our catalog for that series (matched by Jellyfin id, any row of a multi-episode file).

It is read from Jellyfin's data on every Continue build, from the watched list the build already fetches. Nothing is
stored.

**FR-R375-2 — The next episode in order.** The *next episode in order* is the episode after the last finished episode
as `MediaStore.nextEpisodeAfter` defines it: season then episode order, across seasons, specials never, missing
catalog rows (no Jellyfin item) never, the rest of a multi-episode file never (finishing the file S01E01–E03 offers
S01E04). It is offered **whether or not it is watched** — in a re-watch it usually is. There is none after the
series' last episode.

**FR-R375-3 — Which card Continue Watching shows** (replaces the next-up side of R219 §3). Per series, with *resume*
= R219's newest in-progress unwatched episode (unchanged, incl. R185) and *finish* = FR-R375-1:

| Resume | Last finished episode | Next in order (FR-R375-2) | Card |
|---|---|---|---|
| newer than the finish, or no finish | — | — | **resume** (as today) |
| older, or none | yes | yes | **next in order**, labelled `S03E15 · Title` |
| older | yes | none (the last episode was finished) | **resume** (the older one) |
| none | yes | none | **no entry** — the series leaves Continue Watching |
| — | none (nothing counted finished, or not in our catalog) | — | **today's rule with Jellyfin's Next Up** (unchanged) |

- If the next-in-order episode is itself in progress and unwatched, its card shows its progress bar like a resume
  card. Where it starts is unchanged (R306: a watched episode from 0:00, an unwatched one from its position).
- Membership (R219 §2) is unchanged except that *something left to watch* now also means *a next episode in order*.
  A fully watched series is in the list when its last finished episode is not its last episode.
- The order (R219 §4) is unchanged: the newest finish of any episode of the title, or the resume point's date.
- The entry's `episodeId` (R306 FR-R306-5, `continue_episode_id`) is the episode on the card, so the series page and
  the tile name the same episode. R343 FR-R343-13's held Start over episode still overrides it while held.

**FR-R375-4 — One resolver for every surface.** FR-R375-1–3 live in one pure server function used only by
`buildCanonicalContinueList`. The Home Continue row, every channel's Continue row, See all, the focus-detail line, the
Home tile's label and `continue_episode_id` on `/api/tv/playstate` all read its result through the one cached list;
none of them computes a next episode of its own. The function uses `nextEpisodeAfter` (moved to a pure top-level
function if needed), so the cast and Ravilo-screen next-up (R264) and Continue Watching use the same order.

**FR-R375-5 — The series page offers the same episode.** In `primaryEpisodeId` and the primary button:

- **Not finished:** unchanged — the server's `continue_episode_id` comes first, so the button reads
  *Resume · S03E15* as soon as the server is updated, on installed apps too.
- **Finished, and the server names a counted episode** (the viewer is re-watching and the last finished episode is
  not the last one): the button plays that episode and reads **Resume · S03E15**; the page opens on its season
  (R350 FR-R350-3) and its card carries the *UP NEXT* ribbon; the resume kicker names it. No `start_over` is sent:
  the series is not cleared, and Jellyfin's normal tracking continues. The *✓ All {n} episodes watched* line stays.
- **Finished, and the server names nothing:** unchanged — *Start over · S01E01* (R343 FR-R343-2).

**FR-R375-6 — A shuffle, or a replay that doesn't finish, does not move the position** (keeps R343's intent that a shuffled play leaves no trace on
the in-order state; see open question 1). Jellyfin moves an episode's `LastPlayedDate` when any play starts, so a
shuffled play would otherwise become *the last one played*. The server already keeps a per-session plan for a
shuffled entry (`SessionPlan`, `tv/SeriesReplay.kt`) and already puts its position back at an unfinished stop. It now
also keeps the episode's `LastPlayedDate` from before the play (read from the item detail `startPlayback` already
fetches) and, **after the stop has landed** (like FR-R343-4's write-back, so Jellyfin's own stop cannot overwrite it):

- if the play **did not finish** and there was a date, writes it back (`POST /UserItems/{id}/UserData`, the
  existing `setUserData`, gaining an optional `LastPlayedDate`);
- if the play **finished**, writes `min(prior date, anchor − 1 s)` — or `anchor − 1 s` with no prior date — where
  the anchor is the series' last finished episode (FR-R375-1, as the last Continue build saw it), keeping the tick a
  finished shuffled play earns (R343, R347). So the shuffled episode is never the newest finish, even if it was
  briefly opened after the anchor. With no finished episode at all, Jellyfin's date is left: the shuffled episode
  then becomes the anchor. That is accepted (owner, 2026-10-04): shuffle is for finished series, which always have
  one;
- if there was none and the play did **not** finish, nothing more: the episode stays unwatched with no position, so
  it can never be the last finished episode.

**The same write for a replay that doesn't finish** (owner, 2026-10-04, dev review item 3): when any Ravilo play
of an episode that was **watched** at its start stops without finishing, its prior `LastPlayedDate` is written back
the same way. Below 5 % Jellyfin keeps `Played` and zeroes the position, so without this a mis-started watched
episode would read as the newest finish. Plays from other apps still move it.

The write runs in the Jellyfin sink after the stop lands and **before** the stop's refresh (`onStopLanded`), so
the rebuilt Continue list never shows the moved position. A rail pick that leaves the shuffle (R343 FR-R343-5) is an
ordinary play: if it finishes, it moves the position.

Side effects, accepted: R219 §4 orders the series by the restored date, the touched window (§2(c)) doesn't see the
play, and Users & devices' *Recently watched* and Jellyfin's own history don't list it. `PlayCount` still counts it.

**FR-R375-7 — Backwards compatible.** No DTO field is added or removed; `SeriesProgress` stays unfilled (R343 dev
review item 1). An installed app gets FR-R375-3 with the server alone (the tile and, on an unfinished series, the
page). Only the finished-series page (FR-R375-5's second bullet) needs the new app; an older app keeps showing *Start
over · S01E01* there while its Continue tile names S03E15.

## Acceptance

On a **test account** (never the household's own progress):

1. A series watched to S06E06 (S06E07 with an old resume point) and in progress at S03E14: finish S03E14. Home's
   Continue tile, a channel's Continue row, See all and the series page all offer **S03E15** (*Resume · S03E15*).
2. Start S03E15, stop at 20 %: everything offers *Resume · S03E15* with its progress. Finish it: everything offers
   S03E16.
3. Finish the last episode of season 3: S04E01 is offered.
4. Finish a special: the offer does not move.
5. Finish a multi-episode file S01E01–E03: S01E04 is offered.
6. A fully watched series: play S02E03 from its card to the end. Continue Watching shows S02E04 and the page reads
   *Resume · S02E04* (not *Start over*). Then finish the series' last episode: it leaves Continue Watching and the
   page reads *Start over · S01E01*.
7. Re-open a watched episode and stop at 40 %: the offer stays on the episode after the last finished one.
8. Shuffle a series and finish two entries: the offer is what it was before the shuffle.
8a. Start a watched episode and stop after 20 s (under 5 %): the offer does not move.
9. A series with nothing finished but touched (stopped under 5 % of S01E01): unchanged from today.

## Tests

**Pure resolver** (`src/linuxX64Test/.../tv/ContinueTargetTest.kt`, plain data in the style of `StartOverHoldsTest`
and `SeriesReplayTest`; catalog `MediaItem`s built in code, Jellyfin items as `JellyfinPlayItem`):

1. The sweep's case: S03E14 finished today, S06E06 finished in August, S06E07 resume in August → next card on S03E15
   with label `S03E15 · …`, `episodeId` = S03E15.
2. Resume newer than the last finish → resume card (R219's first row unchanged).
3. The newest watched item is a special → skipped; the anchor is the newest counted one.
4. The newest watched counted episode has a position (re-opened, left partway) → skipped.
5. A multi-episode file S01E01–E03 finished → S01E04; a file S01E04–E06 as the next → one card labelled
   `S01E04–E06 · …`.
6. The last episode of a season → the first episode of the next season; the series' last episode → no next: an older
   resume card if there is one, else no entry.
7. A catalog row with no Jellyfin item between the anchor and the next → skipped.
8. Anchor not in the catalog, or nothing finished → Jellyfin's Next Up card exactly as today (a regression test of
   R219's table rows).
9. The next is in progress and unwatched → the card carries its progress.
10. A fully watched series with an anchor in the middle → an entry; with the anchor at the end → none.

**Server, wider:**
- `HomeFeedService` with a stub Jellyfin (the four fetches) and a catalog: the canonical list, a channel-filtered
  view, See all and `continueEpisodes(device)` all name S03E15 for test 1's data; `playstateAnswer` lays it on the
  series as `continue_episode_id`, and a `StartOverHolds` hold still wins.
- `SeriesReplayTest` / `PlaybackWriterTest`: a shuffled stop carries the prior `LastPlayedDate` (restored after the
  stop lands); none + finished → anchor − 1 s; none + not finished → no date write; a Start over stop is unchanged.
- `JellyfinClient.setUserData` body: `LastPlayedDate` only when given (existing callers' bodies byte-identical).
- `WireCompatTest` unchanged (no DTO change).

**Client** (`ravilo-ui`):
- `SeriesEpisodesTest`: finished + `continueEpisodeId` naming a counted episode → `primaryEpisodeId` returns it; finished
  without one → the first counted episode (R343); unfinished unchanged.
- `SeriesDetailFocusTest` (Robolectric, TV size): a finished series with `continue_episode_id` = S03E15 opens on
  Season 3, the button reads *Resume · S03E15*, OK plays S03E15 without `start_over`; the existing finished walk
  (*Start over*, *All 39 episodes watched*) still passes.

**Only a device confirms** (Pixel 9 or the Mac, against a deployed backend, test account): acceptance 1–8 end to end,
including that Jellyfin's own stop does not overwrite FR-R375-6's restored date, and that a TV's Continue row follows
within one refresh after a stop.

## Owner decisions (2026-10-04)

1. **Shuffle doesn't move it (FR-R375-6 as written).** After a shuffled play stops, the server writes the episode's
   previous last-played date back, so Continue Watching keeps following the in-order viewing.
2. **Old series coming back: accepted.** A fully watched series whose last finished episode isn't its last rejoins
   Continue Watching however long ago that was. Before building, count on the prod DB copy how many series that adds;
   only if it is many, ask the owner about a time window.

## Dev review (2026-10-04, against `main` `4ae4f169`)

Read against `HomeFeedService.buildCanonicalContinueList`, `MediaStore.nextEpisodeAfter`, `EpisodeSpan.kt`,
`PlaybackService` (start, stop, the Jellyfin sink), `SeriesReplay.kt`, `JellyfinClient` (`getNextUp`,
`getRecentlyPlayedAll`, `setUserData`, `markPlayed`), `SeriesEpisodes.kt` and `SeriesDetailScreen.kt`, and against
Jellyfin's own source at tag `v12.1` (`SessionManager`, `UserDataManager`, `BaseItem.MarkPlayed`,
`TvShowsController`). The design holds. Ten items, two for the owner.

1. **The Jellyfin facts hold, and FR-R375-6's write works.** `OnPlaybackStart` sets `LastPlayedDate = now` and
   touches `Played` only for items that can't resume; `UpdatePlayState` is as described. `SaveUserData(…,
   UpdateUserItemDataDto, …)` applies `LastPlayedDate` when it is sent, so `setUserData` only needs an optional
   field (absent ⇒ today's body, byte for byte). `BaseItem.MarkPlayed` with no date (our `markPlayed` sends none)
   keeps the existing `LastPlayedDate`, so R347's tick never moves a restored date, whether it lands before or after
   the restore.

2. **Owner decision 2's count is nearly moot.** `getNextUp` sends no `nextUpDateCutoff`, and Jellyfin then uses
   `DateTime.MinValue`, so today's Continue Watching already has no time window: an abandoned series from years ago
   is already there. The only series R375 *adds* are ones Jellyfin's Next Up has nothing for (the furthest watched
   episode is the last one), but whose newest finish is an earlier episode, i.e. a finished series with one older
   episode watched again later. Keep the quick count before building, but expect a handful.

3. **For the owner: a watched episode re-opened for a few seconds counts as a finish.** Below 5 % (`MinResumePct`)
   Jellyfin zeroes the position and leaves `Played` as it was. So a watched S06E06, started by mistake and left
   after 20 s, reads *watched, no position, last played now*, and FR-R375-1 takes it as the last finished episode.
   Continue Watching then jumps to S06E07, which is the very jump this phase removes. Acceptance 7 (stop at 40 %)
   doesn't catch it, because 40 % keeps a position. **Lean: widen FR-R375-6 to every Ravilo play of an
   already-watched episode that doesn't finish:** after the stop lands, put its `LastPlayedDate` back. It's the same
   write as the shuffle's and the same place. Plays from other apps (Jellyfin web) still move it.

4. **For the owner: a shuffle on a series with nothing finished.** FR-R375-6's last case leaves Jellyfin's date on
   a finished shuffled episode when no counted episode was finished before. That episode is then the *only*
   finished one, so it becomes the anchor: shuffle a never-watched series, finish S02E05, and Continue Watching
   offers S02E06. No date can stop this, because any finished episode is an anchor whatever its date. Only a record
   of our own could, and the spec rules out a table. **Lean: accept and say so in FR-R375-6.** Shuffle is for
   finished series (R343), and those always have an anchor.

5. **Ties and precision.** `isoToEpochSeconds` drops the fractions, and marking a season or series watched
   (`MarkPlayed`, no date given) stamps every never-played episode with the same second. The anchor then depends on
   list order. Compare the full timestamp, and break a tie by episode order, latest wins, so marking a season watched
   offers the next season's first episode. Pick the anchor with a max over the series' items, not "first one seen":
   R198 says never to trust Jellyfin's sort, and today's `lastFinishedByKey` (`HomeFeedService.kt:1012`) still
   trusts it. Fix both in the same change.

6. **Where the resolver lands (FR-R375-4).** The pure `ContinueTarget.kt` takes the series `MediaItem`, that
   series' watched items from `finishedItems`, and **`resumeItems` indexed by episode id**. FR-R375-3's "next is in
   progress ⇒ progress bar" needs the next episode's own resume, and `resumeByKey` keeps only the newest per series,
   which is often a different episode (S06E07). Two more changes in the build:
   - Membership (`candidateKeys`, `:1063`) must also admit a key with a next-in-order episode, or a finished series
     never gets in (FR-R375-3's §2 note).
   - The card's label comes from the catalog (`Episode.title`, as `nextEpisodeAfter` does), because Jellyfin's
     `play.name` isn't available for an episode Next Up didn't return. Season and episode come from
     `resolvedEpisodeSpan(series, next.jellyfinId, null, null)`.

   Move `nextEpisodeAfter` to a top-level pure function in `EpisodeSpan.kt`. Its private `fileEnd` repeats what
   `resolvedEpisodeSpan` already computes, so it can reuse that.

7. **FR-R375-6 has to be sequenced in the sink, and needs the anchor at start.**
   - The restore goes in the Jellyfin sink's `stop` after `postPlaybackStopped` succeeds and **before**
     `onStopLanded` (`PlaybackService.kt:452–466`, as `startOverUnplayed` already does). Otherwise
     `invalidatePlaystate` rebuilds Continue Watching with the shuffled episode as the anchor, and it flips back one
     refresh later.
   - Carry the date on `PendingWrite` beside `startOverUnplayed`, and keep it through `enqueue`'s STOP merge.
   - The prior date is in the `saved` user data `startPlayback` already reads.
   - "One second before the anchor" needs the anchor's date at start, which only the Continue build computes. Have
     the build keep an in-memory `anchorDateBySeries` per user next to the cached list, and read that. It can be one
     refresh stale, which doesn't matter here.
   - Make the finished case one rule: write `min(prior, anchor − 1 s)`, or `anchor − 1 s` with no prior. An
     unwatched episode briefly opened *after* the last finish has a prior date newer than the anchor; restored with
     its tick, it would otherwise become the anchor.
   - A backend restart mid-play forgets `SessionPlan` (the shipped bug from the R368 review), so that play moves the
     anchor. That's a known gap; nothing new to do here.

8. **What rewriting dates also changes.** These are all acceptable, but FR-R375-6 should say them:
   - R219 §4 orders by the restored date, so a series you just shuffled doesn't rise to the front of Continue
     Watching.
   - The touched window (§2(c)) doesn't see the play.
   - Users & devices' *Recently watched* (phase 143) and Jellyfin's own history don't list it.
   - `PlayCount` (phase 269) still counts it, since we never send `PlayCount`.

9. **Client: one pure helper, not a second `finished` flag.** Add `rewatchEpisodeId(detail, overlay)` to
   `SeriesEpisodes.kt`: the server's `continueEpisodeId` when the series is finished and the id is a counted
   episode, otherwise null. `primaryEpisodeId` returns it first in the finished case. In `SeriesDetailScreen`, the
   kicker (`:616`), `hasResume` and `startOverNow` (`:756–760`) read `finished && rewatch == null`. The *All {n}
   episodes watched* line, Reset progress and the Shuffle pill keep plain `finished`. The *UP NEXT* ribbon
   (`isResumeEpisode = ep.id == resumeEpId`, `:943`) and R350's opening season both follow the primary id, so they
   need nothing. Start over stays one step away (Reset progress, or S01E01 on the rail).

10. **Tests to add** to the spec's list:
    - Resolver: same-second ties resolve to the later episode in order; the next episode's own progress comes from
      the per-episode resume map, not the series' newest.
    - Resolver: a finished series with an anchor mid-way enters the list (the membership change).
    - Sink: the restore lands before `onStopLanded` fires, and an R347 tick plus a restore leave the restored date.
    - `min(prior, anchor − 1 s)` for the finished-shuffle case.
    - Item 3's under-5 % re-open, if the owner takes the lean.

## Owner decisions (2026-10-04, after the dev review)

1. **Item 3 — a replay that doesn't finish puts the date back.** Any Ravilo play of an already-watched episode that
   stops before the end restores its prior `LastPlayedDate` (FR-R375-6, acceptance 8a).
2. **Item 4 — a shuffle on a series with nothing finished: accepted.** The finished shuffled episode becomes the
   anchor; no table of our own.

## Dev notes — where it lands

- **Server:** a new pure `tv/ContinueTarget.kt` (FR-R375-1–3) called from `buildCanonicalContinueList`
  (`HomeFeedService.kt:1000–1081`): keep the per-series watched items from `finishedItems` (already fetched), pick
  the anchor, call `nextEpisodeAfter`, and replace the `NextUpCandidate` choice in the `when` at `:1069`. `getNextUp`
  stays (no `enableRewatching`) for series with no anchor. `ContinueEntry.episodeId` carries the chosen episode, so
  `continueEpisodes` and `/api/tv/playstate` need no change.
- **Shuffle:** `SessionPlan` gains `priorLastPlayed` (and the anchor's date, for the none case); `PlaybackWriter`'s
  after-stop step writes it, as it does `startOverUnplayed`; `JellyfinClient.setUserData` gains an optional
  `lastPlayedDate`.
- **Client:** `SeriesEpisodes.kt:54` `primaryEpisodeId` and `SeriesDetailScreen.kt` (`:616` the kicker's `!finished`
  gate, `:756–760` `hasResume` / `startOverNow`). Phone, desktop and TV share this composable.
- **Not touched:** the player's next-up card and auto-advance (already in order within a season); the mockups.

## Build notes (2026-10-04)

**Server**
- **`tv/ContinueTarget.kt` (new, pure):** `planContinue(library, resume, nextUp, finished, touched)` holds R219 §2–§4 and
  FR-R375-1–3 in one place, and `buildCanonicalContinueList` now only fetches, calls it and makes the cards (FR-R375-4).
  `lastFinishedEpisode(series, watched)` is the anchor: watched, position 0, counted (season 1+, a Jellyfin item, in the
  catalog), the newest `LastPlayedDate` at full precision (`isoToEpochTicks`, 100 ns), a tie to the later episode in
  order, a max over the items (dev review item 5). With an anchor, Jellyfin's Next Up is not consulted: resume newer
  than the anchor → resume card; else the next in order (label `S03E15 · {catalog title}`, or a resume-style card with
  its own progress when that episode is in progress and unwatched, from a per-episode resume map — item 6); no next →
  the older resume, else no entry. Without an anchor, R219's rule with Next Up is unchanged (and tested). Membership
  admits a key with a next in order. `lastFinishedByKey` / `lastTouchedByKey` are maxes now, not "first seen" (R198).
- **`nextEpisodeAfter`** moved to a top-level pure function in `tv/EpisodeSpan.kt` (with `countedEpisodesInOrder`), its
  kicker from `resolvedEpisodeSpan`; `MediaStore.playPushFor` calls it.
- **FR-R375-6 + owner decision 1:** `SessionPlan` gained `priorLastPlayed`, `watchedAtStart` (episodes only) and
  `anchorLastPlayed` (a shuffle's series anchor, read at start from `HomeFeedService.anchorDate`, the in-memory
  `anchorDateBySeries` per user that each trusted Continue build writes; wired in `Main.kt` as
  `playbackService.anchorDateFor`). Pure `lastPlayedRestore(plan, finished)` (`SeriesReplay.kt`): not finished and
  (shuffled or watched at start) → the prior date (none ⇒ nothing); finished and shuffled → `min(prior, anchor − 1 s)`
  or `anchor − 1 s`; no anchor ⇒ Jellyfin's date stays (owner decision 2); a Start over stop that cleared ⇒ none. The
  date rides `PendingWrite.restoreLastPlayed` (kept through `enqueue`'s STOP merge) and the Jellyfin sink writes it
  after `postPlaybackStopped` succeeds and before `onStopLanded` (the inline no-writer path writes it after its stop
  too). `JellyfinClient.setUserData` takes nullable `played`/`positionTicks` and an optional `lastPlayedDate`; the body
  (`userDataBody`) carries only the fields given, so R343's body is byte-identical and the restore is a date-only write.
- **Owner decision 2 (count first):** not counted. The prod DB holds our catalog, but every viewer's played/last-played
  data lives in Jellyfin, so a count needs Jellyfin API calls, which this pass doesn't make. Per dev review item 2 the
  addition is small: `getNextUp` sends no `nextUpDateCutoff`, so Continue Watching already has no time window; R375
  adds only finished series whose newest finish is an earlier episode.

**Client** (`ravilo-ui`): `rewatchEpisodeId(detail, overlay)` in `SeriesEpisodes.kt` (finished + the server's
`continueEpisodeId` naming a counted episode); `primaryEpisodeId` returns it first on a finished series. In
`SeriesDetailScreen`, the kicker gate, `hasResume` and `startOverNow` read `startOverMode = finished && rewatch == null`;
*All {n} episodes watched*, Reset progress and Shuffle keep plain `finished`. The opening season, the rail and *UP
NEXT* follow the primary id. No DTO change (FR-R375-7).

**Tests:** `ContinueTargetTest` (the spec's ten plus ties, fractions, an untrusted sort, the date helpers and the
user-data body), `SeriesReplayTest` (`lastPlayedRestore`: seven cases incl. `min(prior, anchor − 1 s)` and the under-5 %
replay), `PlaybackWriterTest` (the date survives a STOP merge and a straggling tick); `SeriesEpisodesTest` (three R375
cases) and `SeriesDetailFocusTest` (a finished 3 × 13 series naming S03E10 opens on Season 3, reads *Resume · S03E10*,
keeps *All 39 episodes watched*, plays S03E10 without `start_over`; the existing *Start over* walk still passes).
**Not unit-tested:** the sink's order (restore before `onStopLanded`) and Jellyfin keeping a restored date through
R347's tick — `JellyfinSink` needs a live `JellyfinClient` (no `MockEngine` in `linuxX64Test`); the order is one
`if` above the hook in `PlaybackService.JellyfinSink.stop`.

**Only a device confirms** (Pixel 9 or the Mac, test account, deployed backend): acceptance 1–8a end to end, that
Jellyfin 12.1 applies the date-only `UserData` write and its own stop doesn't overwrite it, and that a TV's Continue
row follows within one refresh. A backend restart mid-play forgets `SessionPlan`, so that play moves the anchor (known
gap, dev review item 7).
