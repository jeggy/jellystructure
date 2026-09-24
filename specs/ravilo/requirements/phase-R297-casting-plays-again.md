# Phase R297 — Casting plays again: probe audio like video, and never skip on an error

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

Two defects compound, both measured on the device through the receiver's DevTools
(`@cast_shell_devtools_remote`, forwarded over adb):

1. **The receiver claims audio codecs it cannot play.** Every load fails with
   `[cast.framework.PlayerManager] Load failed: Shaka Error 4032` (`CONTENT_UNSUPPORTED_BY_BROWSER`:
   no variant is playable). Asked on the device, `MediaSource.isTypeSupported('audio/mp4;
   codecs="ac-3"')` and `"ec-3"` are **false**, as is `canDisplayType('audio/mp4', 'ac-3'/'ec-3')`,
   while AAC, Opus, H.264 and HEVC are true. `capabilities()` probes video but hard-codes
   `audioCodecs = aac, mp3, opus, ac3, eac3`, so for an AC-3 source Jellyfin copies AC-3 through
   (master playlist `CODECS="avc1.4D4029,ac-3"`) and Shaka rejects the whole stream. Negotiated without
   AC-3, the same episode comes back `CODECS="avc1.4D4029,mp4a.40.2"`; loaded with Shaka in the
   receiver page it reaches `readyState 4` with 13 s buffered, where the AC-3 variant fails 4032.
2. **An error is treated as the end of the episode.** `onFinished()` runs on CAF's `MEDIA_FINISHED`
   whatever its `endedReason`, and advances to the next episode. So one bad stream walks the whole
   queue at ~2 s per item, which is what the phone's remote shows as the episode changing by itself.

3. **The backend ignored the declaration anyway.** With the probing receiver hot-swapped into prod
   (2026-09-24, bedroom TV), the receiver no longer declared AC-3 (`isTypeSupported('…ac-3') -> false`
   in the TV's own log), yet Jellyfin's command line was still `-codec:a:0 copy` and the load died the
   same way. `deviceProfile()` wrote the `TranscodingProfiles` `AudioCodec` as a fixed
   `aac,ac3,eac3,mp3` (fMP4) / `aac,ac3,mp3` (TS), ignoring `capabilities.audioCodecs`.

*First hypothesis, withdrawn:* that CAF needed `hlsSegmentFormat = FMP4` for 253's fMP4 profile. The
console shows CAF plays HLS through Shaka, which detects fMP4 itself; the failure is the audio codec.

## Requirements

- **FR-R297-1 — Audio is probed like video.** The receiver declares AC-3, E-AC-3 and Opus only when
  `canDisplayType('audio/mp4', …)` says the device plays them; AAC and MP3 always. A Chromecast that
  passes AC-3 through to an amplifier keeps it; one that cannot gets AAC from Jellyfin instead.
- **FR-R297-4 — A transcode carries only audio the client declared.** The transcoding profile's
  `AudioCodec` is the segment type's own list intersected with `capabilities.audioCodecs`, AAC when
  nothing is left. A client that declares nothing keeps today's list; the Android app (which declares
  AC-3 and E-AC-3) gets exactly today's profile.
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
- The Tizen receiver (`ravilo-screen`) has its own hard-coded capability list; it is not this phase.
- The tracks-at-end MKV (R294) on the receiver. It transcodes through Jellyfin, which reads the file
  itself; testable once casting works at all.

## Verified so far

- **FR-R297-2 (bedroom TV, receiver hot-swapped into prod, 2026-09-24):** a failing cast now produces
  one `PlaybackInfo` and the idle screen; no other episode is loaded.
- **FR-R297-1 (same):** the receiver's probes appear in the TV's own log: `ac-3` false, `ec-3` false,
  `opus` true.
- **FR-R297-4:** unit tests only; needs a backend deploy.

## Acceptance (a TV's built-in Chromecast, cast from the Pixel's release app)

1. **Before the fix (done 2026-09-24, stue TV):** Shaka 4032 on every load; AC-3/E-AC-3 unsupported;
   the AAC-negotiated stream loads in the receiver page.
2. A clean episode cast from the phone plays on the TV, with sound, from the phone's position; pause,
   seek and stop from the phone work.
3. Changing audio or subtitles from the phone (R285 restream) keeps playing.
4. A forced error (e.g. an unreachable stream) leaves the TV on its idle screen and the phone's remote
   idle; no other episode is loaded.
5. The episode from R294 (tracks at end) casts and plays.
