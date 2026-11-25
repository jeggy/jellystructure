# Phase R285 — Audio and subtitles work on every receiver (Samsung/webOS TV app, Chromecast)

> Receiver half of [[phase-253-every-track-choice-is-a-server-side-choice-on-a-single-audio-stream]].
> Read 253's table first: on the TV app **no subtitle has ever been drawn**, and neither receiver
> offers PGS or a working audio switch.

## Scope

Touches `:ravilo-receiver-core`, `:ravilo-screen`, `:ravilo-cast` and the Android sender; the
parser, list order and pick rule live in `:shared` (`ReceiverSubtitlesTest`), the only place a test
can reach them. Jellyfin answers the subtitle URL with `Access-Control-Allow-Origin: *`, so the TV
app's cross-origin fetch is allowed. **Found while wiring it, and fixed:** phase 236 defines
`set_audio` / `set_subtitle {index|null}` / `set_subtitle_size` / `skip`, `RemoteRoutes` forwards
exactly those, and the TV app matched `audio_track` / `subtitle_track` / `sub_size` /
`seek_relative` — so no phone had ever changed a track or a caption size on the TV app; it answers
to the spec's names, the old ones kept as aliases. On Chromecast the `audio`/`subtitle` commands
`CastCommand`'s own doc named were handled nowhere; `selectedAudio` was the constant `0` and
`selectedSub` "whichever track is flagged default".

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

**Amended 2026-11-19 (DEB-4994) — the Cast receiver draws every text subtitle itself.** The first
sentence above is superseded. A viewer cast a series episode from the phone to a TV's built-in Cast
on 2026-11-17. Both of its embedded text tracks had been extracted weeks before, so the server
would have answered with the file at once. Still no subtitle showed, and none of several picks from
the phone's remote changed anything. The cause in the code: a text-to-text pick on the phone's Cast
link is CAF's own `EDIT_TRACKS_INFO` (`CastSenderAndroid.selectSubtitle`, the Mac's the same), which
the receiver never sees. So the receiver's own fetch-and-draw (DEB-4792) runs only for the track
shown at load, and for a pick sent as our `subtitle` command. Every other pick depends on CAF
drawing a sideloaded VTT. That has been seen to work only on a Google TV Streamer with a
pre-extracted track (DEB-4704), and seen to fail from a `blob:` copy on a real Chromecast (DEB-4792).
Drawing on Cast now has one path, the one FR-R285-3 gives the TV app:

- **a.** The receiver draws the active text track in its own `#subtitle` layer, from the player's
  clock, at the viewer's `sub_size`. It fetches the file itself with its device token (DEB-2205 FR-6:
  a 202 is retried in the background, and the picture never waits).
- **b.** "The active track" is whatever CAF's text-track manager says is active
  (`getActiveIds()`), read on the drawing tick. A pick reaches the layer by whichever path it took:
  our `subtitle` command, the phone's or the Mac's `EDIT_TRACKS_INFO`, an older sender, or the
  platform's own caption control. No sender has to change, and an installed phone keeps working
  unchanged.
- **c.** CAF still gets one `TEXT` Track per text subtitle, with the same ids (`100+i`), names and
  languages, so the senders' lists, the burn-in rule (a listed subtitle with no CAF Track) and the
  platform caption list are unchanged. Every Track points at an empty VTT, so CAF never draws a
  second copy.
- **d.** Off, a burn-in (R282's invariant) and a new item clear the layer. A restream of the same item
  keeps the files already fetched.

No wire change: no message, field or id is added, removed or retyped.

**Amended 2026-11-25 (DEB-4994, DEB-5621) — the receiver remembers the pick itself.** The 2026-11-19 build
(`90c1b1b2a`, DEB-5016) is on `main` and shipped in v1.71, but nothing has shown it working: the device check above
is parked with the house devices, and `tests/e2e/cast-receiver.spec.ts` never makes a pick (its fake CAF answers
`getActiveIds()` with `[]`). Point **b** also leaves one dependency in place. The layer draws only the track CAF's
text-track manager *reports* active, and every one of those Tracks points at an empty `blob:` VTT. If CAF drops a
track whose file it could not use, `getActiveIds()` comes back empty and nothing is drawn: not the load's default,
not a phone's pick, not even our own `subtitle` command, which also goes through `setActiveByIds`. A `blob:` subtitle
has already been seen failing on a real Chromecast (DEB-4792). Point **b** is replaced:

- **b′.** The receiver keeps the active text position itself, and the drawing tick reads that:
  - a **LOAD** sets it from the ticket (its shown text track, or none);
  - our **`subtitle` command** sets it as `receiverSubPick` decides (a text pick, Off, or none for a burn-in);
  - a new **`EDIT_TRACKS_INFO` interceptor** sets it from the request's `activeTrackIds`: the first id from 100 to
    100 + the text count − 1 is that position, no such id is Off. The request is then passed on to CAF unchanged, so
    the senders' status and the platform caption list stay as they are. A request with no `activeTrackIds` (a style or
    language-only edit) leaves the position alone;
  - **CAF's own report is the fallback for the platform caption control**: when `getActiveIds()` answers a text id that
    differs from the last one the receiver read from it, that becomes the position. A report that stays empty never
    clears a position the receiver set.
- Points **a**, **c** and **d** are unchanged. Off, a burn-in and a new item still clear the layer. A pick that needs
  burning in still restreams.
- No sender changes. An installed phone or Mac keeps sending what it sends today.

**Proof (CI).** `tests/e2e/cast-receiver.spec.ts` with the real bundle. `helpers/cast-receiver.ts`'s fake CAF gets a
settable clock (`getCurrentTimeSec`) and keeps `getActiveIds()` at `[]` throughout, which is the failure under test:
- a LOAD whose ticket shows text track 0 draws its fixture cue in `#subtitle` at the cue's time, fetched with
  `Authorization: Bearer`;
- an `EDIT_TRACKS_INFO` with `activeTrackIds: [101]` switches the layer to track 1's cue; one with `[]` clears it; `[100]`
  shows track 0 again; the interceptor returns the request to CAF;
- our `subtitle` command with a text index does the same;
- `getActiveIds()` turning to `[101]` on its own (the platform's control) switches the layer;
- exactly one `#subtitle` cue element is drawn at a time.
`:shared:desktopTest` covers the position rules if they move into `TicketSubtitles`. The device check above stays, and
goes on the build's QA ticket, parked on DEB-4608.

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
- DEB-4994 (FR-R285-4 amendment), on a device. Cast an episode with two text tracks from the
  phone's app, not from a script, because the phone's own `EDIT_TRACKS_INFO` is the path under test.
  Run it on the TV's built-in Cast where the report came from, and on a Chromecast or Streamer:
  - The default track shows from the start.
  - A pick of the other track from the phone's remote shows that track within a few seconds.
  - Off clears the layer, and re-picking the first track shows it again.
  - A cold (never extracted) track still shows after its 202.
  - Exactly one copy of each cue is drawn.
