# Phase R364 — My List shows what you just added

## Status

`Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA (release
`1.49-11-g76f351b4`). Dev-reviewed 2026-10-04 against `main` `5210045a` (see the end; still `Planned`). Client only (`BrowseScreen.kt` / `BrowseStore`).

## What was seen

Profile → *My List* (empty, *0 titles*) → Back → open a series → **+ My List** (the button turns to *− My List*) →
Profile → *My List*: still **0 titles**. Leaving and re-opening My List does not help; only restarting the app does.
The server is right: `GET /api/tv/browse?kind=mylist` with the TV's own device token returned the series at once,
and Jellyfin's favourites held it.

**Cause (`main` 76f351b4).** My List (and *All*) render through `BrowseScreen` with a kept `BrowseStore`
(`RaviloApp.kt:1791`, key `browse:<name>:MY_LIST`). `BrowseScreen` loads with
`LaunchedEffect(kind) { if (store.activeKind != kind) store.load(kind) }`: the kept store's `activeKind` already
equals `MY_LIST` after the first visit, so it **never loads again** for the life of the process. The Movies/Series
seeded store refreshes silently on every arrival; this one was never given that.

Also seen on the empty page: it says only *0 titles*, nothing about how a title gets there; focus sits on the app
bar's **Home** tab (one OK leaves); and the *My List* heading is drawn in the platform's default font, unlike every
other page title (Space Grotesk).

## Requirements

### FR-R364-1 — Refresh on every arrival
Every arrival at My List (from the profile menu, or a Back from a title) refreshes it silently: the list on screen
stays until the new one arrives (no *Loading…* flash, R212's rule), and R139's restore runs on the refreshed list
(with R361's rule if the restored title was removed from the list). The same for the *All* browse page.

### FR-R364-2 — A change made in the app shows at once
Adding or removing a title from My List anywhere in the app updates a kept My List store immediately (the way
`WatchedBus` patches watched ticks, R147): an add inserts the title at the front (the list's sort is *recently
added*), a remove drops it.

### FR-R364-3 — The empty page says what to do
An empty My List shows one line under the count: *Add a title with **+ My List** on its page.* (en/da/fo; da/fo
drafts, the shipped table wins). On a TV, focus on arrival stays in the app bar but on the **avatar** (the control My
List was opened from), not on Home.

### FR-R364-4 — The heading in the app's display font
*My List* (R204) uses the same font, size and weight as the other page titles (*Movies*, *Discover*). The TV's
*Settings* heading likewise.

## Acceptance

1. Empty My List → Back → a title → + My List → Profile → My List: the title is there, without a restart.
2. On My List, open the title → − My List → Back: it is gone and focus is on its neighbour (R361).
3. Add from a phone while the TV's My List was visited earlier: the next arrival on the TV shows it.
4. Empty My List shows the hint; focus is on the avatar; OK reopens the profile menu, not Home.
5. Store tests: a kept store refreshes on every arrival without a *Loading…* flash; 1, 2 and 4 are walked key by
   key. See *Tests*.

## Tests

`fakeTvApiClient` is JVM (OkHttp) and lives in `androidUnitTest`, so the store tests go there too (plain JUnit, no
Robolectric), not in `commonTest` as the review wrote. Robolectric conventions as in R361's *Tests*.

**Store (`BrowseStoreRefreshTest`, new, `androidUnitTest/…/screens/`).** The fake counts calls to
`/api/tv/browse?kind=mylist` and answers 0 titles, then 1. `BrowseStore` gets an injectable dispatcher (or the test
polls `state` with a timeout, review item 9).
- FR-1: `refresh(MY_LIST)` twice on one store → two fetches, the second state holds the title, and the recorded
  states after the first `Loaded` contain no `Loading`.
- Review item 2: a first `refresh(ALL)` on a fresh store reaches `Loaded` (today it stays on `Loading`).
- A kind change passes through `Loading` once; a refresh resets paging to page 1.
- FR-2: no test while the review's drop stands (constitution invariant 4); acceptance 1–3 rest on FR-1.

**Robolectric walks (`MyListFocusTest`, new, `…/screens/`, `w960dp-h540dp-television`, `BrowseScreen(MY_LIST)` on a
kept store).**
- Acceptance 1: the fake answers empty → *0 titles*; leave; the fake now answers one stand-in; return → the title is
  shown, on the same store.
- Acceptance 2 (R361's grid rule, review item 4): two titles, OK on the first, the fake drops it, return → the other
  title is focused, not the app bar.
- Acceptance 4 (FR-3): empty → the `browse.mylist_empty_hint` text is shown, the focused node is the avatar (a new
  `APP_BAR_AVATAR_TAG`), OK → the test's `onProfile` runs, `onNavSelect` does not.
- R362 FR-5 on `BrowseGrid`: 12 titles, Right ×3, Down → the 10th.

**Strings.** `ProfileStringsTest`'s key list gains `browse.mylist_empty_hint` (resolves in every language, names no
Jellyfin); the hint takes the button's label from the detail button's own key, so no separate check.

**TV only (manual, D-pad on the TV).** Acceptance 1 against the real server (Jellyfin favourites); acceptance 3 (add
from a phone, then open My List on the TV); FR-4 by eye: *My List*, *Settings*, *Movies* and *Search* headings in the
same face and size.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `BrowseScreen`/`BrowseStore`, `RaviloApp`'s `Dest.Browse` routing, `DetailStore.setFavorite`,
`WatchedBus`, `AppBar` and the page headings across the screens. The cause is confirmed. FR-R364-2 conflicts with the
constitution and should go; FR-R364-4 needs one heading style named. Nine items, none for the owner.

1. **Cause confirmed.** `BrowseScreen.kt:210` loads only `if (store.activeKind != kind)`. The kept store
   (`RaviloApp.kt:1791`) keeps `activeKind = MY_LIST` after the first visit, so it never loads again.
   `BrowseStore.load` also always sets `Loading` first (`BrowseScreen.kt:127-130`), so there is no silent path to
   reuse yet.

2. **"The same for All": nothing to see today, but the bug is there.** `BrowseStore.activeKind` starts as `ALL`.
   A first visit to `ALL` through `BrowseScreen` would therefore **never load at all** and stay on *Loading…*. On the
   TV nothing opens `Dest.Browse(ALL)`. The phone sends `ALL` to the seeded store (`RaviloApp.kt:1709-1719`). The fix
   below covers it for free.

3. **FR-R364-1.** Replace the guard with an arrival refresh: `LaunchedEffect(store, kind) { store.refresh(kind) }`.
   `refresh` keeps a `Loaded` state on screen until the new page 1 lands, the same pattern as
   `SeededBrowseStore.load` (`SeededBrowseScreen.kt:232-236`). It goes back to `Loading` only from nothing or on a
   kind change. It resets paging to page 1. My List is short, so a viewer deep in a long list is not a real case here.

4. **The restore runs before the refresh lands.** `BrowseGrid`'s R139 restore (`BrowseScreen.kt:421-425`) runs
   once, on the list already on screen. If the refresh then drops the focused title (acceptance 2: − My List, Back),
   the title leaves while it is focused. That is the grid twin of R361 FR-R361-3, and grids have no such handling
   today (only `StaticContentRow` does). The grid needs the same rule: focus `fallbackIndex(oldIndex, newSize)`'s
   tile, from R361's review item 4. Say so here or in R361 FR-4. Otherwise acceptance 2 relies on Compose's recovery.

5. **FR-R364-2 should be dropped.** Inserting a title at the front on the client is derived catalog state. The
   constitution rules it out: "The TV renders server-pushed state; it holds no derived catalog state" (§ Watched-state,
   invariant 4). It is also not buildable as written: `setFavorite` returns only the title's play state
   (`DetailStore.kt:108-113`, `:213-217`), not a card to insert. And it is not needed. My List can only be seen by
   arriving at it, which FR-1 refreshes, including the Back from the title page. Acceptance 1–3 all hold with FR-1
   alone. If a future surface shows My List without an arrival (a Home row), give it a `home_changed`-style push
   from the server, not a client patch.

6. **FR-R364-3, the avatar.** The app bar puts `navFR` at `activeNav.coerceIn(0, …)` (`AppBar.kt:146`). My List
   passes `activeNav = -1`, so arrival focus lands on index 0, Home. The avatar's requester is private to the bar
   (`AppBar.kt:115`). Add a parameter (`entryFocus = AVATAR`, or an `avatarFocusRequester` the screen passes in) and
   request it in `BrowseScreen`'s arrival effect for `MY_LIST`. R365 FR-R365-5 needs the same parameter; build it
   once.
   The hint is a new key, `browse.mylist_empty_hint`, in the three `i18n/*.json`. It shows only when
   `results.total == 0`. Take the button's label from the same key the detail button uses, so the hint never names a
   button that reads differently.

7. **FR-R364-4: "the same as the other page titles" is not one style today.**
   - *Movies*, *Series* and every seeded browse page: Sora 28 bold (`SeededBrowseScreen.kt:511`);
   - *Discover*: Space Grotesk 22 bold;
   - *Search*: Space Grotesk 28 bold;
   - *Settings* on a TV: the default font at 32 (`SettingsScreen.kt:285-286`; Space Grotesk on desktop only);
   - *My List*: the default font at 24 (`BrowseScreen.kt:279-286`).
   The design sets headings in Space Grotesk (`design/ravilo/ravilo.css`). Name it: one `PageTitle` composable,
   Space Grotesk bold 28 (Search's), used by My List, Settings and the seeded browse header. That also moves
   *Movies*/*Series* from Sora to Space Grotesk. That is a visible change and it matches the design. Discover's 22
   can follow or stay, as long as the phase says which.

8. **Status line.** Client only still holds, but the files are `BrowseScreen.kt`, `AppBar.kt` (item 6), a new
   `PageTitle` (item 7) and the three `i18n/*.json`.

9. **Tests.**
   - Store test (`commonTest`, `fakeTvApiClient`): a kept `BrowseStore` refreshes on a second arrival and never
     passes through `Loading` while `Loaded`; a first `ALL` arrival loads. `BrowseStore` runs on
     `Dispatchers.Default`, so give it an injectable dispatcher or poll the state with a timeout.
   - Robolectric: acceptance 1 (the fake answers empty, then with the title) and 4 (focus on the avatar), in a small
     `MyListFocusTest` beside `BrowseFocusTest`.
   - Device only: acceptance 3 (an add from the phone).
