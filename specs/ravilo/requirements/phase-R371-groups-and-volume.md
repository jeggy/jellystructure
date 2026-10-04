# Phase R371 — More rooms one at a time, and volume for all of them and each room

## Status

`Planned` — written 2026-10-03 (design-authored), **not dev-reviewed**. **Phase 4 of 6** (see R368). Depends on
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
