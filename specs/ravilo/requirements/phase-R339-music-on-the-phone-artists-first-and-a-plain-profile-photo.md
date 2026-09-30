# Phase R339 — Music on the phone: Artists first, and a plain photo on Profile

> Owner, 2026-09-30: *"In Music and the Browse tab, let's have it like on jellystructure. So it goes "Artists",
> "Albums", "Songs", and then all the others."* · *"When in music mode and on the profile, do not add this weird
> circle around the profile picture."*

## Status

`✓ Built` 2026-09-30 (§Build notes; not deployed, not device-tested) — written 2026-09-30 (design-authored) from `design/ravilo/Ravilo Mobile.html` (built there the same day).
**Dev-reviewed 2026-09-30** (§Dev review — one sentence struck, item 2). Number verified free (after R338). Two small changes; they hold in the desktop's compact layout
too, which is the phone (R337 FR-R337-2).

## Requirements

**FR-R339-1 — Browse's chips follow the admin.** The listening mode's Browse chip strip reads **Artists · Albums ·
Songs · Genres · Playlists · Audiobooks** — the admin's Library → Music order (287), Audiobooks absent without the books
library — and **Artists** is the chip shown on arrival. Supersedes the order in **FR-R321-6** (*Albums · Artists ·
Songs …*). Re-tapping Browse still steps to the next chip (R170's ladder) in the new order; search results keep their
own order (Songs · Albums · Artists, FR-R321-6). The desktop sidebar's Library uses the same order (R337 FR-R337-3).

**FR-R339-2 — On Profile in music mode, no ring round the photo.** When the listening mode's bar is showing and
Profile is the page, the bar's avatar has **no ring**; **R267's pill still slides under it**, the same colour as on every
other active tab (owner, 2026-09-30: *only the circle border*), and the label is lit.
Supersedes **FR-R304-1**'s white ring in music mode only; films mode keeps R304 as built.

## Acceptance

1. Music mode → Browse opens on Artists; the chips read Artists · Albums · Songs · Genres · Playlists (· Audiobooks).
2. Music mode → Profile: the pill sits behind the photo as on the other tabs, with no ring round the photo; films mode →
   Profile still has R304's ring.

## Dev review (2026-09-30, against `main` `c4258560`)

Small, and ready to build as written apart from one sentence (item 2).

1. **FR-R339-1:** reorder `MUSIC_CHIPS` (`MusicBrowseScreen.kt:82`) to `artists, albums, songs, genres, playlists`, and
   change `Dest.MusicBrowse`'s default chip from `"albums"` to `"artists"` (`RaviloApp.kt:330`). The genre drill-in still
   lands on Albums (`onChip("albums")`, `MusicBrowseScreen.kt:205`), which is right: a genre narrows albums. Artists
   already sort by title (`sorts`, `:118`), the right default for the chip you arrive on.
2. **Strike "Re-tapping Browse still steps to the next chip (R170's ladder)".** That is not what the app does. A re-tap
   on Browse **focuses the search field**: R321 FR-R321-4/-6 as built (`RaviloApp.kt:1981–1983`,
   `replaceTop(dest.copy(focusInput = true))`), and the owner's 2026-09-27 rule for the merged Browse + Search. Nothing
   steps through the chips. The FR keeps its other sentences.
3. Search results keep the built order *Songs · Albums · Artists* (`MusicBrowseScreen.kt:311/320/325`) ✓.
4. **FR-R339-2:** the ring is drawn in `ProfileDot` (`RaviloBottomNav.kt:233–240`) whenever the item is selected.
   `BottomNavCell` passes `ring = isSelected && items != MUSIC_BAR`; the bar already receives `items`. The pill is drawn
   by the bar, not the dot, so it stays. Films mode is unchanged (R304).
5. No wire change and no new strings. The desktop's compact layout inherits both changes. The desktop sidebar's list is
   R337 dev review 7.

## Build notes (2026-09-30)

Built as the dev review says; **not deployed, not device-tested** (compiled for web; the phone build shares the code).

- `MusicBrowseScreen.kt` — `MUSIC_CHIPS` reads `artists, albums, songs, genres, playlists`; `RaviloApp.kt` —
  `Dest.MusicBrowse`'s default chip is `artists`. The Songs shortcut (`chip = "songs"`) and the genre drill-in to Albums
  are unchanged.
- `RaviloBottomNav.kt` — `BottomNavCell` takes `profileRing` (`items != MUSIC_BAR`), and `ProfileDot` draws the ring only
  when lit *and* asked to. The 2 dp gap stays either way, so the photo is the same size in both modes; the pill is the
  bar's and is untouched.
