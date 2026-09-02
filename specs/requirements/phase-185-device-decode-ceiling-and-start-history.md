# Phase 185 — Remember what each device can take, and how long it actually took

> Reported live: *Until Dawn (2025)* — an 82 Mbps 4K DV/HDR10+ REMUX — stuttered on the living-room TV
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

> **Timestamp units — check the neighbour you copy.** These two tables disagree today:
> `ravilo_device.last_seen` is **epoch milliseconds**, while `playback_qoe.updated_at` is **epoch
> seconds** (verified against the live DB 2026-09-02). `decode_measured_at` sits on `ravilo_device`, so
> it is **milliseconds**; `playback_start_sample.recorded_at` (FR-185-4) is a new table and should also
> be milliseconds. Do not infer the unit from `playback_qoe`.

**FR-185-2 — Never infer a ceiling.** Two distinct unknown states, both permanent-until-measured and
both honest:
- **not measured yet** — the device has run no Ravilo session since the R216 build. `NULL`.
- **not measured** — the client cannot report a ceiling at all (Ravilo Web / browser playback).
A `NULL` ceiling means *no note, ever*, for every file. Unknown must never be treated as unlimited, and
must never be treated as constrained.

**FR-185-3 — Carry the file's video bitrate.** `Track` (`model/Media.kt`) gains
`videoBitrate: Int?` (bps), set only for `kind == VIDEO`, alongside the existing `width`/`height`/
`videoRange`. It is **not** a database column: `Track` is serialized inside the item's own record, so
this rides the existing blob and needs no migration — but it does mean an existing item only gains the
field when it is re-probed, i.e. backfilled on the next scan.

**The source is ffprobe, not Jellyfin.** `Track` is built entirely by `FfprobeRunner.probe()`
(`media/FfprobeRunner.kt:102`); `JellyfinMediaStream` (`auth/Models.kt:176`) carries no `BitRate` field
at all and is only used for playback negotiation, never for track ingest. A single-source read is not
enough either — measured over a 30-file random sample of this library:

| where the video bitrate actually is | share |
| --- | --- |
| `streams[].bit_rate` | 33% (mp4/mov, some others) |
| absent on the stream, present as the container tag `BPS` / `BPS-eng` | 50% (matroska) |
| neither — only `format.bit_rate` | 17% |
| nothing at all | 0% |

Matroska does not store a per-stream bitrate, so `streams[].bit_rate` is `null` for two thirds of the
library — including exactly the 4K REMUXes this phase exists for. Read it as a ladder, first hit wins:

1. `streams[].bit_rate` on the video stream.
2. the video stream's `BPS` tag — key match is **case-insensitive prefix `BPS`**, since both `BPS` and
   the language-suffixed `BPS-eng` occur live (19 vs 4 in a 25-file matroska sample). `@SerialName`
   cannot express a prefix, so `FfprobeTags` needs the tag map read generically rather than one field
   per spelling.
3. `format.bit_rate` as the floor — it includes audio and subtitles, so it over-states the video
   stream, but on a heavy file the video dominates and an over-estimate is the safe direction here: it
   can only make the note fire slightly early, never suppress it.

This requires `FfprobeRunner.probe()`'s command to gain `-show_format` (today it is `-show_streams`
only) and `FfprobeStream` to gain `bit_rate`. Record which rung supplied the value, so the admin can
tell a measured bitrate from a container-level estimate.

`null` after all three rungs means *no note* — but per the sample that should be no file at all, and if
it starts happening it is a scanner bug, not a normal state.

**FR-185-4 — Record how long starts take.** A new append-only `playback_start_sample`
(`device_id`, `item_id`, `file_id`, `seconds`, `recorded_at`), written **only on session completion** —
never mid-session, never from a session that was abandoned before first frame. `seconds` is
negotiation-to-first-frame as the client reports it. Retention: the most recent N per
(device, file) — older samples pruned, because a firmware update or a network change makes ancient
samples misleading. **N ≥ 3**, or FR-185-7's `measured` basis is unreachable by construction and the
softer sentence is the only one that can ever ship.

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
together. A note without a re-encode behind it, or a re-encode with no note, is a bug in this phase —
with exactly one legitimate exception: Phase 177 compares against the ceiling the device reports **in
that session's negotiation**, while the note compares against the **last recorded** one. A firmware
update that moves the ceiling therefore makes them disagree for exactly one play, after which FR-185-1's
overwrite reconciles them. Do not add a second measurement path to close that window; it is one wrong
sentence, once, per firmware change.

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

1. ~~**Is the R216 build actually installed on the living-room TV?**~~ **Answered on-device 2026-09-02:
   yes, and it has been since 2026-08-30.** The stue TV (`BRAVIA_4K_VH21`, 192.0.2.11) was running a
   build installed 2026-08-30 20:37, from `e684a516` — two days *after* R216 landed in `87235de8`
   (2026-08-28). Confirmed independently by the data rather than by timestamps alone: `playback_qoe`
   holds **105 rows** for that device (`84a57080…`), populated with exactly the fields R216 added
   (`video_decoder`, `link_kind`, `link_mbps`, `bandwidth_estimate_bps`). R216's transcode fallback is
   demonstrably firing too — the heavy 2026-09-01 sessions record `direct_play = 0`, and
   `dropped_frames` is **0 on every row**.

   **So the copy R222 ships is the right copy.** Through Ravilo the file re-encodes and starts slowly;
   it does not stutter. The *Until Dawn* stutter that triggered this work was a **Wholphin** session,
   which negotiates straight against Jellyfin and never reaches any of this — exactly as the research
   report concluded, and now positively confirmed rather than assumed. Translation is unblocked.

   (All three devices were brought to `HEAD` on 2026-09-02 anyway, so R220 is live for the first time.)
2. **Do other OEM decoders report honest ceilings?** R216's own open question #1. A decoder that
   over-reports means a note that never fires (harmless); one that under-reports means a note on
   everything (the feature dies of distrust). Only testable as more devices join.
3. **Key the ceiling per codec, or one value per device?** FR-185-1 stores the codec alongside the value.
   **Live data now argues for per-codec.** Every `playback_qoe` row from the stue TV — 4K sessions
   included — reports `video_decoder = OMX.MTK.VIDEO.DECODER.AVC`, i.e. the **AVC** decoder, because
   R183/R216 force an AVC transcode target. A single per-device column would therefore record the
   *AVC* ceiling, while the number the FR-185-6 predicate actually needs is the **HEVC direct-play**
   ceiling of the file being considered. Storing one value per device risks comparing a HEVC REMUX's
   bitrate against an AVC decoder's limit. Resolve before building.
4. **Retention N for start samples** — bounded below at 3 by FR-185-4, but the actual value is open.
   And whether a firmware change (detectable via a changed ceiling) should discard the history for that
   device: the samples were measured against a decoder that no longer exists, which argues yes.
