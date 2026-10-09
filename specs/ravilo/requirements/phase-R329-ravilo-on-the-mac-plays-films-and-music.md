# Phase R329 — Ravilo on the Mac plays films and music

> Owner, 2026-09-29: a Ravilo app for the Mac, its `.dmg` on every release (R331).

## Status

`⚠ Partial` — **built 2026-09-29; a film played on a real Mac the same day (a 4K HDR film as the server's 1080p SDR, text subtitles, seeking) — music, the media keys and the spike's 4K numbers are still owed** (§Build notes). Written 2026-09-29 (dev-authored) from `research-reports/ravilo-macos-desktop-app-2026-09-29.md` §3.
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

## Build notes (2026-09-29)

Built from the dev review on Linux. **Nothing here has played**: the Swift library has never been compiled and no Mac
has run it, so FR-R329-1's numbers are owed and the phase is `⚠ Partial` until they are in and meet the bar.

1. **The Swift library** (`ravilo-desktop/native/`, ABI 2): `Player.swift` (AVPlayer behind a handle),
   `NowPlaying.swift` (`MPNowPlayingInfoCenter`/`MPRemoteCommandCenter`, every call hopped to the main queue, the
   commands back through one C callback), `Power.swift` (a `PreventUserIdleDisplaySleep` assertion). The player
   never calls into the JVM: AVFoundation's threads only set flags under the handle's lock, and Kotlin drives the
   rest — the start seek, AVPlayer's own subtitles off, a queued audio pick, starting — from one `ravilo_player_tick`
   on Compose's thread every 50 ms. So every AVPlayer mutation happens on one thread. A seek before the item is
   ready is kept and made once it is (AVFoundation cancels a seek outside the seekable range), and playing waits for
   it, so frame 0 never flashes.
2. **FR-R329-1 path (b), as the review ordered.** `AVPlayerItemVideoOutput` asks for 32BGRA;
   `ravilo_player_copy_frame` copies the newest `CVPixelBuffer` row by row into memory Kotlin owns (a ring of
   **three** JNA buffers, not two: a frame is written while the screen may still draw either of the two before it),
   and Skia wraps that memory without a second copy (`Data.makeWithoutCopy` → `Image.makeRaster`). The surface
   pulls once per display frame on Compose's thread. `qoeSnapshot` carries the access log's dropped frames, stalls
   and observed bitrate, which is what the spike's numbers come from. **Not measured.**
3. **FR-R329-3 — capabilities.** `isPlayableExtendedMIMEType` probes `hvc1` (→ `hevc` and `hls_hevc`), `ac-3` and
   `ec-3`; AAC and MP3 always; never DTS or TrueHD. A new common seam, `playsOnlyHls()`, makes PlayerStore send
   `hls_only` for the Mac (Android and the web negotiate exactly as before; `hls_hevc` stays false for them).
   **HDR is not claimed**, a deliberate deviation: the frame path is 8-bit BGRA, so an HDR stream would reach the
   screen as whatever AVFoundation's 8-bit conversion makes of it; the server's tone-mapped SDR is the honest answer
   until the spike shows otherwise. `-Dravilo.hdr=true` claims what the probes allow, for that measurement.
   `detectDecoderLimits` answers *unknown* (phase 185's line).
4. **FR-R329-4.** `hls_audio_renditions = true`; an audio pick selects the audible option named `a{position}` (the
   backend's composed master, R291 — as Android finds it), else by position. The player lists the ticket's audio
   once AVPlayer is ready.
5. **FR-R329-5.** The ticket's text tracks (a `url`, not `encode`) are the player's subtitle tracks; picking one
   fetches its WebVTT (a path is resolved against the stream's own server), `parseVtt` parses it, and the surface
   draws `activeCueText` at the bottom above R251's inset, in R110's look (white, a soft ~80 % black outline) at
   Android's size — 0.0533 × 0.9 of the **picture's** height (R300) × the Subtitle size row. Burn-in stays the
   common player's R282 restream. AVPlayer's legible group is switched off.
6. **FR-R329-6/7.** Space, Return and clicks were already the player's (FocusModifiers maps Space to play/pause;
   `playerTapTogglesChrome`). A new constant, `playerArrowsSeek` (true only on the desktop), makes ← and → seek
   −10 s/+30 s instead of moving along the transport row, except in next-up, the rail and the picker. **F** and
   **Esc** (out of full screen first) are the window's, only while a film's player is on screen; a double-click on
   the picture toggles full screen (`wakeOnPointerMove`'s desktop actual). Full screen is macOS's own
   (`WindowPlacement.Fullscreen`); the player never enters it by itself (open question 2's lean), and leaving the
   player leaves it only if the player entered it. The chrome and cursor keep the shared timings (3.6 s and R157's
   2 s) rather than a Mac-only 3 s.
7. **FR-R329-8/10.** While a film is loaded, once a second: the display assertion follows *playing*, and the Now
   Playing card follows the player (title, the kicker as the artist line, the artwork fetched once per URL).
   `MacNowPlaying` gives the card to one owner at a time, so a film that stops cannot clear a song's card.
8. **FR-R329-9 — the music engine** is the Android engine ported to an audio-only AVPlayer, polled four times a
   second where the phone listens (the end, a failure, playing/buffering changes; for a book the early fetch, the
   card's chapter and the sleep timer). Songs direct-play with `mp3, flac, m4a, mp4, aac, wav` / `mp3, aac, flac,
   alac, pcm` (not Opus), and since a direct-play URL has no extension, AVPlayer is told the file's type
   (`AVURLAssetOverrideMIMETypeKey`, from the container). A book's next part is fetched early on direct play and
   played from that ticket at the boundary — on AVPlayer that shortens the gap rather than removing it. AVPlayer has
   no skip-silence: a new `playerSkipsSilence` seam hides that setting on the Mac. Settings ▸ Listening now shows
   wherever music plays off a TV (the phone, the Mac). Media keys: play, pause, toggle, next/previous (songs),
   ±30 s (books), seek.
9. **Stops reach the server on quit** (FR-R328-9 with a film): quitting first takes the app out of the window so the
   player's screen disposes and sends its stop, and a new common `TeardownWork` lets the quit wait (two seconds at
   most) for stops that were sent; the music engine registers its own. Verified on Linux: quitting from the player
   sent `playback stop` to the local server.
10. **R237 on a load-time failure.** R306's latch arms only on a clear read, so a failure this player knows at once
    (no library; a URL AVFoundation refuses) is reported from a second after the load. Verified on Linux: Play
    shows the cold start, then *Something went wrong* · Retry · Back, and Esc returns to the detail page.
11. **Tests:** `MacPlayerStateTest`, `MusicMimeTest`, `TeardownWorkTest` (ravilo-ui desktop: 276). Android, both web
    targets and the desktop app compile.
12. **Owed to a Mac:** everything that plays — the spike's numbers for 1080p H.264 and 4K HEVC, acceptance 2–6, the
    rendition switch, whether AVFoundation's 8-bit conversion of HDR is good enough to claim HDR, and the Swift
    library's first compile.
13. **First run on a real Mac (2026-09-29, the owner's MacBook Pro, macOS 27.0.1, Apple Silicon).** The Swift library
    compiled first time on `macos-15`. **1.45's `.dmg` never started a film:** R290's start latch waits for
    `hasRenderedFirstFrame` before it calls `play()`, and `AVPlayerItemVideoOutput` hands out no frame while the player
    is paused, so the cold start waited forever. Fixed in `2c36a521`: once the item is ready and no start seek is
    pending, the tick counts the first frame as reached. The same commit adds `ravilo_player_debug` (ABI 4) and a log,
    `~/Library/Logs/Ravilo/ravilo.log` (5 MB, one rotation), which writes the player's state once a second until the
    first frame, every five seconds while it buffers, and on a failure.
    **Verified by the owner on that build:** a 4K HEVC HDR film starts from Home and plays with text subtitles, a skip
    of twenty minutes buffers and carries on, the display stays awake while it plays (`pmset -g assertions`: *Ravilo is
    playing a film*), and quitting sends the stop. QoE: 0 dropped frames, 0 rebuffers, over Ethernet.
    **Finding: the Mac is served 1080p SDR.** The source was 3840×2160 HDR at 22 Mbps; AVPlayer's presentation size
    was 1920×1080 and the server transcoded (`directPlay=false`), since the Mac claims no HDR on the 8-bit path
    (item 3). Why 1080p rather than 4K SDR is not yet known. Real 4K HDR needs a different frame path (an
    `AVPlayerLayer`, or a 10-bit one) and is a phase of its own.
    **Still owed:** acceptance 1 at 4K, 3 (DTS, the rendition switch), 4's PGS, and 5 (music, the media keys,
    Control Center).

## Triage (2026-10-09, against `main` `9ea5da3c`)

- **Code: nothing found missing.** **Owed on the Mac:** acceptance 1 at 4K, 3 (DTS, the rendition switch), 4's PGS,
  5 (music, the media keys) and FR-R329-1's spike numbers.
