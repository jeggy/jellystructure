# Phase R369 — Any Ravilo app controls a session

## Status

`Planned` — written 2026-10-03 (design-authored), **not dev-reviewed**. **Phase 2 of 6** (see R368). Depends on
R368. Designs: `Playback Sessions - Directions.html` §B2 (the bar as a remote) and §B5 (the remote).

## The rule

A command goes **to the server**, the server sends it to the session's target, and every app shows what the target
then reports. No app tells another app directly. The phone that sent a cast keeps its direct link as a faster path
(R356), but the server stays the record.

## Requirements

**FR-R369-1 — Commands.** `POST /api/tv/sessions/{id}/command { revision, op, args }` where `op` ∈ `play` · `pause` ·
`seek {position_ms}` · `next` · `previous` · `jump {index}` · `set_shuffle {on}` · `set_repeat {mode}` ·
`set_audio {stream}` · `set_subtitle {stream}` · `stop`. Queue edits (`add`, `move`, `remove`) reuse the R322 queue
ops on the session. A command whose `revision` is out of date is still applied when it can't conflict (play, pause,
stop). A seek or queue edit based on an old revision gets `409 { session }`; the app redraws and does nothing more.

**FR-R369-2 — How a command reaches the target.** An **app** target gets it as a `session_command` event on its own
events socket (the TV, phone or desktop player already acts on R354's dashboard commands; this reuses that path). A
**cast** target gets it through the receiver's custom channel from the backend (report §4.3). If the backend can't
reach the receiver, the command goes to the sender phone when one is attached, and otherwise fails with
`session.cant_reach`.

**FR-R369-3 — Who can control.** Anyone whose own session it is. Another member's session can be controlled only
when 304's household switch is on. An **admin** controls every session fully from the admin (304), whatever the switch
says. `SessionView.controllable` carries the answer, and the app never works it out itself.

**FR-R369-4 — Controllers.** Opening a session's remote (or controlling it from the bar) adds this device to
`playback_session_controller`, and closing the remote removes it. There's no limit. Two apps that press at the same
moment are handled by FR-R369-1's revision rule. The latest press wins and both apps show the outcome.

**FR-R369-5 — The bar is a remote** (canvas §B2). The FR-R368-8 bar has ⏯ and next for any session where
`controllable` is true. Tapping the bar opens the remote. The **+N chip** opens *Playing everywhere*.

**FR-R369-6 — The remote** (canvas §B5) is today's Now playing (phone), Playing page (desktop) and book player,
pointed at a session:

- A **place line** under the title, in accent, with the place's icon: *Playing on Stue + Gæsteværelse*. Tapping it
  opens R372's *Move to…*.
- The transport, the seek bar, the queue, lyrics, shuffle and repeat all act on the session.
- Volume is R371's.
- ⋯ adds **Play here** and **Stop** (R372 defines them). **Stop** ends the session wherever it plays, after an inline
  confirm the first time on someone else's session.
- While a command is in flight the control dims for up to 400 ms. There's no spinner unless the wait passes 1 s, then
  the R218 small spinner shows by the timestamp.

**FR-R369-7 — The position stays smooth.** Between reports, an app moves the bar forward from
`position_ms + (now − position_at)` while the state is `playing`, and snaps to the next report. A target reports at
least every 5 s while playing, and right away after any command.

**FR-R369-8 — Strings** × en · da · fo (drafts):

| Key | en | da | fo |
|---|---|---|---|
| `session.cant_reach` | Can't reach {place} | Kan ikke nå {place} | Fái ikki samband við {place} |
| `session.stop_everywhere` | Stop on {place} | Stop på {place} | Steðga á {place} |
| `session.stop_person` | Stop {person}'s {title}? | Stop {person}s {title}? | Steðga {title} hjá {person}? |

## Acceptance

1. *Cannery Lights* plays on Stue from Eyð's Pixel. On her MacBook she presses pause in the capsule. Stue pauses
   within a second, and the Pixel's Now playing shows *Paused*.
2. The Pixel is switched off. The MacBook still pauses, skips and seeks Stue (the backend reaches the receiver itself).
3. The Pixel and the MacBook press next at the same moment. Stue skips once, not twice (the second command is stale for
   `next`, so it gets a 409 and is dropped).
4. Olivar's session shows no ⏯ on Eyð's phone while 304's switch is off.

## Taken as leans

- A stale `next` is dropped (409) rather than applied twice.
- Stopping someone else's session asks once.
