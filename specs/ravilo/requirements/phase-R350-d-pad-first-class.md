# Phase R350 — D-pad first-class: the series page, Back, Search, Discover, the player and focus you can see

> Owner, 2026-10-02, after an evening with Ravilo on a Sony BRAVIA (960 × 540 dp, release build): *"We want dpad users
> to get like they have first class support."* Eight findings from that session, plus the siblings found while fixing
> them.

## Status

`✓ Built` — written 2026-10-02 (dev-authored) from the owner's TV session, before any fix; built the same day (see
*Build notes*). Not deployed, not device-tested. Client-only (`:ravilo-ui`); no wire change, no server change. Number
given by the coordinator.

**Supersedes, in part:**
- **R178 FR-RV-SEL1-1** (hiding the chrome resets the player's focus to Play) — see FR-R350-7. R178's Select guard
  (FR-RV-SEL1-2/3) stays.
- **R251 FR-R251-1/2** (a key that finds the chrome hidden only reveals it) for Left, Right and Up — see FR-R350-7.
- **R346 FR-R346-3**'s opening-season rule — see FR-R350-3. Its Play fallback and counts stay.
- **R277 FR-R277-1**'s TV half (the keyboard raised on arrival) — see FR-R350-4. The phone half stays.

## Requirements

### FR-R350-1 — Down from the hero's primary button focuses the open season's pill
**Seen:** Play → Down (Season 1) → Right, Right (Shuffle) → Down (rail) → Up (Shuffle) → Up (Play) → Down lands on
**Shuffle**, not on the open season.
**Cause:** the pill row carries `focusRestorer()`. In Compose 1.9 a `requestFocus()` on a child also runs the
restorer's enter rule, which sends focus to the last focused child: Down from Play asks for the open season's pill
and gets the restored one.
**Requirement:** Down from Play always focuses the open season's pill (R138/R343). The row keeps its memory for one
direction only: **Up from the rail** returns to the pill that was last focused. Any other entry (a request from
Play, the app bar's Down) goes where it was sent.

### FR-R350-2 — Back from the player returns to the control that started playback
**Seen:** Shuffle → player → Back put focus on *Resume* with the page scrolled to the top.
**Cause:** only the top of the navigation stack is composed. Coming back rebuilds the series page, which always
focuses Play (and re-runs the opening-season rule, so a season the viewer picked was lost too).
**Requirement:** the series page's store remembers which control started playback — Play, Shuffle, or an episode
card (a multi-episode file's card counts as one) — and which season was open. Coming back from the player (or the
cast remote) the page reopens on that season, scrolled so the control is on screen below the app bar, with focus
on it. Read once: any later arrival focuses Play as before. If the card is gone (the season no longer holds it),
focus goes to Play.

### FR-R350-3 — The page opens on the season that holds the primary button's episode
**Seen:** a series whose button reads *Resume · S13E19* (R306's Continue Watching episode) opened on Season 1 (the
first season with anything unwatched); Down focused Season 1 and the rail showed S01E08.
**Requirement:** the opening season is the season holding `primaryEpisodeId` — whatever the primary button plays
(the Continue Watching episode, an episode in progress, the first unwatched counted episode, or S01E01 on a finished
series). The episode rail opens scrolled to that episode. When there is no primary episode the R346 rule stays.
Phone and desktop share the code.

### FR-R350-4 — Search: Back leaves, and the keyboard comes on OK
**Seen:** from the results, the first Back moved focus into the field and raised the keyboard, the second closed the
keyboard, the third left.
**Requirement (TV):**
- Back from the results or the suggestions grid leaves Search, back to where the viewer came from.
- Back while the keyboard is up closes the keyboard (the system's own behaviour); Back from the field leaves.
- Arriving at Search focuses the field **without** raising the keyboard; **OK on the field** raises it. Up from the
  grid's first row returns to the field, also without the keyboard.
- R295's return to the opened result is kept.
**Phone:** unchanged (R277: nothing raised on arrival; tapping the field or the bar's Search raises it; Back from a
scrolled grid scrolls to the top first).

### FR-R350-5 — Discover: the app bar's Down lands on the tab strip
**Seen:** Down from the top bar skipped the tabs (Networks · Studios · Genres · Coming Soon · Request) and landed in
the wall's first tile. Up from the wall did reach the tabs.
**Requirement:** top bar → the selected tab → the content, both ways. With no tabs (R310's empty Discover) the
bar's Down goes to the content as before.

### FR-R350-6 — The player's episode rail is headed by its season
**Seen:** the rail header read *S01E01* while episode 3 played.
**Cause:** the header was `kicker.substringBefore("·")`, written for R346's old `S1 · E5` kicker. Since R346 the
kicker is `S01E05` and the whole code came through.
**Requirement:** the header is the season in the viewer's language — *Season 1* · *Sæson 1* · *Sesong 1*
(`detail.season` + the number), and the season's own name for Specials. Same on the phone's episode sheet.

### FR-R350-7 — The player's chrome stays while paused, and a key that finds it hidden still acts
**Seen:** paused, the chrome auto-hid after a few seconds; the next Right only revealed it (R251) and focus had gone
back to Play (R178), so every Right press after a pause cost an extra press.
**Requirement (TV):**
- **Paused ⇒ the chrome stays up** (the phone already does, R244). Pressing play re-arms the 3.6 s hide.
- **Hiding never moves focus.** The control the viewer last focused is still focused when the chrome comes back.
- **Left, Right and Up** that find the chrome hidden reveal it **and** move, from that remembered control (Left/Right
  on the seek bar scrub, as they do when the chrome is up).
- **Down** that finds the chrome hidden reveals it and moves only within the transport (seek bar → Play); it never
  opens the episode rail from a hidden chrome.
- **OK** that finds it hidden stays R178's play/pause (never fires a hidden control) and puts focus on Play, so the
  highlighted control matches what OK just did.
- The Skip Intro pill, the next-up card, the picker and the episode rail are unchanged.

**Why this and not R251:** R251's rule (*"→ → OK means the same thing whether or not the chrome auto-hid"*) is kept
better by not moving focus at all: the hide timer now changes only what is drawn, never what a key does. Under R251
a viewer who waited and one who didn't reached different controls anyway (hidden: the first → was swallowed). The
one key that keeps a hidden-chrome rule is OK, because it is the one that fires a control.

### FR-R350-8 — Every focusable thing shows it is focused
**Seen:** nothing marked the Home hero as focused on arrival; the series page's Play looked the same before and after
the first key.
**Requirement (TV, and a desktop on the keyboard):**
- The Home hero draws the design's inset ring (`.hero-hit.focused`) while focused.
- A focused `RaviloButton` draws the design's ring outside the button (`.btn.focused`), so the primary button reads
  as focused without a second state to compare it with.
- A focused synopsis draws the design's ring (`.hero-syn.dsyn.focused`), not only a brighter ink.
- The trailer overlay's Close draws a ring when focused.
A handset draws none of this (R298).

### FR-R350-9 — The same class of bug elsewhere
- **Movies / Series browse grid:** Up from any tile in the first row goes to the facet bar (its first chip), never
  past it to the app bar's search or avatar above the right-hand tiles.

### The Mac app (coordinator, 2026-10-02 — the same shared series page, keyboard arrows on a computer)

**FR-R350-10 — An episode card names its episode `S01E05`.** The cards read *E1 · {title}* (and a multi-episode
card's list *E1 · …*). R346 FR-R346-5 is *one spelling on every viewer screen*, with no exemption for a card inside
its season, and the desktop design draws `S01E01` under each card. So the card reads *S01E01 · {title}*. The number
badge on the still stays a bare number (it is a badge, not a code).

**FR-R350-11 — The arrow keys move focus on a computer as the D-pad does on a TV.** On the Mac, Down from a season
pill did nothing, and Up, Right and Down from an episode's *Mark watched* did nothing (Tab worked).
**Cause:** Compose Multiplatform's desktop owner turns only Tab, Shift+Tab and Back into focus moves; Android turns
an unhandled arrow into Compose's 2-D focus search. Every place the app leaves a direction to that search (lazy
rows, the toggle under a card, the pills' Down) was dead on the desktop.
**Requirement:** on the desktop, an arrow no focused element consumed asks the same focus search (one modifier at
the app root). Android unchanged.

**FR-R350-12 — The season pills never hide one behind the window's edge on a computer.** At 1280 px a series with
seven seasons, Specials and Shuffle had Shuffle cut off at the right edge, and a mouse cannot scroll a row sideways.
**Requirement:** on the desktop the pill row wraps onto a second line instead of scrolling; Left/Right still walk
the pills in order, Down goes to the line below and from the last line to the rail. On the TV the row scrolls and a
focused pill is always brought fully into view (R250's gutter).

**FR-R350-2 also covers the Mac's report** that returning from the player reset the series page's scroll position.

## Non-goals
- The sign-in and server-setup screens (another phase is changing them).
- Redesigning the player's focus model or the TV's transport order.
- Back from a related title or a cast face on the series page (it still lands on Play) — recorded below.

## Acceptance (TV, release build)
1. Series with 3+ seasons and Shuffle: Play → Down (open season) → Right ×2 → Down → Up (Shuffle) → Up (Play) →
   Down: the open season's pill.
2. Shuffle → player → Back: focus on Shuffle, pills on screen below the app bar. An episode card → player → Back:
   that card, on the same season.
3. A series whose button reads *Resume · S13E19*: the page opens on Season 13, the rail shows E19; Down from Play
   focuses Season 13.
4. Search → type → Down into the results → Back: back on the page Search was opened from, one press. Search: the
   field is focused, no keyboard; OK raises it; Back closes it; Back again leaves.
5. Discover: Down from the top bar lands on the selected tab; Down again the wall; Up the tab; Up the bar.
6. Player on episode 3: the rail header reads *Season 1*.
7. Pause, wait 10 s: the chrome stays. Play, wait 4 s: it hides; Right ×2 moves two controls from where focus was.
8. Home on arrival: the hero has a ring. A series page on arrival: Play has a ring.
9. Movies: Up from the last tile of the first row lands on a facet chip.

## Verification
Robolectric key-by-key walks under `:ravilo-ui:testDebugUnitTest` (R343's `SeriesDetailFocusTest` and siblings) for
FR-1/2/3/4/5/9, pure tests for FR-6/7, plus the release APK and `check-player-dex.sh`.

## Build notes (2026-10-02)

**Built, all twelve FRs, client-only.** No wire or server change; no new strings (`detail.season` was already in the
table, unused).

- **FR-1** — `SeasonPicker`'s row lost `focusRestorer()` (Compose 1.9 runs a restorer's enter rule on a
  `requestFocus()` aimed at a child, so Down from Play's request for the open season's pill came back as the restored
  pill). The row now remembers the last focused pill itself and uses it only for `FocusDirection.Up` (Up from the
  rail), in its own `focusGroup`.
- **FR-2** — `SeriesDetailStore.returnTarget` (`SeriesReturnTarget`, read once): Play, Shuffle (TV pill or phone chip)
  or an episode card (a multi-episode card by its first id) plus the open season. On the way back the page reopens on
  that season and focuses the control through the page's one scroll-then-focus helper, item 1 parked below the app
  bar; Play keeps the opening rule. Also covers the Mac's "the scroll position resets".
- **FR-3** — `openingSeasonIndex(seasons, overlay, primaryId)`: the season holding `primaryEpisodeId`; R346's rule
  without one. The rail's opening scroll (`railOpeningIndex`: the returned card, else the primary episode, else the
  first unwatched, else 0) moved from the rail's lazy item to the page, on a hoisted `LazyListState` with
  `requestScrollToItem`, keyed on the season and on the overlay *landing* — not on every overlay change, which used to
  scroll the rail away from a focused card after a Watched toggle or the post-player refresh. (Running it inside the
  lazy item against the hoisted state crashed with "performMeasureAndLayout called during measure".)
- **FR-4** — TV only (`isTvPlatform`): `backToTopOnBack(atTop = { !inGrid || isTvPlatform })`, so Back from the grid
  leaves; `showKeyboardOnFocus = false` and `DirectionCenter` on the field calls `show()`; arrival and Up-from-grid
  focus the field without the keyboard. The phone keeps R277 (bar re-tap still shows it). The Seerr search got the
  same rules. Whether Sony's IME honours `showKeyboardOnFocus = false` is the one thing only the TV can say.
- **FR-5** — `DiscoverSegmentBar(activeFocusRequester, onUp)`: the app bar's Down lands on the selected tab (the
  content when there are none); the strip redirects an Up/Down entry to the selected tab (Up from the wall took the
  chip nearest above the tile — Studios over Networks); Up from a tab goes to the page's nav item.
- **FR-6** — `playerRailSeasonLabel()`: *Season N* (`detail.season` + the number, carried on a new
  `PlayerEpisodeEntry.seasonNumber`/`seasonName`, set by the series page), the season's own name for Specials, the
  number read from the kicker when absent. TV rail and phone episode sheet.
- **FR-7** — `hideChrome()` no longer moves focus; the auto-hide holds while paused, scrubbing or locked on every
  form factor; `dpadRevealsOnly`: Left/Right/Up act on the first press from the remembered control, Down reveals only
  unless it is the seek bar → Play move, OK stays play/pause and now puts the highlight on Play. Supersedes R178
  FR-RV-SEL1-1 and R251 FR-R251-1/2 for those keys; R251's own rule (*the same keys reach the same control whether or
  not the chrome hid*) is now exact, tested in `PlayerDpadRevealTest`. `PlayerScreen`'s widest R8 dex method:
  **239 registers** (limit 250) — the changes added no local.
- **FR-8** — the Home hero's inset ring (`HERO_FOCUS_RING_TAG`); `RaviloButton` (TV branch) draws a 2 dp ring 3 dp
  outside the button, its layer no longer clips (the fills carry the shape); `DetailSynopsis` draws a ring around the
  text; the trailer overlay's Close shows focus. All through `rememberFocusVisual()`, so a handset draws none.
- **FR-9** — `BrowseCardGrid(onFirstRowUp)`: Up from a first-row tile requests the facet bar's first chip (its
  `focusRestorer` hands it to the chip last focused there, if any).
- **FR-10** — `EpisodeCard`/`MultiEpisodeCard(seasonNumber)`: *S01E01 · title* via `episodeCode`; *E1* only when no
  season is known (nothing passes none today).
- **FR-11** — `Modifier.arrowKeysMoveFocus(isDesktopPlatform)` at the app root (`ArrowKeysMoveFocus.kt`): an arrow no
  focused element consumed calls `FocusManager.moveFocus`. Confirmed by reading CMP 1.9.3's `RootNodeOwner`
  (`getFocusDirection` maps Tab, Shift+Tab, DirectionCenter and Back only). Not applied to the web build.
- **FR-12** — on the desktop layout the pill row is a `FlowRow` (wraps); the TV/phone keep the scrolling `Row`.

**Tests** (`:ravilo-ui:testDebugUnitTest`, 326 tests, all green): `SeriesDetailFocusTest` gained 12 walks (R350-1,
Shuffle/card/Play → player → Back, the Resume-in-Season-3 opening, desktop walks for the pills → rail, *Mark watched*
Up/Right/Down, card → player → Back, and 14-season pill rows on TV and desktop) — the desktop walks put the page in the
desktop layout family with arrows moving only through `arrowKeysMoveFocus`, and fail without it (checked).
New: `SearchFocusTest` (Robolectric `television` qualifier, so `isTvPlatform` is true; a recording
`SoftwareKeyboardController`), `DiscoverFocusTest`, `BrowseFocusTest` (on `fakeTvApiClient`, an OkHttp interceptor
answering by path — fails without the fix, checked), `HeroFocusRingTest`, `PlayerRailSeasonLabelTest`,
`EpisodeCardCodeTest`; `SeriesEpisodesTest` and `PlayerDpadRevealTest` updated. `SearchStore` and `TaxonomyStore` take
their fetch as a function (the `TvApiClient` constructors stay). Also green: `:ravilo-android:assembleRelease`,
`:ravilo-web:compileKotlinWasmJs`, `-Pravilo.desktopOnly=true :ravilo-desktop:compileKotlinDesktop`, every CI check
script that runs without a device (`verify-release-apk-on-art.sh` needs an emulator and was not run).

**Not verified:** anything on a device — the Sony BRAVIA (acceptance 1–9), the Mac app's arrows, the phone (R277 path
untouched by design, but not re-tapped). The two things only the TV can answer: the IME and `showKeyboardOnFocus`, and
how the new button/hero rings read at 10 feet.

**Seen, not fixed (recorded for a later phase):**
- Back from a related title or a cast face on the series page still lands on Play (FR-2 covers playback only).
- On the desktop, Settings' `clickable` rows and tabs (`SettingsScreen.kt` `deskHoverRow`) take keyboard focus with
  no focus ring; the update toast and install card (web) likewise.
- The web build has the same "arrows don't move focus" gap as the desktop had (FR-11 is desktop-only, unverified on
  the web).
- `ProfilePickerScreen`'s *Cancel* (add-user) shows no focus — left alone, it is part of the sign-in flow another
  phase is changing.
- The facet bar's `focusRestorer` has FR-1's shape: the app bar's Down asks for the first chip and gets the one last
  focused. Arguably right there (it is the bar's memory), so left.

