# Phase 185 — Remember what each device can take, and how long it actually took

> Reported live: *Until Dawn (2025)* — an 82 Mbps 4K DV/HDR10+ REMUX — stuttered on the living-room TV
> and was abandoned mid-watch. Third stutter on that TV in three weeks. Phase 177 / R216 already read
> that TV's decoder and know it tops out at exactly 60 Mbps, but the number is computed inside one
> `PlaybackInfo` call and thrown away, so nothing can be said about it before someone presses Play.
> This phase writes it down, adds the two facts the sentence needs (the file's bitrate, and how long
> starts have actually taken on that device), and resolves the whole verdict server-side. **R222** renders
> it; this phase decides it.

**Status:** ✓ Built 2026-09-02 (not yet dev-reviewed, not yet live-verified against a real playback
session — compiles clean backend, `linuxX64Test` green incl. new `PlaybackNoteResolverTest`, backend
`GET`/`PATCH` routes not exercised against a live Ravilo client this session).

**Build summary — one real deviation from the drafted schema, made before writing code:** FR-185-1 as
drafted stores one `decode_max_bitrate`/`decode_codec` pair. Built instead as **two** columns,
`decode_max_bitrate_hevc`/`decode_max_bitrate_h264` (+ one shared `decode_measured_at`) — open question 3
already had live evidence this session that a single slot is wrong (every `playback_qoe` row from the
stue TV reports the AVC decoder, because R183/R216 force an AVC transcode target, so a single column
would silently record the AVC ceiling while FR-185-6's predicate needs the HEVC direct-play ceiling for
an HEVC file). This cost nothing extra to build: `ClientCapabilities` already reports both ceilings
together on every negotiation, so `PlaybackService.startPlayback` just persists both, unconditionally,
via `COALESCE` (a negotiation reporting only one codec's ceiling never nulls out a previously-known-good
value for the other). `resolvePlaybackNote`/`playbackNoteFires` pick the column matching the FILE's own
codec (`Track.codec`, from FR-185-3's ffprobe read) — see `PlaybackNoteResolverTest` for the exact case
this fixes.

Every other FR built close to the letter: `Track.videoBitrate`/`videoBitrateSource` (FR-185-3, ladder
verified live against a real matroska file — `streams[].bit_rate` present as a JSON *string* even where
it exists, confirmed before writing the parser); `playback_start_sample` (FR-185-4) written from
`PlaybackService.stopPlayback` on a new optional `startupMs` field on `PlaybackStopRequest` — the client
side of actually measuring and sending this number is **not built** (see below); the resolved
`playbackNote` on `MovieDetail`/`Episode` (FR-185-5/185-9); the `0.9 × ceiling` predicate shared with
Phase 177 verbatim (FR-185-6); the median/expected-vs-measured basis logic (FR-185-7, retention N=10 —
the open question's only unresolved number, picked to match `PlaybackQoeStore`'s own existing default);
the admin device-row second line (FR-185-8), reading the exact same `RaviloDeviceService.decodeCapabilities()`
call the detail payload resolution uses (FR-185-10 — one persisted fact, one representation, literally
the same function call from both call sites).

**Not built:** the Android/Compose client-side instrumentation that measures negotiation-to-first-frame
and actually sends `startupMs` on stop. The backend is fully ready to receive and store it (a client that
never sends it simply never produces `measured` samples — `basis` stays `"expected"` forever, which is a
safe, honest degradation, not a broken state) — this needs `ravilo-android`/`ravilo-ui` changes (measuring
from `PlaybackService.startPlayback`'s call to `RaviloPlayerAndroid`'s `hasRenderedFirstFrame` flipping
true) that were time-boxed out of this pass. Whoever picks this up next: the wire format
(`startup_ms` on `PlaybackStopRequest`) and the storage/read side are both done and tested; only the
client-side timer + wiring it into the stop call remain.

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

**FR-185-1 — Persist the ceiling.** ✅ Built, per-codec rather than one shared value — see the build
summary above for why. `ravilo_device` gains `decode_max_bitrate_hevc` (bps), `decode_max_bitrate_h264`
(bps) and one shared `decode_measured_at` (both ceilings arrive together on every negotiation). Written
on every session negotiation that reports at least one, overwriting the previous value per column
independently (`COALESCE`) — the newest measurement from a device is always the truth, because a
firmware update can change it, and a negotiation reporting only one codec's ceiling must not null out a
previously-known-good value for the other.

> **Timestamp units — check the neighbour you copy.** These two tables disagree today:
> `ravilo_device.last_seen` is **epoch milliseconds**, while `playback_qoe.updated_at` is **epoch
> seconds** (verified against the live DB 2026-09-02). `decode_measured_at` sits on `ravilo_device`, so
> it is **milliseconds**; `playback_start_sample.recorded_at` (FR-185-4) is a new table and should also
> be milliseconds. Do not infer the unit from `playback_qoe`.

**FR-185-2 — Never infer a ceiling.** ✅ Built. Two distinct unknown states, both permanent-until-measured and
both honest:
- **not measured yet** — the device has run no Ravilo session since the R216 build. `NULL`.
- **not measured** — the client cannot report a ceiling at all (Ravilo Web / browser playback).
A `NULL` ceiling means *no note, ever*, for every file. Unknown must never be treated as unlimited, and
must never be treated as constrained.

**FR-185-3 — Carry the file's video bitrate.** ✅ Built, ladder verified live. `Track` (`model/Media.kt`) gains
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

**FR-185-4 — Record how long starts take.** ✅ Built server-side; client-side startupMs measurement NOT built (see build summary). A new append-only `playback_start_sample`
(`device_id`, `item_id`, `file_id`, `seconds`, `recorded_at`), written **only on session completion** —
never mid-session, never from a session that was abandoned before first frame. `seconds` is
negotiation-to-first-frame as the client reports it. Retention: the most recent N per
(device, file) — older samples pruned, because a firmware update or a network change makes ancient
samples misleading. **N ≥ 3**, or FR-185-7's `measured` basis is unreachable by construction and the
softer sentence is the only one that can ever ship.

**FR-185-5 — Resolve the verdict server-side.** ✅ Built. The Ravilo detail payload carries, per file, for the
requesting device:

```
playbackNote: { device: "Bedroom TV", basis: "measured" | "expected", seconds: 20 }
```

Absent when there is nothing to say. The client never receives a bitrate, a ceiling, a margin or a
delivery method (R180 FR-RV-ASP1-2, and the constitution's *frontend renders server-pushed state only*).
`device` is the user-set device name, so the server owns that string too.

**FR-185-6 — One predicate, two consumers.** ✅ Built, shares Phase 177's own 0.9 margin constant conceptually (kept as a separate named constant, `NOTE_MARGIN`, so a future retune of one doesn't silently retune the other without a deliberate edit). Whether the note exists is decided by exactly the
comparison Phase 177 already makes to force a transcode — the file's video bitrate against
**0.9 × the recorded ceiling** — and by nothing else. If that margin is ever retuned, both move
together. A note without a re-encode behind it, or a re-encode with no note, is a bug in this phase —
with exactly one legitimate exception: Phase 177 compares against the ceiling the device reports **in
that session's negotiation**, while the note compares against the **last recorded** one. A firmware
update that moves the ceiling therefore makes them disagree for exactly one play, after which FR-185-1's
overwrite reconciles them. Do not add a second measurement path to close that window; it is one wrong
sentence, once, per firmware change.

**FR-185-7 — History chooses the sentence; it can never toggle the note.** ✅ Built, retention N=10. This is the rule that keeps
the flicker out:
- `basis: "expected"` — the predicate fires, fewer than **3** start samples exist for this
  (device, file). No `seconds`.
- `basis: "measured"` — the predicate fires and there are ≥3 samples. `seconds` is their **median**,
  rounded to the nearest 5 s.
- Re-derived **only** when a new session completes — never per request, so two page views a minute
  apart cannot disagree.
QoE, link state, rebuffer counts and observed throughput **never** feed this field, in either direction.
They stay where R216 put them: admin-side diagnostics.

**FR-185-8 — Show it in the admin, in plain words.** ✅ Built — one deliberate simplification: the admin card always reads "not measured yet" for the unknown state rather than trying to distinguish it from "not measured" (a browser client), since stored state alone can't tell the two apart without more plumbing than this pass budgeted for. Each Ravilo device row on the Users & devices page
carries a second line in its own cell — *"what this device can take · up to 60 Mbps · measured
yesterday"* — with the two unknown states from FR-185-2 spelled out (`not measured yet · no Ravilo
session since the update`, `not measured · the browser never tells us`). Deliberately **not** a sixth
column: that table is shared with the Admin web sessions and Recently watched sections, which have
nothing to put in one. Numbers are allowed here; this is the admin, not Ravilo.

**FR-185-9 — Per file, never per title.** ✅ Built — keyed by `Episode.file`/`MediaItem.path`, the same Phase 149 grouping key R179 already established. A ceiling is per device and a bitrate is per file, so the note
is a property of the thing that plays. A Phase 149 combined multi-episode file is one file and resolves
to **one** note.

**FR-185-10 — One persisted fact, one representation.** ✅ Built — literally the same `RaviloDeviceService.decodeCapabilities()` call from both the admin route and `DetailService`. The admin row and the resolved `playbackNote`
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
   yes, and it has been since 2026-08-30.** The stue TV (`BRAVIA_4K_VH21`, 10.0.0.11) was running a
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
3. ~~**Key the ceiling per codec, or one value per device?**~~ **Resolved, built per-codec, 2026-09-02.**
   `ravilo_device` gained `decode_max_bitrate_hevc`/`decode_max_bitrate_h264` (two columns, one shared
   timestamp) rather than one shared value — exactly the live-data argument this question already made.
   `ClientCapabilities` reports both ceilings together on every negotiation, so this cost no extra
   client round trip; `resolvePlaybackNote` picks the column matching the file's own codec.
4. **Retention N for start samples** — bounded below at 3 by FR-185-4; **built with N = 10** (matching
   `PlaybackQoeStore.recentForDevice`'s own existing default — no stronger signal available to pick a
   different number). Whether a firmware change (detectable via a changed ceiling) should discard the
   history for that device is still open: the samples were measured against a decoder that no longer
   exists, which argues yes, but nothing was built to detect or act on that — a future firmware bump
   just quietly dilutes the median with stale samples until they age out at N=10.
