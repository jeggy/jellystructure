# A failure says what happened and offers a way out

Task: DEB-5835

> Owner, DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful in
> our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."*
>
> He picked this piece, polish group A, on the card "Ravilo polish: what to build" on 2026-11-26.

## Scope

Polish group A of the DEB-5687 investigation: nine rows of its evidence document (§2 rows 1, 7, 8, 11, 12, 13, 14, 15
and 23). Each one is a failure that says nothing, says the wrong thing or leaves the viewer stuck.

- **Clients in:** the Compose family in `ravilo-ui`: Android TV, the Android phone, Ravilo Web (wide and narrow), and
  the desktop app on Mac and Linux.
- **Clients out:** Ravilo Samsung, whose search, sign-in and failure work is DEB-5685 (FR-57, FR-60), and the
  Chromecast receiver, which has none of these screens.
- **Earlier specs:** this picks up what R280 left open (a failed search, and the live player's own error, R280 "Found
  and deliberately not fixed" and open question 2). FR-1 restores FR-R280-3's table (REAUTH offers Sign in). FR-2
  amends R340 with a dated note in its file. R237's classifier (`classifyLoadFailure`) and R280's `LoadErrorState` are
  reused everywhere and not changed in meaning.
- **Wire:** FR-8 only, two additive fields. Nothing else touches the server.
- **QoE and performance:** every FR runs only after something has failed, or on a press. Nothing is added to the play
  start path, the tune path before it fails, Home's first frame or TV focus motion. FR-4 does less work than today: the
  grid stays in place while a query loads.

## Why

From the evidence, re-read against `main` at `921645877`:

- A live channel that fails to tune draws one line and nothing else (`LiveTvPlayerScreen.kt:341-345`). No Retry, no
  Back, no controls on a phone, and channel up/down is ignored because `zap` reads only a `Ready` state
  (`LiveTvPlayerStore.kt:182-183`). The cause is thrown away (`:144`, `getOrNull`).
- A failed search falls through to `search.results` with a count of 0 (`SearchScreen.kt:429-436`): the viewer is told
  the library has nothing when the server did not answer. `Loading` also empties the grid on every query (`:148`,
  `:206-209`). Request search does the same (`SeerrSearchScreen.kt:201-205`); music search draws nothing
  (`MusicBrowseScreen.kt:223`, `:431`).
- "Sign in again" offers Sign in only on Home. `LoadErrorState` draws it only when the caller passes `onSignIn`
  (`LoadError.kt:180`), and of the 24 call sites only `HomeScreen.kt:1056` does. Home's own Sign in only goes to Login
  and leaves the dead profile cached (`RaviloApp.kt:2644`).
- Home's can't-reach page has a second button, Sign out, that runs `unpairAllSessions` at once
  (`HomeScreen.kt:1069`): every profile is signed out, with no question. Everywhere else, Sign out asks first (R340).
- Mark watched, My List and the episode toggle show nothing while writing and nothing on failure
  (`DetailStore.kt:97-114`, `:206-222`, `getOrNull`).
- Settings switches flip at once and swallow a failed write (`SettingsScreen.kt:115-160`, `ProfileScreen.kt:337`). The
  music heart toasts "Added" before the request (`RaviloApp.kt:1837-1841`). Audiobook start over, mark finished and
  bookmark drop failures (`BookPlayerScreens.kt:266`, `:400`; `AudiobookScreens.kt:445`, `:453`).
- Request drops a thrown failure (`DiscoverDetailStore.kt:62-66`). A refused request shows the server's English reason,
  product name and all (`DiscoverDetailScreen.kt:171-174`; `SeerrDiscoverService.kt:294`, `:297`, `:314`, `:327`). A
  viewer who may not request still gets Request, and then a Retry that cannot work: `retryable` is never read.
- A series with no episode in the shown season, and no resume target, has a Play button that does nothing
  (`SeriesDetailScreen.kt:900-901`).
- "Play on another screen" that the server refuses goes to a `println` (`ScreensSheet.kt:315`, `RaviloApp.kt:2197`).

Five of the eleven accounts started this month. A new viewer meets several of these in a first week.

## Requirements

### FR-1 — "Sign in again" always has a Sign in button

- `LoadErrorState` with kind `REAUTH` and no `onSignIn` uses `LocalReauthRequired`: this profile is signed out, then
  the picker opens, or sign-in when it was the last profile. It is the player's route (R237), and the one
  `sign_in_removed` takes (DEB-5781).
- Home uses the same route for its Sign in, so a dead profile no longer stays in the picker.
- Where `LocalReauthRequired` is null (screens before sign-in), today's Back stays.
- This covers all 24 call sites, music pages (`MusicLoadFailed`) and detail pages (`DetailErrorState`) included, with
  no change at the call sites. It restores FR-R280-3. No new string.

### FR-2 — Sign out on Home's can't-reach page asks first

- The page's Sign out opens the confirmation the client's own menu opens, and nothing is signed out until the viewer
  confirms:
  - **TV layout** (Android TV, wide Ravilo Web): R340's `SignOutChoiceOverlay`. *Only {name}* is the default.
    *Everyone on this TV* is what the button does today.
  - **Phone and narrow web:** R304's one-profile confirmation (`profile.signout_*`).
  - **Desktop:** its own one-profile confirmation (`profile.signout_title_desk`).
- Cancel and Back return to the can't-reach page with focus on Sign out. R340 FR-5 (focus stays in the dialog) holds.
- Signing out one profile while the server is unreachable still works locally. The revoke stays best effort, as in
  `signOutActiveSession`. No new string. Amends R340 (dated note in that file).

### FR-3 — A live channel that will not tune says so and offers a way on

- `LiveTvPlayerState.Error` keeps a classified cause. A failed tune call is classified by `classifyLoadFailure`. A
  player fatal error after `LiveFallbackPolicy` has run out is `GENERIC`. A channel not found stays `GONE`.
- The live player draws `LoadErrorState` for that kind. Its `GENERIC` heading is `livetv.tune_failed` (existing) with
  the new body `livetv.tune_failed_body`. The other kinds read as they do on every other page: unreachable offers Retry,
  REAUTH offers Sign in (FR-1), GONE offers Back.
- **Retry** tunes the same channel again through the normal `tune`. **Back** leaves the player as Back does today.
  Focus lands on the first button.
- **Channel up/down, number entry and the phone's vertical swipe work from the error.** They tune from the failed
  channel (`currentChannelId`), not only from `Ready`.
- Radio stations take the same treatment in the radio layout's place.
- Nothing is retried by itself, and nothing changes before a tune has failed.

### FR-4 — A failed search says it failed, and results do not blink

- **Library search** (`SearchScreen`): on `SearchState.Error` the results area draws `LoadErrorState` for the cause
  (Retry runs the same query again; Sign in per FR-1). It never draws a count or "No results".
- **Request search** (`SeerrSearchScreen`) does the same, and **music search** (`MusicBrowseScreen`) draws
  `MusicLoadFailed` in place of the empty space.
- `LoadErrorState` gains `takeFocus` (default true). The searches pass false, so a failure never pulls focus or the
  keyboard away from the field. On a TV, Down from the field reaches Retry.
- **While a new query loads, the previous results stay on screen** and stay focusable, under today's "Loading..." label.
  They are replaced when the answer lands. An empty field still shows suggestions.
- No new string.

### FR-5 — One toast says a change did not happen

- The app's toast (today `MusicToasts` / `MusicToastHost`, reused as is) is drawn on every layout. Today the wide Ravilo
  Web layout has no host: it is neither handset, desktop nor `isTvPlatform` (`RaviloApp.kt:3892`).
- One helper picks the line from the cause, using `loadErrorKindOf` on the thrown failure:
  - no answer or 5xx: `toast.not_saved_offline` — *Couldn't reach the server. Nothing was changed.*
  - REAUTH: `error.load.reauth.title` (existing). On touch and pointer layouts it carries `action.sign_in`, which runs
    FR-1's route. A TV shows the line only: the next page load offers Sign in.
  - anything else: `toast.not_saved` — *Couldn't save that. Nothing was changed.*
- A toast never takes focus and never covers the player's controls.

### FR-6 — Mark watched, My List and the episode toggle show the change and undo a failed one

- On the movie page, the series page and each episode card, a press shows the new state at once on that page. A second
  press on the same control is ignored until the server answers.
- **On success** the server's answer replaces the local state, as R142 does today. `WatchedBus` publishes only then, so
  other pages never show a write that did not happen.
- **On failure** the control goes back to what it showed before, and FR-5's toast says why.

### FR-7 — Settings and music changes that fail are undone and say so

- **Settings switches** (`saveSkin`, `saveTheme`, `saveShowContinueProgress`, `saveAutoplayNext`, `saveUiLanguage`)
  and the phone Profile's language rows: on a failed write the value goes back to what it was, and FR-5's toast shows.
  A language that fails to save also undoes `LastLanguage`.
- **The music heart** toasts `music.my_list_added` / `_removed` only after the server accepts. On failure the heart goes
  back and FR-5's toast shows.
- **Audiobook** start over, mark finished / unfinished and add bookmark show FR-5's toast on failure. Start over still
  plays from the start, because playback is local; a failed mark is left to the next refresh.

### FR-8 — A request says what happened, in the viewer's words

- **Server, additive:** `AcquisitionRecord.reasonCode: String? = null`. Values: `not_allowed`, `not_set_up` (no
  request service), `language_not_set_up`, `request_failed`. They are set at `SeerrDiscoverService.kt:294`, `:297`,
  `:313` and `:327`. `reason` keeps its English text for the admin and for installed apps.
- **Server, additive:** `DiscoverDetail.canRequest: Boolean? = null`. It is filled with the same rule `request()` uses
  (`isAdmin || discover.canRequest`).
- **An installed app** ignores both fields and behaves as today. **A new app against an old server** reads null and
  behaves as today, except that it never draws `reason`.
- **Client:**
  - The app never draws `reason`. A FAILED record draws the line for its `reasonCode`: `request.reason.not_allowed`,
    `request.reason.not_set_up`, `request.reason.language` or `request.reason.failed`. An unknown or null code draws
    no line under the status.
  - *Retry request* is drawn only when `retryable` is true. Otherwise the action is the existing non-pressable GHOST
    button with the status label, so focus still has a place to land.
  - `canRequest == false` draws that GHOST button with `request.not_allowed` in place of Request.
  - A request that throws (no answer) keeps the Request button and shows FR-5's toast. A second press while one is in
    flight is ignored.
- The R319 wire contract records both fields.

### FR-9 — A series always has something under Play, or no Play

- With no resume target, Play takes the first episode of the shown season. If that season is empty, it takes the first
  episode of the first season that has one, played from its own season (the existing `sIdx` rule).
- A series with no episode in any season draws no Play button. In its place is the line `detail.no_episodes` (*No
  episodes to play yet.*) in the page's secondary text style. Focus lands on My List.

### FR-10 — "Play on another screen" that is refused says why

- `PlayOnStore.start`'s `onRefused` shows FR-5's toast host with a line for the reason it already computes. `{device}`
  is the screen's name as the sheet lists it.
  - `busy`: `screens.refused_busy` — *{device} is busy right now.*
  - `forbidden`: `screens.refused_forbidden` — *{device} can't play this.*
  - `unreachable`: `screens.refused_unreachable` — *Couldn't reach {device}.*
  - `failed`: `screens.refused_failed` — *Couldn't start it on {device}.*
- R370's "play there" falls back to playing here, as it does today, and says so with `screens.refused_playing_here` —
  *Couldn't start it on {device}, so it's playing here.* The toast never delays the play here.
- The `println`s become `sessionLog` lines.

### Strings

New keys, English below, each in `i18n/en.json`, `da.json` and `fo.json`. The Danish and Faroese are drafts written in
the slice that adds the key, carrying the existing note *"Draft — the shipped table wins wherever a string already
exists."*, for review. No string names a product, a protocol or a status code (Ravilo constitution invariant 12).

| Key | en |
|---|---|
| `livetv.tune_failed_body` | Try again, or pick another channel. |
| `toast.not_saved` | Couldn't save that. Nothing was changed. |
| `toast.not_saved_offline` | Couldn't reach the server. Nothing was changed. |
| `request.reason.not_allowed` | This profile can't request titles. |
| `request.reason.not_set_up` | Requests aren't available right now. |
| `request.reason.language` | That language can't be requested yet. |
| `request.reason.failed` | The request didn't go through. |
| `request.not_allowed` | Requests are off for this profile |
| `detail.no_episodes` | No episodes to play yet. |
| `screens.refused_busy` | {device} is busy right now. |
| `screens.refused_forbidden` | {device} can't play this. |
| `screens.refused_unreachable` | Couldn't reach {device}. |
| `screens.refused_failed` | Couldn't start it on {device}. |
| `screens.refused_playing_here` | Couldn't start it on {device}, so it's playing here. |

## Design

No design round. Every surface reuses a component that is already drawn and shipped: `LoadErrorState`, R340's
`SignOutChoiceOverlay`, the two one-profile `ConfirmOverlay`s, `MusicToastHost`, the GHOST `RaviloButton` that Discover
already uses for an in-flight request, and the page's secondary text line. No new CSS and no new layout.

## What this does not do yet

- Wording that names the wrong device ("This TV needs to be signed in again" on a phone): group B.
- Loading looks, skeleton shapes and empty states: group C. "Loading..." under kept results (FR-4) stays until then.
- A song that fails to start, a black live picture after "Tuning…", a trailer with no failure state, a failed guide,
  a collection page that gives up: other rows of the evidence, not this group.
- An episode the library has not indexed (a path-based id, `DetailService.kt:173`) still fails in the player rather
  than being marked unplayable. That needs a wire field and is left for a later spec.
- Translated download-failure reasons from the request tools: `reasonCode` covers only the four request refusals.
- Ravilo Samsung: DEB-5685.

## Acceptance

QA on a device from the board, after each slice is on prod. Danish and Faroese are each checked once.

1. Revoke a profile's sign-in in the admin and open a movie page on the TV, phone, web and desktop: *Sign in again*
   with a focused **Sign in**. Pressing it signs out that profile only and opens the picker (or sign-in when it was the
   last profile).
2. Home with the backend stopped: Sign out opens R340's dialog on the TV and the one-profile confirm on a phone and
   on the desktop. Cancel changes nothing.
3. A channel whose tune is refused: heading, body, focused **Retry** and **Back**. Channel up tunes the next channel.
   On a phone, Back and Retry are tappable.
4. Search with the backend stopped: *Couldn't reach the server* and Retry, never "0 results". Typing a second query
   over results keeps the first results on screen until the second answer lands.
5. Mark watched and My List with the backend stopped: the control flips, flips back, and the toast reads *Couldn't
   reach the server. Nothing was changed.* With the backend up, other pages flip only after the answer.
6. A Settings switch with the backend stopped goes back and toasts. The music heart with the backend stopped does not
   say "Added".
7. A profile with requests off: the Discover page shows *Requests are off for this profile*, with no Request and no
   Retry. An installed v1.73 app on the new server behaves as today.
8. A series with no episodes: no Play, the line shows, focus is on My List.
9. "Play on" to a screen another viewer is using: the toast names that screen.

## Build order

For one engineer. Each slice is about 200 changed lines or fewer, and each lands green on its own.

1. **FR-1 — Sign in on every page.** Junior-ready. `components/LoadError.kt`, `screens/HomeScreen.kt`, `RaviloApp.kt`
   (Home's `onSignOut`). Proof: `LoadErrorSignInTest` (REAUTH with only `LocalReauthRequired` draws Sign in and calls
   it; null local draws Back), `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests
   '*LoadErrorSignInTest*' --console=plain`.
2. **FR-2 — Home's Sign out asks.** Junior-ready. `screens/HomeScreen.kt`, `RaviloApp.kt`, `screens/SettingsScreen.kt`
   (overlays reused). Proof: `HomeErrorSignOutTest` (TV layout opens the choice overlay, handset the confirm; nothing
   is unpaired before confirm), `:ravilo-ui:desktopTest --tests '*HomeErrorSignOutTest*'`.
3. **FR-3 — Live channel failure.** Junior-ready. `screens/LiveTvPlayerStore.kt`, `screens/LiveTvPlayerScreen.kt`,
   `components/LoadError.kt` (a `genericTitleKey` / `genericBodyKey` parameter), `i18n/*.json`. Proof:
   `LiveTvTuneFailedTest` (a failed tune keeps its kind; zap and number entry from Error tune the neighbour; Retry
   re-tunes), `:ravilo-ui:desktopTest --tests '*LiveTvTuneFailedTest*'`, plus `LoadErrorStringsTest`.
4. **FR-4 — Search failure and kept results.** Junior-ready. `screens/SearchScreen.kt`, `screens/SeerrSearchScreen.kt`,
   `music/MusicBrowseScreen.kt`, `components/LoadError.kt` (`takeFocus`). Proof: `SearchStoreFailureTest` (Error
   carries the kind; Loading keeps the previous items) with the store's lambda constructor, and `SearchFailedUiTest`
   (no count text on Error; Retry re-queries), `:ravilo-ui:desktopTest --tests '*SearchStoreFailureTest*' --tests
   '*SearchFailedUiTest*'`.
5. **FR-5 — The toast everywhere.** Junior-ready. `RaviloApp.kt` (host for wide web), a `writeFailedToast(t)` helper
   beside `MusicToasts`, `i18n/*.json`. Proof: `WriteFailedToastTest` (cause to key, REAUTH carries Sign in off TV),
   `:ravilo-ui:desktopTest --tests '*WriteFailedToastTest*'`.
6. **FR-6 — Detail toggles**, after 5. Junior-ready. `screens/DetailStore.kt`, `screens/MovieDetailScreen.kt`,
   `screens/SeriesDetailScreen.kt`. Proof: `DetailToggleRollbackTest` (failure restores the overlay and does not
   publish; success publishes the server state; second press ignored while pending), `:ravilo-ui:desktopTest --tests
   '*DetailToggleRollbackTest*'`.
7. **FR-7 — Settings, heart, audiobook**, after 5. Junior-ready. `screens/SettingsScreen.kt`, `screens/ProfileScreen.kt`,
   `RaviloApp.kt` (`setMusicFavorite`), `music/BookPlayerScreens.kt`, `music/AudiobookScreens.kt`. Proof:
   `SettingsWriteRollbackTest`, `:ravilo-ui:desktopTest --tests '*SettingsWriteRollbackTest*'`.
8. **FR-8a — Request fields on the server.** Not junior-ready (wire change). `shared/.../tv/Acquisition.kt`,
   `shared/.../tv/Discover.kt`, `src/linuxX64Main/.../seerr/SeerrDiscoverService.kt`, the discover item route, the
   wire baseline. Proof: `SeerrRequestReasonCodeTest` (each refusal carries its code; `canRequest` matches `request()`'s
   rule), `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew linuxX64Test --tests '*SeerrRequestReasonCodeTest*'
   --console=plain`, and `:shared:linuxX64Test --tests '*WireCompatTest*'`.
9. **FR-8b — Request on the client**, after 5 and 8a. Not junior-ready (reads the new wire fields).
   `screens/DiscoverDetailStore.kt`, `screens/DiscoverDetailScreen.kt`, `i18n/*.json`. Proof: `DiscoverRequestStateTest`
   (no `reason` text drawn; Retry only when retryable; `canRequest` false draws the GHOST line; null fields behave as
   today), `:ravilo-ui:desktopTest --tests '*DiscoverRequestStateTest*'`.
10. **FR-9 — Series with nothing to play.** Junior-ready. `screens/SeriesDetailScreen.kt`, `i18n/*.json`. Proof:
    `SeriesPlayTargetTest` (empty shown season falls to the first season with an episode; none draws no Play),
    `:ravilo-ui:desktopTest --tests '*SeriesPlayTargetTest*'`.
11. **FR-10 — Play-on refusals**, after 5. Junior-ready. `components/ScreensSheet.kt`, `RaviloApp.kt` (`playThere`),
    `i18n/*.json`. Proof: `PlayOnRefusalToastTest` (each reason to its key; fallback still plays here),
    `:ravilo-ui:desktopTest --tests '*PlayOnRefusalToastTest*'`.

Every slice also runs `scripts/check-ravilo-strings.sh` and `scripts/check-deanonymization.sh`. Android compiles
(`:ravilo-ui:compileDebugKotlinAndroid`) and the web compile (`:ravilo-web:compileKotlinWasmJs`) stay green.
