# Phase R291 — switching audio is instant, on every stream

> Owner, 2026-09-24: *"Yes. We even want this to be instant. So switching should just work right away
> without any issues. Let's also write a spec about this."*

## Status

`⚠ Partial` — **2026-09-26 evening: mechanism 1 rebuilt on jellystructure's own renditions, verified on the Pixel 9**
(598–907 ms per switch, the picked track mapped, the picture never stops; see *Build + device measurement,
2026-09-26 evening*) — the stue TV is the last check before the Android switch goes on. Earlier the same day:
**mechanism 1 cannot use Jellyfin as the rendition source** (its audio-only job
never maps the requested track; see *Device measurement, 2026-09-26 afternoon*). **FR-R291-1 built 2026-09-25** from the dev review below (items 1 and 2); **FR-R291-2/3 not
built**: the mechanism is chosen after FR-R291-3's remaining measurements (the video variant's PTS at the same
segment index — one 4K software encode — and item 4's `PlaySessionId` question), which need a quiet window on
the household's Jellyfin and are not this session's to spend. `Planned` when written 2026-09-24 from the
soveværelse-TV sweep (owner: *"we even want this to be instant"*). **Dev-reviewed 2026-09-24 against `main`
`9d2636bb`.** Amends R284 FR-R284-2/3.

### Build (2026-09-25, FR-R291-1)

- **One resolver, in `:shared` (item 1):** `LanguageCodes.kt` (R247's `canonicalLanguage`/`sameLanguage` and
  the ISO alias table, moved; `:ravilo-ui`'s `LanguageIdentity.kt` delegates and keeps only the display
  names) and `TrackVariants.kt` (R195's `VariantKind`, the SDH/AD/commentary regexes, the region table as
  code + name, the provenance collapse, `groupVersions()` — the (kind, region, ordinal) clustering in stream
  order — the `"<kind>|<region>|<ordinal>"` signature, and `resolveAudioChoice()`: R181's audio tier). The
  picker's `buildLanguageGroups`, `variantKind`, `resolveRegion` and `PickerVersion.signature()` now delegate
  to it, so the signature a pick remembers is the one the next start asks for; the flag stays the UI's.
  `TrackVariantsTest` (4); the 20 existing `PlayerScreenTrackResolutionTest` cases pass unchanged through
  the shared grouping.
- **The request (item 2, landed with R292's `start_position_ms`):** `PlaybackStartRequest.audio_language` +
  `audio_variant`, additive; `PlaybackStartRequestWireTest` (4). `armSession` sends the remembered choice
  (series, else global) with the FIRST negotiation; `PlaybackService.startPlayback` resolves it on
  `buildAudioTracks(itemDetail)` — the very list the ticket carries — and passes the match as the
  negotiation's `AudioStreamIndex`, exactly as a restream does, logging one line (`… audio da/plain||0 →
  stream 2 (R291)`). Open question 2 closes with it.
- FR-R291-4/5 hold for FR-R291-1 by construction: every player sends the same request field, and a burn-in
  is untouched by it.
- **Device trial (stue TV, 2026-09-25, local release build of `852b228b`+ this change, backend build 6):** an
  animated series episode carrying five stereo audio tracks (English default at stream 1, Danish at stream 2).
  Danish picked once in the picker, two Backs out, Resume from the detail page: the backend logged
  `playback start: … audio dan/plain||0 → stream 2 (R291)` followed by exactly **one** `PlaybackInfo` line
  (direct play), and the picker opened on the playing stream showed Danish ticked. Before this change the
  same re-entry was two negotiations. The transcode case (where the second negotiation was a ~12–15 s
  restream) is not yet captured on a device — it needs a file the stue TV cannot direct-play with a
  remembered non-default track.

### Build (2026-09-25, FR-R291-2 — mechanism 1)

Built after the measurements below (item 4 first, as the review asked):

- **Backend — `AudioRenditions`.** For a client that declares the new `hls_audio_renditions` capability
  and a start that transcodes with two or more audio tracks, the ticket's `hls_url` becomes
  `/api/tv/stream/{id}/master.m3u8` on this server (`audio_renditions = true`), and `composeMaster` writes
  what Jellyfin cannot: Jellyfin's own video variant, joined to an `aud` group whose DEFAULT is the audio
  the transcode already carries (muxed, no URI), plus one `EXT-X-MEDIA` per other track pointing at
  `/Audio/{id}/main.m3u8?AudioStreamIndex=N` on **its own `PlaySessionId`** (`{session}a{N}`, same device
  id). Others are `AUTOSELECT=NO`, so no player swaps track by the device's locale; `NAME` is
  `a{position} {label}` — unique (Media3 merges renditions that share a name) and the key the player maps
  back to the ticket. All three ticket paths (start, burn-in restream, un-burn/audio restream) go through
  one `withRenditions`. The route is public like the image proxy (a player cannot put a device token on a
  playlist fetch); its 128-bit id is the capability and lives as long as the ticket; what it serves carries
  only Jellyfin's own credential, exactly as `hls_url` did. `AudioRenditionsTest` (4).
- **Phase 180's teardown stops every rendition job:** one `DELETE /Videos/ActiveEncodings` stops one job
  (measured), so `releaseEncodes` stops the video's session and then each rendition session the service
  handed out — on the direct path and the queued writer's path alike.
- **Android — the pick is a track selection.** `PlayerStore` resolves the server-relative URL against the
  address the app already uses; PlayerScreen no longer calls a rendition stream "single-audio"
  (`sessionAudioIndex` is null when `audio_renditions`), so R284's restream is skipped and the pick goes to
  `selectAudioTrack`, which finds the rendition by its `a{position}` name (Media3 lists the muxed track
  first and the renditions after it, not in the ticket's order).
- **FR-R291-4:** the web (hls.js / Safari) and the two receivers do **not** declare the capability and keep
  R284's restream — neither is built or measured, and this note is the "says so" the FR requires.
- **⚠ Shipped switched off.** Before the device measurement could run, the owner withdrew device access
  for the rest of the day (2026-09-25). Android's `switchesHlsAudioRenditions()` is therefore `false`: the
  backend and the player path are built, tested (`AudioRenditionsTest`, compile, the release-build dex
  guard at 245/250 registers) and inert until a client declares the capability. **What closes FR-R291-2
  and FR-R291-3's item 3:** set it to `true`, and on the soveværelse TV (release build) re-enter a
  transcoded film with several audio tracks and switch audio — measure the time to the new audio and
  whether R218's stall shows. If the first audio-only segment's 2–3 s shows as a stall, warm the
  rendition the way the dev review's item 5 describes (one at a time, after a dwell): the player's
  requests use Media3's default user agent, so a prefetch with that same agent lands in the same
  Jellyfin job.

### Device measurement (2026-09-26, stue TV, switch on)

Local release build `1.39-5-g830052c0-dirty` with `switchesHlsAudioRenditions() = true`, prod backend v1.39,
a 2025 4K Dolby Vision REMUX at 80.7 Mbps with six audio tracks (English TrueHD 7.1 + commentary, and
Spanish/French/Italian/Russian AC3 5.1) — above the set's 54 Mbps (0.9 × 60) ceiling, so it transcodes.

- **The path works end to end.** The backend logged `audio renditions=6 (R291)` on the start; the picker
  listed the five languages (English as two versions), each pick was ticked and the Audio pill took the new
  flag; Jellyfin started one audio-only job per new language, **0.4 s after the pick** (the TV clock runs
  1.517 s behind the server's; corrected).
- **A switch to a rendition that is not yet encoding freezes the picture:** the player's session state goes
  `PLAYING → BUFFERING` at the pick and back with the playhead unchanged — **1.10 s** (Italian), **1.32 s**
  (French), ~2 s (Spanish, the first; upper bound 2.5 s). A screen recording at 4 fps shows the picker
  closing and **R218's stall spinner in the Pause button's place for ~1.25 s** (French). So FR-R291-2 —
  *within about a second, no loader* — is **not met yet**: the freeze is the first audio-only segment's
  encode, as predicted.
- **Back to the audio the video already carries (muxed English) is seamless:** no BUFFERING at all.
- **Teardown holds:** Back stopped the video job and the live Italian rendition job (00:26:18 and 00:26:20,
  zero Jellyfin ffmpeg left); the French job had already been ended by Jellyfin's own idle timeout, ~1 min
  after it was switched away from.
- **Found: an AC3 carried track breaks every switch.** The second start carried the remembered Italian AC3
  track; Jellyfin **copied** it into the video (`-codec:a:0 copy`, the variant's CODECS `ac-3`), while the
  renditions were asked for as AAC. An `EXT-X-MEDIA` tag has no CODECS of its own, so Media3 gives every
  rendition the variant's audio format: the first switch failed with *"Unable to bind a sample queue to
  TrackGroup with MIME type audio/ac3"*. The first session had only worked because its carried track was
  TrueHD, which Jellyfin re-encodes to AAC. **Fix:** `composeMaster` asks for each rendition in the codec
  the variant's audio is in (`renditionAudioCodec`: `mp4a` → aac, `ac-3` → ac3, `ec-3` → eac3, MP3 → mp3,
  anything else aac); `AudioRenditionsTest` (5). Not yet seen on the TV at the time of writing.
- **Seen on the way, not R291's:** after that source error the player sat on a black screen with a spinner
  and no sentence (state `ERROR`, no retry) — a mid-playback failure has no R237 treatment; and *Play Again*
  on a watched film started at the previous stop point (`-ss 00:08:57`), not at 0.
- **The warm, built (dev review item 5):** the picker warms the audio row its focus has rested on for
  500 ms — only a row whose OK switches outright (a single-version language, or a version at level 2),
  never the playing track or a subtitle (`audioWarmTarget`, `AudioWarmTargetTest` (3)). The Android player
  fetches that rendition's segment at the playhead through `DefaultHttpDataSource` — the class and user
  agent Media3 loads the stream with, so the request lands in the job the switch reads from — one at a
  time, a stale warm dropped, a rendition not re-warmed within 20 s. Web: no-op.
  **Measured, on a film whose carried track Jellyfin re-encodes to AAC:** the warm had its segment ready in
  1.8–2.2 s; the switch after it froze for **510 ms** and **210 ms** (cold: 1.10–2 s). In a 6 fps screen
  recording of the second, **no stall spinner** — the Pause glyph shows ▶ for ~0.3–0.5 s while the new
  audio buffers (the button follows *is playing*, not *wants to play*), and the picture keeps moving.
  Switching back to the muxed track: no BUFFERING at all.
- **The AC3 fix, seen on the TV (2026-09-26 11:37, prod on a local build of `28a1f082`):** the same film,
  started on the remembered French AC3 (copied, CODECS `ac-3`); the warm started the Spanish rendition as
  `-acodec ac3` (segment ready in 1.1 s) and the switch froze for **522 ms** and played on — where the same
  switch had failed with the source error before.
- **⚠ Then a stall that never ends — the reason the switch stays off.** Next, English (TrueHD, a rendition
  encoded to AC3 6ch): warmed in 655 ms, picked, and the player went `BUFFERING` and **never came back** —
  no error, the frozen frame behind R218's spinner for minutes. Jellyfin's log shows the player **stopped
  requesting anything** at the pick: its idle timer killed the video job 61 s later, then both renditions.
  The player's buffered position after the pick equalled the playhead, i.e. **the video buffer was thrown
  away too** and had to be re-fetched from the playhead — and by then Jellyfin, with *Delete segments* on,
  held only the video job's newest segments (280–287 while the playhead was at 216). A request for a
  segment the job counts as done but whose file is gone waits; nothing else is asked for. Not proven — a
  release build logs nothing from Media3; the Pixel 9's debug build is where to see which request hangs.
  The earlier switches that worked may simply have landed while the playhead's segment still existed.
  **Until that is understood the switch stays `false`**: the committed build keeps R284's restream, and the
  stue TV was put back on a build with it off the same morning.

### Device measurement (2026-09-26 afternoon, Pixel 9 debug build) — the renditions were never the picked track

A debuggable build now logs every load the player makes (`DebugLoadLogger`, tag `R291`; bound only when the
app is debuggable). With the switch on, the same film on the Pixel 9 against prod `v1.39-18-gb41adec5`: the
start transcoded (4K HEVC → H.264, 3.003 s segments, English TrueHD carried as AAC), `audio renditions=6`.

- **English → Spanish:** BUFFERING 1.59 s (no warm on a touch pick), then READY; Spanish segments ~265 KB each;
  the video buffer was **kept** (video segment 94 followed 93, no reload). So the "video buffer thrown away,
  deleted segment re-requested" theory for the stue-TV stall is **not** what happens on a rendition switch.
- **Spanish → French: the stall, reproduced.** The French segments came back **0 B, 2 444 B, 2 820 B …** —
  every segment from 91 to 108 in about a second, none with a single audio sample — and the player sat in
  BUFFERING with its buffered position frozen at the playhead until Back.
- **The cause is Jellyfin's audio-only job, and it is worse than the stall.** Jellyfin's ffmpeg log for every
  rendition job — the Spanish and French ones here, and both of the stue TV's this morning — reads:

  ```
  Stream mapping:
    Stream #0:1 -> #0:0 (truehd (native) -> aac (libfdk_aac))
    Stream #0:7 -> #0:1 (subrip (srt) -> webvtt (native))
  ```

  Stream #0:1 is the **English TrueHD** track, whatever `AudioStreamIndex` asked for; #0:7 is a subtitle
  stream nobody asked for (the job writes `.vtt` segments beside the audio). The command has no `-map` at all,
  and Jellyfin's source says why: `var mapArgs = state.IsOutputVideo ? _encodingHelper.GetMapArgs(state) :
  string.Empty;` (`DynamicHlsController`) — an audio-only request is never mapped, so ffmpeg takes its own
  default (the audio stream with the most channels, and the first subtitle). **No request parameter changes
  that.** Every "switch" measured on 2026-09-25/26 therefore played the same English audio; the timings
  were real, the language never changed. (2026-09-24's cross-correlation *"content yes"* could not tell:
  dubs share the music-and-effects bed.) The empty French segments fit the stray subtitle stream: with
  `-copyts` and a sparse subtitle track in the HLS muxer, the job cut ~190 empty segments in 15 s.
- **So mechanism 1, with Jellyfin as the rendition source, cannot work.** The switch stays `false`. What would
  work is the spec's own fallback — **jellystructure serves each rendition itself** (its own ffmpeg with
  `-map 0:<index>`, the video's +10.000 s offset, `-sn`), or mechanism 3 (swap behind the picture).

### Build + device measurement (2026-09-26 evening) — the renditions are this server's own

Owner: *"Just pick the best option. I want it to be fast and even with good on not so fast tvs."* Mechanism 1
stays — only the rendition **source** changes. Each rendition is `audio/{position}/main.m3u8` next to the
composed master: a VOD playlist of the whole file in 3.000 s segments, each segment made on demand by
`AudioRenditionJobs` from the file on jellystructure's own disk — `-map 0:<track> -sn`, Jellyfin's timing
arguments (`-copyts -avoid_negative_ts disabled -max_delay 5000000`, 3 s HLS), `-hls_flags temp_file`, in the
codec of the video variant's audio. One ffmpeg per (stream, track): paused 2 min ahead of the last request,
resumed as requests close in, replaced by a request it cannot reach soon (a seek), killed idle (90 s) or with
its playback (phase 180), its segments deleted as the player passes them. For a slow TV this is the lightest
path there is: the player fetches one ~265 KB audio segment per 3 s and keeps its video buffer.

Measured in the production container first: first segment **0.6 s** (AC3 source) / **0.8 s** (TrueHD 7.1), 5–7×
real time; segment 100 starts at **309.979 s** against the video variant's 310.000 s — a **21 ms** lead (Jellyfin's
own audio jobs led by 43 ms). Then on the Pixel 9 (debug build, switch on; prod backend on this branch's image,
owner-approved), the same film, starting on the remembered French (AC3, carried and copied, so the renditions
are AC3):

| Switch | Pick → READY | Server's ffmpeg | Segments |
|---|---|---|---|
| French (carried) → Spanish | **598 ms** (cold, no warm on a touch pick) | `-map 0:2` | 265 644 B |
| Spanish → English — the stue TV's stall case | **907 ms** (TrueHD source) | `-map 0:1` | 265 644 B |
| English → Italian | **840 ms** | `-map 0:4` | 265 644 B |
| Italian → French (carried) | **no BUFFERING at all** | — | — |

No video load was cancelled or repeated across the four switches: the picture never stopped. Back stopped all
three rendition jobs (`stopped (playback stopped)`), removed their folders, and left no ffmpeg in the container.
Still to see: the stue TV (D-pad picker with the warm) before the switch goes on for a release — the owner's
rule since 2026-09-25 is that an audio path is seen on a set before it reaches the household.

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

**Decided 2026-09-24 — the first.** Owner: *"just take the best decision. The only important thing is
the user using Ravilo should get the very best experience. It should feel performant and nice and not
laggy."* That criterion also decides FR-R291-2's mechanism when the measurements are in: whichever
switch is fastest *as felt* on the TV, not whichever is cheapest to build.

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

**Measured 2026-09-25** (household Jellyfin 12.1, nobody watching; a 4K HEVC film at 84 Mbps with five DTS
tracks, under a 60 Mbps h264/ts profile — `VideoCodecNotSupported, AudioCodecNotSupported,
ContainerBitrateExceedsLimit`; one 4K software encode, stopped by `DELETE /Videos/ActiveEncodings` right
after, nothing left transcoding):

| Question | Answer |
|---|---|
| **Item 4 — an audio-only job under the video's `PlaySessionId`?** | **It does not kill the video job — it returns the video job's own segment.** With the same `PlaySessionId` and `DeviceId`, `/Audio/{id}/main.m3u8?AudioStreamIndex=5` segment 100 came back in 0.0 s, byte-for-byte the size of the video's segment 100: Jellyfin keys a job's output on media path · user agent · device · play session, so the two requests share one directory. The video job carried on (segment 101 in 0.9 s). **Every audio rendition needs its own `PlaySessionId`**, which makes it a job of its own — and phase 180's teardown must stop each one by its own id. |
| **…and with the same `PlaySessionId` but its own `DeviceId`?** | A real audio-only job (a separate output folder): segment 100 in 2.1–2.8 s, AAC only, first PTS 309.957 s — the same +9.957 s offset as 2026-09-24. **But one `DELETE /Videos/ActiveEncodings` for the shared play session stops one job, not both:** counted as `ffmpeg` processes on the host, a single call with the video's device id took the *audio* job and left the video's running; the remaining calls took the rest. So teardown cannot lean on a shared id: each rendition gets **its own `PlaySessionId`** (and the device's own `DeviceId`), the server remembers which it handed out, and phase 180 stops each by its own id. |
| The video variant's first PTS at segment 100 | **310.300 s** for media time 300.300 s (3.003 s segments): an offset of **+10.000 s**. The audio-only offset measured on 2026-09-24 was +9.957 s at segment 100 (3.000 s segments). The two differ by **43 ms** — two AAC frames at 48 kHz — so a separate audio rendition would lead the picture by ~43 ms: constant, inside common lip-sync tolerance, and correctable by one fixed offset if it shows. |

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
   **Closed — dev review item 5:** under mechanism 1 the cost is per *switch* (one `-vn` job), not per
   track; an unfetched rendition is a playlist line. What must be bounded is warming.
2. Does R181's variant signature resolve identically against the detail payload's track list and the
   ticket's (FR-R291-1)? If not, the start and the picker could disagree about which English is which.
   **Closed — dev review item 1:** with the lean, the server resolves on `buildAudioTracks(itemDetail)`,
   which is the very list the ticket — and so the picker — carries. There is no second list.

## Verification
- Unit: the start request carries the resolver's pick; the pure resolver on the detail payload's list.
- Device, soveværelse TV, release build: re-enter *Dreadful Me 4* with a non-default remembered audio →
  exactly one negotiation in the backend log. Switch audio mid-playback → frame capture shows no loader
  and no black; audio changes within ~1 s.

## Dev review (2026-09-24, against `main` `9d2636bb`)

The premise holds end to end. `PlaybackStartRequest` is `{item_id, capabilities}` (`Models.kt:1140-1142`);
the start path calls `getPlaybackInfo` with no `audioStreamIndex` (`PlaybackService.kt:458`) while both
restream paths pass one (`:907`, `:995`), and `carriedAudioIndex` (`:1077`) only *reports* the index
Jellyfin chose (`:536`). The remembered choice is `RememberedChoice{audioLanguage, audioVariant =
"<kind>|<region>|<ordinal>"}` (`PlaybackPrefsStore.kt:8-21`; the signature at `PlayerScreen.kt:3845`),
client-only per R181. Six items.

1. **FR-R291-1's lean is right, and open question 2 closes with it.** `resolveTrackChoice`
   (`PlayerScreen.kt:3931`) is pure: `RememberedChoice` + the two track lists → `buildLanguageGroups`
   (`:4019`) → `PickerVersion.signature()`. Its inputs are exactly the fields the ticket's `AudioTrack`
   carries — `index, language, label = DisplayTitle, codec, channels, isDefault` — which `buildAudioTracks`
   (`:1044-1062`) derives from Jellyfin's `MediaStreams`. A server-side resolve therefore runs on the
   **same list the picker will show**, because the picker's list *is* `buildAudioTracks(itemDetail)`; the
   start and the picker cannot name a different English. What moves to `:shared`: the resolver, the
   grouping, `PickerVersion`/`VariantKind`/`RegionInfo`, R195's SDH and region tables, the signature, and
   R241's granularity-tolerant language match (`:3946`) — none of it touches Compose. The request carries
   `{audio_language, audio_variant}`; the service resolves an index and passes it exactly as `:995` does.
   The subtitle half needs nothing: text tracks ride the ticket, and the resolver never auto-starts a
   burn-in (`PlayerScreen.kt:764`).
2. **Land it with R292's field, in one change.** R292's dev review (item 2) adds `start_position_ms` to
   the same request for the same reason — a start that does not know what the viewer wants restreams to
   find out. Two optional fields, additive (an installed client never loses one), one change of
   expectations. With both in, R290 FR-R290-4's "at most once" is a fence rather than a path.
3. **Mechanism 1 assumes an HLS jellystructure serves; today it does not.** `hlsUrl` is Jellyfin's own
   `TranscodingUrl` with the token appended (`streamUrlFor`, `:1036-1040`; `withJellyfinToken`, `:917`) —
   the player fetches playlists and segments **from Jellyfin directly**. A master playlist "jellystructure
   writes" is a new device-token route (say `GET /api/tv/stream/{session}/master.m3u8`) whose entries are
   absolute Jellyfin URLs carrying the token; the bytes still flow from Jellyfin. Two consequences the
   spec must own: the web app is served from a different origin than the backend, so hls.js
   needs CORS on that route — the question phase 247 already holds for the receiver; and a served playlist
   is one more place the Jellyfin token appears, no worse than `hls_url` today.
4. **Add a fourth measurement, before the three: does an audio-only job share the video job's
   `PlaySessionId`, or kill it?** Jellyfin starts a segment job by first killing this device's other jobs
   for the same play session; an audio rendition under the video's `PlaySessionId` may therefore end the
   video encode, and one under its own becomes a second Jellyfin session — which Phase 180's teardown
   (`DELETE /Videos/ActiveEncodings`, keyed by play session) and the tracker (`playbackTracker.started`,
   one key per item) know nothing about. Whichever way it falls, 180 must stop every rendition's job on
   Back, and the tracker must count one playing device, not two. This is cheaper to measure than the PTS
   question and decides more.
5. **Warming must be bounded.** "Fetch that rendition's segment when the picker's focus rests on a
   language" makes a D-pad sweep through five languages five `-vn` ffmpeg jobs, each alive until
   Jellyfin's idle timeout. One warm at a time, after a dwell, the previous one abandoned — and open
   question 1 then answers itself: the cost is per *switch*, not per track.
6. **Mechanism 3 is the least likely to work on the TV that motivated the phase.** A second player is a
   second hardware decoder and a second buffer set on a device R292 measured at 236 MB PSS for *one*
   Dolby Vision session, with a 192 MB heap ceiling and codec reclaim already observed — and it doubles
   the server encode for the overlap. Keep it third, and rule it out on the stue TV specifically rather
   than in general.

**Small correction.** FR-R291-3's second list ("Before any code, measured…") repeats the table's questions
after the table answered them; fold what is left into the "still to measure" list, with item 4 first.

**Net effect.** FR-R291-1 is one shared resolver, two request fields (with R292) and one
`getPlaybackInfo` argument — buildable now. FR-R291-2 stays gated on four measurements, the new one first.
