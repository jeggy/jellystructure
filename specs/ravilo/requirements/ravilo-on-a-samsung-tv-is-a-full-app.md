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
- **Constitution.** The Ravilo constitution (Technology Mandates; principles 7 and 8) made Compose
  Multiplatform and `:ravilo-ui` the only home for Ravilo screens. The owner allowed this client as the
  one exception on 2026-11-15 (*Owner decisions* below), and the constitution now says so: one
  Kotlin/JS DOM client, only for TVs that cannot run the Wasm build, with this spec's list rather than
  parity, and `shared` and `ravilo-i18n` for every route, DTO and string.

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
- A row kind the app does not know draws as a plain `CUSTOM` row, and the rest of Home draws (R318
  FR-R318-1; amended 2026-11-16, see below).
- **Live TV is not in v1.** `live_tv_home` is treated as absent.
- A channel button opens that channel's feed (`getChannel`) with the same renderer.
- `focus_detail` is honoured as `none`. The focus-detail panel comes later.
- While the feed loads, Home draws FR-20's skeleton (amended 2026-11-17, DEB-4889, see below).

**FR-6 · Movies, Series and My List are grids.**

- Each one is `browse` with the matching `BrowseKind` (`movie`, `series`, `mylist`) and the Android TV
  app's default sort.
- A grid loads the next page as focus nears its end.
- My List with nothing in it shows the Android TV empty line.
- **No filter or facet UI in v1.**
- A grid's first page loads under FR-20's skeleton (amended 2026-11-17, DEB-4889, see below).

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
- The header carries the audio and subtitle language flags (FR-19), and the page draws FR-20's skeleton while it
  loads (both amended 2026-11-17, DEB-4889, see below).

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

**FR-10 · A phone can still send a title to it.** *(Owner, 2026-11-15: keep it.)*

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
  amendment here, says so (owner, 2026-11-15).

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

## Owner decisions, 2026-11-15 (DEB-4654's card)

1. **Old Samsung TVs may have their own screens:** *"Yes, old Samsung TVs only."* The Ravilo
   constitution carries the exception.
2. **A phone can still send a show to the signed-in TV:** *"Yes, keep it."* FR-10 stands. *No casting*
   means the TV casts to nothing.
3. **New TV features reach the Samsung app only when asked for:** *"Yes, a fixed list."* This spec's
   list plus explicit amendments, never parity.

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


## Amendment 2026-11-16 (DEB-4719) — REST from the widget does need a CORS exception on Tizen 5.0

The *No wire change* decision says *"REST from a widget needs no CORS exception (247)"*. On the 5.0 emulator that
is false. Every POST the widget makes (sign-in by both routes, `playback/start`) gets the CORS plugin's empty 403,
because Chromium 63 puts the page's origin on a POST. GETs are unaffected. The fix is server-side only and is
specified in 247's *Amendment 2026-11-16* (FR-247-4): `/api/tv/**` except `/api/tv/admin/**`, and `/api/remote/**`,
admit `file://`. It is not a wire change: no route, field or DTO moves. Every piece of this spec that signs in or
plays is untestable on 5.0 until FR-247-4 is built. 247's open question 1, carried under *On the owner's TV* above,
is now answered on the 5.0 emulator by FR-247-4's acceptance step 1, ahead of the TV.


## Amendment 2026-11-16 (DEB-4658) — an unknown row kind draws as `CUSTOM`, as R318 says

FR-5 said an unknown row `kind` *"is skipped"* and cited R318 for it. R318 says the opposite. FR-R318-1 decodes
an unknown `Row.kind` as `CUSTOM`, *"a plain content row"*, and its acceptance test asks for *"a `CUSTOM` row, the
rest of the feed intact"*. `shared`'s `RaviloWireJson` does exactly that, so after decoding the Tizen app cannot
tell an unknown kind from a real `CUSTOM` row. Skipping the row would also make the TV disagree with every other
Ravilo client. FR-5 now follows R318: an unknown kind draws as a plain row, with the server's title and the
same tiles and focus as any other row. Only an element that fails to decode at all is dropped (R318's *"a list
with one unknown element → the rest of the list"*), and the rest of Home draws. Nothing on the wire changes.
Found by Gunnar on DEB-4658, before any code was written.

## Amendment 2026-11-16, second (DEB-4719) — on Tizen 5.0 the screen signs in by code only

The page sends `Origin: null` on a POST (DEB-4721). 247's FR-247-5 admits `null` on the TV's token routes, but not
on the two that take a password (`/api/tv/login`, `/api/tv/account/password`) and not on `/api/remote/**` (Tróndur's
call, 2026-11-16). On a set like the owner's that changes FR-2 and a part of FR-3. **On Tizen 10.0 nothing changes**:
its POSTs carry no `Origin`.

- **How the screen knows.** A password POST that answers **403 with an empty body** is the CORS refusal. A route's
  own 403 always carries a JSON body. The screen treats that one answer as "this TV cannot sign in with a password":
  - it never shows it as a wrong password, and never as "Couldn't reach the server";
  - it remembers the fact for this install (stored beside the server address, cleared when the address changes);
  - from then on it hides *Sign in with a password instead* on the code screen, so the code is focused first.

  No user-agent or version sniffing: the server's answer is the fact.
- **The message**, shown in place of the password form, with focus on its one button *Use the code instead*:
  *"This TV can't sign in with a password. Use the code with Ravilo on your phone."* New copy, in every language
  the screen already carries.
- **When the code is unavailable** (`available: false`, FR-2's last rule) and the install is known to be code-only,
  the screen does not open the password form. It says *"Sign-in with a code is turned off on this server, and this
  TV can only sign in with a code. Ask whoever runs the server to turn it on."* and offers *Try again*, which asks for
  a code afresh. On an install that has not learned it yet, FR-2's rule stands: the password form opens, the first
  submit gets the empty 403, and this message follows.
- **FR-3, Add user**, takes the same path. A second viewer is added by code.
- **Change password** (`/api/tv/account/password`, if the screen offers it) answers the same way and shows *"This TV
  can't change a password. Change it with Ravilo on your phone."*
- **Next episode.** `POST /api/remote/play` is refused on 5.0. The next-up countdown's play fails with
  the existing play-failure handling. **Accepted for v1.** A later amendment may move the screen's next episode to a
  `/api/tv/` route; it is not part of DEB-4719.
- **Build.** This is client-side, in `ravilo-screen`, and lands after FR-247-5 and DEB-4727. CI covers it with a
  Playwright cell where the mocked password route answers an empty 403.

## Amendment 2026-11-16 (DEB-4663) — what FR-11 and FR-12 mean on the code that exists

- **FR-12's premise.** The player's strings were never in `ravilo-receiver-core`: `ReceiverStrings` is a language
  holder over `ravilo-i18n`'s table, and every player key (`player.*`, `pl.*`, `receiver.*`) already lives in
  `i18n/*.json`. What moves is the dependency. The full app reads its language from its own `ScreenLanguage`, which holds
  only *which* language is drawn, and calls `ravilo-i18n`'s `t` for every string. `ReceiverStrings` is no longer used
  by `ravilo-screen`; it stays for the Chromecast receiver. Nothing in `ravilo-screen` is a table.
- **The language ladder changes (R279).** R279 drew the receiver in the signed-in viewer's server-side `uiLanguage`.
  The full app does not: its language is the one Settings picked on this TV, else the TV's own `navigator.language`,
  else English. *Follow the TV* is the default and the first choice, ahead of English, Dansk and Føroyskt (the table's
  own order). A viewer's server-side language no longer moves the TV.
- **Server version.** *About* reads the server's version from `GET /api/health`'s `version` (224, FR-224-1), an
  unauthenticated route the setup probe already uses. No wire change. A failed read shows `—`.
- **A new address, precisely.** Setup compares the address the viewer typed with the one that was working, ignoring
  case and a trailing slash; a different one removes every stored profile. The prefill keeps an `http://` scheme: with
  it stripped, a household on an `http://` server who pressed Connect unchanged would have read as a new
  address and been signed out. Any reopened setup, including R269's hold on Back, follows the same rule. When DEB-4719's
  *code-only* fact lands, a new address clears it too (it is "stored beside the server address").

## Amendment 2026-11-16 (DEB-4661) — what FR-9 and FR-10 mean before the title's page exists

- **Play is the TV's own `startPlayback`.** FR-9's *Play calls `startPlayback`* is taken literally: the TV negotiates
  the start itself (`hls_only`, the AVPlay/`canPlayType` probes) and resumes from the ticket's `start_position_ms`. It
  does not ask the backend to push a play to itself first, because `POST /api/remote/play` is refused on Tizen 5.0
  (the *Next episode* note in DEB-4719's second amendment). The player is the one a phone's `play_item` drives; only
  where the title's facts come from differs. For a **movie** the TV reads them from `GET /api/tv/movie/{id}` (its
  segment markers and logo), waiting at most 3 s. A detail that does not come is a play without Skip Intro and a logo,
  never a refused one.
- **An episode's context belongs to FR-7's page.** The series, the episode after it and its S·E line are what the title's
  page (DEB-4660) knows. Until it exists, the stand-in page a tile opens plays the item it names, a tile for a series
  has nothing to play, and a start made on the TV carries no *next up*. A phone's push, and a next-up round trip
  already running, keep theirs. The page hands the player a `ScreenPlayRequest`; DEB-4660 adds the episode fields to it.
- **Back on a start that has not begun** (loading, the busy wait, a refusal) gives it up at once and returns to the page
  it came from, in R237's words for nothing: it is not an error. A start that the server opened a session for in the
  meantime is stopped at 0, and the late ticket never opens a stream.
- **The two *Try again from your phone* strings** (`receiver.failed_sub`, `cast.no_server_sub`) keep their keys for a
  start a phone made. A start the TV made says `receiver.failed_sub_tv` (*Press Back and try again.*) and
  `cast.no_server_sub_tv` (*Check that the server is on and try again.*). Only the first is reachable today:
  `ravilo-screen` has no screen that shows the second since the pairing idle screen left.
- **FR-10 needs no new code.** A `play_item`, `playstate_command` or `player_command` already acts as in the receiver, and
  the shell stays mounted under the player, so the end of a phone's play returns to the page and the focused tile the
  viewer was on. The Playwright spec pins it.

## Amendment 2026-11-16 (DEB-4818) — the launcher icon and the on-screen mark are the real Ravilo mark

The owner's first real-Samsung install (Tizen 5.0, v1.69) showed no Ravilo logo in two places. FR-15's *same `.wgt`*
shipped R264's 128×128 placeholder as `icon.png`, and the screens drew `.mark` as a CSS gradient square.

- **FR-17 · The launcher tile is the Android TV launcher art.** `ravilo-screen/wgt/icon.png` is a 512×423 PNG
  (Samsung's TV icon size), the centre crop of `design/ravilo/assets/store/ic_launcher-512.png` on its own `#000B25`
  background. `config.xml` keeps pointing at `icon.png`.
- **FR-18 · `.mark` is an image packed in the `.wgt`.** Every screen that carries `.mark` (idle, setup, no-server, busy,
  failed) draws `ravilo-mark.svg`, a copy of `design/ravilo/assets/brand/ravilo-mark.svg`, from the widget itself. It is
  never fetched from a server, because the setup screen shows before one is known. The 64 px box and the layout
  do not change. The sign-in screen's `Ravilo` word is text, not `.mark`, and stays.
- **Proof.** `scripts/check-tizen-assets.sh` (CI, with `--selftest`) fails when `icon.png` is not 512×423, when
  `config.xml` names a missing icon, or when `index.html` names a `.mark` image that is not in the widget. A Playwright
  assertion in `ravilo-screen.spec.ts` loads the setup screen and requires its mark to be an `<img>` with
  `naturalWidth > 0`. How the tile looks on the Samsung launcher cannot be covered automatically: it goes to QA, on the
  5.0 emulator and then the real TV.

## Amendment 2026-11-17 (DEB-4833) — every brand spot carries the mark

DEB-4818 named only the `.mark` screens. On the real Samsung the owner found the other brand spots still plain white
text: the shell bar's top-left `Ravilo` and the sign-in and profile-picker `Ravilo` words.

- **FR-18 (extended) · every brand spot is the packed mark plus the wordmark.** `.shell-brand` and `.auth-brand` draw
  `ravilo-mark.svg` from the widget (`<img class="brand-mark">`, 44 px in the 92 px shell bar, 36 px on the auth screens)
  followed by the `Ravilo` word, as the Android TV top bar does. This replaces the last sentence of FR-18 above.
- **Proof.** `ravilo-screen.spec.ts` requires an `<img>` with `naturalWidth > 0` in `#shell .shell-brand`, `#signin
  .auth-brand` and `#profile-picker .auth-brand`; `check-tizen-assets.sh` also covers `brand-mark` images. How it looks on
  the Samsung bar goes to QA with the preview `.wgt`.

## Amendment 2026-11-17 (DEB-4832) — a series' tile plays its episode, not the series

The owner's first real-TV play (Tizen 5.0) ended in *That didn't start*. DEB-4661's amendment said *a tile for a series
has nothing to play*, but the stand-in page sent the tile's id to `startPlayback` whatever its kind. A series id is not
playable: Jellyfin answers `PlaybackInfo` with a 400, and the TV showed the generic refusal. The emulator run passed
because its tile was a movie.

- **A series' stand-in page plays the episode the title's page would.** This replaces *a tile for a series has nothing
  to play* in the DEB-4661 amendment above. OK reads `GET /api/tv/series/{id}` and the series' own entry from
  `GET /api/tv/playstate`, and plays, in this order: a newest-first series' newest counted episode; the episode that
  entry's `continue_episode_id` names (in progress, or next up); the first counted episode. *Counted* is
  R346's: season 1 or later, with a Jellyfin item. The TV does not derive progress itself: the server names the
  episode, and the first-episode fallback is what the series' page plays for a series nobody has started.
- **The player gets the episode's context.** The start carries the episode's title, the `S01E02` kicker, the series' name
  and logo, and the counted episode after it as the next-up card, as a phone's push would. Skip Intro and Skip
  Credits markers are the episode's, which the series route does not carry: a series' start made on the TV has none until
  DEB-4660's page.
- **A series whose detail does not arrive in 5 s, or has no episode to play,** says *That didn't start* with the TV
  wording, like any refused start. It never reaches `startPlayback` with the series id.
- **Proof.** A Playwright case in `ravilo-screen-play-from-tv.spec.ts` presses OK on a series tile and requires the
  start to name the episode, never the series; `:shared:desktopTest` covers the episode choice. A real-device play of a
  series' episode goes to QA with the preview `.wgt`.

## Amendment 2026-11-17 (DEB-4660) — what FR-7 means on the code that exists

FR-7's page replaces the stand-in that DEB-4661 and DEB-4832 described. Nothing in FR-7's text changes; these are the decisions it left to the piece.

- **OK on a movie or series tile opens the page** (`getMovie` or `getSeries`, then `getPlaystate` for the title and every episode). The page draws once both have answered, so the first label is the right one. A title the server does not have says *This isn't here any more*, a refusal says *Not available on this profile*, anything else the unreachable line. Back leaves; the failure is not retried by a redraw.
- **The movie's actions.**
  - The primary button reads *Resume · N min left* from the playstate's `resume_ms` and the movie's runtime (R279's `action.resume_mins_left`, as ravilo-ui does), plain *Resume* when the runtime understates the file, *Play Again* on a watched title and *Play* otherwise. FR-7's ‹time› is that time left.
  - *Start over* shows only with a resume point and starts with `start_position_ms: 0` and `start_over: true`. Resume sends no position: the server's playstate decides where it starts, as it does for every start.
  - My List reads `+ My List` or `− My List`, and the watched button `Mark Watched` or `✓ Watched`.
- **The series' actions.** The primary button, My List and the season strip. FR-7 lists *Mark watched* for the movie only, so a series has none in v1.
  - The primary episode is ravilo-ui's `primaryEpisodeId` with the server's answer checked: a newest-first series' newest episode; on a finished series the episode the server names for a re-watch, else the first counted one as *Start over · S01E01* (when the series says it can clear, R343) or *Play · S01E01*; otherwise the Continue Watching episode, one in progress, the first unwatched counted episode. It never offers a watched episode (owner, 10-29).
  - The label is *Resume · S01E02* once anything has been watched or started, else *Play · S01E02*.
  - The strip shows from two seasons, opens on the season that holds the primary episode, and a pill switches by OK. A multi-episode file is one tile.
- **Write-through is the server's answer.** `setFavorite` and `setPlayed` return the new state, which replaces the old one. A refused write (a 404, a network failure) leaves the button as it was, and a second OK is ignored while one is in flight.
- **The page hands the player its facts.** `ScreenPlayRequest` carries a `ScreenPlayContext`: a movie's logo and Skip Intro markers (so the 3 s `getMovie` wait in DEB-4661's amendment now applies only to a play with no page behind it), and an episode's S·E line, its series' name and logo, its own markers and the counted episode after it. A series' own tile id is never started.
- **DEB-4832's stand-in is gone.** `playSeriesFromTv` and its 5 s wait are removed: the page chooses the episode, and a series whose detail does not come shows the failure line instead of the generic refusal. `pickSeriesPlay` stays in `shared` with its test.
- **Proof.** `ravilo-screen-detail.spec.ts` covers the Resume label, Start over, the My List and watched write-throughs (including a refusal), the next-up episode, a finished series, a season switch, an episode tile and the way back from a play. `ravilo-screen-play-from-tv.spec.ts` now goes through the page. The page on a real TV (key codes, focus on a 55-inch panel, artwork) goes to QA with the preview `.wgt`.

## Amendment 2026-11-17 (DEB-4845) — a stream shows its picture: the page is see-through over AVPlay

The owner's first real-TV play that started (Tizen 5.0, `ravilo-tizen-a91fce22.wgt`) was *all black*. The server side was
healthy: the TV's start at 00:56Z negotiated an HLS transcode (h264 SDR, AAC/AC3, 1080p) and handed out the ticket.
Restores FR-9's *the title plays*.

- **AVPlay draws on a plane behind the page.** `html`, `body` and `#video` were opaque `#000`, so the page covered the
  plane: sound, no picture. While `AvPlayBackend` has a stream open, `<html>` carries `avplay-on`; `screen.css` makes
  `html` and `body` transparent under it and hides the `<video>` the AVPlay path never uses. `close()` removes the class,
  so every other screen keeps its own opaque background.
- **The 2019 sets' AVPlay surface.** `index.html` carries `<object id="av-player" type="application/avplayer">`, which
  Tizen 5.0 documents as the element AVPlay draws through; newer sets ignore it.
- **The display rect is the page's viewport**, not `window.screen`: a 4K panel reports 3840×2160 there while the widget's
  coordinate space is 1920×1080.
- **Proof.** `ravilo-screen-player.spec.ts` asserts, with the fake AVPlay, that during a stream `html` and `body` are
  transparent, `#video` is hidden and `setDisplayRect` is the viewport, and that the class is gone after Back. Chromium
  cannot show Samsung's plane, so the picture itself goes to QA with the preview `.wgt` (it is the one check CI cannot make).

## Amendment 2026-11-17 (DEB-4865) — the next episode starts on the TV itself

QA on the Tizen 5.0 emulator (DEB-4841): an episode ran to the end under the next-up card and the TV showed *Couldn't reach
the server*; `POST /api/remote/play` answered 403 and no start for the next episode reached the server. This replaces the
*Next episode* note in DEB-4719's second amendment (*accepted for v1*), which is no longer accepted.

- **A start the TV made knows the episodes after it.** The page that handed the player the episode (DEB-4660) also hands over
  the counted episodes that follow, each with its title, S·E line and segment markers.
- **The next-up card, by countdown or OK, starts the next episode the way FR-9's Play does.** The item it leaves is
  stopped at its position, the TV negotiates the start itself (`hls_only`), and the card on the new episode names the one
  after it. `POST /api/remote/play` is not asked.
- **A start with no such list** (a phone's push) keeps asking `/api/remote/play`, whose push is the whole of what a play
  needs; DEB-4719's refusal of a Tizen 5.0 widget is the same as before for that path.
- **Proof.** `ravilo-screen-play-from-tv.spec.ts` plays a series' episode with `/api/remote/play` answering 403 and asserts
  the next episode's start, no call to the route, and the following card. A real 5.0 set goes to QA (DEB-4860).

## Amendment 2026-11-17 (DEB-4874) — a start over an open stream closes it first

QA on the Tizen 5.0 and 10.0 emulators (DEB-4867): every start made while a stream was open — the next-up card's (countdown and OK),
on a start the TV made and on a phone's push alike — threw `PLAYER_ERROR_INVALID_STATE` from `webapis.avplay.open`, so the next
episode never played; on 5.0 the 403 of DEB-4865's old path had hidden it. Restores FR-9's *the title plays* and FR-10's *a phone's
play takes over*.

- **`AvPlayBackend.open` leaves AVPlay closed first.** `open()` is legal only from `NONE`, so the backend closes whatever is open
  before it opens: every start path is safe, not only the next-up one.
- **A start that does not open has played nothing.** The screen shows the failure line once, closes the server's session for the item
  at position 0, and never reports a stop at a length. Before, the stream that was still open ran to its end under the new item
  and the stop at *its* length marked the episode that never played **watched**. A stream's end is also ignored while no
  stream is loaded.
- **Proof.** The fake AVPlay of `helpers/screen-cast.ts` now throws `InvalidStateError` on `open()` from anything but `NONE`, as the
  device does. `ravilo-screen-play-from-tv.spec.ts` starts a second item over an open stream (a close sits between the opens, no
  failure shows) and fails an `open()` (the failure line, one stop at 0, none at a length). The emulators go to QA with the preview
  `.wgt`: re-run DEB-4867's table on 5.0 and 10.0.

## Amendment 2026-11-17 (DEB-4889) — language flags on a title's page, and real loading states
Task: DEB-4889

The owner, testing v1.70 on the UE55RU7445 (DEB-799, 09:20Z): *"Missing is the language and subtitles flags on the details
page"* and *"The loading states are very ugly. Home page just says 'Home' while loading and the details page just show the
description with big font while loading."* Both are new promises, so they are FR-19 and FR-20 here. Android TV already draws
both, so there is no design round: the Samsung page redraws what Android TV shows, at this page's scale.

What the code does today:
- `ScreenDetail.render` draws no language line at all. `MovieDetail` and `SeriesDetail` already carry `audio_languages` and
  `subtitle_languages`, so the data is on the wire and decoded.
- While anything loads, Home, a channel's feed, a grid and a title's page draw `.shell-placeholder`. That is one 48 px bold
  line of text with a focus ring: *Home*, *Movies*, or the tile's title.
- A Home feed that fails to load is ignored (`result.getOrNull()?.let`), so that line stays on screen for good.

**FR-19 · A title's page shows its audio and subtitle languages as flags.** This is Android TV's `AudioSubtitleFlagLine`
(R134, R239, R247), redrawn in DOM.

- **Where.** One line in the header, under the meta line and above the overview, on a movie's page and a series' page alike.
  It reads `AUDIO` and its flags, a `·`, then `SUBTITLES` and its flags, using the existing `fd.audio` and `fd.subs` keys
  (FR-12).
- **What it counts** is Android TV's rule, unchanged:
  - Each code is canonicalised first (R247, `canonicalLanguage` in `shared`), so `da` and `dan` are one language.
  - A group shows at most five flags. Every other language goes into an honest `+N`, and that includes a language that has
    no flag (FR-R239-1).
  - A group whose languages all lack a flag still shows its label and `+N`, so it never looks like a title with none
    (FR-R239-2). A group with no languages is left out. When both groups are empty, there is no line and no space is held
    for one.
  - Tamil and Telugu stay without a flag (R239's closed decision).
- **One table, not two.** The language → flag table and the counting rule move from `ravilo-ui`'s `AudioFlagStrip.kt`
  (`LANG_CC`, `flagFor`, `countFlagStrip`) into `shared` (`commonMain`), keyed by the flag file's country code (`gb`, `fo`,
  …). There is no change in behaviour, the same move FR-2 made for `QrEncoder`. `ravilo-ui` keeps only the country code →
  drawable lookup, and `AudioFlagStripTest` stays green. The Samsung app never carries a table of its own.
- **The flag images** are `ravilo-ui`'s 41 `flag_*.png` drawables, packed into the `.wgt` as `flags/<cc>.png` from that one
  source at build time, so no second set is committed. A flag is never fetched from the server. They are about 200 KB in
  total and sit outside FR-14's bundle budget. A page loads at most ten of them.
- **Size, at this page's scale.** Each flag is 39×27 px with 3 px corners and a 1 px border at 18 % white. The label is
  14 px semibold, letter-spaced 1 px, at 55 % white. `+N` is 15 px semibold at 70 % white. Flags sit 9 px apart and the two
  groups 18 px apart. Use `margin-left` between siblings, never flex `gap` (FR-13). Five plus five with both `+N`s fits in
  the header's 62 % column at 1920×1080. If a build ever finds it does not, the larger group gives up a flag to its `+N`
  until the line fits, the same as Android TV. It never clips.
- **Not focusable.** The line is facts, and the D-pad passes over it.

**FR-20 · Every page that loads draws a skeleton, never a word.** This is Android TV's `HomeLoadingShell` and
`DetailLoadingShell`, and the grids' `TileShimmerGrid` (DEB-4091). The skeleton is grey blocks in the places and at the
sizes of what will replace them. It holds no text at all: no heading standing in for a page, and no title in large type.
`.shell-placeholder` is no longer used for loading.

- **Home and a channel's feed** (FR-5):
  - a hero block the full width of the page and 56 vh tall, the default of the feed's `hero_height_pct`;
  - then two rows. Each row is a heading bar 240×26 px, then poster blocks at `.home-tile`'s size (176×265 px, 11 px
    corners), each with a caption bar 130×18 px under it. A row holds as many blocks as fill the width.
- **A title's page** (FR-7):
  - the backdrop block in `.detail-backdrop`'s place and size, with its existing bottom gradient;
  - in the header: a title bar 560×56 px, a meta bar 320×22 px, two overview bars at 80 % and 60 % of the 62 % column,
    21 px tall;
  - two button blocks at `.detail-btn`'s height, 240 px and 200 px wide, with 11 px corners.

  Android TV's shell is the same for a movie and a series, so the series' strip has no skeleton.
- **Movies, Series and My List** (FR-6): the page's real heading stays, because it is the page's title and not a stand-in.
  Under it are a count bar 200×22 px and the grid's six columns of poster blocks, as many rows as fill the screen.
- **How it looks.**
  - The blocks are the surface colours the page already uses: `#202431` for tiles, bars and buttons, and `#161922` for the
    backdrop and the hero.
  - The whole skeleton is one element whose opacity eases from 1 to 0.55 and back over 1.2 s, for as long as it is on
    screen. That keeps FR-14's *opacity and transform only* and gives a 2019 set one animated layer, not one per block.
  - Android TV's moving gradient is not copied, because it would need a per-block animation or a mask.
- **Focus.** The block where focus will land is the focus target and carries the normal ring: the hero on Home, the first
  poster on a grid, the primary button on a title's page. It keeps today's `shell-page-target` id, so the router's handover
  to the real target (DEB-4660) and Back and Up behave as they do now. OK on a block does nothing.
- **When content arrives**, the skeleton is removed in the same frame and the content fades in from 0 to 1 opacity over
  220 ms (DEB-2204 FR-5). A page drawn from what the route already holds does neither. That covers Back to Home, and a
  title's page redrawn after a write-through or a season switch.
- **A backdrop or hero image** fades in over 220 ms when it loads, on top of its surface block. A slow image therefore never
  leaves a bare black area.
- **A load that fails replaces the skeleton. It never pulses forever.**
  - A title's page keeps DEB-4660's failure lines.
  - Home, a channel's feed and a grid's first page show *Couldn't reach the server* (`error.play.unreachable.title`) and its
    body line, with one focused *Retry* button (`action.retry`) that loads again under the skeleton.
  - The failure is drawn as a title and a body line in the page's type, never as a 48 px bare line.
  - Today, a Home feed that fails to load is never reported to the viewer at all.
- **Proof.**
  - **Flags:** `ravilo-screen-detail.spec.ts` gains a movie whose mock detail has three audio languages and seven subtitle
    languages, one of them unmapped. It asserts three flag `<img>`s with `naturalWidth > 0`, five subtitle flags and `+2`,
    and the `fd.*` labels. A series with no languages has no line. The moved counting rule keeps `AudioFlagStripTest` and
    gains a `:shared:desktopTest`.
  - **The packed flags:** `check-tizen-assets.sh` fails when a country code in `shared`'s table has no `flags/<cc>.png` in
    the widget.
  - **Skeletons:** the home, detail and browse specs hold the mock's answer back and assert the skeleton is present, holds
    no text, and has the ring on its focus block. Released, they assert the content draws, focus moves to the real target,
    and the skeleton is gone.
  - **Failure:** a 500 on the feed shows the failure and *Retry*, and *Retry* loads the feed.
  - **CSS:** `check-tizen-engine.sh` stays green.
  - **On the TV:** how the flags read and the pulse looks on the 55-inch panel goes to QA with the preview `.wgt`, on the 5.0
    emulator first.
- **No wire change.** No route, field or DTO moves. The flags are already in both detail DTOs.

## Amendment 2026-11-17 (DEB-4900) — the launcher tile is the wide Ravilo banner, not the square mark

Owner, DEB-799 (2026-11-17): on the Samsung home bar the Ravilo tile is a square picture of the mark, while every other
app's tile is wide with its name beside it. This amends **FR-17**, whose text above (*the centre crop of
`ic_launcher-512.png`*) no longer holds; the rest of FR-17 stands.

- **FR-17 (amended) · The launcher tile is the Android TV banner.** `ravilo-screen/wgt/icon.png` stays a **512×423**
  PNG (Samsung's TV icon size; Tizen's `config.xml` takes one `<icon>` file and ignores `width`/`height`, so there is
  no second, wider icon to name) and `config.xml` keeps pointing at `icon.png`. Its picture is
  `design/ravilo/assets/store/tv-banner-1280x720.png`: the mark on the left, the *Ravilo* wordmark and its gradient
  underline beside it, on the banner's own `#000B25` ground. The banner's tagline is not on the tile. The banner is
  cropped to 871×720 around the mark and wordmark and scaled to 512×423 **without distortion** (0.588 both ways), then
  nudged 20 px down so the group sits at the tile's optical centre.
- **Proof.** `scripts/check-tizen-assets.sh` already fails on any other size, so it needs no change. Whether the tile
  reads as wide on the Smart Hub bar cannot be covered automatically: it goes to QA with the signed preview `.wgt`, on
  the owner's Tizen 5.0 TV. If that tile still shows letterboxed, the 512×423 canvas is the limit and the next step is
  a Samsung-side question, not a different file.

## Amendment 2026-11-17 (DEB-4899) — the audio and subtitles picker leads with the language and its flag

Owner, DEB-799 (2026-11-17, photo from the UE55RU7445): *"There is no flags and only unusable text for the audio tracks and
sometimes the subtitles take as well."* The Lyd tab read `E-AC-3 Atmos 5.1`, `E-AC-3 5.1` ×4: five different languages, told
apart by nothing, and the focused row was light grey on a white pill. FR-9 says the TV's picker is R264's two-level picker,
which is R180's and R195's, and R180 (FR-RV-ASP1-1) leads a row with the language's own name. The Samsung picker named a row
from the start of the track's label, which for these files is the codec, because it had no language-name table (R264's doc
comment: *"a receiver has no language-name table of its own"*). That is a miss against FR-9 and R264, but the flag, the second
line and the focus colours are not written down anywhere for this app, so they are FR-21 here.

**FR-21 · A picker row is the language's flag and own name, then what the track is.** Android TV's picker, redrawn in DOM.

- **The name** is the language's own name from `shared` (`endonymOf`: `dan` → *Dansk*, `deu` → *Deutsch*), the table
  `ravilo-ui` already reads. It moves from `ravilo-ui`'s `LanguageIdentity.kt` into `shared` with no change in behaviour,
  the same move FR-19 made for the flag table, and `ravilo-ui` keeps thin readers of it. Only a code the table does not know
  falls back to the label's first words and then the code.
- **The flag** leads the row: the packed `flags/<cc>.png` of FR-19, 36×25 px, drawn for Audio and Subtitles level 1 rows
  (not *Off*, not *Subtitle size*, not a language with no flag). One table, never a second.
- **The second line** is what the label says beyond the language (*Dolby Digital 5.1* from *Dansk - Dolby Digital 5.1*), and
  for audio the track's codec and channels when the label says nothing else; then Android TV's badge words
  (*Signs only*, *Sound described*, *Describes action*, *Commentary*). A row for a language with several versions shows
  `N versions ›` and opens level 2 as before, where each row is its version's own title, else *Version n*.
- **The focused row is readable.** Near-black text (`#05070c`, 700) on the light pill, and a second line of `#2a2f3d`, so
  the contrast is above 12:1 for the name and above 7:1 for the second line. No translucent tint of the pill.
- **Proof.** `ravilo-screen-play-from-tv.spec.ts` plays a title whose ticket has five E-AC-3 5.1 tracks in five languages and
  a second English version, and asserts the names, five flags with `naturalWidth > 0`, the second lines, level 2's two
  versions and both focus contrasts; `:shared:desktopTest` gains `LanguageNamesTest`, and `LanguageIdentityTest` stays green.
  How it reads from the sofa goes to QA with the preview `.wgt`.
- **No wire change.** The ticket already carries `language`, `label`, `codec` and `channels` for every track.

## Amendment 2026-11-17 (DEB-4925) — leaving Ravilo and coming back

Owner on DEB-4918, real UE55RU7445 (Tizen 5.0), preview `ravilo-tizen-af0f22dc.wgt`: with Ravilo left open and another app in
use for a longer while, going back showed an app that took no key at all; a long press of Back force-closed it. *"the app was just
stuck after the comeback and couldn't navigate or anything."* How long, which other app and whether a film was running are not
known. The app has no reaction to its page being hidden and shown again, so the amendment fixes what is certain to be missing and
leaves the cause to be confirmed on the set (the QA ticket asks for it).

**FR-22 · Hiding the app ends a stream the way Back does; coming back starts from a live app.** Tizen fires the page's
`visibilitychange` when the viewer leaves for another app, the home screen or another input, and again when they return.
- **Hidden with a stream open or starting** (playing, paused, buffering, or loading/busy before the first frame): the screen stops
  it exactly as the remote's Back does, once — the stop report with the position (the resume record), AVPlay closed, `avplay-on`
  removed, the idle screen, the status report. AVPlay's native plane does not survive the app being sent away and is not
  restored; the return is a fresh start from the resume point, never an automatic one. A hidden page with no stream changes nothing.
- **Visible again:** the events socket is dropped and opened afresh (a socket that sat through a long suspend is dead, and its
  reconnect backoff may be at its ceiling), the active viewer's config is read again, and focus is put back on the page's
  target. When the app was away for **5 minutes or more** and the TV shell is the screen (not sign-in, the profile picker or
  server setup), the shell goes back to a freshly loaded Home — the stack and every request still in flight from before the
  suspend are abandoned, so no skeleton waits on a connection the TV no longer has.
- A hide that follows a hide, or a return that follows a return, does nothing the second time.
- **No wire change.** The stop is the existing `playback/stop`; nothing is added to a DTO.
- **Proof.** `ravilo-screen-lifecycle.spec.ts` plays a title on the fake AVPlay, fires a hidden `visibilitychange` and asserts
  one `close`, one stop reported with a position, the idle screen and `html` without `avplay-on`; then returns after the time
  threshold and asserts a freshly loaded Home and a new events socket. Whether this is what the owner saw is **not** proved
  by CI: Tizen 5.0's real `visibilitychange`, AVPlay across a 20-minute absence and a resume from another app go to QA on the
  Samsung with a preview `.wgt`.

## Amendment 2026-11-17 (DEB-4924) — the launcher tile is 16:9, because the Smart Hub bar draws the icon at its own shape

Owner, DEB-4918 (2026-11-17), real UE55RU7445 (Tizen 5.0), two photos of the home bar with the DEB-4900 tile beside the
stock tiles: Internet and Chromecast are about 1.7:1 (16:9), and Ravilo's 512×423 tile is about 1.24:1. The bar does
not letterbox or stretch an icon into a fixed slot; it draws it at the icon's own aspect. This **supersedes the
512×423 size in FR-17 as amended by DEB-4900**, and the "Samsung-side question" that amendment reserved is answered:
the canvas was the limit, and the fix is a wider canvas.

- **FR-17 (amended again) · The launcher tile is a 512×288 (16:9) PNG.** `ravilo-screen/wgt/icon.png` is the Android TV
  banner (`design/ravilo/assets/store/tv-banner-1280x720.png`) cropped to 848×477 around the mark and wordmark
  (x 240–1088, y 88–565, which keeps the tagline out) and scaled to 512×288 without distortion. `config.xml` still
  points at `icon.png`.
- **Proof.** `scripts/check-tizen-assets.sh` now fails on any size but 512×288 (its self-test moves with it). Whether the
  tile reads as wide on the bar goes to QA with the signed preview `.wgt`, on the owner's TV.

## Amendment 2026-11-17 (DEB-4932) — an episode's audio and subtitle choice carries to the next one

Owner, DEB-4932: *"Tizen tv doesn't remember audio changed for one episode when starting the next episode."* FR-9 makes the TV's
player R264's, and R264 is a receiver: the phone that pushed a title had already chosen its tracks, so the receiver never
needed a memory of its own. Since FR-9 the TV starts titles itself, and nothing on it remembers anything. Every start
(`negotiate` in `Screen.kt`) calls `startPlayback` with no `audio_language`, so the server plays the file's default track, and
the subtitle is whatever the ticket flags as default. A pick is kept only until the stream changes. R181's promise
(FR-RV-TRK1-4 to FR-RV-TRK1-6: *"the language never silently resets mid-binge"*) was never made for this app. It is made here
as FR-23, on R181's model.

**FR-23 · The TV remembers the viewer's audio and subtitle choice, per profile, on the set.**

- **What is remembered.** R181's `RememberedChoice`, unchanged: audio language and version signature, subtitle language and
  version signature, or subtitles *Off* as a real choice. There are two tiers, as in R181: one per **series** (a film is its own
  bucket) and one **global**. Each tier is per signed-in profile (`activeUserId`). It is stored in the TV's `localStorage` only.
  Nothing goes to the backend, as R181's non-goal says, and a profile's memory is deleted when the profile is removed
  (`ScreenProfiles.remove` / `clear`).
- **What writes it.** Every audio or subtitle pick while a title plays writes both tiers: the TV's own picker
  (`PickerAction.Audio` / `.Subtitle`) and a phone remote's `set_audio` / `set_subtitle`. Choosing *Off* writes *Off*. The track
  the player picked by itself at a start writes nothing.
- **The series key.** `ScreenPlayContext` gains the series id. A title's page and the next-up card already know it (DEB-4660,
  DEB-4865). A phone's push carries no series id, so its start reads, and its picks write, the global tier only. There is no
  wire change: `PlayItemEnvelope` stays as it is.
- **Audio at a start.** Every start, whether Play, the next-up card or a phone's push, sends the remembered `audio_language` and
  `audio_variant` with `startPlayback`. It sends the series tier's choice, or the global tier's when the series has none. These
  are the existing `PlaybackStartRequest` fields Android TV already sends. The server resolves them with R291's shared
  `resolveAudioChoice`, so the stream starts in that language with no second request. If the ticket shows the series language
  is not in this file but the global language is, the player switches to the global language once, the way a pick does
  (`applyAudio`). If neither language is in the file, the file's default track plays, as it does today.
- **Subtitles at a start.** The subtitle is resolved against `receiverSubtitles(ticket)` with R181's tier order and R235's
  rules: the series tier, then the global tier, then today's default. A remembered *Off* shows none. A match that is a text track
  is shown at once. A match that is only a picture subtitle is burned in, exactly as a manual pick does (`applySubtitle`). The
  rules are the ones `ravilo-ui`'s `resolveTrackChoice` already uses (the tier walk, `sameLanguage`, and the PLAIN-over-SDH and
  forced rules). They move into `shared` with no change in behaviour, the same move FR-19 and FR-21 made, so the Samsung app
  never carries a second copy. `PlayerScreenTrackResolutionTest` stays green.
- **No wire change, no server change.**
- **Proof.**
  - `ravilo-screen-play-from-tv.spec.ts` plays a series episode whose ticket has English (default) and Danish audio, picks
    Danish, and lets the next-up card start the next episode. It asserts that the next `playback/start` body carries
    `audio_language: "dan"` and that the second ticket's Danish track is the one shown as selected.
  - The same for a subtitle: pick Danish on one episode, and the next episode shows Danish. Pick *Off* on one episode, and the
    next episode shows none, even where the file flags a default subtitle.
  - A second profile on the same set starts the same episode on the file's default.
  - The shared rule gains a `:shared:desktopTest` beside the moved code.
  - Whether a binge keeps its language on the real set goes to QA with the preview `.wgt` (two episodes in a row, on the
    UE55RU7445).

## Amendment 2026-11-17 (DEB-4947) — the way back to setup is *Wrong server?* on the sign-in screen, not a hold on Back

QA on DEB-4790 (v1.70, Tizen 5.0 and 10.0 emulators) tried to get back to setup from the sign-in screen with no stored profile.
They held Back for 3.6 s, and the app left to the TV home at 1.6 s on 5.0 and 2.4 s on 10.0. Setup never opened. A page-level key
logger (capture phase) saw no Back `keydown` or `keyup` at all, only `pagehide`. A short Back reaches the page normally. So the
runtime takes a long Back for itself before the page sees it, and FR-11's three-second hold can never fire. That matters: with no
stored profile, Settings sits behind sign-in, so a household whose server has moved has no way back to setup short of a reinstall.

R269's owner decision (2026-09-18) turned down a visible *Change server* item for one reason: *"this app has no navigation"*. The
full app has navigation, and it already shows *Change server* in Settings (FR-11). Android TV's sign-in already ends in a *Wrong
server?* link (R225, `LoginScreen`), and FR-2 says this screen is the Android TV one. Tróndur routed this on DEB-4947. **This
supersedes FR-11's last bullet and R269's FR-R269-7 for this app.**

**FR-11 (amended) · The sign-in screen ends in *Wrong server?*, and the hold on Back goes.**

- **Where.** *Wrong server?* is the last focusable item on the sign-in screen, in each of its views: the code, the password form,
  DEB-4719's code-only message, and the could-not-reach state. Under it is one quiet line with the server's host, without the
  scheme. It works like Android TV's `ServerIndicator` and R269's FR-R269-6, and it is not focusable.
- **Copy.** The existing key `login.change_server`, which Android TV already uses, in all three languages. No new key.
- **What it does.** Exactly what Settings ▸ *Change server* does, by the same code path (`reopenServerSetup`). It opens R269's
  setup screen with the address prefilled and runs the same probe. DEB-4663's rule for a new address applies:
  - A different address (case and a trailing slash ignored) removes every stored profile and the code-only fact.
  - The unchanged address removes nothing.
- **Focus.** Down from the item above it reaches it, and Down from it goes nowhere. It is never focused first. When the screen
  opens, focus goes where FR-2 and DEB-4719 put it: *Sign in with a password instead*, or the code on a code-only install, or
  *Retry* in the could-not-reach state.
- **With profiles too.** It also shows when *Add user* (FR-3) opened the screen, as Android TV shows it beside *Cancel*. A short
  Back there still returns to the profiles. An accidental press costs nothing, because Connect on the prefilled address keeps
  every profile.
- **The hold goes.** Both hold paths in `Screen.kt` are removed: the sign-in one, and the R264 idle one, whose screen no longer
  exists (FR-1). `backHeldSince` and its `keyup` listener go with them. A Back of any length on the sign-in screen now does what a
  short Back does today. When the TV takes a long Back to leave the app, that is the platform's behaviour, and the app does not
  try to stop it.
- **For any future hold logic** (from QA, DEB-4790): on Tizen 5.0 a held key arrives as a stream of fresh `keydown`s about 108 ms
  apart, each with `KeyboardEvent.repeat === false`. A guard like `!e.repeat` therefore cannot tell a hold from repeated presses.
  A long Back never reaches the page at all, on either emulator. Nothing in this app may depend on a held Back.
- **R269's receiver hold (FR-R269-7) needs no row of its own.** The idle receiver screen it lived on is gone (FR-1), the `.wgt`
  is this app (FR-15), and the Chromecast receiver has no setup screen (R269, *Why the Chromecast receiver never needed this*).
  R269 has a matching amendment.
- **No wire change, no server change.**
- **Proof.**
  - `ravilo-screen-signin.spec.ts`, with a server stored and no profile:
    - Down from *Sign in with a password instead* reaches *Wrong server?*, and the host line shows the server.
    - Enter opens setup with the address prefilled, and Connect on it returns to sign-in.
    - Holding `Escape` for 3.5 s does not open setup.
  - The same spec, with one profile stored, through *Add user*:
    - *Wrong server?* then Connect on the same address keeps the profile in `ravilo.screen.tokens`.
    - A different reachable address leaves no profile.
  - The could-not-reach state: Down from *Retry* reaches *Wrong server?*.
  - **FR-16's real-TV list gains one item.** On the owner's set, *Wrong server?* opens setup from the Samsung remote. Whether a
    long Back on the real set also leaves the app is recorded but not fought.

## Amendment 2026-11-17 (DEB-4808) — a phone's *Play on* reaches the TV as a `session_load`, and the TV plays it

Found by QA on the Tizen 5.0 emulator (DEB-4808, step 6): *Samsung TV* was listed in Ravilo Web's *Play on*, a send made the backend log
`start film on screen-… → session_load`, and the TV never followed with a `playback start`. FR-10 said a phone's `play_item` acts as in
the receiver, but the *Play on* list does not push a `play_item`: it starts a session (R370), and the backend sends the place a
`session_load` (R380, FR-R380-11). The Tizen app had no handler for it, so it was dropped and the session ended `failed` after 10 s.
This restores FR-10's *A phone can still send a title to it*; it is not a new behaviour.

- **A `session_load` of a film or an episode starts as a `play_item` does.** The item is `items[index]`, the start is `start_ms`,
  the title is the envelope's. A film also reads `GET /api/tv/movie/{id}` for its logo and Skip Intro markers, waiting at most 3 s;
  a detail that does not come is a play without them. The session joins on the item the TV then reports, so the TV sends no
  session id. Everything after the start is FR-9's: progress, Stop from the phone, the page and tile the viewer was on.
- **An episode started this way carries no *next up* and no S·E line** (the envelope has neither); DEB-4660's page owns an episode's
  context, as in the 2026-11-16 amendment.
- **Any other kind is refused, not dropped.** A `session_load` of `music` or `audiobook` is answered on the socket with
  `{"type":"session_load_failed","id":<session>,"kind":<kind>}`, as every Ravilo app does (FR-R380-11), and nothing starts.
- **No wire change.** The envelope and the refusal frame already exist; the app declares nothing new on its socket (`plays=` absent
  reads as `video`).
- **Proof.** `ravilo-screen-play-from-tv.spec.ts`: a `session_load` of a film starts it (one `/api/tv/playback/start`, no
  `/api/remote/play`), Stop from the phone returns to the page; a `session_load` of a song sends `session_load_failed` and starts nothing.
- **Not covered by CI, so it stays with the QA ticket:** that the TV is offered at all on Tizen 10.0 (DEB-4808 found *Samsung TV*
  missing from Ravilo Web's list there while its socket was connected), and the stop-returns-to-the-same-focus half on a real emulator.

## Amendment 2026-11-19 (DEB-5024) — a picture that cannot keep up steps down instead of stopping

Owner, DEB-5024: *"The video is often buffering on tizen tv."* The trade-off is settled (DEB-3093): lower the quality before
ever stopping the picture.

**What prod shows.** The household's Samsung made 53 starts from 2026-11-16 to 11-19, from outside the house. Every one logged
`takes no record → cap none` and `no ladder: adaptive=false reencodes=false`. Jellyfin copied the picture into a single-variant
stream at the file's own bitrate, from 1.8 to 9.7 Mb/s (most near 5.5). No Samsung has ever written a `playback_qoe` row. A stall
is therefore invisible to the server, and a stall cannot be told from a pause. Between 10 and 25 s of wall time went missing from
each short episode, and up to 2 minutes from some plays. This is DEB-4541's class (one stream, nothing lower to fall to, and a
server that learns nothing), and neither that spec nor 308 reaches this app:

1. **The TV says it does not adapt.** `AvPlayBackend` declares `hls_only` but leaves `hls_adaptive` false. FR-9 says the TV
   negotiates *"with the same negotiation as the receiver"*, and the receiver declares `hls_adaptive = true`. So every master
   the TV gets has one rung: our encoder cuts its plan to one rung (`startRungFor`), Jellyfin's ladder is skipped, the 8 Mb/s
   no-record cap does not apply, and DEB-4541 FR-1 (a video copy goes to our ladder) is keyed on `hls_adaptive`.
2. **The TV reports nothing.** FR-R264-1 says the app *"reports QoE and start samples like any TV"*. That part was never built.
   `onbufferingstart` only draws the buffering view and sends `ScreenStatus`. There is no QoE post and no speed probe, and
   there is no `device_stream_record` row.
3. **A stall with nothing below it repeats.** Nothing on the TV asks for a lower picture.

FR-24 and FR-25 restore FR-R264-1 (FR-25 in DEB-4541 FR-3's shape), FR-26 restores FR-9, and FR-27 is DEB-4541 FR-4 made for
AVPlay. This
amendment makes no backend change, no wire change and no new field. An installed `.wgt` that predates it keeps negotiating as
today.

*Not part of this, already fixed:* 8 of the 53 plays restreamed about 16 s in for an audio pick
(`PlaybackInfo(restream, no burn-in) … audio=2`). The set runs a build from before FR-23 (DEB-4932), and FR-23 sends the
remembered audio with the start. A current `.wgt` on the set removes that stop.

**FR-24 · The TV measures its path, as Android TV does.** The app calls `TvApiClient.probe()` (309, FR-309-3) in two places.
The first is when a title's page opens; that is Android TV's `DetailPrewarm` moment. The second is when a phone's `play_item`
or `session_load` starts a film or an episode, as `RaviloApp` does for a start from another app. It is fire-and-forget:
nothing waits on it. The server answers 204 when the TV was measured within 24 h, so a binge makes no extra probes. The result
lands in `device_stream_record` through the existing `/api/tv/probe/result`. From then on, 309's take caps the TV's negotiation
like any other device's.

**FR-25 · The TV reports what each item went through.** The app posts `PlaybackQoeReport` through the shared client's
existing QoE call, in the Android/receiver shape (DEB-4541 FR-3):

- `per_item = true`. Every count is this item's own and resets at each `open()` (R381, FR-R381-1).
- `first_frame_ms` runs from `open()` to the first `onbufferingcomplete` followed by a moving `oncurrentplaytime`.
  `start_variant_bps` is the ticket's own.
- Every wait after the first frame that lasts **1 s or more**, from `onbufferingstart` to `onbufferingcomplete`, counts in
  `rebuffer_count` and `rebuffer_ms`. Each one is also a `QoeStall` entry with these fields:
  - its position, its length and `after_first_frame_ms`;
  - `buffered_ms = 0`, because AVPlay does not expose it;
  - `phase`: `start` within 10 s of the first frame, otherwise `mid`;
  - `cause = buffer_empty`;
  - `variant_bps`: AVPlay's `getStreamingProperty("CURRENT_BANDWIDTH")` when it answers a number, otherwise omitted.

  A wait the viewer caused (a seek, a pause, an audio or subtitle restream) is not a stall.
- A rung switch counts in `variant_switches_down` and `variant_switches_up` when AVPlay's `onevent` reports a bitrate or
  resolution change (`PLAYER_MSG_BITRATE_CHANGE`, `PLAYER_MSG_RESOLUTION_CHANGED`). A set that never fires them reports 0.
- `encoder`, `direct_play = false` and `link_kind = "unknown"` come from the ticket and the capabilities, as today.
- A report is posted when a stall ends, at a rung switch, and when the item ends (stop, Back, hiding the app under FR-22, the
  end of the stream). A stall therefore reaches 309's record within seconds, and so does DEB-3093 FR-7's hold. A failed post
  is dropped: it is never retried and never shown.
- A start that never reaches a picture sets `start_failure_status`, as DEB-4541 FR-6 does on the receiver: the HTTP status
  when AVPlay's `onerror` carries one, and `-1` otherwise.

**FR-26 · The TV negotiates as an adaptive player.** `AvPlayBackend`'s capabilities set `hls_adaptive = true`, which is the
receiver's negotiation as FR-9 says. `hls_hevc`, the codec probes and `hls_only` are unchanged. AVPlay plays a multi-variant
HLS master and picks its own rung, and our master already lists the start rung first (`EncoderPlan`, the master's order). When
the ticket carries `start_variant_bps`, the app also calls `setStreamingProperty("ADAPTIVE_INFO", "STARTBITRATE=<bps>")`
before `prepareAsync`, so the first picture comes from 309's start rung, not from AVPlay's guess. With the backend as it stands
and DEB-4541 FR-1/FR-2 (DEB-4542) live, this means:

- a video copy on a path that is not proven goes to our ladder, with the floor rungs first;
- a re-encode gets every rung, not one;
- a TV with no record is capped at 8 Mb/s.

`HtmlVideoBackend` (hls.js, the non-Tizen fallback) declares the same flag, because hls.js adapts too.

**FR-27 · A stall with no lower rung asks for one.** This is DEB-4541 FR-4's rule on AVPlay. The trigger is a second stall
within 2 minutes, or one stall of 4 s or more, while the playing rung is the lowest in the master. The rungs are known from
`getStreamingProperty("AVAILABLE_BITRATE")`; when that does not answer, the playing stream counts as having no lower rung. On
that trigger the app does three things:

1. It posts FR-25's report first, so the server sees the stall and its hold.
2. It restreams at its position through the existing `/api/tv/playback/restream`.
3. It opens the answer as it opens any restream, closing AVPlay first, as in the DEB-4874 amendment.

The server's DEB-4541 FR-1 answers the hold with our ladder. If the answer is the same single stream again, the app does not
ask again for that item. The restream is one short stop instead of a train of them, at most once per item, and the buffering
view (R218) covers it.

**Proof (CI).** `ravilo-screen-player.spec.ts` (or a new `ravilo-screen-qoe.spec.ts`) uses the fake AVPlay from
`helpers/screen-cast.ts`, extended to fire `onbufferingstart`/`onbufferingcomplete`, `onevent` bitrate changes and the two
streaming properties. It asserts:

- the start body carries `hls_adaptive: true`;
- a ticket with `start_variant_bps` sets `ADAPTIVE_INFO` before `prepareAsync`;
- a 2 s wait after the first frame posts one report with `per_item: true`, one `mid` stall and `rebuffer_count: 1`, while a
  seek's wait posts none;
- a stop posts the item's final report, and the next item's report starts from zero;
- two stalls on a single-rung stream make exactly one `/api/tv/playback/restream`, and a same-stream answer makes no second;
- a title's page opening calls `GET /api/tv/probe`.

**Proof (QA, after a `.wgt` carrying this and DEB-4542 on prod).** QA runs these on the `tizen-emulator` row, with a
server-side cap on the emulator's traffic (the QA cap technique) and **an uncapped control leg** of the same H.264 file. The
file must be one Jellyfin answers with a video copy.

1. **Uncapped:** a first picture, no stall. The device has a `device_stream_record` row with `measured_bps`, and a
   `playback_qoe` row with `per_item = 1`.
2. **Capped at 1.5 Mb/s from the start:** `video copy → our ladder` in the log, a floor rung in the master, and no stall of 2 s
   or more after the first minute over 10 minutes.
3. **Uncapped start, then a 0.8 Mb/s cap mid-play:** a step down (`variant_switches_down ≥ 1`, or the FR-27 restream), and no
   more than one stall in the 5 minutes after the cap.

The owner judges the real set himself, as agreed on 2026-11-15. *Field read, 14 days after the release reaches the household's
set:* the set's `playback_qoe` rows give stalls per play for the first time, and the wall time lost per episode, read from
Jellyfin's start/stop lines as in this amendment's baseline, falls below 5 %. A count going the wrong way is a finding against FR-26 or FR-27.

## Amendment 2026-11-19 (DEB-5025) — the player has Android TV's controls

Owner on DEB-5025, 2026-11-19: *"No controls for the tizen tv"*. His answer on that row's card: the controls were missing **on the TV while
watching**, and the fix is Android TV's control bar. Then he wrote: *"shouldn't require design round. as it should be using the same designs as
the android tv is using. We just want that as well on the tizen tv app"*. So there is **no design round**. The look and the key model are
Android TV's `PlayerChrome` (`ravilo-ui`, `PlayerScreen.kt` and `PlayerScreenState.kt`) as they are on `main` today, rebuilt in DOM/CSS. The
values below are copied from that code, so the builder does not have to read it.

**What changes.** Today the Samsung player (R264 FR-R264-3) has no buttons. A key press flashes a strip for 3 s: kicker, title, progress
bar, time and a ▶/❚❚ glyph. OK pauses, ←/→ seek −10 s/+30 s, ↓ opens the picker and ↑ shows the strip. **This amends FR-R264-3's key
bullets and FR-9's player for this app.** Everything else FR-R264-3 to FR-R264-7 promise stands. That covers the picker's contents, Skip
Intro's window, the next-up card, the waiting moments, failure copy, Back's two steps, status reports and the 2019 sets' see-through page.

**FR-28 · The controls are Android TV's.** While a stream plays, the chrome is the following, top to bottom:
- **Top band.** A scrim, a 230 px gradient from black at 72 % to transparent. In it, a 68 px row with 48 px side padding: a **Back** control
  at the left and, at the right, the ident R303 already draws (`#ov-ident`: logo, plate or series name).
- **Bottom band.** A scrim, a 280 px gradient from transparent to black at 78 %. In it, a column with 48 px side padding, 12 px top and 16 px
  bottom. From the top:
  - the kicker (upper case, the secondary accent `--accent2`, 11 px bold, 1.5 px tracking), then 4 px;
  - the title (white, 20 px bold, −0.5 px tracking, one line with an ellipsis), then 8 px;
  - the **seek bar**: elapsed time left, total time right, the played part in the accent gradient and the buffered part lighter. A focused
    bar shows a thumb; a scrub shows the target time over it;
  - 6 px, then the **transport row**, spaced 10 px: **−10s · Play/Pause · +30s**, a flexible gap, then **Audio & subtitles**
    (`player.audio_subs`) and, only when the next episode is known (`nextId`), **>> Next** (`player.next`).
- **Focus** is the app's focus ring (`--ring`, as on Home's tiles). On Android TV it is the same ring on the same controls.
- Pixel values are CSS px on the app's 1920×1080 canvas, which is the same scale `screen.css` already uses. Android TV's dp values are
  carried over 1:1 and multiplied by the canvas scale `screen.css` applies to its other chrome. If the builder finds that scale is not 1,
  the build multiplies by it and says which factor it used. (Built with the factor **2**: Android TV's window is 960 × 540 dp and this canvas is
  1920 × 1080 px, so the 230 dp top scrim is 460 px, the 40 dp buttons are 80 px, and so on. The bundle's CSS says the same.)
- The styling goes into `ravilo-screen/wgt/screen.css`, which is this app's production CSS. It is not a `design/` file, and no round is drawn.

**FR-29 · The keys are Android TV's** (R178, R251, R350 FR-R350-7, R363, R44, R112):
- **Focus order** along the row: seek bar · −10s · Play · +30s · Audio & subtitles · Next. Back is not in the order (it is reached by the
  remote's Back key). Left or Right at an end of the order does nothing.
- **With the controls up:** ←/→ move focus. On the seek bar ←/→ **scrub** by `min(10 s, max(5 s, 1.2 % of the duration))`, and OK or ↓
  commits the scrub. ↓ from the seek bar goes to Play. ↓ from the row does nothing, because the Samsung has no episode rail. ↑ from the row
  goes to the seek bar, except that ↑ from the seek bar, Audio & subtitles or Next reaches the Skip Intro pill while it is shown (R363
  FR-R363-1). OK acts on the focused control: −10s and +30s seek, Play toggles, Audio & subtitles opens the picker that ↓ opens today, and
  Next starts the next episode as the next-up card's *Play* does (`playNext()`).
- **With the controls hidden:** OK **plays or pauses and shows the controls with focus on Play** (R178). Inside the intro with the pill
  shown, OK still skips it, as today. ← and → act on the **first** press from the control last focused: they move focus or scrub, and they
  do not seek −10/+30 unless that control is the seek bar (R350). ↑ acts the same way, and shows the pill first inside the intro (R363
  FR-R363-3). ↓ only shows the controls. Hiding never moves focus. A start focuses Play.
- **Media keys act whatever is focused, hidden or not** (R44): play/pause, play, pause, rewind −10 s, fast-forward +30 s, stop, and the
  remote's next key starting the next episode when one is known.
- **Back:** with the picker or a scrub open, Back closes it. With the controls up, Back hides them. With nothing up, Back stops (R112, as
  today).
- **Hide:** the controls hide **3.6 s** after the last key while playing. They stay up while paused and while the picker is open. A phone's
  pause, seek or track command shows them as the flash does today.

**FR-30 · What the strip was is gone, not kept beside it.** The 3 s flash (`flashOverlay`) and its `↓ Audio & subtitles` hint are
replaced by FR-28's chrome, and every place that called the flash now shows the controls. `↓` no longer opens the picker directly: the
picker is reached through **Audio & subtitles**. This is Android TV's model, and the owner asked for the same.
- No wire change. No new string: `player.audio_subs` and `player.next` exist, and −10s, +30s and >> are glyphs, as on Android TV.
- **Engine floor.** FR-13 still holds: Chromium 63, no CSS newer than the shell already uses. The chrome is built once at player start and
  shown or hidden by class, so a 2019 set re-lays nothing per key.

**Proof.**
- `ravilo-screen-player.spec.ts` (fake AVPlay, keyboard events) gains these cases:
  - OK on a hidden chrome pauses and shows the controls with Play focused;
  - → then OK on a playing title seeks +30 s (the first → acts from the hidden controls and moves focus Play → +30s, as FR-29 says; then OK acts);
  - with focus on the seek bar, ← scrubs and OK commits one seek;
  - OK on Audio & subtitles opens the picker;
  - Next shows only with a known next episode and starts it (in `ravilo-screen-play-from-tv.spec.ts`, whose mocked series has one; the player spec's film has none, and asserts Next is absent);
  - the controls hide 3.6 s after the last key while playing and stay while paused;
  - Back hides, then stops.
- Then the Tizen 5.0 emulator and the owner's set, on the QA queue.

## Amendment 2026-11-19 (DEB-5047) — a title's page reads its state again when you come back to it

Owner, DEB-5047: *"it's also missing the whole showing current state of each episodes within a series details page. And also
the button says "Spæl · S15E01" even though I have seen this episodes and the next bunch ones."*

What the evidence says, read on 2026-11-19:
- **The report itself is a profile, not the page.** The set is signed in as one household profile only, and that profile has
  never played the series. Every play of it on prod is under another profile, on a phone and on the Android TV boxes. The
  series' seasons in the library start at 15, so a page with no state for the profile offers the first counted episode, *Play ·
  S15E01*, with no ✓ and no bar. That is FR-7 and DEB-4660's amendment doing what they say. The cure is the profile menu's
  *Switch profile* → *Add user* (FR-3, FR-4), and no code changes for it.
- **The page already draws each episode's state.** An episode tile carries a ✓ when watched and a progress bar when started,
  from the same `getPlaystate` answer Android TV uses (DEB-4660).
- **But it reads that state once.** `ScreenDetail.load()` returns early when the page is ready, `returnFromPlayer()` only
  redraws, and the events socket's `onEvent` ignores `home_changed` and `playstate_changed`. Watch an episode on the TV, press
  Back, and the page still shows the state from before the play: the old ✓s, the old bar, the old *Play · S…E…*. A fresh page
  only comes after FR-22's five minutes away (DEB-4925). Android TV re-reads on re-entry and on the push (FR-R351-7), so the
  owner's *"also missing"* is right for any play made on the TV itself.

**FR-31 · A title's page reads its state again whenever it can have changed.** This is Android TV's FR-R351-7 on the Samsung's
page, unchanged:
- **When.** The page reads `getPlaystate` again for the title and, on a series, for the series and every episode:
  - when it is shown again after the player (`returnFromPlayer`), whichever way the play ended: Back, the end of the last
    episode, a failure;
  - when the events socket pushes `home_changed` or `playstate_changed` while the page is in the router's stack (on screen or
    under the player), so a play another device made, or a write a phone made, reaches the page.
- **What it keeps.** The read never empties what the page shows. Old values stay until new ones arrive. A failed read, or one
  that answers an empty map, keeps them, as Android TV's stores do. One read runs at a time per page; a push that arrives during
  one asks for one more read after it, never a queue.
- **What it redraws.** The primary button's word and episode (FR-7, DEB-4660), each episode tile's ✓ and bar, a movie's
  *Resume · N min left* and *Start over*, and My List and the watched button. The redraw keeps focus where it was (the tile that
  played, per FR-9), and it keeps the season pill the viewer is on. The page does not jump to the season of the new primary
  episode under the viewer.
- **No skeleton.** A re-read draws over the page as it stands (FR-20's *a page drawn from what the route already holds*).
- **No wire change.** `getPlaystate` and both events exist, and no new string is needed.
- **Proof.** `ravilo-screen-detail.spec.ts` gains these cases:
  - Back from a play whose mock playstate now marks the episode watched shows its ✓, and the primary button names the next one.
  - A `home_changed` pushed while the page is open redraws it without moving focus.
  - A re-read that answers 500, or `{}`, leaves every ✓ and the button as they were.
  - A push during a read gives one more read after it, not two in parallel.
  After the tests, the page goes to the Tizen 5.0 emulator on the QA queue, then to the owner's set.

## Amendment 2026-11-19, second (DEB-5024) — a picture that stops without saying so is a stall

QA on the Tizen 5.0 emulator (DEB-5067, finding DEB-5078) found that AVPlay does not always raise the events FR-25 and FR-27
count. On a Jellyfin copy stream the position froze about 25 s in while `getState()` stayed `PLAYING`, and it held for 4
minutes with no `onbufferingstart`. Under a 0.3 Mb/s cap on our ladder the position fell 60 s behind real time over 5 minutes,
again with no buffering event. The report said `rebuffer_count 0` and nothing restreamed. Whether the real set behaves the same
is not known yet. Either way, a stall that depends on AVPlay's word goes unseen, so the app watches the position itself. This
is the Cast receiver's frozen-picture rule (DEB-2205, DEB-4637), made for AVPlay.

**FR-32 · A position that stops moving while playing is a stall, and a long one restreams.** *(Numbered FR-28 when
first committed, in `f1a25a91d`, which repeated DEB-5025's FR-28. A citation of "DEB-5024, FR-28" from that commit or from
DEB-5079 means this FR.)*

- **What counts as stopped.** The app reads `getCurrentTime()` every 500 ms while AVPlay is `PLAYING` and the app has not paused,
  sought or restreamed in the last 2 s. If the position has not advanced for **1 s or more**, and no `onbufferingstart` is
  open, that is a stall. It ends when the position moves again.
- **How it is reported.** It is an FR-25 stall like any other: the same counts, the same `QoeStall` entry, and a report posted
  when it ends. Its `cause` is chosen as follows:
  - `buffer_empty` when `getStreamingProperty("CURRENT_BANDWIDTH")` at its start is below the playing rung's bitrate (the
    path could not carry it). This kind counts toward 309's take.
  - `frozen_with_buffer` otherwise, also counted in `frozen_count` and `frozen_ms`. This kind does not count toward the take,
    as `StallRule` already says.

  A stall FR-25 already saw through `onbufferingstart` is not counted a second time.
- **It feeds FR-27.** Both kinds count toward FR-27's trigger (a second stall within 2 minutes, or one of 4 s or more), so a
  thin path on the lowest rung still asks for a lower one.
- **A freeze of 5 s restreams.** A stall that reaches **5 s** with the position still not moving (either cause) restreams at
  the position through `/api/tv/playback/restream`. That happens immediately, without waiting for FR-27's count, and at most
  once per item. The request carries `below_bandwidth_bps` set to the playing rung's bitrate. For a Jellyfin copy that is the
  copy's own bitrate, from `AVAILABLE_BITRATE` or the ticket. The server's DEB-2205 FR-5.2 then answers with our encoder's
  rungs below it, never the stream that froze. The entry is a `recovery` with reason `frozen`, and the buffering view (R218)
  covers it.
- **If the restream freezes too.** A second 5 s freeze on the same item does not restream again. The app keeps the buffering
  view and reports the stall when it ends.
- **No wire change.** Every field and the request bound already exist.
- **Proof (CI).** Add to `ravilo-screen-qoe.spec.ts`. The fake AVPlay holds `getCurrentTime()` while `PLAYING` and fires no
  buffering event. The tests assert:
  - a 2 s hold posts one `mid` stall;
  - with `CURRENT_BANDWIDTH` above the rung, the stall's cause is `frozen_with_buffer` and `frozen_count` is 1;
  - with `CURRENT_BANDWIDTH` below the rung, the cause is `buffer_empty`;
  - a 5 s hold makes exactly one `/api/tv/playback/restream` with `below_bandwidth_bps` set to the rung, and a second hold
    makes none;
  - a pause, a seek and an audio restream make no stall.

  A `PlaybackService` test checks that a restream with `below_bandwidth_bps` set on a play that was a Jellyfin copy answers
  with our encoder's rungs under the bound.
- **Proof (QA).** Rerun DEB-5067's copy-stream freeze on the emulator. Within 6 s of the freeze there is one restream, then
  our ladder and a moving position, and the item's row shows the stall. The real set is judged by the owner.
- *2026-11-20, DEB-5080 (Munin) — why a Jellyfin copy may freeze on AVPlay. Plausible, not proven; no behaviour changes
  here.* For the frozen session, Jellyfin's ffmpeg cut 6 s MPEG-TS segments with the video copied, `-copyts`, and
  `-avoid_negative_ts disabled`. Jellyfin writes `main.m3u8` itself, as a fixed 6 s per `#EXTINF` entry. A copy can be cut only at
  the source's keyframes, so with a GOP longer than 6 s the real segments drift from what the playlist announces. By ~25 s
  (segment 4) the drift can be seconds. This is Jellyfin's known copy-HLS timing defect (jellyfin/jellyfin#17966), and a
  Tizen 5.0 player that stops asking for segments while it reports progress is jellyfin-tizen#410. Throttling (on, 180 s delay)
  and segment deletion (720 s kept) do not explain it: ffmpeg was ~4 minutes ahead when the player stopped asking. FR-32
  turns such a freeze into one 5 s restream onto our ladder, which re-encodes with forced keyframes. So it is the cure in
  either case, and nothing more is specified now.
  - **What would change that.** If the owner's set freezes on copy streams after FR-32 ships, *or* QA's rerun finds the
    source's keyframes further apart than 6 s around the freeze (ffprobe the first ~40 s of the file), the next step is an
    amendment that stops a Samsung from taking a Jellyfin **video copy** over HLS at all. DEB-4541 FR-1 would send it to our
    ladder whatever the take, at the cost of a GPU session per Samsung play. That is a trade-off for the owner, asked then
    and not before.
  - Jellyfin's log said the audio was copied, while the start URL asked for AAC. That has no bearing on the freeze. It is
    recorded only so that nobody reads the URL as proof of what was sent.

## Amendment 2026-11-19, third (DEB-5078) — a start our encoder refuses is asked once more, out of sight

On the emulator (DEB-5067) a film's first start went to our ladder. ffmpeg exited before its first segment: the log said
`ffmpeg exit 59904`, which is a wait status for exit code 234, ffmpeg's EINVAL. The server marked the file for Jellyfin for
6 h (DEB-3093 FR-2), AVPlay's `prepareAsync` answered *Unknown error*, and the app drew *That didn't start*. The viewer's
second start played Jellyfin's copy. The server's half works, because the refusal is recorded before the player sees the
failure, so the next ticket for that file is Jellyfin's. What is missing is the second ask. Android and the Cast receiver
make it on their own (DEB-3093 FR-6), and the Samsung app makes none. Why ffmpeg refused this file is not known: its
`ffmpeg.log` went with the job's folder. DEB-3093 FR-8's 2026-11-19 note makes the next refusal keep its reason.

**FR-33 · A start that fails on our encoder before its first picture is asked once more.**

- **The trigger.** The ticket's `encoder` is `ours`, and AVPlay's `onerror`, or an `open()` that throws, arrives before
  FR-25's first frame.
- **What the app does.** It closes the failed attempt as today: FR-25's report with `start_failure_status`, and the server's
  session stopped at 0 (DEB-4874). Then it calls `POST /api/tv/playback/start` once more for the same item, with the same start
  position and FR-23's remembered audio, and opens the answer. All of this happens under the loading view already showing,
  with no error, no shutter and no second *Still trying…*. The server answers with Jellyfin's stream when the file was
  refused, and with our encoder again otherwise.
- **What it does not do.** A second failure shows today's *That didn't start*, whichever encoder served it. These keep today's
  behaviour, with no second ask:
  - a ticket whose `encoder` is not `ours`;
  - an error after the first frame;
  - Back, or a newer start, during the second ask.
- **Each attempt reports as its own start.** Each has its own play session and its own `playback_qoe` row, so the failure
  stays visible to the server.
- **No wire change, no backend change.** `StreamTicket.encoder` and the start route already exist.
- **Proof (CI).** In `ravilo-screen-player.spec.ts`, the fake AVPlay fails `prepareAsync` on a ticket with `encoder: "ours"`.
  The test asserts:
  - exactly one more `/api/tv/playback/start`, no error screen, and the second ticket plays;
  - a second failure shows the error;
  - a ticket with no `encoder` fails straight to the error;
  - an `onerror` after the first frame makes no second start.
- **Proof (QA).** On `tizen-emulator`, a file our encoder refuses plays from the first OK. The backend log shows the refusal
  and then Jellyfin's stream on the second ticket. The QA row names the file, which has to be one not refused in the 6 h
  before. The real set is judged by the owner.

### Addition 2026-11-20 (DEB-5025, QA DEB-5076): three gaps the emulator found

DEB-5076 ran FR-28 to FR-30 on the Tizen 5.0 emulator (`ee0fa1a13`). Two results fail what the amendment already says. One is Android TV
behaviour the amendment left out, and is added here.
- **Media keys arrive under Tizen's names** (fails FR-29's *media keys act whatever is focused*). The page receives `XF86PlayBack`
  (10252), `XF86AudioPlay` (415), `XF86AudioPause` (19), `XF86AudioRewind` (412), `XF86AudioNext` (417) and `XF86AudioStop`
  (413), not `MediaPlayPause`, `MediaPlay` and the rest. Each key is matched by its Tizen name or its key code as well as by the standard
  name. Key code 417 is Tizen's fast-forward, whatever name it arrives under, so it seeks +30 s. Next episode stays on the bar. What
  the owner's set sends is recorded on its QA row.
- **The focused Play button shows its glyph** (fails FR-28's *Focus is the app's focus ring*). The focused disc draws its ▶/❚❚ in the dark
  ink on the white disc, with the ring around it. It never goes white on white.
- **Captions rise above the bar while it is up** (R251 FR-R251-4, which Android TV does and FR-28 did not carry over). While the controls
  show, the drawn subtitles (`#cues`) sit above the bottom band. When the bar hides, they return to their usual floor.
- **Per FR-29, these stay as they are:** Left from −10s reaches the seek bar (it is first in the order), and a new item, Next's included,
  focuses Play.

## Amendment 2026-11-20 (DEB-5129) — a phone's push gets its next episode on 5.0 too

QA on the Tizen 5.0 emulator (DEB-5125): Next and the next-up card dropped the set to Home when the start had come from a
phone's push. That is the path DEB-4865 left on `POST /api/remote/play`, and `/api/remote/play` refused the 5.0 POST's
`Origin: null`. Phase 247's DEB-5129 amendment (FR-247-6) admits `null` on `/api/remote/**`. This replaces DEB-4865's
*"DEB-4719's refusal of a Tizen 5.0 widget is the same as before for that path"*: on 5.0, a phone's push now plays its
next episode by the same round trip as on 10.0. No client change. DEB-4865's TV-made path is unchanged and still never
asks the route. A password sign-in on 5.0 is still refused (DEB-4719's code-only message stands).

## Amendment 2026-11-23 (DEB-5024) — a picture that crawls is counted, not only one that stops

QA rerun DEB-5220 (Tizen 5.0 emulator, `.wgt` from `c494d5099`, prod `v1.71-10-g5f47123c`) passed FR-32's freeze. A frozen copy
stream restreamed once onto our ladder and played on, and the row read `frozen_count 1`. Under a 0.3 Mb/s cap, below the lowest
rung, AVPlay stepped down once and then played at about half speed, in holds of up to 0.8 s each. That is under FR-32's 1 s, so
the report said `rebuffer_count 1` for three minutes of a crawling picture. DEB-5067 read the same shape as 0. Nothing can play
faster than the path once the lowest rung is reached, and FR-27 already asks for a lower one. What is wrong here is that the
report calls it smooth. 14 days after the release, the field read rests on these rows.

**FR-34 · Play that falls behind real time is a stall.**

- **The window.** While AVPlay is `PLAYING`, the app keeps a sliding 10 s window of wall time against position. It leaves out
  the 2 s after a pause, a seek or a restream, and any time already inside an FR-25 or FR-32 stall.
- **When it counts.** If the position advanced less than **70 %** of the wall time in that window, the shortfall (wall − advance)
  is one stall. It is a `QoeStall` with `cause = buffer_empty`, `phase` as FR-25 says, and the window's start as its position. It
  counts in `rebuffer_count` and `rebuffer_ms`, and it is posted as FR-25 posts any stall. The window then starts over, so one
  stretch of crawling is one stall per 10 s, not one per sample.
- **It feeds FR-27, not FR-32.** A crawl is a thin path, not a frozen player. It counts toward FR-27's trigger, a second stall
  within 2 minutes, which asks once for a lower rung. It never restreams on its own account.
- **No wire change.**
- **Proof (CI).** In `ravilo-screen-qoe.spec.ts`, the fake AVPlay advances `getCurrentTime()` by 400 ms in every 1 s of wall
  time, in 0.6 s holds, for 20 s. The tests assert:
  - two `buffer_empty` stalls are posted, each about 6 s;
  - `frozen_count` stays 0;
  - a stream advancing at 0.9× posts none;
  - a pause inside the window posts none.
- **Proof (QA).** Rerun DEB-5220's 0.3 Mb/s leg. It passes when `rebuffer_count` rises about once per 10 s of crawl, and when
  one FR-27 restream is asked for. Our ladder at that point has nothing lower, so the server answers with the same stream and the
  app does not ask again.

## Amendment 2026-11-23, second (DEB-5394) — the TV starts the encode before Play, as Android TV does

Owner on DEB-5394, 2026-11-23: *"lets add the same pre buffering as we have in android, where opening a details page starts
buffering before you actually click play and the same for next up episode when watching an episode. If more ideas then please
add. We want to make it feel faster on tizen, currently it feels to slow to start playing things."*

**What Android does, and what of it reaches this app.** Android has two moments.

- **The title's page.** `DetailPrewarm` (309, FR-309-3/-6) waits 2 s on a page, runs the speed test, then posts
  `/api/tv/playback/prewarm` for what Play would play. It re-posts every 30 s and cancels when the page is left without Play.
  The server starts our encoder's job, and Play adopts it (`Encoder.adoptPrewarm`: same plan, a start within one segment).
  This app runs only the speed test at that moment (FR-24).
- **The next episode.** R381's `prepareNext` asks `/api/tv/playback/prepare`, and Android prefetches the next file's first 16 MB.
  That works only for a **direct play**. A transcoded next episode gets nothing (R381, owner: *no transcode preload until
  309/313*). This app negotiates `hls_only` and never direct-plays, so R381's prefetch has nothing to fetch here.

So the part that carries over is 309's early encode, at both moments. It warms **our encoder only**. A play that Jellyfin copies
on a proven path (DEB-4541 FR-1, `videoCopyRoute`) answers the prewarm `none`, and it starts as it does today. On prod over the 72 h to
2026-11-23, the household's set (still on a 1.70 `.wgt`) lost a median 5.8 s of wall time per 5-minute episode (4.1–10.2 s,
24 plays). It played Jellyfin copies throughout. The next episode's start reached the server 0.4–0.9 s after the last
one's stop. The set has posted no `first_frame_ms` yet, because FR-25 is not on it. These two FRs therefore make a re-encode, or a copy
sent to our ladder, start warm. FR-37 is about the time AVPlay itself takes, which every route pays.

**FR-35 · A title's page starts the encode Play would use.** This is Android TV's `DetailPrewarm`, on `ScreenDetail`.

- **When.** The page has drawn and Play's target is known: the film, or the series' primary episode (`seriesPrimary`, the
  one the page's Play starts). After **2 s** on the page, the app posts `prewarmPlayback` with that item, `backend.capabilities()`
  (the start's own) and FR-23's remembered audio (`rememberedAudio()` for that series). FR-24's probe stays where it is.
- **While the page shows.** An answer `warm` is re-posted every **30 s**, so the job's idle timer does not end it. An answer `direct` or
  `none`, or a failure (including a 404 from an older server), stops the loop. Nothing is retried, shown or waited on.
- **Play.** Play on the page starts as today. The server adopts the warm job when the plan and the start match. *Start over*
  and an episode tile that is not the primary one are different plays: the server stops the warm job and starts its own, as
  on Android.
- **Leaving.** Back or Home, a phone's push over the page, or hiding the app (FR-22) posts `cancelPrewarm` for that item
  **at once**. Leaving by Play does not cancel. The app knows it left for Play, so Android's 1.5 s grace is not needed. A
  page whose target changes, such as an FR-31 re-read that moves the primary episode, cancels the
  old item and starts the 2 s again for the new one.
- **No wire change, no backend change.** `prewarmPlayback`/`cancelPrewarm` are in `TvApiClient` today, and the server's 309
  budget already drops warm jobs before a play's.

**FR-36 · The next episode's encode starts before the card.** Android has no equivalent for a transcode. Here it is the
whole of the next-up half of the ask.

- **When.** Playing an item with a `nextId`, the app posts `prewarmPlayback(nextId)` once the position reaches the credits
  marker. With no trusted marker, it posts **60 s before the end**. That is R381's own trigger, which is earlier than this
  app's card: the card waits for the last second (R384). It is the same with "play next automatically" on or off, because the card still offers *Next*.
  The capabilities are the start's own, and the audio is FR-23's for the series.
- **While it waits.** A `warm` answer is re-posted every 30 s until the next episode starts or the prewarm is dropped.
- **Dropped.** A seek back before the trigger, Back, a stop, Home, hiding the app, the card's cancel, and a start of
  anything other than `nextId` each post `cancelPrewarm(nextId)`. A viewer who stops at the credits leaves nothing running.
- **Adopted.** The card (countdown, OK or *Next*) and the controls' Next start `nextId` as today. The server adopts the job
  when the next episode starts where it was warmed (from 0, or at its own resume point). The playing item's stream is not touched:
  the server keys a warm job by device **and** item.
- **No wire change, no backend change.**

**FR-37 · AVPlay starts on a short buffer, if the set shows it helps.** AVPlay chooses its own start buffer unless
`setBufferingParam` sets one. That buffer is part of every start on every route, the Jellyfin copies included.

- The build measures three spans per start, logged on the console with the item's id only: `startPlayback`'s round trip,
  `open()` to `prepareAsync`'s success, and that to FR-25's first frame. It measures them on the `tizen-emulator` row, on a Jellyfin copy and on
  our ladder, with and without `setBufferingParam("PLAYER_BUFFER_FOR_PLAY", "PLAYER_BUFFER_SIZE_IN_SECOND", n)` and the
  matching `PLAYER_BUFFER_FOR_RESUME`, for n = 2 and 4. The call goes after `open()` and before `prepareAsync`. A set that refuses it
  plays on as today.
- The setting is kept only if the median start falls and the start's own stalls (FR-25 `phase = start`) do not rise. The
  smallest such n is used. Otherwise the call is left out and the numbers go on this row. No wire change.

**Proof (CI).** In `ravilo-screen-detail.spec.ts`, with the fake clock:

- a page held 2 s posts one `/api/tv/playback/prewarm` for Play's target with the start's capabilities;
- a `warm` answer is re-posted at 30 s, and a `none` answer is not;
- Back posts `/prewarm/cancel` for that item, while Play does not;
- an FR-31 re-read that moves the primary episode cancels the old item and warms the new one.

In `ravilo-screen-player.spec.ts`:

- reaching the credits marker posts one prewarm for `nextId`, or 60 s before the end without one;
- a seek back before it posts the cancel;
- the card's countdown starting `nextId` posts no cancel;
- Back on the card posts it.

**Proof (QA, a `.wgt` carrying this on the `tizen-emulator` row).** Use an H.264 episode our ladder makes, such as a
burned-in subtitle or a capped leg, and an uncapped control. The backend log shows `encoder: prewarm device=screen-…` within 3 s of the page opening,
and `play adopted the prewarm` on Play. Leaving with Back shows `prewarm cancelled` at once. At the next episode's credits
the log shows a prewarm for it, and the advance adopts it. QA compares `first_frame_ms` against the same start with the prewarm
cancelled, and FR-37's three spans go on the row. *Field read, 14 days after the `.wgt` reaches the household's set:*
the wall time lost per episode, read as in DEB-5024's baseline, against this amendment's 5.8 s.

## Amendment 2026-11-23, third (DEB-5394) — what the builds and QA taught FR-35 to FR-37

The builds landed as `3ddab56d7` (FR-35, FR-36) and `a020fd639` (FR-37). QA on the Tizen 5.0 emulator (DEB-5399) passed FR-35
and FR-36. A warm start's `first_frame_ms` had a median of 2053 ms over 6 starts, against 2622 ms over 3 cold ones. Three things change:

- **FR-35's re-target comes from an FR-31 re-read, not a season switch.** The primary episode (`seriesPrimary`) never depends
  on the season pill, so a season switch moves nothing. The wording above is corrected. The test is the FR-31 re-read.
- **FR-36: a seek while the card shows keeps the warm job.** QA sought back past the credits marker with the card up. The
  warm job was cancelled, but the card stayed and its countdown ran on, so the next episode started cold. The rule is now:
  - a seek back before the trigger drops the warm job only when **no card shows**;
  - while the card shows, only the card's own cancel (Back on the card, or the phone's *cancel*) drops it, along with Back, a
    stop, Home, hiding the app and a start of another item, as before.
- **FR-36: the seek-back drop is judged from the seek's target, never from a position sample.** A forward seek that made
  AVPlay re-prepare cancelled the warm job and posted it again 1.7 s later. The position probably reads 0 while AVPlay
  re-prepares. A seek forward, a restream or a re-prepare never cancels. This brings the code into line with FR-36's *"a seek back"*.
- **FR-37: no start buffer is set.** DEB-5408 measured 30 starts on the emulator: a Jellyfin copy and our ladder, 5 each with
  no call, n = 2 and n = 4. Neither n shortened `open()` to prepared. The medians were 2152 / 2284 / 2229 ms on the copy and
  1805 / 1862 / 1955 ms on the ladder, with no start stall in any of them. Following FR-37's own rule, the app makes no
  `setBufferingParam` call. The `start-spans` console line stays, so the household set's spans can be read later.
  Prepared-to-first-frame read a flat 1010–1041 ms on the VM. That is its playtime tick, and it tells nothing there.

**Proof (CI)**, in `ravilo-screen-player.spec.ts`:
- a seek back before the marker while the card shows posts no cancel, and the countdown's start adopts the warm job;
- the same seek with no card shows posts the cancel;
- a forward seek that re-prepares posts no cancel and no second prewarm.

No wire change.

## Amendment 2026-11-24 (DEB-5487) — Home moves the way Android TV's does

> Owner on DEB-5487, 2026-11-24: *"Hero caoursel doesnt auto advance. Navigating down moves the focus correctly, but doesnt
> keep a reasonable padding to the bottom so its all sticked to the bottom of the screen and navigating down focuses on the
> moddle item instead of the one all the way to the left, like on android tv. Lets get more inspiration from the android tv
> for our tizen tv. Currently there are no animations when navigating around, lets add some animations. When navigating to
> the right i can see some of the poster images disappearing, like 7 items away and one up and 6 items away etc."*

**What the code does today** (at `cb69e6557`). FR-4 and FR-5 never described the carousel, the resting position or motion,
so the first three findings are gaps. The fourth is a fault in FR-14's image cap.

- **The hero never advances.** `ScreenHome.render` draws every slide as its own target in a sideways-scrolling
  `.home-heroes` strip. Left and Right move focus from slide to slide, and nothing times them. The feed already carries
  `auto_advance_seconds` (`HomeFeed.autoAdvanceSeconds`: default 7, 0 means off). Android TV reads it. The Samsung decodes
  it and ignores it.
- **The focused row sits on the bottom edge.** `ScreenFocus.focus` calls `element.scrollIntoView(false)`, which lines the
  target's bottom up with the bottom of `#shell-body`. Every Down that scrolls leaves the focused row flush with the bottom
  of the screen.
- **Down from the hero lands mid-row.** `ScreenFocus.move(0, ±1)` takes the row's remembered tile, or else the tile whose
  centre is nearest across. Under the full-width hero, that is the tile under the middle of the screen.
- **Nothing moves smoothly.** The page and the rails jump to their new scroll positions, and the focus ring has no
  transition.
- **Posters vanish to the right.** `ScreenHome.updateImages` (DEB-4729) ranks every tile within one screen above or below
  focus by its straight-line distance from it, and keeps the nearest 60. It never asks whether a tile is on screen.
  - With three or four long rows in that band, the 60th-nearest tile is about six or seven tiles away.
  - A visible tile further out in the rows next to focus loses its image, while tiles scrolled out of sight on the left
    keep theirs.
  - Each move to the right reshuffles the set. That is the *"7 items away and one up"* pattern.

Each rule below is Android TV's (`ravilo-ui`: `HeroCarousel.kt`, `ContentRow.kt`, `Tile.kt`, `BringIntoView.kt`,
`Motion.kt`), redrawn in DOM under FR-13 and FR-14. **No wire change** and no design round: Android TV draws all of it
today. Sizes are Android TV's dp at the canvas scale of 2 that the player chrome already uses (a 1920 px canvas is a
960 × 540 dp TV window).

**FR-38 · The hero is Android TV's carousel** (R58, R165, R236, R365 FR-R365-10, R374).
- **One target, one slide at a time.** The slides are stacked in one box, and the sideways strip goes.
  - Left and Right change the slide and wrap at both ends. They do not move focus.
  - OK opens the slide that is showing.
  - Up goes to the bar. Down goes to the first row (FR-40).
- **It advances on its own.** The slide changes every `auto_advance_seconds` (0 means never) and wraps from the last slide
  to the first.
  - It counts whether or not the hero has focus.
  - It counts only while the hero is on screen. Once Home has moved far enough down that the hero is out of view, the
    count stops, and coming back starts a full interval (DEB-4283).
  - Left or Right restarts the interval.
  - It stops while a page is open over Home and while the app is hidden (FR-22).
  - Back from a title opened from the hero shows that same slide and starts a full interval (FR-R365-10).
- **A change is a crossfade.** The new slide fades in over 600 ms and the old one fades out over 400 ms. Opacity only.
- **The backdrop drifts.** The slide that is showing scales from 1.0 to 1.05 over 9 s as a CSS transform, and starts again
  from 1.0 on every new slide. It does not run while the hero is off screen. This is the one part QA may drop if it
  stutters on Tizen 5.0 (see *Proof*).
- **Page dots,** shown when there are two slides or more, sit at the bottom right of the hero, 96 px from its right edge and
  88 px from its bottom.
  - The current slide is a 56 × 16 px accent pill. The others are 16 px dots in the secondary ink at 35 % opacity, 16 px
    apart.
  - The change between dots uses opacity and transform only, never a width transition.
- **No focus ring on the hero** (R374). The dots and the moving slide show where focus is, as on Android TV.
- **Only three backdrops are loaded:** the slide showing and the next two (R88). They are loaded before their slide fades
  in, so a slide never fades in empty.

**FR-39 · The focused row rests at a fixed line, not on the bottom edge.**
- **The line is 248 px from the top of the screen** (`EdgeBand.HOME`'s 124 dp inset).
  - When focus moves into a row on Home or on a channel's feed, the feed moves so that the row's tiles start at the line.
  - The row's heading shows above it, under the bar, and the next row shows below it.
- **With the hero focused, the feed is at its top.**
- **The last row rests at the same line.** The feed ends with enough padding for it to get there.
- **The grids and the search results** (FR-6, FR-8) follow the same line for the focused grid row. The first grid row is the
  one exception: the page stays at its top while it is focused. A title's page keeps its scrolling as it is today.
- **One resting position per key press** (R367 FR-R367-1). A move lands once and is never corrected a second time.
- **The move is a transform** (FR-14).
  - The feed or grid moves by a `translateY` transition of 220 ms on Android TV's `EASE`, `cubic-bezier(.22,.61,.36,1)`.
  - `#shell-body` no longer scrolls natively on these pages.
  - A key pressed while a move is under way sends it on from where it is. A held key never queues moves.

**FR-40 · Down into a row lands where Android TV lands.**
- **From the hero or the bar,** Down focuses the first row's remembered tile, or else its **first tile**: the leftmost one,
  index 0. This is Android TV's `firstRowFR` with `focusRestorer`.
- **Between rows,** Up and Down keep today's rule: the remembered tile, or else the tile nearest across (DEB-4888's band).
  Android TV's 2D focus search does the same between content rows.

**FR-41 · Rails slide the way Android TV's rows do, and the focused tile grows.**
- **A rail moves only when the newly focused tile would be cut off** (`ContentRow`'s bring-into-view spec).
  - A tile cut at the start moves to the left gutter, the body's 72 px inset.
  - A tile cut at the end moves just far enough to be fully visible, including its grown size and its ring.
  - A tile that is already fully visible does not move the rail.
  - The move is a `translateX` transition of 220 ms on `EASE`. Rails no longer scroll natively.
- **The focused tile grows.** A tile on Home, a channel's feed, a grid, the search results or the title page's episode rail
  scales to 1.10 with its ring (`TILE_FOCUS_SCALE`), over 200 ms on `EASE`, using transform only. The tile focus leaves
  shrinks back the same way. Channel buttons grow the same way.
- **No grown tile is clipped.** Rails and grids leave room for a grown tile and its ring, as Android TV pads its rows
  40 px above and below (20 dp).
- **A tile's image fades in over 220 ms when it arrives** (`IMAGE_CROSSFADE_MS`), through `ScreenSkeleton.fadeOnLoad`.

**FR-42 · Every tile on screen shows its image.** This replaces the selection rule in FR-14's *"no more than about 60 tile
images"*. The cap itself stays.
- **A tile on screen always has its image.**
  - The 60 slots go first to every tile that is on screen.
  - Any slots left go to off-screen tiles, nearest to focus first, within one screen above or below as today.
  - An on-screen tile is never dropped to make room for an off-screen one.
  - At today's sizes the screen holds about 40 tiles, so the cap is never reached on screen alone. If it ever is, the
    on-screen tiles win.
- **"On screen" is judged where the feed and the rails will rest** after the move (FR-39, FR-41), never in the middle of a
  transition. A move never drops the image at its own destination.
- **An image is removed only once its tile has left the screen.**

**Proof (CI).** The tests drive Playwright keyboard events in `ravilo-screen-home.spec.ts` and `ravilo-screen-browse.spec.ts`,
with Playwright's clock for the timers.
- **FR-38:**
  - With `auto_advance_seconds: 2` mocked, the slide changes after 2 s and wraps from the last slide to the first.
  - Right restarts the interval.
  - Once Home has moved down until the hero is out of view, the slide does not change.
  - With 0, the slide never changes.
  - Back from a title opened on slide 2 shows slide 2.
  - The hero has no ring.
- **FR-39:** after each Down into a row, once the move has finished, that row's first tile's top is 248 ± 2 px from the top
  of the viewport. The same holds for the last row and for the second row of a grid.
- **FR-40:** Down from the hero focuses `home-row-0-tile-0`, from a feed whose first row is wider than the screen.
- **FR-41:**
  - The focused tile's computed transform is a scale of 1.1 once the transition has ended, and the tile it left has none.
  - A tile that is already fully visible does not move its rail.
- **FR-42:** with a feed of 4 rows of 30 tiles, after ten presses of Right in the second row, every `.tile-art` that
  intersects the viewport has an `img[src]`, and the page holds no more than 60.
- **The guards:** `scripts/check-tizen-engine.sh` still passes, and the bundle still parses as ES2017. `transform`,
  `transition` and `@keyframes` are all in Chromium 63.

**Proof (emulator).** QA runs on the `tizen-emulator` row, Tizen 5.0 first, and checks five things:
- the auto-advance and the crossfade;
- the resting line on Home and in a grid;
- Down from the hero landing on the leftmost tile;
- no poster dropping out while moving right through a long row;
- motion that keeps up with a held key on 5.0.

If the backdrop drift (FR-38) is the only thing that stutters on 5.0, it is dropped and the rest ships. QA says so on the
row. The owner's set confirms FR-14's budget later (FR-16).

**Not in this amendment:** Android TV's focus-detail panel (still *Later*), the row-open mode (R240), *Surprise me*, and the
title page's vertical scrolling.

**Build order**, one piece each for one engineer, each `blocked` by the one before it:
1. **FR-40 and FR-42.** Down from the hero lands on the leftmost tile, and the image cap counts on-screen tiles first. These
   are small fixes in `ScreenFocus` and `ScreenHome.updateImages`.
2. **FR-39 and FR-41.** The feed, grids and rails move by transform to their resting positions, and the focused tile grows.
   FR-42's *"judged where it will rest"* lands here, because this is where transitions begin.
3. **FR-38.** The hero carousel.

QA on the emulator follows piece 3.
