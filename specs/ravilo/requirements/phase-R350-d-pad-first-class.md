# Phase R350 — D-pad first-class: the series page, Back, Search, Discover, the player and focus you can see

> Owner, 2026-10-02, after an evening with Ravilo on a Sony BRAVIA (960 × 540 dp, release build): *"We want dpad users
> to get like they have first class support."* Eight findings from that session, plus the siblings found while fixing
> them.

## Status

`✓ Built` — written 2026-10-02 (dev-authored) from the owner's TV session, before any fix; built the same day (see
*Build notes*). Not deployed, not device-tested. Client-only (`:ravilo-ui`); no wire change, no server change. Number
given by the coordinator. **Amended 2026-10-02** after a re-test on the Sony (FR-13 to FR-16: Search arrival without
the keyboard, Change your password and sign-in fields that open the keyboard only on OK, Settings opening at the top);
built the same day, see *Re-test on the TV* at the end.

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
- The sign-in and server-setup screens (another phase is changing them). *R349 has since landed; the re-test
  amendment's FR-15 brings the sign-in fields under the same keyboard rule.*
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


## Re-test on the TV, 2026-10-02

The owner re-tested a release build of `main` `6b0a74ac` on the Sony BRAVIA the same day. Inside Search everything
from FR-4 held (Up from the results focused the field with no keyboard, OK raised it, Back closed it), but three
findings remained.

### FR-R350-13 — Arriving at Search on a TV never raises the keyboard
**Seen:** opening Search from the top bar's search icon (OK) showed the keyboard at once, every time.
**Cause:** FR-4 relied on `KeyboardOptions(showKeyboardOnFocus = false)`. Only the state-based
`BasicTextField(TextFieldState)` reads that flag. Ravilo's fields are the `value: String` overload, which is Compose's
legacy `CoreTextField`: it starts an input session whenever it gains focus while editable, and on Android starting a
session shows the keyboard (`TextInputServiceAndroid` turns *start input* into *show keyboard*). The flag was silently
ignored. FR-4's test recorded a stand-in `SoftwareKeyboardController`, which this path never calls, so it passed.
**Requirement (TV):** arriving at Search (and at the Seerr search) focuses the field with a visible ring and **no input
session**; OK on the field makes it editable and raises the keyboard; D-pad focus coming back to the field (Up from the
results, after typing too) never raises it. The phone keeps R277.

### FR-R350-14 — Change your password: D-pad focus on a field never raises the keyboard
**Seen:** Down from *Back* landed on *Current password* with the keyboard up, so the next OK typed a letter.
**Requirement (TV):** the same rule as Search and the server-setup address (R349 FR-R349-7): a field the D-pad lands on
shows a focus ring and no keyboard; OK opens the keyboard. Down/Up move between the fields and to *Save password*
without the keyboard. The keyboard's *Next* keeps typing in the next field (the viewer is typing already). R348's
password keyboard options and R349's scroll-above-the-keyboard stay.

### FR-R350-15 — The sign-in form follows the same rule
The sign-in screen's Username and Password behaved the same (arrival focused Username and raised the keyboard). Same
requirement as FR-14, including *Next* from Username carrying the typing on to Password. R349's walks keep passing.

### FR-R350-16 — Settings opens at the top, with focus on its first control
**Seen:** Settings opened scrolled to the middle with focus on the Playback toggle *Show progress on Continue
Watching*; one stray OK flipped it.
**Cause:** `SettingsContent` requested focus on that toggle once the settings loaded (a bug-fix-era *land focus on a
stable control*), overriding the page's own request for *‹ Back*; bringing the toggle into view scrolled the page.
**Requirement:** Settings opens at the top with focus on its first control, *‹ Back*. A stray OK there leaves Settings
and changes nothing. (The first setting is not a safe landing: on a TV the first theme pill is usually not the theme in
use, so OK on it changes the theme.) Down from Back enters the first section (Appearance's first theme, or the skins,
or Language); Up from that section returns to Back.

### The fix

- **`OkToEdit`** (`screens/OkToEdit.kt`, TV only, inert elsewhere): the field is `readOnly` until OK. A read-only
  legacy field starts no input session when focused; turning it editable while focused starts one, and the keyboard
  comes with it. Leaving the field makes it read-only again. OK (or a keyboard's Enter) on a non-editing field starts
  editing and both halves of that press are eaten; OK on a field already being edited (keyboard closed with Back) shows
  the keyboard again. `continueTyping()` keeps an IME *Next* in typing. The caller draws a 3 dp focus ring from
  `focused` (a read-only field draws no cursor).
- Used by Search, the Seerr search, the sign-in fields and the three Change your password fields. The server-setup
  address keeps R349's focusable frame (same behaviour, already correct).
- **One more legacy path:** the field's first-composition effect restarts input if the field already holds focus when
  it runs, read-only or not. A screen that focuses a field on arrival now waits one frame first (`awaitFieldReady()`),
  so that effect has run and found nothing focused. On the device the arrival `LaunchedEffect` was already dispatched
  after it; under the test clock it ran first, which is how the tests found it.
- **Settings:** the toggle's focus grab is gone; *‹ Back* keeps the arrival focus; `SettingsContent` takes `topFR`
  (Back) and `firstFR` (worn by the first section's first control) so Back's Down and the first section's Up are
  explicit.
- `showKeyboardOnFocus` is no longer passed (it did nothing on these fields).

## Build notes (re-test amendment, 2026-10-02)

**Built, FR-13 to FR-16, client-only.** No wire or server change, no new strings. Built 2026-10-02, not deployed, not
device-tested.

- **Root cause of FR-13 confirmed in the test harness before the fix:** with the R350 code, Robolectric's
  `InputMethodManager` shadow reported the keyboard shown and the view held an input session right after Search
  arrived, although `showKeyboardOnFocus = false` was set. Read from Compose 1.9.4's sources: only
  `TextFieldDecoratorModifier` (the state-based field) checks the flag; `CoreTextField` calls `startInputSession` on
  focus, and `TextInputServiceAndroid.processInputCommands` sets *show keyboard* for every *start input*.
- Why *Up from the results* looked right on the Sony before the fix is not known: the same session start ran there too.
  It does not matter now — no path starts a session until OK.
- **Tests** (`:ravilo-ui:testDebugUnitTest`): every keyboard assertion reads the real `InputMethodManager` (Robolectric
  shadow) and the view's input session (`onCheckIsTextEditor()`), not a stand-in controller.
  - `SearchFocusTest` (rewritten, 5): arrival (no keyboard, no session; OK raises it); **arrival from a focused
    top-bar icon by OK**, the press's release landing on the new page; Back from the results; Up from the first row;
    type → Down → Up comes back without the keyboard, OK opens it again.
  - `SettingsFocusTest` (new, 3, TV): opens on *‹ Back* with the title on screen and the toggle not focused; a stray OK
    leaves and sends no `PUT /api/tv/settings`; Back → Aurora → English → Show progress → Autoplay → Change password and
    back up to Back, with no write.
  - `KeyboardFormTest` (+3): sign-in arrival / Down / Up with no keyboard, OK opens it, Up after typing closes it;
    *Next* from Username keeps the keyboard for Password; Change password: Down from Back and through every field to
    *Save password* and back with no keyboard, OK opens it, *Next* keeps typing in New password. R349's walks unchanged
    and green.
  - `PasswordKeyboardTest` (+3): on a TV each password field hands the keyboard R348's options once OK opens it, and a
    focused field opens no input connection before OK. The phone-configuration tests now initialise the app context
    themselves (`isTvPlatform` had been reading a previous TV test's context).
  - Checked: the new Settings, sign-in and Change password tests fail against the previous screens (6 failures), and
    the Search arrival assertion fails against the previous Search (keyboard shown on arrival).
- Also green: `:ravilo-android:assembleRelease`, `:ravilo-web:compileKotlinWasmJs`,
  `-Pravilo.desktopOnly=true :ravilo-desktop:compileKotlinDesktop`, the CI check scripts that run without a device.

**Owed, on the Sony:** Search from the top bar (no keyboard; OK raises it; Back closes it; Back leaves); Settings (opens
at the top on *‹ Back*; Down walks the sections); Change your password and sign-in (Down/Up through the fields with no
keyboard, a ring on the focused field, OK opens the keyboard, *Next* carries on). And on the Pixel 9 that a tap on each
of those fields still opens the keyboard at once (the phone path is untouched by design).

## Amendment (2026-10-02) — Play and Start over come back where they were

**Seen in the Mac re-test (backend `v1.48-54-gfefa9049`):** Back from a *Start over* play landed at the top of the series
page, while Back after a *Shuffle* play kept the page's scroll and the control.

**Why.** The page remembered Play as the control, but on the way back it ran the ordinary arrival (`focusPlay`: scroll to
the top, then Play), and it reopened on the opening season rather than the one that was open. With a mouse, Play is
pressed on a page scrolled down (Play still on screen above the Episodes section); a click does not move focus, so the
page was never put back at the top before playback. On top of that, R72's reframe (focusing the hero's buttons scrolls
the hero back into full view) would have undone any restored scroll.

**FR-R350-2a — Play and Start over use the same return target as Shuffle.** Pressing Play / Resume / Start over records
the open season and the page's scroll (first visible item and its offset) with the control. Back from the player (or the
cast remote) reopens that season, puts the page at that scroll and focuses Play there; the one focus request that does
so does not trigger R72's reframe. With no recorded scroll (an older store), the ordinary arrival stands. On a TV,
pressing Play always has the page at the top (Play is reached by `focusPlay`), so the TV's result is unchanged except
that the open season comes back too.

**Built 2026-10-02 (FR-R350-2a), not deployed, not device-tested.** `SeriesReturnFocus.Play` carries a `ListScroll`;
`returnSeason` no longer skips Play; the return path scrolls with `scrollToItem(index, offset)`, sets
`keepScrollOnPlayFocus` and requests focus through `requestFocusRetrying`; the hero row's R72 reframe skips that one
focus. New Robolectric walk in `SeriesDetailFocusTest` (desktop): swipe the page up 120 px, press Play, leave, come back
— Play is focused at the same y; checked to fail without the fix (it came back at the top). All 17 walks pass.
**Re-test (Mac):** a series → scroll down until the Episodes header shows with *Start over* (or *Resume*) still on
screen → click it → Back: the page is where it was, *Start over / Resume* is focused, the same season is open.
