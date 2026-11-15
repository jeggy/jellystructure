# Ravilo on a Samsung TV is a full app, driven by the TV remote
Task: DEB-4654

> Owner on DEB-799, 2026-11-15: *"How much work would it be to make it into a full so instead of mobile
> controlled only. No casting support needed and no discover tab support"*, then *"Cant you just use our
> tizen emulator instead of real tv and then when the app is in real good state, i can test it in a real
> tv … go ahead and let this be agent controlled"*. Tizen work had been paused since 2026-09-25; this
> resumes it.

## Scope

- **What it is.** The Samsung TV app becomes a Ravilo app you use with the TV remote: sign in, choose a
  profile, Home, Movies, Series, Search, a title's page, My List, and playback that resumes where you
  left off. It is not a receiver that waits for a phone any more.
- **What it reuses.**
  - The player that R264 built (`ravilo-screen`): AVPlay, the audio and subtitle picker, Skip Intro,
    next-up, the per-cause failure copy.
  - R269's server setup screen.
  - `shared`'s `TvApiClient` and DTOs, and `ravilo-i18n`'s tables. Both already compile to Kotlin/JS
    (`js(IR)`), so the app calls the same routes and decodes the same DTOs as every other Ravilo client.
  - The deleted R189 client `ravilo-tizen` (`git show fa198823^:ravilo-tizen/…`, 22 files, about 2,400
    lines) is **reference only**. Its screens called routes that all still exist, but they predate two
    months of features and never ran on a TV. A piece may copy from it. Nothing restores the module.
- **What it supersedes** (each amended in its own file, 2026-11-15):
  - R264's *no navigation*: FR-R264-2 and the first non-goal.
  - R264's pair-from-the-phone enrolment, FR-R264-1's first sentence. The rest of FR-R264-1 stays: the
    events socket, the negotiation, the progress and status reports.
  - R269's *then the code* (FR-R269-4) becomes *then sign-in*.
  - **R264 FR-R264-3 to FR-R264-7 stay in force as the player's requirements.**
  - **247 and R272 are unchanged** and still hold.
- **The device.** The owner's TV is a Samsung UE55RU7445: 2019, RU7400 series, **Tizen 5.0**, the same
  family as R264's UE55RU7440.
  - Samsung's per-year web-engine table gives Tizen 5.0 **Chromium 63**. Today's Compose/Wasm UI needs
    WasmGC (Chromium 119), so it cannot run there. Tizen 9.0 TVs (2025, Chromium 120) could run it. A
    full Ravilo on those TVs is a separate question that this spec does not open.
  - Development and QA happen on the Tizen TV emulator (DEB-4653), which runs **Tizen 10.0**. The owner
    tests the real TV once the app is in good shape (FR-16).
- **Constitution.** The Ravilo constitution (Technology Mandates; principles 7 and 8) makes Compose
  Multiplatform and `:ravilo-ui` the only home for Ravilo screens. A hand-written DOM client with its
  own Home, Browse and detail screens is an alternative to that mandate. **No build piece starts until
  the owner has amended it** (DEB-4654's card, question 1). The amendment this spec assumes:
  - one Kotlin/JS DOM client, only for TVs whose web engine cannot run the Wasm build;
  - the client's screens are the list in this spec, not parity;
  - it still uses `shared` for every DTO and route, and `ravilo-i18n` for every string.

## Decisions taken here

- **One module and one package.** The full app grows inside `ravilo-screen`. `config.xml` keeps
  `package="RaviloScrn"` (the 10-character constraint R264 found), so a set that has the receiver
  installed upgrades in place. The release asset, its signing and its workflow stay R272's.
- **No design round.** Every screen in v1 already exists on Android TV. This app redraws those screens
  in DOM, the way R269's dev note had the setup screen redrawn: the same copy, the same order, the same
  focus rules. The colours, type and focus ring come from `design/ravilo/Ravilo TV.html` and
  `ravilo.css`. Where Chromium 63 cannot draw something (FR-13), the screen draws without it and does not
  get a redesign. A surface that exists only on Tizen would need Rannvá. v1 has none.
- **No wire change.** Every route v1 needs exists and is reached today by `TvApiClient`.
  - Artwork comes from `/api/tv/image/` and `/api/tv/channel-logos/`. Both are open routes
    (`AuthPlugin.kt`), so a plain `<img>` needs no token.
  - REST from a widget needs no CORS exception (247). The one socket is `/api/tv/events`, which 247
    FR-247-3 already admits.
  - A piece that finds it needs a new route, a new field, or another socket stops there, and this spec
    gets an amendment first.

## Requirements

**FR-1 · One boot path: server, then sign-in, then profile, then Home.** R269's setup screen runs while
no server is stored, unchanged. With a server and no profile, the app opens on FR-2's sign-in screen.
With one profile it opens Home as that profile. With several it opens FR-3's picker.

- The pair-only idle screen of R264 FR-R264-2 is gone, and so is its `screen/code` + `screen/claim`
  loop. The routes stay on the server for one release cycle, as the wire rule requires.
- A set upgraded from the receiver holds `screen`-kind tokens in `ravilo.screen.tokens`. These are
  borrowing rows, and DEB-2233 FR-3 lets them approve nothing. On first launch the app drops them
  (`unpair`, best effort) and signs in fresh.

**FR-2 · Sign-in is the Android TV screen: the code first, the password second.** This is DEB-2233
FR-5, redrawn in DOM.

- The screen shows:
  - the QR of `linkUrl`;
  - the code in large type, grouped 3 + 3;
  - the one instruction;
  - this TV's name.
- Below them is *Sign in with a password instead*. It is focused first.
- The screen follows FR-5's rules and its 2026-10-24 amendment: the 3 s poll, *Approving on ‹name›'s
  phone…*, a silent re-mint on expiry, five failed polls before the could-not-reach state, and the
  password form directly when `available` is false.
- `platform` is `tizen`. The device name is `Samsung TV` unless Tizen reports the set's own name.
- The password form is R175's: a real `<input>` for each field, which raises Tizen's own IME (R281's
  lesson), the same copy, and the typed username kept on failure.
- **No single sign-on button**, the same reading as FR-5.
- The QR needs `ravilo-ui`'s `QrEncoder.kt`, which is pure Kotlin with no Compose import. The sign-in
  piece moves it into `shared` (`commonMain`), with no change in behaviour, and both clients draw from
  the same matrix.

**FR-3 · Profiles are the Android TV picker.** One device token per signed-in viewer, stored in the TV's
`localStorage`.

- The picker shows each profile and *Add user*. *Add user* opens FR-2.
- *Switch profile* and *Sign out* sit in the profile menu (FR-4).
- Signing out removes that one profile (`unpair`). The other profiles stay.

**FR-4 · Navigation: a top bar and the D-pad.**

- The bar holds **Home · Movies · Series · Search** and the profile avatar. **There is no Discover.**
- The avatar opens the profile menu: **My List · Switch profile · Settings · Sign out**. This matches
  R170, where My List lives in the profile menu.
- **The focus model.**
  - There is always exactly one visible focus target.
  - The arrow keys move spatially.
  - Focus enters a row at the tile last focused in it.
  - A row scrolls to keep the focused tile in view.
- **Back** goes up one level:
  - it closes what is open;
  - from a page it returns to the previous page, with its focus restored;
  - on Home it moves focus to the bar;
  - on the bar it exits the app.
- The keys are R264's, including `XF86Back` (10009) and the media keys, registered through
  `tizen.tvinputdevice`.

**FR-5 · Home renders the feed the server sends, in the order it sends it.**

- It draws `heroes`, then the channel buttons, then `rows`, using the feed's `tile_shape` and
  `hero_height_pct`.
- Continue Watching is whichever row the server sends. The client never builds a row of its own.
- A row kind the app does not know is skipped, and the rest of Home draws (R318).
- **Live TV is not in v1.** `live_tv_home` is treated as absent.
- A channel button opens that channel's feed (`getChannel`) with the same renderer.
- `focus_detail` is honoured as `none`. The focus-detail panel comes later.

**FR-6 · Movies, Series and My List are grids.**

- Each one is `browse` with the matching `BrowseKind` (`movie`, `series`, `mylist`) and the Android TV
  app's default sort.
- A grid loads the next page as focus nears its end.
- My List with nothing in it shows the Android TV empty line.
- **No filter or facet UI in v1.**

**FR-7 · A title's page.**

- **Movie:** the backdrop, the title, the meta line (year, runtime, the server's rating badge), the
  overview, and the actions.
  - The primary action is **Resume ‹time›**, or **Play** when nothing has been watched.
  - The other actions are **Start over** (only when there is a resume point), **My List** (on or off),
    and **Mark watched** or **unwatched**.
- **Series:** the same header, a season strip and that season's episodes.
  - The primary action is the series' next-up episode, which is the constitution's *series resume/next
    is the default action*. It never offers a watched episode (owner, 10-29).
  - An episode tile plays that episode.
- Played state and My List change through `setPlayed` and `setFavorite`. The page re-reads the server's
  answer and does not flip the button on its own (the constitution's server-owned state).
- **No people row in v1.** The cast list comes later, with person pages.

**FR-8 · Search.**

- Search is a real `<input>` that raises Tizen's IME, with results under it.
- Results are movies and series from `search`, requested 400 ms after the last keystroke.
- **Down** from the field enters the results. **Back** from the results returns to the field with the
  typed text kept.
- No people and no music in v1.

**FR-9 · Playing from the TV is R264's player, started by the TV.**

- **Play** calls `startPlayback` with the same negotiation as the receiver (`hls_only`, the
  AVPlay/`canPlayType` probes) and resumes from the server's playstate.
- The app reports progress every 10 s and a stop on exit.
- Everything in R264 FR-R264-3 applies: transport, the two-level picker, subtitle size, Skip Intro and
  Skip Credits, the next-up countdown, the waiting moments, and the per-cause failure copy.
- **The differences from the receiver:**
  - Back from the player returns to the page it was started from, with focus on the item that played.
    It does not go to an idle screen.
  - Auto-advance plays the next episode on the TV itself through the same backend round trip the
    receiver uses (`remotePlay` on its own device), so the next episode arrives complete.
  - A refused start says why in R237's words, with no *from your phone*. The two strings that say
    *Try again from your phone* get TV-remote variants in `ravilo-i18n`.

**FR-10 · A phone can still send a title to it.** *(Subject to the owner's answer to card question 2.)*

- While a profile is signed in, the app keeps R264's events socket (FR-R264-1, FR-R264-5) under that
  profile's token. A phone's *Play on* list therefore shows it, the same way it shows an Android TV.
- A `play_item`, `playstate_command` or `player_command` acts exactly as it does in the receiver today.
- A `play_item` that arrives while the viewer is browsing takes over the screen. When it ends, the app
  returns to the page the viewer was on.
- Status reports (FR-236-5) continue while the app plays.
- **Nothing here sends to another device.** There is no cast icon and no *Play on* menu on the TV.

**FR-11 · Settings, the minimum.**

- **Language:** follow the TV, or English, Dansk or Føroyskt. The setting uses `ravilo-i18n`'s table
  and its order.
- **Server:** the address, and *Change server*. *Change server* opens R269's setup screen with the
  address prefilled and the same probe. A new address signs every profile out, because the tokens
  belong to the old server.
- **About:** the app version and the server version.
- R269 FR-R269-7's three-second hold on Back stays, as the way out of a sign-in screen pointed at a
  server that has gone.

**FR-12 · Every string comes from `ravilo-i18n`.** Every string uses an existing key wherever the
Android TV app has one. A new key is added to all three languages in `ravilo-i18n`. The app never
carries a table of its own.

- `ReceiverStrings` in `ravilo-receiver-core` stays for what only the Chromecast receiver shows.
- The full app reads the player's strings from `ravilo-i18n` too, so the TV and Android TV cannot drift
  apart in wording.

**FR-13 · The engine floor is Chromium 63.**

- **The bundle:**
  - The built `ravilo-screen.js` parses and runs on Chromium 63 (see the amendment below). Kotlin 2.3's
    output and the bundled libraries both contain newer syntax, so the build transpiles the bundle.
  - The app's own code calls no web API newer than Chromium 63. Two to avoid: `AbortController` (66) and
    `ResizeObserver` (64).
- **The CSS** uses no feature newer than Chromium 63: no `gap` on flex containers (84), no
  `aspect-ratio` (88), no `inset` (87), no `:focus-visible` (86), no `backdrop-filter` (76).
- **A guard enforces the CSS rule.** `scripts/check-tizen-engine.sh` greps the module's CSS and fails on
  any of the properties above. It is wired into CI's script checks.
- **A second guard checks the built bundle.** CI parses the production `ravilo-screen.js` as ES2017
  (`acorn --ecma2017`, or the equivalent) and fails on any syntax newer than that. The web APIs the app
  calls are checked on the Tizen 5.0 emulator (FR-16), not by this guard.

**FR-14 · Light enough for a 2019 set.**

- **Cold start to Home:** under 5 s on the RU7445 with a warm server. R264 FR-R264-7's 3 s still holds
  for the setup and sign-in screens.
- **Images:**
  - Rows more than one screen below the focus do not load their images until focus comes near.
  - A page keeps no more than about 60 tile images in the DOM.
- **The bundle** stays under 1 MB gzip. The receiver is 281 KB today.
- **Animation** is opacity and transform only.

**FR-15 · Packaging.** It is the same `.wgt`, the same workflow (R272) and the same release asset.
`config.xml` gains nothing: no new privilege is needed.

**FR-16 · How it is proven.**

- **In CI.** The test stack's `ravilo-screen` image (247 FR-2) already serves this bundle to Playwright
  through `HtmlVideoBackend`. Each build piece adds a Playwright spec against the real `app` service and
  the mocks:
  - sign-in by code (approve from a second browser context) and by password;
  - a profile switch;
  - Home rows and an unknown row kind;
  - a grid's next page;
  - a title's actions and the server's answer;
  - search;
  - a play that resumes and reports progress;
  - Back returning focus to the item.

  D-pad navigation is driven with keyboard events in Playwright.
- **On the emulator.** The `tizen-emulator` row (DEB-4653) covers what CI cannot: AVPlay, the real key
  codes, the IME, and the widget's `file://` origin. It runs **Tizen 5.0** (`ravilo-tv-5`, the owner's
  set's platform) as well as 10.0. Every piece's QA runs on 5.0 first. Each piece's QA ticket names that row.
- **On the owner's TV, once the app is in good shape.** This is the only place the following can be
  answered. They are carried there, not guessed at:
  - R264's open question 1: can HTML chrome and subtitle text draw over AVPlay on Tizen 5.0?
  - 247's open question 1: what `Origin` does Tizen 5.0 send on the events handshake? If it sends
    `null`, the socket is refused. Browsing and playing from the TV still work, but FR-10 does not, and
    247 needs a different answer.
  - The set's real user agent. FR-13's floor is first checked on the 5.0 emulator; the TV confirms it.
  - FR-14's budgets.
  - R269's IME assumption.
  - The distributor certificate for the TV's DUID, which waits until this test (DEB-799).

## Later — not v1, in no order

- Live TV, the guide and the *On now* row (R189's `LiveTvGuideScreen`/`LiveTvPlayerScreen` as
  reference).
- Person pages and the detail page's cast row.
- Browse filters and facets (R190).
- The focus-detail panel (phase 202).
- Radio, music and audiobooks.
- Support (DEB-1501).
- Playback sessions: *Move to…* and *Play here* (R372).
- Single sign-on on the TV.
- webOS (R264 FR-R264-8's `.ipk`).

## Non-goals

- **Discover**, and everything under it: Coming Soon, Networks, Studios, Genres, Request, and Seerr
  search.
- **Casting from the TV**, to a Chromecast, another TV or anything else.
- **Downloads.**
- Compose, WebAssembly, or a store submission. The store stays R272's manual step.
- **Parity.** A TV feature specced for Android TV does not reach Tizen unless that spec, or an
  amendment here, says so (card question 3).

## Build order

These are one piece each for one engineer. Each piece is `blocked` by the one before it, and each ends
with its Playwright spec and its QA ticket.

1. **The shell.** The router and back stack, the spatial focus engine, the key map, the top bar and the
   base CSS, with FR-13's guard script. R264's player is hosted as a route, and the receiver's existing
   Playwright specs stay green.
2. **Sign-in and profiles** (FR-1 to FR-3): `QrEncoder` moves to `shared`, the code and password
   screens, the multi-profile store, the picker, dropping the `screen` tokens, and the events socket
   under a profile token.
3. **Home** (FR-5), with channel feeds.
4. **Movies, Series and My List** (FR-6).
5. **A title's page** (FR-7).
6. **Playing from the TV** (FR-9) and **a phone sending to it** (FR-10).
7. **Search** (FR-8).
8. **Settings** (FR-11), and the move of the player's strings into `ravilo-i18n` (FR-12).

After 8: QA's full pass on the emulator, then the owner's real-TV test (FR-16).

## Open questions — on DEB-4654's owner card

1. **The constitution** (see *Scope*). Recommended: amend it for exactly one DOM client on pre-WasmGC
   TVs, with screens limited to this spec's list.
2. **Keep a phone sending to it** (FR-10)? Recommended: keep it. It is shipped code and almost free.
   *No casting* is read as *the TV casts to nothing*.
3. **A fixed feature list, not parity?** Recommended: yes. Tizen gets only what this spec lists, plus
   explicit amendments, so a TV change does not double in cost.

## Amendment 2026-11-15 (DEB-4653's measurement) — the bundle must parse on Chromium 63

Claude Top installed v1.67's `ravilo-tizen-1.67.wgt` on a Tizen 5.0 TV emulator (`ravilo-tv-5`, DEB-4653). It
installs and launches, then stays black: `Uncaught SyntaxError: Unexpected token ?` at column 150777 of
`ravilo-screen.js`. The bundle holds 201 `??` operators (ES2020) from Kotlin 2.3's output, so no Kotlin code
runs at all. The same build reaches the setup screen on the 10.0 emulator. **The receiver as shipped has
therefore never been able to start on the owner's TV.** FR-13 changes:

- The bundle is transpiled to Chromium 63 at build time. For example, a `babel-loader` with
  `@babel/preset-env` targeting `chrome 63`, added through `ravilo-screen/webpack.config.d/`. The exact means
  is the build piece's choice; the outcome is the requirement. This is a new build dependency and stays
  with Sjúrður.
- The second guard (FR-13) parses the built bundle as ES2017 in CI, so the regression cannot come back
  unseen.
- Both land in build piece 1 (DEB-4656), **before** any screen. The same fix makes today's receiver start on
  Tizen 5.0, so it is worth having even if the owner's card stops the full app.

