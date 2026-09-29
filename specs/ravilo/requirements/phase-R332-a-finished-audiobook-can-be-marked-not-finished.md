# Phase R332 — A finished audiobook can be marked not finished

> Owner, 2026-09-29, with an iPhone screenshot of the book page's ⋯ sheet on a finished audiobook: *"I think i found
> a bug. How can i mark this as not played"*

## Status

`✓ Built` 2026-09-29, **not deployed, not device-tested** (§Build notes) — written, dev-reviewed and built the same
day against `main` `a0f33d6b`. Number verified free (Ravilo tops at R331 on local `main`, R327 on `origin/main`).
**Amends** R323 FR-R323-3 (what ⋯ on a book holds). No backend, route or wire change; one new string.

## What the owner saw

The book page of a finished audiobook (*Finished*, the primary button *Start over*) with ⋯ open: *Start over · Mark as
finished · Go to author*. The book was already finished, so the sheet offered to do what was already done, and nothing
undid it. The only way back was *Start over*, which also starts playing, and its first heartbeat puts the book in
*Continue listening* a few seconds in instead of back to unstarted.

## Why it happens

1. `BookMenuSheet` (`AudiobookScreens.kt`) drew its rows without conditions: *Mark as finished* on every book, and
   *Start over* on a book never started. The mockup (`design/ravilo/mobile/ravilo-books.js` `bookMenu`) already hid
   *Mark as finished* on a finished book and *Start over* on an unstarted one; the build did not carry the conditions
   over.
2. R323 FR-R323-3 lists ⋯'s rows without saying what a finished book shows, and nothing drew a way back.
3. The server already has the way back. `PUT /api/tv/music/audiobook/{id}/finished` with `{"finished": false}`
   (`AudiobooksStore.setFinished`) clears `finished_at` and puts the place at part 0, 0 ms. The card rule
   (`AudiobooksTvService.card`: started = a position past 0, or finished) reads that as **not started**: *Start*, no
   ring, not in *Continue listening*. *Start over* sends the same request and then starts playback.

## Decisions

- **One row that flips, not two rows.** On a finished book the row reads *Mark as not finished* with the check lit.
  The lit check shows the state and the words say what a tap does, the same as a lit ♡. On any other book the row
  reads *Mark as finished*, unlit.
- **Not finished means unstarted**, not "where I was before I finished". The server keeps no earlier place, and a
  finished book's place is its end anyway. The admin's Listeners (281) shows the listener at 0:00.
- **Nothing plays.** If the engine holds this book (the Playing tab's *Finished · Start over*), it lets go of it, the
  same as *Mark as finished* does.
- **The words are *not finished***, not *unplayed*. The book's state word is *Finished* (`ab.finished`), and the row
  names its opposite.

## Requirements

**FR-R332-1 — ⋯ on a finished book offers *Mark as not finished*** in place of *Mark as finished*, with the check
glyph lit in the accent. *Finished* is what the page itself shows: the engine's state when it holds this book, else
the server's `position.finished`. The same sheet opened from the book player's ⋯ uses the engine's state.

**FR-R332-2 — A tap makes the book unstarted and plays nothing.** The engine lets go of the book if it holds it, then
`setAudiobookFinished(id, false)`, then the page and the shelf are read again. The page reads *Start*, the ring and
the *Finished* line are gone, the shelf cell loses its ✓, and *Continue listening* does not list the book. Bookmarks
and speed stay (the server keeps both).

**FR-R332-3 — *Start over* is on ⋯ only once the book is started** (a position past 0, finished, or held by the
engine), as the mockup draws it. On an unstarted book the primary button already says *Start*.

**FR-R332-4 — One new string**, `ab.mark_unfinished`: *Mark as not finished* · *Marker som ikke færdig* · *Merk sum
ikki liðugt*. Danish and Faroese are drafts. They go in the shipped table (`i18n/*.json`) and the mockup's
`ravilo-i18n.js`.

**FR-R332-5 — No backend, route or wire change.** The route and body are R323's, so an older server behaves the same.

## Out of scope

♡ My List on ⋯ (drawn in the mockup, never built: R323's) · restoring the place a listener had before finishing · the
TV (it has no listening mode). The Mac app gets the change for free: the sheet is common code.

## Acceptance

1. A finished book, ⋯: *Start over* · *Mark as not finished* (lit check) · *Go to author*.
2. Tap *Mark as not finished*: the sheet closes, nothing plays, and the page reads *Start* with no *Finished* line and
   no ring. The Browse ▸ Audiobooks cell loses its ✓, and Listen shows no *Continue listening* card for the book.
3. ⋯ again: *Mark as finished* (unlit), and no *Start over*.
4. From the Playing tab on *Finished · Start over*, ⋯ → *Mark as not finished*: the player lets go of the book, and
   the book page reads *Start*.
5. Danish and Faroese show their own strings.

## Dev review (2026-09-29, against `main` `a0f33d6b`)

Written with the spec. Client only, four items.

1. `BookMenuSheet` takes `started` and `finished` from its two callers: the book page passes its own values (engine
   first, then the server's position), and `BookPlayingScreen` passes the engine's `b.finished` (always started).
2. `MusicEngine.clear()` runs before the write, as it does for *Mark as finished*. Without it the page's
   `finished = here?.finished ?: …` would keep reading the engine's *Finished*.
3. No new glyph: `MusicIcon.CHECK` with `BookSheetRow`'s existing `lit`.
4. Wire (R319, backwards compatible): nothing changes. A new app against an old server and an old app against a new
   server behave as today, apart from the row.

## Build notes (2026-09-29)

Built as reviewed:

1. **FR-R332-1/2/3** — `AudiobookScreens.kt` `BookMenuSheet`: the row is `ab.mark_unfinished` (lit) on a finished book
   and `ab.mark_finished` otherwise, and it sends `!finished`. *Start over* is drawn only when `started`. The book
   page passes its `started`/`finished`; `BookPlayerScreens.kt` passes `started = true, finished = b.finished`.
2. **FR-R332-4** — `ab.mark_unfinished` in `i18n/en.json`, `da.json` and `fo.json`.
3. **Mockup** — `design/ravilo/mobile/ravilo-books.js` `bookMenu` shows *Mark as not finished* on a finished book
   (`unfinish`: back to unstarted, paused), plus the three strings in `design/ravilo/ravilo-i18n.js`.

Not deployed and not device-tested. Verified by compile (`:ravilo-ui:compileDebugKotlinAndroid` ·
`:ravilo-web:compileKotlinWasmJs` · `:ravilo-ui:compileKotlinDesktop`), `check-i18n-spelling.sh`,
`check-ravilo-strings.sh`, `check-phases.sh`, `check-mobile-css.sh` and `check-deanonymization.sh`.
