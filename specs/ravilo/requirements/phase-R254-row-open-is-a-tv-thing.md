# Phase R254 — The row opens on a TV, and only on a TV

> **J** — the focused row growing in place with a panel beside the tile (R240 FR-R240-3) — was
> designed at 1920×1080 for a D-pad. Today a phone and the web app run it too, because nothing anywhere
> asks what kind of device is drawing Home. Owner decision 2026-09-17: *row open in place works on TVs
> only, not on web or mobile.* This phase makes that a rule, in the one place the client already
> decides which direction is really in effect, and has the admin card say so.

## Status

`✓ Built` 2026-09-17 (FR-R254-1/-6/-7: `effectiveFocusDetailMode(…, isTv)`, 8 tests, wasm card + mockup copy; Android + both Wasm targets compile; **not on a device** — FR-R254-4's phone acceptance is owed). Was `Planned` — written 2026-09-17 from the owner's direction and a trace of `main` the same day; not
dev-reviewed, not built. Client-only plus one copy change in the jellystructure Ravilo config card
(`src/wasmJsMain/.../RaviloConfig.kt`) and its design mirror (`design/app/ravilo-config.html`). No
backend, DTO or config change — see FR-R254-2 for why the gate is deliberately *not* server-side.

**Numbering:** verified against `STATUS.md` and the spec directories on 2026-09-17 — Ravilo is taken
through **R253**, admin through **227**. Ravilo-only, no admin pair (the card copy is carried here as
FR-R254-6, the way R190 §D carried its admin facet). Next free: **228 / R255** (R255 is taken the same
day by the sibling backdrop-gradient spec).

Design: nothing new to draw. `design/ravilo/Ravilo Mobile.html` never loads `ravilo-focus.js` — the
phone mockup has never drawn L or J — so the design set is already consistent with this rule; only the
admin card's copy changes.

## Current state (traced against `main`, 2026-09-17)

**The mode is resolved per user, then downgraded per device — but only for one device property.**
Phase 202 FR-202-2 has the server resolve `focusDetail: "none" | "line" | "rowOpen"` from the two
admin booleans, once, per Jellyfin user, and `HomeFeed` carries the string. On the client the only
thing between that string and the screen is `effectiveFocusDetailMode(resolvedMode, reduceMotion)`
(`ravilo-ui/.../focus/FocusDetailReducedMotion.kt`), R240 open question 3's answer: a system
"reduce motion" preference turns `rowOpen` into `line`, nothing else. It is called from exactly two
places — `HomeLoaded` (the reserved band, the reapply effect, L's strip) and `ContentRowItem` (what a
tile's `onFocused` hands the controller) — so those two can never disagree about which mode is live.

**The platform seam exists and Home does not read it.** R234 added `isTvPlatform`
(`ravilo-ui/.../Platform.kt`): on Android the runtime `uiMode == UI_MODE_TYPE_TELEVISION` check
(`RaviloAppContext.isTelevision`, R192), on wasmJs a constant `false` — "ravilo-web is never a TV".
R234 gated *Your profile* on it, R244 gated the phone player chrome on `LocalHandset`, R252 sends it
to the server as `X-Ravilo-Platform: tv | phone | web`. `HomeScreen.kt` mentions none of these; the
focus-detail path is platform-blind end to end.

**What that does on a phone, traced (not device-verified — no device pass this session).** Touch has
no hover, so a tile is focused only by a tap or by a restore. A tap runs `requestFocus()` and then
`onTap` (`FocusModifiers.kt`, R157), so `onFocused` fires, the 170 ms dwell starts, and the detail
page opens before it lands — nothing visible. Coming **back** is different: R139 restores focus to
the exact tile, `onFocused` fires again, nothing cancels the dwell, and J opens on the phone: the tile
grows to 300/210 of its width, the panel is laid out at `PANEL_MIN_WIDTH` (280 dp — the arithmetic
`screenWidth − 2·raviloHPad − grownTile − itemSpacing` goes negative on a 393 dp screen and is
coerced up), R242's backdrop fills the phone, and R250's app bar goes opaque. A Bluetooth keyboard or
game controller on a phone or tablet opens it on every settled move. `LocalHandset` is true in every
one of these cases and `isTvPlatform` is false; nothing consults either.

**On the web** arrow keys are the primary navigation, so every settled focus opens J, at whatever
width the browser window happens to have. Hover does not move focus on tiles (`moveFocusOnHover` is
opt-in and off for lazy-list items), so a mouse user sees J only after a click-then-Back, like the
phone. The owner's decision covers the web regardless of input device.

**The server knows the platform, but not in a way this should lean on.** Phase 224 stores R252's
header on `ravilo_device.platform` and `DeviceData.platform` carries it into every route, so
`buildHomeFeed(device, config)` *could* resolve `rowOpen → line` for `platform != "tv"`. Three facts
argue against it: the home feed is cached **per user only** (`feedCache[userId]`, Phase R86-A/204), so
a per-platform answer would have to be applied after every cache read or the platform would have to
enter the cache key — R233 FR-R233-5's exact trap; `platform` is **null** for any device that has not
spoken since 224 shipped and is a self-reported header from the very client that can gate itself;
and the admin's *"On · superseded"* (FR-202-6) is a per-household statement that would silently become
per-device. Reduced motion already established that a *device* property downgrades on the *client*
while the server's per-user resolution stays untouched. This phase follows that shape.

**The facts payload is not affected either way.** Phones already receive `FocusDetailFacts` on every
Home card (~0.9 KB/title, 202 FR-202-7) and, since the downgrade lands on `line`, still need them.

## Goal

`rowOpen` is a TV direction. On any platform that is not a TV the household's resolved `rowOpen`
renders as `line` — exactly what that household would see with the row-opens switch off — through the
one function that already owns this kind of downgrade, so the two call sites cannot disagree, the
server's per-user resolution is untouched, and the admin card states where each direction applies.

## Functional requirements

**FR-R254-1 — `rowOpen` resolves to `line` on every non-TV platform.** `effectiveFocusDetailMode`
gains a third input, the platform, and applies one rule: `rowOpen` becomes `line` when the platform
is not a TV **or** the system prefers reduced motion; `line` and `none` are returned unchanged in
every case. Both existing call sites (`HomeLoaded`, `ContentRowItem`) pass the same value; no third
site may re-derive it. The platform input is `isTvPlatform` (R234's seam, the runtime hardware check on
Android and the constant `false` on wasmJs) — **never** `LocalCompact` or `LocalHandset`: a phone in
landscape is still a phone, a narrow browser window is still the web app, and a 10-foot UI is a TV at
any window size (R234 FR-R234-1's own reasoning, reused verbatim).

**FR-R254-2 — The gate lives on the client, like reduced motion; the wire is unchanged.** Phase 202
FR-202-2's "the server resolves the mode" is about the supersession of two booleans, and stands:
`HomeFeed.focusDetail` and `RaviloConfig.focusDetail` still carry the household's per-user answer, and
the client still owns no precedence rule between L and J. What the client *does* own is knowledge of
the device it is running on, which is the same footing R240 open question 3 gave reduced motion. The
server-side alternative (resolve per `DeviceData.platform` in `buildHomeFeed`) is recorded in
*Current state* and **rejected** for this phase; if Phase 202 open question 3 (a device-class
capability the server consults) is ever taken up, this rule is one input to it, not a competitor.

**FR-R254-3 — What a phone or the web shows instead is L, exactly as today.** Nothing new is drawn
for the phone or the web: a household with `rowOpen` on sees, on those platforms, what it would see
with `rowOpen` off — the foot strip and its reserved band if `focusDetailLine` is on, nothing if it is
off. L's own fitness on a touch device is deliberately left as open question 1, not decided here.

**FR-R254-4 — Nothing that belongs to J leaks onto a non-TV platform.** Because the downgrade happens
before the controller is fed (`onFocus(…, effectiveMode, …)`), everything keyed on `fdUi.mode ==
"rowOpen"` follows for free and must be confirmed to: the grown tile (`Tile.open`), the panel and its
closing slot, FR-R240-9's vertical scroll, the reserved row band, R242's backdrop
(`FocusDetailBackdrop`), R250's opaque app bar (`AppBar(opaque = …)`), and R250's panel-width clamp.
Acceptance: on a phone with `rowOpen` on for the household, a Back-return to Home (R139's restore)
grows no tile, opens no panel, paints no backdrop and leaves the app bar's translucency as it was.

**FR-R254-5 — A config change still applies live, through the same rule.** FR-R240-13's reapply
effect is keyed on the *effective* mode today and stays so: flipping the row-opens switch in
jellystructure while a phone has a tile focused re-runs the reveal with `line`, and while a TV has one
focused re-runs it with `rowOpen`. No platform ever sees the other's transition.

**FR-R254-6 — The admin card says where each direction applies.** Preferences → **Focus detail**
(`RaviloConfig.kt` `#sect-focus`, mirrored in `design/app/ravilo-config.html`):

- The *Row opens in place* row gains **`TVs only`** — one short clause in the row's own hint text:
  *"TVs only — a phone or the web app shows the status line instead."*
- The line's **`On · superseded`** state (FR-202-6) becomes **`On · superseded on TVs`** while the
  row-opens switch is on, because on every other platform the line is exactly what shows.
- The shipped hint *"ships off — moves the row's height and its tiles' positions on every focus move
  (invariant 11, unmeasured on the living-room BRAVIA)"* is **deleted**: it has been false since
  2026-09-13 (R240's sweep closed invariant 11 and the default flipped to on; 202 FR-202-6 already
  says the card "no longer needs to explain an unmeasured cost"). The design mockup's card carries no
  such text; the wasm card is behind it.

Nothing else on the card changes; the delay's *"Both directions"* readout is still true per
platform.

**FR-R254-7 — The rule is unit-tested where reduced motion is.** `FocusDetailReducedMotionTest`
(or a renamed sibling) covers: `rowOpen` on a non-TV → `line`; `rowOpen` on a TV without reduced
motion → `rowOpen`; `rowOpen` on a TV with reduced motion → `line`; `line` and `none` unchanged on a
non-TV; both flags set → `line`. The function stays pure so no Compose rule is needed.

## Non-goals

- **No per-device config surface.** 202 open question 3 ("should `rowOpen` be per device?") is not
  answered here; this is a platform-class rule, not a setting.
- **No phone-specific focus-detail design.** A touch UI has no "highlighted title"; nothing is drawn.
- **No change to L**, to the payload, to the dwell, or to any R240/R242/R250 mechanic on the TV.
- **No change to `ravilo-tizen`** — it does not implement focus detail at all — nor to the Cast
  receiver (R245), which never renders Home.
- **No server-side platform gate** (FR-R254-2), and no change to `feedCache`'s key.

## Acceptance

1. Pixel 9, household defaults (`line: true`, `rowOpen: true`): open a title from a Home row, press
   Back. The originating tile is focused (R139) and, after the dwell, **L's strip** states it; no
   tile grows, no panel, no backdrop, the app bar is as translucent as before.
2. Same phone, with an external keyboard: arrow across a row — a plain row, then L after the dwell.
3. `ravilo-web`, any window width: arrow across a row — as 2; click a tile and press browser Back —
   as 1.
4. Stue TV, same household: J opens exactly as on `main` today — the rule changes nothing on a TV.
5. Turn the row-opens switch off in jellystructure: the TV's open row narrows out and L appears
   (FR-R240-13); the phone shows no transition at all.
6. Admin card: with `rowOpen` on, the line row reads *On · superseded on TVs*; the row-opens row
   carries *TVs only …*; the "unmeasured on the living-room BRAVIA" text is gone.
7. `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`, `allTests` green;
   `scripts/check-mobile-css.sh` green if the design card's markup is touched.

## Verification

- Unit: FR-R254-7.
- Device: acceptance 1–2 on the **Pixel 9** (the household's test phone), 3 in a browser, 4–5 on the
  stue TV — the TV pass only when the owner schedules it, per the household's own rule.

## Source references

- `ravilo-ui/src/commonMain/.../focus/FocusDetailReducedMotion.kt` — `effectiveFocusDetailMode`, the
  function this phase extends; `commonTest/.../FocusDetailReducedMotionTest.kt`.
- `ravilo-ui/src/commonMain/.../Platform.kt` (`isTvPlatform`), `androidMain/.../PlatformAndroid.kt`,
  `wasmJsMain/.../PlatformWasmJs.kt`, `androidMain/.../RaviloAppContext.kt` (`isTelevision`).
- `ravilo-ui/src/commonMain/.../screens/HomeScreen.kt` — `HomeLoaded` (`effectiveFocusDetail`,
  `lineActive`, the reapply effect, `FocusDetailBackdrop`, `AppBar(opaque = …)`) and `ContentRowItem`
  (`onFocused → store.focusDetail.onFocus(…, effectiveFocusDetail, …)`).
- `ravilo-ui/src/commonMain/.../focus/FocusModifiers.kt` — tap → `requestFocus()` → `onTap`;
  `moveFocusOnHover` opt-in.
- `ravilo-ui/src/commonMain/.../components/FocusDetailPanel.kt` — `PANEL_MIN_WIDTH` and
  `focusDetailPanelWidthFor`, the arithmetic that has no answer on a phone.
- `src/wasmJsMain/kotlin/dev/jellystructure/ui/RaviloConfig.kt` — `#sect-focus`, `focusLineSuperseded`;
  `design/app/ravilo-config.html` — the mirrored card.
- `src/linuxX64Main/.../tv/HomeFeedService.kt` — `feedCache[userId]`, `buildHomeFeed(device, config)`;
  `src/linuxX64Main/.../auth/Models.kt` — `DeviceData.platform` (nullable, Phase 224);
  `shared/.../RaviloVersion.kt` — the `X-Ravilo-Platform` header (R252). Read for FR-R254-2's
  rejection, not touched.
- `specs/requirements/phase-202-focus-detail-config-and-payload.md` — FR-202-2, FR-202-6, open
  question 3. `specs/ravilo/requirements/phase-R240-focus-detail-on-home-rows.md` — FR-R240-4,
  FR-R240-13, open question 3 (the reduced-motion precedent).
  `specs/ravilo/requirements/phase-R234-profile-photo-and-password.md` — FR-R234-1 (platform, not
  screen size).

## Open questions

1. **Should L show on a phone at all?** Touch has no focus; the 88 dp band is reserved for a strip
   that only ever speaks after a tap-then-Back. The web is different — keyboard focus is real there
   and L cannot regress anything. Lean: `none` on a phone, `line` on the web — but the owner asked
   about J, so this is recorded, not decided. If taken, it is the same one-line rule with a third
   outcome.
2. **Android boxes that do not report the TV `uiMode`.** Some set-top boxes run Android with
   `UI_MODE_TYPE_NORMAL`; on such a box this rule would take J away. `isTvPlatform` is the one seam
   for "is this a TV" (R234, R252) and that is where such a device would be fixed — not by loosening
   this rule to screen size.
3. **Should the card count the household's devices per platform?** Phase 224 knows each device's
   platform, so *"applies to your 2 TVs"* is computable. Not worth a query for a hint; left out.
