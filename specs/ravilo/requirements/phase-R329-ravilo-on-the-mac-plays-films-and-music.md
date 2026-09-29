# Phase R329 — Ravilo on the Mac plays films and music

> Owner, 2026-09-29: a Ravilo app for the Mac, its `.dmg` on every release (R331).

## Status

`Planned` — written 2026-09-29 (dev-authored) from `research-reports/ravilo-macos-desktop-app-2026-09-29.md` §3.
**Dev-reviewed 2026-09-29** against `main` `4c67e49f` (§Dev review) — build from it. Number verified free. **Second of four** (R328 → **R329** → R330 → R331). **Builds on** R328's native
library, the receiver's `hls_only` negotiation (the Chromecast path, `2b19966f`), R218 (waiting states), R180/R195
(the picker), R282/R285 (burn-in by restream), R322/R323 (the music and book engine), phase 180 (the stop).

## Decisions (leans)

| # | Question | Lean |
|---|---|---|
| D1 | Engine | **AVPlayer**, driven from R328's Swift library. libVLC would direct-play MKV, but macOS gives it no surface to draw into, so every frame would be a CPU copy, and it adds ~100 MB. Chosen by FR-R329-1's spike |
| D2 | What the server sends | **Everything as HLS** — the Chromecast receiver's `hls_only` negotiation, unchanged: H.264/HEVC video copied, audio re-encoded only where the Mac cannot decode it, picture subtitles burned in |
| D3 | Subtitles | **Drawn by Compose**, one path for every text subtitle (WebVTT from the ticket, `shared`'s parser), so they look like the TV's |
| D4 | Player chrome | **The TV's chrome with a pointer** (not R244's handset chrome) |
| D5 | AirPlay | **Not in this phase** (§Out of scope) |

## Requirements

**FR-R329-1 — The spike comes first, and has a bar.** Before any other requirement is built: a Compose Desktop window
on an Apple-Silicon Mac plays the household's Jellyfin HLS through AVPlayer with Compose chrome drawn over the picture.
Two ways to hand frames to Compose are tried in this order:

- (a) `AVPlayerItemVideoOutput` pixel buffers (IOSurface-backed) wrapped as images in Compose's own Metal context —
  no copy;
- (b) the same buffers copied into a Skia bitmap.

The build notes record, for 1080p H.264 and 4K HEVC: dropped frames over five minutes, CPU, and whether an HLS
audio-rendition switch works. **The bar:** 1080p with no visible stutter and under 1 % dropped frames; 4K playable.
If neither path meets it, the phase stops and says so — the Mac's answer is then the web player (research road 1).

**FR-R329-2 — The player behind the seam.** `RaviloPlayer`'s desktop actual drives AVPlayer through the native library:

- load at a start position, play, pause, seek;
- `hasRenderedFirstFrame` — the first frame reached the output;
- `isBuffering` — `timeControlStatus` is waiting to play at the requested rate;
- release, which reports the stop (phase 180) and tears the item down.

R218's cold start, stall and seek moments and R237's failure copy work as on the TV.

**FR-R329-3 — The Mac tells the server the truth** (the R284/R302 rule):

- `hlsOnly = true` — AVPlayer plays no MKV.
- Video codecs from runtime probes: `AVURLAsset.isPlayableExtendedMIMEType` on `avc1…`, `hvc1…` and `dvh1…`, which
  also sets `hlsHevc`.
- HDR10/HLG/Dolby Vision from the same probes together with the screen's extended dynamic range
  (`NSScreen.maximumPotentialExtendedDynamicRangeColorComponentValue > 1`).
- Audio: AAC, AC-3 and E-AC-3 where the probe says yes; never DTS or TrueHD.

The backend negotiates exactly as it does for the Chromecast receiver. **No backend change.**

**FR-R329-4 — Audio tracks.** When the HLS master carries several audio renditions, the picker switches between them
through AVPlayer's audible media-selection group (`switchesHlsAudioRenditions = true`). Otherwise the pick is R285's
restream, as on the receiver.

**FR-R329-5 — Subtitles.**

- Text subtitles: the ticket's WebVTT, parsed with `shared`'s `parseVtt`/`activeCueText` and drawn in Compose at
  R180's positions, with the Subtitle size row (S/M/L).
- Picture subtitles (PGS): R282's burn-in by restream.
- AVPlayer's own legible group is never shown, so there is one look.

**FR-R329-6 — The chrome with a pointer.**

- Moving the mouse shows the TV chrome; it hides after 3 s, taking the cursor with it.
- **Space** play/pause · **←/→** −10 s/+30 s (the TV's steps) · **F** full screen · **Esc** leaves full screen, then
  Back.
- Clicking the picture toggles the chrome; double-clicking toggles full screen.
- Skip intro, next-up and the picker work by keyboard and mouse.

**FR-R329-7 — Full screen** is macOS's own (its own Space). The player may enter it; leaving the player leaves it only
if the player entered it.

**FR-R329-8 — The display stays awake** while a film plays (an `IOPMAssertion` that prevents display sleep), released
on pause and on leaving.

**FR-R329-9 — Music and audiobooks.** `MusicEngine`'s desktop actual is R322/R323's engine on an audio-only AVPlayer:

- one Jellyfin session per song, the queue in `MusicQueue`, progress and stop reported per song;
- a book's parts, speed, sleep timer and chapters as on Android;
- the Android behaviour carried over: gaps between songs accepted (R322), the next part fetched early on direct play
  (R323).

`MusicEngine.supported = true` makes the listening mode available on the Mac (R321/R326), and a film taking the
screen stops the song (FR-R322-12).

**FR-R329-10 — Now Playing and the media keys.** While a song, a book or a film plays:

- `MPNowPlayingInfoCenter` carries the title, the artist or series, the album or book, the artwork, the position and
  the rate.
- `MPRemoteCommandCenter` answers play, pause, toggle, next, previous and seek; a book gets ±30 s (R323).
- So Control Center, the keyboard's media keys and AirPods drive Ravilo, and the Now Playing card follows R322's rules.

**FR-R329-11 — Trailers** open in the default browser.

## Out of scope

- **AirPlay from the Mac** (an Apple TV or AirPlay speaker). AVPlayer supports it, but macOS has no public API to open
  the route picker except its own view; a later phase.
- Picture-in-Picture · offline downloads · local files.

## Acceptance

1. The spike's numbers are in the build notes and meet the bar.
2. A 1080p H.264 film and a 4K HEVC HDR film play from Home with the chrome over them; the Users & devices quality line
   shows direct video with copied streams.
3. An MKV with DTS audio plays, the audio converted by the server; the picker switches between two audio languages.
4. A film with PGS subtitles shows them burned in; a film with SRT subtitles shows them drawn by Compose at S, M and L.
5. An album plays in the listening mode; the keyboard's media keys pause and skip; Control Center shows the song.
6. Leaving a film stops its Jellyfin session within a second (phase 180).

## Open questions

1. ~~Does AVPlayer need the server to package HEVC as fMP4 HLS?~~ **Answered (dev review 2): yes, and the backend
   already does — `hlsHevc` selects the transcoding profile whose URL comes back `SegmentContainer=mp4`.**
2. Should a film play windowed first (lean) or go straight to full screen?

## Dev notes

- `ClientCapabilities` already has `hlsOnly`, `hlsHevc`, `supportsHdr10`, `supportsHlg` and `supportsDolbyVision`
  (`shared/.../Models.kt`).
- The Chromecast receiver's `capabilities()` (`ravilo-cast/.../Receiver.kt`) is the model for probe → capabilities.

## Dev review (2026-09-29, against `main` `4c67e49f`)

The spike is the phase; the rest is known. Twelve items.

1. **FR-R329-1's two frame paths are the wrong way round.** (a) — handing AVPlayer's IOSurface-backed pixel buffers to
   Compose without a copy — has **no public API in Skiko** (its `Image` constructors are `makeFromBitmap`,
   `makeFromPixmap`, `makeRaster`, `makeFromEncoded`; nothing imports a Metal texture). (b) — copy each
   `CVPixelBuffer` into a Skia bitmap — is the path every shipped Compose Desktop player uses: ComposeMediaPlayer's
   macOS backend and JetBrains' own vlcj sample. **Build (b) first, with a pool of two bitmaps** (no allocation per
   frame); 1080p60 is ~500 MB/s of copies and 4K24 ~800 MB/s, which Apple Silicon does. Try (a) only if (b) misses the
   bar, and then it is Skiko work, not ours. A third fallback exists and is worse: an `AVPlayerLayer` in a child
   `NSWindow` ordered beneath a transparent, undecorated Compose window — it loses the title bar and native full
   screen. The bar in the FR stands.
2. **HEVC over HLS is already fMP4.** `JellyfinClient.kt:104` — `capabilities.hlsHevc` picks the transcoding profile
   whose URL comes back `SegmentContainer=mp4`, the form AVPlayer requires for HEVC. Open question 1 answered.
3. **FR-R329-4 is R291's feature, not R285's.** For a client that says `hlsAudioRenditions = true`, `withRenditions`
   (`PlaybackService.kt:371`) points a transcode's ticket at a **composed master carrying every audio track**; AVPlayer
   switches those through its audible `AVMediaSelectionGroup`. The Mac never direct-plays a film (`hlsOnly`), so every
   film is a transcode and every audio pick is a rendition switch. R285's restream stays only for burn-in.
4. **Subtitles reuse the receiver's split.** With `hlsSubtitles = false` the ticket lists text tracks with a WebVTT
   `url` and burn-in candidates with `deliveryMethod = "encode"`; `receiverSubtitles()` and `receiverSubPick()` in
   `shared` already tell the two apart for the Chromecast — reuse both, fetch the same URL CAF loads, parse with
   `parseVtt`, draw with `activeCueText`. One subtitle look, as D3 wants.
5. **Music should direct-play, not go through HLS.** AVPlayer plays MP3, AAC, ALAC and FLAC files natively (FLAC since
   macOS 10.13); `startMusicPlayback` negotiates with `audio = true` and only converts what the client cannot decode,
   so the music engine's capabilities say `mp3, aac, flac, alac` (not Opus — AVPlayer takes Opus only inside CAF/MP4)
   and WMA is converted by the server, exactly as for the phone (286 FR-286-7's line).
6. **`MusicEngine`'s desktop actual is ~700 lines of new code**, the size of the Android one, because the Android
   engine is ExoPlayer/Media3-shaped throughout. What it reuses unchanged: `MusicQueue`, `BookMath`/`BookPlayback`,
   `MusicQueueStore` and every screen (they call `MusicPlayback`, R324). Same rule as Android: one Jellyfin session per
   song, progress every 10 s, stop on leaving.
7. **Now Playing needs the main thread.** `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter` must be touched on the
   main thread and deliver their commands there; the Swift library hops with `DispatchQueue.main` on the way in and
   the JNI callback posts to Compose's main dispatcher on the way out. Same shape as the Android engine's
   `Dispatchers.Main.immediate`.
8. **Full screen:** Compose Desktop's `WindowPlacement.Fullscreen` uses macOS's own full screen (its own Space) —
   FR-R329-7 needs no native code. **Display sleep** (FR-R329-8) does: `IOPMAssertionCreateWithName(NoDisplaySleep)` in
   the Swift library, released on pause.
9. **The Swift library arrives with this phase** (R328 dev review 1): player, Now Playing, the assertion. Built with
   `xcrun swiftc -emit-library -target arm64-apple-macos14.0`, loaded once with `System.load`, absent on Linux with
   `RaviloPlayer` reporting *not supported* and `MusicEngine.supported = false` — the Linux build browses, the Mac
   plays.
10. **Capabilities honesty (FR-R329-3):** `ClientCapabilities` already carries `hlsOnly`, `hlsHevc`, `hlsSubtitles`,
    `hlsAudioRenditions`, `supportsHdr10/Hlg/DolbyVision`; the Chromecast receiver's `capabilities()` is the model.
    `detectDecoderLimits` answers nulls (phase 185's *not measured yet* is the honest line in Users & devices).
11. **Trailers:** `Desktop.browse(url)` — no embed, as the FR says.
12. **Wire:** none. The ad-hoc signing decision (R331) does not touch this phase beyond signing the dylib.
