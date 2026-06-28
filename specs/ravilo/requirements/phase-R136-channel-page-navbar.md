# R136 — Channel page: top-navbar parity

> Builds on **R76/R77** (the top `AppBar` nav). Affects the Channel page (opened from the Home channel rail).

## Problem

Every main screen (Home, Movies/Series Browse, Discover, detail) shows the full top **`AppBar`** —
section nav tabs (Home / Movies / Series / My List) + profile + search + clock. The **Channel page**
instead shows the lightweight **`ChannelBar`** (just `‹ Home` back + the channel name + clock), so from a
channel you **can't reach the section nav** — it reads as "missing the top navbar."

## Current

- `AppBar` (`components/AppBar.kt`) — full nav, used by `HomeScreen` (~279), `BrowseScreen` (~218),
  `DiscoverScreen` (~195), `MovieDetailScreen` (~319), `SeriesDetailScreen` (~459).
- `ChannelBar` (`components/AppBar.kt` ~209–306) — back + channel name + clock only; rendered by
  `ChannelScreen.kt` (~221–229). Its `onDown` focuses the hero / first tile.

## Requirements

1. The Channel page shows the **standard top navbar** (section nav tabs + profile + search + clock),
   consistent with the other screens, so the user can jump to any section from a channel.
2. The **channel name** is still surfaced (a page title/heading), and Back still works (D-pad Back; an
   explicit back affordance is optional once the nav tabs are present).
3. Preserve the existing behaviour: DOWN from the bar focuses the hero / first tile; scrolled-solid bar
   background; the always-composed overlay so Back works during Loading/Error.

## Approach

Render the full `AppBar` on `ChannelScreen` (pass `navItems`/`activeNav`/`onNavSelect`/`onProfile`/
`onSearch` + the channel's `onDown`), and show the channel name as a heading under the bar (or add an
optional `title`/`back` slot to `AppBar` for a "channel context" variant so one bar shows both nav tabs and
the channel name). Keep `barScrolled` + the always-composed placement.

## Files

- `ravilo-ui/.../screens/ChannelScreen.kt` (swap `ChannelBar` → `AppBar` / channel-context bar; wire nav).
- `ravilo-ui/.../components/AppBar.kt` (optional title/back slot for the channel variant).

## Out of scope

Redesigning the navbar; the channel rail on Home.
