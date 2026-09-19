# Phase R268 — Discover's tabs lead with the library, and the strip scrolls

> Discover's five segments are ordered by the order they were built in — *Coming Soon · Request ·
> Studios · Networks · Genres* — which puts the two **config-gated, outward-looking** tabs first and the
> three that index **what the household already owns** last. Owner direction 2026-09-18: lead with the
> library. New order: **Networks · Studios · Genres · Coming Soon · Request**. And five chips do not fit
> a portrait phone, so the strip must scroll rather than clip — on the phone by touch, on the TV by
> D-pad carrying the focused chip into view.

## Status

`Planned` — written 2026-09-18 from the owner's direction. **Dev-reviewed 2026-09-19 against `main`
`dcb97f2c`** (see §Dev review at the foot: scope claims verified accurate; the declared order is also a
correctness fix, and the enum should be reordered with it). Not built. Client-only
(`ravilo-ui` commonMain), **no new string in any language** (all five labels exist in en/da/fo), no
backend, DTO or config change. Supersedes **R243 FR-R243-1**'s tab order only; its gating rule, the
walls, the counts and the scoped line are untouched. Pairs with **R262** (Discover as one frame with one
segment bar) — R262 moves the bar out of the lazy list, this phase fixes what the bar contains and how
it overflows; either can land first.

**Numbering:** written 2026-09-18 as R265 and renumbered **R265 → R268** the same afternoon, when
`main` took R263 · R264 · R265 (the web app installs · the receiver-only TV app · play on a TV from the
phone). Verified against `main` on 2026-09-18 — Ravilo taken through **R265**, admin through **236**.
R266 (Cast Connect) and R267 (the phone's top row + bottom bar) are ours, written the same day.
Ravilo-only, no admin pair. Next free: **238 / R269**.

Design: built into both mockups the same day — `design/ravilo/ravilo-app.js` (`discTabs()` order) +
`design/ravilo/ravilo.css` (`.discseg` scrolls) for the TV, and `design/ravilo/Ravilo Mobile.html` (the
Discover segment row, all five chips, scrolling) for the phone.

## Current state (traced against `main`, 2026-09-18)

- The order is assembled, not declared: `DiscoverSegment`'s available list puts Coming Soon and Request
  first and the three taxonomy segments after, and the mockup mirrors it —
  `discTabs()` in `design/ravilo/ravilo-app.js` pushes `coming` (if Sonarr/Radarr), then `request` (if
  Seerr), then concatenates `TAXO_TABS = ['studios','networks','genres']`.
- **Gating means the first tab is not a fixed thing.** A household with no Seerr and no *arr sees
  Studios first; the household that has both sees Coming Soon first. Whatever the order is, it has to
  read sensibly with either or both of the outward tabs absent (R243 FR-R243-1 — an unavailable segment
  has no chip at all).
- **Nothing scrolls.** `DiscoverSegmentBar` is a plain `Row` of chips; `.discseg` in `ravilo.css` is an
  `inline-flex` pill group with `white-space: nowrap` and no overflow rule. At five chips, TV labels at
  21 px inside 24 px padding, the strip runs past the *Search on Seerr* pill that shares its row; on a
  411 dp phone even three chips plus the header crowd the width, which is exactly the defect R262 §3
  records on the nav strip one row above.
- Both surfaces already have the machinery this needs: the TV's `scrollRowTo` (used by the nav row and
  every content row) carries a focused item into view with the same tween, and the phone's strips
  (`.tabs`) already scroll horizontally with hidden scrollbars.

## Goal

The strip reads library-first, in one declared order, and never clips a chip on any screen — whichever
of the two gated segments exist.

## Functional requirements

**FR-R268-1 — One declared order, library first.** The segment order is
**Networks · Studios · Genres · Coming Soon · Request**, declared once as a constant list that both the
bar and any index-based logic (entry focus, the Discover-button step-to-next-segment of R170) read from.
No call site re-orders it and no branch assembles it by availability.

*The order is the same on every platform. An intermediate round of **R267** briefly prepended `Movies`
and `Series` to this strip on handsets; that was undone when the phone's two type browses merged into
its own **Library** page instead (R267 FR-R267-5b), so these five segments and this order are what
ships everywhere.*

**FR-R268-2 — Gating filters, it never re-orders.** The rendered strip is the declared order with the
unavailable segments removed (R243 FR-R243-1 unchanged: Coming Soon needs Sonarr or Radarr, Request
needs Seerr, the three taxonomy tabs are always present because they index the library itself). So a
household with neither integration sees exactly *Networks · Studios · Genres*, and one with both sees
all five in the order above. **The default segment on entering Discover is the first *available* chip**
— which is now always **Networks**, since the taxonomy tabs cannot be gated off.

**FR-R268-3 — Why this order, so it is not re-litigated by the next reader.** Networks and Studios are
the two walls a viewer browses *by habit* ("what's on DR?", "the Pixar shelf"); Genres is the widest and
least specific of the three, so it sits after them; Coming Soon is about titles the household does not
have yet; Request is about asking for one. Left to right, the strip therefore runs from *what you own*
to *what you don't*, and the first chip is the same one on every household.

**FR-R268-4 — The strip scrolls horizontally and never clips.** The segment bar is horizontally
scrollable on every platform, with the scroll confined to the bar (it never moves the page or the nav
row above it — R267 FR-R267-5's rule, same reason). The first chip is flush with the page gutter and
**every chip can be brought fully into view**; a partially visible chip at the trailing edge is the
scroller's own affordance (FR-R268-6), not a defect — what is forbidden is a chip that cannot be
reached at all, which is what happens today.

**FR-R268-5 — On a phone it is a touch scroller with no scrollbar.** Swipe to reach the far chips, no
visible scrollbar (the platform's transient one only), and momentum as the platform gives it. A chip is
never shrunk, truncated or ellipsised to make five fit — the strip gets longer, not denser, because the
13 sp floor and the 46 dp target (R244/R234) win over fitting everything on screen at once.

**FR-R268-6 — On a TV the focused chip is carried into view.** D-pad Left/Right moves between chips and
the bar scrolls so the newly focused chip is fully visible with the neighbouring chip peeking, using the
same tween the nav row and content rows use (`scrollRowTo`'s idiom). Focus never lands on a chip that
is off-screen, and the bar does not scroll when focus is elsewhere on the page. Web follows the TV's
rule for keyboard focus and the phone's for a trackpad swipe.

**FR-R268-7 — Selected state survives the scroll.** The selected chip keeps its filled/`cur` treatment
whether or not it is currently in view, and entering Discover scrolls the strip so the **selected** chip
is visible (on a household where the remembered segment is *Request*, the strip opens scrolled to it,
not at *Networks* with the selection off-screen).

**FR-R268-8 — Nothing else about Discover changes.** The walls and their tile shapes, the counts and the
profile-scoped line (R243, R259 FR-R259-7's 6-up), the date rail and the missing pill (R160/R188), the
trending rows and Seerr search (R170/R171), R262's one-frame shape and its Back rules — all unchanged.
This phase moves chips and lets them scroll.

## Non-goals

- **Adding, removing or renaming a segment**, or changing what any of them shows.
- **Letting the household re-order the tabs.** One order, the same on every TV in the house; a setting
  for five chips would cost more to explain than it saves.
- **Remembering the open segment across app restarts** (R262's non-goal, still).
- **A scroll indicator on the segment bar.** The nav row above it has one (`NavScrollIndicator`, only
  when it overflows); a second 3 dp bar 40 px below the first is noise. The peeking neighbour chip of
  FR-R268-6 is the affordance.
- **Vertical wrapping to two lines on a phone.** It reflows the whole header every time a chip is
  pressed and it makes the strip's height depend on the language.

## Acceptance

1. Household with Sonarr/Radarr **and** Seerr, stue TV: the strip reads *Networks · Studios · Genres ·
   Coming Soon · Request*; entering Discover opens **Networks**.
2. Household with neither: the strip reads *Networks · Studios · Genres* and opens Networks. With Seerr
   only: *Networks · Studios · Genres · Request*.
3. Stue TV: from *Networks*, Right ×4 reaches *Request* with every intermediate chip fully visible when
   focused and the strip scrolled so the focused chip is never at the edge of the screen.
4. Pixel 9, portrait, all five available: the strip is swipeable, the first chip is flush with the
   gutter, *Request* can be swiped fully into view, and no label is truncated or under 13 sp.
5. Pixel 9: tapping *Genres* changes only the content below the strip; the strip's scroll offset and the
   nav row above it do not move (with R262 in, the header does not re-create either).
6. Re-entering Discover on a household whose last segment was *Request*: the strip is scrolled so the
   selected *Request* chip is visible and marked.
7. `Strings.kt` gains no key; a diff of the i18n table across this phase is empty.
8. `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`, `allTests` green.

## Source references

- `ravilo-ui/src/commonMain/…/screens/DiscoverSegment*` — the segment enum, its available-list and
  `DiscoverSegmentBar` (the chips, their `cur`/focus treatment and the entry-focus effect R262 re-keys);
  `…/screens/NavItems.kt` — the nav-level `Discover` entry and R170's step-to-next-segment.
- `design/ravilo/ravilo-app.js` — `TAXO_TABS`, `SEG_LABEL`, `discTabs()`, `discSegment(active)` and
  `scrollRowTo` (FR-R268-6's tween); `design/ravilo/ravilo.css` — `.discseg` / `.dseg` /
  `.dischead-controls`.
- `design/ravilo/Ravilo Mobile.html` — the phone's Discover segment row and its `.tabs` scroller idiom.
- **R243** FR-R243-1 (the segments, their gating, the walls — this phase supersedes its *order* only) ·
  **R262** (one Discover frame, one segment bar, Back) · **R267** (the phone's two-row bar, and the rule
  that a strip's scroll is its own) · **R259** FR-R259-7 (6-up walls on a TV) · **R170/R171** (Request) ·
  **R160/R188** (Coming Soon) · **R244/R234** (46 dp targets, 13 sp floor).

## Open questions — for the dev team

1. **Should the first chip be *Networks* or *Studios*?** The owner's list says Networks; in this library
   networks carry the series shelf and studios the film shelf, so the first chip decides which shelf a
   viewer lands on by default. Design follows the direction as given; one line to swap if it reads
   wrong after living with it.
2. **Does the TV's segment bar want the whole row scrollable, or just the chips?** The bar shares its
   row with the *Search on Seerr* pill (`.dischead-controls`, `margin-left: auto`). Lean: the chips
   scroll, the pill stays pinned right — otherwise the search affordance can scroll off, and R52's point
   is that search is always reachable.
3. **A remembered segment per household or per device?** FR-R268-7 assumes whatever R262 retains. Not
   this phase's call; flagged so the two do not disagree.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

Traced against `screens/NavItems.kt` and `RaviloApp.kt`. **This is the cleanest spec of the 2026-09-18
batch: its scope claims are accurate.** All five labels exist (`seg.coming`, `seg.request`,
`seg.studios`, `seg.networks`, `seg.genres` — `NavItems.kt:84-88`), and nothing here touches a route, a
DTO or the backend. Four notes, one of which strengthens the phase's own argument.

1. **FR-R268-1/-2 are a correctness fix, not only an ordering one — and the code already paid for the
   proof.** Three functions independently encode order today: `discoverSegments()` assembles by
   availability (`:59-63`), `defaultDiscoverSegment()` re-implements the same precedence from scratch
   (`:68-72`), and `nextDiscoverSegment()` is a third. The comment at `:65-67` records that they
   **already drifted once**: *"this used to answer only the first two, so a household with neither
   integration would have reached Discover and landed on a segment that is not rendered"* — a bug R243's
   own dev review had to find and fix by hand. Under FR-R268-2 the default becomes
   `discoverSegments(...).first()` and that entire class of bug is unrepresentable. This is the
   strongest argument the phase has and it is currently only implied; put it in FR-R268-2 so the next
   reader does not treat the requirement as cosmetic and reintroduce a parallel branch.
2. **Reorder the enum as well, and it is safe to.** `DiscoverSegment` is declared
   `COMING_SOON, REQUEST, STUDIOS, NETWORKS, GENRES` (`:53`) and `TAXONOMY_SEGMENTS` is
   `STUDIOS, NETWORKS, GENRES` (`:56`) — both the old order. Leaving either while introducing a new
   declared list puts two orders in one file, and anything reaching for `entries` or an ordinal would
   disagree with the bar. **Checked and cleared:** the segment travels only in
   `Dest.Discover(displayName, segment, focusSegment)` (`RaviloApp.kt:229`), which is in-memory, and
   `toRoute()` renders Discover as a bare `"/discover"` with no segment component (`:284`) — so no
   ordinal is persisted or serialised anywhere and the enum can be reordered with no migration. Do it,
   and make `TAXONOMY_SEGMENTS` the gating set only, never an order.
3. **Open question 1 has a live consequence worth naming.** `defaultDiscoverSegment`'s `else` branch is
   `DiscoverSegment.STUDIOS` (`:71`), so today a household with neither integration lands on **Studios**.
   The reorder changes that to **Networks** — intended, and covered by acceptance 2, but it is a real
   behaviour change for the one configuration that gets no other change from this phase. Name it in the
   spec rather than letting it fall out of the ordering, because it is the household most likely to
   notice and least likely to have been considered.
4. **Open question 2: confirmed, chips scroll and the pill stays pinned.** The lean is right and it
   already follows from FR-R268-4's "the scroll is confined to the bar" — but say it explicitly about
   `.dischead-controls`, because the two requirements are in different places and a builder reading only
   FR-R268-6 could reasonably scroll the whole row.
5. **One implementation warning for FR-R268-6/-7 on the TV, drawn from this project's own history.**
   Entering Discover must both scroll the strip so the selected chip is visible (FR-R268-7) and give a
   chip focus (R262's entry-focus effect). **Sequence them; never race them.** This codebase has been
   bitten by exactly this three times — R232 (the season row: the scroll and the focus request ran as
   concurrent coroutines, so the first Down only *looked* like it focused), R223 (season-picker focus
   with rapid-Up scroll-stranding) and R200/R201 (a `FocusRequester` whose target was never placed). The
   settled shape is R232's: await the scroll, then request focus. A line in FR-R268-6 costs nothing and
   is cheaper than rediscovering it on the stue TV.
