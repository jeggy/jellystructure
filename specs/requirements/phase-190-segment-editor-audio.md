# Phase 190 — the segment editor has no sound

> Live report: *"There's no audio, when playing the media file within my browser."*

## Status
✓ Built 2026-09-06. Not dev-reviewed. Diagnosed from the stream URL the editor mints plus a codec
census of the live production library; the browser side was still not instrumented — see Open
questions. FR-190-4's probe was run live against this house's Jellyfin 10.11.11 before any code was
written, per its own requirement — see the route's doc comment in Source references for the full
findings, summarized here:

- `VideoCodec=copy&AudioCodec=aac` on the plain progressive `/Videos/{id}/stream.mp4` endpoint needs no
  `PlaybackInfo` negotiation — a bare GET, same shape as the existing `Static=true` URL — but responds
  `Accept-Ranges: none`. A live transcode has no known total size and is not byte-range seekable; this
  is standard Jellyfin/Emby behaviour, confirmed against a real title (40 Weeks Gone, DTS/MKV).
- The same request against `/Videos/{id}/master.m3u8` returns a genuine `#EXT-X-PLAYLIST-TYPE:VOD`
  playlist (1001 segments, ~6s each) — seekable by design. But native `<video src>` HLS playback only
  works in Safari; Chromium (the browser this bug was reported against) has no built-in HLS support,
  and this admin frontend has no HLS.js dependency. Adopting HLS was ruled out for this phase — it would
  add a new client-side JS dependency for a single admin tool, disproportionate to the fix.
- `StartTimeTicks` IS honoured on the progressive endpoint (a request 20 minutes in returned in ~2s vs
  ~6.5s from zero — ffmpeg seeking with `-ss`, not decoding from the start). This is exactly how
  Jellyfin's own web client seeks a live transcode: reload `<video src>` with a new `StartTimeTicks`
  rather than setting `currentTime` in place on a persistent resource.

This reshaped FR-190-5 from "does seeking still work" (yes, but not via HTTP range) into "seeking means
a reload, not an in-place `currentTime` set" — implemented client-side as `seekToAbsoluteMs`.

**Implementation notes:**
- FR-190-1/190-2: `SegmentRoutes.kt` gained `isBrowserSafeDirectPlay` (every audio track in
  `{aac, mp3, opus, flac, vorbis}` AND the container in `{mp4, m4v, webm}`) and `containerOf` (the file
  extension — `Track` has no container field). `GET /{itemId}/stream` now decides `direct` vs `remux`
  from the unit's own stored `Track` list before minting a URL; the remux branch requests
  `VideoCodec=copy&AudioCodec=aac&AudioChannels=2` plus a deterministic `PlaySessionId`
  (`segmentsPlaySessionId` — stable per item/episode, not random, so a page reload harmlessly reuses it).
  The response gained `mode` and `playSessionId` fields.
- FR-190-3: `Segments.kt`'s `wireVideo` sets the badge from `trimStreamMode` (server-supplied), not a
  hard-coded string — *"audio re-encoded so your browser can play it · video untouched"* for remux,
  unchanged text for direct play and the two existing fallback cases.
- FR-190-4: see above.
- FR-190-5: new `seekToAbsoluteMs` is the one seek entry point (track-click, ±10s, "play the cut" all
  route through it now). Direct play seeks in place, unchanged. Remux mode reloads `video.src` with
  `startMs`, tracking `trimRemuxBaseMs` (the offset the current load started from) so `timeupdate` and
  `playCut`'s auto-pause can report the TRUE absolute position (`currentVideoAbsoluteMs`) rather than
  the reload-relative one `video.currentTime` alone would give. `correctDuration` is guarded to only
  trust a load where `trimRemuxBaseMs == 0` — a reload seeked mid-file reports only its own remaining
  duration, not the file's real length, and would otherwise corrupt the timeline on every seek.
- FR-190-6: the remux URL carries the existing `SEGMENTS_STREAM_DEVICE_ID`. New
  `POST /{itemId}/stream/stop` calls `JellyfinClient.stopActiveEncoding` (the exact mechanism Phase 180
  already built and proved safe to call unconditionally). Teardown fires from two places: `renderTrim`'s
  `isNewTitle` branch (opening a different title/episode within `/segments`) and a new
  `wireStreamTeardownOnce` `hashchange` listener (leaving `/segments` entirely) — the two cases a single
  hook can't both cover, since a same-path query-only navigation doesn't change `Router.currentPath()`.
- New `SegmentStreamModeTest` (9 cases) covers `isBrowserSafeDirectPlay`/`containerOf`/
  `segmentsPlaySessionId` directly, including the exact reported shape (DTS in MKV) and the exact
  probed title. `compileKotlinLinuxX64`/`compileKotlinWasmJs` clean, `linuxX64Test` green. **Not
  verified in a live browser** — no headless browser on this host, same limitation the investigation
  itself hit; the live Jellyfin probe is real, the client-side reload-seek behaviour is reasoned through
  but unwatched.

## Problem

The trim view's `<video>` is pointed at a **`Static=true`** Jellyfin stream — the original file, byte
for byte, in its original container with its original codecs:

```kotlin
val url = "$base/Videos/$jellyfinId/stream?Static=true&MediaSourceId=$jellyfinId" +
          "&DeviceId=$SEGMENTS_STREAM_DEVICE_ID&api_key=${session.jellyfinUserToken}"
```
— `SegmentRoutes.kt:292-303`

That was a deliberate Phase 163 decision, stated in the route's own doc comment: *"Never transcodes:
this is a scrub/preview tool, not a client that needs HDR tone-mapping or codec negotiation — if the
browser can't decode the file directly, the trim view falls back to timecode-only editing (no video)."*

The decision is right about **video** and wrong about **audio**, because the failure is not symmetric.
A browser that cannot decode the video track fires `error` on the `<video>` element, the editor
catches it (`Segments.kt:629-632`) and shows *"can't direct play here — use the timecodes below"*. A
browser that decodes the video but not the audio fires **no event at all**: it plays the picture
silently, the editor's `loadedmetadata` handler runs, and the badge cheerfully reads
**"direct play · no transcode"** (`Segments.kt:625`). The tool reports success while being unusable
for the job it exists to do.

### How much of the library this hits

Codec census over the production database (`~/jellystructure/config/config.toml`'s library,
516 items / 6 721 files with tracks, read 2026-09-06):

| first audio track | files | share |
|---|---|---|
| aac | 3 341 | 49.7 % |
| **eac3** | 1 558 | 23.2 % |
| **ac3** | 1 344 | 20.0 % |
| **dts** | 175 | 2.6 % |
| opus | 126 | 1.9 % |
| flac | 115 | 1.7 % |
| **truehd** | 37 | 0.6 % |
| mp3 | 24 | 0.4 % |

Containers: 6 058 mkv, 626 mp4, 19 m4v, 18 avi.

**46.4 % of files lead with an audio codec no mainstream browser decodes** (Chrome/Chromium ship no
AC-3/E-AC-3 decoder on desktop Linux, and never had DTS or TrueHD). Those files are 90 % MKV, whose
video is h264 (64 %) or hevc (33 %) — h264-in-MKV plays in Chromium, so the picture appears and the
sound does not. That is precisely the reported shape.

### Why this matters more here than in a normal player

The segment editor's whole job is deciding where an intro ends and where credits begin. Both
boundaries are primarily **audible** events — the theme tune starting, the music cutting, the dialogue
resuming. The editor already knows this: Phase 163 step 6 added a server-rendered **waveform lane**
(`GET /api/segments/{id}/waveform`, ffmpeg-decoded on demand) specifically so the operator can *see*
the audio. It gave them the picture of the sound and not the sound.

The "▶ play the cut" action (`Segments.kt:596-614`) — the control that exists so a human can verify a
boundary before signing it off — is the single most audio-dependent thing in the product, and on
nearly half the library it plays silence.

## Goal

Pressing play in the trim view produces sound, on any file in the library, without transcoding video
and without changing what the editor is allowed to be honest about.

## Requirements

### FR-190-1 — Audio may be transcoded; video may not

Phase 163's "direct play or nothing" rule is **amended, narrowly**: the editor never re-encodes video
(no HDR tone-mapping, no scaling, no bitrate negotiation — those are R177/R216's concerns and stay out
of this tool), but it **may** ask Jellyfin to remux the container and re-encode the audio track to
something the browser decodes. Audio re-encode is cheap, CPU-bounded and cannot produce the
undecodable-stream class of bug R183/R216 exist to prevent.

Concretely: `VideoCodec=copy`, `AudioCodec=aac`, stereo downmix, an explicit container the browser
accepts. The exact endpoint shape is an implementation choice, but it must be **probed live against
this house's Jellyfin 10.11.11 before any code is written** — see FR-190-4.

### FR-190-2 — The decision is made server-side, from data already stored, before the browser sees a URL

The client cannot detect "video decoded, audio didn't" — that is the whole reason this bug went
unnoticed. So the choice must not be a client fallback.

`GET /api/segments/{itemId}/stream` already resolves the item; it also has the item's full `Track`
list, which carries every audio stream's `codec` (populated by `FfprobeRunner`, the same field this
spec's census was computed from). The route decides:

- every audio track is in a **browser-safe set** (`aac`, `mp3`, `opus`, `flac`, `vorbis`) **and** the
  container is `mp4`/`m4v`/`webm` → direct play, exactly as today;
- otherwise → the audio-remux URL.

One predicate, evaluated once, on stored data. No probing at request time, no second round trip, no
per-browser capability negotiation.

`Track` has no container field; the extension of `item.path` / the episode's `path` is the container
and is already available on the same route.

### FR-190-3 — The badge tells the truth

`#seg-vid-tag2` currently hard-codes *"direct play · no transcode"* on `loadedmetadata`. The response
gains the mode the server chose, and the badge reflects it:

- direct play → **"direct play · no transcode"** (unchanged)
- audio remux → **"audio re-encoded so your browser can play it · video untouched"**
- no `jellyfinId` → **"not matched in Jellyfin yet"** (unchanged)
- `<video>` `error` → **"can't play this file here — use the timecodes below"** (unchanged)

A file that direct-plays picture but has no audible sound must never be able to display the first
badge again.

### FR-190-4 — Probe the endpoint before building it

Phase 163's `POST /MediaSegments` → **405** and Phase 187's *"every assumed endpoint was wrong, and the
OpenAPI document is actively wrong about the body"* are the standing precedent: reading the schema is
not probing. Before implementation, confirm against the live 10.11.11 that a video-copy /
audio-transcode stream can be requested, what it is actually called, whether it needs a `DeviceProfile`
POST to `/Items/{id}/PlaybackInfo` first (the shape
`specs/research-reports/` and `reference-live-jellyfin-negotiation-testing` already document for
Ravilo), and whether seeking works on the resulting stream — a segment editor that cannot seek is no
better than one with no sound. Record the findings in this spec.

### FR-190-5 — Seeking and duration must still work

The editor's entire interaction model is `video.currentTime = x` (`seekVideoTo`, `playCut`,
`seekRelative`) plus a frame-accurate `video.duration` (`correctDuration`). Whatever stream is served
must support byte/time-range seeking and report a real duration. If the probe finds that the only
available audio-transcode shape is a live HLS stream with no seek, say so and re-scope rather than
shipping a player that plays sound but cannot jump to the credits.

### FR-190-6 — A separate `DeviceId`, and teardown

The editor already identifies itself as `jellystructure-segments-editor`
(`SEGMENTS_STREAM_DEVICE_ID`). A transcoding session must keep that identity so it is
distinguishable in Jellyfin's active-sessions list, and it must be **stopped when the editor leaves** —
Phase 180 exists because an abandoned transcode kept NVENC busy for nobody. An admin tool that can
strand a transcode is a smaller version of the same bug; navigating away from `/segments`, or the
`renderSegments` entry that nulls `currentTrimData`, must issue the stop.

Phase 189 FR-189-3 removes the per-edit teardown/recreate of the `<video>`, which would otherwise
create and abandon one transcode session per stepper click. **190 should not ship before 189**, or
should carry FR-189-3 itself.

## Non-goals

- No video transcode, ever, for this tool — including HDR tone-mapping. A file whose *video* the
  browser can't decode keeps its current honest fallback (timecodes + waveform, no picture).
- No subtitle rendering in the editor.
- No change to Ravilo's playback negotiation (R177/R183/R216) or to `PlaybackService`. This route
  deliberately uses the admin's own Jellyfin session, not a device token, and that stays.
- No audio-track picker. The default/first audio track is the right one for judging a boundary.

## Acceptance

1. Open the trim view for an E-AC-3 MKV episode (23 % of the library — e.g. any file whose first audio
   track is `eac3`), press play: **sound comes out**, and the badge says the audio was re-encoded.
2. Open the trim view for an AAC MP4: sound comes out, the badge still says direct play, and no
   transcode session appears in Jellyfin.
3. "▶ play the cut" on a credits marker seeks to 3 s before the marker, plays with sound, and
   auto-pauses 3 s after the end — on both files above.
4. Drag the playhead to 40:00 on the E-AC-3 file: the video seeks there and audio follows.
5. Navigate from `/segments` to the media page: no transcode session for
   `jellystructure-segments-editor` remains active in Jellyfin.
6. A file with no `jellyfinId` still shows *"not matched in Jellyfin yet"* and no video element.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/SegmentRoutes.kt:286-303` — the
  `Static=true` stream route and its "never transcodes" doc comment.
- `src/wasmJsMain/kotlin/dev/jellystructure/ui/Segments.kt:616-648` — `wireVideo`, the
  `loadedmetadata`/`error` handlers and the hard-coded badge (`:625`, `:631`); `playCut` `:596-614`;
  `seekVideoTo`/`seekRelative` `:584-592`.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/SegmentRoutes.kt:307-324` — the waveform
  route, i.e. the existing proof that the audio matters here.
- `specs/requirements/phase-163-segment-editor.md` — §"Jellyfin is a source only" and the direct-play
  decision this amends.
- `specs/requirements/phase-180-playback-session-teardown.md` — why FR-190-6 is not optional.
- `specs/requirements/phase-187-account-photo-and-password.md` FR-187-1 — the probe-first precedent.

## Open questions

1. **Still open — the browser side was never instrumented**, before or after the fix. The codec census
   makes the diagnosis near-certain for 46% of the library, but which specific title the operator hit
   was never confirmed, and the fix itself has not been watched in a real browser (no headless browser
   on this host — the same limitation the investigation hit). One line in the console
   (`document.getElementById('seg-video').webkitAudioDecodedByteCount`) on a real remux-mode file would
   settle whether sound is actually reaching the speaker, and is worth doing before this ships.
2. **Answered (FR-190-4).** Jellyfin 10.11.11 accepts `VideoCodec=copy&AudioCodec=aac` as a bare GET on
   the plain progressive stream endpoint — no `PlaybackInfo` negotiation needed, same shape as the
   existing `Static=true` URL. It responds `Accept-Ranges: none` (not byte-range seekable, as a live
   transcode with unknown total size), which is why seeking is implemented as a `StartTimeTicks` reload
   rather than an in-place `currentTime` set — see the Status section and `SegmentRoutes.kt`'s own doc
   comment for the full probe.
3. **Decided: downmix.** Implemented as `AudioChannels=2` unconditionally — this is a monitoring feed
   for judging a boundary, not a listening experience, and downmixing removes a whole class of
   channel-layout failure the editor has no reason to expose an operator to.
4. **New, from the live probe.** Whether to adopt HLS.js (or similar) for this admin frontend in a
   future phase, which would make the genuinely-seekable HLS-VOD shape usable in Chromium and avoid the
   reload-per-seek pattern entirely. Deliberately out of scope here — a new client-side dependency is a
   bigger decision than this bug fix warrants — but worth stating rather than silently foreclosing.
