# Phase R365 — Small D-pad findings from the 2026-10-04 sweep

> Owner, 2026-10-04: *"Please just do a bunch of navigating around on the TV and make sure that everything feels
> smooth and makes sense UI/UX wise. And spec any big or small bugs or things that could be improved."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA (release
`1.49-11-g76f351b4`, = `main` for every screen below). Not dev-reviewed. Client only. The larger findings of the same
sweep are **R361** (a title that is gone), **R362** (Movies/Series), **R363** (Skip Intro), **R364** (My List) and
phase **269**'s 2026-10-04 amendment. Each item below is small and independent; they can be built and ticked one by one.

## Requirements

### FR-R365-1 — Search: *Clear* is reachable on a TV
**Seen:** with a query typed, the *Clear* label shows top right of Search, but no key reaches it (Up and Right from
the field do nothing); clearing takes the on-screen keyboard's delete key, one letter at a time.
**Why:** R277 FR-3 made it clickable on handsets only and left the TV deliberately (*"a focusable header control
there is a change someone should make deliberately, with a TV in front of them"*).
**Requirement (TV):** *Clear* is focusable when a query is present. **Right** from the field focuses it; OK clears the
query, puts focus back on the field (without raising the keyboard, R350 FR-13) and shows the suggestions; Left or
Down from *Clear* returns to the field. No query, no *Clear* (as today).

### FR-R365-2 — A title page: Right from the synopsis stays on the page
**Seen:** a film or series page, Down to the synopsis (R350's focus ring), **Right** → the app bar's **Search** icon.
**Requirement:** Right (and Left) on the synopsis do nothing; Up goes to the meta/genre row above, Down to the
buttons (unchanged).

### FR-R365-3 — A series page: Down after choosing a season lands on that season's first episode to watch
**Seen:** on the season pills, Right to *Season 7*, OK → Down lands on the card spatially under the pill (E03),
not on the start of the season.
**Requirement:** Down from the **open** season's pill focuses the season's primary episode: the first unwatched
episode in it (R346's counting), else E01; scrolled into view first. Down from a pill that is only focused (not
opened) is unchanged.

### FR-R365-4 — Discover · Coming Soon: Down lands on the selected chip
**Seen:** Down from the *Coming Soon* tab focuses the **Movies** chip (nearest under the tab), while **All** is the
selected one.
**Requirement:** Down from the tab strip into a chip row focuses the **selected** chip (R350 FR-5's rule for the tab
strip, applied to the chip row under it).

### FR-R365-5 — Back from Settings returns to the avatar
**Seen:** on Discover, avatar → *Settings* → Back: focus on the **Discover** tab (the page's default), not on the
avatar the menu was opened from.
**Requirement:** Back from a page opened from the profile menu (Settings, My List, Switch profile) returns focus to
the **avatar** on the page underneath.

### FR-R365-6 — A collection's Back-to-top puts focus at the top
**Seen:** a collection (channel) page with two rows, focus in the second row, **Back** scrolls the page to the top
but leaves focus on the second row's tile, now half off the bottom of the screen; the next Back leaves.
**Requirement:** the collection page's Back-to-top (R55) behaves like Home's and Movies': scroll to the top **and**
focus the app bar's active tab (or the first row's first tile when the page has no app-bar tab), so the second Back
leaves from where the viewer can see.

### FR-R365-7 — A Discover wall's browse page does not say where it came from twice
**Seen:** Discover → Networks → a network: breadcrumb *Discover · Networks*, then the subtitle *From "Discover ·
Networks" · 4 titles*.
**Requirement:** when a breadcrumb is shown, the subtitle carries only the count (*4 titles*).

## Acceptance

One D-pad walk per item on the TV path, each in a Robolectric test where the screen already has one (Search,
series page, Discover, browse), and a re-check on the Sony.

## Noted, not ours

- At 01:43 the TV's own *BootModeAppToForeground* timeout (Sony) returned the TV to its home screen while Ravilo sat
  idle on Search; Ravilo was not at fault (logcat: `Background timeout reached, starting home intent`).
