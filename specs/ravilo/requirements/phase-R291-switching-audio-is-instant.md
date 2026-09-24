# Phase R291 — switching audio is instant, on every stream

> Owner, 2026-09-24: *"Yes. We even want this to be instant. So switching should just work right away
> without any issues. Let's also write a spec about this."*

## Status

`Planned` — written 2026-09-24 from the soveværelse-TV sweep. Not dev-reviewed, not built. Amends
**R284** FR-R284-2/3 and **253** (the restream carries the audio). Pairs with **R290**.

## What happens today

On **direct play** switching audio is already instant: every track is in the file and ExoPlayer selects
one (verified 2026-09-24, *Creatures, Ltd.*, TrueHD ↔ AC-3, no reload).

On a **transcode** it is not. Jellyfin's HLS transcode carries exactly one audio track
(`-map 0:0 -map 0:<audio>`), so 253/R284 change audio by asking for a new stream. Measured on the
soveværelse TV, *Dreadful Me 4* (86 Mbps HEVC, over the TV's 60 Mbps ceiling):

| Case | What the viewer sees |
|---|---|
| Pick another English track in the picker | R218's cold-start screen for **~12–15 s**, then playback resumes at the same position with the new audio. |
| Re-enter the title with a remembered non-default audio | The first stream starts, the resolver finds the wrong audio, and R284 FR-R284-3 restreams: a **second full start, ~12 s**, every time (see R290 for how it looks). |

Two costs stack. The restream is a brand-new Jellyfin transcode (new `PlaySessionId`, `-ss` to the
position, fresh GOP), and prod Jellyfin currently encodes **in software** (`HardwareAccelerationType =
none` since the 12.1 upgrade — see phase 246), so each start is a 4K `libx264` spin-up. And the first
negotiation cannot ask for the right audio at all: `PlaybackStartRequest` is `{item_id, capabilities}`
(`Models.kt:1140`) while the remembered choice lives client-side (`PlaybackPrefsStore`, R181).

## Requirements

### FR-R291-1 — The first negotiation asks for the audio the viewer will get
`PlaybackStartRequest` gains an optional audio preference, and the client sends the one R181's resolver
would pick (series choice → global choice → source default), so the **first** ticket already carries
it. The remembered choice stays client-local (R181's rule) — the request carries the *answer*, not the
store. With this, R284 FR-R284-3's automatic restream only runs when the server could not honour the
request, which should be never.

The client cannot resolve that today: the detail payload carries only `audio_languages` (a list of
codes — `Models.kt:299/318/582/621`), not the per-track title, channels and default flag R195's variant
signature matches on. Two ways, for dev review to pick:
- **send the preference, let the server resolve** — the request carries the remembered
  `{language, variant signature}` (series choice, else global choice) and the backend picks the
  matching `MediaStream` index with the same pure resolver, moved to `:shared`; or
- **ship the track list earlier** — the detail payload (and every other path that starts playback:
  Continue Watching, next-episode auto-advance) carries the file's audio tracks, and the client resolves.

Lean: the first. It is one request field, every start path gets it for free, and the resolver already
has no platform dependency.

### FR-R291-2 — A picked audio track plays within about a second, with no loader
Picking another audio track on any stream keeps the picture moving and changes the sound within ~1 s.
No cold-start screen, no black frame, no position jump, no chrome flash. This is the requirement; the
mechanism is chosen after FR-R291-3's measurement, from these, in order of preference:

1. **Every audio track as a rendition, composed by jellystructure.** Jellyfin cannot emit them itself
   (measured, FR-R291-3), but it can serve each audio track alone. The ticket's HLS URL becomes a
   master playlist *jellystructure* writes: Jellyfin's video variant plus one `EXT-X-MEDIA TYPE=AUDIO`
   per track, each pointing at `/Audio/{id}/main.m3u8?AudioStreamIndex=N`. Only the selected rendition
   is fetched, so an unused track costs nothing. A switch costs one audio-only segment (1.7–5.2 s cold),
   and the player can **warm** it: when the picker opens (or its focus rests on a language), fetch that
   rendition's segment at the current position so it is ready before OK is pressed. Blockers: the
   three open measurements in FR-R291-3.
2. **Copy the video, not re-encode it.** Where the transcode exists only because of audio or a
   container issue (not the video's bitrate or codec), R284 FR-R284-6's HEVC-over-HLS and video copy
   make a restream a remux, which starts in well under a second. It rarely applies in this household:
   the measured transcode's reasons were `VideoCodecNotSupported, AudioCodecNotSupported,
   ContainerBitrateExceedsLimit`, i.e. an 86 Mbps file against a 60 Mbps TV. The video has to be
   re-encoded whatever the audio.
3. **Swap behind the picture.** Keep the current stream playing while a second player prepares the new
   one at the same position; when its first frame is ready, cut over on a frame boundary. The viewer
   hears the old audio for the seconds the new stream needs, then the new one — no loader, no gap.

### FR-R291-3 — Measure before choosing
**Measured 2026-09-24** (household Jellyfin 12.1.0, the 86 Mbps HEVC film from the Status section, all
read-only or stopped with `DELETE /Videos/ActiveEncodings` within seconds; no 4K video encode was started
for this, because two household Chromecast transcodes were running at the time):

| Question | Answer |
|---|---|
| Does Jellyfin put several audio tracks in one HLS transcode? | **No.** Its `master.m3u8` has one `EXT-X-STREAM-INF` and no `EXT-X-MEDIA TYPE=AUDIO`; `AudioStreamIndex` picks the one track. Fetching `master.m3u8`/`main.m3u8` starts no ffmpeg. |
| Can Jellyfin serve ONE audio track of a video file on its own? | **Yes.** `GET /Audio/{videoId}/main.m3u8?MediaSourceId=…&AudioStreamIndex=N&AudioCodec=aac&SegmentContainer=ts` → 200, a VOD audio-only playlist with 3.000 s segments (`/Audio/{id}/master.m3u8` → 500; use `main`). ffmpeg: `-ss <pos> -vn -acodec libfdk_aac -ac 6 -copyts -avoid_negative_ts disabled -f hls -hls_time 3`. |
| What does the first audio segment cost after a jump? | **1.7–1.8 s** to first byte at 5:00 and 12:30, **5.2 s** at 20:00; the following segment 2 ms. |
| Is the audio right? | **Content yes, timestamps shifted.** Segment 100 is exactly media time 300 s (envelope cross-correlation 1.00 at 299.98 s against the source), but its first PTS is **309.957 s**: a constant **+9.957 s** at every position probed (5:00, 12:30, 20:00). The source's own `start_time` is 0. |
| Do the video variant's segments line up? | Video segments are 3.003 s (72 frames at 23.976 fps); its transcode uses the same `-copyts -avoid_negative_ts disabled` and muxer. **Its PTS offset is not measured yet.** It needs one video segment, i.e. one 4K software encode. |
| Hardware encoding on vs off | **Not measured.** Production has no accelerator selected (phase 246's `hwaccel_none`, now on the Dashboard per phase 257). |

Still to measure, in a quiet window and before any code:
1. The video variant's first PTS at the same segment index. If it is also +9.957 s, Media3's shared
   per-discontinuity `TimestampAdjuster` keeps sound and picture in step. If not, the audio renditions
   are off by the difference, and jellystructure must re-time them (or serve them itself).
2. Whether the video variant can be requested **without** audio, or whether Media3 ignores the muxed
   audio once the variant names an `AUDIO` group.
3. What Media3 does on the TV and phone when the selected audio rendition changes: a seamless switch,
   or a rebuffer lasting as long as the first segment.

Before any code, measured against the household's Jellyfin (12.1) and written into this spec:
- whether Jellyfin will emit multiple audio renditions in one HLS transcode, and by which request
  parameters (and whether its `master.m3u8` then lists them);
- the time to first segment of a restream with hardware encoding on (after phase 246's fix is applied)
  vs off, for a 4K HEVC HDR source;
- whether Media3 on the soveværelse and stue TVs switches between HLS audio renditions without a
  rebuffer.

### FR-R291-4 — The same on every player
Android TV, phone, web (R284 FR-R284-4/5) and the receivers (R285) follow the chosen mechanism. Where a
platform cannot (e.g. Samsung AVPlay and alternate audio renditions — unknown), it falls back to
today's restream and says so in its own build note — never silently.

### FR-R291-5 — A burned-in subtitle survives an audio switch
As today (R284 FR-R284-2): switching audio keeps the burned-in subtitle, whichever mechanism is chosen.

## Invariants
- R180 FR-RV-ASP1-2: nothing tells the viewer a switch was local or a restream.
- The picker always names the audio that is playing (R282/R284 invariant).
- No new strings.

## Out of scope
- Switching **subtitles** instantly on a burn-in (a PGS burn-in re-encodes the video by definition).
  Text subtitles are already instant everywhere.
- Turning hardware encoding back on — that is phase 246's finding, applied separately; this phase only
  measures its effect.

## Open questions
1. How many audio renditions can a transcode carry before the encode cost hurts other viewers (the
   session ceiling, 218/phase 182's gates)?
2. Does R181's variant signature resolve identically against the detail payload's track list and the
   ticket's (FR-R291-1)? If not, the start and the picker could disagree about which English is which.

## Verification
- Unit: the start request carries the resolver's pick; the pure resolver on the detail payload's list.
- Device, soveværelse TV, release build: re-enter *Dreadful Me 4* with a non-default remembered audio →
  exactly one negotiation in the backend log. Switch audio mid-playback → frame capture shows no loader
  and no black; audio changes within ~1 s.
