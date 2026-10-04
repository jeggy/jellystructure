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

**FR-R375-6 — A shuffle does not move the position** (keeps R343's intent that a shuffled play leaves no trace on
the in-order state; see open question 1). Jellyfin moves an episode's `LastPlayedDate` when any play starts, so a
shuffled play would otherwise become *the last one played*. The server already keeps a per-session plan for a
shuffled entry (`SessionPlan`, `tv/SeriesReplay.kt`) and already puts its position back at an unfinished stop. It now
also keeps the episode's `LastPlayedDate` from before the play (read from the item detail `startPlayback` already
fetches) and, **after the stop has landed** (like FR-R343-4's write-back, so Jellyfin's own stop cannot overwrite it):

- if there was a date, writes it back (`POST /UserItems/{id}/UserData`, the existing `setUserData`, gaining an
  optional `LastPlayedDate`), keeping the tick a finished shuffled play earns (R343, R347);
- if there was none and the play **finished**, writes a date one second before the series' last finished episode
  (FR-R375-1, read at the shuffle's start), so the shuffled episode is never the newest; with no finished episode at
  all, Jellyfin's date is left (there is no position to keep);
- if there was none and the play did **not** finish, nothing more: the episode stays unwatched with no position, so
  it can never be the last finished episode.

Then the stop's usual refresh rebuilds the Continue list. A rail pick that leaves the shuffle (R343 FR-R343-5) is an
ordinary play and does move the position.

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
