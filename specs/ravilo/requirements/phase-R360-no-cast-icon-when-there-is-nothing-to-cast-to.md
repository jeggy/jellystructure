# Phase R360 — No cast icon when there is nothing to cast to

> Owner, 2026-10-03: *"not showing the casting icon if the available devices are empty."* Today the glyph is present
> whenever the **server** has a cast capability (Chromecast set up, screens on, or AirPlay here — `castActive` in
> `RaviloApp`), so a phone on a network with no Chromecast, in a household with no paired TV, shows a cast icon that
> opens a sheet with nothing in it but *Add a TV*.

## Status

`Planned`. Written 2026-10-03 with the owner, against `main` `ff9746d4`; not dev-reviewed. Number checked free (Ravilo
specs top at R359 on `origin/main`). **Amends** FR-R245-1 (presence), FR-R265-1 (presence) and **removes** FR-R265-5
(*Add a TV* from the sheet). Client only (`ravilo-ui`): no route, no DTO and no wire change; the backend's
`/api/remote/pair` stays for API compatibility. Strings: two keys go out of use (FR-R360-5).

## Decisions (owner, 2026-10-03)

- **D1 — the icon follows the sheet's list.** It shows exactly when the sheet would list at least one device, and is
  absent when that list is empty. A paired TV that is **offline still counts**: it is a row in the sheet
  (*Offline · last seen …*), so the icon shows.
- **D2 — *Add a TV* is removed entirely.** Pairing a TV with a code exists only for the Tizen receiver (R264), and all
  Tizen work is paused. There is no replacement entry point; it comes back with Tizen if Tizen comes back.
- **D3 — strictly live.** The icon appears the moment the list gains its first device and goes when the list empties,
  with only a short grace before it hides (FR-R360-3).

## Requirements

**FR-R360-1 · One rule: the icon is present when the list is not empty.** "The list" is exactly the set of device rows
`ScreensSheetBody` would draw for the current mode, computed in **one** place (a function on `CastController`, read by
both the sheet and every glyph), so the icon and the sheet cannot disagree:

- every paired **screen** from `GET /api/remote/devices` (`kind = SCREEN`, R327's filter), **online or offline**,
  nearby or not;
- every Cast route the platform discovers (Android's MediaRouter, the desktop's Cast v2 discovery) that the sheet
  would list in this mode: in video mode only `display` routes; in music mode speakers and groups too (R324
  FR-R324-1 — so a speaker-only household shows the icon on Now playing and in music mode, and not in video mode);
- the AirPlay row where WebKit reports a target (R270's footnote row, FR-R265-4).

Explanatory lines in the sheet are **not** devices and do not count: *speakers need Android* (R324 FR-R324-10), the
Mac's Local Network line (R330 FR-R330-8). The server's capability (`castActive`) stays a precondition — no capability,
no icon, and no discovery — but on its own no longer shows anything.

**FR-R360-2 · Except while a cast is on.** Connecting, reconnecting or connected (a Chromecast, a screen, a speaker,
AirPlay's `wireless`), the icon is present whatever the list says: it is the way to the sheet's *Stop casting*
(FR-R245-10), and a device that drops off discovery mid-session must not take the control with it.

**FR-R360-3 · Live, with a short grace on the way out.** The icon appears on the frame the first device arrives. When
the list empties it hides after **10 s** if still empty — long enough for MediaRouter's remove-then-re-add of the same
route while a Cast device reconnects or a group republishes (seen around R353), short enough to read as live. A device
arriving inside the grace cancels it. Absent, never greyed (FR-R245-1's rule stands); no fade beyond the app bar's own.

**FR-R360-4 · The list is known before the sheet opens.** Today the screens list is fetched only when the sheet opens
(and once on app start for R265's reconnect). It is now kept on `CastController`: fetched on app start and on every
return to the screen (the fetch FR-R265-7's reconnect already makes — one request, both uses), after a sign-in or
profile switch, and whenever the sheet closes. No polling: whether a paired TV is online does not change the answer
(D1), only pairing/unpairing does, and that happens elsewhere. Cast routes are already collected while the app is on
screen (`rememberCastRoutes` in `CastSheetHost`, R293's on-screen-only scan); they move to the controller unchanged so
the glyphs can read them. A failed fetch keeps the last list rather than emptying it (an outage must not hide a TV the
viewer just used).

**FR-R360-5 · *Add a TV* is gone.** From the sheet: the *Add a TV* row, `AddTvSheetBody` and its code entry, the
`onAddTv` plumbing, `CastController.pairScreen`, and the empty-sheet hint that reused `screens.add` (the sheet can no
longer open empty, FR-R360-1). `screens.add`, `screens.code_hint` and `screens.code_failed` are removed from
`i18n/*.json` if nothing else reads them. `TvApiClient.remotePair` and the backend route stay (API compatibility).

**FR-R360-6 · Every entry point obeys it.** The phone app bar, the player's chrome (`castSlot`), Now playing's glyph
and device chip (R324), the desktop toolbar's *Play on…* (`DeskCastButton`) and the desktop music bar's cast button.
The gate lives in the shared rule, not at each call site (R286's lesson: a fourth call site inherits it). The TV stays
glyph-less (R286). In the app bar the glyph is the leftmost action, so its arrival moves nothing to its right.

**FR-R360-7 · Tests.** Common-code unit tests of the rule: empty → absent; one offline screen → present; speaker only →
absent in video mode, present in music mode; AirPlay only → present; connected with an empty list → present; list
empties → still present at 9 s, absent at 10 s; device back at 5 s → never hidden.

## Out of scope

- A new place to pair a TV (D2).
- Showing *why* there is no icon, or a "no devices found" state anywhere (absent, not explained — R265's rule).
- Changing what the sheet lists or how it orders rows.

## Acceptance

1. Pixel 9, a network with no Chromecast, a viewer with no paired TV: no cast icon on Home, a detail page, the player
   or Now playing.
2. The same phone near a Chromecast: the icon appears within the discovery time, without reopening anything; switch the
   Chromecast off: the icon goes about 10 s after the route drops.
3. A viewer with one paired TV that is switched off: the icon shows, and the sheet lists the TV as *Offline*.
4. While casting, unplug the Chromecast: the icon stays until the cast ends.
5. Music mode next to a speaker only: the icon is on Now playing; switch to films & series: it is gone.
6. No sheet anywhere offers *Add a TV*.

## Open questions

- The design mockups (`design/ravilo/Ravilo Mobile.html`, `Play on a TV - Directions.html`) still draw *Add a TV* and an
  always-present glyph; they should follow once this is built.
