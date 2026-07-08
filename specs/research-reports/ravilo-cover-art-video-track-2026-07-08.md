# Cover-art muxed as a video track breaks playback — investigation (2026-07-08)

**Date:** 2026-07-08

**Question:** a user pressed play on *Server Farm* S01E01 on the stue TV and the media player
opened but the video never started. Why — and beyond the client fix, what "management" should
jellystructure grow so it can *detect* and *repair* files like this (like it already does for
untagged tracks, multi-default audio, and zero-audio files)?

**Status: root-caused and the client fix shipped** (commit `482a336`, `ravilo-player`). This report is
the record of the analysis and a scope note for **future server-side management** in jellystructure —
that part is **not built**.

---

## 1. The offending file

`Server.Farm.S01E01.1080p.MAX.Web-DL.[TR-EN].DDP5.1.H.264-TURG.mkv` (jellyfinId
`69addee9…`). `ffprobe` on the video streams:

```
stream 0: h264  1920x1080   disposition.default=1  attached_pic=0   (the episode)
stream 1: png    857x856     disposition.default=0  attached_pic=0   FILENAME=cover.png MIMETYPE=image/png
+ audio: eac3 (eng, default) + eac3 (tur)
+ 25x subrip subtitle tracks
```

The release muxes a **`cover.png` as a second video stream** that is **not** flagged as an attached
picture (`attached_pic=0`), so it looks like a genuine, selectable video track rather than album art.
**Every S01 episode of this release has the same `h264 + png` pair** (spot-checked S01E02/E03). Normal
single-video-track files are unaffected — which is why everything else plays.

## 2. What happened on the client

Jellyfin's `PlaybackInfo` reports **DirectPlay=True** for this file (and only counts the one h264 video
stream), so jellystructure hands ExoPlayer the raw MKV. On the TV, the player opened (audio session +
black SurfaceView) but **no video ever rendered, and no error was thrown** — Media3's Matroska extractor
logged `MatroskaExtractor: Unknown FourCC. Setting mimeType to video/x-unknown` for the png track.

Ground truth came from a temporary `onTracksChanged` diagnostic on the Bravia:

```
type=2 video/avc          1920x1080  supported=true  SELECTED     ← real episode
type=2 video/x-unknown    857x856    supported=false not selected ← the cover.png
type=1 audio/eac3                    supported=true  SELECTED
STATE: BUFFERING → READY, error=null
```

That output is **after** the fix (video/avc correctly selected). Before the fix the same file left the
player with **no working video decoder** for the h264 track.

### Root cause (client)
`RaviloRenderers` built the ExoPlayer renderers with `EXTENSION_RENDERER_MODE_PREFER`. That preferred
the jellyfin ffmpeg artifact's experimental **`FfmpegVideoRenderer` for video** as well as audio (the
extension was only ever wanted for exotic *audio* — DTS/TrueHD/AC3/E-AC3). With that software video
renderer in the list, the presence of the junk `video/x-unknown` cover track left the player unable to
assign a working decoder to the real h264 track.

### Fix (shipped)
Subclass `DefaultRenderersFactory` and force `EXTENSION_RENDERER_MODE_OFF` for **video only** (hardware
`MediaCodecVideoRenderer` always), keeping `PREFER` for audio. The track selector then cleanly picks
`video/avc` and ignores the un-decodable cover. Verified end-to-end on the stue TV: `state=3` (READY),
`speed=1.0`, `error=null`, position advancing `141065ms → 144063ms` at 1x, ~28 min buffered.

### Diagnostic caveat worth remembering
This Bravia's **hardware video decoder emits no visible logcat lines**, and `screencap` cannot capture
the **SurfaceView** video layer (a consequence of the R173 TextureView→SurfaceView switch). So
"no MediaCodec logs + a black screenshot" is **not** a reliable failure signal — it fooled two
intermediate reads in this investigation. Confirm playback via the media-session `PlaybackState`
(position advancing) and/or a `Player.Listener` (`onTracksChanged`/`onPlaybackStateChanged`/
`onPlayerError`), not logcat greps or screenshots.

### Secondary observation (not the cause)
There was a consistent **~40 s gap** between the player opening and the stream loading — the backend was
slow to return the `StreamTicket` for this item. Likely aggravated by its **25 subtitle tracks**
(`buildSubtracks`) plus the stale-admin-token validation round-trip (see the
`session-user-device-management-2026-07-07` report). Annoying, not the terminal cause, but a candidate
for the same "management" effort.

## 3. Proposed management support in jellystructure (not built)

The client is now robust, but the file is genuinely malformed and other clients / Jellyfin apps could
mishandle it too. jellystructure already stores each item's tracks (it recorded `0:v:1 png` for this
episode) and already has a **file-health/triage vocabulary** — untagged tracks, multi-default audio,
missing artwork, zero-audio (Phase 128). "Cover art muxed as a video track" fits the same model.

- **Detect (scan-time):** flag any `MediaItem`/`Episode` whose tracks contain a **secondary VIDEO
  stream that is really an embedded image** — heuristics: `codec ∈ {png, mjpeg, bmp}`, or a small/square
  resolution, or a container `MIMETYPE=image/*` / `FILENAME=cover.*`, on a track that is **not** the
  primary/default video. This is a pure read over already-stored track metadata (`TriageDetection`
  pattern) — no new probing.
- **Surface:** a new triage type (e.g. `cover_as_video`) in `TriageRoutes` + the Dashboard breakdown +
  the Library `filter=` set, mirroring `multi_default` / `zero_audio`, so an operator can see every
  affected title.
- **Repair (one action):** reuse the existing track-edit infrastructure (`TrackRoutes` +
  `MkvpropeditRunner`/`FfmpegRunner`, the `SeedingGuard`, the write-through UI): a **"Fix cover track"**
  button that either sets the cover stream's disposition to `attached_pic` (`mkvpropedit
  --edit track:@N --set flag-... ` — the proper flag so players treat it as album art) or drops the
  stream (`ffmpeg -map` remux) for non-MKV. Same shape as the multi-default-audio "keep this one" fix.
- **Alternative (heavier, not recommended):** force Jellyfin to remux/transcode such files at
  `PlaybackInfo` time (`Static=false`) so no client sees the junk track — but that gives up direct-play
  and is worse than repairing the file once.

Scope note: this is **opt-in cleanup + visibility**, not required for playback (the client fix handles
it). Its value is (a) making the whole library healthy for any client, and (b) the same detection can
carry the ~40 s "too many subtitle tracks slows StreamTicket build" concern if that's pursued.

## 4. Acceptance (of the shipped client fix)
- Server Farm S01E01 plays on the stue TV after the fix (verified: READY, advancing, no error).
- Normal single-video-track content still plays (hardware video was always the correct path for it).
- FFmpeg audio (DTS/TrueHD/AC3/E-AC3) is unchanged — still preferred via `buildAudioRenderers`.
- Other TVs (Soveværelse) + the phone want the same build — pending deploy.
