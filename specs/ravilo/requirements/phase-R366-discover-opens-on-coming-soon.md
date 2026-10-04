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

## Acceptance

1. With Sonarr/Radarr configured: Discover opens on Coming Soon, the strip reads *Coming Soon · Networks · Studios ·
   Genres · Request* (Request only with Seerr). (With something on the calendar, or before the calendar has ever
   answered; see 5.) CI: `theDeclaredOrderIsComingSoonThenTheLibraryThenRequest`,
   `theFirstChipIsComingSoonWhenAvailableElseNetworks`, `anUnknownOrNonEmptyCalendarOpensOnComingSoon`.
2. Without Sonarr/Radarr: the strip starts at Networks and Discover opens there. CI: `gatingFiltersAndNeverReorders`,
   `theFirstChipIsComingSoonWhenAvailableElseNetworks`.
3. On the TV, Down from the app bar's Discover tab lands on the selected chip (R350 FR-5), Left from Coming Soon
   does nothing, Right walks the strip in the new order. Device only (manual check below); the order Right walks is
   `steppingWrapsThroughTheRenderedOrderOnly`'s.
4. `DiscoverSegmentOrderTest` green, and `UpcomingEmptyTest` green (see *Tests*).
5. (Owner decision, 2026-10-04.) With Sonarr/Radarr configured and nothing on the calendar: Discover opens on
   Networks (or the first chip after Coming Soon), and the strip still starts with Coming Soon. CI:
   `anEmptyCalendarOpensOnTheNextChip`, `anEmptyCalendarNeverMovesTheChip`.

## Tests

### FR-R366-4 — Tests
`DiscoverSegmentOrderTest`: the pinned order becomes the one above; `theFirstChipIsNetworksOnEveryHousehold`
becomes *Coming Soon when available, else Networks*; the subsequence and always-rendered-default tests stay.

*Consolidated 2026-10-04 with the dev review's item 3 (five tests pin the old order, not two) and the owner decision
(an empty calendar skips Coming Soon for the entry chip only). Everything below is pure and runs in CI on
`:ravilo-ui:testDebugUnitTest` and `:ravilo-ui:desktopTest`.*

**`DiscoverSegmentOrderTest`** (`ravilo-ui` `commonTest`, `dev.jellystructure.ravilo.ui.screens`): the five R268
tests that pin the old order, updated.

| Today's test | After R366 |
|---|---|
| `theDeclaredOrderIsLibraryFirst` | renamed `theDeclaredOrderIsComingSoonThenTheLibraryThenRequest`; `both` is `[COMING_SOON, NETWORKS, STUDIOS, GENRES, REQUEST]`. |
| `gatingFiltersAndNeverReorders` | `arrOnly` is `[COMING_SOON, NETWORKS, STUDIOS, GENRES]`; `seerrOnly` (`[NETWORKS, STUDIOS, GENRES, REQUEST]`) and `neither` (`[NETWORKS, STUDIOS, GENRES]`) and the subsequence loop are unchanged. |
| `theFirstChipIsNetworksOnEveryHouseholdThatHasNetworks` | renamed `theFirstChipIsComingSoonWhenAvailableElseNetworks`: `defaultDiscoverSegment(true, d)` is `COMING_SOON` and `defaultDiscoverSegment(false, d)` is `NETWORKS`, for `d` in both values. |
| `steppingWrapsThroughTheRenderedOrderOnly` | the two `neither` assertions are unchanged; `next(both, COMING_SOON)` is `NETWORKS` (was `REQUEST`); `next(both, REQUEST)` is `COMING_SOON` (was `NETWORKS`). |
| `aWallGatedOffAloneOrInPairsKeepsTheOrderAndLandsOnTheFirstLeft` | same case table; the list is `[COMING_SOON] + expected + [REQUEST]` and the default for `(true, true, walls)` is `COMING_SOON`. Add the `upcomingAvailable = false` variant: the list is `expected + [REQUEST]` and the default is `expected.first()`, so "lands on the first wall left" stays covered. |

Unchanged: `theEnumsOwnOrderIsTheShippedOrder`, `theDefaultIsAlwaysARenderedChip` (it calls without `upcomingEmpty`,
which defaults to `null`), `noAnswerFromTheServerShowsEveryWall`, `allThreeOffLandsOnTheIntegrationsOrOnNothing`,
`steppingNeverReachesAHiddenWall`, `taxonomySegmentsAreAGatingSetNotAnOrder`.

New, for the owner decision (`defaultDiscoverSegment(upcomingAvailable, discoverAvailable, walls, upcomingEmpty:
Boolean? = null)`):

- `anEmptyCalendarOpensOnTheNextChip`: `upcomingEmpty = true` gives `NETWORKS` for `(true, true)` and `(true, false)`,
  `STUDIOS` for `(true, true, walls = {STUDIOS})`, and `REQUEST` for `(true, true, walls = ∅)`.
- `anUnknownOrNonEmptyCalendarOpensOnComingSoon`: `upcomingEmpty = null` (never fetched) and `false` both give
  `COMING_SOON` for `(true, true)` and `(true, false)`. The owner's two cases are this test and the one above.
- `anEmptyCalendarNeverMovesTheChip`: `discoverSegments(...)` takes no calendar argument, so for every gating the strip
  still starts with `COMING_SOON` when `upcomingAvailable`; and `nextDiscoverSegment(both, REQUEST)` is still
  `COMING_SOON`, so the Discover button's step reaches it on an empty calendar.
- `anEmptyCalendarIsIgnoredWithoutComingSoon`: with `upcomingAvailable = false`, `upcomingEmpty = true` gives the same
  default as `null` for every `discoverAvailable` and `walls`.
- `theEntryChipIsAlwaysRenderedWhateverTheCalendarSays`: over every `upcomingAvailable × discoverAvailable × walls`
  (`null`, `∅`, each single wall, each pair, all three) × `upcomingEmpty` (`null`, `false`, `true`), the default is in
  `discoverSegments(...)`, and it is `null` only when that list is empty. One case follows from this and is pinned
  by name: with Coming Soon as the **only** chip (`(true, false, walls = ∅)`) an empty calendar still opens on
  `COMING_SOON`, since skipping it would land on nothing.

**`UpcomingEmptyTest`** (`ravilo-ui` `commonTest`, same package). It tests the pure step that turns the upcoming
store's last answer into `upcomingEmpty`, e.g. `fun upcomingEmptyAfter(previous: Boolean?, state: UpcomingState):
Boolean?` (the name is the build's choice; the rule is not):
- `Loaded` with at least one `items` entry ⇒ `false`; `Loaded` with no `items` ⇒ `true`;
- `Loaded` with no `items` but some `missing` ⇒ `true`. *Nothing on the calendar* is what makes the page read
  *Nothing scheduled* (`UpcomingContent`'s `filtered.isEmpty()` under the *All* filter), and `missing` alone does not
  change that sentence;
- `Loading` and `Error` ⇒ `previous`, so a failed refresh never forgets the last answer, and a first launch stays
  `null`.

**Not covered by CI:** the persisted value (kept across launches like `HomeSnapshotCache`, whose platform actuals
have no harness in this module), the wiring in `RaviloApp.kt` that passes it to every `Dest.Discover` push, and
acceptance 3's focus moves. `DiscoverFocusTest` (R350) passes its own `segments` list and needs no change.

**Device only — manual check (a TV and the phone, about two minutes):**
1. Sonarr/Radarr configured, something on the calendar: press Discover. The strip reads *Coming Soon · Networks ·
   Studios · Genres* (*· Request* with Seerr) and Coming Soon is selected. Down from the app bar lands on the Coming
   Soon chip; Left does nothing; Right walks Networks → Studios → Genres → Request. Press the Discover button
   repeatedly: it steps through every chip and wraps back to Coming Soon.
2. On a household (or test server) with nothing on the calendar: open Discover once and leave it, so the empty answer
   is stored. Then force-stop the app, launch it again and press Discover. It opens on Networks, and Coming Soon is
   still the first chip; one Left from Networks reaches it and it reads *Nothing scheduled*.
3. Repeat 2 on a fresh install (the calendar never fetched): Discover opens on Coming Soon.
4. Without Sonarr/Radarr: the strip starts at Networks and opens there.

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
