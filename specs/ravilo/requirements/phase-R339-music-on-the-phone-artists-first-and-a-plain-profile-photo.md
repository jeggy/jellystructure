# Phase R339 — Music on the phone: Artists first, and a plain photo on Profile

> Owner, 2026-09-30: *"In Music and the Browse tab, let's have it like on jellystructure. So it goes "Artists",
> "Albums", "Songs", and then all the others."* · *"When in music mode and on the profile, do not add this weird
> circle around the profile picture."*

## Status

`Planned` — written 2026-09-30 (design-authored) from `design/ravilo/Ravilo Mobile.html` (built there the same day).
**Not dev-reviewed.** Number verified free (after R338). Two small changes; they hold in the desktop's compact layout
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
