# Phase R285 — Audio and subtitles work on every receiver (Samsung/webOS TV app, Chromecast)

> Receiver half of [[phase-253-every-track-choice-is-a-server-side-choice-on-a-single-audio-stream]].
> Read 253's table first: on the TV app **no subtitle has ever been drawn**, and neither receiver
> offers PGS or a working audio switch.

**Status:** Planned (written 2026-09-20, not dev-reviewed).

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
