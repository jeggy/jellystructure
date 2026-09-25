# Phase R303 — The player says what is playing, top right

## Status

`Planned` — written 2026-09-25 from the owner's ask and the mockups (`design/ravilo/ravilo-player.js/.css`,
`ravilo-app.js`, `Ravilo Mobile.html` + `mobile/ravilo-mobile-player.css`, `Ravilo Receiver App.html`),
drawn the same day. **Dev-reviewed 2026-09-25 against `main` `e7991df3`** (see §Dev review at the bottom: the
detail payloads already carry logo and ink, so the TV, phone and web halves are a `Dest.Player` field and
one composable; the receiver and the phone→TV push need the fields on their play message; FR-R303-5
describes a repetition that does not exist; the TV slot must not go into `PlayerScreen`'s body). Not
built. Uses **232**'s `logo_ink` and **R214**'s versioned `logoUrl`.

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
   **Closed — dev review item 4:** the TV chrome has nothing top right today (seen on the stue TV
   2026-09-25: Back top left, the block bottom left, transport bottom); `pl-stream` is the phone's.

## Acceptance

A series episode with a logo shows it top right with the chrome up and nothing with the chrome down; the
same episode with no logo shows the series name; a film with no logo shows nothing; a `dark` logo sits on
the plate; the kicker reads `S2 · E7`.

## Dev review (2026-09-25, against `main` `e7991df3`)

Six items, one of which changes the size of the phase.

1. **The TV, phone and web halves need no backend change.** The detail payloads already carry
   `logo_url` and `logo_ink` (`Models.kt:361/363` movie, `:586/588` series), and both detail screens
   already hand them to their hero (`MovieDetailScreen.kt:216`, `SeriesDetailScreen.kt:394`). `Dest.Player`
   (`RaviloApp.kt:257-277`) carries neither — nor a series name — so the phase is: three fields on
   `Dest.Player`, threaded at the detail's push sites (`:1248-1256` and the episode path above it) and
   through the binge's `replaceTop`, plus one composable in each chrome. The web app is the same
   commonMain `PlayerScreen`, so it comes for free.
2. **Two entry points do not come from a detail, and both need the fields on their message.** The
   phone-driven TV play (`play_item`, `RaviloApp.kt:604-620`) builds `Dest.Player` from `PlayItemEnvelope`,
   which carries `type, jellyfin_id, kind, title, start_position_ms, session_user_id` (`Models.kt:699-706`)
   — no logo, no ink, no series name. The receiver-only Tizen app is worse off: `Screen.kt:253-254` sets
   `title = env.title; kicker = null` from the same kind of push, so today it shows a bare title. FR-R303-7
   ("everywhere the player is") therefore needs `logo_url`, `logo_ink` and `series_name` added to the play
   push, resolved server-side where `RemoteRoutes` already resolves the item (`resolvePlayTarget`) — additive
   fields, never removed once shipped. "The player fetches nothing" then holds on every road.
3. **FR-R303-5 describes a repetition that does not exist.** On both the TV and the phone the kicker is
   already `S3 · E3` and the title is the episode's (seen on both devices 2026-09-24/25); the series name
   appears nowhere in the player, which is precisely the owner's complaint. The FR is a no-op — keep it as
   the invariant it is ("the bottom block stays S · E and the episode title"), not as a change.
4. **Open question 2 closes: the TV's top right is empty.** The TV chrome draws Back top left and nothing
   top right; the `TopEnd` in `PlayerScreen.kt:3497` is the *now playing* badge on the episode-rail tiles,
   not the chrome. On the phone the top row is `[Back] [kicker · title, weight 1] [castSlot]`
   (`PlayerHandsetChrome.kt:180-190`), so the slot sits between the title and the cast glyph as FR-R303-1
   says — and the title's `weight(1f)` is what yields to it.
5. **The TV slot must be its own `@Composable`, not lines in `PlayerScreen`'s body.** The TV chrome lives
   inside `PlayerScreen.kt` (4,175 lines; the release dex guard last measured its widest method at 239 of
   a 250 limit), and the two release-only `VerifyError`s this project has shipped both came from inlining
   new chrome there. Write it as an invariant, as R290 does.
6. **Where the fallback decides.** A failed logo load is the image loader's error callback — Coil on
   Android, the `<img>` `onerror` on the web — flipping one state to the text fallback; keep that state
   in the slot composable, not in `PlayerBookkeeping`, since nothing else reads it.

**Small correction.** "A film shows nothing" when there is no logo is right, but a film *with* a logo
now shows it top right *and* its title bottom left — say that this is intended (the mockup draws both),
so nobody "fixes" the duplication.

**Net effect.** Three `Dest.Player` fields, one composable per chrome, three additive fields on the play
push resolved server-side, and the receiver reading them. No strings.
