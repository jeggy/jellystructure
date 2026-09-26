# Phase R310 — A Discover wall with nothing on it has no tab

> Owner, 2026-09-26: *"Some instances may not have the best metadata and end with no entry actually
> having "Network" or "Studio" applied to it, in these cases we should not show the tab at all under the
> discover page in ravilo. So if networks is missing, then we just default on selecting the next tab and
> so on."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Client (`ravilo-ui`: TV, phone, web) plus one small additive backend answer. **Every choice
decided the same day**: the owner handed the calls over (*"You just decide for me. We want all best
solutions for everything"*). See *Decisions* at the end. **Numbering:** verified against `STATUS.md` the
same day — Ravilo taken through **R307**.

Supersedes **R243 FR-R243-1**'s "the three taxonomy segments are never gated". It also supersedes
**R268**'s consequence that Discover *"always lands on Networks now"*. R268's own rule (one declared
order, gating only filters it) is kept, and this phase relies on it.

## What is wrong

`discoverSegments()` (`NavItems.kt`) gates Coming Soon on Sonarr/Radarr and Request on Seerr. The three
library walls (Networks · Studios · Genres) are always in the bar, and Discover always lands on
Networks. A wall with no values for this viewer is still a chip. Opening it shows the header
*"0 networks · 0 titles"* and R243's one sentence *"Nothing here for this profile yet"*
(FR-R243-8). That happens whenever:

- **the metadata never supplied it.** A network exists only when a series carries one, and a studio
  only when an item does. A library matched poorly, or not against TMDB at all, can have none.
- **the library has no such kind.** A films-only library has no networks, ever.
- **the viewer cannot see any of it.** Every wall is counted over what *this* device may see
  (`mediaStore.liveItems(device)`: library allow-list, tag policy). A profile limited to one library can
  have an empty wall while the household's other profiles do not.

So "empty" is per viewer, and only the server can say it (render-never-compute).

Today the bar cannot know either. It is drawn from two booleans `HomeStore` fetches at Home load
(`getDiscover().available`, `getUpcoming().enabled`). The walls' own data (`GET /api/tv/facets`) is only
fetched once Discover is open (`TaxonomyStore`, R262 FR-R262-7). By then the bar is already drawn and the
landing segment already chosen.

## Requirements

**FR-R310-1 — A wall with no values for this viewer has no chip.** Networks, Studios and Genres each
appear in the segment bar only when the server says that wall has at least one value for this viewer.
The declared order (R268 FR-R268-1: Networks · Studios · Genres · Coming Soon · Request) is untouched:
gating still only **filters** that one list, in `discoverSegments`, and never reorders it. Coming Soon
and Request keep their own gates, unchanged.

**FR-R310-2 — Discover lands on the first chip that is left.** `defaultDiscoverSegment` stays "the first
of `discoverSegments`". With no networks it lands on Studios, with neither on Genres, then Coming Soon,
then Request. The same list drives the Discover nav button's "step to the next segment"
(`nextDiscoverSegment`), so a hidden wall is never stepped to either.

**FR-R310-3 — The bar knows before it is drawn: a small answer, fetched with the other two.** A new
route answers which walls hold anything for this device:
`GET /api/tv/facets/summary` → `{"networks": 66, "studios": 573, "genres": 41}`, the three value counts
and nothing else. It is read from the **same per-viewer facets cache** (`BrowseService.facets`,
keyed on the user and their visibility scope) that fills the walls, so a chip and its wall cannot
disagree. `HomeStore` fetches it beside the two flags it already fetches (`refreshDiscoverAvailable`,
`refreshUpcomingAvailable`), on Home load and on every refresh. The client shows a wall when its count
is above zero and does no other arithmetic.

**An older backend** has no such route and answers 404. It is a separate path rather than a query
parameter on `/api/tv/facets` because an old server would ignore the parameter and answer the full
facets object, whose `networks` is a list, not a count. The client treats a 404 or any failure as "no
answer" and keeps today's behaviour, all three walls shown. A new app on an old server loses nothing.

**FR-R310-4 — A change while Discover is open.** The answer can change: a scan adds studios, or the
admin narrows a viewer's libraries. When it is refreshed (Home load, live-config push), the bar
recomposes from it. If the segment on screen has just lost its chip, the frame moves to the first
available segment in place (`replaceTop`, no slide) with focus on that chip, the way an unavailable
segment is handled. A wall that gains values appears in its declared place, and focus stays where it is.

**FR-R310-5 — Nothing at all to discover still opens.** If every segment is filtered out (no walls with
values, no Sonarr/Radarr, no Seerr), the Discover item stays in both navigation bars. The TV's top bar
and R267's five-item phone bar keep their geometry. Discover opens with no chips and FR-R243-8's
existing sentence in the content region. `defaultDiscoverSegment` must not call `.first()` on an empty
list: today that cannot happen, and this phase is what makes it reachable. The landing segment
becomes nullable (`Dest.Discover.segment: DiscoverSegment?`), and the frame renders the empty sentence
for `null`.

**FR-R310-6 — The wall's own empty sentence stays.** FR-R243-8's *"Nothing here for this profile yet"*
remains for the one case the gate cannot rule out: the summary said yes, and the wall loaded empty
because the library changed in between. It is a safety net, no longer the normal way an empty wall is
shown.

**FR-R310-7 — Tests.** `DiscoverSegmentOrderTest` gains:

- each wall gated off alone and in pairs: order kept, landing on the first left;
- all three off with and without the integrations: Coming Soon / Request / nothing;
- `nextDiscoverSegment` never returning a hidden wall.

Backend: the summary equals the sizes of the three lists `facets(device, null)` returns for the same
device.

## Non-goals

- Hiding Coming Soon when Sonarr/Radarr is configured but nothing is coming, or Request when Seerr has
  nothing to offer. Those tabs are about integrations, and their empty states are their own.
- Hiding a single tile, or a wall with *few* values. One network is a wall.
- The admin side. The admin's Metadata page lists what exists, and an empty list there is a work item,
  not a navigation question.

## Acceptance

1. A viewer whose visible library has series but no networks (for example, a profile limited to a
   library whose series never matched on TMDB): Discover's bar reads *Studios · Genres · …*, and
   pressing Discover lands on Studios with its chip focused.
2. A films-only profile: no Networks chip.
3. The owner's own viewer on production: unchanged. All three walls present, landing on Networks.
4. While Discover sits on Networks, a refresh reports `networks: 0` (in a test, the summary stubbed to
   change between two answers): the frame moves to the next chip, with focus on it. Nothing crashes, and
   no chip is left that opens a *"0 networks"* page.
5. The same on the phone's Discover tab and in the web app.
6. A new app against a backend without the summary: all three walls shown, as today.

## Decisions (2026-09-26, delegated by the owner)

1. **The summary route, not the Home feed and not a prefetch.** Prefetching the full facets at Home would
   cost 123 KB on every Home load, most of it 2 432 tags the walls never draw, on a TV whose cold start
   took four phases (R210–R213). An additive field on the Home feed saves one small request but ties
   the gate to the Home payload's own cache, which is keyed and invalidated for different reasons. That
   is how two surfaces come to disagree. The summary is answered from the very cache the walls are
   drawn from, and it follows the pattern `HomeStore` already uses for Discover's other two gates.
2. **Discover never disappears from the navigation.** A household with nothing to discover still gets
   the item and one sentence, so the TV's top bar and the phone's five-item bottom bar never change
   shape (R267's geometry, R170's fixed tabs). A nav item that comes and goes with the library would
   move every item after it under the viewer's thumb.

## Dev review (2026-09-26, against `main` `0e5e434f`)

Every seam named is where the spec says. Six items.

1. **The server route.** `get("/tv/facets")` (`TvRoutes.kt:617-621`) matches that path exactly, so
   `get("/tv/facets/summary")` sits beside it without shadowing. It answers from
   `browseService.facets(device, null)`, the same cached `FacetsEntry` (`BrowseService.kt:257-271`): three
   list sizes in a `@Serializable` class (`networks`, `studios`, `genres`). No new computation, no new
   cache.
2. **The client fetch.** `HomeStore` fetches its two Discover flags in `refreshDiscoverAvailable` /
   `refreshUpcomingAvailable` (`HomeStore.kt:177-183`), called from the three load and refresh paths
   (`:141-142`, `:153-154`, `:210-211`). A third, `refreshTaxonomyWalls`, sits beside them and exposes a
   `StateFlow<Set<DiscoverSegment>?>`. `null` means *no answer* (404 or failure), and FR-R310-3 then
   shows all three.
3. **One filter, as R268 requires.** `discoverSegments(upcomingAvailable, discoverAvailable)`
   (`NavItems.kt:100-107`) gains the walls set, and its `else -> seg in TAXONOMY_SEGMENTS` becomes
   *in the walls set, or no answer*. `defaultDiscoverSegment` and `nextDiscoverSegment` keep deriving from
   it (`:120-129`).
4. **The nullable landing.** `Dest.Discover.segment` (`RaviloApp.kt:264`) becomes `DiscoverSegment?`.
   `defaultDiscoverSegment` has **nine** call sites in `RaviloApp.kt` (`:1077`, `:1122`, `:1166`, `:1260`,
   `:1279`, `:1419`, `:1468`, `:1591`, `:1746`). They keep calling it; only the type widens.
   `DiscoverScreen` (`DiscoverScreen.kt:60`) renders FR-R243-8's sentence in the content region for
   `null`.
5. **A wall vanishing while open (FR-R310-4)** is handled once, in `RaviloApp`'s `is Dest.Discover`
   branch (`:1308`): when `dest.segment !in segs`, `replaceTop(Dest.Discover(name, segs.firstOrNull(),
   focusSegment = true))`. That is the same `replaceTop` a chip press uses (no slide).
6. **Tests.** `DiscoverSegmentOrderTest` (`ravilo-ui/src/commonTest/.../DiscoverSegmentOrderTest.kt`)
   gains FR-R310-7's cases. The backend test compares the summary to `facets(device, null)`'s list sizes
   for a scoped device and an unscoped one.

**Net effect.** One small route, one fetch beside two existing ones, one filter parameter, one nullable
type, one re-selection effect. No new cache.
