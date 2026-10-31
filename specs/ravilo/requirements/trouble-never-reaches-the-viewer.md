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
- A viewer's own audio, subtitle or version pick keeps today's treatment: that wait is their action (DEB-2205, FR-1).
- If the old stream is frozen (FR-5.2 of DEB-2205) there is nothing to keep playing: the switch shows the held frame until
  the new stream's first frame, and still draws no spinner under 2 s.

### FR-7 — The best picture the path can carry, soon

- **A stall's hold does not outlast the evidence.** The cap is 1 h (today 24 h), and a measurement newer than the stall
  whose × 0.7 clears a rung lifts the cap to that rung. A stall during a reap, a restart or a server wait (FR-1 to FR-3) is
  not the path's fault and writes no `stalled_bps`.
- **On our encoder the climb goes straight to the best rung the estimate × 0.7 clears**, with 10 s buffered. Every rung
  already exists and a step down is instant, so nothing is gained by one rung at a time. Jellyfin's ladder keeps FR-309-4,
  where a rung is a cold start.
- **A play that sits below the source on a fast link says why.** The 8 plays above are found out while building this: a
  trimmed ladder, a player that cannot climb or a cap each write a `held_by` reason into the encoder summary, and any that
  is a bug is fixed in this FR.

### FR-8 — Every stop and replacement leaves evidence

Restores DEB-2205's FR-8 on the roads that skip it.

- Every replacement on any road writes one `stalls_json` entry with cause `recovery`, its duration and the rung it left,
  and every restream names its reason in the QoE report.
- `encoder_summary` gains the p95 and maximum segment response time and the number of 503s, as the player sees them.
- A reap that spared a stream (FR-1) logs one line, so the next report can be answered from data.

### FR-9 — The other clients

After Android, because TVs are nearly all the plays. Each follows FR-4 to FR-6 as far as its player allows:

- **Web (hls.js):** retries and fragment abandonment configured to FR-4, FR-5's stall rule, FR-6.
- **Mac (AVPlayer):** FR-5's stall rule and FR-6.
- **Linux (mpv):** timeouts and reconnect set, and FR-6 on each step.
- **Cast receiver:** the restream recovery it has none of today, then FR-4 through Shaka's retry parameters.

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
