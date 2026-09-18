# Phase R267 — The phone's navigation: one top row, and the pages at the bottom

> `AppBar` is one row that carries everything — brand, page context, every nav item, cast, search, a
> clock and the avatar — and on a phone it survives only by making that whole row scroll sideways
> (`LocalCompact` drops the spacer and wraps the Row in `horizontalScroll`), so the brand scrolls out
> of view in one direction and the avatar in the other. This phase splits the phone's navigation in
> two: **identity and actions stay in a single top row**, and **the four pages move to a bottom bar**,
> where a thumb is. Handset only — the TV and the web app keep the row they have, clock included.

## Status

`Planned` — written 2026-09-18, **not dev-reviewed**. Client-only (`ravilo-ui`), gated on R256's
`isHandset` seam. **No backend, DTO, config or wire change, and no new string in any language** —
every label rendered (`nav.home`, `nav.movies`, `nav.series`, `nav.discover`) already exists in
en/da/fo.

**Numbering:** written 2026-09-18 as R261, renumbered **R261 → R264** the same day (`main` took R260 ·
R261 · R262 within hours), and renumbered again **R264 → R267** the same afternoon when `main` took
R263 · R264 · R265 (the web app installs · the receiver-only TV app · play on a TV from the phone).
Verified against `main` on 2026-09-18 — Ravilo taken through **R265**, admin through **236**.
Ravilo-only, no admin pair. Next free: **238 / R269** (R266 is Cast Connect and R268 the Discover tab
order — both ours, same day).

Design: `design/ravilo/Bottom Nav - Directions.html` — the baseline, four directions on Pixel 9
frames, the comparison, and the three frames where a bottom bar meets the rest of the app (the cast
mini bar, Discover, the player). **Direction B · "pill" was chosen** (owner, 2026-09-18) and is built
into `design/ravilo/Ravilo Mobile.html` (`.bnav` / `.bn` / `.bnind`).

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
