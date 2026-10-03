# Phase R360 — No cast icon when there is nothing to cast to

> Owner, 2026-10-03: *"not showing the casting icon if the available devices are empty."* Today the glyph is present
> whenever the **server** has a cast capability (Chromecast set up, screens on, or AirPlay here — `castActive` in
> `RaviloApp`), so a phone on a network with no Chromecast, in a household with no paired TV, shows a cast icon that
> opens a sheet with nothing in it but *Add a TV*.

## Status

`Planned`. Written 2026-10-03 with the owner, against `main` `ff9746d4`; dev-reviewed 2026-10-03 (section at the end — FR-R360-4 replaced by item 2, and two notify calls on the server, item 3). Number checked free (Ravilo
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

## Dev review (2026-10-03, against `main` `2bf7300f`)

Read against `RaviloApp`'s cast wiring, `Cast.kt`, `ScreensSheet.kt`, `AppBar.kt`, the player and music chrome, both
`rememberCastRoutes` actuals, and the server's `Main.kt`, `RemoteRoutes` and `ScreenPairingService`. The design holds.
FR-R360-4 changes shape (item 2), and the server needs two lines (item 3). Eleven items; one is for the owner (item 9,
lean given).

1. **Why the icon is always there today.** `castActive` is non-null when `castAppId != null || screensEnabled ||
   airplayAvailable`, and the server sends `screens.enabled = true` on **every** installation (`Main.kt`,
   `screensCapability`, 236 item 9). It does that on purpose, so a household could add its first TV from the sheet. So the
   glyph shows on every server and every platform, whatever is around. With *Add a TV* gone (D2) `enabled` has no
   reason left. **The server keeps sending it unchanged**, because installed older apps read it to offer *Add a TV*. The
   new client stops using it for presence.

2. **FR-R360-4 is replaced: the screens half is already server-pushed.** `RaviloConfig.screens.paired` is computed as
   `listByUser(user).any { kind == "screen" }`. That is the same `listByUser`, filtered to the same kind, that
   `GET /api/remote/devices` answers and the sheet keeps (R327: `SCREEN` only; `listedToRemote()` drops only `cast`).
   Offline TVs are included. So `paired` is exactly "the sheet has a TV row", per viewer, and it arrives with the config
   the app already fetches. **No new fetch and no polling.** The reconnect fetch (FR-R265-7) stays as it is. The client
   keeps `screensPaired` beside `screensEnabled` in `refreshConfig()`.

3. **Server: a pairing change must push the config.** Nothing calls `tvEventBus.notifyConfigChanged(userId)` when a
   screen is paired (`ScreenPairingService`'s claim) or when a screen session is revoked or deleted (the admin's Users
   & devices, sign-out-everywhere). So `paired` would stay stale until the next unrelated config change. Add the
   notify call on those paths for `kind = "screen"`, once per affected user. Status: "client only" becomes **client +
   two notify calls on the server**. No route, DTO or wire change.

4. **Disagreement in the open sheet.** The sheet still fetches the list when it opens (it needs online, busy and
   now-playing). If that fetch returns no TV while `paired` said there was one (revoked in between), the fetch is
   newer. It overrides `paired` until the next config refresh. If the sheet then has no rows at all, it **closes
   itself** rather than showing a bare title: the empty-sheet hint went with *Add a TV*. The icon then goes after the
   grace.

5. **Chromecast routes move out of the sheet.** `rememberCastRoutes` is called inside `ScreensSheet`, which only
   `CastSheetHost` composes, which only exists while `castActive != null`. The call moves up into `CastSheetHost`,
   called once with the same `appId` and on-screen keys (R293 is unchanged). It publishes into a `StateFlow` on
   `CastController` (`routes`), and the sheet reads it from there. On the desktop `CastDiscovery.acquire()` stays
   reference-counted, with one acquirer. The mode filter (`visibleRoutes`: displays only in video mode, everything in
   music mode) becomes one function that both the sheet and the rule call. Today it is inline in `ScreensSheetBody`.

6. **One rule, one composable.** `CastController.hasDevices(music)` returns
   `screensPaired || routesFor(music).isNotEmpty() || airplayAvailable`. `rememberCastIconShown()` combines that
   with FR-R360-2 (`sender.link != NONE || airplay.wireless || MusicCast.linked`) and FR-R360-3's grace. Every glyph
   calls `rememberCastIconShown()`. **`castActive` keeps its meaning** (the capability). The reconnect, the mini bar,
   the connecting bar and the player hand-off stay keyed on it, because a session can exist while the list is empty.

7. **The entry points, checked.**
   - The phone app bar (`AppBar.kt` ~202), the wide bar (~298) and the player's `castSlot` (`PlayerScreen` 1922) all
     render `CastButton()`, which gates itself. Put the rule there.
   - `DeskCastButton` gates on `LocalCast` only, and `DesktopMusicBar` on `cast != null`. Both switch to the rule.
   - Now playing's `BottomRow` uses `CastButton`, so it is covered by the gate above.
   - `DeviceChip` shows only while linked, and `CastRemoteScreen` is reachable only while casting. FR-R360-2 already
     covers both, so they need no change.
   - The TV stays out (R286's `isTvPlatform` return comes first).

8. **The grace is testable in common code.** Build the hide delay as a flow transform (`debounce`-like, one
   direction only: show at once, hide after 10 s). FR-R360-7's cases run in `commonTest` under
   `kotlinx-coroutines-test`'s virtual time, so no sleeping test.

9. **For the owner — the phone's top bar moves.** FR-R360-6's "arrival moves nothing" is true on the wide bar only. On
   the phone, R267 FR-R267-2's order is *brand · the page's own control · cast*. Cast is the **rightmost** item, so
   its arrival slides the page's control (`handsetTopSlot`: Home's, Browse's) about 52 dp left, and in the player it
   pushes the logo slot (R303). **Lean: accept it.** A paired TV comes with the config, so for that household the glyph
   is there from the first frame with content. Only a Chromecast or speaker found by discovery arrives later, usually
   within a second or two of opening, once per opening. Reserving the slot would bring back the empty gap R265 rules out.

10. **Strings and API.** `screens.add`, `screens.code_hint` and `screens.code_failed` are read only by `ScreensSheet`,
    so they can come out of all three `i18n/*.json`. Nothing else, no test and no script names them. `CastController.pairScreen`
    goes. `TvApiClient.remotePair` stays, because the route stays and the client mirrors the API.

11. **Consequences noted, not changed.** On the iPhone web app in music mode with no paired TV and no AirPlay target,
    there is no icon. That means the *speakers need Android* line (R324 FR-R324-10) is never seen there. This is D1 as
    decided, since explanatory lines do not count. A Ravilo Android TV (`kind = tv`) has never been a sheet row
    (R327), so it doesn't count either.
