# Phase R297 — Casting plays again: tell the receiver the stream is fMP4, and never skip on an error

## Status

`Planned` — written 2026-09-24 from a device test, not dev-reviewed. Spec first. Amends **R285**
FR-R285-5 and **253** FR-253-3 (the fMP4 HLS profile they turned on for the Chromecast) and **R245**'s
end-of-item behaviour.

## What happens today

Casting any title to the stue TV's built-in Chromecast fails the same way (2026-09-24, a clean episode
and a damaged one alike):

- the receiver negotiates a ticket and Jellyfin starts a transcode;
- about two seconds later the receiver stops it (`stopped playback … at 0ms`, client *Ravilo dev*);
- it loads the **next episode**, which fails the same way, and so on down the queue. The phone's remote
  is left on the last item at 0:00 / 0:00 with Play disabled; the TV shows the idle screen.

Two defects compound:

1. **The stream is fMP4 and the receiver never says so.** Since 253/R285 the receiver declares
   `hls_hevc` whenever `canDisplayType('video/mp4', 'hev1…')` answers yes (it does on the BRAVIA), and
   the backend then asks Jellyfin for its fMP4 HLS profile. Replayed 2026-09-24: `SegmentContainer=mp4`,
   an `#EXT-X-MAP` init segment and `.mp4` media segments, AC-3 copied. The receiver hands CAF only
   `contentType = application/x-mpegURL`; CAF's HLS player assumes MPEG-TS segments unless
   `media.hlsSegmentFormat` / `media.hlsVideoSegmentFormat` say `FMP4`. Casting worked on 2026-09-18,
   before this profile existed.
2. **An error is treated as the end of the episode.** `onFinished()` runs on CAF's `MEDIA_FINISHED`
   whatever its `endedReason`, and advances to the next episode. So one bad stream walks the whole
   queue at ~2 s per item. The `ERROR` listener is empty, so nothing records why.

## Requirements

- **FR-R297-1 — fMP4 is declared.** When the ticket's HLS URL is Jellyfin's fMP4 profile
  (`SegmentContainer=mp4`), the LOAD the receiver hands CAF sets `hlsSegmentFormat` and
  `hlsVideoSegmentFormat` to `FMP4`. A TS stream is left exactly as today. This covers a restream
  (R285's track change) as well as a first load, since both pass through the same interceptor.
- **FR-R297-2 — Only a real end moves on.** `MEDIA_FINISHED` advances to the next episode only when its
  `endedReason` is `END_OF_STREAM` (or absent, as older frameworks send it). On `ERROR` (and any other
  reason) the receiver stops the session, returns to its idle screen and tells the phone the item
  ended, exactly as a finished last episode does. It never loads another item on its own after an error.
- **FR-R297-3 — Errors are recorded.** The `ERROR` listener logs CAF's `detailedErrorCode` and reason to
  the receiver's console, so the next failure can be read through DevTools rather than guessed.

## Non-goals

- A dedicated "couldn't play on {device}" state on the phone. The receiver's message set
  (`status | busy | noserver | nextup | ended | tracks`) has no failure type, and installed phones would
  ignore a new one; that needs a phone release and its own copy in three languages. Follow-up.
- Turning `hls_hevc` off for the Chromecast. fMP4 is the only HLS shape Jellyfin emits HEVC in, and
  losing it would re-encode every HEVC title for a device that decodes HEVC (253's reason).
- The tracks-at-end MKV (R294) on the receiver. It transcodes through Jellyfin, which reads the file
  itself; testable once casting works at all.

## Acceptance (stue TV's built-in Chromecast, cast from the Pixel's release app)

1. **Before the fix, from DevTools on the receiver:** the load fails with a CAF error, confirming
   defect 1 (recorded here before the fix is deployed).
2. A clean episode cast from the phone plays on the TV, with sound, from the phone's position; pause,
   seek and stop from the phone work.
3. Changing audio or subtitles from the phone (R285 restream) keeps playing.
4. A forced error (e.g. an unreachable stream) leaves the TV on its idle screen and the phone's remote
   idle; no other episode is loaded.
5. The episode from R294 (tracks at end) casts and plays.
