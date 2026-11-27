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

> *Amendment 2026-11-27 (DEB-5928).* QA's lab pass on DEB-5041 (leg A3) capped Jellyfin's single stream below its
> bitrate. The receiver never asked for a ladder, because it counted the wait as a frozen picture. It also checks FR-4 only
> when a wait ends, so a wait that outlasts the cap would never be counted. Two changes:
> - **The stall FR-3 and FR-4 count** includes the receiver's delivery wait as DEB-2205 FR-5's amendment of the same date
>   defines it: a stop while the video element's `readyState` is 2 or less, with cause `buffer_empty`, whatever `buffered`
>   reads ahead and whether or not CAF reports `BUFFERING`.
> - **"One stall of 4 s or more" fires at 4 s**, not when the wait ends. When a delivery wait on a rung with no lower choice
>   reaches 4 s, the receiver closes it as a `QoeStall` of the length it has reached and posts the report. The QoE route then
>   writes `stalled_bps` (FR-3) before the ask, as it does today. After that the receiver asks `stall_to_ladder`. The rest of
>   the wait belongs to the recovery's own `recovery` entry, so one wait is never two stalls. The two-stalls-in-2-minutes
>   trigger still counts stalls as they end.
>
> Unchanged: once per item, the rung test, and the same-stream answer that stops a second ask. The tests are in
> `CastRecoveryTest`. A wait still open at 4 s on a single-variant master asks. The same wait at 3.9 s does not. A wait on
> a rung with a lower rung listed does not ask. **Proof:** acceptance 4 below,
> run on a real Chromecast as DEB-5928 asks, with a Jellyfin video-copy item and its uncapped control.

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

## Amendment 2026-11-27 (DEB-5927) — the receiver steps down on a buffer that is empty, not only one that is falling

**What the lab saw.** QA's pass on DEB-5041 (leg B1): a Chromecast receiver, no record, a 1.5 Mb/s cap from before the `LOAD`,
our ladder `1080p@1998k/480p@1500k/360p@600k/240p@300k` with the start on the top. Over 7 minutes the receiver stayed on
`variant_height=1080 @ 3189074`: 20 `buffer_empty` stalls of 2.6–7 s (buffer ahead 0.3–0.5 s), 141 s of rebuffering, zero
down-switches, Shaka's estimate 0.39–1.07 Mb/s, and no `309 abr: step down` line in the sender's log. A cap that arrived mid-play
(B2, on 360p) did step down, so Shaka's own ABR can.

**Why.** The receiver's ladder rule (`ReceiverAbr`, over `LadderRules`) steps down only when the buffer is under 20 s **and
falling** (`mustStepDown`: lower than at the last look by more than 500 ms). A buffer pinned at 0.3–0.5 s, refilled by every
segment and emptied again, never reads as falling, so the rule that exists to catch exactly this never fires. FR-309-5's second
clause, *"or its estimate × 0.8 drops under the current rung"*, is not enforced by the receiver at all, and FR-5 of
`trouble-never-reaches-the-viewer.md` (*a stall caps one rung down at once*), which Android, the web and the Mac follow, is not
enforced there either. The receiver left the choice to Shaka alone, and on the top-rung start Shaka did not move.

### FR-7 — The receiver's ladder rule has the estimate clause and the stall cap

On an adaptive play (any laddered master, ours or Jellyfin's):

- **Estimate clause (restores FR-309-5).** When at least 3 segments have been fetched for this item (the same gate as the QoE
  report's estimate, FR-309-13), the buffer is under 20 s, and Shaka's bandwidth estimate × 0.8 is under the bandwidth of the rung
  playing, the receiver switches to the highest rung whose bandwidth is at most estimate × 0.8, or to the lowest rung when none
  is. It checks every 2 s, with the rule above, whatever Shaka chose.
- **Stall cap (applies FR-5 to the receiver).** A `buffer_empty` stall of 1 s or more, or a video that has had nothing buffered
  and not been seeking or paused for two checks in a row (4 s), caps the play **one rung down at once**, to the lowest when it is
  there. A cap made under 10 s ago is not made again (the stall belongs to the rung being left). The cap holds the receiver below
  the rung it left for **120 s** with no further stall (FR-309-4's clean-for-120 s), then lifts, and the climb is the stock one.
- Neither rule changes the start rung (309's start is the server's, as *What this does not do* says), and neither runs when the
  master lists no lower rung (FR-4 asks for the ladder then).
- Each step down writes the existing `309 abr:` debug line with its reason (`estimate`, `stall`, `starved`).

**Tests.** `LadderRulesTest` covers the pure parts (`estimateStepDown`: below the 0.8 line it picks the rung under the line, the
lowest when none fits, nothing with fewer than 3 segments, with a buffer of 20 s or more, or when the rung is already under the
line; the stall cap's target and its 120 s hold). The wrapper itself is JavaScript over the CAF framework's Shaka and cannot run
under Gradle; it is covered by the QA ticket for this task, which repeats B1 on a Chromecast with the server-side cap and an
uncapped control: no `buffer_empty` stall of 2 s or more after the first minute, and a `309 abr: step down` line within a minute of
the start.

## Amendment 2026-11-27 (DEB-5929) — a failed start is reported once the backend answers again

> Tróndur on DEB-5929, 2026-11-27: *"fix the receiver. Don't weaken the acceptance."* Step 6 stays a drop.

**What the lab showed** (DEB-5041, leg A4). All traffic to the receiver was dropped from the `LOAD` until it gave up
(58 s, Shaka 1003 on segment 0, CAF `LOAD_FAILED` 905), and then restored. No QoE row with `start_failure_status`
arrived, and no `playback: start failed` line was logged. The session stayed *Lab tv is offline · paused at 0:00* under
*Playing everywhere* until Stop was pressed. With a 0.1 Mb/s cap instead, FR-6 worked as written. Two things lose the
report:

- `stopSession()` sends the item's final QoE report and its stop exactly once, at the moment the receiver gives up. On a
  total drop the receiver is still offline at that moment. `postPlaybackQoe` swallows its own failure, by R216's
  invariant, so the receiver cannot tell that the report was lost.
- The lost stop is why the row stayed. The 90 s stop watchdog reaped it. One progress report before any picture is
  enough for `everReported`, so FR-R372-13 kept the row *paused · offline* on FR-R372-8's 10 min clock instead of ending
  it `failed`. The row was doing what R372 promises. It lingers because the receiver's own stop, the stop that *found
  live 2026-10-09* (1) relies on, never arrives.

A total drop is what an away-from-home link going dark looks like, so FR-6 has to hold for it.

**FR-6, amended: the receiver keeps a failed start's report and stop until they are delivered.** When the receiver
ends an item that never reached a first frame (FR-6's first bullet), it does this:

1. **It builds the item's final QoE report once**, at the failure, with `startFailureStatus` set as FR-6 says. It sends
   that report and then the stop at the held position, the same route and body as today.
2. **A send that gets no HTTP answer, or a 5xx, is held and sent again.** The retries come 5 s later, then at doubling
   gaps up to 30 s, as the events socket reconnects (`eventLoop`). The report goes first. The stop goes only once the
   report has been answered, so the log line comes before the row ends.
3. **Any 2xx or 4xx answer ends that post's retries.** A 4xx means the post was refused, and sending it again would not
   change that.
4. **The hold ends after 10 min**, or when the receiver app unloads, whichever is first. After 10 min, FR-R372-8 has
   ended the row `offline` and the stop has nothing left to end. Nothing is written to storage: a report from a
   receiver that was closed while the link was dark is lost, as it is today.
5. **A new `LOAD` drops a held stop, but not a held report.** A stop that lands after a new start on this place can end
   the new session (FR-289-2), and the new start already ends the old row (R368's `replaced`). The report is about the
   old item only, so it keeps its schedule.
6. **One `playback: start failed` line per item still holds,** because the receiver sends again only when it got no
   answer. The one case left is a report that reached the backend while its answer was lost. That report can log twice,
   and this is accepted: the wire has no id to deduplicate on, and adding one would be a wire change.

**What it gives.** When the link comes back while the receiver app is loaded, the QoE row and the log line arrive within
30 s. The stop follows, and the row ends `stopped` 15 s later through the stop hold (`onStop`). It no longer shows as a
phantom play until the 10 min window runs out. While the link stays dark, nothing on the receiver can reach the backend,
and FR-R372-8's 10 min clock still bounds the row.

**Not changed:** the reap rule (FR-R372-13) and the session windows (FR-R372-8). A play that reached a first frame
keeps its one-shot report and its stop. R372's owner choice keeps that play's place on the offline clock, and a late
stop would take *Play here* away from it. No wire change: the receiver's shared client gains a QoE post that says
whether it was answered, alongside the existing one. `postPlaybackQoe` keeps its never-throws contract for every other
caller. Nothing changes on the backend.

**Acceptance 6, amended.** Drop all traffic to the receiver from the `LOAD` until it gives up, then restore traffic
and wait 60 s. The item's QoE row has `start_failure_status = -1`, and the log has exactly one
`playback: start failed …` line for it. The session has ended (`end_reason = stopped`). It no longer reads
*offline · paused at 0:00* under *Playing everywhere*. Keep the 0.1 Mb/s cap leg as the control: one row, one line, and
no change from today.

**Tests:**
- `CastRecoveryTest` gets a pure schedule for a held failed-start post. It sends again after no answer or a 5xx, and
  stops after a 2xx or a 4xx. The gaps run 5 s, 10 s, 20 s, 30 s, 30 s. Nothing is sent after 10 min. A new `LOAD`
  drops the stop and keeps the report. The stop is not sent before the report has been answered. All of this runs on an
  injected clock, with no sleeping.
- `PlaybackSessionsTest` (or `SessionEndingTest`): a stop for a row the watchdog has already reaped to *paused ·
  offline* ends it `stopped` once the stop hold runs out.
