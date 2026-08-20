# Phase R204 — My List page has no on-screen heading (bug fix)

> Found during the same 2026-08-18/20 screenshot session (`presentation/observed-issues-2026-08-18.md`,
> item 7). `screenshots/56-my-list.png` shows only `8 titles` at the top of the grid — nothing on
> screen says "My List". Every other browse-family page gets its identity from the nav bar (the tab
> label stays visible whether or not it's the active tab); My List lost that when R170 moved it out
> of the tab row into the avatar's ProfileMenu, and nothing replaced it.

**Status:** Implemented.

## Bug report
Self-found while auditing screenshots. Confirmed by code inspection, not just the screenshot.

## Investigation
`BrowseScreen.kt` (shared by Movies/Series/All/My List) renders exactly one heading-shaped text in
its `Loaded` state — the results count (`browse.titles`/`browse.title_one`) — no page title above it,
for any `kind`:
```kotlin
// Count + grid
Text(
    if (s.results.total == 1) str("browse.title_one")
    else str("browse.titles", mapOf("count" to s.results.total.toString())),
    ...
)
```
For `BrowseKind.MOVIES`/`SERIES`/`ALL`, the page's identity comes from `AppBar`'s `navItems`, which
render every tab's label regardless of which one is `activeNav` (confirmed in `AppBar.kt` — labels
aren't conditionally hidden, only the active tab gets the selected-state styling). So "Movies" reads
on screen via the nav bar even though `BrowseScreen.kt` itself never prints it.

`BrowseKind.MY_LIST` is different: `activeNav = -1` (R170's own comment: *"My List moved out of the
section-tab row into the avatar's ProfileMenu, so it no longer has a nav index to highlight"*), and
critically **My List was also removed from `navItems` entirely** — it isn't a tab anymore, so no nav
label reads "My List" either. Combined with `BrowseScreen.kt` never printing a page title of its own,
arriving at My List (from the profile menu) leaves nothing on screen identifying the page — only the
count.

## Requirements

### FR-RV-R204-1 — My List prints its own name
When `kind == BrowseKind.MY_LIST`, render `str("nav.my_list")` ("My List") as a heading above the
results-count line, in `BrowseScreen.kt`'s `Loaded` branch. Movies/Series/All are unaffected — their
identity via the nav-bar tab label is untouched, so no page-title text is added for those kinds
(avoids a redundant second "Movies" appearing above "Movies" already highlighted in the nav bar).

## Invariants
- Every page a viewer can land on says what it is somewhere on screen, either via a highlighted nav
  tab or an explicit heading — My List had neither.

## Out of scope
- Adding page titles to Movies/Series/All too — they already have one (the nav-bar tab label); adding
  a second, redundant heading there is unrequested scope creep.
- The seeded-browse ("→ See all") breadcrumb/subtitle pattern described in CLAUDE.md's Ravilo section
  — that page is `SeededBrowseScreen.kt`, a different composable from `BrowseScreen.kt`, not touched
  here.

## Source references
- Bug site: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/BrowseScreen.kt`
  (`Loaded` branch, count `Text`).
- Nav removal this exposed: `BrowseScreen.kt:217-224` (R170 comment), `RaviloApp.kt:1103`
  (`onMyList = { ... push(Dest.Browse(BrowseKind.MY_LIST, ...)) }`).
- Evidence screenshot: `presentation/screenshots/56-my-list.png`.
