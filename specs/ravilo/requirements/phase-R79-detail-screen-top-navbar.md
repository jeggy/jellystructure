# Phase R79 — Top navbar on Movie / Series detail screens

> Bring the Ravilo `AppBar` (Home · Movies · Series · Top 10 · My List + search/avatar) onto the
> Movie and Series detail screens, where it is currently absent, so the viewer can jump to a top-level
> destination without first pressing Back.

## Problem
On Home, Browse and Discover the top navbar is always present (overlaid on the content). The moment
the viewer opens a Movie or Series detail page the navbar vanishes — those screens render no bar at
all. The only way out is Back. This is inconsistent with every other browse-type screen and makes the
detail page feel like a dead end on the 10-foot UI.

## Goal
Render the same `AppBar` overlay on `MovieDetailScreen` and `SeriesDetailScreen`, matching the
established per-screen overlay pattern, with a correct TV D-pad focus round-trip (UP from the hero
actions reaches the bar; the bar's DOWN returns to Play) and without changing how Back behaves.

## Current state (as-is)
- **`AppBar` is the shared navbar.** `ravilo-ui/.../components/AppBar.kt`:
  ```kotlin
  @Composable fun AppBar(
      navItems: List<String>? = null, activeNav: Int = 0, onNavSelect: (Int) -> Unit = {},
      navFR: FocusRequester = remember { FocusRequester() }, onDown: () -> Unit = {},
      userInitials: String = "", onProfile: (() -> Unit)? = null, onSearch: (() -> Unit)? = null,
      scrolled: Boolean = false, modifier: Modifier = Modifier,
  )
  ```
  Default tabs `Home · Movies · Series · My List`; callers inject the gated `Top 10` tab via
  `navItems`. Right cluster: search icon · clock · avatar. It is a fixed-height
  (`RaviloDimens.appBarHeight = 60.dp`) `fillMaxWidth` **overlay** — it does not occupy layout space.
  `navFR` is bound to the active tab's slot (R67); `onDown` bridges into screen content;
  `scrolled` drives the transparent→solid background (R62).
- **No global scaffold — every screen overlays its own `AppBar`.** Pattern is
  `Box { <scrolling content with top padding>; AppBar(...) }`:
  - `HomeScreen.kt` — AppBar overlay at the end of the root `Box`; content uses a bring-into-view spec
    with `topInsetDp = appBarHeight + 34.dp`; focus bridges `navBarFR`/`heroFR` (UP from hero →
    `navBarFR`, AppBar `onDown` → hero).
  - `BrowseScreen.kt`, `DiscoverScreen.kt` — same overlay + top-padding pattern.
  - `ChannelScreen.kt` uses a lighter `ChannelBar` variant instead.
- **`RaviloApp.kt` is the navigation host but NOT a navbar host.** Dispatch is
  `AnimatedContent { when (dest) { … } }` inside a root `Box` whose `onKeyEvent` pops the stack on
  `Key.Back`/`Escape`/`Backspace`. The `when` only *selects* a screen; each screen decides whether to
  draw an `AppBar`. The detail branches pass only `onBack` / `onPlay` / `onRelatedSelect` — **no nav
  callbacks** (`onNavSelect`, `onProfile`, `onSearch`, `discoverAvailable`, `displayName`), so the
  detail screens currently have no way to render or wire a bar.
- **`discoverAvailable` is already hoisted to `RaviloApp` state** and threaded into Browse/Discover.
- **Detail screen shape.** `MovieDetailScreen.kt` / `SeriesDetailScreen.kt`: root
  `Box(fillMaxSize, background) { when(state){ Loading / Error / Loaded } }`. The `Loaded` body is a
  non-lazy `Column(verticalScroll)` whose first child is a **full-bleed hero `Box`** (height = full
  container) with backdrop, gradient and a bottom-start text/actions column (title · meta · audio
  flags · synopsis · `Play/Resume` + `+ My List`). Entry focus is `playFR.requestFocus()`. The actions
  `Row` has an `onFocusChanged` that snaps the page to top (R72) to reframe the hero. Bring-into-view
  spec here is `rememberEdgeBringIntoViewSpec(peekDp = 60.dp, topInsetDp = 20.dp)`.
- **Back on detail is single-stage.** Detail screens deliberately do **not** use `backToTopOnBack`
  (`BackToTop.kt` documents this); Back pops straight to the previous screen via `RaviloApp`'s root
  handler.
- **No platform variants.** `AppBar` and both detail screens are single-source `commonMain` — no
  android/wasmJs actuals to mirror.

## Requirements

### A. Thread nav callbacks into the detail screens
1. Add `displayName`, `onNavSelect: (Int) -> Unit`, `onProfile`, `onSearch`, and `discoverAvailable:
   Boolean` parameters to `MovieDetailScreen` and `SeriesDetailScreen`, and wire them from the
   `Dest.MovieDetail` / `Dest.SeriesDetail` branches in `RaviloApp.kt`.
2. **Nav-tab semantics must match Browse**, not Home. From a detail screen (which is pushed on top of
   some prior screen), selecting a top-level tab should reset to that destination rather than stack a
   new screen on top of the current stack. Reuse Browse's logic (`RaviloApp.kt` ≈ lines 320–328):
   `0 → stack = listOf(Dest.Home(name))`; `1/2 → Browse(MOVIES/SERIES)`; `3 → Discover` if
   `discoverAvailable` else `Browse(MY_LIST)`; `4 → Browse(MY_LIST)`. Pass `discoverAvailable` so the
   `Top 10` tab is gated identically.
3. `onProfile → push(Dest.ProfilePicker)`; `onSearch → push(Dest.Search(displayName))`; avatar via the
   existing `LocalUserAvatarUrl` (same as Browse/Home).

### B. Render the AppBar overlay on detail
4. In each detail screen's **root `Box`**, add `AppBar(...)` as the **last child** so it overlays the
   full-bleed hero (it must **not** push content down — same as Home). Render it **outside** the
   `when(state)` Loading/Error/Loaded branch (or replicate it across branches as `ChannelScreen`
   does) so the bar is present even during the detail loading shell.
5. `activeNav = -1` (no tab is "current" on a detail page — detail is not a tab destination), so no
   tab shows the active highlight. Inject the `Top 10` tab into `navItems` only when
   `discoverAvailable`, identical to Browse.
6. Drive `scrolled` from the detail `scrollState` (solid bar once scrolled past the hero), matching
   the R62 behaviour on the other screens.

### C. TV D-pad focus round-trip
7. Add a single, always-composed `navBarFR = remember { FocusRequester() }` and pass it as the
   `AppBar`'s `navFR`. Set the AppBar's `onDown = { playFR.requestFocus() }` so DOWN from the bar
   returns to the primary action.
8. Make D-pad **UP from the hero actions row** focus the bar: add an `onUp = { navBarFR.requestFocus() }`
   to the actions `Row` (via the existing `dpadFocusable` / focus-direction modifier, which consumes
   the key when a directional callback is supplied). This mirrors Home's `heroFR`/`navBarFR` bridge —
   the bar is reached only via explicit UP, never by native spatial search.
9. **Entry focus stays on Play** (`playFR`), not the bar — detail's primary action is unchanged. The
   R72 "snap to top when the actions row gains focus" effect keeps the hero (and thus the overlay bar)
   framed during the round-trip.

### D. Spacing
10. Bump the detail bring-into-view spec's `topInsetDp` from `20.dp` to ≈ `RaviloDimens.appBarHeight +
    24.dp` so that when D-pad navigates up into the first content row (cast / "More Like This" /
    season picker) its title isn't hidden under the 60.dp bar — the same reasoning as Home's
    `appBarHeight + 34.dp`.

## Invariants
- **Per-screen overlay, not a global wrapper.** Every `AppBar` prop is screen-specific (`navFR`/`onDown`
  bridge into *this* screen's focusables, `scrolled` derives from *this* screen's scroll state,
  `navItems`/`onNavSelect` differ); a global bar in `RaviloApp` would fight all of that. Follow the
  established convention.
- **Back stays single-stage.** Adding the bar must not introduce a two-stage Back or `backToTopOnBack`
  on detail — Back from anywhere on a detail page still pops to the previous screen via `RaviloApp`'s
  root key handler.
- Renders server-pushed state only; the bar adds navigation affordance, not new data.

## Out of scope
- A navbar on `PlayerScreen` (full-screen video — intentionally chrome-light).
- Redesigning `AppBar` itself or adding new tabs/items.
- Phone-target (R60) navbar adaptation — this phase targets the existing common `AppBar` layout.
- Changing `ChannelScreen`'s `ChannelBar`.

## Source references
- `ravilo-ui/.../components/AppBar.kt` (the bar + `ChannelBar`),
  `ravilo-ui/.../screens/HomeScreen.kt` (overlay + `navBarFR`/`heroFR` bridge reference, topInset),
  `ravilo-ui/.../screens/BrowseScreen.kt` (nav-tab reset semantics, `discoverAvailable`),
  `ravilo-ui/.../screens/MovieDetailScreen.kt` + `SeriesDetailScreen.kt` (targets; `playFR`, actions
  row, R72 snap, bring-into-view spec),
  `ravilo-ui/.../RaviloApp.kt` (`Dest`, dispatch, `push`/`pop`, root Back handler, `discoverAvailable`
  hoist, Browse nav lambda ≈ 320–328),
  `ravilo-ui/.../focus/FocusModifiers.kt` (`dpadFocusable` directional callbacks),
  `ravilo-ui/.../theme/Dimens.kt` (`appBarHeight = 60.dp`).
- Related: **R57** (sticky navbar — admin config preview, not this bar), **R62** (scrolled-solid bg),
  **R65** (tokenized bar height + avatar URL), **R67** (navFR at active slot), **R72** (detail snap-to-top
  on actions focus), **R47** (detail focus draw-only scale).
