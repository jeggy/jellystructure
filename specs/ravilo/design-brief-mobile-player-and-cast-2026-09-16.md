# Design brief — Ravilo on phones: the media player, casting, and the missing phone screens

**Date:** 2026-09-16 · **For:** the design project (Cosmos) that owns `design/ravilo/` · **Status:** brief,
awaiting round-1 directions. Source investigation:
`specs/research-reports/ravilo-mobile-player-chromecast-ios-2026-09-16.md`.

> Owner's picture of the end result, 2026-09-16: *"I click the Chromecast icon on my Android phone, pick
> my TV, and it starts streaming via Jellyfin — I can control the pause directly from the Jellyfin
> dashboard. On the mobile I also see a simple remote-like screen (Netflix inspired) that takes the whole
> app view, where I can go 10 seconds back or 30 seconds forward, pause/play, and select subtitles. And we
> need designs for the mobile media player."* Plus: the phone app must feel *"enterprise-like"* and
> *"everything should look good, nice and mobile friendly"*, on **Android and iOS**.

---

## 0. What exists, so nothing is drawn twice

| Have | Where | Notes |
|---|---|---|
| Phone frame + CSS (Pixel 9, 412 × 915) | `ravilo/Ravilo Mobile.html`, `ravilo/mobile/ravilo-mobile.css` | Only three screens: Settings, Your profile, Change password (R234). 46 px targets, 13 px type floor already established there. |
| TV player chrome (the thing the phone currently reuses) | `ravilo/Ravilo TV.html` → `ravilo-player.js`, `ravilo-player.css` | Transport row −10s · ▶ · +30s · Audio & subtitles · Next; two-level flag-forward picker (R180/R195); Skip Intro pill; Next-up card; episode rail. |
| Player waiting states, incl. two phone frames | `ravilo/Player Loading and Buffering - Directions.html` | Cold start (B "Grounded"), stall (chrome rises, spinner in the play button), seek (spinner in the scrub tile), frame E. **Reuse as-is on phone.** |
| Failed-start copy | R237 spec, nine strings × en/da/fo | Reuse; never name a product, protocol or status code. |
| Skins | Aurora · Midnight · Noir | Every new screen in all three. Noir drops accent tints (R221/R222 precedent). |
| Brand assets | `ravilo/assets/brand/*.svg` | Receiver idle screen uses the lit-mark. |
| i18n | `ravilo/ravilo-i18n.js` | en · da · fo. Every new string in all three. |

Rules carried over from the built app: **never reveal the delivery method** (R180 FR-RV-ASP1-2 — no
"transcoding", no codec names, no bitrate in viewer copy); a TV screen never talks about settings
(R216/R234); render-never-compute — the phone shows what the server resolved.

---

## A. The mobile media player (Android + iOS)

**Frames needed:** landscape (primary) and portrait, on a Pixel-class Android frame **and** an iPhone
frame with a Dynamic Island and the home indicator. Both platforms share one design; differences are
safe areas and the absence of a system back button on iOS (edge swipe instead).

### A1. States to draw
1. **Chrome visible** — top: back, title + kicker (*Severance · S2 E4*), cast icon, more (⋯). Bottom:
   seek bar with time labels, transport (−10 s · ▶/❚❚ · +30 s), and the secondary row: *Audio &
   subtitles*, *Speed*, *Next episode* (series only), *Lock*. Scrims top and bottom, as on TV.
2. **Chrome hidden** — video only. Portrait: letterboxed video centred, black around it.
3. **Gesture feedback** — double-tap left/right shows a ±10 s ripple with the amount; vertical swipe on
   the left half shows a brightness pill, on the right half a volume pill; pinch toggles fit/fill with a
   one-word toast. Draw each overlay at the moment it appears.
4. **Locked** — everything hidden except a small lock glyph that unlocks on long-press; a tap shows
   "Locked · hold to unlock" for a second.
5. **Scrubbing** — thumb enlarged, time bubble above the thumb, ghost bar (as TV). No thumbnail
   preview (the server has none yet).
6. **Audio & subtitles** — the R195 two-level picker as a **bottom sheet** (language rows with flags →
   versions), plus a *Subtitle size* row (S · M · L) only on phone. Tap-away dismiss.
7. **Speed** — bottom sheet, 0.75 · 1 · 1.25 · 1.5 · 2. Chip on the chrome shows the non-default value.
8. **Skip intro / Skip credits** — the pill at phone scale, bottom-end, never overlapping the transport.
9. **Next-up** — the countdown card at phone scale, bottom-end in landscape; full-width strip in portrait.
10. **Episode rail** — a bottom sheet listing the season (phone), not the TV's horizontal rail.
11. **Waiting and failure** — reuse the R218 frames; failure sheet reuses R237 copy with *Try again* /
    *Back*.
12. **Rotate** — a rotate button in the chrome when portrait-locked by the system; the player follows
    the sensor otherwise.

### A2. Constraints
- Targets ≥ 46 px, type ≥ 13 px (the mobile mockup's own floor). Chrome inset from every safe area;
  nothing under the Dynamic Island or the punch-hole; the seek bar clears the home indicator.
- Auto-hide 3.6 s (TV value) — confirm or propose a phone value.
- Playback continues on the phone only while the app is in the foreground (owner decision, 2026-09-16):
  no background-audio state, no mini-player in the app, no PiP in this round.
- Live TV player: same chrome, no seek bar, channel name + *Now/Next* line, channel up/down by vertical
  swipe on the right edge.

---

## B. Casting from the phone (Android + iOS, Chromecast)

### B1. Flow (the owner's sentence, as screens)
1. **Cast icon** on every app bar (Home, Detail, Player) — the standard Cast glyph, in its three states:
   disconnected · connecting (animated) · connected. Shown only when the server says Chromecast is set
   up (§F); absent otherwise, not greyed. No AirPlay icon, ever (owner decision).
2. **Device picker** — the *system* Cast dialog on both platforms (not ours). Draw the frame around it so
   the moment is understood, do not redesign the dialog.
3. **Connecting** — a thin bar under the app bar: "Connecting to Living room TV…" → "Casting to Living
   room TV". Must feel instant; no full-screen interstitial.
4. **Play while connected** — pressing *Play* on a detail screen casts instead of playing locally, and
   the phone opens the remote (B2). Pressing the cast icon *inside* the local player hands the current
   position to the TV and swaps to the remote.
5. **Re-connect (a requirement, not a nicety)** — the TV keeps playing even if the phone dies, so
   opening the app while a cast is running must bring control back without a new session: the cast icon
   animates and a bar reads "Reconnecting to Living room TV…", then the mini bar (B3) appears with the
   live position; one tap opens the remote. Draw the reconnecting moment and the two outcomes: the cast
   is still running (mini bar), or the receiver has finished or is gone (nothing shown, no error — the
   viewer just casts again with one tap).
6. **Stop casting** — from the remote or the cast icon; the TV returns to the receiver idle screen.

### B2. The full-screen remote (Netflix-inspired, whole app view)
- Backdrop art dimmed, poster or still, kicker + title, *"Playing on Living room TV"* line.
- Seek bar with times, dragging allowed; **−10 s · ▶/❚❚ · +30 s** large and centred; secondary row:
  *Subtitles & audio* (opens the same bottom sheet as A1-6, choices applied on the TV), *Next episode*,
  *Stop casting*. Volume by the phone's hardware keys with a transient pill; no on-screen slider.
- States: playing · paused · buffering on the TV (spinner in the play button, as R218 moment C) ·
  next-up countdown mirrored from the TV · **receiver unreachable** ("Lost contact with Living room TV"
  with *Try again*) · **server busy** (Phase 182's 503 → "The server is busy right now. It will start as
  soon as it can." with the elapsed wait) · ended.
- Portrait only; landscape simply keeps the portrait layout centred. The system back / edge swipe
  returns to the app with the mini bar; casting continues.

### B3. Mini bar while browsing
- A 64 px bar above the bottom safe area on every screen while casting: thumbnail, title, ▶/❚❚, and
  the device name. Tap → remote. Swipe down → nothing (it never dismisses while a cast runs).

### B4. Lock screen / notification (Android; iOS later)
- The Cast SDK's own notification: artwork, title, ▶/❚❚, ±10/30 s, stop. Draw one frame for copy and
  artwork crop only — the layout is the platform's.

---

## C. What the TV shows while being cast to (the Ravilo receiver)

Runs on any Chromecast, from a 2013 stick to a Cast-enabled TV, at 1080p; keep it light (a 1st-gen
stick has little memory and no HEVC). Nothing here is interactive — the phone is the remote.
1. **Idle / just launched** — black, the Ravilo lit-mark, "Ready to play from your phone" (en/da/fo).
2. **Loading** — R218 moment B verbatim (pulse, kicker + title, sweep, "Loading…").
3. **Playing** — video only. On pause or seek: a low overlay for ~3 s with kicker + title, progress
   bar, time, and the ❚❚ glyph; then gone.
4. **Buffering** — R218 moment C treatment (frozen frame, chrome up, spinner).
5. **Subtitles** — the TV's own caption style (R110 white text, black outline), sizes S/M/L from the
   phone.
6. **Next-up** — the countdown card as on the TV app; the phone mirrors it.
7. **Unreachable / busy / ended** — one plain sentence each, no codes, the same R237/182 copy.

---

## D. The missing phone screens (Android + iOS)

Draw at least the first frame of each; note which are shared with the TV design and only reflowed.
1. **Home** — hero (portrait hero height already configurable, R159), rows with phone-scale tiles
   (today's 155 × 232 dp posters are TV tiles), Continue Watching with progress, channel rail.
   Decide **navigation**: the scrolling top tab strip (today, a TV pattern) vs a bottom bar (Home ·
   Search · Discover · Live · Profile). Recommend the bottom bar; draw both once.
2. **Movie / Series detail** — full-width hero, Play/Resume + cast affordance, genres row (R221),
   flag strip (R239), synopsis, season picker + episode list as a phone list with triptych cards
   (R179), cast & crew, About.
3. **Browse / See all** — facet bar → **bottom-sheet** checklists (the TV's popovers are D-pad
   surfaces); Maturity as a range control; sort.
4. **Search** — native keyboard, results grid, recent searches.
5. **Discover + Seerr request** — request flow with the language picker (R172) as a sheet.
6. **Live TV guide** — a phone EPG: channel list + now/next, not the TV's grid.
7. **Profile picker, login, server setup** — already touch-usable; restyle for the phone frame,
   iOS keyboard avoidance, the R225 server indicator.
8. **Settings** — exists (R229/R234); add *Quality on mobile data* (Auto · Data saver · Always high)
   and *Wi-Fi only* rows, and the Cast device list if any.

Global for D: 20 dp gutters (R145), bottom safe area, pull-to-refresh where a list is server-fed,
skeleton shimmer as on TV, all three skins, all three languages.

---

## F. Admin: Settings → Chromecast card (jellystructure, `app/settings.html`)

Chromecast is **optional and set up by the admin** (owner requirement). jellystructure hosts the
receiver itself, and **every installation registers its own Cast application with Google** — by the
owner's explicit decision, that one paid, external step (US$5 once, Google only) belongs inside
jellystructure like any other key. The card's job is to make it feel like part of the product. One card
in the existing Settings tab structure (likely under *Ravilo*, next to Live TV's placement, or
*Connections*):
1. **Enable Chromecast** switch, off by default. Off ⇒ no Cast button anywhere in Ravilo.
2. **Your receiver address** — read-only, copyable: `https://<this server>/cast/`, with a live check
   chip: *reachable over the internet* / *not reachable — Chromecast needs a public https address*.
3. **Register with Google** — three numbered plain-language steps inline, not a help link: *Register an
   application at the Google Cast Developer Console (a one-time US$5 fee)* · *Choose "Custom Receiver"
   and paste the address above* · *Add your Chromecast as a test device, or publish the application so
   any Chromecast can use it* — then the **Application ID** field (8 hex characters). Copy rule: Google
   is named because the admin pays Google; nothing else is named.
4. **Concurrent cast sessions** stepper (the transcoding ceiling), with a one-line explanation in plain
   words: "Each cast is a transcode on your Jellyfin server."
5. **Status** line: receiver reachable · application ID set · *"Cast from your phone once to confirm
   it works"* until the first session · last cast · devices that have cast (linking to Users & devices,
   where a Chromecast appears as its own device row).
Follow the wf.css tokens and the write-through editing shape (Phases 71/74); draw the off state, the
on-but-unregistered state (steps visible, ID empty), and the registered-and-verified state.

## E. Deliverables and order

1. **Round 1 — `ravilo/Mobile Player - Directions.html`**: two or three directions for A (chrome +
   gestures), on both device frames, plus the picker sheet and next-up. Owner picks.
2. **Round 1 — `ravilo/Casting - Directions.html`**: the B1 flow as a storyboard incl. the re-connect
   moment, two directions for the remote (B2), the mini bar (B3), the receiver screens (C), and the
   admin card (F) drawn once in `app/settings.html`'s idiom.
3. **After the picks:** build into `Ravilo Mobile.html` + `ravilo-mobile.css` (player, remote, mini bar)
   and a new `ravilo/Ravilo Receiver.html`; then the D screens as a second brief round.
4. Print copies as for earlier rounds; keep `LANG_CC`/flag tables in sync with `ravilo-i18n.js`.

**Not in this round:** background audio / PiP on the phone, offline downloads, a Ravilo TV app
"Cast Connect" receiver (later phase), tablets. **Never:** AirPlay — dropped by owner decision,
2026-09-16; do not draw an AirPlay affordance anywhere.
