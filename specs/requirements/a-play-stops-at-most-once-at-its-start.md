# A play stops at most once, at its start, and plays the original whenever the path can carry it

Task: DEB-2205

> Owner, 2026-10-23 (DEB-2205), after a 4K HDR remux froze in Ravilo on the stue TV and played in another client:
> *"Can you take a look at why it was so bad and why our ladder setup wasn't kicking in properly, so it would just
> work flawlessly."*
>
> On the decision card the same morning:
> 1. *"We always want direct-play if the network (sometimes HDD is being used by other processes, which also effects
>    the performance) from the server to the client can handle it. But we want Ravilo/Jellystructure to be smart and
>    learn and pick the best playback as possible, but also making sure that it never buffers, unless it's only in
>    the beginning. Stopping and buffering more than once is a sign that we do not have the best algorithm."*
> 2. On where a frozen play should go: *"Whatever you think is best for the user. User should feel as little as
>    possible interference."*
> 3. On which resume position wins when another app played the title since: *"jellyfin decides this."*

## Scope

Backend and every Ravilo client that plays video (Android TV and phone first; the web, desktop and Cast receiver
inherit the server half and the rules below where they apply). There is no new screen and no new setting, so there
is no design round. The admin's existing QoE badge covers FR-1.

This spec **amends**:
- **177**: FR-177-2's ceiling, and FR-177-4's advertised-ceiling term.
- **179**: FR-179-1 and FR-179-2, for text subtitles.
- **309**: FR-309-1's decode term and FR-309-8.
- **313**: F2's recovery rule, and FR-313-6 on Android.
- **R292**: FR-R292-3's start position.

Each of those files carries a dated pointer here. The investigation, with the timeline and every log line, is the
[investigation document on DEB-2205](/DEB/issues/DEB-2205#document-investigation).

### What happened (2026-10-22, stue TV, Ravilo 1.56)

The film was a 2160p HDR10 HEVC remux: 69.6 Mbps video, DTS-HD MA 5.1, 53 GB, with seven embedded subtitle tracks
and a Danish sidecar `.srt` added later.

- **Ravilo did not direct-play it.** The TV's `MediaCodec` advertises `bitrate-range 1–60000000`, and FR-177-2
  turns that into a hard `VideoBitrate ≤ 54 Mbps` condition. So the play went to our encoder at 2160p@20 Mbps HEVC
  HDR, prewarmed about 40 s ahead.
- **The picture never passed 0:02.** On load, the player sideloaded every text subtitle track although subtitles
  were off.
  - Jellyfin's extraction cache is keyed by stream index, and the sidecar had shifted every embedded index by one.
    Index 4 missed the cache, and Jellyfin read the whole 53 GB file for 4 minutes to extract it.
  - The three subtitle loads failed (`subtitle_load_errors = 3`).
  - After 20 s, 313 F2's stuck watch restreamed to Jellyfin at the ladder's top: 4K H.264 at 40 Mbps, tone-mapped
    to SDR. That stream did not move either before the viewer gave up 11 s later.
- **Not the disk, and not the decoder.**
  - Jellyfin's own transcode read the same disk at 8× real time while the extraction ran.
  - Our encoder's segments were already made before Play.
  - The same TV played our 2160p HEVC rung cleanly on 10-14.
  - The common factor of both frozen streams is the player waiting on subtitle requests. That is a strong inference,
    not a proof: the failed play's QoE row was overwritten by a later one-second retry, and the deploy at 22:55
    replaced the container log (FR-8).
- **The other client direct-played the original file** on the same TV for 7½ minutes: the 69.6 Mbps HEVC HDR10
  picture, DTS decoded on the device.
- **Ravilo came back from the background 1 h 50 min later** and reopened the film at its own 0:02, although
  Jellyfin held 7:34 from the other client.
- **Found on the way: Jellyfin serves the wrong language.** Its subtitle cache `.meta` holds only the video file's
  size and mtime, so after a sidecar shifts the indices, index *n* is answered with the old *n*'s file. On this
  film today, *Arabic* shows the German track and *German* shows the Spanish one. Across the library, **424 of the
  4 385** titles with a Jellyfin subtitle cache have a sidecar newer than that cache (read 2026-10-23). That is an
  upper bound: a sidecar that replaced an older one shifts nothing.

## Principles

1. **The original, whenever the path carries it.** The device's *advertised* decoder bitrate is a hint, not a wall.
   What the device has *shown* it can decode, and what the path from the disk to the screen has *shown* it can carry,
   decide (309's principle 3, extended to decoding).
2. **One stop, at the start.** After the first frame, the picture stops only when the viewer asks it to (pause, seek,
   a subtitle or audio pick). Every other stop is a defect the system learns from at once.
3. **Leave before it runs dry.** A stream that is falling behind is replaced while there is still picture in the
   buffer, so the change is seen as a quality change, not a stop.
4. **Nothing optional holds the picture.** A subtitle, an artwork, a probe or a report never delays the first frame
   or stops the picture.
5. **Every stop leaves evidence.** The next report is answered from data, not reconstruction.

## Requirements

### FR-1 — The measure: stops after the first frame

- A **stop** is any moment after a play's first frame, other than a viewer pause, when the position does not advance
  for **≥ 1 s** while the player intends to play.
  - The wait after a viewer's seek, or after their own audio or subtitle pick, is that action's start and is not a
    stop.
  - A start (FR-309-2's first frame) is not a stop.
- Every client's QoE report counts them: the existing `rebuffer_count` / `rebuffer_ms` cover the buffer-empty kind.
  A new optional field, `frozen_count` / `frozen_ms`, covers the kind where the position does not move with buffer
  in hand. Each entry in the existing `stalls_json` gains a `cause`:
  - `buffer_empty`
  - `frozen_with_buffer`
  - `decode`, for FR-3's signals
  - `recovery`, a switch FR-4 or FR-5 made that showed as a stop
- The admin's existing `--warn` QoE badge (177 FR-177-5) fires on **≥ 1 stop**. No new surface.
- **Target:** zero stops on a play whose path has carried the same bitrate before. A play that stops twice is a
  defect to file, as the owner said.

### FR-2 — Direct play whenever the path can carry it

- A file direct-plays when its stream bitrate fits **both** of these:
  - its stream bitrate is FR-309-15's `directPlayStreamBps`, the figure Jellyfin weighs;
  - **what the device can take for delivery** is FR-309-1's take **without** the advertised decode term:
    `min(proven_bps or measured × 0.7, stalled_bps × 0.8 while < 24 h)`;
  - **what it has shown it can decode** is FR-3's learned ceiling for that codec, when one exists.
- **The advertised decoder bitrate is no longer a condition** for a client that reports decode health (FR-3). For
  those clients:
  - FR-177-2's `VideoBitrate` codec profile is emitted from the learned ceiling only, and not at all when nothing has
    been learned;
  - FR-177-4's `MaxStreamingBitrate` drops its advertised-ceiling term.
  - The advertised value is still reported, stored and shown on the admin's device row, labelled *advertised*.
- **An installed client that does not report decode health keeps today's rule exactly**, advertised ceiling
  included. It cannot hand over (FR-4), so it must not be sent into a decoder it never measured.
  - The capability is a new optional `ClientCapabilities.reports_decode_health` (`false` by default).
- The disk is part of the path. A stall caused by the server's disk (another process reading it, an import, a
  re-extraction) is recorded like any other stall (FR-309-1's `stalled_bps`). The next play within 24 h of a file
  that heavy then starts on the ladder. No separate disk measurement and no scheduling of other processes (non-goals).

### FR-3 — The decode ceiling is learned, not advertised

- During a direct play, a client that reports decode health watches two signals:
  - (a) **dropped frames** above **2 % of rendered frames over any 10 s window**. The QoE report gains
    `rendered_frames` beside the existing `dropped_frames`.
  - (b) **a frozen picture with buffer in hand**: the position not advancing for **3 s** while ≥ 5 s is buffered
    ahead.
- On either signal the client:
  - reports `decode_failed` with the file's video codec and the stream's video bitrate, on the QoE report as new
    optional fields `decode_failed_codec` and `decode_failed_bps`;
  - hands over at once (FR-4, immediate form).
- The backend keeps it on the device's stream record in new columns `decode_failed_bps`, `decode_failed_codec` and
  `decode_failed_at`, one entry per codec family (`hevc`, `h264`, `av1`, `vp9`).
  - From then on a file of that codec above **0.9 × `decode_failed_bps`** goes to the ladder, and FR-177-2's
    condition is emitted at that value.
  - It does not expire with time, because a decoder does not get faster.
  - It is **lifted** by a clean direct play in the same codec: ≥ 10 min, < 0.1 % dropped, no stop, at a higher
    bitrate. That sets the ceiling to `null`. A ceiling 7 days old stops holding negotiation until a play lifts it or fails again (amendment 2026-10-25, below).
- A play the viewer stops within 3 s of a signal still records it.

### FR-4 — Leave a direct play before it runs dry (amends FR-309-8)

- A direct play watches its buffer in **seconds ahead**. It is sized in seconds rather than bytes:
  - Media3's `DefaultLoadControl` gets `setPrioritizeTimeOverSizeThresholds(true)`;
  - a target buffer of **≥ 30 s**, bounded by the device's memory class (`ActivityManager.getMemoryClass()` × 0.25
    for the buffer). On a device that cannot hold 30 s at the file's bitrate, every threshold below scales to half
    of what it can hold.
- **The rule fires** when either is true:
  - the buffer ahead is under **15 s and has fallen for 3 s**;
  - the player's real estimate (FR-309-13) × 0.8 is under the file's stream bitrate while under 30 s is buffered.
- **Hand-over, ahead of time:**
  - The client asks for the ladder at **H = position + buffered − 2 s**, through R284's restream with a new optional
    `handover_at_ms`. The server answers with our encoder's master starting at the segment containing H (FR-313-1's
    prewarm, adopted).
  - The start rung is the best rung within the player's real estimate × 0.7. Never above the file's own bitrate.
  - The client keeps playing the direct stream and switches when the position reaches H. The switch must show the
    new stream within **1.5 s** of H. A blink for a decoder re-init is allowed and is logged as cause `recovery`
    if it exceeds 1 s.
  - Our segments at H must exist before the switch. If they do not by H, the client keeps the direct stream while it
    still has buffer, and switches at the first segment that exists.
- **Immediate form:** when the rule fires with under 4 s buffered, or on FR-3's signal, the restream happens at the
  current position at once. This is today's FR-309-8 behaviour, now the fallback rather than the plan.
- Either form writes the file's stream bitrate as `stalled_bps` (24 h hold, FR-309-1), or `decode_failed_*` for FR-3.
  A stream handed over in the last 24 h is not re-offered as direct play.

### FR-5 — A frozen ladder play recovers quickly, downward, in place first (amends 313 F2)

This applies to an HLS play, ours or Jellyfin's.

1. **Buffer empty, mid-play:** 309's step-down (FR-309-5) is the answer. In addition, after **3 s** of waiting the
   player caps its own adaptive selection at the rung **below** the current one (Media3
   `TrackSelectionParameters.setMaxVideoBitrate`). It does not restream, and it records `stalled_bps`.
2. **Position not moving with ≥ 3 s buffered, for 5 s** (replacing 313 F2's 20 s, `OUR_STREAM_STUCK_MS`): this is a
   decode or render stall, not delivery.
   - The client restreams **once** (R284) at the current position: to our encoder at the rung **below** the frozen
     one, in the other codec family where the device has one (HEVC froze → H.264 SDR rungs; H.264 froze → HEVC if
     the device declares it).
   - If that freezes too, it restreams to Jellyfin (`not_our_encoder`, as 313 F2), with the negotiation cap at the
     rung below the first frozen one. **Never above it.**
   - A third freeze ends on R237's error (Retry / Back) as today.
3. **Never up on trouble.** No recovery may start above the rung that was playing when the trouble began, and
   nothing climbs again (FR-309-4) for 2 min after a recovery.
4. Each recovery is a stop (FR-1, cause `recovery`) and is logged in one line, for example
   `recovery: froze on 2160p@20000k (hevc) with 9 s buffered → ours 1080p@8000k h264 (DEB-2205 FR-5)`.

### FR-6 — A subtitle never holds the picture, and is always the track it says (amends 179)

- **Only the shown subtitle is loaded.** The ticket still lists every text track (the picker needs them), but the
  client sideloads **only** the track being shown, plus a forced track the rules show. With subtitles off it loads
  none.
  - Picking another track mid-play loads it then. That wait is the viewer's own action (FR-1) and may re-prepare at
    the current position.
- **A subtitle load is never part of the video's preparation.** Android fetches the selected subtitle itself, off
  the media item, and attaches it when it has arrived, so a subtitle URL that hangs never delays the first frame and
  never stops the picture.
  - Any shape that meets the test below is acceptable: a separate `MergingMediaSource` child added after the load,
    or Media3's text output fed from the app.
  - **Test:** a stub subtitle URL that answers after 5 minutes delays neither the first frame nor the position.
    The subtitle shows within 2 s of arriving.
- **Embedded text subtitles come from jellystructure, not from Jellyfin's index-keyed cache.** This is FR-313-6's
  *converted once per file and cached*, now for every client and every path (direct play, our encoder, Jellyfin's).
  - The backend extracts each embedded text track to WebVTT with one ffmpeg pass per file (every text track in one
    read, `ionice -c3`, `defer_while_playing`).
  - The cache is keyed by **file path + size + mtime + the container's own stream index** (ffprobe's, not Jellyfin's).
  - It is served at a new `GET /api/tv/subtitles/{item}/{containerIndex}.vtt` with the device token.
  - The ticket's `SubTrack.url` for an embedded text track points there. Sidecar files keep Jellyfin's URL, which
    reads the sidecar directly, with no extraction and no index-shift risk.
  - The extraction runs in the processing features' background lane when a file is new or its size or mtime changes.
    It replaces FR-179-1's Jellyfin warm for text tracks.
  - A request for a track not yet extracted starts that file's pass and answers **202** with `Retry-After`. The
    client retries in the background and shows the subtitle when it arrives. It never waits on it.
  - *Amendment 2026-10-23 (DEB-2209):* the ticket's `SubTrack` gains an optional `container_index` (the container's own
    stream index of an embedded text track; absent for a sidecar, an image track, or a file the server could not match to its
    own scan, in which case that track keeps Jellyfin's URL). Our encoder's WebVTT renditions (FR-313-6) read the same cache,
    so no path asks Jellyfin's index-keyed extraction for a text track it has scanned. The extraction needs no Jellyfin
    connection. The client loads the shown track when it is *selected*, not when the ticket arrives, so the picker lists every
    track with no request made; with subtitles off none is.
- Image subtitles (PGS, VobSub) are unchanged: burn-in via our encoder (FR-313-6), or Jellyfin.
- Wire: the ticket shape is unchanged. Only the URL value differs, and an installed client fetches whatever URL it
  is given.

### FR-7 — The start position: Jellyfin decides (amends FR-R292-3)

- A start that carries the client's own position (R292's return from the background, R306's exception) uses
  **Jellyfin's** user-data position whenever Jellyfin's record for the item was written **after** this client's own
  stop of it, that is, whenever another app or device played it since. One log line:
  `playback start: … Jellyfin's position 454335ms is newer than the client's (2866ms) — Jellyfin's wins (DEB-2205
  FR-7)`.
- When Jellyfin holds nothing newer than this client's own stop, the client's own position stands. This is the case
  R292 exists for: Jellyfin drops a position under its minimum-resume percentage. That position is the one Jellyfin
  was told at that stop, so Jellyfin has not been overruled.
- *Reading of the owner's answer, stated so it can be corrected:* "Jellyfin decides" is taken as "Jellyfin's record
  wins whenever anyone else has written it", not as "a return from the background starts at 0 when Jellyfin dropped
  a short position".

### FR-8 — Every stop leaves evidence

- **One QoE row per play, not per title.** The backend keys a play's QoE by the play's own session (the
  `playback_session.id` it already holds for device + item at report time), so a retry or a re-watch never
  overwrites an earlier play's row. Installed clients need no change. The admin's per-device summaries read the
  newest row per play.
  - *Amendment 2026-10-23 (DEB-2209):* a start made within the 15 s stop-hold **joins** the live `playback_session`
    and keeps its id, so a retry one second later would still have shared the key. The key is therefore the session's id
    **and the time that start was served**: `<playback_session.id>@<start ms>`. A start with no session falls back to
    `<device>-<item>@<start ms>`. A report that arrives after the stop still finds the play's key (the server keeps the last
    one per device and item); one from before this build keeps today's `<device>-<item>`.
- **The encoder's own record of each play.** When a job stops, the backend logs one line to the activity ring and
  stores the same figures on the play's QoE row in a new `encoder_summary` JSON column:
  - segments made;
  - speed (min and median × real time);
  - the longest any segment request waited, and how many waited over 1 s;
  - the input read rate (MB/s);
  - the rungs it ran;
  - whether it was paused.
- **The client's stop entries** (FR-1's `stalls_json` with `cause`) carry the buffer ahead, the rung, and the
  pending subtitle loads at that moment.

- *Amendment 2026-10-23 (DEB-2211, slice 3 — the readings FR-1 and FR-5 were built to):*
  - **Which stops are frozen.** `frozen_with_buffer` is a position that does not advance for **≥ 1 s** while the player
    wants to play, is not buffering or seeking, has rendered its first frame and holds **≥ 3 s** ahead. It is measured
    for every play (direct play included), counted in `frozen_count` / `frozen_ms`, and entered in `stalls_json` with
    its `cause`, the buffer ahead and the rung. Only `buffer_empty` entries are *delivery* stalls: the server's stream
    record (`stalled_bps`, FR-309-1) and the client's direct-play-to-ladder rule read those and ignore the other
    causes, because a frozen picture says nothing about what the path can carry (FR-3 is where decode is learned).
  - **FR-5.1's cap is lifted after 2 min.** The in-place cap (`setMaxVideoBitrate`, one rung below the current one,
    taken from the rungs the master lists) holds for the "nothing climbs for 2 min" window and is lifted at its end, so
    FR-309-4's own one-rung-up rule applies again. A new load clears it.
  - **FR-5.2's units.** The frozen rung is the variant's `BANDWIDTH` the player reports. The restream's ceiling is
    sent as `ClientCapabilities.max_video_bitrate` = `(BANDWIDTH − 640 kb/s) / 1.5 − 1`, which is always under the
    frozen rung's own video bitrate (the largest audio allowance is 640 kb/s), so no rung at or above it is offered. The
    codec flip is `hls_hevc` / `hls_hevc_capable` = false for a frozen HEVC rung; a frozen H.264 rung leaves the
    declaration as the device made it, and the server picks HEVC where the device declares it and the source allows.
    No new wire field: an installed server and an installed client keep working in both directions.
  - **What 20 s still watches.** `OUR_STREAM_STUCK_MS` (20 s) stays for a stream from our encoder that never moves with
    **no** buffer in hand (313's refused plan): that is a start that did not happen, not a frozen picture. The 5 s watch
    needs the first frame and ≥ 3 s ahead.
  - **Every freeze is counted per item, not per stream.** The first, second and third freeze of one item are FR-5.2's
    three steps whichever encoder served the stream they happened on. A direct play that freezes is measured (FR-1) and
    does not recover here: its hand-over is FR-4.
  - **The QA flag.** A debuggable Android build stops the picture on a broadcast
    (`dev.jellystructure.ravilo.DEBUG_FREEZE_RENDER`): playback holds its frame and position while the buffer fills, and the
    store reads that as a freeze. A release build binds nothing.

- *Amendment 2026-10-24 (DEB-2212, slice 4 — how FR-2, FR-3 and FR-4 sit on the wire and in the player):*
  - **Who is relieved of the advertised ceiling.** `ClientCapabilities.reports_decode_health` (default `false`) is sent by the
    Android app (TV and phone) only. For a client that sends it:
    - the per-codec `max_hevc_bitrate` / `max_h264_bitrate` are still reported, stored and shown *advertised*, and are **not**
      conditions;
    - `max_video_bitrate` is read as an **explicit cap** (FR-5.2's recovery ceiling, a stepping restream's) and the app sends it as 0
      otherwise, so FR-177-4's `MaxStreamingBitrate` has no advertised term left for it;
    - FR-177-2's `VideoBitrate` condition is emitted per codec family from the learned ceiling only, at **0.9 × `decode_failed_bps`**
      (one `LessThanEqual` profile per family: `hevc`, `h264`, `av1`, `vp9`), and not at all for a family with nothing learned;
    - the ladder's top for Jellyfin's own transcode (`decodeCeiling`) reads the same learned figure.
    An installed client keeps today's rule byte for byte, and ignores nothing it sent.
  - **Where a decode failure is kept.** `87.sqm` adds `decode_failed_bps`, `decode_failed_codec` and `decode_failed_at` to the
    device's stream record as three **parallel comma-separated lists** (the failed video bits/s, the families, the epoch seconds), so one
    row holds one entry per family. A new failure of a family keeps the **lower** bitrate (a failure higher up says nothing new); the
    time is the newest. It never expires. `playback_qoe` gains `rendered_frames`.
  - **The QoE report carries the facts, the server writes the record.** New optional fields: `rendered_frames`; `decode_failed_codec`
    and `decode_failed_bps` (the family and, where the client knows it, the stream's video bitrate; the server reads it from the file
    when it is absent); and `stalled_bps` (the file's stream bitrate of a direct play left because the path could not carry it, written
    as FR-309-1's stalled stream). Each is sent **once**, on the post that precedes the restream, and the restream waits for that post.
    A repeat of `stalled_bps` never restarts the 24 h hold.
  - **The lift.** A direct play of a family with a failed ceiling lifts it when the *same stream* has been held ≥ 10 min between
    posts, the report has no stop of any cause and no frozen picture, under 0.1 % of `rendered_frames` were dropped (a report with no
    rendered count never lifts), and the file's video bitrate is above the failed one.
  - **The signals.** (a) Dropped frames are judged over a 10 s window of the renderer's cumulative counts; a window that rendered
    under 120 frames says nothing, and a pause, a seek or a decoder re-init starts it again. (b) The frozen picture is FR-1's own meter:
    held ≥ 3 s with ≥ 5 s buffered. The frozen stop is counted as `frozen_with_buffer` as FR-1 says; the dropped-frame signal, which is
    not a stop, enters `stalls_json` as a `decode` entry with no duration. Both hand over at once.
  - **The hand-over ahead of time.** `PlaybackRestreamRequest.handover_at_ms` carries H. The server negotiates as a restream, starts
    its encoder at H's segment and **answers when that segment of the start rung exists, or after 8 s**, with the ticket starting at H
    (`startPositionMs = H`). The player keeps the direct stream, holds the ticket, and cuts over when the playhead is within 250 ms of
    H, starting the new stream at the later of H and the playhead. Three exits besides: the buffer runs dry before H (under 1 s ahead
    while waiting) cuts over at once at the playhead; a ticket that arrives after H cuts over at once ("the first segment that
    exists"); a seek away (back 1.5 s, or forward past H by 30 s), no answer in 30 s, a failed restream, or an answer that is the
    direct play again drops the hand-over and the existing stall path (FR-309-8) is free again. The ticket of a direct play carries
    `stream_bps`, the file's whole stream bitrate (size × 8 / length), so the player can compare it with its own estimate; the rule
    reads the estimate only when it rests on ≥ 3 transfers and ≥ 2 MB (FR-309-13).
  - **The buffer.** `DefaultLoadControl` is subclassed (`TimeFirstLoadControl`): time over size, a 50 s target (above the ≥ 30 s the
    spec asks for), **for a direct play only**, stopped at what `getMemoryClass()` × 0.25 holds at the file's bitrate. Every other
    stream, an HLS ladder above all, keeps the byte bound it always had: a 2160p rung at 50 s does not fit the heap of the TV this
    runs on. The thresholds above (15 s, 30 s, 4 s) scale by `holdable / 30 s` where the device holds less than 30 s of the file.
  - **What CI cannot prove** and moves to the QA ticket: the reported 4K HDR10 remux direct-playing on the stue TV for 10 min with
    no stop (Acceptance 1), a hand-over on a shaped link on that TV (Acceptance 4), and the memory class the TV really reports. The
    timing rule is tested against a throttled local server (`DirectPlayHandoverTimingTest`); the Media3 cut itself is not.

- *Amendment 2026-10-25 (DEB-2205, the owner's answer on the ceiling-lift card: **Weekly retry**):*
  - **The gap.** FR-3 sends a file above 0.9 × `decode_failed_bps` to the ladder, and lifts the ceiling only after a clean
    direct play *above* the failed bitrate. No such file is ever offered as a direct play, so as built the ceiling could never
    lift, and one bad evening (heat, a background app, a QA forced freeze) cost a TV its 4K direct play for good. Høgni found it
    on DEB-2366.
  - **The retry.** A family's learned ceiling is **not a condition** once its `decode_failed_at` is **7 days** old or more. Every
    negotiation that reads the ceiling treats that family as having nothing learned: FR-177-2's `VideoBitrate` profile, the
    direct-play gate and the ladder's `decodeCeiling` top. The stored entry stays as it is.
    - A clean play under the slice-4 lift rule (≥ 10 min, no stop, < 0.1 % dropped, above the failed bitrate) **lifts** it, as
      before.
    - A new decode failure of that family is noted as before: the lower bitrate is kept and `decode_failed_at` becomes now. That
      **restarts the 7 days**, and FR-4's immediate hand-over is the viewer's safety net for that one play.
    - A play in the window that neither lifts nor fails leaves the window open for the next play.
  - **This replaces "It never expires"** in the slice-4 amendment's *Where a decode failure is kept*. The entry still never
    expires *from the record*. What lapses after 7 days is its hold on negotiation.
  - **What the admin shows** is the stored entry with its date, unchanged. Nothing on the wire changes, and an installed client
    that does not report decode health keeps the advertised rule exactly.

## Acceptance

1. **The reported film plays.** On the stue TV, the reported film (or any 2160p HDR10 HEVC file of 60–80 Mbps whose
   path has carried it) **direct-plays**. The backend's `PlaybackInfo` line says `directPlay=true`, and 10 min play
   with `rebuffer_count = 0` and `frozen_count = 0`.
2. **A hanging subtitle holds nothing.** A subtitle endpoint stubbed to hang 5 min: first frame within FR-309-2's
   target, position advancing throughout, the subtitle shows when it arrives. With subtitles off, no subtitle request
   is made at all (read in the backend's access log).
3. **The right language.** A file given a new sidecar after its first extraction serves the right language for
   every embedded track (compare the served WebVTT's first cue with ffprobe's stream language).
4. **Hand-over without a stop.** A direct play throttled below its bitrate (QA: the TV on a shaped link) hands over
   to our ladder ahead of time: one `recovery` entry or none, never a `buffer_empty` stop. The next play of the same
   file within 24 h starts on the ladder.
5. **A freeze goes down.** A forced freeze on our HEVC rung (QA: a debug flag that stops rendering) recovers within
   ~5 s on a lower H.264 rung. The log line names both rungs, and nothing restreams above the frozen rung.
6. **Resume follows Jellyfin.** Play 5 min in another client after backgrounding Ravilo on the same title; bring
   Ravilo back: it resumes at the other client's position.
7. **Evidence survives a retry.** Two plays of one title leave two QoE rows. A play served by our encoder has an
   `encoder_summary`.

## Non-goals

- **Scheduling other processes on the host** (imports, torrent checks, other clients). FR-2 learns from the stalls
  they cause; it does not prevent them.
- **Shedding a rung from a running encoder job when it falls behind real time.** No play has been seen to need it.
  FR-8 records the job's speed, so a real case can be specced from data.
- **An upstream Jellyfin fix to its index-keyed subtitle cache.** Worth preparing per `upstream-contrib`. The owner
  decides whether to send it; FR-6 does not depend on it.
- **Whether a return from the background after a long absence should start playing by itself** (it did, after
  1 h 50 min). That is R292's play-intent rule, unchanged here; it is raised on the task as an observation.

## Supersedes and amends

| Spec | What changes |
|---|---|
| 177 FR-177-2, FR-177-4 | For a client reporting decode health, the advertised ceiling is not a condition. FR-3's learned ceiling is. |
| 179 FR-179-1, FR-179-2 | Text subtitles are extracted and served by jellystructure; only the shown track is loaded; a load never holds the picture. |
| 309 FR-309-1, FR-309-8 | The take's decode term is FR-3's learned ceiling; a direct play hands over before it runs dry. |
| 313 F2, FR-313-6 | The stuck watch is 5 s with buffer in hand and recovers downward, ours first. Text subtitles come from our cache on every client. |
| R292 FR-R292-3 | A return's start position is Jellyfin's when another app or device wrote it since. |

## Code anchors (`main` at `68f26bab`)

- Direct-play gate:
  - `auth/JellyfinClient.kt` `videoBitrateConditions` (FR-177-2's condition);
  - `tv/PlaybackService.kt` `decodeCeiling` (:728) and the negotiation around :966;
  - `tv/StreamRecord.kt` `canTake` (:61), `negotiationCap` (:99), `directPlayStreamBps` (:109);
  - `db/StreamRecord.sq`.
- Client capabilities: `ravilo-ui/…/seams/HdrCapabilities.kt` `detectDecoderLimits`; `shared/…/tv/Models.kt`
  `ClientCapabilities`, `PlaybackQoeReport` (:185).
- Stuck watch and recovery: `ravilo-ui/…/screens/PlayerStore.kt` `StuckWatch` (:110), `OUR_STREAM_STUCK_MS` (:122),
  `startFailureWatch` (:680); `tv/PlaybackService.kt` `withRenditions` / `restream` (:644, :1950).
- Player: `ravilo-ui/…/seams/RaviloPlayerAndroid.kt` — `load()` builds every `SubtitleConfiguration` (:431–463), and
  `SubtitleRetryingLoadErrorHandlingPolicy` (:808).
- Subtitles: `tv/PlaybackService.kt` `buildSubtracks` (:1847, Jellyfin's `Stream.vtt` URL at :1899 and :723).
- Resume: `tv/PlaybackService.kt` :944–996 (`startPositionMsOverride`, R292/R306).
- Encoder: `tv/Encoder.kt`, `tv/EncoderJobs.kt`, `tv/EncoderPlan.kt` (`ENCODER_SEGMENT_MS` 2 s).

## Build order

One engineer, one slice at a time. Each slice ships on its own and is QA'd on the stue TV.

1. **FR-6 + FR-8.** This removes the most probable cause of the reported freeze and the wrong-language subtitles, and
   makes the next report readable. Backend (`/api/tv/subtitles`, the extraction pass, the per-play QoE key, the
   encoder summary) and Android (only the shown track loaded, a subtitle off the video's preparation).
2. **FR-7.** Backend only, small.
3. **FR-1 + FR-5.** The stop measure, and downward recovery in place.
4. **FR-2 + FR-3 + FR-4.** Direct play past the advertised ceiling, learned decode, and hand-over ahead of time.
   This is the slice that makes the reported film direct-play.
