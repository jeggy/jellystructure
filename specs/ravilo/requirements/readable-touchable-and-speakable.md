# Readable, touchable and speakable: contrast, a pressed look, screen-reader names, phone targets, a still hero

Task: DEB-5838

> Owner, 2026-11-26 (DEB-5687): *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app
> beautiful in our edge cases, but remember that QoE and performence is most important. We are also now getting more and
> more users."* He picked this piece, polish group D, on the card "Ravilo polish: what to build" on 2026-11-26.

## Scope

Group D of the DEB-5687 polish batch: five rows, all in `ravilo-ui` (`commonMain`, with small `androidMain` and
`desktopMain` seams). It reaches the Android app (TV and phone), Ravilo Web and the desktop app (Mac and Linux).

- **In:** Android TV, Android phone, Ravilo Web, desktop Mac and Linux, each as each FR says.
- **Out:** Ravilo Samsung (`ravilo-screen`, its own DOM and CSS) and the Chromecast receiver (no browsing UI). Their look
  and contrast are DEB-5687's "one product on every screen" work, not this.
- **Amends** DEB-2204 (`focus-rings-and-loaded-content-fade-in.md`), whose FR-4 left the hero out of reduced motion on
  purpose. The dated note is in that file. **Builds on** R338 (the five themes and their 4.5 : 1 text rule), R244 (the
  phone player's chrome), R298 and R350 (where focus is drawn), R374 (the hero draws no ring) and R383 (the stepped drift).

No wire change, no server change, no schema. Nothing here touches the playback start path.

## Why

The DEB-5687 read (evidence document, sections 3 and 4) found, against `main` and re-checked on 2026-11-27:

- **Daylight's Play label is 1.29 : 1.** A phone never shows focus, so the primary `RaviloButton` always rests on
  `accentDim` (`#E3E0FB`) under `onAccent` (white) (`components/RaviloButton.kt:139, 149`; `theme/Colors.kt`, `DaylightColors`).
- **Midnight's white on teal is 1.83 : 1** on every focused or selected accent fill (`MidnightColors`: `accent #19D6C6`,
  `onAccent #FFFFFF`). That includes the TV's focused Play.
- **A tap shows nothing.** `dpadFocusable`'s pointer path is a bare `detectTapGestures` (`focus/FocusModifiers.kt:212-223`), and
  the phone's `Modifier.tap` is `clickable(indication = null)` (`music/MusicCommon.kt:314`). There is no ripple and no
  pressed state anywhere.
- **A screen reader is not told a tile or button is a button.** `dpadFocusable` (95 call sites) is a key handler,
  `focusable()` and a tap detector, with no role and no click action (`focus/FocusModifiers.kt:160-237`).
- **The phone film player is unnamed.** `HandsetIconButton` names nothing: Back passes no label, and PiP, full screen
  and rotate pass one with `showLabel = false`, which drops it (`screens/PlayerHandsetChrome.kt:205, 235, 244, 262, 328-350`).
  Play/Pause is a bare canvas (`:386-405`), the skip buttons say only "10 s" and "30 s" (`:277-279`), the seek bar is a
  bare canvas (`:499-510`), and the chrome hides after 3.0 s whatever assistive tech is on (`:118`).
- **Two phone targets are 24 to 28 dp.** The episode mark-watched chip is a 13 dp glyph with 5 dp padding
  (`screens/SeriesDetailScreen.kt:1319-1330`); the music queue row's "more" is a 24 dp box (`music/MusicPlayerScreens.kt:343`).
- **Reduced motion leaves the hero running.** It still advances every 7 s with its Ken Burns drift and a 600 ms
  crossfade (`components/HeroCarousel.kt:145-195, 239-246`). Linux never reports the setting: the desktop seam reads it
  only on a Mac (`desktopMain/.../seams/DesktopWindowSeams.kt:108-118`). On a computer the hero can also change slide
  between a pointer reaching Play and the click (`HeroCarousel.kt:364-370`).

More viewers now arrive on phones than on any other device (6 of them in 30 days), and the light theme is the default
for a new phone install (R338 D5).

## Requirements

### FR-1 — Daylight's primary label reads at 4.5 : 1 or more

- The primary `RaviloButton` on a phone, in Daylight, draws its label at **4.5 : 1 or more** against the fill it rests on.
- Two candidates, for Rannvá to pick (see Design): the label in `accent` on the pale fill (`#5B4EE0` on `#E3E0FB`,
  4.52 : 1, barely over), or the resting fill in `accent` under white (5.82 : 1, the same as the focused one).
- The other four themes and the desktop layout's button (`isDesktopLayout`, ink on the page colour) do not change here.

### FR-2 — Midnight's label on an accent fill reads at 4.5 : 1 or more

- In Midnight, any label drawn in `onAccent` on `accent` is **4.5 : 1 or more**: the focused primary button on a TV, a
  selected chip, a selected segment.
- Two candidates, for Rannvá to pick: dark ink on the teal (`#04101A` on `#19D6C6`, 10.49 : 1), which needs the label
  on `accentDim` to stay white, so the button picks its ink per fill; or a deeper teal under white (`#0B7A71`, 5.2 : 1),
  which changes the theme's character.
- A colour change only. Focus motion on a TV is unchanged: the same animated fill, a different value.

### FR-3 — A press shows while the finger or pointer is down

- **Phone, Ravilo Web, desktop:** a tile, a `RaviloButton`, a nav item, a list row, and anything built on
  `dpadFocusable`'s tap path or on `Modifier.tap` shows a **pressed look from pointer-down until release or cancel**:
  the target draws at 0.97 scale with a `fg` overlay at 8 % alpha, and returns in the release frame. A drag that
  becomes a scroll cancels it.
- **Draw-only.** The pressed value is read inside `graphicsLayer` or `drawWithContent`. A press never recomposes the
  target and never changes its layout size.
- **Android TV:** unchanged. The pressed look is driven only by pointer input, which a remote never sends. Enter and
  D-pad centre draw no press, and focus motion gains no work.
- `check-theme-whites.sh` still passes: the overlay is `fg`, not white.

### FR-4 — A screen reader is told what a tile or button is, and can press it

- `dpadFocusable` with an `onSelect` or `onTap` gives its node **`Role.Button`** and an **`onClick` semantics action**
  that runs the same callback a tap runs. A caller may pass its own role (a tab, a switch) and a click label.
- A `Tile` names itself by its title, and by "Watched" when it carries the watched badge. A `RaviloButton` names itself
  by its label.
- **Android phone and TV (TalkBack), desktop (VoiceOver on a Mac):** a tile is read as "<title>, button", and a
  double-tap opens it.
- **Ravilo Web:** out. Its accessibility tree stays off (`ravilo-web/.../Main.kt:44`) until its frame cost is measured.
  The semantics added here are present and tested on desktop, so turning the tree on later needs no change in `ravilo-ui`.
- **No cost on focus motion:** semantics are static per item, so they add no recomposition on a focus move. DEB-2204's
  FR-3 composition counts still hold for `Tile` and `RaviloButton`.

### FR-5 — A screen reader can run the phone's film player

- **Every control is named:** Back (`player.back`), Picture in picture, Full screen or Exit full screen, Rotate,
  Play or Pause, Back 10 seconds, Forward 30 seconds. A label hidden by `showLabel = false` still names the node.
- **The seek bar** is a slider to a screen reader: it states the position as "{position} of {duration}" and accepts
  the reader's increase and decrease (one step is 10 s back or 30 s forward, the same as the buttons) and set-progress.
- **The chrome does not hide while a screen reader is on** (Android touch exploration). It still hides on a tap of the
  picture, as now. The 3.0 s timer is unchanged for everyone else.
- **New string keys**, English text:
  - `player.skip_back_10`: "Back 10 seconds"
  - `player.skip_forward_30`: "Forward 30 seconds"
  - `player.pause`: "Pause" (Play reuses `action.play`)
  - `player.seek_position`: "Position"
  - `player.seek_state`: "{position} of {duration}"
  The visible "10 s" and "30 s" under the arrows stay. Danish and Faroese are added in the same commit, each value
  carrying a `note` that it is a draft to check (`i18n/README.md`), so `check-ravilo-strings.sh` finds no gap.
- Phone only. The TV and desktop players are driven by keys and keep R244's and R329's chrome.

### FR-6 — The two small phone targets take a 48 dp touch

- The episode mark-watched chip and the music queue row's "more" each take a touch **at least 48 × 48 dp**, centred on
  what is drawn. What is drawn does not change size, and no row gets taller.
- On the desktop layout (a mouse) the drawn size is the target, as today.
- Other targets under 48 dp (subtitle size chips 36 dp, Cast 40 dp, R244's 46 dp player buttons) are not changed here.

### FR-7 — Reduced motion keeps the hero still

- **When `LocalReducedMotion` is true:** the hero does not auto-advance, has no Ken Burns drift (scale 1.0), and changes
  slide as a cut, not a crossfade. Left and Right, a swipe and the dots still change slide. The order of slides is
  unchanged.
- **Linux reports the setting.** The desktop seam reads, once, as the Mac does: GNOME's `org.gnome.desktop.interface`
  `enable-animations` false, or KDE's `org.kde.kdeglobals.KDE` `AnimationDurationFactor` 0, through the existing
  `LinuxPortal.readSetting`. No portal, or no answer within 2 s, means motion is allowed.
- **On a computer, the hero does not advance while the pointer is over it.** The interval restarts when the pointer
  leaves, so a click on the hero's Play or More info always acts on the slide the viewer saw.
- Under reduced motion the hero does less work than today. With the setting off, nothing about the hero changes.

## What this does not do yet

- Make Ravilo Web usable by a screen reader (its tree stays off).
- Large system text (1,172 inline sizes), dim text on cards in every theme, the watched tick's 1.91 : 1, and the
  remaining accent pairs under 4.5 : 1 in Aurora, Graphite and Noir (see the open question on the task).
- Reduced motion for page transitions, tile springs and R240's row-open. They keep DEB-2204's rule.
- Subtitle styling, captions preference, audio-description track labels.
- Samsung and Chromecast.

## Design

No design round. Every FR reuses an existing component.

Two choices need **Rannvá's eye, not a round**: she picks FR-1's and FR-2's candidate (or gives her own value that
meets 4.5 : 1), and says yes or no to FR-3's pressed look (0.97 scale, `fg` 8 %). The engineer posts each candidate
on the task as a desktop screenshot of the detail page's buttons and a Home row, in Daylight and Midnight. Her
answer on the task is the decision. Only `theme/Colors.kt` changes; nothing under `design/` is written by this piece.

## Acceptance

1. `ThemeContrastTest`: for Daylight and Midnight, every label ink on every fill it is drawn on (primary resting,
   primary focused, selected chip) is 4.5 : 1 or more. The test prints the same pairs for all five themes.
2. On a Pixel in Daylight, the detail page's Play label reads clearly; on a TV in Midnight, a focused Play reads clearly.
3. On a phone, a desktop and a browser, holding a finger or the mouse button on a tile or button shows the pressed look
   until release; a scroll that starts on a tile shows none at the end. On a TV nothing changes.
4. With TalkBack on a Pixel: a Home tile reads "<title>, button" and a double-tap opens it; every film-player control
   is read by name; the seek bar reads "{position} of {duration}" and moves by swipe up and down; the chrome stays up.
5. On a Pixel, the episode mark-watched chip and the queue row's "more" respond to a touch 10 dp outside what is drawn.
6. With reduced motion on (Android "Remove animations", a Mac's Reduce motion, GNOME animations off, a browser's
   `prefers-reduced-motion`), the hero stays on one slide for a minute, does not drift, and Right changes slide with a cut.
7. **TV frames:** the DEB-2204 `gfxinfo` journey on the BRAVIA on API 31, before and after FR-3 and FR-4: janky frames no more
   than 0.5 points above the before build.

## Build order

One engineer, in this order. Each slice is one commit citing `(DEB-5838, FR-k)`.

1. **FR-7 hero under reduced motion.** `components/HeroCarousel.kt`. Proof: `HeroReducedMotionTest` (no advance over
   20 s of test clock, scale stays 1.0, Right changes `activeIndex` in one frame).
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*HeroReducedMotion*' --console=plain`.
   About 60 lines. **Junior-ready.**
2. **FR-7 pointer over the hero pauses it.** `HeroCarousel.kt` (the `inView` flow joins a hover flag). Proof: a case in
   `HeroReducedMotionTest` with a hover interaction. About 40 lines. **Junior-ready.**
3. **FR-7 Linux reads the setting.** `desktopMain/.../seams/DesktopWindowSeams.kt`, a pure parser beside `LinuxPortal`.
   Proof: `LinuxReducedMotionTest` (GNOME `false`, KDE `0`, KDE `1.0`, no answer). `:ravilo-ui:desktopTest --tests
   '*LinuxReducedMotion*'`. About 60 lines. **Junior-ready.**
4. **FR-6 two touch targets.** `screens/SeriesDetailScreen.kt`, `music/MusicPlayerScreens.kt`. Proof:
   `PhoneTouchTargetTest` (touch bounds ≥ 48 dp, drawn bounds unchanged, row height unchanged). About 50 lines.
   **Junior-ready.**
5. **FR-4 roles and click actions.** `focus/FocusModifiers.kt`, `components/Tile.kt`, `components/RaviloButton.kt`.
   Proof: `DpadSemanticsTest` (role, click action runs `onSelect`, tile name) and DEB-2204's composition-count tests
   still green. `:ravilo-ui:desktopTest --tests '*DpadSemantics*'`, then `:ravilo-ui:compileDebugKotlinAndroid` and
   `:ravilo-ui:compileKotlinWasmJs`. About 120 lines. Not junior: it sits under 95 sites and owes acceptance 7 on a TV.
6. **FR-3 pressed look.** `focus/FocusModifiers.kt`, `music/MusicCommon.kt`, `components/RaviloButton.kt`. Proof:
   `PressedLookTest` (scale and overlay while down, back in the release frame, no recomposition, none from a key).
   About 150 lines. Not junior: shared modifier, TV frame check, and it waits on Rannvá's yes.
7. **FR-5 phone player for a screen reader.** `screens/PlayerHandsetChrome.kt`, a `screenReaderOn()` seam
   (`androidMain` reads touch exploration; desktop and web false), `i18n/en.json`, `da.json`, `fo.json`. Proof:
   `HandsetChromeSemanticsTest` (every control named, seek bar range and state, no hide while the seam is true),
   `:ravilo-ui:testDebugUnitTest` for the seam, `scripts/check-ravilo-strings.sh`. About 180 lines. Not junior: an
   Android seam and a TalkBack leg on a Pixel.
8. **FR-1 and FR-2 colours, after Rannvá's pick.** `theme/Colors.kt`, `components/RaviloButton.kt` (ink per fill if
   FR-2 takes dark ink). Proof: `ThemeContrastTest`, `scripts/check-theme-whites.sh`.
   About 80 lines. Not junior: it waits on a design decision and touches the TV's focused fill.
