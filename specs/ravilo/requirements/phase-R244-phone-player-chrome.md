# Phase R244 — The player on a phone

> `PlayerScreen` renders the **TV chrome verbatim on a phone**: 48 dp gutters, D-pad pills with focus
> rings, forced sensor-landscape, no gestures, no safe-area reads. A phone held upright has no player at
> all. This phase gives the handset its own chrome — transport centred on the picture, secondary controls
> on a thumb-reachable rail, the conventions every phone video app has made invisible — while leaving the
> TV untouched. It deliberately does **not** add playback speed.

## Status

`Planned` — written 2026-09-16, **not dev-reviewed**. Built into the mockups 2026-09-16.

**Numbering:** verified against `main` on 2026-09-16 — admin taken through **217**, Ravilo through
**R243**. No `phase-R244-*` file and no `STATUS.md` row for it. The source research report
(`ravilo-mobile-player-chromecast-ios-2026-09-16.md` §6) proposed numbers against 216 / R243, both of
which were taken the same day by the Discover taxonomy pair, and 217 by the Towo removal; its whole
ladder therefore shifts, and this phase takes the next free Ravilo number rather than reserving gaps for
phases nobody has written. Next free after this phase and its Chromecast pair (218 + R245): **219 /
R246.**

Design / reference implementation: `design/ravilo/Mobile Player - Directions.html` (+ its 8-page print
copy) for the three directions and all twelve states; the build in `design/ravilo/Ravilo Mobile.html` +
`design/ravilo/mobile/ravilo-mobile-player.css` (`.mp-*`); strings in `design/ravilo/ravilo-i18n.js`.

Reuses without change: **R218** (loading / stall / seek states), **R237** (per-cause failed-start copy),
**R180** + **R195** (the two-level flag-forward audio/subtitle picker), **R234** (the phone mockup's
46 px / 13 px floors), **R179** (the multi-episode file card), **R229** (phone padding), **R222**
(the slow-to-start note).

## Current state

Measured against the code on 2026-09-16:

| Convention | Today |
|---|---|
| Tap to show/hide controls, auto-hide | ✓ `CHROME_HIDE_MS = 3600` |
| Drag to scrub | ✓ bar only; no thumbnail preview (`trickplayUrl` is always `null`) |
| Double-tap left/right to seek | ✗ `detectTapGestures` has no `onDoubleTap` |
| Swipe for brightness / volume | ✗ no gesture surface, and **no brightness API in any seam** |
| Pinch to fit / fill | ✗ `PlayerVideoSurface` letterboxes by DAR only |
| Portrait playback / rotate / lock | ✗ `PlayerImmersiveEffect` forces `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` |
| Lock controls (child-proof) | ✗ |
| Subtitle size on a phone | ✗ `SubtitleView` is the TV treatment (R55/R110) |
| Skip intro / next-up / episode rail | ✓ **but TV-sized** — `SkipIntroPill` is anchored `end 28 dp / bottom 160 dp`, and `NextUpCard` / `EpisodeRail` are unverified against a 412 × 915 frame |
| Safe areas (notch, punch-hole, gesture bar) | ✗ fixed 48 dp gutters, no `WindowInsets` read in the player |
| Haptics on seek / skip | ✗ |

`LocalHandset` (smallest side < 600 dp, orientation-stable) already exists and is correct. `PlayerScreen`
consults it at six sites, none of which is the chrome layout.

## Decisions taken (owner, 2026-09-16)

1. **Direction 2 · "Thumb rail"** — transport centred on the picture, secondary controls as a right-edge
   column in landscape that folds into a row above the seek bar in portrait.
2. **Auto-hide 3 000 ms**, not the TV's 3 600.
3. **Follow the sensor.** A rotate button appears *only* when the system has portrait locked.
4. **Both** brightness and volume swipes.
5. **Subtitle size** is a row inside the picker sheet.
6. **Playback speed is removed entirely** — no rail item, no sheet, no *More* entry, and **no new
   `RaviloPlayer` seam member**. This also removes a Wasm actual and a future iOS actual from the work.
7. **Haptics** on skip, lock and seek release.

## Functional requirements

**FR-R244-1 · A handset chrome layout, gated on `LocalHandset`.** The TV layout is not modified. On a
handset the chrome is: a top bar (back · kicker + title · cast · rotate-when-locked), a centred transport
(−10 s · ▶/❚❚ · +30 s), a seek bar with times at the bottom, and three secondary controls — **Subtitles ·
Next · Lock** (Episodes replaces Next on a film's chrome only where a season exists). Targets ≥ 46 px,
every readable label ≥ 13 px. The skip amounts render as labels **beneath** the arrow (`10 s`, `30 s`),
never as a numeral inside the glyph, because a numeral inside a 46 px circle cannot clear the floor.

**FR-R244-2 · The rail's orientation rule.** Landscape: a right-edge column, vertically centred, inset
from the safe area. Portrait: the same items in the same order as a row above the seek bar. There is no
third arrangement, and the Skip Intro pill is anchored **left of the rail and above the seek bar** so the
two can never overlap at any frame size.

**FR-R244-3 · Auto-hide.** 3 000 ms after the last interaction. Held open indefinitely while a bottom
sheet is open or a seek drag is in progress. **Suspended entirely while locked.** A paused player keeps
its chrome. A tap on a control never counts as a tap that hides the chrome.

**FR-R244-4 · Double-tap to seek.** Double-tap in the outer left third seeks −10 s, in the outer right
third +30 s, with a ripple carrying the amount. Repeat taps inside the window **accumulate** (10 → 20 →
30) while the ripple is up. The chrome does not rise: a double-tap is not a request to see the controls.
The centre third is excluded so the transport is never double-tapped by accident.

**FR-R244-5 · Vertical swipes.** A vertical drag on the left half sets screen brightness, on the right
half volume, each with a transient pill at the edge it belongs to and no persistent slider. Brightness
needs a **new seam member** — there is no brightness API anywhere in the codebase today — and it must be
scoped to the player's window, never the device default.

**FR-R244-6 · Pinch to fit / fill.** A pinch toggles between letterboxed (fit) and cropped (fill) and
shows a one-word toast for ~900 ms. It may not zoom to an arbitrary scale, and the choice does not
persist across titles.

**FR-R244-7 · Portrait playback and the rotate button.** `PlayerImmersiveEffect` stops forcing
sensor-landscape on a handset and follows the sensor. In portrait the video is letterboxed and centred
and the chrome is laid over the black, not over the picture. When the *system* has rotation locked to
portrait, and only then, a rotate button appears in the top bar. A permanently present rotate button is
out of scope — it would be a second control contradicting the first.

**FR-R244-8 · Lock.** Lock hides everything but a lock glyph above the home indicator. A tap shows
**"Locked · hold to unlock"** for ~1.2 s; a long-press unlocks. Gestures are inert while locked;
auto-hide is suspended; the system back is **not** blocked.

**FR-R244-9 · Scrubbing.** Drag enlarges the thumb to 26 px, raises a time bubble above it, and shows a
ghost mark where the drag began. **No thumbnail preview** — `trickplayUrl` is `null` on every ticket, and
a blank tile is worse than no tile. A haptic fires on release, not during the drag.

**FR-R244-10 · The picker as a bottom sheet.** R180/R195's two levels, unchanged in content: language
rows with flags, then versions with the same forced / SDH / signs badges and R239's honest counting.
Tap-away dismisses. Added on phone only: a **Subtitle size** row (S · M · L) that applies live behind the
sheet and says *"Applies on this phone only."* The sheet is **one component with two destinations** — the
same sheet serves the local player and the casting remote (R245), and the only difference is one line
naming the device the choice applies to.

**FR-R244-11 · Episode rail → season sheet.** The TV's horizontal rail becomes a vertical list of the
season on a handset: thumbnail with progress, number, title, runtime, the current episode marked. R179's
multi-episode file card keeps its own treatment inside the row.

**FR-R244-12 · Skip intro, next-up, waiting and failure at phone scale.** The Skip pill and the Next-up
card take handset sizes (pill ≥ 46 px; next-up a corner card in landscape and a full-width strip in
portrait). Waiting states reuse **R218** verbatim, including the 400 ms debounce and the *"Still trying…"*
line from R237 FR-R237-5. A failed start renders **R237's existing per-cause sentence** in a bottom sheet
with *Try again* / *Back* — no new copy, nothing new to translate.

**FR-R244-13 · Safe areas.** The player reads `WindowInsets` and insets the chrome from every one:
nothing under a Dynamic Island, a punch-hole or a gesture bar, and the seek bar clears the home
indicator. On iOS there is no system back button — the edge swipe leaves, and leaving must do exactly
what Android's back does, i.e. end the session (phase 180).

**FR-R244-14 · Haptics.** A light tick on skip (both buttons and the double-tap), on lock, and on seek
release. Nothing on play/pause — the picture answers that.

**FR-R244-15 · The Live TV player.** `LiveTvPlayerScreen` gets the same chrome with **no seek bar**: the
channel name and a Now/Next line replace the title, and a vertical swipe on the right edge changes
channel. It has no phone handling of any kind today.

**FR-R244-16 · Strings.** Thirteen new strings × en/da/fo (`ravilo-i18n.js`): `pl_subtitles`,
`pl_episodes`, `pl_next`, `pl_lock`, `pl_guide`, `pl_locked_hint`, `pl_rotate`, `pl_fit`/`pl_fill`,
`pl_play_now`, `pl_sub_size`, `pl_size_s`/`_m`/`_l`, `pl_sub_size_note`. **No speed strings.** Where a
string already exists — *Loading…*, *Cancel*, *Back*, R237's nine sentences — the existing wording wins;
the Danish and Faroese above are drafts and must be reviewed before release.

## Non-goals

- **Playback speed**, in any form. Removed by owner decision; no control, no sheet, no seam, no string.
- **Background audio, PiP, a local mini-player.** Local playback keeps today's contract: foreground only,
  session ends on `ON_STOP`. The only mini bar in Ravilo is the casting one (R245).
- **A lock-screen or notification card for local playback.** R193 stands: the phone never creates an
  OS-level `MediaSession`. The *casting* notification is R245's, and is the Cast SDK's own.
- **Thumbnail scrub previews.** Nothing to render.
- **Offline downloads. Tablets.** `LocalHandset` is the switch that keeps both the TV and a future tablet
  layout out of this.
- **Any AirPlay affordance**, ever (owner decision, 2026-09-16).
- **Quality on mobile data.** A real and separate gap — a phone on cellular gets no bitrate cap at all,
  because `detectLinkState()` returns `UNKNOWN` for everything that is not ethernet or Wi-Fi and
  `linkKind = "unknown"` means phase 177's cap never applies. It is a Settings row plus a server change,
  not a player change, and is its own phase.
- **Brief §D** — Home, Detail, Browse, Search, Discover, the Live TV *guide*, login and the Settings
  additions. Second design round.

## Acceptance

1. On a handset in landscape, every secondary control is reachable without changing grip, and the Skip
   Intro pill never overlaps the rail or the seek bar at any frame size.
2. A phone held upright plays. Rotating it is not required, and no rotate button is visible while the
   system allows rotation.
3. Double-tapping the right third three times in quick succession seeks +90 s, not +30 s, and the chrome
   does not appear.
4. With a sheet open, the chrome does not auto-hide. Locked, it does not auto-hide and no gesture does
   anything.
5. Subtitles are **one tap** from a visible chrome, and the sheet that opens is byte-for-byte the same
   component the casting remote opens.
6. No screen, string, log line or control anywhere in this phase mentions playback speed.
7. Nothing in the viewer's half names a product, a protocol, a codec, a bitrate or a status code.
8. The TV player is pixel-unchanged. `check-mobile-css.sh` passes, with the new `.mp-*` classes living in
   a served stylesheet rather than an inline block (the 187 lesson).

## Open questions

1. **Brightness.** There is no brightness seam. Android can set a window attribute; the Wasm build has no
   equivalent at all. Does the web player simply not offer the left-half swipe, or does it map to a CSS
   filter on the surface (which is not the same thing and would fight HDR)?
2. **Haptic strength** on the two devices in the house has not been felt. The design asks for a light
   tick; if the hardware only has one motor strength, is a tick on every skip still wanted?
3. **Auto-hide while paused.** This spec keeps the chrome up when paused. That is a judgement, not a
   measurement — worth revisiting if it reads as clutter on a phone held one-handed.
4. **The Live TV channel swipe** (FR-R244-15) collides with the volume swipe on the right edge. The
   design gives channel-change the *edge* and volume the *half*; whether that is distinguishable under a
   thumb needs a real device.
5. Whether `LocalHandset` is the right gate for the **fold** state of a foldable, where the smallest side
   changes mid-session. Out of scope here, but the gate is the thing that would have to change.
