# Trouble on the server or the connection never reaches the viewer

Task: DEB-3093

> Owner, 2026-10-30: *"I want to feel no issues and I want best quality possible, whenever server or the connection from
> server to client goes bad I want the server and client to handle it the best way possible, just like how youtube or
> netflix or any other client. The users do accept quality going a little bit down and then up again if something is
> unstable (network wise), but buffering and lagging is bad experience."*

## Scope

Touches the backend (`tv/`), the Android player (`ravilo-ui`, TV and phone) and later the other clients. It extends
[`a-play-stops-at-most-once-at-its-start.md`](../../requirements/a-play-stops-at-most-once-at-its-start.md) (DEB-2205),
which made a stop rare and measured. This spec covers what that one left out: a failure the viewer never needed to see.
It amends R372, 309, R216, R218 and DEB-2205, each with a dated note in its own file. The sound half is 313's amendment
of 2026-10-30 (FR-313-5a to 5c). Its order of work is the board's.

**The trade-off is settled, and it is the owner's:** lower the quality before ever stopping the picture.

## Why

What prod showed on 2026-10-30, over 7 days (318 plays on 11 viewer devices, TVs almost all of them):

- **79 video plays were reaped by mistake:** read as gone, then back within 30 s. 23 were on our encoder, where the reap
  ends the stream. 19 of the 20 inside the available Caddy log fell within 5 s of a reload. The viewer meets it about 50 s
  later, when the buffer runs out. Almost none of this shows in the QoE rows.
- **47 plays started on the file and moved to a ladder,** each by a full player reset. Recorded recoveries stop the
  picture for a median 3.2 s.
- **8 of 87 plays over 5 min ended well below the source on a link that could carry more,** most with no climb at all.
- A single earlier stall can hold a device low for 24 h: a TV measured at 173 Mb/s started a 4K film on its lowest rung,
  and took about 20 s to climb.

What the code does (audited 2026-10-30):

- A backend restart ends every stream on our encoder, and a stream ends 4 h after it was minted, even mid-film.
- The periodic watchdog tick ends a play with fresh heartbeats if its socket happens to be down.
- A slow or failing segment does not change rung: timeouts and resets retry the same rung, and a slow segment is never
  abandoned.
- The client's 8 s read timeout is shorter than the server's 15 s wait for a segment.
- Every stream replacement draws a black shutter and a spinner over a reset player. A failed restream call gets one
  attempt, then the error screen, even with the old stream still playing.
- The stall treatment shows at 400 ms, and resuming needs 10 s buffered.

What the big players publish: Netflix's buffer-based work picks the rate from how full the buffer is, using capacity
estimates only at startup, and cut rebuffers 10–20 % in field tests with millions of users (Huang et al., SIGCOMM 2014).
None of them publishes its current production client, so this spec takes the principle and not their numbers.

## Requirements

### FR-1 — Losing the control channel never touches the stream

- The events socket, the watchdog and a session's `offline` state are bookkeeping. **None of them ends, stops or
  invalidates a stream.** A watchdog reap may still tell Jellyfin the play stopped and mark the session; the stream's
  entry stays.
- A running job with no segment requests is freed by its own 60 s idle sweep, and the next request restarts it. That is
  the only way a job ends while the viewer has not asked to stop.
- A device whose progress report arrived in the last 30 s is never reaped on the socket test, on any tick. A socket that
  ended abruptly still counts as gone only after 15 s (R372's amendment).
- A viewer's own stop ends the stream at once, as today.
- A new stream replacing an old one (`superseded`) releases the old one only when the new one's first segment is read,
  or after 60 s. A restream that fails therefore still has the old stream to play.

### FR-2 — A stream outlives a restart and the clock

- **A deploy does not end a play.** A stream's plan is stored by its stream id (a new table; a plan is small), and a
  request for an id not in memory re-registers it from the store and restarts the job at that segment. The same holds
  for a composed master and its renditions (`AudioRenditions`) and for a sidecar. A stored plan goes when the session
  ends or after 24 h unread.
- **A plan stored by one build is read by the next, in both directions.** A new field in a stored plan has a default that
  means today's behaviour, and a plan an older build stored keeps playing as it was planned. A deploy that cannot read a
  plan ends that play, which is the failure this FR exists to remove.
- **A film does not outlive its ticket.** Every playlist, segment or init read renews `expiresAt` by 4 h. A stream ends by
  expiry only after 4 h with no reads, paused or not.
- **A refusal means ffmpeg refused the plan.** Only a stream's first start can mark it refused (410, and the file to
  Jellyfin for 6 h). A later job that exits early is started once more, then again on the next request, and answers 503.
- **A restarted job takes a card through the normal placement.** A stream that idled out during a pause counts toward
  its card only once it has restarted, and if no slot is free it restarts on fewer rungs rather than failing.
- **A full work folder prunes before it fails:** behind the player first, then retired jobs.

### FR-3 — The two sides agree how long to wait

- The player's read timeout is 20 s and its connect timeout 8 s. The server's 15 s wait for a segment stays.
- A segment not ready after that wait answers **503 with `Retry-After: 1`**, not 404: the job is still running, and a 404
  excludes the rung for a minute. A 404 remains for a stream that does not exist even in the store.

### FR-4 — A failing or slow segment means a lower rung, now

Android, for HLS. A custom `LoadErrorHandlingPolicy` and the existing `ClimbingTrackSelection`:

- **Any** failed segment load (a timeout, a reset, any 4xx or 5xx) falls to the next rung down at once while one exists,
  excluding the failed rung for 15 s, and a playlist failure does the same. Retry delays are 0.5, 1, 2 and 4 s, then 5 s,
  and a load is retried for as long as the buffer holds picture.
- **A slow segment is abandoned** (`shouldCancelChunkLoad`) when its projected finish is later than the buffered time minus
  5 s and a lower rung exists. The climb then waits 30 s.
- **The rate is chosen from the buffer once one exists:** today's estimate rule and the step-down under 20 s stay, and a
  step-down also fires when the buffer has fallen for 6 s and is under 30 s.
- A failure becomes an error only when the buffer is empty, every retry has run, and a restream (FR-6) has failed too.
- *2026-10-31, DEB-3102 — how the abandon rule reads in code.* A load is judged only once it is 1 s old (a rate needs something
  measured), and never while nothing is buffered (the first picture, a seek, and a stall that FR-5's cap already answers are not
  held to the buffer's budget). Its rate is the load's own bytes over its age, the player's estimate until bytes arrive. After an
  abandon the rung below is the ceiling for 30 s. "Every retry has run" with an empty buffer is the five scheduled retries.
- *2026-10-31, DEB-3172 — a start does not drop a rung because of how long our own encoder takes.* The note above stays, with two
  changes.
  - **The carve-out does not stop at the first segment.** After a start, or a seek that lands outside the buffer, no load is judged
    until the buffer ahead of the loading segment has once reached the 5 s margin plus that segment's own length: 7 s on our
    encoder's 2 s segments. Below that a segment made and fetched at real time cannot pass, so the rule would be judging the wait
    and not the link. With 2 s segments the second and third loads have a budget under zero and the fourth has 1 s, and any of them
    still loading at 1 s old would be abandoned however fast the link is. A replacement (FR-6) is a start. A stall does not reset
    this: a thin buffer after that point is what the rule is for, and the trade-off above is to lower the quality before stopping
    the picture. Only a load with nothing in hand stays unjudged, as the note above says.
  - **A load's rate is the link's.** It is the load's bytes over the time since its own first byte, and the player's estimate until
    it has 0.5 s of them. This replaces "the load's own bytes over its age" above. The age still counts toward the projected finish,
    because the buffer drains during a wait, but the wait is not the link's speed. Measured from the age, a 30.64 Mb/s segment that
    waited 1.55 s for the encoder and has read its first 188 kB comes out at 1 Mb/s and a finish a minute away, which abandons it
    even with 40 s in hand.
  - **What answers a slow link until then:** the start rung, the player's estimate × 0.7 on every new segment, the failed-load rule
    above and FR-5's cap. A link that collapses in those seconds can cost a wait or a stall where the rule would have stepped down.
    No released build has the abandon rule at all, so this is no worse than today, and it is the accepted price.
  - **Why this is not a corner case,** read from prod on 2026-10-31 (the last 7.7 days, 138 plays on our encoder of 10 segments or
    more): all 34 plays with a 2160p top rung had a segment request wait over 1 s for its file, and 13 of them had two or more, as
    did 9 of the other 104. ffmpeg's slowest reported speed in a play has a median of about 1.2×, where a 2 s segment takes about
    1.7 s to exist.

### FR-5 — A short wait does not look like a problem

- A stall under 2 s draws nothing: no dim, no chrome, no spinner. R218's treatment starts at 2 s (today 400 ms); its other
  moments are unchanged.
- A stall caps one rung down **at once** (today after 3 s).
- A play resumes at 4 s buffered (today 10 s). The rung cap is what keeps one stall from becoming a train of them, which
  is the concern 10 s answered in R216.

### FR-6 — A stream is replaced without being seen

For every replacement the viewer did not ask for: an encoder failure, a hand-over to the ladder, a freeze recovery and a
stall's move to the ladder.

- **The old stream keeps playing until the new one is in hand.** The restream call runs behind the picture: no `Loading`
  state, no shutter, no start screen. A failed call is retried at 1, 2, 4 and 8 s for as long as the buffer holds.
- The player switches only once the new stream's first segments are fetched. The switch holds the last frame and shows no
  black frame, no spinner and no chrome: **at most 0.5 s of held picture, measured on the stue TV's class of device.**
- A failed encoder stream is first asked for again (FR-2 restarts it). Jellyfin's stream is the second try, not the first.
- *2026-10-31:* a warm-up that cannot fetch its first segment in time asks the next rung down, then the lowest, before it counts as
  a failed try. At 1 Mbps it kept asking the rung the server chose and never finished, so the switch never came (DEB-3149).
- *2026-11-04, DEB-3545 — "its first two segments" are the two the player reads first,* the segment that holds the replacement's
  position and the one after it, and the rung's init segment (`#EXT-X-MAP`) where it has one. They are not the first two entries of the
  playlist. Our encoder's playlists, and Jellyfin's, list the whole film from segment 0. A warm-up that takes the first two entries
  fetches the film's opening 4 s, and on our encoder that request starts the stream's job at segment 0
  (`EncoderJobs.segment`: a segment outside the running job restarts it). The player's first read at the position then restarts the
  job there after the switch, so the held picture pays a cold encoder start. Past the film's first 4 s, Android's cache (DEB-3171)
  never holds what the player asks for either. The position is found by adding up the playlist's `#EXTINF` durations, so Jellyfin's segment lengths need no
  rule of their own. This holds for every client that warms a replacement: Android, the web and the Cast receiver.
- A viewer's own audio, subtitle or version pick keeps today's treatment: that wait is their action (DEB-2205, FR-1).
- If the old stream is frozen (FR-5.2 of DEB-2205) there is nothing to keep playing: the switch shows the held frame until
  the new stream's first frame, and still draws no spinner under 2 s.
- *2026-10-31, DEB-3103 — how Android builds it.* A replacement is one restream the store makes behind the picture: the
  state stays `Ready` (never `Loading`), and the answer arrives as `Ready` with `replacement = true`, which the screen reads
  as "hold the last frame": no shutter, no start screen, no cold-start moment, for at most 2 s, after which R218's own
  treatment applies. *2026-10-31, DEB-3171:* those 2 s run from the moment the replacement is handed to the player, and no buffering
  moment (cold start, seek or stall) is drawn inside them whatever the player's first-frame flag says, so there is no dim and no chrome
  in the first 2 s; the first frame no longer ends the window early. The player is not touched until the new stream's first segments have been fetched: the store asks the
  server for the new master, the rung it starts on and its first two segments, so the encoder (or Jellyfin) has them
  before the engine is reset. *2026-10-31, DEB-3171:* the two segments are also kept, streamed into a small cache of their own
  (Android, 64 MB LRU, five minutes), and the player reads that stream's segments from the cache by URL; a miss is a network read, so a
  mismatch costs only the gain. Before this the player fetched the same bytes again after the reset, on the same slow link, which a
  hand-over only ever happens on (a 3 Mbps first segment over a 5 Mbps cap is about a second). One engine only; two decoders are not started (R292). A failed call, or a failed warm-up,
  is retried at 1, 2, 4 and 8 s (then every 8 s) for as long as the buffer holds picture; it becomes the error screen only when
  the buffer is empty and the four have run. Our encoder's failed or stuck stream is asked for once more from our encoder
  (FR-2 restarts it), then from Jellyfin, then ends on R237's error. Every road in the first bullet uses this one path: the
  encoder failure, the hand-over to the ladder (both forms), the freeze recovery, the stall's move to the ladder, and the
  rung step of 309 (FR-309-9). A viewer's own pick still goes through `Loading`.

### FR-7 — The best picture the path can carry, soon

> *2026-10-31:* a fast link must also stay on the file: an estimate alone no longer moves a play off it. See the amendment under
> FR-4 of `a-play-stops-at-most-once-at-its-start.md`.

- **A stall's hold does not outlast the evidence.** The cap is 1 h (today 24 h), and a measurement newer than the stall
  whose × 0.7 clears a rung lifts the cap to that rung. A stall during a reap, a restart or a server wait (FR-1 to FR-3) is
  not the path's fault and writes no `stalled_bps`.
- **On our encoder the climb goes straight to the best rung the estimate × 0.7 clears**, with 10 s buffered. Every rung
  already exists and a step down is instant, so nothing is gained by one rung at a time. Jellyfin's ladder keeps FR-309-4,
  where a rung is a cold start.
- **A play that sits below the source on a fast link says why.** The 8 plays above are found out while building this: a
  trimmed ladder, a player that cannot climb or a cap each write a `held_by` reason into the encoder summary, and any that
  is a bug is fixed in this FR.
- *2026-10-31, DEB-3104 — what was built and what the 8 plays showed.* The hold is `STALL_HOLD_SEC` = 1 h. A measurement (the probe's or
  the QoE rows') newer than the stall lifts the cap to what its own × 0.7 allows, so the start rung is the best one that clears.
  A stall that follows within 120 s of this server's start, of a restarted stream of that device, or of a 503 it answered writes
  no `stalled_bps` (the hold still restarts). On the client, `LadderRules.allowedMaxBps` on our encoder bounds the climb at the top
  rung once 10 s are buffered, and Media3's (hls.js's, AVPlayer's) own × 0.7 rule chooses within it; Jellyfin's ladder keeps one rung
  and 30 s. `held_by` is a comma-separated string on the encoder summary: `stall_cap` (the cap holds the
  start under what the device has measured), `no_record` (720p start), `cannot_climb` (a player that does not adapt gets one rung),
  `trimmed_ladder` (no free slot, fewer rungs than planned). Read from prod's last 7 days (114 plays on our encoder): the plays that
  ended on 480p or 720p with an estimate over 100 Mb/s started on the 480p rung (`start_variant_bps` 2.4 Mb/s) and climbed one rung per
  10 s of buffer or not at all — the cap and the one-rung climb, both removed here. `trimRungs` was not found to be the cause of
  one; its order (the start, the rung below, the one above) is kept so a trimmed ladder can still step down, and a trimmed play is
  named by `trimmed_ladder`. Whether a fast link reaches the source's rung in the first 20 s is read from the `held_by` and
  `start_variant_bps` of plays after the release, not from this commit.

- *2026-10-31, what "during" means:* a stall reaches the server only with the play's next QoE report, which can come minutes
  after it. So a stall is the server's when the play's **previous report came before the fault**, however late this report
  arrives, as well as within the 120 s. On the 10-31 swap a stall inside the restart was reported 140 s later and capped its
  device for an hour. `playback_qoe.updated_at` is that previous report and survives a restart, so this needs no new field. A
  real stall in the same gap is excused too, which is the cheap side: the next one writes it.

### FR-8 — Every stop and replacement leaves evidence

Restores DEB-2205's FR-8 on the roads that skip it.

- Every replacement on any road writes one `stalls_json` entry with cause `recovery`, its duration and the rung it left.
  `recovery` is already a cause, so that entry needs no new field. Its reason is a new optional field on the entry, which
  is additive on the wire.
- `encoder_summary` gains the p95 and maximum segment response time and the number of 503s, as this server answers them
  (request to last byte). The player's own view of a slow segment is its `stalls_json` entry above.
- A reap that spared a stream (FR-1) logs one line, so the next report can be answered from data.
- *2026-10-31, DEB-3103 — what the replacement's entry says.* The entry is cause `recovery`, its `duration_ms` is the time from
  the switch to the new stream's first movement, and `rung_height`/`rung_bps` are the rung it left. `reason` is the new optional
  string on `QoeStall` (additive): `encoder_failed`, `encoder_stuck`, `frozen`, `decode_failed`, `handover_now`, `handover_ahead`,
  `stall_to_ladder`, `step_cap`. The clock starts at the switch, not at the decision, because the old stream plays on until
  then. FR-1's rule stays: a stop under 1 s is not a stop, so a replacement shorter than that writes its log line and no entry;
  one that stopped the picture for a second or more is the entry. Every replacement writes the log line.
- *2026-11-01, DEB-3332 — every rung change says why, in the log.* Android, and the log only: no field, no `stalls_json` entry, nothing
  on the wire. Until now only an abandon wrote a line, so a fall could be told from a dim link only by reading a decoder's crop, and
  not at all from a report. Two lines, because a rung is picked a buffer's length before it is seen:
  - **When the player picks another rung,** one line from `ClimbingTrackSelection`: the rung it left, the rung it picked, the reason,
    the seconds buffered and the estimate. The reason is `failed_rung` (the rung it left is excluded, FR-4), `abandon` (the 30 s
    ceiling after an abandon), `buffer_low` (under 20 s and falling, FR-309-5), `buffer_falling` (fallen for 6 s and under 30 s, FR-4),
    `estimate` for any other fall and `climb` for any rise. Checked in that order, first match wins. A pick that changes nothing writes
    nothing.
  - **When a rung reaches the picture,** one line from the player's format-change callback: the rung and the one before it. A play's
    first rung writes this line too, which nothing wrote before.
  - The tag and the prefix are the abandon line's (`adb logcat -s DEB-3102`, `ladder:`), and every build writes them, because QA tests
    the release build, where `DebugLoadLogger` is not bound. The abandon line stays as it is, so one abandon writes it and then the
    `abandon` pick. FR-5's in-place cap replaces the selection, so it writes no pick line: its own `recovery:` line and the picture line
    say it.

### FR-9 — The other clients

After Android, because TVs are nearly all the plays. Each follows FR-4 to FR-6 as far as its player allows:

- **Web (hls.js):** retries and fragment abandonment configured to FR-4, FR-5's stall rule, FR-6.
- **Mac (AVPlayer):** FR-5's stall rule and FR-6.
- **Linux (mpv):** timeouts and reconnect set, and FR-6 on each step.
- **Cast receiver:** the restream recovery it has none of today, then FR-4 through Shaka's retry parameters.
- *2026-10-31, DEB-3105 — what the Cast receiver does, and what CAF does not allow.* CAF resets its player on every `LOAD`, so the
  receiver cannot keep the old stream playing behind a replacement (FR-6's first bullet); a stream that failed has nothing left to
  keep, and that is the only replacement the receiver makes. What it does:
  - **Restream recovery.** A film whose stream fails after its first picture is asked for again at the place it stopped, its master
    and first two segments are fetched (`warmHlsStart`), and only then is it `LOAD`ed. Our encoder is asked first (FR-2 restarts the
    job), Jellyfin's stream second, then the error screen; a stream that came from Jellyfin, a load that never reached a picture,
    music and a station keep today's behaviour. A failed call or fetch is asked again at 1, 2, 4 and 8 s, and then ends on the error
    (nothing is buffered, so the four are all the buffer allows). A sender's own `LOAD` cancels it.
  - **FR-8.** A recovery that stopped the picture for a second or more writes a `recovery` entry (`encoder_failed`, the rung it left)
    in the QoE report's `stalls`.
  - **FR-5.** The buffering view appears after 2 s of waiting and not at once; Shaka starts and resumes at 4 s buffered
    (`streaming.rebufferingGoal`).
  - **FR-3 and FR-4, through Shaka's settings.** `retryParameters` on segments and manifests: five attempts at 0.5, 1, 2 and 4 s, a
    20 s request timeout. Shaka has no cap on the delay, so the 5 s of Android's schedule is not reproduced.
  - **Not done:** FR-4's fall to a lower rung on a failed or abandoned segment, and FR-5's cap one rung down at once on a stall.
    FR-9 names Shaka's retry parameters as the whole of FR-4 here; the receiver registers no segment-failure hook, and `ReceiverAbr`
    sees buffer levels only. None of this is verified on a Chromecast; the tests are the pure decisions in `CastRecoveryTest`.
- *2026-11-04, DEB-3213 — what the web client (hls.js 1.7.3) does, and what a browser does not allow.* Web only; a film on hls.js. A live
  channel keeps hls.js's own ABR (DEB-2627) and gets the load policies below but none of the holds. Native HLS (Safari, and an iPhone
  that takes it) is not hls.js and keeps what it had.
  - **FR-3 and FR-4, through hls.js's load policies** (`WebHlsPolicy`). `fragLoadPolicy`, `manifestLoadPolicy` and `playlistLoadPolicy` (1.7
    has no `levelLoadPolicy`; a level's playlist is the last): 20 s to the first byte, five retries at 0.5, 1, 2, 4 and 5 s
    (`exponential`, capped at 5 s, a timeout and an error alike). Beyond the fifth, a failed load goes on being retried for as long as the
    buffer holds picture, and ends as the error only with the buffer empty (`shouldRetry`, `LadderRules.retryLimit`). hls.js would not
    retry a 4xx at all; here any status is retried. A whole segment may take 120 s, a playlist 30 s.
  - **A failed load falls to the rung below at once** (FR-4). On a segment or level-playlist error of the main video the next load level is
    the one below, so the first retry already asks the lower rung, and that rung is held out for 15 s (`WebRungHolds`, which
    `boundLadder` applies as `autoLevelCapping` beside the 309 rule).
    *2026-11-05, DEB-3544:* the hold ending is not enough on hls.js. Its own ABR skips a level that has a load error until one of that
    level's segments is buffered, and `errorPenaltyExpireMs` defaults to 0, which is never; a rung skipped is never loaded, so QA saw the
    player stay on the rung below for 130+ s after the cap lifted at 15 s. The config sets `errorPenaltyExpireMs` to the same 15 s
    (`WebHlsPolicy.ERROR_PENALTY_EXPIRE_MS`), so the rung is a candidate again when the hold ends and hls.js's own rule (× 0.5 up) decides.
  - **A slow segment is abandoned** (FR-4, `WebSegmentAbandon`). hls.js's own abandon compares a load's projected finish with the whole
    buffer; FR-4's is against the buffer minus 5 s, with DEB-3172's carve-out (nothing is judged with nothing buffered, nor until the buffer
    has once held 5 s plus the segment, nor under a second old, nor on the lowest rung) and the rate of the load's own bytes. It is
    `LadderRules.shouldAbandonSegment`, judged every 200 ms on hls.js's own load statistics; an abandon moves the next level, fires the
    emergency-abort event hls.js's stream controller resets on, cancels the request, holds the rung below for 30 s and writes one console
    line in the form of Android's (`ladder: abandoned a 1000k segment after 1300 ms with 8 s in hand → 500k`). hls.js's own rule stays on.
  - **FR-5's cap.** A stall caps one rung down at once, for the 2 minutes Android's cap holds (`CLIMB_BLOCK_AFTER_RECOVERY_MS`). The 2 s draw
    rule is the shared `PlayerScreen`'s and is not touched.
  - **FR-6 is on** for the web: `unseenReplacements` is true here too (`holdsLastFrameAcrossSource()`). hls.js's `destroy` removes the
    element's `src` and calls `load()`, which drops the frame, so a load on an element that has played a frame draws it to a canvas (960 px
    wide at most) and sets it as the `<video>`'s `poster`; a browser shows the poster from the source change until the new source's first
    frame, where it is removed (and after 10 s at most, and by every load that is not a replacement). The restream runs behind the picture as
    on Android: no `Loading`, no shutter.
  - **Not done.** (1) **FR-5's resume at 4 s buffered.** hls.js has no rebuffering goal; the browser resumes an element that has run dry
    when it has a few frames, and holding it to 4 s means pausing the element, which the chrome, the media session and the play state sent
    to the server would all read as the viewer's pause. A stall resumes sooner than Android's, and the cap above is what keeps one stall from
    becoming a train. (2) **The warm-up's bytes are still dropped** (`WarmedSegments.wasmJs.kt`): hls.js reads the new stream's first segments
    from the network, so the warm-up pays only the wait, not the fetch; serving them from the cache would need a custom hls.js loader.
    (3) **The 0.5 s bound of held picture** is not measured on a browser; whether Safari and Firefox show the poster in that gap as Chromium
    does is QA's. (4) **No Playwright spec in CI:** the config lives in a `js()` string of the wasm bundle that runs only when a film plays
    on a signed-in session; the tests are the pure rules in `WebTroubleTest` (`WebHlsPolicy`, `WebRungHolds`, `WebSegmentAbandon`). A lab run
    in Chromium with the app's own JS lifted out of `RaviloPlayerWasm.kt`, a three-rung VP9 HLS ladder and a throttling server read: the
    config accepted by hls.js 1.7.3 (20 s to the first byte, five retries, `shouldRetry` a function); a top rung answering 503 or 404 from one
    segment on gave one failed request, then every later segment from the rung below with no stall; an abandon from the page's side moved the
    next request to the lower rung; and the poster showed the held frame while the new source had no data and was gone at its first frame.
  - *2026-11-04, DEB-3545 — what a browser costs, measured.* QA's run on prod (DEB-3535, headless Chromium 149 on the hls.js path, a
    1080p top rung at 18 Mb/s on our encoder, a frozen old stream) held the poster for 1373 ms and 1778 ms, with no black frame. A
    lab run in the same Chromium build timed only the browser's own part. It used a synthetic three-rung fMP4 ladder (18 Mb/s 1080p
    with grain) served from localhost, an audio rendition beside it, and the player's own config, poster, `destroy` and new instance.
    The new source's first frame came **307–404 ms** after the source change when the start position sat on a segment boundary, and
    **567–842 ms** when it sat 1.3–2 s into a segment. The difference is the decoder working forward from the segment's keyframe, in
    software in a headless browser. Answering the same requests from memory gained nothing on localhost.
    So the bound is within a browser's reach, and most of what QA measured is the warm-up defect in FR-6's 2026-11-04 note.
    The web keeps FR-6's 0.5 s. What remains after that fix is the network fetch of segments the warm-up already fetched (item 2
    above), which costs most on a slow link, and the decode from the keyframe. Each is decided on QA's measurement after the fix, not
    before it. Safari plays native HLS and not hls.js, so a custom loader would never reach it.
- *2026-11-07, DEB-3214 — what the Mac client (AVPlayer) does, and what AVFoundation does not allow.* Mac only; a film on HLS. A live channel
  keeps AVPlayer's own ABR (DEB-2627). Linux (mpv) is DEB-3215's. The Swift library is unchanged: the signals it already counts
  (`AVPlayerItemPlaybackStalled`, `AVPlayerItemFailedToPlayToEndTime`) are the whole of what this needs.
  - **FR-5's stall rule.** `automaticallyWaitsToMinimizeStalling` is true and `preferredForwardBufferDuration` 40 s, and the resume point
    is AVFoundation's: it is not a setting, and the row accepts it as the 4 s resume. The 2 s draw rule is the shared `PlayerScreen`'s. **The
    cap:** `MacLadderPeak.onStall` reads the library's stall count every watch tick (1 s) and lowers `preferredPeakBitRate` to the rung below
    the peak (the start rung when no peak is set yet) at once, and nothing climbs for 2 minutes (`CLIMB_BLOCK_AFTER_RECOVERY_MS`). A stall on the
    lowest rung caps nothing and blocks nothing. The Mac's climb now also honours `PlayerLadderHints.climbBlockedUntilMs`, which the store
    sets after its own recoveries and the Mac's peak ignored until now.
  - **FR-6 is on** for the Mac: `holdsLastFrameAcrossSource()` is true there (and only there), so `unseenReplacements` is true, the store keeps
    `Ready` (never `Loading`) and fetches the new stream's master and first segments before the player is touched. The held picture is the
    surface's own: Compose keeps the last frame it drew, and the torn-down item's video output offers none, so the old frame stays until the new
    item draws one. **The clock** would not: a new `AVPlayerItem` reports position 0 and no duration until its start seek has landed, so
    `MacHeldFrame` (the web's `WebHeldFrame` rule, DEB-3547) reports the replacement's start and the length the play had from a load made over
    a picture until the new item's first frame, its failure, or 10 s. A viewer's own pick over a picture opens the same window, and so does a
    next-episode load in the same player (the old length shows until the new item has read its own, which is before its first frame).
  - **Not done.** (1) **FR-3 and FR-4.** AVFoundation exposes no request timeout, no retry schedule, no segment-failure callback and no abandon
    hook for HLS: it retries and falls to another variant on its own. A failed item is terminal (`status = failed`, or
    `FailedToPlayToEndTime`) and goes the existing way, to the store's restream (FR-6), which is where a failed encoder stream is asked for
    again. (2) **The old stream does not play on until the new one is in hand.** `ravilo_player_load` pauses the one `AVPlayer` and replaces
    its item, so the sound stops when the warmed stream is handed over, not when the decision is made; keeping it needs a second `AVPlayer` to
    ready the new item, and R292 allows one engine. Whether the gap is worth that is QA's measurement, as the web's 0.5 s was. (3) **The
    warm-up's bytes are dropped** (`WarmedSegments.desktop.kt`): AVPlayer reads the new stream's first segments again; serving them from the
    cache needs an `AVAssetResourceLoader` delegate. (4) **The 0.5 s bound is not measured** on a Mac. (5) **Nothing here runs on AVPlayer in CI:**
    `ci / macos` compiles the Swift (unchanged) and the tests are the pure decisions, `MacTroubleTest` (`MacLadderPeak.onStall`, `MacHeldFrame`) and
    `LadderSteppingTest` (`peakAfterStall`, `nextPeakBps` with a blocked climb).

## What this does not do yet

**One ladder whose top rung is the original file, copied.** It is where this leads: no restream ever, one sound path and
segment-level retries, with a picture no worse than direct play. It means an always-available encoder for the lower rungs
and a segment grid over the file's own keyframes, so it is a new spec, not a slice of this one. It is decided on the
numbers after FR-1 to FR-8 land: if a move from the file to the ladder still shows as a stop under FR-6, it goes next.

Caddy's `stream_close_delay`: with FR-1 a reload no longer matters to a stream.

## Acceptance

Each by a QA seat on a device from the board, after its slice is on prod. Nothing here needs a design round.

1. A Caddy reload during a film on our encoder: no stop, the same encoder stream throughout, and no `Stop watchdog` that
   ends it.
2. A backend restart during a film on our encoder: no visible stop, and the same stream id serves afterward.
3. A film played past 4 h after its ticket, with a long pause in it: no stop.
4. A UniFi cap dropped under the playing rung, then lifted: the picture steps down without a stop, and back up within a
   minute of the lift.
5. A hand-over from the file to the ladder, recorded at 6 fps or better: no black frame, no spinner, at most 0.5 s held.
6. A failed segment on the playing rung (blocked for one rung): a lower rung, no stop.
7. Over a week on prod: no `recovery` entry over 1 s and no stop that a server event caused, read from FR-8's evidence.
8. After the release carrying FR-5, read from `stalls_json` on viewers' own TVs, because a cap cannot reach it (FR-4 steps down
   before the buffer runs dry): a stall above the lowest rung is followed by a lower rung; stops get shorter and a stalled
   play has no more of them; and few plays sit on the lowest rung, where there is no rung left to cap to. A count going the
   wrong way is a finding against FR-5. The baseline and the method are the document `field-read` on DEB-3093.
9. On a fast link and a 2160p ladder on our encoder, a cold start and a seek outside the buffer each ask for the top rung and start
   on it, with no abandon line in the log. A UniFi cap of 0.5 Mbps dropped while the 2160p rung plays abandons the slow segment for a
   lower rung, and writes one line per abandon. A cold start with a cap already under the start rung reaches a picture and falls to a
   rung the cap can carry.
   - *2026-11-01, DEB-3332 — why the cap is that low.* This case first read "a cap under the playing rung with a buffer in hand". On the
     Stue TV (DEB-3212) caps of 10 and 3 Mbps with 51 to 98 s buffered abandoned nothing and fell by the ordinary step-down with no
     stall. That is FR-4 working and it is what acceptance 4 asks for, so it is not a failure here. A load is abandoned when its
     projected finish passes the buffer minus 5 s, and on a 20 Mbps rung a 2 s segment projects 13 s at 3 Mbps, against about 90 s.
     A segment already in flight does not change that sum, and a thin buffer cannot be arranged: a cap takes a minute or two to go on,
     and under a live cap the player never climbs back to a rung the cap cannot carry. So the case moves the cap, not the buffer. At
     0.5 Mbps the same segment projects 80 s, and the buffer draining meets that within 13 s of the load's start even from 98 s, while
     a 2 s segment of real film at 4 Mbps or more is still loading. It sits under the lowest rung, so the seat asks for the cap's removal
     as soon as the line is written, and a stall after that line, while the cap is still on, is no finding.

     The seat reports the seconds buffered from FR-8's rung lines and the rate it measured. No abandon line, on a link measured at about
     0.5 Mbps with under 85 s buffered at the first load after the cap is live, is a finding against FR-4. Anything else is a void run,
     and it runs again.
