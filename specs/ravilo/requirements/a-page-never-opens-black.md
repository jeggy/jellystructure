# A page never opens black, and a page seen before opens at once

Task: DEB-4091

> Owner, 2026-11-09, with a screenshot of the Mac app on Music › Albums: the sidebar and the mini player are
> drawn, and the whole page to the right of the sidebar is empty dark background. *"Too often I see just a black
> screen. Let's add more loading states to the app (both desktop and mobile) and preferably some small cache as
> well. So it's even faster when you are navigating something you already seen before etc."*

## What Ravilo does today

Every screen below is in `ravilo-ui/src/commonMain`, so the same code runs on the Mac, Linux, the phone, the web
app and the TV. Desktop and phone differ only in runtime layout branches.

**The film side already has loading views.** Home shows `HomeLoadingShell` and the film and series pages show
`DetailLoadingShell` (`components/Shimmer.kt`). Each of them has a `LoadErrorState` with Retry. The film Library
on desktop (`BrowseScreen`) and the phone's seeded grids (`SeededBrowseScreen`) show one centred *Loading* line.

**The music side mostly has none.** Music state is `Load { Loading; Ready; Failed }` (`music/MusicCommon.kt`).
Several screens draw the same thing whether the state is `Loading` or `Failed`:

| Surface | While loading | When the load fails |
|---|---|---|
| Albums, Artists, Songs (`MusicBrowseScreen.listChip`) | an empty 120 dp box | the same empty box, until the page is opened again |
| Genres | an empty `FlowRow` | the same |
| Playlists | an empty 80 dp box and *New playlist* | the same |
| Listen | nothing (`Load.Loading -> Unit`) | `LoadErrorState` with Retry, and a reload when the server answers again (R345) |
| Album page, artist page | the back button only | the *no music* empty text, so a server error reads as an empty library |
| Audiobook shelf, book page, author page | an empty box or the back button | the *no audiobooks* empty text |
| Playlist page | its header with no rows | the same |

A failed browse list also never tries again. `MusicPagedList` keeps no reason for the failure, and nothing calls
`load()` again until the page is opened again. Listen alone listens to `serverOpens` (R345).

**Revisits within a run are already instant.** Each screen's store is kept for the life of the app
(`keptStore`, R40), so going Back, or reopening a page seen earlier in the same run, shows the last data at once and
refreshes it quietly. What a viewer waits on is **the first visit to a page**, and **every page after a cold start**,
because nothing outlives the process except the Home feed (R212's `HomeSnapshotCache`: one file per user, 7 days,
2 MB cap, cleared on sign-out). A phone app is cold-started far more often than the Mac app, because Android kills
it in the background.

**The screenshot shows more than the empty box.** On the desktop, Music Browse always draws its page title
(*Albums*, 30 sp) and the sort pill above the list, whether the list has loaded or not. The screenshot has no title.
So something blanked the whole page panel, not only the list. That cause is not found in the code. It is open
question 1, and QA reproduces it before the build is called done.

## Prior art

- **Netflix, Spotify, Apple Music** draw a skeleton the shape of what will arrive (cover squares, title lines,
  track rows). The page never shows a blank area while it loads, and the skeleton keeps the page from jumping when
  the content lands.
- **Spotify and Apple Music** reopen the last-seen library lists at once after a relaunch and refresh them
  quietly. Their offline caches are much larger. The part used here is only *show the last list first*.
- **Ravilo's own R212** does exactly that for Home. This spec reuses its shape for the music pages instead of
  adding a second kind of cache.

## Options considered

1. **Loading and error views on every surface, plus a small saved copy of the music entry pages (chosen).** This
   removes the blank state everywhere, and it makes the pages a viewer opens first after a launch instant. The
   cost is one more small file per user, on the same terms as R212's.
2. **Loading and error views only.** This is the cheapest option and removes every blank state. A cold start still
   waits on the network for every music page, and the owner asked for the cache too.
3. **An HTTP cache in the API client** (Ktor `HttpCache` with a disk store). It is generic, but it needs
   `Cache-Control`/`ETag` from the backend on every route. It still waits for a revalidation unless it serves stale
   data, and it caches far more than the pages a viewer returns to. It costs more and gives less control than option 1.

## Requirements

**FR-1 — A loading page shows the shape of what is coming.** Every surface in the table above shows a skeleton,
in place of its content, until its first answer arrives. The skeleton uses `Shimmer.kt`'s brush:

- **Albums and Artists:** the grid's own geometry. These are cover squares (circles for artists) in the same
  column count and width as the loaded grid, each with one title line, plus a second line for albums. The skeleton
  fills at least the visible height of the page.
- **Songs, playlist page:** track rows at the loaded row height, with a cover square on Songs.
- **Genres:** chips in the loaded chips' height.
- **Playlists:** rows. The *New playlist* button stays drawn as it is today.
- **Listen:** a section title line, then one row of cover squares, repeated for as many sections as fill the page.
- **Album, artist, audiobook book and author pages:** the page's header (a cover or photo block and two title
  lines), then track or book rows.
- **Audiobook shelf:** the shelf's own cover grid.
- **Film Library (desktop) and the phone's seeded grids:** a grid of `TileShimmer` in the loaded grid's columns.
  This replaces the centred *Loading* line. R205's centring rule then has nothing left to centre.

Unchanged:

- The desktop page title and sort pill, and the phone's chips and count line, stay drawn above the skeleton. Only
  the content region is a skeleton.
- DEB-2204 FR-5's fade-in runs when the content replaces the skeleton, as it already does over the empty box.

**FR-2 — A skeleton respects reduced motion and a hidden window.** Under `LocalReducedMotion` the skeleton is drawn
in its base colour with no moving sweep, as DEB-2204 FR-4 requires of all of this app's motion. On the desktop, the
sweep stops while the window is minimised or hidden (`rememberAppOnScreen()`), so a skeleton never keeps a hidden
app drawing frames (FR-R383-3).

**FR-3 — A failed page says so and offers Retry.** When a surface's load fails and it has nothing loaded to show, it
shows `LoadErrorState`, worked out by `loadErrorKindOf(throwable)` (R280), with Retry. This replaces the empty box
and the *no music* or *no audiobooks* text. An empty-library text shows only when the server answered with nothing.

- `MusicPagedList` keeps the failure, as `MusicLoader.lastFailure` does, so the browse lists can say what went wrong.
- A failed refresh of a page that already has data keeps showing the data. This is today's rule in `MusicLoader` and
  `MusicPagedList`, and it is unchanged.
- Retry puts the surface back to its skeleton and loads again.
- On a list with a page title above it, the error view sits under the title in the content region. It does not
  replace the page.

**FR-4 — A failed page tries again when the server is back.** Every surface in the table that is showing FR-3's error
view loads again by itself when `serverOpens` ticks, as Listen already does (R345, acceptance 2). This covers every
kept store whose state is `Failed`, whether that page is visible or not, so going Back to it finds it loaded.

**FR-5 — The music entry pages survive a restart.** On a cold start, the music pages a viewer opens first are drawn
from a saved copy and refreshed quietly behind it. This is R212's idea, applied to music:

- **What is saved, per user:**
  - Listen's answer;
  - the first page of Albums, Artists and Songs, each for the sort it was last loaded with;
  - Genres;
  - Playlists.

  It is one overwritten file or entry per user, never a growing history. It uses the same per-platform store as
  `HomeSnapshotCache`, under its own name.
- **When:** a surface's store writes its part after each successful first-page answer. A *load more* page is never
  saved.
- **Seeding:** a store created on a cold start, for a user who has a saved copy younger than 7 days, starts
  `Ready(saved)` instead of `Loading`. A browse list is seeded only when the saved copy's sort is the sort being
  asked for. It then loads quietly. The fresh answer replaces the saved one, and the list's paging starts from
  page 0 of the fresh answer. A failed quiet refresh leaves the saved copy on screen, as FR-3's second bullet says.
- **Bounds:**
  - 2 MB per user. A write that would exceed the cap is skipped and the previous copy is kept.
  - A copy older than 7 days is treated as absent.
  - Images are never saved. Covers stay in Coil's own disk cache, which is already sized per platform.
  - The copy is cleared wherever `HomeSnapshotCache.clear` is called, on sign-out and user removal.
- **Format:** the saved copy is the server's own `MusicHome` / `MusicList` / genre and playlist answers, read with
  `RaviloWireJson`, so a copy written by a newer build still loads (R318). A copy that fails to decode is treated as
  absent and never breaks the page.
- **No fade on a seeded page**, as DEB-2204 FR-5 already says for R212's Home.

**FR-6 — Not reached.** The Tizen screen app and the Chromecast receiver do not compile `ravilo-ui`. Nothing in them
changes.

## Invariants

- **No wire change.** The client stores answers it already receives, and the backend is untouched.
- No surface listed above shows only background while it waits or after it fails.
- The first visit to a page that was never saved still waits on the network, behind FR-1's skeleton.
- Within a run, revisiting a page stays as it is: the kept store's data at once, then a quiet refresh.
- No derived or optimistic state. The saved copy is the server's last answer, shown as the server gave it.

## Out of scope

- Pre-loading pages the viewer has not opened (for example, warming Albums when Music mode opens). That costs every
  viewer extra Jellyfin requests to save some of them one wait. It can follow if the first visit still feels slow
  once FR-1 ships.
- Saving the album, artist, playlist and audiobook pages, or the film pages, across a restart. They stay
  memory-only, kept for the run as today.
- A bound on the number of kept stores in memory. They hold small lists, and R383's memory budget is measured
  separately.

## Acceptance

1. **First visit.** On a Mac and on a phone, with the backend reached slowly, opening Albums, Artists, Songs, Genres,
   Playlists, Listen, an album page, an artist page and the audiobook shelf each shows the skeleton under the page's
   title, then the content. No surface shows only background.
2. **Failure.** With the backend unreachable, each of those surfaces shows the error view with Retry, never an
   empty-library line. With the backend back, the page loads by itself without a relaunch or a Retry press (FR-4).
3. **Restart.** Open Listen and Albums, quit the app, start it again, and open the same two pages. They draw at once
   with the last lists and no skeleton, then update if the server's answer changed. On a phone, *quit* is a
   force-stop.
4. **Sign-out.** After signing a user out, that user's saved music copy is gone.
5. **Reduced motion.** With reduced motion on, the skeleton shows and does not sweep.
6. **The owner's screenshot.** QA's reproduction of the whole-panel blank (open question 1) no longer reproduces, or
   it has its own task.

## Open questions

1. **Why the screenshot has no page title.** The desktop page draws *Albums* and the sort pill above the list in
   every load state, and the sidebar shows Albums selected with the rail search closed, so the title should have been
   there. Candidates are a screen transition caught mid-fade, a long main-thread stall while the page composes, or a
   window that came back from sleep without a redraw. A QA reproduction on the Mac decides which, using the test
   driver's screen read (R383 FR-R383-6) and `-Dravilo.debug.frames`. If the cause is outside this spec, it becomes
   its own task. It does not block FR-1 to FR-5.

## Scope

Client only, in `ravilo-ui` commonMain plus the snapshot store's three platform actuals (`desktopMain`,
`androidMain`, `wasmJsMain`, beside `HomeSnapshotCache`'s). It extends R212 (the snapshot pattern), R345 (reload on
`serverOpens`), R280 (`LoadErrorState`) and DEB-2204 FR-5 (the fade-in over a loading view). It supersedes R205's
*Loading* line on the two film grids.
