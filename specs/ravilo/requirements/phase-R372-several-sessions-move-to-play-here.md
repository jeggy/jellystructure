# Phase R372 — Several sessions at once, *Move to…*, *Play here*, and how sessions end

## Status

`Planned` — written 2026-10-03 (design-authored), **not dev-reviewed**. **Phase 5 of 6** (see R368). Depends on
R368–R371. Designs: `Playback Sessions - Directions.html` §B5 and §B7, and `… - Desktop, TV & Admin.html` §B9
(the TV as a target) and §B11 (the 16-state strip).

## Requirements

**FR-R372-1 — Several sessions per viewer, with no limit.** Each session is separate: its own queue, place, position
and options. R368's bar rule picks which one the bar shows, and *Playing everywhere* lists them all.

**FR-R372-2 — *Move to…*** (canvas §B7). Opened from the remote's place line or ⋯. It lists R370's places with the
current one ticked:

- **Move** (pick another single place). The new place loads the session's queue, index and position (minus 2 s for
  speech, books and episodes), and the old one stops once the new one reports `playing`. During the hand-off the place
  line reads *Moving to {place}…*. If the new place fails, the old one keeps playing and the line says *Couldn't move to
  {place}*.
- **Add or remove places** (tick more than one; music and books only) is R371's group path.
- A film or episode moves only to a `video` place.
- The session keeps its `id`, so every controller stays attached.

**FR-R372-3 — *Play here*.** On a session not playing on this device, *Play here* (⋯, or the session's row in
*Playing everywhere*) is a *Move to…* to **this device**. On the phone it's the existing R299 `cast_play_here`.

**FR-R372-4 — How a session ends.**

- **Paused:** ends **24 h** after the last change. The resume point is the one Jellyfin already has.
- **Stopped** by anyone: ends right away everywhere, and its rows fade out after 60 s (FR-R368-7).
- **Played out:** the queue ends, and the row reads *Finished* for 60 s.
- **The place went away** (switched off, unplugged, no report for 90 s): the session is **paused where it was** and
  marked *{place} is offline · paused at 48:10*. It offers *Play here* and *Move to…* and ends after the 24 h.

**FR-R372-5 — Every state the remote and bar can show** (canvas §B11, the 16-state strip): starting · playing ·
paused · buffering · reconnecting (after a restart) · moving · move failed · adding a place · place offline · can't
reach · stopped by someone else · finished · ended · someone else's (view-only) · someone else's (controllable) ·
older receiver (not in sessions, R356's direct path only). Each state's words are in the strings table. No state uses
the word *session*.

**FR-R372-6 — The TV as a place** (canvas §B9). A Ravilo TV app can be a target like any other:

- *Play on…* lists it (it has an events socket). The TV opens the player with what was sent, whatever page it was on,
  except during R274's sign-in, where it waits.
- A **volume panel** on the D-pad (▲ while the chrome is up, as drawn) shows the TV's volume for sessions sent to it.
- The TV's Home row *Playing in other rooms* is round 2 and not specified here.

**FR-R372-7 — Strings** × en · da · fo (drafts):

| Key | en | da | fo |
|---|---|---|---|
| `session.move_to` | Move to… | Flyt til… | Flyt til… |
| `session.moving` | Moving to {place}… | Flytter til {place}… | Flytur til {place}… |
| `session.move_failed` | Couldn't move to {place} | Kunne ikke flytte til {place} | Fekk ikki flutt til {place} |
| `session.place_offline` | {place} is offline · paused at {time} | {place} er offline · sat på pause ved {time} | {place} er ikki tøkt · steðgað við {time} |
| `session.finished` | Finished | Færdig | Liðugt |
| `session.stopped_by` | {person} stopped it | {person} stoppede den | {person} steðgaði |

## Acceptance

1. *Cannery Lights* on Stue. Eyð picks *Move to… → Kontor*. Kontor starts at the same second, Stue stops, and the
   MacBook's capsule follows without a blink.
2. *Sommeren ’92* paused on Soveværelse TV is gone from *Playing everywhere* after 24 h. Jellyfin's resume point is
   48:10.
3. Soveværelse TV is unplugged mid-film. The Pixel shows *Soveværelse TV is offline · paused at 48:10* with *Play
   here*. Play here resumes on the phone at 48:08.

## Decided by the owner (2026-10-04)

- Paused sessions end after 24 h.
- A place that goes offline pauses its session and keeps it 24 h, instead of ending it.

## Taken as leans

- Moving speech, books and episodes rewinds 2 s.
