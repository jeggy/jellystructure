# Phase 185 — Remember what each device can take, and how long it actually took

> Reported live: *Till Daybreak (2025)* — an 82 Mbps 4K DV/HDR10+ REMUX — stuttered on the living-room TV
> and was abandoned mid-watch. Third stutter on that TV in three weeks. Phase 177 / R216 already read
> that TV's decoder and know it tops out at exactly 60 Mbps, but the number is computed inside one
> `PlaybackInfo` call and thrown away, so nothing can be said about it before someone presses Play.
> This phase writes it down, adds the two facts the sentence needs (the file's bitrate, and how long
> starts have actually taken on that device), and resolves the whole verdict server-side. **R222** renders
> it; this phase decides it.

**Status:** Planned (design-authored 2026-09-02, not yet dev-reviewed)

Research: `specs/research-reports/ravilo-per-device-decode-ceiling-warning-2026-09-02.md`
Design: `design/ravilo/Decode Ceiling Warning - Directions.html` (A + B′ chosen; C and D recorded as
rejected). Admin half implemented in the mockup at `design/app/ravilo-users.html`.

## Current state

Three separate gaps, each small, which together make the sentence impossible:

1. **The ceiling is ephemeral.** `detectDecoderLimits()` (R216) reads the device's own
   `MediaCodec.getBitrateRange()` for the codec it will actually pick, and the server turns it into a
   `VideoBitrate` device-profile condition at a 0.9 margin so Jellyfin transcodes instead of breaking.
   Correct, and invisible: `ravilo_device` has no column for it, and `playback_qoe` is session-scoped
   and pruned. No detail request can ask "what can this TV take".
2. **The file's bitrate isn't stored.** `Track` carries codec, resolution, HDR flags and language, but
   no video bitrate — so even with a ceiling on hand there is nothing to compare it against.
3. **Nothing times a start.** How long a session took to reach first frame is not recorded anywhere, so
   there is no way to say "about 20 seconds" and no way to know whether the estimate is any good.

The measuring half of the owner's proposal therefore exists in the only place it is useless: inside a
decision, not inside a record.

## Goal

Given a (device, file) pair, the backend can answer one question — *will this be slow to start here, and
if so how slow* — and hand Ravilo a finished sentence's worth of data. Nothing about bitrates, ceilings
or delivery methods leaves the server.

## Functional requirements

**FR-185-1 — Persist the ceiling.** `ravilo_device` gains `decode_max_bitrate` (bps),
`decode_codec` (the codec the ceiling was measured for) and `decode_measured_at`. Written on every
session negotiation that reports limits, overwriting the previous value — the newest measurement from a
device is always the truth, because a firmware update can change it.

**FR-185-2 — Never infer a ceiling.** Two distinct unknown states, both permanent-until-measured and
both honest:
- **not measured yet** — the device has run no Ravilo session since the R216 build. `NULL`.
- **not measured** — the client cannot report a ceiling at all (Ravilo Web / browser playback).
A `NULL` ceiling means *no note, ever*, for every file. Unknown must never be treated as unlimited, and
must never be treated as constrained.

**FR-185-3 — Carry the file's video bitrate.** `Track` gains `video_bitrate` (bps), read from Jellyfin's
`MediaStreams[].BitRate` at scan time and written by the same engine that writes the rest of the track
row (Phase 175's unified ingest). Backfilled on the next scan of an existing item; `NULL` where Jellyfin
reports nothing, which also means *no note*.

**FR-185-4 — Record how long starts take.** A new append-only `playback_start_sample`
(`device_id`, `item_id`, `file_id`, `seconds`, `recorded_at`), written **only on session completion** —
never mid-session, never from a session that was abandoned before first frame. `seconds` is
negotiation-to-first-frame as the client reports it. Retention: the most recent N per
(device, file) — older samples pruned, because a firmware update or a network change makes ancient
samples misleading.

**FR-185-5 — Resolve the verdict server-side.** The Ravilo detail payload carries, per file, for the
requesting device:

```
playbackNote: { device: "Bedroom TV", basis: "measured" | "expected", seconds: 20 }
```

Absent when there is nothing to say. The client never receives a bitrate, a ceiling, a margin or a
delivery method (R180 FR-RV-ASP1-2, and the constitution's *frontend renders server-pushed state only*).
`device` is the user-set device name, so the server owns that string too.

**FR-185-6 — One predicate, two consumers.** Whether the note exists is decided by exactly the
comparison Phase 177 already makes to force a transcode — the file's video bitrate against
**0.9 × the recorded ceiling** — and by nothing else. If that margin is ever retuned, both move
together. A note without a re-encode behind it, or a re-encode with no note, is a bug in this phase.

**FR-185-7 — History chooses the sentence; it can never toggle the note.** This is the rule that keeps
the flicker out:
- `basis: "expected"` — the predicate fires, fewer than **3** start samples exist for this
  (device, file). No `seconds`.
- `basis: "measured"` — the predicate fires and there are ≥3 samples. `seconds` is their **median**,
  rounded to the nearest 5 s.
- Re-derived **only** when a new session completes — never per request, so two page views a minute
  apart cannot disagree.
QoE, link state, rebuffer counts and observed throughput **never** feed this field, in either direction.
They stay where R216 put them: admin-side diagnostics.

**FR-185-8 — Show it in the admin, in plain words.** Each Ravilo device row on the Users & devices page
carries a second line in its own cell — *"what this device can take · up to 60 Mbps · measured
yesterday"* — with the two unknown states from FR-185-2 spelled out (`not measured yet · no Ravilo
session since the update`, `not measured · the browser never tells us`). Deliberately **not** a sixth
column: that table is shared with the Admin web sessions and Recently watched sections, which have
nothing to put in one. Numbers are allowed here; this is the admin, not Ravilo.

**FR-185-9 — Per file, never per title.** A ceiling is per device and a bitrate is per file, so the note
is a property of the thing that plays. A Phase 149 combined multi-episode file is one file and resolves
to **one** note.

**FR-185-10 — One persisted fact, one representation.** The admin row and the resolved `playbackNote`
read the same column. They cannot drift.

## Non-goals

- **No user-visible setting, anywhere.** R216's invariant stands: nothing here is settable, adjustable,
  dismissible or overridable. There is no quality picker and this phase does not add the first step
  toward one.
- **Nothing for non-Ravilo clients.** The session that triggered this report was **Wholphin**, which
  negotiates its own device profile straight against Jellyfin. It is architecturally unreachable — not a
  limitation to be fixed in a later phase. Ravilo says nothing about it, and the admin device row is
  where a curious operator finds out why.
- **No cross-device advice.** "Better on the Living room TV" needs a second measured device, a claim the
  other TV is free, and household logistics. If it ever ships it is its own phase.
- **No new empty/warning states** on the admin page beyond the two unknowns — and no badge, banner or
  count anywhere else in the admin.
- **No prediction of stutter.** With 177/R216 live the file does not stutter; it re-encodes and starts
  slowly. This phase describes that, and nothing more.

## Open questions

1. **Is the R216 build actually installed on the living-room TV?** If it is not, the transcode fallback
   is not live there either, the file genuinely stutters, and the copy R222 ships is the wrong copy — it
   would have to be about picture quality instead of start time. Confirm on-device before building.
2. **Do other OEM decoders report honest ceilings?** R216's own open question #1. A decoder that
   over-reports means a note that never fires (harmless); one that under-reports means a note on
   everything (the feature dies of distrust). Only testable as more devices join.
3. **Key the ceiling per codec, or one value per device?** FR-185-1 stores the codec alongside the value;
   whether a device needs several rows (HEVC vs AV1) depends on how far apart real decoders are.
4. **Retention N for start samples**, and whether a firmware change (detectable via a changed ceiling)
   should discard the history for that device.
