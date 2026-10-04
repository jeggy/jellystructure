# Phase R366 — Discover opens on Coming Soon

> Owner, 2026-10-04: *"In discover we want coming soon to be the first tab, just before networks."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from the owner's direction; dev-reviewed 2026-10-04 (section at the end —
the design holds; FR-R366-4 breaks three more tests than it names, and the mockups are already done). Client only
(`ravilo-ui` commonMain, `NavItems.kt`) plus the design mockup; no string, DTO, backend or config change.
**Amends R268 FR-R268-1** (the declared order) only; R268's mechanism (one declared order, gating filters it,
the entry chip is the first rendered one) stays exactly as built.

## Requirements

### FR-R366-1 — The declared order
`DiscoverSegment` (and with it `DISCOVER_SEGMENT_ORDER`) becomes:

**Coming Soon · Networks · Studios · Genres · Request**

Only Coming Soon moves; the three library walls keep their order and Request stays last.

### FR-R366-2 — Where Discover opens
Unchanged rule, new result: Discover opens on the first rendered chip (`defaultDiscoverSegment`). So:
- a household with Sonarr/Radarr (Coming Soon available) **opens on Coming Soon**;
- a household without it opens on **Networks**, as today (or the first library wall left, R310).
The Discover nav button's step while on Discover (R170, `nextDiscoverSegment`) walks the new order.

### FR-R366-3 — Every surface follows
TV, phone and desktop take the order from the same list (no per-platform order). The design mockup's
`SEG_ORDER` in `design/ravilo/ravilo-app.js` (and the phone's in `Ravilo Mobile.html`) changes to match.

### FR-R366-4 — Tests
`DiscoverSegmentOrderTest`: the pinned order becomes the one above; `theFirstChipIsNetworksOnEveryHousehold`
becomes *Coming Soon when available, else Networks*; the subsequence and always-rendered-default tests stay.

## Acceptance

1. With Sonarr/Radarr configured: Discover opens on Coming Soon, the strip reads *Coming Soon · Networks · Studios ·
   Genres · Request* (Request only with Seerr).
2. Without Sonarr/Radarr: the strip starts at Networks and Discover opens there.
3. On the TV, Down from the app bar's Discover tab lands on the selected chip (R350 FR-5), Left from Coming Soon
   does nothing, Right walks the strip in the new order.
4. `DiscoverSegmentOrderTest` green.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `NavItems.kt`, `RaviloApp.kt`'s Discover wiring, `DiscoverScreen.kt`, `DiscoverSegmentOrderTest`,
`DiscoverFocusTest` and the two mockups. The design holds. Seven items, one for the owner.

1. **One line of code.** `enum class DiscoverSegment` (`NavItems.kt:73`) is the declared order, and
   `DISCOVER_SEGMENT_ORDER` (`:84`) is `entries.toList()`. Reorder the enum to `COMING_SOON, NETWORKS, STUDIOS,
   GENRES, REQUEST`. `discoverSegments` (`:103`) filters it, `defaultDiscoverSegment` (`:134`) takes its first,
   `nextDiscoverSegment` (`:139`) walks the rendered list, and every `Dest.Discover` push in `RaviloApp.kt` (`:1589`,
   `:1635`, `:1683`, `:1783`, `:1802`, `:1951`, `:1999`, `:2141`, `:2495`) goes through `defaultDiscoverSegment`. So
   TV, phone and desktop follow with no other change (FR-R366-3). Reordering is safe: no ordinal is used anywhere
   (`grep` finds only `:84`), and `toRoute()` renders a bare `/discover` (`RaviloApp.kt:371`), so nothing persisted
   or on the wire carries the order.

2. **Comments to rewrite, or they lie.** The R268 KDoc above the enum (`:64-72`) and above `DISCOVER_SEGMENT_ORDER`
   (`:74-83`, "library first … the first chip is the same one on every household"), and `defaultDiscoverSegment`'s
   (`:116-126`, "this is always **Networks** now"). Replace them with R366's rule: Coming Soon first where Sonarr or
   Radarr is set up, then the library walls, Request last.

3. **FR-R366-4 undercounts the test changes.** `DiscoverSegmentOrderTest` has five tests that pin the old order,
   not two:
   - `theDeclaredOrderIsLibraryFirst`: new list, new name;
   - `gatingFiltersAndNeverReorders`: the `arrOnly` expectation becomes `COMING_SOON, NETWORKS, STUDIOS, GENRES`
     (`seerrOnly` and `neither` are unchanged);
   - `theFirstChipIsNetworksOnEveryHouseholdThatHasNetworks`: becomes Coming Soon when `upcomingAvailable`, else
     Networks;
   - `steppingWrapsThroughTheRenderedOrderOnly`: `next(both, COMING_SOON)` is now `NETWORKS`, and
     `next(both, REQUEST)` is now `COMING_SOON`;
   - `aWallGatedOffAloneOrInPairsKeepsTheOrderAndLandsOnTheFirstLeft`: the expected list becomes
     `[COMING_SOON] + expected + [REQUEST]`, and the default for `(true, true, walls)` is always `COMING_SOON`. Keep
     the case table, but add the `upcomingAvailable = false` variant so "first wall left" stays covered.
   `theEnumsOwnOrderIsTheShippedOrder`, `theDefaultIsAlwaysARenderedChip`, `allThreeOffLands…`,
   `steppingNeverReachesAHiddenWall` and the gating-set test stay as they are. `DiscoverFocusTest` (R350) passes its
   own `segments` list and is unaffected. No other test, script or screenshot test names the order.

4. **The mockups are already done.** `5210045a` changed `SEG_ORDER` in `design/ravilo/ravilo-app.js:940` and
   `design/ravilo/Ravilo Mobile.html:759`, each with an R366 note. One leftover: the first line of the comment above
   `ravilo-app.js:940` still reads "library first: Networks · Studios · Genres · Coming Soon · Request". That's a
   design-side fix, not a blocker.

5. **Acceptance 3 needs nothing new.** Down from the app bar lands on the selected chip through `segmentFR`
   (`DiscoverScreen.kt:236-239`, R350). Left from the first chip has nothing to its left in the strip's
   `focusGroup`, which is today's behaviour on Networks. The R267 re-tap (`OnReselect`) and the R310 landing rule
   (`RaviloApp.kt:1840-1841`) both read the first rendered chip, so they follow.

6. **A small, pre-existing wrinkle that becomes visible.** `upcomingAvailable` starts `false` and is set only from
   `HomeStore` (`RaviloApp.kt:654`, `:1543`). If Discover is pressed before Home's feed has answered, the viewer
   lands on Networks. When the feed arrives, the Coming Soon chip appears at the front of the strip and the chips
   shift right; the R310 rule keeps the viewer on Networks. Today the late chip appears in the middle, so it is less
   visible. It is rare, because the home snapshot normally seeds the store, and no change is needed. Noted so it
   is not reported as an R366 bug.

7. **For the owner — an empty Coming Soon.** With Sonarr/Radarr set up but nothing on the calendar, Discover now
   opens on *"Nothing scheduled"* (`up.nothing`, `UpcomingScreen.kt:197`). **Lean: open there anyway.** One rule
   (the first tab) is easier to learn than a tab that is sometimes skipped, and the walls are one Right away.
   Skipping it would need the calendar fetched before the landing is chosen, which is the cold-start wait R262
   removed.

**Tests:** the updated `DiscoverSegmentOrderTest` (common, pure). Only the device confirms acceptance 1–3 on the TV,
which is a quick look.


## Owner decisions (2026-10-04, after the dev review)

**With nothing on the calendar, Discover opens on the next chip (Networks)**, not on Coming Soon's *Nothing scheduled*.
Coming Soon's chip stays in first place in the strip. Build: `defaultDiscoverSegment` gains `upcomingEmpty: Boolean?`
from the upcoming store's last answer (kept across launches like the other client caches); `true` ⇒ skip Coming Soon
for the entry chip only; `null` (never fetched) ⇒ open on Coming Soon as the rule says. The Discover nav button's
step still walks every rendered chip. Add a test for both cases.
