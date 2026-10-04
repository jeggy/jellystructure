# Phase R371 — More rooms one at a time, and volume for all of them and each room

## Status

`Planned` — written 2026-10-03 (design-authored), **dev-reviewed 2026-10-04** (see the end: the master volume works everywhere through the receiver; adding a room is new Android work through `MediaRouter2`, unproven on the desktop; only a linked Android app sees the rooms). **Phase 4 of 6** (see R368). Depends on
R368–R370, R355 (speaker groups from the phone) and R357 (every player reports its volume). Designs:
`Playback Sessions - Directions.html` §B4 and `… - Desktop, TV & Admin.html` §B4a.

## Requirements

**FR-R371-1 — Pick one, then add another (owner, 2026-10-03).** There is **no cast-everywhere button**, no
*Make a group…*, and no saved or *whole house* group anywhere in Ravilo. Every playback starts on **one** place
(R370). A session grows only by adding rooms to it while it plays. The rooms form a Cast group that Ravilo builds on
the fly (R355's path), with `cast_group` as the session's `target_kind`. Only speakers and displays join; a Ravilo
app (phone, computer, TV app) is never one of the rooms. Groups the household made in the speakers' own app aren't
listed (R370).

**FR-R371-2 — *Add a speaker…*** (canvas §B4·a). In the remote's **Speakers** sheet (phone) or the volume popover
/ Speakers card (desktop, web, TV panel), the last row is *Add a speaker…*. It lists the free speakers and displays
with their current level. **One tap adds one room**: it joins in sync at its own level, with no ticks, no *Play on N
speakers* button and no confirm. The sheet stays open, so another can follow. A busy room is listed but can't be
tapped. Removing: swipe a room's row left (phone) or its × (desktop). Removing the second-last room leaves an ordinary
one-place session, and removing the last stops it.

**FR-R371-3 — The place line names the rooms** in the order they joined: *Stue* → *Stue + Gæsteværelse* →
*Stue + 2* (the full list is in the Speakers sheet). When a room leaves on its own (unplugged, R355), the line says
*{room} left* for 5 s.

**FR-R371-4 — Volume: one master and one per room** (canvas §B4a):

- **One place:** one slider, the place's own volume.
- **Several rooms:** a **Volume** slider on top (labelled *Volume*, never *All speakers*, so it can't read as a
  play-everywhere button) and one slider per room under it. The master moves every room by the same
  ratio, keeping their balance. A room slider moves only that room. Each room has its own mute.
- Phone: in the remote's ⋯ volume panel, and the hardware buttons drive the master (R324's HUD).
- Mac capsule and GNOME bar: the volume button opens a popover with the same sliders.
- Desktop Playing page and the web player: an inline row under the transport.
- The state comes from R357's volume reports. A room that doesn't report shows *—* and its slider is disabled with the
  reason *{place} doesn't report its volume*.

**FR-R371-5 — Volume commands.** R369's `command` gains `set_volume { level }` (master) and
`set_volume { target_id, level }` (one room), plus `set_mute` in both forms. The server sends them to the receivers
and shows what they report back.

**FR-R371-6 — Strings** × en · da · fo (drafts):

| Key | en | da | fo |
|---|---|---|---|
| `group.add_speaker` | Add a speaker… | Tilføj en højttaler… | Legg ein hátalara afturat… |
| `group.left` | {room} left | {room} forlod gruppen | {room} fór úr bólkinum |
| `volume.master` | Volume | Lydstyrke | Ljóðstyrki |
| `volume.no_report` | {place} doesn't report its volume | {place} oplyser ikke lydstyrken | {place} sigur ikki ljóðstyrkina |

## Acceptance

1. Eyð plays *Cannery Lights* on Stue. In the remote's Speakers sheet she taps *Add a speaker…* → Gæsteværelse.
   It joins in sync, and the place line reads *Stue + Gæsteværelse*.
2. On the MacBook capsule she drags *Volume* from 60 % to 30 %. Stue goes 70 → 35 and Gæsteværelse 50 → 25.
3. She adds Kontor the same way, and the line reads *Stue + 2*. Kontor is unplugged, and the line reads *Kontor left*
   for 5 s, then *Stue + Gæsteværelse*.
4. No *Play on…* list, sheet or menu in any Ravilo app offers several places at once.

## Decided by the owner (2026-10-03)

- No cast-everywhere button, no *Make a group…*, no saved groups. Pick one place, then add another.
- 2026-10-04: one tap adds a room straight away, with no ticks and no confirm.

## Taken as leans

- *Volume* scales the rooms by the same ratio and keeps their balance.
- A phone, computer or TV app is never one of the rooms.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against R355 (spec and build notes), R357, R354's receiver half, `CastSender`/`CastSenderAndroid`, `ravilo-castv2`,
`Receiver.kt`, the resolved `androidx.mediarouter` (1.8.0-beta01, from the Gradle cache), the canvases
(`sessions-phone.js` §B4, `sessions-desk.js` §B4a, `sessions-rest.js` §B9) and `i18n/*.json`.
- **The master volume works from every app today**, through the receiver (item 6).
- **Adding a room from Ravilo is new work on Android** (R355 never built it) and **unproven on the desktop**.
- **Nobody but a linked Android app can see a group's rooms or their own levels** (item 5).

Twelve items; two are for the owner.

1. **Correction: R355 builds no group.** Android's output panel (⊕) builds it. R355 only **reads** the result.
   - `CastSender.members` comes from MediaRouter's selected route (its `SELECTED` members, R355 build note 1), and
     `castSessionName` names it.
   - "A Cast group that Ravilo builds on the fly (R355's path)" is therefore new. What R355 proved is the effect: the
     receiver moves onto the group alive, with no new LOAD, enrolment or Jellyfin session.

2. **Android: adding a room is possible, through a framework API.** In the resolved `androidx.mediarouter`
   1.8.0-beta01, `MediaRouter.addRouteToSelectedGroup`, `removeRouteFromSelectedGroup` and `transferToRoute` exist, but
   are `@RestrictTo(LIBRARY)` (checked in the jar). Don't build on them. The public road is the framework's
   `MediaRouter2.RoutingController.selectRoute`/`deselectRoute` (API 30+). The controller is the one androidx creates
   for the Cast session when media transfer is on.
   - `getSelectableRoutes()` is the *Add a speaker…* list.
   - The app's `minSdk` is 24, so the row is absent below API 30 (a check on `Build.VERSION.SDK_INT`).
   - Needs one device session on the two speakers before the UI is built.

3. **Every other app: no Cast, so no adding.**
   - **Desktop:** `ravilo-castv2` has no public way to grow a group. The multizone namespace is undocumented (report
     §8 Q5). The canvas itself marks it: *desktop depends on §8 Q5* (`sessions-phone.js:123`).
   - **Web, iPhone, TV app and admin (304):** no Cast at all.
   - A relay through an attached Android app is possible but fragile: it works only while that phone is awake and
     linked.

   Owner question 1.

4. **Music only.** Cast groups carry audio only (R355 *Out of scope*: video), and books never cast (R323,
   `MusicCast.kt:382`). So *Add a speaker…* appears only on a music session. The FR should say so; R372-2 already
   limits it to "music and books".

5. **Who knows the rooms.** The backend sees nothing when a speaker is added or removed (R355 point 2). The receiver
   runs on the group's endpoint, and CAF gives it only the group's own volume.
   - **The group's members and each member's level are known only to the Android app holding the session's Cast
     link** (`CastSender.members`, plus each member route's volume).
   - So that app reports them to the server: a `members [{ cast_device_id, name, volume, muted }]` field on its
     session report, on change. The server keeps them in `options_json`, with `target_kind = cast_group`.
   - **Consequences, recorded rather than hidden:**
     - FR-R371-3's *{room} left* shows only while such an app is linked.
     - A room added in Google Home with no Ravilo app watching never reaches the server.
     - With no linked Android app, the room rows show *—*, and R357 has nothing to report for them: its receiver report
       is the group level, not a member's.

6. **The master: use Cast's own group volume.** The receiver already obeys a volume command on its events socket
   (`ReceiverRemoteAction.Volume` → `context.setSystemVolumeLevel`, `Receiver.kt:372`) and reports the result (R357
   FR-R357-4).
   - On a group endpoint that is the group's volume, and Cast spreads it over the rooms itself. So `set_volume
     {level}` goes to the receiver from any app, the web and the TV included.
   - **The server never computes room levels from the master.** Two writers would race Cast's own scaling.
   - Acceptance 2's arithmetic (60 → 30 halves 70 and 50) must be checked on the speakers. Cast's group volume may not
     be exactly proportional. If it isn't, the acceptance follows what Cast does.

7. **One room's level.**
   - **Android:** a member route's `requestSetVolume`/`getVolume` (public). MediaRouter has no per-route mute, so a
     room's mute sets the level to 0. The level before the mute is held **on the server** (`options_json`), not in the
     app, so every app shows the same thing.
   - **Desktop:** `ravilo-castv2` can open a connection to each member speaker's own endpoint and send the receiver
     namespace's `SET_VOLUME {level | muted}`, which R330 already sends to one device. It needs a device test that a
     speaker running Google's multizone follower app accepts it.
   - **Web, TV and admin:** only by relay through an attached capable app (item 3).

8. **Wire.** R369's command gains `set_volume { level }` and `set_mute { muted }` (master), plus the same with
   `cast_device_id` (one room).
   - Levels are integer percent 0–100, R354's `RemoteVolume` scale. 236's `player_command` `set_volume {volume}` stays
     for older apps.
   - The master goes to the receiver's socket. A room op goes as `session_command` to the linked Android (or desktop)
     app, which applies it through its own Cast access. With no such app, the server answers `409 unreachable`, and the
     room's slider is disabled.

9. **Phone buttons.** R324's HUD drives the volume of a cast this phone is **linked** to (the Cast SDK). For a session
   the phone controls only through the server, the keys can be caught only while Ravilo is on screen (`onKeyEvent` →
   `set_volume`). With no media session for it, the keys stay the phone's own off screen. FR-R371-4's phone bullet
   holds for the linked case only.

10. **Removing a room.** The **first** room (the group's leader) can't be dropped without `STREAM_TRANSFER` (R355
    point 4 and open question 1).
    - So "removing the second-last room leaves an ordinary one-place session" works only when the room removed isn't
      the first.
    - Removing the first is a R372 *Move to* the remaining room: a new LOAD and a stop, with a short gap. That stands
      until stream transfer is built.
    - Swipe and × are fine.

11. **Strings.** `volume.master` repeats the shipped `cast.volume` word for word in all three languages (*Volume* ·
    *Lydstyrke* · *Ljóðstyrki*), so reuse it. `group.add_speaker`, `group.left` and `volume.no_report` are new.
    `cast.group` (*Speaker group*) stays where a row needs a noun for the group.

12. **Build order.** After R369, which is where commands exist. The master is small: the server forwards to the
    receiver, and the client gets the slider row. It can ship first on every app.
    - Then the Android room work: `MediaRouter2` add and remove, member reporting, member volume.
    - Then the desktop rooms, if the speaker test passes.
    - Tests: the members report replaces the server's room list; a room op with no capable app answers 409; the master
      never writes per-room levels.

**For the owner**

1. **Which apps get *Add a speaker…* now?** **Lean: the Android app in this phase. The desktop follows only if a test
   on the speakers shows our own Cast client can grow a group. The web app, the iPhone, the TV app and the admin
   don't get it,** since they can't reach the speakers.
2. **Each room's own slider where the app can't reach the speakers** (web, TV, admin, and a desktop if item 7's test
   fails). **Lean: show the master everywhere. Show the rooms' names and levels as last reported by the linked
   Android app, with no slider and the reason *{place} doesn't report its volume* when none is linked.** A relay
   through someone's phone is not worth its failures.

**Shipped bugs found (not fixed)**

None.


## Owner decisions (2026-10-04, after the dev review)

1. ***Add a speaker…* is on every app, through the relay** (R370 decision 1): Android and desktop build the group
   directly (Android via `MediaRouter2` routing controllers; desktop after the speaker test proves our Cast client
   can), and the web app, iPhone, TV and admin send `add_room`/`remove_room` to the server, which relays it to the
   Android/desktop app holding the session's Cast link — or, if none, to a relay app on that network, which then
   holds the link. No such app online ⇒ the item is shown disabled with *No phone or computer nearby can reach the
   speakers*.
2. **Each room's own slider on every app, through the same relay.** The linked Android/desktop app reports each
   room's level and applies room changes; the server fans them out. With no linked or relay app, the room sliders
   are disabled with *{place} doesn't report its volume*; the master still works (receiver path).
