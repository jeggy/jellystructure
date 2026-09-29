# Phase R329 — Ravilo on the Mac plays films and music

> Owner, 2026-09-29: a Ravilo app for the Mac, its `.dmg` on every release (R331).

## Status

`Planned` — written 2026-09-29 (dev-authored) from `research-reports/ravilo-macos-desktop-app-2026-09-29.md` §3. Not
dev-reviewed. Number verified free. **Second of four** (R328 → **R329** → R330 → R331). **Builds on** R328's native
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

1. Does AVPlayer need the server to package HEVC as fMP4 HLS (`hvc1`), and does Jellyfin's HLS for an `hls_only`
   client already do so? The Chromecast path says yes for `hlsHevc`; verify in the spike.
2. Should a film play windowed first (lean) or go straight to full screen?

## Dev notes

- `ClientCapabilities` already has `hlsOnly`, `hlsHevc`, `supportsHdr10`, `supportsHlg` and `supportsDolbyVision`
  (`shared/.../Models.kt`).
- The Chromecast receiver's `capabilities()` (`ravilo-cast/.../Receiver.kt`) is the model for probe → capabilities.
