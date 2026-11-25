# A play on a thin path always has a lower rung to fall to

Task: DEB-4541

> Owner, 2026-11-14: *"We were streaming via Chromecast [away from home] just now, but it was very laggy. Can you check the
> logs and figure out why and what can be done so that the lagging would fix on its own."*

## Scope

Touches the backend (`tv/PlaybackService`, `tv/EncoderPlan`, `tv/VideoLadder`) and the Cast receiver
(`ravilo-cast/…/Receiver.kt`, which ships with the backend). No app release, no wire change: every field used here
already exists on `PlaybackQoeReport` and `QoeStall`. It amends FR-313-3, FR-313-10 and FR-313-12 (a dated note in that
file) and DEB-3093 FR-9's Cast receiver entry (a dated note in `specs/ravilo/requirements/trouble-never-reaches-the-viewer.md`).
It does not build the copied-top-rung ladder that DEB-3093 names under *What this does not do yet*; this is the smaller
step that removes the same failure today.

**The trade-off is the owner's and settled** (DEB-3093): lower the quality before ever stopping the picture.

## Why

Two casts from the owner's phone to a Chromecast outside the house, 2026-11-14 01:41–01:58Z, read from prod's
`playback_qoe` and the backend log:

| | first episode | second episode |
|---|---|---|
| served by | Jellyfin, video copied (`TranscodeReasons=DirectPlayError`, `reencodes=false`) | the same |
| rungs in the master | one (`no ladder`) | one |
| stream | 576p H.264, the file's own ~2.4 Mb/s | the same |
| start cap | `takes no record → cap 8000k` | `takes 3260k (measured 4657k)` |
| first frame | 29.4 s | 23.5 s |
| stalls | 10, 41.9 s | 14, 102.3 s |
| steps down | 0 | 0 |
| Shaka's estimate at the end | 4.66 Mb/s | 0.80 Mb/s |

About a fifth of the second episode's wall time was a stopped picture. The path carried between about 0.8 and 5 Mb/s
and swung inside that range: the phone's own probe from the same place read 5.3 Mb/s twenty minutes later.

Three things in the code made that a stop, not a lower picture:

1. **A video copy gets one stream.** Our encoder takes a play only when Jellyfin's answer re-encodes the picture
   (`reencodesVideo`), and Jellyfin's own ladder has the same rule. A file that needs only its container or its sound
   changed for the receiver is served as a single variant at the file's bitrate, so the receiver's ABR has nothing to
   step down to however slow the path gets. FR-308-1 says a transcoded stream lists a ladder; the copy was left out
   because a lower variant would be a different encode cut on different boundaries. Our encoder cuts every rung on one
   grid, so that reason does not hold for it.
2. **The floor is 480p at 1.5 Mb/s,** on our encoder and on Jellyfin's ladder alike. On this path, at the bottom of its
   swing, even the floor would have stopped.
3. **The server learned nothing from 24 stalls.** The receiver's QoE report carries no `per_item` marker, so 309's
   record ignores it (`foldIntoRecord` reads only per-item reports, per R381), and its `stalls` list holds recoveries
   only, never an ordinary wait. The receiver has no `device_stream_record` row at all. The second episode started
   exactly as the first had.

The class over the 30 days to 2026-11-14: of the 95 plays Jellyfin served (apps and casts), 8 stalled (8.4 %), with 40
stalls and 389 s stopped; of the 559 on our encoder, 10 stalled (1.8 %). Netflix and YouTube keep rungs down to about
240p at a few hundred kb/s for exactly this, and pick the rung from how full the buffer is.

## Requirements

### FR-1 — A video copy to a player that adapts goes to our ladder unless the path is proven

When Jellyfin's answer is a transcode that copies the picture (`reencodesVideo` false), the device declares
`hls_adaptive`, and our encoder could serve the play under FR-313-12, the play goes to **our encoder** with a ladder,
unless the device's record proves the path:

- **Proven** means the device's take (309, FR-309-1: its measured × 0.7, under any stall cap) is at least **twice** the
  copy's stream bitrate (the source's video bitrate plus the negotiated audio), and no stall hold (DEB-3093 FR-7) is live
  for it. A proven path keeps Jellyfin's copy, which is the file's own picture at no encoder cost. On the household LAN a
  take is tens of Mb/s, so nothing changes there.
- **Not proven** (no record, a take under twice the copy, or a live hold) goes to our encoder. The ladder is planned
  from the source as any other play on our encoder: the top rung is the source's own size at no more than its own
  bitrate (FR-313-3's output cap still applies), and the rungs below it follow FR-2.
- The rule holds at the start and at every restream (a stall's move to the ladder, a recovery, a hand-over), so a path
  that was proven and then failed reaches the ladder on the restream through the hold that FR-3 writes.
- A fallback under FR-313-12 (no slot, no file, a refused source) keeps Jellyfin's copy and logs as today.
- One log line per decision: `playback: item=<id> video copy → our ladder (take <k>k < 2 × copy <k>k | no record |
  stall hold) (DEB-4541 FR-1)`, and `… video copy kept (take <k>k ≥ 2 × copy <k>k)` when proven.

### FR-2 — The ladder reaches down to what a thin path carries

On our encoder, 308's table gains two floor rungs below its 480p rung:

| rung | H.264 | HEVC |
|---|---|---|
| 360p | 800 kb/s | 600 kb/s |
| 240p | 400 kb/s | 300 kb/s |

- A floor rung is listed only if it sits clearly below the rung above it (`RUNG_GAP`, × 0.8) and below the source.
- **A path that is not known to be fast puts the floor first.** When the device has no record, its take is under
  **6 Mb/s**, or a stall hold is live, the four-rung limit (FR-313-3, FR-313-8) keeps the top rung, then 240p, then 360p,
  and drops rungs from just under the top first. Both of tonight's casts are this case (no record, then a take of
  3.26 Mb/s).
- **Otherwise** the limit keeps today's rungs, and a floor rung fills a slot only if one is free (a full four-rung ladder is
  unchanged).
- The start rung is chosen as today (309), from the rungs that are listed.
- Jellyfin's own ladder keeps its table. A play that reaches it is one our encoder could not take.

### FR-3 — The Cast receiver reports what it went through, per item

The receiver's QoE report is the Android player's shape, so 309's record and DEB-3093 FR-7's hold work for a cast:

- `per_item` is `true`. Every count (rebuffers, rebuffer ms, steps, first frame, dropped frames) is that item's own and
  resets at each `LOAD`, as FR-R381-1 says for Android.
- Every wait after the first frame that lasts 1 s or more is a `QoeStall` entry in `stalls`: its position, its length,
  the buffered time when it began, `phase` (`start` within 10 s of the first frame, `mid` after), `cause`
  (`buffer_empty`, or `recovery` as today), and the rung it happened on (`variant_bps`, `rung_height`, `rung_bps`).
- A report is posted when a stall ends, as well as at a rung switch and at the end, so a stall reaches the record within
  seconds rather than at the end of the episode.

### FR-4 — A cast that stalls with no lower rung asks for one

When a play on the receiver stalls and its master lists no rung below the one playing (a single-variant stream, or the
lowest rung already), the receiver asks for a restream at its position through DEB-3093 FR-9's restream recovery
(`warmHlsStart`, then `LOAD`):

- It asks after the second stall within 2 minutes, or after one stall of 4 s or more.
- The request carries the stall (FR-3 posts it first), so the server's FR-1 sees the hold and answers with our ladder.
  If the server answers with the same single stream again, the receiver does not ask again for that item.
- The recovery is a `recovery` entry with the existing reason `stall_to_ladder`.
- CAF resets the player on a `LOAD`, so the switch is a short stop (DEB-3093 FR-9). It is one stop instead of a train of
  them, and it happens at most once per item.

### FR-5 — The decision is visible

The encoder summary's `held_by` (DEB-3093 FR-7) gains `floor_rungs` when FR-2 added them, and the admin's *Playing now*
shows a play that FR-1 moved to our encoder as `encoder=ours`, as any other. Nothing new is drawn.

### FR-6 — A cast that never reaches a picture says so

*Added 2026-11-14, DEB-4569.* From 10-31 to 11-14, every cast with more than one rung in its master failed before its first
frame. The receiver's Shaka request timeout was set to 20, which Shaka reads as 20 ms, not 20 s (DEB-3093 FR-3, restored on
its own task). The server never learned of it. The receiver reports a failed load only as a `note` on the log channel, which
only the Mac sender reads. Its QoE row for that item is a row of zeros with no `start_failure_status`, and the backend logs
nothing. Nine starts failed that way one morning. The only trace was a `playback stop … at 0ms` that reads like a viewer
stopping.

- When the receiver ends an item that never reached a first frame (CAF's `ERROR` with the player idle, which today goes to
  `stopSession()` and `failed()`), its final QoE report for that item sets `startFailureStatus` (FR-R237-6). The value is the
  HTTP status of the failed request when the error carries one, and `-1` otherwise, the same meaning FR-R218-7 gives it. The
  field already exists. No wire change.
- The backend logs one line for every QoE report that carries `startFailureStatus`, from any device:
  `playback: start failed device=<id> item=<id> status=<n> segments=<bandwidthSamples> (DEB-4569 FR-6)`. A failed start is
  then as easy to find in the log as a stall.

## Acceptance

By a QA seat on a Chromecast from the board, after the build is on prod, with a server-side `tc` cap on the receiver
(the QA cap technique) and **an uncapped control leg** of the same file on the same receiver. The file is one Jellyfin
answers for the receiver with a video copy (`no ladder: … reencodes=false` in today's log).

1. **Control, uncapped, on the household LAN, with a record:** `video copy kept` in the log, one stream, no stall.
2. **Capped at 1.5 Mb/s from the start:** `video copy → our ladder` in the log, a floor rung in the master, a first
   picture, and after the first minute no stall of 2 s or more over 10 minutes. The receiver's rung falls under the cap.
3. **Uncapped start, then a cap of 0.8 Mb/s mid-play:** the picture steps down to a floor rung; any stall is followed
   by a lower rung, and no more than one stall in the 5 minutes after the cap.
4. **A single-variant start (a proven path), then a cap of 1 Mb/s:** within two stalls or 4 s of stall, one
   `stall_to_ladder` recovery and our ladder after it; the receiver's next QoE row has `per_item` set and lists the
   stalls; the device has a `device_stream_record` row with `stalled_bps` set.
5. **Field read, 14 days after the release:** cast plays from outside the house show fewer stalls per play than this
   spec's baseline, and the share of Jellyfin-served plays that stall falls from 8.4 %. A count going the wrong way is a
   finding against FR-1 or FR-2.
6. **FR-6, a start that cannot succeed:** a `tc` drop of all traffic to the receiver from the `LOAD` until the receiver gives
   up. The receiver's QoE row for that item has `start_failure_status = -1`, and the log has one `playback: start failed …`
   line for it.

## What this does not do

- It does not change the start rung for a cast with no record (309's 4 Mb/s start). The floor rungs are there for Shaka
  to fall to; a slow first segment is still a slow start.
- It does not borrow the enrolling phone's measurement for the receiver (308 keeps that rule).
- The copied-top-rung ladder (the original file as the top rung) stays DEB-3093's next step.

## Tests

- `EncoderPlanTest` (or `PlaybackServiceTest` for the routing): a video copy to an adaptive device with no record goes
  to our encoder; with a take of 2 × the copy and no hold it stays on Jellyfin; with a live hold it goes to ours; a
  non-adaptive device keeps Jellyfin's copy.
- `VideoLadderTest` / `EncoderPlanTest`: with no record, a 576p 2.4 Mb/s H.264 source gets 576p / 480p / 360p / 240p at
  the H.264 figures; a 2160p HEVC source with a take of 3 Mb/s keeps its top, 240p and 360p inside four; the same source
  with a take of 50 Mb/s and no hold gets today's four rungs; on the same take, a source whose ladder today has three
  rungs gains 360p as its fourth.
- `CastRecoveryTest` (pure decisions): the stall-to-ladder trigger (two stalls in 2 minutes, one of 4 s, not again after
  a same-stream answer), and the report's `per_item` counts resetting at a `LOAD`.
- FR-6: `CastRecoveryTest` covers the receiver's failure status (an error that carries a status gives that status, and one
  that carries none gives `-1`, only when no first frame was seen). A `PlaybackService` test checks that a report with
  `startFailureStatus` logs the line.

## Amendment 2026-11-25 (DEB-5640) — a floor-first ladder keeps a rung to climb to

> Owner on DEB-5531, 2026-11-25: *"lets also try to add support for pre buffering and ladder up again etc. So it feels
> faster and better and with highest quality possible."* On DEB-5620 the same day: make the Samsung's QoE better.

**What prod shows.** The household's Samsung was measured at 7.3 Mb/s and has never been proven. Its
take is therefore `measured × 0.7` ≈ 5.1 Mb/s, under `FLOOR_FIRST_TAKE_BPS`, so every one of its 48 plays over three days
was floor-first. On a 1080p source whose own bitrate is about 7 Mb/s, the backend log of 2026-11-25 reads
`takes 5108k → cap 5108k` and `rungs=1080p@7034k/480p@1500k/360p@800k/240p@400k start=1`. The rule above keeps the top,
then 240p, then 360p, and drops the middle rungs from just under the top first. That leaves the 720p rung out:

- The start is 480p (peak `BANDWIDTH` 2.44 Mb/s), the best listed rung inside the take.
- The only rung above it is the top, at a peak of about 10.7 Mb/s, which a 7.3 Mb/s line cannot carry. AVPlay has nothing
  it can climb to, so the whole evening plays at 480p.
- DEB-5621's FR-25 amendment makes a clean play prove its path. But 309's proof is the variant that was held, so 480p
  proves 2.44 Mb/s, the take does not move, and the next play is the same ladder again.

The floor rungs are right for a thin path. Dropping the one rung the take could climb to is not: a ladder with no
reachable rung above the start can never climb, and it can never prove the next one.

**FR-2, amended: with a take, the floor-first limit keeps the start rung and the rung above it.** The bullet *"A path that
is not known to be fast puts the floor first"* now has two cases.

- **No take** (no record, as on a first cast): unchanged. The limit keeps the top, then 240p, then 360p, and drops rungs
  from just under the top first.
- **A take** (a measurement, a proof or a stall hold gives one, and it is under 6 Mb/s or a stall hold is live): the four
  slots go, in this order, to:
  1. **the start rung**, which is the best rung whose peak `BANDWIDTH` (`rungBandwidth`) fits the take, as `startRungFor`
     picks it from the full list;
  2. **the climb rung**, the next rung above the start in the full list. That is the top only when no middle rung lies
     between them;
  3. 240p;
  4. 360p;
  5. the middle rungs below the start, highest first;
  6. the rungs above the climb rung, lowest first.

  A rung already holding a slot is not counted twice, and a rung the full list does not have (a floor rung the source
  sits below, as FR-2 says) is skipped. The listed rungs are drawn top first, as today, and the start rung is chosen
  from them by `startRungFor`, as today.
- **The top is dropped** when it is in none of the first four slots. This is the only case where a ladder's highest rung
  is not the source's own size. Anything that reads `rungs[0]` as the size the job encodes, such as the card placement's
  load (`jobLoad`), reads the highest listed rung, and that is what the job encodes.
- **A path with a take of 6 Mb/s or more and no hold** is unchanged: today's rungs, with a floor rung only in a free slot.
- `held_by` is unchanged (`floor_rungs` when a rung under 480p is listed). The playback log line already lists the rungs,
  which is how the change is read on prod.

**What it gives the Samsung above.** The same source at a take of 5.1 Mb/s gets `720p@4000k / 480p@1500k / 360p / 240p`,
and starts at 480p. AVPlay's own ABR may then climb to 720p (peak 6.19 Mb/s) on a 7.3 Mb/s line. Once 720p is held for
120 s with no stall, FR-25's posts prove it, the take becomes 6.19 Mb/s, the path is no longer floor-first, and the next
play starts at 720p on today's ladder. A thin path keeps both floor rungs, and so does any take under 2.44 Mb/s: there
the start is a floor rung or 480p, and the climb rung is the one above it. HEVC works the same way with its own figures.

**Not changed:** the `6 Mb/s` threshold, the start rule, 309's headroom (`× 0.7`), the stall hold, Jellyfin's ladder and
the route choice (FR-1). No wire change, no schema, no client change: every device that reaches our encoder on a path
that is not proven gets the climb rung, the Chromecast and Android TV included.

**Tests** (`EncoderPlanTest`):
- a 1080p H.264 source at about 7 Mb/s with a take of 5.1 Mb/s gets `[720, 480, 360, 240]`, and `startRungFor` picks 480p;
- the same source with a take of 7 Mb/s gets today's four rungs;
- the same source with no record gets today's floor-first ladder `[1080, 480, 360, 240]`;
- a take of 1 Mb/s gets a ladder whose start is 240p and whose next rung up is 360p, both floor rungs kept;
- a live stall hold with a take of 3 Mb/s keeps 720p as the climb rung;
- a 2160p HEVC source with a take of 3 Mb/s, the case in *Tests* above, is now `start + climb + 240p + 360p`, and the
  test's expected list is updated in the same commit.

**Proof (QA, parked on DEB-4608, does not block the build).** On the `tizen-emulator` row with the QA cap at 7 Mb/s and an
uncapped control: the log's ladder lists 720p, the play starts at 480p, and `variant_switches_up ≥ 1` in the item's QoE
row. *Field read*, 14 days after the deploy: the household set's start rung and its `proven_bps`.
