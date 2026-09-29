# Phase R327 — A Chromecast is one row in the sheet: the platform's route, never its receiver's record

> Owner, 2026-09-28, with a screenshot of the Pixel 9's *Play on a TV* sheet: *"the Ravilo app shows the same devices
> twice, while some of them are being shown as offline, but also as ready."*

## Status

`✓ Built` 2026-09-29 (§Build notes; commit `1ac58a7d`) — written and **dev-reviewed 2026-09-28** against `main` `32daeee2`, from the owner's screenshot and the
production device table (read-only). Number verified free (Ravilo tops at R326). No design change: the mockups never
drew a receiver's record. **Amends** R265 FR-R265-3 (what tier 2 lists) and 236 FR-236-4 (what the device list
carries). **Builds on** 218 (a receiver enrols as a `ravilo_device`, `kind = cast`, named *Chromecast via Ravilo ·
{device}* for Jellyfin's dashboard — FR-218-10).

## What the owner saw

*All your TVs (6)*: three rows *Chromecast via Ravilo · Stue TV / Køkken hub / Soveværelse TV* reading *Offline · last
seen Saturday / Friday / Friday*, dimmed and not tappable — and under them *Stue TV · Køkken hub · Soveværelse TV*,
each *Ready ›*. The household has five Cast devices (two TVs, the Nest Hub, two screenless speakers); the three
displays are the ones the SDK lists, since the app is not yet registered for audio-only devices (286).

## Why it happens

1. `GET /api/remote/devices` returns **every** `ravilo_device` row for the caller (`RemoteRoutes.kt` `/devices` →
   `deviceService.listByUser`), and the phone keeps `kind ∈ {screen, cast}` (`ScreensSheet.kt:142`), listing those
   not `nearby` under *All your TVs* — **together with** every Chromecast the Cast SDK can see (the routes,
   `ScreensSheet.kt:146`).
2. A Chromecast receiver's row is a **record**, not a device to reach: 218 enrols the receiver by hand-off code
   (`CastService.redeem`, `kind = "cast"`, a `cast-…` id) so that the session ceiling and the Jellyfin dashboard name
   work. Production holds exactly three — `cast-…` *Stue TV*, *Køkken hub*, *Soveværelse TV*, last seen 09-25/09-26.
3. `online` is `tvEventBus.isConnected(deviceId)`. The receiver (`ravilo-cast`) never opens the events socket — it
   speaks the Cast channel to the phone and 218/279's playback routes to the server — so a receiver row is **never**
   online, even while it plays (R265's own open note saw *Offline · last seen Thursday* on a playing TV). It never
   stamps `last_public_address` either, so it is never *nearby*: always tier 2, always *Offline*, never tappable.
4. The three *Ready* rows are the SDK's routes for the **same three devices**. So each display is listed twice: once
   as a record that can do nothing, once as the route that does everything.
5. A tap on a receiver row could never have worked: `joinScreen` → `ScreenSender.link` → `/api/remote/**` →
   `TvEventBus.push` → `409 device_offline`, because a Chromecast is driven through the Cast SDK's channel, not 236's
   screen protocol.

## Decisions

- **A Chromecast reaches the viewer through the platform's route only.** The route says Ready / Playing / Busy and
   joins a running session (R265's build notes). On a phone with no Cast SDK (iPhone, web) a Chromecast is not
   reachable, so it is not listed — 286's road C is the day that changes.
- **The receiver's record stays what it is** — 218's ceiling, FR-218-10's dashboard name, Users & devices — it just
   never becomes a row a viewer sees.
- **Fixed on both sides.** The server side alone repairs every installed phone (1.35–1.44 today) on the next backend
   release; the phone side keeps a new app honest against an older server.

## Requirements

**FR-R327-1 — The sheet lists screens and routes, never receiver records.** `ScreensSheetBody` keeps
`kind == SCREEN` only (drop `CAST` from the filter at `ScreensSheet.kt:142`); the SDK's routes are unchanged.

**FR-R327-2 — `GET /api/remote/devices` omits `kind = cast` rows**, and `GET /api/remote/devices/{id}`, `POST
/api/remote/play` and `/command` treat a `cast` id as *not found for this caller* (today `/play` answered `409
device_offline`, which was never true in the sense the phone showed). One filter in `RemoteRoutes.kt`, applied where
`listByUser` is read. A Home Assistant integration on phase 111's shapes loses rows it could never control.

**FR-R327-3 — Reconnect is unchanged.** FR-R265-7's `reconnectsTo` requires `online`, which a receiver row never had;
the Chromecast rejoin is the SDK's own resume (R265 build notes), and R265's open item about a phone that was killed
stays open there — not here.

**FR-R327-4 — Users & devices is unchanged.** FR-236-10 keeps listing receivers with their kind and dashboard name
(`RaviloUsers.kt:91`); 286 adds the *audio only* badge there.

**FR-R327-5 — No enum value is removed.** `DeviceKind.CAST` stays on the wire shape (the rule since v1.31: never
delete a shared-DTO member); it simply never appears in this list.

**FR-R327-6 — No new strings, no design change.**

## Out of scope

Making a receiver's row *online* (it would need the receiver on the events socket — 286 does not need it either) ·
the killed-phone rejoin (R265) · speakers and groups (R324 — they are routes too, and this phase is why they must be).

## Acceptance

1. Pixel 9, the three displays idle: *All your TVs (3)* — Stue TV · Køkken hub · Soveværelse TV, each *Ready*; no
   *Chromecast via Ravilo* row, no *Offline*.
2. Stue TV casting from this phone: its route reads *Playing {title}*; still one row for it.
3. An older phone (1.44) against the new server: the same three rows.
4. Settings → Users & devices still shows the three receiver rows with their dashboard names.
5. `GET /api/remote/devices` carries no `"kind":"cast"`; `POST /api/remote/play` for a `cast-…` id answers 404.

## Dev review (2026-09-28, against `main` `32daeee2`)

Written with the spec. Four items, all small.

1. **Phone:** one predicate at `ScreensSheet.kt:142`. `reconnectsTo` (`Cast.kt`) needs no change (item 3 above).
2. **Server:** `RemoteRoutes.kt` reads `deviceService.listByUser(caller)` in four places (`/devices`, `/devices/{id}`,
   `/play`, `/command`) — one helper `remoteDevicesOf(caller)` = `listByUser(...).filter { it.kind != "cast" }`, used by
   all four; `CastService` and `PlaybackService` keep seeing cast devices (the ceiling, the dashboard identity).
   `RemoteRoutesTest`: a `cast` row is not listed and `/play` on it is 404.
3. **Wire (R319):** rows removed, no shape change; `DeviceKind` untouched. An old app against a new server sees fewer
   rows; a new app against an old server filters the same rows itself.
4. **Why not make the record online instead:** the receiver would have to hold an events socket for the whole session
   only so a row could say *Playing* beside the route that already says it — and on iPhone/web nothing could act on
   it. The route is the truth on Android; there is no truth to show elsewhere until road C.

## Build notes (2026-09-29)

Built as reviewed, in one commit (`1ac58a7d`):

1. **FR-R327-2 — the server side** (`RemoteRoutes.kt`): `GET /api/remote/devices` omits `kind = cast` rows. A receiver's
   record has no events socket, so it is never online or nearby and can never be driven through `/api/remote`; the
   Cast SDK's route is the one row a Chromecast gets. This repairs every installed phone on the next backend release,
   with no app update. Covered by `RemoteListingTest` (a cast row is filtered; a screen row is not).
2. **FR-R327-1 — the phone** (`ScreensSheet.kt`): the sheet filters `DeviceKind.SCREEN` explicitly as well, so an
   app against an older server also shows each Chromecast once.
3. Nothing else changed: the ceiling, the dashboard name, Users & devices and reconnect all still read `kind = cast`
   where they did.

Not deployed and not device-tested: the owner withdrew backend-restart and device permission on 2026-09-29, mid-round. Verified by compile (`compileKotlinLinuxX64` · `compileKotlinWasmJs` · `:ravilo-ui:compileDebugKotlinAndroid` · `:ravilo-web:compileKotlinWasmJs` · `:ravilo-cast:compileKotlinJs`), the unit tests named below, and the six fences.
