# Phase R370 — One *Play on…* list: start on any place, join or start new

## Status

`Planned` — written 2026-10-03 (design-authored), **not dev-reviewed**. **Phase 3 of 6** (see R368). Depends on
R368 and R369. Designs: `Playback Sessions - Directions.html` §B3 (one *Play on…* list) and §B6 (join-or-new moments).

## Requirements

**FR-R370-1 — One list of places, from the server.** `GET /api/tv/targets` → `[Target { id, kind, name, icon,
capabilities { video, audio, display }, busy: SessionRef?, reachable }]`. It merges Ravilo devices with an events
socket open and Cast receivers (each scanning app reports what it finds, and so does the backend; report §4.4).
**Single places only** (owner, 2026-10-03): no group, *whole house* or *everywhere* entry — a speaker group the
household made in the speakers' own app is left out too. More rooms are added after starting (R371). The server removes duplicates. Every *Play on…* sheet and popover shows this list. Today's phone-only
discovery list becomes a fallback for when the server can't be reached.

**FR-R370-2 — Four tiers, in this order** (canvas §B3):

1. **This device** (*This phone* / *This Mac* / *This computer*).
2. **Playing now**: places with a session, each showing what it plays.
3. **Free places**: TVs and displays first, then speakers, by name.
4. **Unreachable**: last seen within 24 h, dimmed, with *Not reachable*. They can't be picked.

The list depends on the kind: film and episode list only `video` places, music and books list all of them. A place that
can't play this kind is absent, not greyed. The iPhone keeps R324's footnote and its own rules.

**FR-R370-3 — Starting on a place.** `POST /api/tv/sessions { target_id, items, index, start_ms, options }` creates
the session (FR-R368-1) and tells the target to load it. An app target gets a `session_load` event; a cast target is
loaded by the backend through its channel. If the backend can't load a receiver itself, the sending app loads it and
attaches. The session starts as `starting`, and the app opens the remote straight away.

**FR-R370-4 — A place that is busy always asks (owner, 2026-10-04)** (canvas §B6). Choosing a place that already
has a session opens an inline choice on that row, **every time** (never remembered, no *Remember* switch):

- **Play {title} here instead.** The current session there ends at its position (a paused film keeps its resume point
  as today), and a new one starts.
- **Cancel.**

There is **no *Add to what's playing*** — adding to a queue stays in the song menu (*Play next*, R322). The same
always-ask applies when the viewer presses Play on something of the same kind already playing for them elsewhere
(music while their music plays on Stue; an episode of a series playing on a TV): *Play there instead* or *Play here*
(the other keeps playing). On someone else's session, *Play here instead* is offered only when 304's switch is on, and
reads *Stop {person}'s {title} and play here?*. A place busy outside Ravilo keeps R324's *Stop Spotify and play here?*.

**FR-R370-5 — Starting a second session is just starting one.** Picking a free place while something plays elsewhere
creates a **new** session. The other one keeps playing, and the bar now shows +1 (FR-R368-8). There's no "you already
have one" warning.

**FR-R370-6 — Strings** × en · da · fo (drafts):

| Key | en | da | fo |
|---|---|---|---|
| `target.playing_now` | Playing now | Spiller nu | Spælir nú |
| `target.free` | Free | Ledige | Leys |
| `target.unreachable` | Not reachable | Kan ikke nås | Fæst ikki samband við |
| `target.replace` | Play {title} here instead | Afspil {title} her i stedet | Spæl {title} her í staðin |

## Acceptance

1. With nothing playing, Eyð's Pixel *Play on…* lists This phone · Stue · Gæsteværelse · Kontor · Soveværelse TV, and
   nothing that plays on several places at once.
2. *Cannery Lights* plays on Stue, and she picks Stue for another album. The row asks *Play … here instead / Cancel*
   — no *Add*. She does it again a minute later and is asked again.
3. She starts *Sommeren ’92* on Soveværelse TV from the MacBook. Stue keeps playing, and the capsule shows **+1**.
4. Kontor is switched off. It shows *Not reachable* at the bottom and can't be picked.

## Taken as leans

- Four tiers in this order. Unreachable places listed for 24 h.

## Decided by the owner (2026-10-03)

- **No cast-everywhere button.** *Play on…* lists single places only; you pick one, then add another (R371).
- **2026-10-04: a busy place always asks, never offers *Add*** (FR-R370-4).
