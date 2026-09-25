# Phase R267 — The phone's navigation: one top row, and the pages at the bottom

> `AppBar` is one row that carries everything — brand, page context, every nav item, cast, search, a
> clock and the avatar — and on a phone it survives only by making that whole row scroll sideways
> (`LocalCompact` drops the spacer and wraps the Row in `horizontalScroll`), so the brand scrolls out
> of view in one direction and the avatar in the other. This phase splits the phone's navigation in
> two: **identity and actions stay in a single top row**, and **the four pages move to a bottom bar**,
> where a thumb is. Handset only — the TV and the web app keep the row they have, clock included.

## Status

`✓ Built` — design-authored 2026-09-18, dev-reviewed 2026-09-19 against `main` `dcb97f2c`, **built
2026-09-20 and verified on a real Pixel 9** (debug build). **Finished 2026-09-25**: FR-R267-9's
scroll-to-top and Search's top row built and verified on the Pixel 9; the other two owner items were
closed by R304 and R274 (see *Finished (2026-09-25)* below).

⚠ **Two of this phase's own Status claims were false**, exactly as the dev review found, and both are
now corrected: it needed **backend changes** (FR-R267-5c) and it needed **new strings**.

### Build (2026-09-20)

- **FR-R267-1/-2/-3/-4/-13** — `AppBar` gains a `LocalHandset` branch: brand · the page's own control
  · cast, and nothing else. No clock (guarded inside `ClockDisplay` too, so a future call site cannot
  reintroduce a second one 40 px under the platform's). **No horizontal scroll**, which was the whole
  defect: `LocalCompact` "fixed" a crowded bar by letting the brand, the cast button and the avatar
  slide off-screen at rest. The wide path is byte-for-byte what shipped.
- **FR-R267-5/-6/-6a/-7/-10/-12** — `RaviloBottomNav`: five items, one gradient pill that **slides**
  (~180 ms) rather than five states cross-fading, 11.5 sp labels (the single fenced exception to the
  13 sp floor), opaque with a hairline, and `RaviloDimens.bottomNavHeight` as the one place anything
  offsets by. Drawn as a child of the root `Box`, **outside `AnimatedContent`** per review item 4 — a
  bar that animated in and out with every content transition is the two-bars-mid-slide problem R262
  FR-R262-3 exists to prevent.
- **FR-R267-5b** — review item 5's claim held: `Dest.Browse(kind)` really is one screen with a type
  parameter, so Library is that screen with its parameter exposed. My List keeps the old path (it is a
  pushed destination from the profile menu, not a Library type).
- **FR-R267-5c, and the backend the Status said it did not need** (review item 1). `BrowseKind.MUSIC`
  + a real `musicvideo` slice in `BrowseService` — which also closes a hazard of its own: **any kind
  other than `movie`/`series` used to fall through to `all`**, so a music browse would have rendered
  the whole library's counts rather than an error. `BrowseFacets.kind_counts` carries all four counts
  in **one** response, because `facets(kind)` answers for one slice per call and the client may not sum
  them itself.
- **FR-R267-5d** — Profile opens the existing menu and **never takes the pill**; re-tapping closes it.
  Verified on device.
- **New strings** (review item 2): `nav.library`, `nav.profile`, `lib.type.all`, `lib.type.music`,
  `lib.count`, `lib.empty_music` × en/da/fo. Danish and Faroese are drafts.

### Found by running it on a Pixel 9 — three real bugs, all fixed

1. **Switching the Library type left the page on "Loading…" forever.** Each type gets its own store,
   but R262's `contentKey = "section"` deliberately keeps Home/Browse/Discover as one entry in the
   content transition, so the screen is **not** recomposed when only the type changes and
   `LaunchedEffect(Unit) { store.load() }` never re-ran. Keyed on the **store** now, which is also just
   the honest statement of what the effect is for.
2. **A D-pad focus ring was drawn around the first poster on a touch screen.** R257 hands focus to the
   first cell so a D-pad has somewhere to land; a phone has no D-pad and the viewer cannot clear it.
   Suppressed on handsets, along with the entry focus request into a nav row that no longer exists
   there. FR-R267-6's "selection may never be carried by focus" now holds in fact, not only in intent.
3. **The type dropdown rendered full-width across the top of the screen** instead of under its own
   pill — the anchor box had stretched across the row. Anchored and given a real width.

### Verified on the Pixel 9

Home → Library → type dropdown → Movies (491 → 270 titles) → Search (field focused, keyboard up) →
Discover → Profile (sheet opens, **pill stays on Discover**) → re-tap Profile (closes) → Home → Back
(exits the app, correct for a root destination). The pill slides; the top row's slot is present on
Library and **empty everywhere else**. Discover also confirmed **R268** on device: it opens on
*Networks* with the strip scrolling.

### Finished (2026-09-25)

- **FR-R267-9 — re-tapping the page you are on scrolls it to its top.** One counter in `RaviloApp`
  (`reselectTick`), bumped when the bar's item for the page already on screen is tapped, read by each
  page through one helper, `OnReselect`, which fires only when the counter changes **after the page
  composed**. That rule is the point: a counter compared against zero (R304's first form on Profile,
  now replaced) fires again when Back returns to a page that was re-tapped before it was left, and
  scrolls R295's restored position straight back to the top. Per page: Home scrolls the store-held
  list (R137); Library scrolls its grid and closes the type menu, keeping the type; Discover returns to
  the first available segment and scrolls it to the top (the scroll matters when the viewer is
  already on that segment, where the destination does not change at all); Search puts the caret back
  with the keyboard up and scrolls the results to the top, keeping the query; Profile scrolls to top.
  **Found on the Pixel 9:** Search's first build raised the keyboard and left the grid where it was —
  consuming the re-tap flag restarts the effect that was scrolling, which cancelled it. The scroll now
  runs in the screen's own scope.
- **Search's top row** (FR-R267-2; R277 FR-R277-4 left it open as this phase's item): brand · cast, as
  on every other page and as the mockup's persistent row draws it. The handset-only 16 dp top padding
  R277 used while there was no row is the ordinary `appBarHeight + 24 dp` again. TV unchanged.
- **The profile menu** — superseded by R304: Profile is a page on a phone, with no menu to anchor.
- **The bar over the keyboard** — decided and built by R274: the keyboard covers the bar.

**Verified on the Pixel 9 Pro (debug, 2026-09-25):** Home, Library, Discover (from Networks and from
Genres), Search and Profile each scrolled down and re-tapped → top (Search confirmed by a scroll-up
that moved nothing); Library's type menu open → Library tapped → closed, type kept; re-tap Home, scroll,
open a title, Back → Home comes back where it was, not at the top.

### Not built at the time (2026-09-20), kept as the record

- **FR-R267-9's scroll-to-top on re-tap** is NOT implemented. The navigation half is (Discover
  re-tap returns to the first available segment — written as *first available*, per review item 7, so
  it and R268's order cannot disagree). Scrolling a page to its top needs each of the four screens to
  expose its scroll state, which is a bigger diff than the rest of this phase put together and is
  better done as its own change than bolted on at the end of this one.
- **The profile menu is still anchored where the TV's avatar used to be** (upper right), while its
  trigger is now bottom-right. It should become a bottom sheet. It also draws a D-pad focus ring on
  its first item — same class as bug 2 above, different component.
- **The Search page has no top row at all** (no brand, no cast) — that screen never called `AppBar`.
  FR-R267-2 implies it should.
- **The bottom bar rides above the software keyboard on Search**, so the bar stays reachable but the
  results area shrinks to about three tiles. Platform-typical would be to let the keyboard cover it.
  An owner call, not a defect.
- Open questions 2 (predictive back), 3 (screen-level title) and 4 (brand lockup width in da/fo)
  remain open; none blocked the build.

## Current state (traced against `main`, 2026-09-18)

`AppBar` (`ravilo-ui/…/components/AppBar.kt`) is a single `Row` inside a `Box` of
`RaviloDimens.appBarHeight`: the brand lockup (`BrandMark(26.dp)` + *Ravilo* at 22 sp Space Grotesk),
R136's optional `· {title}`, every item of `raviloNavItems()` (**Home · Movies · Series · Discover** —
`NavItems.kt:39`), a `weight(1f)` spacer **only when not compact**, then `CastButton()`,
`SearchIcon()`, `ClockDisplay()` and `ProfileAvatar()`.

**What compact mode does, and why it is not enough.** `LocalCompact` (width < 600 dp) removes the
spacer and wraps the whole Row in `horizontalScroll`, because a scrollable Row has unbounded width and
cannot host a weighted spacer. On a 393–411 dp phone:

- The **brand scrolls away**, and so does the avatar in the other direction: nothing is pinned, so the
  two controls a viewer reaches for most have no fixed place.
- **Cast, search and the avatar sit behind a swipe.** The cast button is the one control in Ravilo
  whose meaning comes from being recognised (R245 FR-R245-1), and it is off-screen at rest.
- The **clock takes a slot** the nav needs, on the one device that already draws a clock in its own
  status bar one row above ours.
- Nav and actions share one scroll offset, so scrolling to *Discover* pushes the avatar further away.
- The active tab's only signals are `colors.text` + SemiBold; the strong signal (the inverted pill) is
  bound to **focus**, and a touch device has no focus (R254's own finding).
- The pages sit at the **far end of the thumb's reach**, ~800 px up a 915 px screen, and cost a second
  row of chrome that then collides visually with Discover's own five-chip strip (R262 §3, R268).

`LocalCompact` is also the wrong seam: it is width-only, so a narrow *browser window* gets the phone's
layout. R256 settled the rule when a 540 dp-short-side TV was served the phone's player — **form
factor first, size second** — and left `isHandset(isTv, w, h, density)` in `Platform.kt` as the one
seam for "this is a phone". `LocalCompact` stays correct for what it is and is untouched here.

## Goal

On a handset: one top row that pins identity and actions and never scrolls, and a bottom bar that
carries the four pages and marks the current one unmistakably. On every other platform the bar is
exactly what it is today.

## Functional requirements

**FR-R267-1 — Handset only.** When `LocalHandset` is true (R256 FR-R256-1: not a TV, short side under
600 dp) the phone layout of FR-R267-2/5 applies. When it is false — TV, tablet, and the web app at any
window width — the single row ships unchanged, including its compact horizontal scroll. A
platform-class rule on the client, the shape R254 FR-R254-2 and R256 established; the server is not
asked and no config decides it.

**FR-R267-2 — The top row: the brand, the page's own control if it has one, then cast.** Left: the
brand lockup (`BrandMark` + the *Ravilo* wordmark, ink-coloured — not accent, not a gradient fill).
Right: **the Library type dropdown when the Library page is showing** (FR-R267-5c), then **the cast
button**. Nothing else: search and the profile are bottom items (FR-R267-5), there is no page-context
text (R136) and **no clock** (FR-R267-3). This is TV2 Play's *Kategorier* position, with the rule it
is missing: the slot belongs to **one page**, is empty on every other, and never becomes a home for
controls that have nowhere else to live.

**FR-R267-3 — No clock on a phone.** `ClockDisplay()` renders only when `LocalHandset` is false. The
platform draws the time in the status bar directly above ours, and a second clock 40 px below the
first is furniture. TV and web keep it exactly as today — on a TV it is the only clock in the room.

**FR-R267-4 — Cast is present or absent, never a gap.** R245 FR-R245-1 unchanged: `CastButton()`
renders nothing when Chromecast is not set up or the platform has no sender, and the row's spacing
closes up around it. Nothing greyed, nothing reserved.

**FR-R267-5 — The pages are a bottom bar of exactly five.** **Home · Library · Search · Discover ·
Profile** — Home on the left because it is where the app opens and where Back ends up, **Search in the
literal centre** because it is the item reached without looking and the one a viewer goes to from any
other page, and **Profile bottom-right, drawn as the profile picture itself** (TV2 Play's *Min side*
idiom). Five is what fills the bar without crowding it: a centre slot needs an odd count, and three
read as empty on a 915 px screen. Each item is an icon over a one-word label in a **≥ 46 dp** target;
the selected pill is 56 dp so it sits inside its own item.

**FR-R267-5a — Search is a page on the phone, not an icon.** R52 put search in the TV's right cluster
as a magnifier because a 10-foot UI has no keyboard and a D-pad reaches the cluster in two presses.
A phone has a keyboard and a thumb, so search is one of the places a viewer goes — it becomes the
middle bottom item and **leaves the top row entirely** (FR-R267-2). The screen is the field at the top
of the page with results beneath it; the field takes focus on arrival, and the query survives
navigating away and back within a session (R259 FR-R259-1's finding, applied from the start here).
R52 is unchanged on the TV and the web app.

**FR-R267-5b — Movies and Series are one page on a phone, called Library.** The two type browses merge
into a single destination. This is a presentation change, not a new screen: `Dest.Browse(kind)` is
already one screen with a type parameter, so Library is that screen with its parameter exposed as a
control (FR-R267-5c) instead of as two nav items. Its rows, grid, facets and See-all behaviour are
unchanged (R187). **`raviloNavItems()` is not rewritten** — the phone *composes* from that list, it does
not redefine it; the TV and the web app keep Home · Movies · Series · Discover exactly as they have
them. Discover keeps its own five segments (R243/R262/R268) and gains nothing from this merge.

**FR-R267-5c — The Library page's type filter is one dropdown in the top row, left of cast, and only
while Library is showing.** The pill carries the current type and opens a short menu anchored under
itself: **All · Movies · Series · Music**, each with its count for this profile, the current one
ticked; a tap anywhere outside closes it. The page below carries a one-line count of what is shown.
The slot is **empty on Home, Search, Discover and every pushed screen** — a filter must not sit in the
chrome of a page it does not apply to, which is the whole difference between this and a permanent
*Kategorier* button. Music videos are a real library type (phase 172) and the option is offered **even
when the household holds none** — it reads `0` and the page says *Nothing filed as music yet*; hiding a
filter the admin can fill next week is worse than an honest empty state. The pill reads the type's own
word, never "Filter" or "Kategorier", and the choice does not persist across app restarts (it is a
browse control, not a setting).

**FR-R267-5d — Profile is a menu, not a page.** The bottom-right item opens the existing profile sheet
(*Your profile · My List · Settings · Sign out* — R170's hub, R234's account screens) and **never takes
the selected pill**: the pill stays on the page underneath, because nothing about which page you are
on has changed. Its icon is the viewer's avatar — the photo when there is one, initials when there is
not (187/R234) — at 24 dp inside the same 46 dp target as every other item.

**FR-R267-6 — The selected page is marked by a shape, and the shape moves.** The selected item's icon
sits inside a filled pill in the brand gradient with its label in full ink; the others are muted icon
+ label. The pill is **one element that slides** between items (~180 ms, the app's own easing), so the
bar reads as a single object rather than four independent states. On a phone the selection may never
be carried by focus, by hover, or by two shades of grey alone — touch has none of the first two, and
the third is what direction A was rejected for. Pressed state is a ripple/ground change on the item,
distinguishable from selected.

**FR-R267-6a — The label floor bends here, and only here.** The five labels are **11.5 sp**, below the
13 sp floor R234/R244 set for body copy: they are permanent, icon-paired, one-word labels in the
platform's own idiom, and holding 13 sp would make the bar taller than a platform bar for no gain in
legibility. This is the **only** exception in the phone design; it is not a precedent for content copy.

**FR-R267-7 — Opaque, and only over the four pages.** The bar uses the page surface with a hairline
top edge — not translucent, not floating (direction C was rejected: a blurred capsule puts ink over a
scrolling poster wall, the trap R245's remote directions already rejected, and it leaves nothing for
the mini bar to dock against). Content's bottom padding is the bar's height, so nothing scrolls under
it and nothing is clipped by it. The bar is **absent** on every pushed destination — detail pages, the
player, the cast remote, the profile picker, account screens and seeded grids — each of which has its
own Back and sits on a higher layer. It never overlays video.

**FR-R267-8 — The cast mini bar docks on top of it.** R245 FR-R245-6's 64 dp mini bar sits directly
above the nav bar; the pair moves as one block, the mini bar keeps its tap target and its rule (never
dismissible while a cast runs), and the content's bottom padding is the sum of the two. On a pushed
screen where the nav is absent the mini bar returns to its own inset, as today.

**FR-R267-9 — Re-tapping the page you are already on.** Always scrolls that page's content back to its
top — the platform behaviour on every phone bottom bar — and two pages do one more thing on top of
that:

| Re-tap | What happens |
|---|---|
| Home | Scroll to the top of the page. |
| **Library** | Scroll to the top **and** close the type dropdown if it is open. The type itself is not reset — a viewer who filtered to *Series* and scrolled down wants the top of *Series*. |
| **Discover** | Scroll to the top **and** return to the **first available segment** (R268's order, so **Networks** on every household — a taxonomy tab cannot be gated off). *This replaces R170's step-to-the-next-segment on the phone; R170 stands on the TV, where the nav item is reached by D-pad and stepping is the cheaper gesture.* |
| **Search** | Scroll to the top **and** put the caret back in the search field, keyboard up. The query is not cleared — re-tapping is how a viewer gets back to editing what they typed, not how they start over. |
| **Profile** | Opens the sheet (FR-R267-5d). Re-tapping while it is open closes it, as tapping the scrim does. |

*(An earlier round put Movies and Series into Discover's strip and named Studios, then Movies, as its
first chip. With the merge of FR-R267-5b they leave Discover again, so the first available segment is
**Networks** as R268 declares it — and the rule stays written as "first available" so order and re-tap
cannot disagree.)*

**FR-R267-10 — Always visible on its pages.** No hide-on-scroll: it is the one affordance that says
where you are, and a bar that disappears while you read a row is a bar you have to go looking for.

**FR-R267-11 — Discover's own chips stay at the top of Discover.** The five segments (R243/R262/R268:
Networks · Studios · Genres · Coming Soon · Request, scrollable) are that page's content, not app
navigation — and neither is Library's type dropdown (FR-R267-5c). With the app's pages at the bottom
there is exactly one chip row on screen, which is what removes the two-stacked-strips ambiguity this
phase was partly written for.

**FR-R267-12 — Insets and geometry from one place.** The top row sits below the status-bar inset and
the bottom bar above the navigation-bar/home-indicator inset (`WindowInsets.safeDrawing`, R244's
safe-area work), including an iOS Dynamic Island and gesture pill. `RaviloDimens` gains the bar's
height, and anything that offsets content by it — a page's bottom padding, the mini bar's offset —
reads the resolved value from that one place. A phone value wrong by one bar is how a heading ends up
under a bar (R257 FR-R257-5 / R259 FR-R259-2, twice).

**FR-R267-13 — One AppBar, one NavBar, no forks.** The top row stays inside `AppBar` behind the
`LocalHandset` branch, sharing `BrandMark`, `CastButton`, `SearchIcon` and `ProfileAvatar` with the
single-row path; the bottom bar is one new composable placed by the app's scaffold for the four page
destinations only. Every screen that calls `AppBar` today keeps its call site, and `navFR` / `onDown` /
`activeNav` / `onNavSelect` keep their contracts (the D-pad inputs are simply never exercised on a
handset; `onNavSelect` is what the bottom bar calls).

## Non-goals

- **Any change on the TV or the web app.** The single row, its clock, its focus inversion, R62's
  scroll-driven background and R250's `opaque` flag all stand.
- **New pages, or a different page list.** Four, as `raviloNavItems()` defines them.
- **New strings**, and no icon set beyond the four glyphs the bar needs.
- **Hiding the bar on scroll** (FR-R267-10), **a floating bar** (FR-R267-7), **a fifth item or a
  scrolling bar** (FR-R267-5).
- **A bottom bar on a tablet**, or on the web at any width (FR-R267-1).
- **`LocalCompact`'s own behaviour.**

## Acceptance

1. Pixel 9, portrait: the top row has the brand at the left edge and the avatar at the right edge **at
   rest**, with cast (Chromecast set up) and search between; no swipe reaches any of them, and no clock
   is drawn anywhere in the app's chrome.
2. The bottom bar shows five items — *Home · Library · Search · Discover · Profile* — with Search
   centred and the viewer's avatar as the Profile icon; the selected one carries the gradient pill and
   an ink label; tapping *Library* slides the pill onto it in ~180 ms and swaps the content.
2a. Tapping *Profile* opens the profile sheet and the pill **does not move** off the page underneath.
2b. Library: the top row shows the type pill left of the cast button reading *All*, and the page a
   line with the count; opening the pill lists All · Movies · Series · Music with per-type counts and a
   tick on the current one; picking *Series* filters the rows, the count and the pill's own label;
   picking *Music* on a household with none shows `0` and *Nothing filed as music yet*. Leaving Library
   removes the pill from the row entirely.
3. Chromecast **not** set up: the cast button is absent and the top row is the brand alone; the bottom
   bar is unchanged.
4. Casting: the mini bar sits directly above the bottom bar, both visible, neither overlapping, and the
   last poster row is fully scrollable above them.
5. Open a title from Home: the bottom bar is **gone** on the detail page and in the player, and returns
   on Back. Nothing draws over video.
6. Tap *Home* while on Home scrolled down: the page scrolls to the top. Tap *Discover* while on
   Discover's *Genres*: it scrolls to the top **and** lands on *Networks*. Tap *Search* while on
   Search: it scrolls to the top, the caret is in the field, the typed query is still there. Tap
   *Library* with its dropdown open: the dropdown closes, the type is kept.
7. Discover: exactly one chip row on screen — its five segments, scrollable, *Networks* first — with
   the bottom bar below it.
8. iPhone 16: the top row clears the Dynamic Island, the bottom bar clears the home indicator, and
   nothing in either is under an inset.
9. `ravilo-web` in a 380 px-wide window: **the single top row**, clock included, no bottom bar — narrow
   is not a phone. Stue TV: unchanged from `main`.
10. `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`, `allTests` green;
    `isHandset`'s tests (R256 FR-R256-3) unchanged.

## Source references

- `ravilo-ui/src/commonMain/…/components/AppBar.kt` — the single row this phase branches;
  `…/screens/NavItems.kt:39` — `raviloNavItems()`; `…/Platform.kt` — `isHandset` / `isTvPlatform`;
  `…/theme/Dimens.kt` — `appBarHeight`, `raviloHPad`.
- `design/ravilo/Bottom Nav - Directions.html` — the four directions, the comparison and the three
  consequence frames; `design/ravilo/Ravilo Mobile.html` — `.bnav` / `.bn` / `.bnind`, the built pick.
- **R256** (form factor first, size second) · **R254** (a platform-class rule lives on the client) ·
  **R245** FR-R245-1/6 (the cast button's presence rule; the mini bar) · **R244/R234** (safe areas,
  46 dp targets, the 13 sp floor FR-R267-6a bends) · **R170** (My List in the avatar menu; the Discover
  merge and its step-to-next-segment) · **R52** (search is an icon) · **R136** (the page context this
  phase drops on a phone) · **R262/R268** (Discover's one frame and its five scrollable segments) ·
  **R62 / R250** FR-R250-3 (the TV's bar backgrounds) · **R257** FR-R257-5 / **R259** FR-R259-2 (why
  geometry comes from one place).

## Decisions taken (owner, 2026-09-18)

1. **Direction B · pill**, over A (ink + weight: too weak without focus or hover), C (floating capsule:
   ink over pictures, and nothing for the mini bar to dock against) and D (one label: *Discover* is not
   inferable from a glyph). *Decide-for-me.*
2. **The 13 sp floor bends to 11.5 sp** for these labels only (FR-R267-6a). *Decide-for-me.*
3. **Tap-on-active scrolls to top**, with the per-page additions of FR-R267-9.
4. **The pill slides** between items (FR-R267-6); **the brand stays** in the top row; **handset only**
   per R256 (FR-R267-1); **the mini bar docks above the bar** (FR-R267-8); **no hide-on-scroll**
   (FR-R267-10).
5. **Five items, with Search in the centre and Home on the left** — three read as too empty. The
   composition went through two rejected rounds, recorded because the reasoning is the useful part:
   *Home · Movies · Search · Series · Discover* split a pair with an unrelated control, and
   *Home · Search · Discover* solved that by emptying the bar. The answer was to merge the pair
   (FR-R267-5b) and fill the fifth slot with the profile.
6. **TV2 Play is the reference for the shape**, and the two changes from it are deliberate: the avatar
   comes **out** of the top row into the bar's last slot (FR-R267-5/5d) rather than sitting in both
   places, and the *Kategorier*-style dropdown is **page-scoped** — present in the row only while
   Library is showing (FR-R267-2/5c), never a permanent button.
7. **Music is in the Library dropdown** even where the household has none (FR-R267-5c).

## Open questions — for the dev team

1. **Where the bottom bar is placed in the composition.** It belongs to the four page destinations, so
   either the app scaffold draws it for `Home`/`Browse`/`Discover` destinations, or each of those four
   screens draws it. Design's lean: **the scaffold**, so the bar cannot animate in and out with a
   content transition (R262 FR-R262-3's "no slide between sections" applies to it too).
2. **Predictive back and the bar.** Android's predictive-back preview will show the previous page
   *behind* the bar; confirm the bar does not double-draw during the gesture.
3. **Where does a screen-level title go** now that FR-R267-2 drops `· {title}`? `ChannelScreen` is the
   live case (R136). Lean: the screen's own first row, as browse and taxonomy already do.
4. **Does the brand lockup need to shrink?** 26 dp mark + 22 sp wordmark is a TV measurement; beside
   three controls on a 393 dp screen it fits but is tight. If it does not in da/fo, shrink the
   wordmark, never the targets.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

The diagnosis of `AppBar`'s compact mode is exact, the seam is the right one, and the load-bearing
structural claim holds. **But the Status section describes a smaller phase than the requirements do:**
it was written for the four-item bar and not updated when the composition changed to five, and two of
its headline claims are now false. Nothing here challenges the design; it changes what the phase costs.

1. **"No backend, DTO, config or wire change" is false — FR-R267-5c needs three.** (a) `BrowseKind` is
   `ALL, MOVIES, SERIES, MY_LIST` (`BrowseScreen.kt:65-67`) and `BrowseService.browse`'s own contract is
   *"kind: `movie` | `series` | `mylist` | null (all)"* (`:130`), mapped at `:150-151`. **There is no
   music-video browse kind on the Ravilo path at all**, although `MediaKind.MUSIC_VIDEO` exists in the
   shared model and is filtered in one unrelated place (`BrowseService.kt:79`). (b) `facets()` caches and
   slices exactly three ways — `all` / `movie` / `series` (`:244-251`) — so there is no music slice, and
   **any other `kind` silently falls through to `all`**, which would render a wrong count rather than an
   error. (c) The dropdown shows **four counts at once**, and `facets(kind)` returns one slice per call:
   four counts means four round trips or a new field on the response, and the client may not sum them
   itself (render-never-compute). Fix the Status line, and decide explicitly whether Music ships in v1 —
   FR-R267-5c's argument for offering an empty Music is good, and it is also the single most expensive
   item in the phase.
2. **"No new string in any language" contradicts FR-R267-5.** The Status and the Non-goals both claim
   zero, justified by "*every label rendered (`nav.home`, `nav.movies`, `nav.series`, `nav.discover`)
   already exists*" — a list written for the four-item bar. FR-R267-5's bar is **Home · Library · Search
   · Discover · Profile**, and FR-R267-5c adds *All*, *Music*, a per-type count line and *Nothing filed
   as music yet*. Measured against the shipped table: `nav.search` **does** exist (and `nav.my_list`,
   `nav.settings`); `nav.library`, a Profile label, *All*, *Music*, the count line and the empty state do
   **not**. That is about six new keys × en/da/fo, which is also da/fo draft work. The Decisions section
   records that the composition went through two rejected rounds — the Status simply did not follow it.
3. **Acceptance 1 contradicts FR-R267-2, from the same revision.** It asserts "the brand at the left edge
   and **the avatar at the right edge** at rest, with cast … **and search** between", while FR-R267-2
   says the top row is brand, the Library dropdown and cast, *nothing else*, because search and profile
   became bottom items. Rewrite acceptance 1 against FR-R267-2; acceptance 2/2a/2b already test the new
   composition, so it is only this one line.
4. **Open question 1: there is no scaffold to place the bar in.** `AppBar(` is called from **13 sites
   across 9 files** — every screen draws its own. So "the app scaffold draws it" is **new structure**, not
   a choice of where to put something in existing structure: a wrapper around `RaviloApp`'s `when (dest)`
   branch. The lean is still right (a bar that animates in and out with a content transition is exactly
   R262 FR-R262-3's problem), but FR-R267-13's "every screen that calls `AppBar` today keeps its call
   site" hides a consequence: if the bar is drawn outside the screens, each of the four still has to
   leave FR-R267-7's bottom padding, so this touches four screens rather than none. Say that, or the
   first build will clip a poster row.
5. **FR-R267-5b holds, and it is the claim the whole five-item composition rests on.** `Dest.Browse(val
   kind: BrowseKind, val displayName: String)` (`RaviloApp.kt:194`) really is one screen with a type
   parameter, so Library is that screen with its parameter exposed as a control. Confirmed rather than
   corrected — worth stating, because if it had been two screens the merge would have been a rewrite.
6. **FR-R267-1's seam is in place.** `isHandset(isTv, widthPx, heightPx, density)` (`Platform.kt:23`) and
   `LocalHandset` (`Dimens.kt:24`) both shipped with R256, so acceptance 9 — a 380 px `ravilo-web` window
   keeps the single row and its clock — is enforceable the day this is built, with no new seam.
7. **Build order: R268 before R267, or FR-R267-9 must stop naming a segment.** R262 is `✓ Built`, so
   FR-R267-11's "exactly one chip row" premise is live. **R268 is not built**, so today the first
   Discover segment is R243's order, not R268's — and FR-R267-9's re-tap table says the phone lands on
   *Networks*. The parenthetical already gets this right ("the rule stays written as *first available* so
   order and re-tap cannot disagree"); the table above it does not. Either land R268 first or let the
   table say *first available* too, and leave the naming to R268.
8. **Smaller, all fine.** FR-R267-3's clock removal is a `LocalHandset` branch around the existing
   `ClockDisplay()`; FR-R267-4 restates R245 FR-R245-1 unchanged; FR-R267-12's one-place geometry is the
   right lesson from R257 FR-R257-5 and R259 FR-R259-2; and FR-R267-6a's 11.5 sp exception is argued
   properly and fenced to five labels. Open questions 2 (predictive back), 3 (screen-level title) and 4
   (brand lockup width in da/fo) are all genuinely device questions and none blocks the build.
