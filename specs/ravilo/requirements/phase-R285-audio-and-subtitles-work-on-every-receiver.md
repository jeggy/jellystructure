# Phase R285 — Audio and subtitles work on every receiver (Samsung/webOS TV app, Chromecast)

> Receiver half of [[phase-253-every-track-choice-is-a-server-side-choice-on-a-single-audio-stream]].
> Read 253's table first: on the TV app **no subtitle has ever been drawn**, and neither receiver
> offers PGS or a working audio switch.

**Status:** ✓ Built 2026-09-20 (same day as written), not dev-reviewed, **no receiver hardware touched.** `:ravilo-receiver-core`, `:ravilo-screen`, `:ravilo-cast` and the Android sender compile clean; `ReceiverSubtitlesTest` 18/18 (parser + list order + pick rule live in `:shared`, the only place a test can reach them). The parser was additionally run once over *Honeyman*'s real Danish VTT from the live Jellyfin — **741/741 cues, æ/ø/å intact** (not committed: copyrighted text) — and that same request confirmed Jellyfin answers the subtitle URL with `Access-Control-Allow-Origin: *`, so the TV app's cross-origin fetch is allowed. **Found while wiring it, and fixed:** phase 236 defines `set_audio` / `set_subtitle {index|null}` / `set_subtitle_size` / `skip`, `RemoteRoutes` forwards exactly those, and the TV app matched `audio_track` / `subtitle_track` / `sub_size` / `seek_relative` — so **no phone has ever changed a track or a caption size on the TV app**; it now answers to the spec's names (old ones kept as aliases). Chromecast: the `audio`/`subtitle` commands `CastCommand`'s own doc named were handled nowhere; `selectedAudio` was the constant `0` and `selectedSub` "whichever track is flagged default". ⚠ Unverified and only hardware can say: that HTML draws over AVPlay on the RU7440 (R264's standing question), CAF's `getTextTracksManager()` calls, and a self-issued `playerManager.load()` mid-session.

## Requirements

### FR-R285-1 — The receivers' subtitle list includes picture subtitles
`subtitleTracksOf()` (receiver-core) lists text tracks first, **then** the ticket's `encode` tracks —
appended, so every existing text index keeps its meaning for a remote that is mid-session.

### FR-R285-2 — One restream path in receiver-core
`audio_track i` → restream with `ticket.audio[i].index`, keeping the burn-in. `subtitle_track i` →
a text track (or −1) un-burns first if a burn-in is active, then shows it; an encode track restreams
with its index, keeping the audio. The reload resumes at the current position and keeps the
play/pause state. Status reports `selectedAudio` from `ticket.audioStreamIndex` and `selectedSub` as
the burned track's list position while one is burned in (R282's rule, on the wire the remote reads).

### FR-R285-3 — The TV app draws text subtitles itself
`ravilo-screen` fetches the selected track's VTT, parses it, and draws the active cue in a DOM layer
over the video from the playback clock — one renderer for both backends (AVPlay has no URL-based
external-subtitle API; `<video>` could use `<track>`, but one path is one set of bugs). Honour
`sub_size`. A burn-in ticket hides the layer (R282's invariant). Parser is pure and unit-tested:
timestamps with and without hours, multi-line cues, cue settings ignored, `<i>`/`<b>`/ASS override
tags stripped, malformed blocks skipped.

### FR-R285-4 — Chromecast: PGS and audio through the same restream
CAF keeps sideloading text tracks. A PGS or audio pick reloads the media with the restreamed URL at
the current position; a burn-in load activates no text track.

### FR-R285-5 — HEVC over HLS only where the device says yes
Chromecast sets `hlsHevc` from the `canDisplayType('video/mp4', hev1…)` probe it already runs.
`ravilo-screen` leaves it **false**: whether the RU7440's AVPlay takes fMP4-HEVC HLS is a hardware
question, and a wrong yes is a black screen on titles that play today. Named, not guessed.

## Out of scope
- The phone remote's UI: it renders the receiver's track list as-is, so new entries appear unchanged.

## Verification
- Unit: VTT parser; list ordering; the pick-decision helper. JS compile of all three modules.
- ⚠ **No receiver hardware here.** Whether HTML draws over AVPlay on the RU7440 is the same open
  question R264 already carries for the receiver's own chrome — this phase inherits it.
