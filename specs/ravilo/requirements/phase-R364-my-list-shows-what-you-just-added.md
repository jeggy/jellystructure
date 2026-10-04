# Phase R364 — My List shows what you just added

## Status

`Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA (release
`1.49-11-g76f351b4`). Not dev-reviewed. Client only (`BrowseScreen.kt` / `BrowseStore`).

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
5. Store tests: a kept store reloads on arrival; an add/remove event patches it.
