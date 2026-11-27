# A viewer can remove a title from Continue Watching

Task: DEB-5840

> Owner, DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful
> in our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."*
>
> He picked this piece on the card "Ravilo polish: what to build" on 2026-11-26.

## Scope

A viewer can take one title (a film, or a whole series) out of their own Continue Watching row. The progress is kept.
The title comes back by itself the next time the viewer plays it, on any device.

Touches the backend (`tv/`, one new table, one new route, two additive playstate fields), `:shared` (the DTO and one
client call) and `ravilo-ui` (the movie and series detail pages). It amends:

- **R219** ([`phase-R219-continue-watching-model.md`](phase-R219-continue-watching-model.md)), which deferred a manual
  remove on 2026-08-30. This spec builds it as the *filter stage over the canonical list* R219 left room for. Dated
  amendment in that file.
- **DEB-1264** ([`continue-watching-hides-titles-by-tag.md`](continue-watching-hides-titles-by-tag.md)), which kept
  single-card hiding deferred. Dated amendment in that file. Its admin tag filter is unchanged and runs beside this one.

Builds on, and does not change: R233 (a system row is scoped to its page), R229/R248 (a change reaches Home through
`home_changed`), R306 (a series page resumes the episode its Continue entry points at), R343 (Start over), R142 (Mark
watched).

**Clients in:** Android TV, phone, Ravilo Web (both layouts), desktop Mac and Linux. All share the `ravilo-ui` detail
pages. **Out:** Samsung (no button yet; its Home already obeys the server row), Chromecast (no Continue row), the admin.

## Why

The investigation (DEB-5687, evidence §8) found Continue Watching has no viewer remove. The only ways out are
**Mark watched**, which is untrue for a film the viewer gave up on, and **Start over** (R343), which still leaves the
title in the row. A title abandoned once stays near the front of the row until something newer pushes it past the cap
of 20. Netflix offers this on every device and keeps the progress. With more viewers arriving, the row is the first
thing each of them sees.

## Requirements

### FR-1 — Where the hidden state lives

The backend stores it, per Jellyfin user. A Ravilo profile is a Jellyfin user, so every device signed in to that
profile agrees.

- New table `continue_hidden(user_id TEXT, item_id TEXT, activity_at INTEGER, hidden_at INTEGER,
  PRIMARY KEY(user_id, item_id))` in a new migration `103.sqm`.
- `item_id` is the Jellyfin id of the movie or the **series**, never an episode. Hiding a series hides its resume card
  and its next-up card alike.
- `activity_at` is the entry's `lastActivityAt` (R219 §4) at the moment of hiding. `hidden_at` is the server clock.
- Nothing is written to Jellyfin. Position, played state and favourites are untouched (R219 invariant "Jellyfin owns
  playstate"). The table holds a viewer's choice, not a copy of playstate, so FR-R219-1's "no persisted copy" still
  holds.
- The backend keeps each user's rows in memory once read, written through. No feed build reads the database.
- The gone-user sweep (`GoneUserRemoval`, DEB-2675) deletes the user's rows in its transaction.

### FR-2 — The filter

A Continue entry is left out when the user has a row for its `mediaItem.jellyfinId` and the entry's current
`lastActivityAt` is not later than that row's threshold (FR-3).

It is a view-stage filter, after the canonical list and the channel filter, beside DEB-1264's tag filter, and
**before** the cap of 20 (FR-R219-5). A hidden title never takes a slot, and `seedTotalCount` counts what is left. It
applies in every place DEB-1264 FR-4 lists: both branches of `buildRows`, `continueRowNonEmpty`, and
`continueWatchingAll`. The canonical list, its background rebuild and its per-user cache are untouched (FR-R233-5).

It does **not** apply to `continueEpisodes` (R306). A hidden series' own page still resumes the episode it was on.
Search, browse, collections and the detail page still show the title, with Resume where it has a position.

If every entry is hidden, the row and its header are omitted, as an empty Continue row is today.

### FR-3 — It comes back when the viewer plays it

The threshold is `max(activity_at, hidden_at)`. Any play that moves the entry's `lastActivityAt` past it brings the
title back at its true place in the order: a resume, a newly finished episode, Start over, or a play on another
device. Using the later of the two values stops a progress report that lands just after the hide from bringing the
title straight back.

When the background rebuild sees a hidden entry past its threshold, it deletes the row. A row whose title has left the
canonical list (finished, or R219 rule (c)'s day 8) is kept; if the title returns through a new play, FR-3 applies.

A new episode arriving in a hidden series does not bring it back. The next-up card's activity is the last finished
episode, which has not moved (open question 2).

### FR-4 — The route

`PUT /api/tv/continue/hidden`, body `{"item_id": "<id>", "hidden": true|false}`. It answers `200` with the item's
`CardPlayState` (FR-5), as `PUT /tv/played` answers its own state. `hidden: false` deletes the row.

- An id that is not in the user's canonical list answers `200` with `in_continue: false` and stores nothing.
- After a write the backend drops that user's feed, rail and channel caches and sends `home_changed` to the user's
  devices, as the tail of `invalidatePlaystate` does. It makes **no** Jellyfin call and no playstate refresh.
- The write is idempotent.

### FR-5 — The state on the wire

`CardPlayState` gains two additive fields, both default `false`:

- `in_continue` — the id is a movie or series in the user's canonical list and is not hidden.
- `continue_hidden` — the id is in the canonical list and is hidden.

They are filled by `playstateAnswer` from the cached list and the hidden set. That is a memory read, so
`GET /tv/playstate` costs no more.

**Old installed apps** decode with `ignoreUnknownKeys` and never see the fields. They show no button and are not
affected. Their Home rows obey the hide as soon as the backend ships, and they re-pull on `home_changed` as today.
**A new app on an old backend** never receives `in_continue: true`, so it never shows the button. It must never call
the route without that signal, because an absent `/api` route answers `200 text/html`. The wire baseline records both
fields (`WireCompatTest`).

### FR-6 — The action on the detail page (Android TV, phone, Ravilo Web, desktop)

The movie and series detail pages show one action when the playstate says so:

| State | Label key | English |
|---|---|---|
| `in_continue` | `action.continue_remove` | Remove from Continue Watching |
| `continue_hidden` | `action.continue_restore` | Show in Continue Watching |
| neither | — | no action |

Pressing it calls FR-4 through `DetailStore`, the way `setPlayed` and `setFavorite` do. It behaves like Mark watched
under DEB-5835 FR-6 (owner, 2026-11-27: *"At once, undone with a note if the save fails"*): the label flips at once on
this page, a second press is ignored until the server answers, the answer replaces the local state, and Home's row
changes only after a success. A failed call puts the label back and shows DEB-5835 FR-5's toast.
The restore label is the undo: a viewer who removes by mistake presses the same place again.

It is never the first focus target. Play stays first on TV, and the action never moves Play or the buttons before it.
Nothing is added to Home, a row or a tile, so TV focus motion is unchanged. Nothing touches the play start path.

### FR-7 — Strings

`action.continue_remove` and `action.continue_restore` are added to `i18n/en.json`, `da.json` and `fo.json`. The
Danish and Faroese texts follow each file's own `row.continue` wording (`Fortsæt med at se`, `Halt fram at síggja`)
and carry a `note` marking them as drafts for review. `scripts/check-i18n-spelling.sh` passes.

## Design

One small round. It reuses `RaviloButton` (ghost) and adds no component, but the action rows are already full and the
phone row has fixed widths per language (DEB-4070). The designer draws:

1. **Android TV movie and series detail**: where the action sits in the action row, at the longest language (Faroese),
   in both states.
2. **Phone movie and series detail**: where it sits when the Play · Download · My List row is full, in both states.
3. **Desktop and Ravilo Web wide**: the same row at desktop width.

No new colour, icon or animation.

## What this does not do yet

- **Samsung.** No button. Its Home row obeys the hide.
- **A menu on the tile** (long press on the phone, hold OK on TV, right-click on desktop). Out, because Ravilo tiles
  have no menu today and TV focus cost comes first (open question 1).
- **An Undo toast.** The restore label on the detail page is the undo.
- **A list of hidden titles** for the viewer or the admin.
- **Expiry of old rows.** They cost one row each.

## Open questions

1. **A tile menu later?** Recommended: not in this task. Measure the detail-page action first; a tile menu touches
   every Home tile's key handling on TV.
2. **Should a new episode bring a hidden series back?** Recommended: no. The viewer chose to hide it; a play brings it
   back.

## Acceptance

On a backend with this change and a test user with at least three Continue entries (one film, two series):

1. **Remove.** Removing the film from its detail page on Android TV takes it out of Home's row and its See all on every
   device of that user after `home_changed`. The See all count drops by one. Its page still offers Resume at the same
   position. No Jellyfin playstate changed.
2. **Series.** Removing a series hides its card whether it shows resume or next-up. Its page still resumes the
   R306 episode.
3. **Restore.** The page now offers "Show in Continue Watching". Pressing it brings the title back at its true place.
4. **Comes back by playing.** Remove a series, then play any of its episodes for a minute on the phone. After the
   next rebuild it is back in the row, and its `continue_hidden` row is gone.
5. **No early return.** Removing a title within seconds of stopping it does not bring it back.
6. **Per profile.** Another Jellyfin user on the same TV still sees the title.
7. **Old app.** An Android TV build that predates this change shows the row without the removed title and shows no
   new button. A new build against an old backend shows no button.
8. **Gone user.** After the gone-user sweep removes a user, `continue_hidden` holds no row of theirs.
9. **Cost.** `GET /tv/home` and `GET /tv/playstate` make no Jellyfin call and no database read they did not make
   before.

## Build order

One engineer. Slices in order. Slices 1–3 need no design. Slice 4 waits for the round.

1. **Store (FR-1).** `src/commonMain/sqldelight/dev/jellystructure/db/103.sqm`, `ContinueHidden.sq`, a
   `tv/ContinueHiddenStore.kt` (in-memory, write-through), the delete in `tv/GoneUserRemoval.kt` and
   `UserRemoval.sq`, and the `DROP TABLE IF EXISTS` in the two migration-replay tests. Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew linuxX64Test --tests '*ContinueHiddenStoreTest*' --console=plain`, and the
   gone-user test extended.
2. **Filter and return (FR-2, FR-3).** `tv/HomeFeedService.kt`: the filter in `buildRows` (both branches),
   `continueRowNonEmpty`, `continueWatchingAll`; the threshold and the row delete in the rebuild. Proof:
   `./gradlew linuxX64Test --tests '*HomeFeedContinueHiddenTest*'`, modelled on `HomeFeedContinueHideTagsTest` with
   `continueSourceForTest`.
3. **Wire (FR-4, FR-5).** `server/routes/TvRoutes.kt` (route, `playstateAnswer`), `shared/.../tv/Models.kt`
   (`CardPlayState`, request DTO), `shared/.../tv/TvApiClient.kt` (`setContinueHidden`), the cache drop and push in
   `HomeFeedService`, and the wire baseline (`scripts/record-wire-baseline.sh`). Proof:
   `./gradlew linuxX64Test --tests '*ContinueHiddenRouteTest*'` and
   `./gradlew :shared:linuxX64Test --tests '*WireCompatTest*'`.
4. **Strings (FR-7). Junior-ready.** `i18n/en.json`, `da.json`, `fo.json`. Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-i18n:generateRaviloStrings :ravilo-i18n:checkRaviloStrings --console=plain`
   and `scripts/check-i18n-spelling.sh`.
5. **The action (FR-6).** After the round. `ravilo-ui/.../screens/MovieDetailScreen.kt`, `SeriesDetailScreen.kt`,
   `DetailStore.kt` (`setContinueHidden`, read-after-write like `setFavorite`). Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*ContinueRemoveActionTest*' --console=plain`
   (label per state, no action when neither, the label flips at once and goes back on a failure, Play keeps first focus), then the
   Android TV and phone compiles.
