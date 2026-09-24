# Phase R302 — The web app declares only the audio it can play

## Status

`✓ Done` — written and built 2026-09-24 (`3077f92c`), deployed to prod the same day (dev image), not
dev-reviewed. Verified in headless Chromium against prod: the page publishes `aac,mp3,flac,opus`; the
backend negotiates AAC for that list (FR-R297-4). Acceptance 2 not run as a signed-in viewer. Amends **R283** FR-R283-1's web line
("exactly the pre-R283 list") and pairs with **R297** FR-R297-1 (the Chromecast) and FR-R297-4 (the
backend honours the declaration).

## What happens today

`AudioCapabilities.wasmJs.kt` declares `aac, mp3, flac, opus, ac3, eac3` for every browser. Chrome
and Firefox on a desktop cannot decode AC-3 or E-AC-3 in MSE; Safari and Edge-on-Windows sometimes
can. So on most browsers an AC-3 or E-AC-3 title direct-plays (the backend offers `mkv` direct play
with that audio list) and the `<video>` element plays picture with **no sound**, or the HLS path
copies AC-3 into the stream and hls.js refuses the variant — the same failure R297 found on the
Chromecast, one layer over. Since R297 the backend intersects the transcode's audio with the client's
list, so an honest list is enough to make Jellyfin convert instead.

## Requirements

- **FR-R302-1 — Probe, like the receiver does.** `supportedAudioCodecs()` on wasm asks the browser:
  `MediaSource.isTypeSupported('audio/mp4; codecs="ac-3"')` / `"ec-3"` / `"opus"` / `"flac"`, falling
  back to `HTMLMediaElement.canPlayType` where MSE is absent (Safari's native HLS). AAC and MP3 always.
  Answered once per page load.
- **FR-R302-2 — The direct-play list follows.** The same list reaches `ClientCapabilities.audioCodecs`,
  so a title whose audio the browser cannot decode is transcoded rather than direct-played silent.

## Non-goals

- Video codec probing on the web (h264 only today, R284 FR-R284-6); a separate question.

## Acceptance

1. Unit (wasm): with a stubbed `isTypeSupported` answering false for `ac-3`, the list omits `ac3`.
2. Ravilo web on desktop Chrome: an E-AC-3 episode (the one from R294) plays **with sound**; the
   backend log shows `transcode=true` for it and `direct_play` for an AAC title.
