# A newest-first series lists and plays its latest episode

Task: DEB-2916

> Owner, 2026-10-29 (DEB-2916): *"mark our series as something where only the newest episodes are relevant, basically
> making the sorting within Ravilo reversed. So that the newest episode is listed first and when you click play, it
> would play the newest instead of the one that is currently marked as 'Next up'."*
>
> After the design round, 20:07Z: *"Lets go with A, but the next up should not be shown anymore and when nearing the
> end where the usual skip credits/autoplay would happen, we would also skip that, so there is no next episode even if
> we haven't seen the episode from yesterday etc."*

The investigation is the `investigation` document on DEB-2916, and the round is
`design/incoming/2026-10-29-newest-first/`, from DEB-2917. This spec uses the round's stand-in programme, *Dagbók*: a
daily programme, Season 2026, numbered by air date (`S2026E302` = Thu 29 Oct), with 12 episodes kept.

## Scope

A series can be marked **newest first**. It is one switch per series in the admin, and jellystructure turns it on by
itself for a series that an Auto-fetch numbers by air date. On a marked series, for every viewer:
- the episodes are listed newest first and labelled by air date;
- **Play** plays the newest episode;
- Continue Watching only ever offers the newest;
- **nothing follows an episode**: no *Up next*, no autoplay and no credits card. The episode plays to its end and the
  viewer is back on the series page.

The work spans both trees: the server, the admin series page, and the Ravilo series page, player and Continue tile.

**Amends**, each with a dated note in its own file:
- [R375](phase-R375-next-up-follows-the-last-episode-played.md) FR-R375-3 and FR-R375-5: a marked series takes
  FR-6 and FR-10 below instead.
- [R343](phase-R343-reset-progress-and-shuffle-on-a-series.md) FR-R343-2 and FR-R343-5: a marked series has no
  *Start over* and no *Shuffle*.
- [R182](phase-R182-skip-intro-credits.md) FR-RV-SKIP1-2: a marked series has no credits card.

It takes up what Auto-fetch left out (`specs/requirements/auto-fetch-…`, FR-4.5 and its *Non-goals*: *"Showing the air
date in Ravilo instead of the code would be a Ravilo spec of its own"*). That file is unchanged.

## Owner decisions (2026-10-29, DEB-2916)

1. **A: an admin switch per series**, turned on by itself for a date-numbered Auto-fetch series. Not the viewer's own
   sort control (C), not automatic-only (B), not a tag (D).
2. **Nothing follows an episode** (comment `bb210d1b`). There is no *Up next*, no autoplay, no *Skip credits* and no
   *Watch credits*, even when yesterday's episode is unwatched.
3. **Play and labels are as drawn in A.** Play plays the newest, and replays it when it is already watched. Episodes
   are labelled by the air date alone. These were the card's recommendations, and the owner took A as drawn. The
   reading was confirmed on the thread before this spec was written.

## What is true today (verified 2026-10-29, `main` `872dbcb1`)

- **Order.** The server sends seasons ascending with Specials last (`tv/DetailService.kt:139`) and episodes ascending
  (`:149`). The client keeps that order.
- **Play.** `primaryEpisodeId` (`ravilo-ui/…/screens/SeriesEpisodes.kt:66-75`) takes the server's `continue_episode_id`
  first, then an episode in progress, then the first unwatched counted episode. On a never-started *Dagbók*, that is
  the **oldest** of the 12.
- **Continue Watching.** `planContinue` (`tv/ContinueTarget.kt`) offers the episode after the last finished one, in
  ascending order (R375). After today's episode, nothing follows, and the series leaves the row.
- **The Continue tile's badge** is drawn by the client from the card's season and episode numbers
  (`HomeScreen.kt:710-711`, `episodeCode()`), so it reads `S2026E302`. `next_up_label` is server text and shows only
  in the focus-detail line and on channel rows.
- **`continue_episode_id`** (`server/routes/TvRoutes.kt:1680-1690`) is read by the client only as the series' pointer.
  It sets:
  - the Play target and its label;
  - the *UP NEXT* ribbon;
  - the rail's opening slot;
  - the resume kicker;
  - a finished series' re-watch target.

  No client reads it as "this series is in Continue Watching". Start-over holds already set it outside the row
  (`TvRoutes.kt:1671`).
- **The player.** It gets its episodes from the series page (`buildEpisodeContext`, `SeriesDetailScreen.kt:236-258`,
  the current season only). A Continue tile opens the series page first (`RaviloApp.kt:969`, `:2427`). The credits
  card is chosen at `PlayerScreenState.kt:270-276`:
  - *Up next* with a countdown when there is a next episode;
  - otherwise *Skip credits*, which exits the player (`:385-389`).

  Auto-advance walks `dest.episodes` (`RaviloApp.kt:2940-2950`).
- **Elsewhere.**
  - A push or remote play takes its next episode from `MediaStore.resolvePlayPush` → `nextEpisodeAfter`
    (`MediaStore.kt:682-699`, `tv/EpisodeSpan.kt:51-60`).
  - The Cast receiver takes the sender's list (`Receiver.kt:1333-1336`).
  - The Tizen app is `ravilo-screen`, which is receiver-only and does not use `ravilo-ui`.
- **What the server sets per series.** `SeriesDetail` carries `start_over` and `shuffle` (`Models.kt:981`, `:984`).
  `shuffle=false` hides Shuffle on installed apps. Episodes already carry `air_date` (`Models.kt:802`).
- **Storage.** `media` stores each `MediaItem` as JSON, so a nullable field needs no migration (as `metadataLanguage`
  did). A rescan rebuilds the item, so an operator's field must be carried over, as `lockedArtwork` is
  (`ArtworkLock.kt:30`).
- **Auto-fetch.** A page-only fetcher writes `autofetch_series_mark(series_path, fetcher_id)`, where `series_path`
  equals `MediaItem.path`. The fetcher's recipe says `numbering.kind == "date"` (`autofetch/Recipe.kt:83-86`).
- **Strings.** `date.weekday_day_month`, `date.month_day_year`, `wd.abbr.*`, `month.*`, `browse.sort.newest`,
  `up.new_episode`, `tile.badge_new` and `action.resume` all ship in en, da and fo (`i18n/*.json`).

## Requirements

### Server

**FR-1 — The mark.**
- `MediaItem` gains `newestFirst: Boolean?`:
  - null means *automatic*;
  - true or false is the admin's choice.
- A rescan carries it over, as it does `lockedArtwork`.
- A series is **newest first** when:
  - the admin set it on; or
  - it is null **and** the series is fed by an Auto-fetch whose recipe numbering is `date`. "Fed by" means an
    `autofetch_series_mark` row whose `series_path` is the item's path, and whose fetcher's recipe has
    `numbering.kind == "date"`.
- Only a TV series can be newest first.
- One server function answers the question. Every rule below calls it, and none decides on its own.

**FR-2 — The admin's switch on the wire.**
- `GET /api/media/{id}` for a TV series gains:
  - `newest_first`, the effective value from FR-1;
  - `newest_first_source`: `"auto"` (null, decided by Auto-fetch), `"admin"`, or `"off"` (null and not fed by a
    date-numbered fetcher).
- `PUT /api/media/{id}/episode-order` with `{"newest_first": true|false}` stores the admin's choice:
  - it records a media-history line;
  - it answers with the same two fields;
  - it refuses anything that is not a TV series.
- There is no route back to automatic. Once the admin has chosen, Auto-fetch never changes it.

**FR-3 — The series detail.** `SeriesDetail` gains `newest_first: Boolean = false`. On a newest-first series:
- `shuffle` is false, so installed apps hide Shuffle too;
- the wire order of seasons and episodes is **unchanged**: ascending, Specials last. An installed app keeps today's
  list, and a new app reverses it (FR-8).

**FR-4 — The newest episode.**
- It is the last counted episode (R346: season 1 or later, with a Jellyfin item) in season-then-episode order.
- For a multi-episode file, it is the file's first part.
- On a date-numbered series this is the latest air date. The same order covers a series the admin marks by hand.

**FR-5 — What Play plays, for every viewer.**
- On a newest-first series, `continue_episode_id` on `/api/tv/playstate` is **the newest episode**:
  - for every viewer;
  - whether or not the series is in that viewer's Continue Watching;
  - whether the newest is watched, unwatched or in progress.
- An installed app then plays the newest, labelled with its code, on both an unfinished and a finished series (R375's
  re-watch path).

**FR-6 — Continue Watching** (replaces R375 FR-R375-3 for a newest-first series).
- **Membership** is R219's *genuinely started* rule, unchanged: a resume point, a finished episode or a touch.
- **The card**, for a started series:

  | The newest episode | Card |
  |---|---|
  | in progress (unwatched, with a position) | **resume** on it, with its progress |
  | unwatched, with no position | **new** on it, with no progress |
  | watched | **no entry**: the series leaves the row until a newer episode arrives |

- An older episode never makes a card, whether it is in progress or not. It can still be resumed from its own card on
  the series page.
- **Order** (R219 §4): the card's activity time is the later of:
  - the viewer's last activity on the series;
  - the newest episode's arrival in the library (the catalog episode's created time).

  A new episode therefore brings the series to the front of the row.
- **On the wire:**
  - The card keeps its season and episode numbers, so an installed app keeps its code badge.
  - `next_up_label` is the episode's title alone, with no code.
  - `MediaCard` gains `episode_air_date: String? = null` (`yyyy-MM-dd`). It is set only on a newest-first series'
    Continue card, and only when the episode has an air date.

**FR-7 — No next episode on the server.** For a newest-first series:
- `resolvePlayPush` sends no next episode, so neither *Play on a TV*, the remote nor `ravilo-screen` advances;
- FR-6 never computes a next in order.

### Ravilo: the series page (TV family and phone; one `SeriesDetailScreen`)

**FR-8 — Order.**
- When `newest_first` is true, the page lists the seasons newest first, with Specials still last, and each season's
  episodes newest first.
- It opens on the season holding the newest episode, with the rail on the newest (its first slot).

**FR-9 — Labels by air date.**
- On a newest-first series, an episode with an `air_date` is labelled by that date wherever a viewer would see its
  code:
  - the rail or list card's main label, with the episode's title as the second line;
  - the primary button;
  - the resume kicker;
  - the player's title line;
  - the Continue tile (FR-15).
- **The format** is `date.weekday_day_month` with `wd.abbr.*` and `month.*` (*Thu 29 Oct*). A date outside the
  current calendar year uses `date.month_day_year`, so New Year never shows two different *29 Oct*s alike.
- An episode with no `air_date` keeps its code.
- A series that is not newest first is unchanged.

**FR-10 — The primary button** (replaces R375 FR-R375-5 for a newest-first series).
- It always plays **the newest episode** (FR-4), from the client's own list, and never an older one.
- It reads:
  - **Resume · Thu 29 Oct** (`action.resume`) when the newest is in progress;
  - **Play newest · Thu 29 Oct** (`detail.play_newest`) otherwise, including when the newest is watched, which then
    plays it again from the start.

**FR-11 — What the page leaves out** on a newest-first series:
- *Shuffle* (FR-3);
- *Start over*;
- every watched count: the header's *{w} of {n} episodes watched*, the per-season *x / n*, and *All {n} episodes
  watched*;
- the *UP NEXT* ribbon.

Each card keeps its own ✓ when watched.

**FR-12 — What the page adds** on a newest-first series:
- **The mark.**
  - A *Newest first* pill on the hero (`browse.sort.newest`).
  - Its accessible label is `detail.newest_first_a11y`.
  - Its glyph is a sort-descending glyph, **not** a downward arrow onto a line: the round's arrow reads as
    *download*.
- **The state pill** beside it:
  - *New Episode* (`up.new_episode`) when the newest is unwatched **and** an older episode is watched;
  - *Caught up* (`detail.caught_up`, after a drawn ✓) when the newest is watched;
  - nothing otherwise, which covers a never-started series and the newest in progress.
- **The *NEW* ribbon** (`tile.badge_new`) on the newest episode's card, under the same condition as *New Episode*.
  When nothing is watched yet, every episode is equally new, so the ribbon would mark an arbitrary card.

### Ravilo: the player, casting and the Continue tile

**FR-13 — Nothing follows an episode** (owner decision 2). In a play of a newest-first series:
- the player has **no next episode**:
  - no *Up next* card;
  - no autoplay;
  - no *Next* control;
  - no move to another episode at the end;
- there is **no credits card at all**: no *Next Episode*, no *Skip credits*, no *Watch credits*, and no *End of
  episode* card;
- the episode plays to its end, and the player then closes back to the series page with the newest episode focused
  (R350 / R365's return target);
- *Skip intro* is unchanged;
- R347 is unchanged: an episode left at its credits still counts as watched;
- the player's own episode rail (R208), where shown, lists the episodes newest first, and picking one there still
  plays it.

**FR-14 — Casting.** Casting a newest-first episode from the app sends that episode alone as the queue, so the
receiver has nothing to advance to. The receiver itself is unchanged.

**FR-15 — The Continue tile.**
- When a card carries `episode_air_date`, its corner badge reads:
  - **New · Thu 29 Oct** (`tile.new_on`) when the card has no progress;
  - **Thu 29 Oct** with the progress bar when it has progress.
- In both cases the badge replaces the code.
- The tile's accessible label carries the same words.

**FR-16 — Strings.** Four new keys in en, da and fo. The da and fo texts are drafts from the round, and the shipped
table wins:

| key | en | fo (draft) | da (draft) |
|---|---|---|---|
| `detail.play_newest` | Play newest | Spæl nýggjasta | Afspil nyeste |
| `detail.caught_up` | Caught up | Dagført | Ajour |
| `detail.newest_first_a11y` | Newest first — this series lists its newest episode first | Nýggjast fyrst — henda røðin vísir nýggjasta partin fyrst | Nyeste først — denne serie viser det nyeste afsnit først |
| `tile.new_on` | New · {date} | Nýtt · {date} | Ny · {date} |

The button's ` · {date}` follows the shipped pattern of *Play · S01E05*.

### Admin

**FR-17 — Episode order on the series page.**
- **The control.**
  - The admin's series page gains an **Episode order** control at the top of the episodes card, in the shipped
    `.seg`: *Oldest first* / *Newest first*.
  - Under it: *How Ravilo lists this series' episodes, and what **Play** starts.*
  - It shows the effective value from FR-2.
- **The note beside it:**
  - with `newest_first_source == "auto"`: *Set by Auto-fetch: numbered by air date.*;
  - with `"admin"`: *Set by hand. Auto-fetch will not change it.*;
  - with `"off"`: none.
- **Saving.** A click sends FR-2's PUT, and the control re-renders from the answer. The admin renders server-pushed
  state only.
- The admin has no string table, so its words are English literals (as DEB-2657's FR-13).

### Compatibility

**FR-18 — Backwards compatible.** The wire only gains fields: `SeriesDetail.newest_first`, `MediaCard.episode_air_date`
and FR-2's two admin fields. Nothing is removed or retyped, and `WireCompatTest` stays green.

**An installed app gets, from the server alone:**
- Play plays the newest (labelled with its code);
- no Shuffle;
- Continue Watching follows FR-6, with the code badge;
- *Play on a TV* and the remote have no next episode.

**It does not get:**
- the newest-first order;
- the date labels;
- FR-11's other removals;
- FR-13 in its own player.

Playing the newest there gives no *Up next*, because it is the last episode of its season, but an old player still
shows its *End of episode* card. Playing an older episode on an old app can still autoplay forward. Both end with the
update.

## Design

The round is `design/incoming/2026-10-29-newest-first/` (DEB-2917), direction **A**. The frames to build from:

| Frame | What it shows |
|---|---|
| `newest-a-tv-s1` … `s4` | The TV page in the four viewer states |
| `newest-a-phone-s1`, `-s2` | The phone page |
| `newest-a-home-tv-h1` … `h3`, `newest-a-home-ph-h1` … `h3` | The Continue tile: new, half-watched, not in the row |
| `newest-a-player-end`, `newest-a-player-land` | The end of an episode, and the page it lands on |
| `newest-a-admin-newest`, `-oldest`, with `-light` | The admin switch in both themes |

The round's *Up next* frame (`newest-b-player-upnext`), its C frames and its dashed-bar tile (`h1-alt`) were declined.
The glyph on the hero mark changes (FR-12).

## Non-goals

- A viewer's own sort control (direction C), or a per-viewer choice of any kind.
- "Newest" by air date across a series whose numbering disagrees with its dates. FR-4 uses the numbering.
- Resuming an older half-watched episode from Continue Watching (FR-6).
- Any change to Auto-fetch, its numbering or its retention.
- Any change to the Cast receiver's or `ravilo-screen`'s own screens. They show what they are sent.
- A route back to *automatic* once the admin has chosen.

## Acceptance

On *Dagbók* (12 episodes, `S2026E291` to `S2026E302`, fed by a date-numbered page-only fetcher, `newestFirst` null),
with a new app:

1. The admin page shows *Episode order: Newest first* and *Set by Auto-fetch: numbered by air date.*
2. **Never started.**
   - The TV page lists *Thu 29 Oct* first and opens on it.
   - The button reads *Play newest · Thu 29 Oct* and plays `S2026E302`.
   - There is no Shuffle, no watched count, no state pill and no *NEW* ribbon.
3. **Watched up to Wed 28 Oct.**
   - The page shows *New Episode*, with *NEW* on Thu 29 Oct.
   - Home's Continue row has *Dagbók* first, with *New · Thu 29 Oct*.
   - Selecting it opens the page, and Play plays Thu 29 Oct.
4. **Thu 29 Oct half-watched.**
   - The button reads *Resume · Thu 29 Oct*.
   - The tile reads *Thu 29 Oct*, with its progress bar.
5. **Thu 29 Oct played to the end, with Tue 27 Oct unwatched.**
   - No card appears at the credits, and nothing autoplays.
   - The player closes to the page with Thu 29 Oct focused.
   - The page shows *Caught up*, and *Dagbók* is not in Continue Watching.
   - Play replays Thu 29 Oct.
6. **A new episode, Fri 30 Oct, is scanned in.** *Dagbók* returns to the front of Continue Watching with *New · Fri 30
   Oct*.
7. **The admin switches it to *Oldest first*.**
   - The note reads *Set by hand…*.
   - The page is ascending, with codes, Shuffle and *Up next* as today, after a reload.
   - A later rescan leaves it *Oldest first*.
8. **An installed app from before this change**, on the same states:
   - Play plays `S2026E302`;
   - there is no Shuffle;
   - Continue Watching follows 3, 5 and 6, with the code badge.
9. **Play on a TV and casting** of Wed 28 Oct from the phone play that episode and stop at its end.
10. **Another series**, not marked, behaves exactly as today.

## Tests

- **Server (`linuxX64Test`):**
  - FR-1's rule: null with a date fetcher, null without one, admin true, admin false, not a series;
  - the mark surviving a rescan;
  - FR-2's PUT and GET;
  - FR-3's `shuffle=false` and unchanged order;
  - FR-5 for a viewer outside Continue and on a finished series;
  - FR-6's table, its order (a newer arrival moves the card ahead), and that an older in-progress episode makes no
    card;
  - FR-7's empty next from `resolvePlayPush`;
  - `WireCompatTest`.
- **Ravilo (`commonTest` / `desktopTest`):**
  - FR-8's order and opening slot;
  - FR-9's label, including another calendar year and a missing air date;
  - FR-10's target and label in all four states;
  - FR-12's pill and ribbon conditions;
  - FR-13: a newest-first episode context has no next, no credits-card mode, and its end returns to the page;
  - FR-14's one-episode cast queue;
  - FR-15's badge.
- **On a device (QA):**
  - acceptance 2–6 and 9 on the living-room TV and the phone;
  - acceptance 8 on an installed release build.

## Build order

1. **Server** (FR-1–FR-7, FR-18's fields).
2. **Admin** (FR-17), after 1.
3. **Ravilo series page and Continue tile** (FR-8–FR-12, FR-15, FR-16), after 1.
4. **Ravilo player and casting** (FR-13, FR-14), after 3.
5. **QA** on a device, after 2–4 are on prod.
