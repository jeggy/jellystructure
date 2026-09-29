# Phase R335 — Ravilo on Linux plays: films, music and audiobooks through mpv

> Owner, 2026-09-30, after playing a film in the Flatpak on the GNOME desktop and getting R237's card: *"let's make
> the specs and then start implementing, so we can have full proper 4k support (on devices that have 4k support) on
> linux machines"*.

## Status

`⚠ Partial` — **built 2026-09-30; the engine is verified in the Fedora container (software decode, both files at their
frame rate through the ring); a real GPU, a real 4K display and path (b) are unverified** (§Build notes). Written
2026-09-30 (dev-authored) from `research-reports/ravilo-linux-flatpak-2026-09-29.md` §5. Number verified free:
`origin/main` tops at R332, local `main` at R334.

**Builds on** R328 (the desktop app; on Linux every seam but the player already answers), R329 (the player seam's
shape on the desktop: an engine behind `RaviloPlayer`/`MusicEngine`, frames copied into a three-buffer ring Skia
wraps, subtitles at R180's position, R237's card on failure), R333 (the Flatpak: `--device=dri` and `--socket=pulseaudio`
were reserved for exactly this), R237/R218 (failure and waiting copy), R284/R302 (a client tells the server the truth
about what it decodes), R283 (the audio codecs a client really plays), phase 177/R216 (decoder ceilings are measured,
never invented).

## The shape in six lines

1. **One engine, mpv through `libmpv`**, driven over JNA from `desktopMain` — the same binding pattern as the Swift
   library, without a library of our own to build. mpv plays every container and codec the household has (MKV, HEVC,
   AV1, DTS, TrueHD, PGS), decodes on the GPU where there is one, tone-maps HDR to SDR itself, and renders
   subtitles itself.
2. **Direct play, said honestly.** The Linux capabilities name what mpv plays; HDR10 and HLG are claimed because the
   picture that reaches the screen is right (tone-mapped by libplacebo), which is what "supports" means to the
   server's profile (a `false` forces a tone-mapped *transcode* instead — the opposite of 4K). No decoder ceiling is
   invented (`DecoderLimits.UNKNOWN`); a 4K file is offered as it is and the machine's decoder answers.
3. **Two ways to the screen.** (a) mpv's software render API writes the frame into R329's ring at the size of the
   surface, and Skia draws it — works on every machine, including one with no GL, and is the path of the first build.
   (b) mpv's own GPU output (`vo=gpu-next`) into an X11 child window embedded in the Compose window, zero copies,
   hardware decode to display — the path 4K deserves, taken when the display has GL and Compose can draw the chrome
   over the child (D3).
4. **Subtitles and audio are mpv's**: embedded tracks from the container, the ticket's sidecar files added to mpv,
   PGS drawn by mpv, the pick applied in place (`sid`/`aid`); R180's position and the S/M/L size become `sub-pos` and
   `sub-scale`. R329's Compose cue overlay stays the Mac's.
5. **Music and books** run on a second, audio-only mpv instance behind the same `MusicEngine` the Mac uses, with the
   Mac's session bookkeeping unchanged; WMA, Opus and Vorbis direct-play on Linux (mpv decodes them), so the server
   converts nothing.
6. **The Flatpak builds mpv from source** (mpv, libplacebo, libass as modules; FFmpeg from the runtime's
   `ffmpeg-full` extension; NVDEC headers), the recipe jellyfin-media-player's Flathub manifest already uses.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Engine | **mpv via `libmpv`** (research §5.2). GStreamer, the runtime's own, stays the fallback plan if mpv proves unbuildable on Flathub — nothing in the seams would change |
| D2 | Binding | **JNA in Kotlin**, no C of our own: ~20 functions (`mpv_create/initialize/set_option_string/command/get_property/set_property/observe_property/wait_event/terminate_destroy`, the render context's `create/render/update/free`), events on one daemon thread, properties polled into a snapshot with the Mac's 15 ms TTL |
| D3 | The picture | **(a) software render into the ring first** — the proven R329 path, at the surface's size so a 1080p window never pays for 4K; **(b) the embedded GPU window second**, behind a probe (an X11 display, GL, and Compose's interop blending drawing the chrome over a heavyweight child); (b) is on when the probe passes, (a) otherwise, and `-Dravilo.video=sw|gpu` forces either for measuring |
| D4 | Decoding | `hwdec=auto-safe` (VA-API, NVDEC, Vulkan — whatever the machine and the sandbox expose; a copy-back variant on path (a)) |
| D5 | HDR | **`supportsHdr10 = supportsHlg = true`**: mpv tone-maps (libplacebo) to the SDR window, so the file direct-plays and looks right. Dolby Vision profile 8 rides that (its HDR10 base layer); `supportsDolbyVision` stays false (profile 5 needs a DV decoder). When a Wayland/HDR display path exists for a child window, D5 is revisited |
| D6 | Capabilities | containers mkv/mp4/mov/avi/ts/webm; video h264/hevc/av1/vp9/mpeg2video/mpeg4; audio aac/mp3/ac3/eac3/dts/truehd/flac/opus/vorbis/pcm; `supportsEmbeddedTextSubs = true`; `hlsOnly = false`; decoder limits unknown |
| D7 | Music | one audio-only mpv instance (`vid=no`), the engine's queue/book logic untouched; music capabilities gain wma/asf, opus, vorbis, ogg |
| D8 | What the machine does while a film plays | the Inhibit portal (no screen blanking) and MPRIS (media keys, GNOME's player controls) are **R336**, not here — playback first |
| D9 | The dev loop | the host has no libmpv and the owner installs nothing on it: **the Fedora container is the bench** (`mpv-libs` 0.41 installed there); the app is built on the host and run on the container's XFCE display, and the Flatpak is built there too |

## Requirements

**FR-R335-1 — `Mpv`, the binding.** `ravilo-ui/src/desktopMain/…/desktop/Mpv.kt`: the JNA `Library` for `libmpv.so.2`
(loaded lazily, absent = `MpvPlayer.available == false` and the honest R329 fallbacks stay), the `mpv_event` and
`mpv_render_param` layouts, `mpv_get_property` for flag/int64/double/string, and `track-list` read as the JSON mpv
returns for a node. Nothing above the binding sees a pointer.

**FR-R335-2 — `MpvPlayer`, the engine**, the same surface `MacPlayer` gives `RaviloPlayerDesktop` and
`MusicEngineDesktop` (`load(url, mime, startMs)`, `play`, `pause`, `seekTo`, `setRate`, `setVolume`, `selectAudio`,
`state`, `error`, `debug`, `audioOptions`, `takeFrame`, `release`), so both actuals pick the engine by platform and
change nothing else. The state snapshot is R329's `MacPlayerState` (position, duration, buffered, time control, item
state, ended, first frame, size, dropped frames, stalls, seeking, wants-play) filled from mpv's properties:
`time-pos`, `duration`, `demuxer-cache-time`, `pause`, `paused-for-cache`, `core-idle`, `eof-reached`, `seeking`,
`video-params/w|h`, `frame-drop-count`, `idle-active`, and the `end-file` event's error. Options at creation: `vo=libmpv`
(path a) or `vo=gpu-next` + `wid` (path b), `hwdec=auto-safe`, `keep-open=yes`, `terminal=no`, `input-default-bindings=no`,
`audio-client-name=Ravilo`, `ytdl=no`, `cache=yes`, `demuxer-max-bytes` sized for a 4K remux, and `sub-visibility=no` until a
track is picked. A load is `loadfile <url> replace start=<s>`.

**FR-R335-3 — The picture, path (a).** `takeFrame(surfaceW, surfaceH)`: when `mpv_render_context_update` says a
frame is new, `mpv_render_context_render` writes `bgr0` at `min(video, surface)` size into the ring's next buffer
(the three-buffer discipline and the retired-buffers rule of R329 kept), and the surface draws it as today. On a
resize the buffers grow once. Nothing is rendered when no frame is new: a paused film costs no CPU.

**FR-R335-4 — The picture, path (b).** A `SwingPanel` hosting a heavyweight `Canvas` gives mpv its `wid`
(`Native.getComponentID`); the player chrome is Compose over it, which needs `compose.interop.blending` — probed at
first use on this machine (an X11 display, a GL context, and the blending property honoured) and remembered; when the
probe fails the player uses path (a) silently. `--stop-screensaver=no` (D8's portal will do it), `--force-window=no`.
The surface composable is one for both paths.

**FR-R335-5 — Subtitles.** The ticket's text tracks whose delivery is a file are added with `sub-add <url> auto` in
the ticket's order; embedded tracks come from the container. `subtitleTracks` is mpv's `track-list` filtered to
subtitles, in mpv's order, labelled as R329 labels (title, else language name, else `Sub N`), `deliveryMethod`
`embedded`/`external`; `selectSubtitleTrack(i)` sets `sid` (−1 = `no`); `setSubtitleScale` sets `sub-scale`
(S/M/L = 0.8/1.0/1.3 on mpv's default size, which is already R110's look); `sub-pos` and `sub-margin-y` keep R180's
position above the chrome. PGS is drawn by mpv. R329's Compose cues are not used when the engine is mpv.

**FR-R335-6 — Audio.** `audioTracks` from `track-list` (language, channels, title, default); `selectAudioTrack(i)` sets
`aid`. R181's remembered pick resolves against them as on Android.

**FR-R335-7 — Capabilities (Linux).** `DesktopCapabilities` answers D6 when `MpvPlayer.available`; `playsOnlyHls()`,
`switchesHlsAudioRenditions()` false; `supportsEmbeddedTextSubtitles()` true; `detectHdrSupport()` per D5;
`detectDecoderLimits()` unknown. With no libmpv the answers stay R329's conservative ones and the player fails at
once with R237's card, saying (new string, three languages) *Playback is not available in this build*.

**FR-R335-8 — Music and books.** `MusicEngineDesktop` builds `MpvPlayer(audioOnly = true)` on Linux; `musicMimeFor`
is unused there (mpv sniffs); `gapless-audio=weak`; a book's next part is fetched early exactly as on the Mac. The
Linux music capabilities: containers mp3/flac/m4a/mp4/aac/wav/ogg/opus/wma/asf; codecs mp3/aac/flac/alac/pcm/opus/vorbis/wmav2/wmapro.

**FR-R335-9 — Failure and waiting.** mpv's `end-file` with an error, or no first frame within R237's window, is
`playbackFailed` with the cause mapped to R237's copy (network, format, decoder); `paused-for-cache` is R218's stall;
`seeking` is R218's seek moment.

**FR-R335-10 — The Flatpak.** `flatpak/net.jebster.Ravilo.yml` gains, before the app module: `libass`, `libplacebo`
(OpenGL on, Vulkan on where the SDK's headers allow, no shader compiler needed for GL), `nv-codec-headers`, and `mpv`
(`-Dlibmpv=true -Dcplayer=false -Dlua=disabled -Djavascript=disabled -Dlibplacebo=enabled`), each pinned by tag and
checksum, FFmpeg from `org.freedesktop.Platform.ffmpeg-full` declared in `add-extensions` (already the runtime's
`ffmpeg` at build time). `finish-args` are unchanged: `dri` and `pulseaudio` were reserved for this. The Flatpak's
`LD_LIBRARY_PATH` reaches `/app/lib` so `libmpv.so.2` loads.

**FR-R335-11 — The Linux dev loop and tests.** `ravilo-desktop/README.md` says how: build the app image on the host
(`-Pravilo.desktopOnly=true :ravilo-desktop:createDistributable`), run it on the container's XFCE display as `xfce`
(`DISPLAY=:1`, libmpv from Fedora), screenshots from inside; the Flatpak from the container. Tests: the `track-list`
JSON → tracks mapping, the state mapping (`MpvState` from property values), the render-param packing, and — where
`libmpv` exists (the container) — one integration test that plays a generated file headlessly and sees frames;
elsewhere it skips, saying so.

## Out of scope

The Inhibit portal and MPRIS (**R336**) · AirPlay/Cast from Linux beyond what R330's JmDNS path already gives · HDR
passthrough to an HDR display · a Linux menu-bar redesign (the Swing strip under GNOME's title bar — a small R328
follow-up) · Windows.

## Acceptance

1. On the container's XFCE display (software decode, llvmpipe): a 1080p H.264/AAC MKV and a 4K HEVC MKV play from the
   local stack with picture and sound, seek, pause, audio switch and an embedded subtitle; the engine's dropped-frame
   count and CPU are recorded in the build notes (the container measures correctness, not speed).
2. On the owner's Linux desktop with a GPU, through the Flatpak: a 4K HEVC HDR10 remux from the household's server
   direct-plays (the ticket says direct play, `hwdec` reports a hardware decoder in `debug()`), tone-mapped, smooth
   at the file's frame rate; the audio pick and a PGS subtitle work in place.
3. Music and a book play on Linux; a WMA track direct-plays (the ticket's container is `wma`).
4. With `-Dravilo.video=gpu` on a machine where the probe passes, the chrome draws over the video; with `sw`, the same
   film plays through the ring.
5. `flatpak-builder` builds the manifest with the mpv modules in the container; the installed Flatpak plays.
6. Without libmpv (the plain app image on a machine without it) the player says *Playback is not available in this
   build* instead of *Something went wrong*.

## Open questions

1. **Does Compose 1.9's interop blending work on X11 with an OpenGL Skia backend?** It is documented for Windows and
   Linux as experimental. If not, path (b) waits for a Compose release, and 4K rides path (a) — which is what the
   Compose-desktop players in the wild do; measure it on the owner's machine before deciding it is not enough.
2. **NVDEC inside the Flatpak** — the `org.freedesktop.Platform.GL.nvidia-*` extension ships `libcuda`/`libnvcuvid`
   for the host's driver; verify on the owner's NVIDIA machine that `hwdec` reports `nvdec`, else `vaapi`/software.
3. **A 4K window on path (a)**: 33 MB per frame through zimg and a Skia upload; at 24 fps plausible on a desktop
   CPU, at 60 fps probably not. The build notes carry the numbers from wherever they can be measured.
4. **The failure copy for "no libmpv"** — three languages, R279's table.

## Build notes (2026-09-30)

Built the same day, on Debian, run in the Fedora container (`~/fedora`, D9): Fedora's `mpv-libs` 0.41 (libmpv API 2.5),
RPM Fusion's FFmpeg for H.264/HEVC decoders, two generated files — 1080p H.264 with two audio tracks and a SubRip
track, 4K HEVC 10-bit — and `Ravilo --mpv-bench` / `--mpv-window` (FR-R335-11), added to the app for exactly this.

1. **FR-R335-1/2 as specified.** `Mpv.kt` (the JNA surface), `MpvPlayer.kt` (the engine), `DesktopEngine.kt` (the
   shared surface; the Mac's `MacPlayer` is wrapped, not changed). Both desktop players pick the engine by platform;
   `MacPlayerState` stays the state's name on both — renaming it would have touched the Mac's files for nothing.
2. **The ring (path a) plays at the file's frame rate**: 1080p H.264 — first frame in 413 ms, 133 frames in 5.6 s
   (23.7 fps), 6 drops; 4K HEVC 10-bit — first frame in 850 ms, 24.0 fps, software decode on 32 cores. The first
   measurement said 33 ms of "render" at every size; that was mpv holding each frame until its display time
   (`MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME`, on by default). Off, the real cost is **3 ms a frame at 1080p and 5 ms
   for 4K scaled to a 1080p surface**, and the update flag paces the frames. `sw-fast` changed nothing measurable.
3. **Tracks, seek, picks:** mpv's `track-list` gives both audio tracks with language and channels and the subtitle
   with its codec; a seek to 2 s lands at 2.08 s; `aid` and `sid` follow the picks. Unit tests cover the JSON, the
   param layout and the honest absence.
4. **Path (b) is unproven** (open question 1). The plumbing works — with mpv's `x11` output the picture paints inside
   the embedded canvas — but the container has no GL for anyone: Skia falls back to software, mpv's `gpu`/`gpu-next`
   stay black, and the red Compose box of `--mpv-window` does not draw over the canvas. Whether interop blending
   composes the chrome over a native child on a real desktop is the first thing to try there; until then the ring is
   the default and `-Dravilo.video=gpu` is an experiment. The engine releases mpv before AWT destroys the canvas
   (Xlib's BadWindow would otherwise end the process).
5. **FR-R335-7's copy** (*Playback is not available in this build*) is **not done**: the failure card's text comes from
   `PlayerSessionState.Error` in common code with one generic kind, and a new kind touches every platform; R237's card
   shows as before. Owed.
6. **The Flatpak (FR-R335-10):** the four modules and the `ffmpeg-full` extension are in the manifest. The
   `--local` render of R333 had to change twice for it: it now clones the checkout's HEAD (a `dir` source walked every
   module's build output and the backend's unreadable files), and matches the app module's source line by line (its
   regex had crossed into the mpv modules). The build's result is recorded below when it lands.
7. **Not measured anywhere yet:** hardware decode (`hwdec` reports `no` in the container — CUDA has no driver there,
   VA-API no device), a 4K surface, a real display's frame pacing, HDR tone-mapping's look. Acceptance 2 is the
   owner's Linux desktop with a GPU.
