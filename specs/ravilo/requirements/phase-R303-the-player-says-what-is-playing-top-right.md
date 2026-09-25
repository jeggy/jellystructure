# Phase R303 — The player says what is playing, top right

## Status

`Planned` — written 2026-09-25 from the owner's ask and the mockups (`design/ravilo/ravilo-player.js/.css`,
`ravilo-app.js`, `Ravilo Mobile.html` + `mobile/ravilo-mobile-player.css`, `Ravilo Receiver App.html`),
drawn the same day. Not dev-reviewed. Not built. Uses **232**'s `logo_ink` and **R214**'s versioned
`logoUrl`.

> *"When in the media player and the item has a logo artwork available, it should be shown when the media
> controls are shown (maybe top right). Especially for series, which currently do not show the title of the
> thing you are watching, only season and episode numbers + episode name."*

## Functional requirements

**FR-R303-1 — where.** Top right of the player chrome, inside the chrome, so it appears and fades with it.
Never shown while the chrome is hidden, never on the picture alone. TV: max 400 × 84 px (1920 frame).
Phone: max 112 × 36 dp, immediately left of the cast glyph.

**FR-R303-2 — what.** A film shows its own `logoUrl`. An episode shows the **series'** `logoUrl`, never a
season's or an episode's. The play payload carries it (`logoUrl`, `logoInk`, and for episodes
`seriesName`) — the player fetches nothing.

**FR-R303-3 — no logo.** A series shows its **name in text** in that slot (Space Grotesk bold, right-aligned,
two lines max, ellipsis). A film shows **nothing** — its title is already in the bottom block. A logo that
fails to load falls back the same way.

**FR-R303-4 — a dark-ink logo sits on a light plate.** Owner decision. When `logo_ink` is `dark`, the logo
is drawn on a rounded light plate (`rgba(244,246,250,.92)`), not recoloured. This differs from the detail
hero, which tints a dark logo (R259 FR-R259-6); the player's top edge sits over moving video, where a
tint can still vanish.

**FR-R303-5 — the bottom block stops repeating the series.** For an episode the kicker above the episode
title is `S2 · E7` only; the series name is now top right. A film's kicker is unchanged.

**FR-R303-6 — gives way.** On the phone, when an AirPlay chip names the TV in the top row, the logo slot is
hidden. Nothing else hides it.

**FR-R303-7 — everywhere the player is.** The TV app, the phone, the web app, and the receiver-only TV app
(R264). The Chromecast receiver (`/cast/`) follows R245's rule for its own chrome and is not in scope.

**No new strings.** The series name is data.

## Open questions

1. Live TV: show the channel's logo there too? Lean no — the channel row already carries it.
2. Does the TV's Compose player chrome have room top right on a 16:9 title with R218's stream badge
   (`pl-stream`) beside it? The mockup draws both; confirm on the stue TV.

## Acceptance

A series episode with a logo shows it top right with the chrome up and nothing with the chrome down; the
same episode with no logo shows the series name; a film with no logo shows nothing; a `dark` logo sits on
the plate; the kicker reads `S2 · E7`.
