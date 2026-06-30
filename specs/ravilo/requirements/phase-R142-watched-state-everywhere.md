# Phase R142 — Watched state everywhere: tile badges + mark-played write-through

> Surfaces Jellyfin **played / unplayed** state across the whole TV UI and lets the viewer **maintain**
> it from the detail screen — a ✓ check / progress sliver on every poster tile (Home rows, channel rows,
> Movies / Series / My-List grids, search, More-Like-This) and **mark-played / mark-unplayed**
> write-through on movie + series detail (per-episode and whole-season). Builds on
> **[R07](phase-R07-detail-watched-state-api.md)** (detail PlaybackState), **[R08](phase-R08-playback-brokering-reporting.md)**
> (progress / played reporting) and **[R13](phase-R13-detail-screens.md)** (detail UI); extends the
> poster-tile component from **[R10](phase-R10-home-screen.md)** / **[R11](phase-R11-channel-and-browse-grids.md)**.
> Completes the poster-tile indicators that **[R75](phase-R75-detail-audio-language-flags.md)**
> explicitly deferred.

## Problem
Jellyfin maintains per-user **played / unplayed** and **resume** state, and R07 already ships it inside
the detail payload — but the TV only uses it in two narrow places: the **series episode list** (✓ /
progress / dim) and the **Continue Watching** row. Browsing a content row or a Movies/Series grid gives
the viewer **no at-a-glance signal** of what they have already seen, and there is **no way to mark a
title or episode played/unplayed from the TV** — the single most common library-maintenance action ("I
finished this movie on another device", "mark this episode watched so up-next is right", "I want to
re-watch from the start"). Today the viewer must open a different Jellyfin client to fix watched state.

## Goal
Show Jellyfin's played/unplayed/in-progress state on **every** poster surface, and let the viewer
**maintain** it directly from the detail screen — mark a **movie**, an **episode**, a **season**, or a
**series** played/unplayed — written **through to Jellyfin user-data** and reflected everywhere. State is
**server-authoritative**: the client renders and re-pulls it, never accumulates it.

## Current state (as-is)
- **Detail already consumes PlaybackState (R07).** `screens/DetailScreen.kt` renders the series episode
  cards' ✓ / progress / dim and the header "X of Y watched" + season bar from each episode's
  `PlaybackState`; the movie hero shows Resume vs Play from the movie's `PlaybackState`. The Continue row
  uses it too.
- **Browse tiles show nothing.** The shared poster component (`MediaCard` / `PosterTile`, used by Home
  rows, channel rows, the Movies/Series/My-List grids, search results and More-Like-This) draws art +
  label only. Its card model can carry `PlaybackState` but the **played ✓** and **in-progress bar** are
  never drawn.
- **No write path from the TV.** R08 reports playback **progress** during a session, but there is **no
  TV-facing played/unplayed toggle endpoint**, and no UI to invoke one. Detail and episode watched-state
  are effectively **read-only** on the client.
- **Mockup specifies the whole feature.** `design/ravilo/ravilo-app.js` (`tile()` played badge/sliver;
  `episodeCard()` play surface + `.ep-done` toggle; `renderDetail()` movie **Mark Watched** + series
  **Mark all**; `toggle*Watched` helpers; player-finish write-through), `design/ravilo/ravilo.css`
  (`.tile-check`, `.tile-prog`, `.tile.poster.watched`, `.ep-done`, `.dmeta-watched`, `.btn.watched-on`)
  and `design/ravilo/ravilo-data.js` (the per-title/episode watched store standing in for the API
  payload) define it; the live preview shows it on Home, the grids and both detail kinds.

## Requirements

### A. Backend — played/unplayed write-through endpoint
1. Add a TV-facing **played/unplayed write**: `PUT /api/tv/played` with `{ itemId, played }` (movie,
   **episode**, **season** or **series**), brokered to Jellyfin user-data
   (`POST` / `DELETE /Users/{uid}/PlayedItems/{itemId}`, fanned out across child episodes for a season /
   series). Device-token auth like the rest of `/api/tv/**`, scoped to the **paired user**.
2. It mutates **only user-data** — the played flag (and clears/sets the resume position) — and **never**
   library metadata (constitution: Ravilo never mutates the library).
3. The response returns the **updated `PlaybackState`(s)** and the recomputed **`SeriesProgress`** +
   resume/up-next pointer (R07 §3), so the client re-renders from the authoritative result instead of
   guessing. Marking a **season/series** played marks every child episode played and clears their resume;
   unplayed clears them; either way the series rollup is recomputed.

### B. Tiles — played ✓ + in-progress sliver on every poster
4. The shared poster component renders, from its card `PlaybackState`: a **✓ played badge** (top-right)
   when `watched`; otherwise a **thin progress bar** (bottom) when `0 < pct < 100`; an unwatched tile
   shows neither. A played poster **dims slightly**, returning to full brightness on focus.
5. This applies on **every poster surface** — Home rows, channel rows, Movies / Series / My-List grids,
   search results and More-Like-This — driven by the **same server `PlaybackState`** the detail/Continue
   surfaces use (one source of truth; no parallel client store). The ✓ badge sits **opposite** the
   existing 4K/HD corner badge so the two never collide.

### C. Detail — movie mark-played write-through
6. Movie detail gains a focusable **Mark Played** action beside Play. When the movie is played it reads
   **✓ Played** (tinted) and toggles back to unplayed; the hero meta shows a **✓ Watched** chip and the
   primary action becomes **Play Again**. Enter calls A and re-renders the screen from the returned state.

### D. Detail — series per-episode + whole-season
7. Each episode card gets a **focusable played toggle** (the ✓ / ○ control); Enter marks that episode
   played/unplayed via A, and the card's ✓ / progress / dim, the header "X of Y watched", the season bar
   and the up-next pointer all recompute from the response. Episode **playback** stays the card's primary
   focus target; the toggle is a **second focusable** (D-pad steps card → toggle → next card), with
   focus restored to the toggled control after the re-render (R45/R137).
8. The episodes header gains a **Mark all played / Mark all unplayed** control (season scope) → A at
   season scope.
9. The **Resume / up-next** pointer (R07 §3) is re-derived **server-side** after any toggle (first
   in-progress episode, else first unplayed, else last) so the hero's "Resume · E4" / "Up next" ribbon
   stays correct without client recomputation.

### E. Player finish → write-through
10. On playback end / exit the client reports the final position (R08); at **≥ ~90 %** the item is marked
    **played** in Jellyfin user-data (the existing progress report crossing the played threshold), so
    finishing a movie flips its tiles to ✓ and a series episode advances up-next **without a manual
    toggle**. Below threshold it remains in-progress (resume preserved).

## Invariants
- **Server-pushed state only** (constitution / R07): every tile badge, detail mark and up-next pointer
  renders the authoritative `PlaybackState`; a toggle **writes through and re-pulls/returns** state — the
  client never accumulates a watched count or invents played-state locally.
- **User-data only:** played/unplayed + resume are the *only* things Ravilo mutates; library metadata is
  untouched. Non-admin Jellyfin users are allowed.
- `PlaybackState` / `SeriesProgress` DTOs are defined once in `:shared` and reused by tiles, detail and
  the player episode rail — one shape, three render sites.
- **Data plane unchanged:** this is control-plane user-data; video bytes and images still stream straight
  from Jellyfin.

## Out of scope
- Hiding fully-watched titles, a "watched/unwatched" browse **filter**, or a dedicated "Watched" row —
  this phase adds **indicators + maintenance**, not new browse organisation.
- Cross-season series rollups beyond recomputing the **loaded** season(s) (long series are paged per
  R07 §6); the series-tile state derives from what the detail screen has loaded.
- "Mark played up to here" bulk affordances — only single item / whole season / whole series.
- Audio/subtitle language flags (R75 / R78) — unrelated detail metadata.

## Acceptance
- A Movies grid shows a ✓ on titles already watched and a progress sliver on in-progress ones, matching
  the same titles' detail / Continue state.
- On a movie detail, **Mark Played** flips the card to **✓ Watched** + **Play Again**, and that title's
  poster tiles show ✓ on return; toggling back clears it — both reflected in Jellyfin (verifiable in
  another client).
- On a series, toggling one episode updates its ✓, the "X of Y watched" count, the season bar and the
  up-next pointer; **Mark all played** marks the whole season and the series poster reads watched.
- Finishing a movie in the player (≥ 90 %) marks it played with no manual step; stopping mid-way keeps it
  in-progress with the resume sliver.
- All watched changes survive an app relaunch — they live in **Jellyfin user-data**, re-pulled on load;
  the client never invented them.

## Design reference
`design/ravilo/Ravilo TV.html` + `design/ravilo/ravilo-app.js` (`tile()` played badge / in-progress
sliver; `episodeCard()` `.ep-play` + `.ep-done` toggle; `renderDetail()` movie **Mark Watched** action +
hero **✓ Watched** chip + series **Mark all played**; `toggleItemWatched` / `toggleSeasonWatched` /
`toggleEpisodeWatched` / `syncSeriesItemState`; player `restoreFocus` write-through),
`design/ravilo/ravilo.css` (`.tile-check`, `.tile-prog`, `.tile.poster.watched`, `.ep-done`,
`.dmeta-watched`, `.btn.watched-on`, `.dsec-actions`), `design/ravilo/ravilo-data.js` (`watched` store —
the per-title / per-episode `PlaybackState` the detail API serves), `design/ravilo/ravilo-i18n.js`
(`mark_watched` · `watched` · `play_again` · `mark_all_watched` / `mark_all_unwatched` · `watched_of` ·
`toast_*`, en · da · fo). Source: `ravilo-ui/.../screens/DetailScreen.kt` + `screens/HomeScreen.kt` +
the shared poster-tile component, `shared/.../tv/Models.kt` (`PlaybackState`, `SeriesProgress`),
`src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` (Jellyfin user-data read + played
write), `TvRoutes.kt` (`PUT /api/tv/played`). Related: R07 (detail watched-state API), R08 (playback
brokering + reporting), R13 (detail UI), R10 / R11 (home + browse grids), R75 (deferred poster-tile
indicators).
