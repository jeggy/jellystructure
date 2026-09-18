# Phase R262 — Discover is one page with five tabs, not five pages

> Since R243 gave Discover its five segments, entering it and switching between *Coming Soon · Request
> · Studios · Networks · Genres* looks like loading a whole new page: the app bar leaves with the old
> screen and slides in with the new one, a segment's first visit replaces everything on screen —
> app bar, title, segment bar — with Home's hero skeleton (a blank shimmer that covers the whole TV),
> and on the phone the tab strip forgets where it was scrolled. Back then takes **three** presses to
> reach Home from a scrolled wall. Owner direction 2026-09-18: the top bar just changes focus to
> *Discover* and the content of that page appears; the same for the sub-tabs; on a TV, Back from
> anywhere on the page first goes to the top, and from the top leaves to the page you came from; on
> web and mobile Back simply goes back, wherever you are on the page.

## Status

`✓ Built` — written 2026-09-18 from the owner's direction, a trace of `main` and a same-day run on the
Stue TV (release `1.23-dirty` sideload) and the Pixel 9 (Play 1.26). **Dev-reviewed 2026-09-18 against `main` `05195d1f`** (see
§Dev review at the bottom: nine `backToTopOnBack` sites, the focus token must be consumed, and R267/R268
touch the same page). **Built 2026-09-18** — compiles clean on `ravilo-ui` (commonMain/Android/wasmJs),
`ravilo-android` and `ravilo-web`. FR-R262-3 implemented for the full nav-bar section group
(Home/Movies/Series/Discover), not Discover's own segments alone, since acceptance 1 and 5 require it.
**Not yet device-tested** — acceptance 1–8 need the Stue TV, the bedroom TV (dev review item 5) and a
real Pixel 9/browser, none run this session.
Client-only (`ravilo-ui` commonMain). No backend change: the three endpoints are already server-cached
and fast (the Coming Soon and Studios walls both had their data on screen inside the first
screenshot, < 0.5 s after the tap, on both devices). Nothing new to draw: `design/ravilo/ravilo-app.js`
already renders Discover as one page whose tabs swap the content beneath a fixed header — this phase
makes the app match the mockup.

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — Ravilo taken
through **R259**, admin through **234**. Ravilo-only, no admin pair. Siblings written the same day:
**R260** (the player's Back on the phone) and **R261** (fullscreen only while playing).

## Current state (traced against `main` and observed, 2026-09-18)

### The shape: three screens wearing one tab

`RaviloApp.kt`'s `Dest.Discover(displayName, segment, focusSegment)` branch renders a **different
screen composable per segment**: `UpcomingScreen` (Coming Soon), `DiscoverScreen` (Request) and
`TaxonomyScreen` (Studios/Networks/Genres), each with its own store (`keptStore("upcoming:…")`,
`"discover:…"`, `"taxonomy:…"`), its own `AppBar(...)`, its own page header and its own copy of
`DiscoverSegmentBar` inside the first item of its own `LazyColumn`. A chip press does
`replaceTop(Dest.Discover(…, seg, focusSegment = true))`; the class is the same so `AnimatedContent`
skips the slide, but the composable tree is torn down and rebuilt from the root.

### Four things that follow from that shape, each seen on a device

1. **Entering Discover slides the whole page, app bar included.** Home → Discover is a `push`, class
   changes, the Forward slide runs (25 % of the width, 220 ms). On the Stue TV the burst screenshot
   taken ~0.8 s after Select still shows the Home hero on the left and the Discover page — *with its
   own app bar* — sliding in from the right. The nav bar the viewer just pressed moves away and a copy
   of it moves in. (Movies ↔ Series never do this: both are `Dest.Browse`, same class.)
2. **A segment's first visit blanks the screen.** Each store starts in `Loading`, and every screen
   renders `Loading` as `HomeLoadingShell()` — Home's skeleton: a 600 dp hero block plus two rows,
   composed *instead of* the app bar, the title and the segment bar. A TV is 540 dp tall, so the hero
   block alone fills it: the Stue TV showed a uniform shimmer-grey frame with nothing on it 0.7 s after
   selecting *Studios*, then the finished wall at 1.4 s. `Error` does the same (a centred column, no
   app bar). Browse does this right — its `AppBar` is outside the state switch and `Loading` is a
   one-line *Loading…* in the content area.
3. **The tab strip forgets itself on the phone.** The nav bar on a phone is a horizontal scroller; the
   viewer scrolls it to reach *Discover* (off-screen at 411 dp — the strip shows `Home · Movies ·
   Series`). Every segment switch rebuilds the `AppBar`, so the strip snaps back to its start and the
   active *Discover* tab is scrolled out of view (Pixel 9 screenshot after tapping *Studios*: strip
   reads `Ravilo · Home · Movies · Series`, Discover absent).
4. **Back is three stages on the TV.** Each screen's root has R55's
   `backToTopOnBack(atTop = { navBarFocused }, onBackToTop = { navBarFR.requestFocus(); scrollToItem(0) })`.
   Measured from four rows down the Studios wall: Back #1 → top of page, focus on the **Studios chip**;
   Back #2 → focus on the nav bar's *Discover*; Back #3 → Home. Two causes:
   - "At top" is defined as *the nav bar has focus*, not *the page is scrolled to the top*. Focus on a
     chip at the top of the page counts as "not at top", so Back spends a press moving focus one row up.
     Browse defines it by scroll position (`firstVisibleItemIndex == 0 && …ScrollOffset == 0`).
   - `DiscoverSegmentBar`'s `LaunchedEffect(focusActiveOnEntry, active) { activeFR.requestFocus() }`
     lives inside a lazy item. When Back #1 scrolls the header back into the viewport, the item is
     composed afresh, the effect re-runs (`focusSegment` is still `true` on the destination) and steals
     the focus that `onBackToTop` had just given the nav bar. R257 FR-R257-2 met a cousin of this.
5. **Back-to-top is not gated by platform.** `backToTopOnBack` is a key-event modifier; on the phone the
   gesture Back goes through `PlatformBackHandler` and pops directly (correct by accident), but on the
   web a keyboard Escape/Backspace on any scrolled page scrolls to the top instead of leaving. The
   owner's rule — scroll-to-top-on-Back is a TV thing — is not written anywhere.

### What is already right and must stay

- Focus lands on the nav bar's *Discover* item on entry from another tab (each screen's
  `LaunchedEffect(Unit) { navBarFR.requestFocus() }`), and on the pressed chip after a segment switch
  (`focusSegmentOnEntry`). R257 FR-R257-2's tile restore on Back from a seeded grid.
- The retained stores: a second visit to a segment is instant and refreshes silently
  (`refresh(silent = true)`), and live-config pushes refresh them (R33).
- The Discover nav button while already on Discover steps to the next segment (the R170 fix).

## Requirements

**FR-R262-1 · One Discover frame.** Discover is one screen composable with one `AppBar`, one page
header (title + subtitle) and one `DiscoverSegmentBar`, all composed **once** for as long as
`Dest.Discover` is on top, whatever the segment. Only the region below the segment bar changes when a
chip is pressed. The three stores stay as they are (one fetch each, retained across navigation); what
changes is that they feed *content*, not *screens*. The segment bar and the header leave the lazy list
and live in the frame, so nothing about them is re-created by scrolling or by a segment switch.

**FR-R262-2 · Loading and error are content states, never page states.** While a segment's store is
`Loading`, the frame stands (app bar, title, segment bar, the pressed chip focused) and the content
region shows a loading treatment sized to that region — Browse's one-line *Loading…* is acceptable; a
content-region shimmer in the shape of the segment (date rail + two cards; six tiles) is better. The
Home hero skeleton is never shown on Discover. `Error` renders the existing message inside the content
region with the frame standing, so the viewer can switch to a working segment without leaving.

**FR-R262-3 · The app bar does not move on a section switch.** Switching between the nav bar's
sections (Home · Movies · Series · Discover) is not a slide: the bar stays where it is, the selected
tab changes, and the content beneath is replaced (instantly, or with a short crossfade of the content
region only — never a horizontal slide of the whole page). The slide is kept for drilling in — a
detail page, the player, a seeded grid, See all — and for Back out of those. This is what
`AnimatedContent`'s `contentKey` already does for Movies ↔ Series (same class); it becomes the rule for
every section-to-section move. *Owner decision to confirm:* the direction was given for Discover;
applying it to Home/Movies/Series is the same one-line rule and is proposed here for consistency.

**FR-R262-4 · A segment switch keeps the viewer's place in the frame.** On the phone the nav strip's
horizontal scroll is preserved (it is the same composable, so nothing resets it); on the TV focus
stays on the pressed chip (FR-R243-1, unchanged); the content region starts at its top for the new
segment; the previous segment's scroll position may be retained in its store (Coming Soon already
retains `listState` there) so returning to it lands where the viewer left.

**FR-R262-5 · Back on a TV is exactly two stages, defined by scroll.** On a TV (`isTvPlatform`), while
`Dest.Discover` is on top: if the content region is scrolled away from its top, Back scrolls it to the
top and moves focus to the nav bar's *Discover* item, consuming the press; if the content region is at
its top — wherever focus is: a chip, the nav bar, the first row of tiles, the date rail — Back pops to
the previous destination. "At the top" is the content region's scroll state
(`firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`), never which element has focus.
The segment bar's entry-focus effect runs once per chip press and never on re-composition, so nothing
re-steals the focus that stage one placed.

**FR-R262-6 · Back on web and mobile is one stage.** Off the TV, Back (the phone's gesture or button,
the browser's Back, and a keyboard Escape/Backspace on the web) pops to the previous destination from
anywhere on the page. Scroll-to-top-on-Back is a TV behaviour: `backToTopOnBack` is a no-op unless
`isTvPlatform`, on every screen that uses it — Home, Browse, Search, Discover — because the owner's
rule is about the platform, not about Discover. (Phone gesture Back already pops; this makes the
keyboard path agree and writes the rule down.)

**FR-R262-7 · Warm the other tabs.** Entering Discover starts all three stores (Coming Soon, Request,
the facets) if they are not already retained, so the first chip press after entry finds its content
loaded or in flight rather than cold. The three requests are the ones the screens make today, all
server-cached (216 FR-216-8/11; `UpcomingService`); nothing new is fetched and nothing is fetched twice.
A segment that is not available (Coming Soon without Sonarr/Radarr, Request without Seerr) is not
warmed — its chip is not shown either (R243 FR-R243-1).

**FR-R262-8 · Nothing the segments do changes.** The date rail, filters and missing pill (R160/R188 on
Coming Soon); the trending rows, My requests and Seerr search (R170/R171 on Request); the walls, their
counts, the scoped line, tile restore on Back from a seeded grid (R243, R257, R259) — all unchanged in
content and behaviour. Only where they are composed changes.

## Acceptance

Stue TV (D-pad):

1. From Home, Select *Discover* → the nav bar does not move; *Discover* is selected and focused; the
   Coming Soon content is on screen or shows its loading treatment beneath a standing header. No frame
   in a 100 ms screenshot burst shows the Home hero and the Discover bar at the same time, or a
   screen without an app bar.
2. Cold-start the app, Select *Discover*, press Right ×2 to *Studios*, Select → the header, the chip
   row and the app bar are identical in every burst frame; the content region alone changes from its
   loading treatment to the wall.
3. Down ×4 into the Studios wall, Back → top of page, focus on the nav bar's *Discover*. Back → Home.
   Two presses, from every segment, from any depth.
4. On Discover with focus on the *Networks* chip (page at top), Back → Home in one press.
5. Home → Discover → *Movies* → Back → Discover on the segment that was open, at the top, no slide of
   the app bar in either direction.

Pixel 9:

6. Scroll the nav strip to *Discover*, tap it, tap *Studios*, tap *Genres* → the strip still shows
   *Discover* selected and in view throughout.
7. Scroll deep into a wall, gesture Back → Home directly.

Web (ravilo-web):

8. Scroll deep into a wall, press Escape → the previous page; browser Back → the previous page.

## Non-goals

- Changing the segment set, its gating, or any segment's content (R243/R170/R160 stand).
- Persisting the open segment across app restarts.
- A shared app bar hoisted above *every* destination (detail pages, the player and the profile picker
  have no nav bar); this phase hoists it only within the Discover frame and removes the slide between
  sections. If the same "one frame" shape is later wanted for Home/Movies/Series it is a follow-on.
- Backend work: no new endpoint, cache or field.

## Open questions

1. **FR-R262-3's scope** — Discover only, or every section switch? Proposed: every section switch,
   since the rule is one `contentKey`/`transitionSpec` change and the inconsistency (Movies ↔ Series
   already do not slide, Home ↔ Movies do) is the odd thing today.
2. **Where should stage-one Back put focus on the TV** — the nav bar's *Discover* (this spec; the R55
   pattern, and what `onBackToTop` already tries) or the active chip? Lean: the nav bar; the chip is one
   Down away and the viewer's next press is most often Back again.
3. **Content-region loading treatment** — the one-line *Loading…* (zero design work) or a per-segment
   shimmer (a small addition to `Shimmer.kt`)? Lean: the shimmer for the walls (six tile plates), the
   line for the two list segments.

## Dev notes

- The frame is a new `DiscoverScreen`-level composable (rename the current Request screen's file to
  avoid two things called Discover) taking the three stores and the segment; the three existing
  `*Loaded` bodies become its content region, minus their headers, segment bars and app bars.
- `DiscoverSegmentBar`'s focus effect: key it on a *press token* (the `focusSegment` request from the
  destination, consumed once), not on `(focusActiveOnEntry, active)`, and keep it outside any lazy
  item — FR-R262-1 does the latter for free.
- `backToTopOnBack`: add the platform gate in the modifier itself (`if (!isTvPlatform) return this`),
  so the six call sites need no change; and switch Discover's `atTop` to the scroll test Browse uses.
- `AnimatedContent`: `contentKey` groups the section destinations (`Home`, `Browse`, `Discover`) under
  one key, or the `transitionSpec` returns a content fade for section→section moves; either is the
  whole of FR-R262-3.
- FR-R262-7 is three `keptStore(...)` calls hoisted to the frame's entry rather than one per branch.

## Dev review (2026-09-18, against `main` `05195d1f`)

The shape is as traced: `RaviloApp.kt:990–1060` renders three screens from one `Dest.Discover`, each
with its own `AppBar`, header and `DiscoverSegmentBar`; all three render `Loading` as
`HomeLoadingShell()` (`UpcomingScreen.kt:150`, `DiscoverScreen.kt:94`, `TaxonomyScreen.kt:112`); a chip
press is `replaceTop(… focusSegment = true)`. The plan stands. Five notes for the build.

1. **`backToTopOnBack` has nine call sites, not six** — `HomeScreen`, `BrowseScreen`,
   `SeededBrowseScreen`, `SearchScreen`, `SeerrSearchScreen`, `ChannelScreen`, `UpcomingScreen`,
   `DiscoverScreen`, `TaxonomyScreen`. The gate inside the modifier (`focus/BackToTop.kt`) still covers
   them all in one line; FR-R262-6's list of screens should be read as *every* one of the nine, channel
   pages and seeded grids included.
2. **`focusSegment` lives on the destination, so it outlives the press.** It stays `true` on the stack
   entry; when the viewer returns from a seeded grid the frame recomposes and a press-token keyed on the
   destination would fire again — against R257 FR-R257-2's tile restore. The token must be **consumed**:
   once the chip has taken focus, `replaceTop(dest.copy(focusSegment = false))` (same class, no
   transition), or a counter held in the frame. Add to acceptance: Studios → a tile → Back lands on the
   tile, not on the chip.
3. **The taxonomy store is one store for three chips** (`keptStore("taxonomy:…")`, `RaviloApp.kt:1045`),
   so FR-R262-7's "three stores" is three `keptStore` calls covering all five segments — correct as
   written, just not one per chip.
4. **Two design-authored drafts landed the same day and touch this page; neither blocks it.** **R268**
   re-orders the chips (*Networks* first) and makes the strip scroll — so acceptance 1 should say *the
   first available segment's content*, not *Coming Soon*. **R267** moves the phone's pages to a bottom
   bar, after which FR-R262-4's phone half and acceptance 6 (the scrolled nav strip) describe a strip that
   no longer exists. Build R262 first; the frame it creates is what both of those want to edit.
5. **Run acceptance 3 on the bedroom TV too.** The root's `PlatformBackHandler` is *enabled* on Discover
   (`stack.size > 1`), and that TV is where one physical Back was seen reaching both a dispatcher handler
   and a key handler (R260 dev review, item 1). If it does so here, stage one's consumed `KeyDown` is
   followed by a dispatcher pop and "two stages" reads as one. The Stue TV's measured three stages show it
   does not happen there. If it reproduces, the fix is R260's per-press flag applied at the root, not a
   change to this phase's rules.
6. **FR-R262-3 / open question 1 stays the owner's call.** Mechanically it is one shared `contentKey`
   for `Home`, `Browse` and `Discover`; it also removes the slide on a *pop* between sections, which is
   consistent with the rule. Open question 2: the nav bar. Open question 3: the one-line *Loading…*
   first; the per-segment shimmer is polish and can follow.

No backend change, no new string, no dependency on another phase.
