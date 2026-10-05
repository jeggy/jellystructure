# Phase R376 — Ravilo on the web gets a real video player

> Owner, 2026-10-05: *"The ravilo web doesn't have a proper video player, so it's not even possible [to switch audio].
> Let's create a spec right now to add this video player so it's properly supported on web."*

## Status

`Planned` — written 2026-10-05 (dev-authored, from the owner's ask above), checked against `main` `6ee8197c`. Not
dev-reviewed. Client only (`ravilo-ui` wasmJs actuals, `ravilo-web` boot); no server change, no wire change. Picks up
the 2026-09-18 research report's option A (`specs/research-reports/ravilo-web-pwa-player-cast-2026-09-18.md` §4.1,
"the single most valuable spike") and its §4.4 checklist, which no phase took. Supersedes **R169**'s HTML-chrome
fallback and **R157**'s DOM transport, and **R284 FR-R284-5** (web audio is a no-op); closes **R218 FR-R218-6**'s web
fallback and **R291 FR-R291-4** for the browser.

## What happens today

The web player is a `<video>` element laid over or under the Compose canvas, not the app's player:

- **The chrome is a different, smaller player.** `ravilo-web`'s `Main.kt:24` boots the deprecated
  `CanvasBasedWindow` (CMP 1.9.3; its own TODO says *migrate to ComposeViewport and rework the HTML player
  layering*). The canvas cannot be transparent there, so the video is put **above** the canvas (`RaviloPlayerWasm.kt:42–46`)
  and the visible transport is plain HTML (`PlayerChromeBridgeWasm.kt:19–110`): ⏪ ▶ ⏩ emoji buttons, two times and a
  range slider. The real Compose chrome (`HandsetPlayerChrome` / `PlayerChrome`) is composed underneath, unseen.
  **There is no audio or subtitle button a viewer can see**; the picker, next-up card, episode rail and Skip Intro
  only appear by pushing the video behind the canvas (`PlayerScreen.kt:1348–1353`).
- **The player lies about its state.** `hasRenderedFirstFrame = true`, `isBuffering = false`, `isSeeking = false`
  (`RaviloPlayerWasm.kt:176–183`) — no `waiting`/`playing`/`seeking` events are wired, so R218's cold start, stall and
  seek moments never show. `qoeSnapshot()` is empty, so R216 learns nothing from a browser.
- **Audio.** Since `22385e99` a transcode with several audio tracks gets R291's composed master and a pick selects the
  rendition (hls.js `audioTrack` / Safari `audioTracks`) — but there is no visible picker to make the pick, and it is
  unmeasured. On **direct play** (Chrome/Firefox negotiate direct play: `playsOnlyHls() = false`) a multi-audio file's
  pick does nothing at all: there is no `_hls` and Chrome exposes no `video.audioTracks` (`PlayerScreen.kt:776–778`).
- **The rest of the platform is unused.** No fullscreen from the player (only the shell's F key, hidden in standalone,
  `boot.js:65–84`), no `visibilitychange`, no Media Session metadata (handlers only, `:207–224`), no picture-in-picture,
  `containers` hard-coded to `mkv, mp4, avi, mov` for every platform (`PlayerStore.kt:186–215`), hls.js pinned at
  1.5.13 with default config.

What does work and stays: VTT subtitles as cleaned `<track>` blobs, ASS/SSA through JASSUB, Safari's in-manifest
subtitles (R265 FR-R265-8), AirPlay (R265), the capability probe (R302), `video.volume` (R354).

## Requirements

### FR-R376-1 — The video sits behind a transparent canvas; the app's own player chrome draws over it

`ravilo-web` boots with `ComposeViewport` and a **transparent** canvas (`isWindowTransparent`, available in CMP 1.9.3 —
R169's own finding). The `<video>` lives **behind** the canvas at all times; the canvas is see-through wherever the
player screen draws nothing. The web then shows **the same Compose player the phone shows** — `HandsetPlayerChrome`
on a narrow window, the desktop/TV chrome on a wide one, chosen exactly as on the other platforms — with its audio &
subtitles picker, next-up card, episode rail, Skip Intro, seek bar, lock and every other control. `setChromeVisible`'s
z-index swap and `PlayerChromeBridgeWasm` are **deleted**, not kept as a fallback. Pointer events reach the canvas;
the `<video>` never takes input.

**Gate (first, before anything else in this phase):** a spike proving that under `ComposeViewport` keyboard and
gamepad input, R281's hidden-input text bridge (`TextFieldFocusBridge.kt`), touch scrolling and the R263 install
shell all still work, on Chrome (desktop + Android) and Safari (macOS + iPhone, iOS ≥ 18.2). If the spike fails, this
phase stops and says why in its build notes — it does not fall back to growing the HTML chrome (R244 forbids that).

### FR-R376-2 — The player tells the truth about its state

`hasRenderedFirstFrame`, `isBuffering`, `isSeeking`, `isPlaying`, `isEnded` and `playbackFailed` come from the
element's own events (`loadeddata`/`playing`, `waiting`/`stalled`, `seeking`/`seeked`, `play`/`pause`, `ended`,
`error`) and hls.js's (`ERROR` with `fatal`), so R218's three moments (cold start, stall, seek) show on the web exactly
as on Android, with R218's 400 ms debounce and its strings — no new copy.

### FR-R376-3 — Audio switches the way R291 chose, on every stream the web gets

- **A transcode** (hls.js or Safari native): R291's composed master; a pick selects the rendition `a{position}`, the
  picture keeps playing. Measured in Chrome and Safari before this phase is `✓` (R291's open browser measurement).
- **Direct play of a file with two or more audio tracks**: the browser cannot switch tracks inside one file (Chrome and
  Firefox expose no `audioTracks`), so the web **asks for HLS** for such a file — `ClientCapabilities` gains nothing
  new; the web sends `hls_only = true` when the item has more than one audio track (the ticket's track list is known
  before the start), and gets R291's master. A single-audio file keeps direct play.
- `selectAudioTrack` reports whether it switched; when it could not, PlayerScreen restreams (R284) instead of doing
  nothing silently.
- The picker lists what the stream actually carries (hls.js `audioTracks` / Safari's), labelled with the server's
  names by position — never a track the stream does not have.

### FR-R376-4 — Fullscreen, orientation and the background, as a browser allows

- **Fullscreen:** the player enters fullscreen on the **page element** (`document.documentElement.requestFullscreen()`,
  not the `<video>`, so the Compose chrome stays on top) when playback starts in a browser tab, and leaves it on exit.
  In an installed app (standalone) there is nothing to enter. **iPhone:** no `requestFullscreen` on non-video elements
  and **never** `webkitEnterFullscreen` (it hands the picture to iOS's own player and loses the chrome) — the
  standalone app is already full screen.
- **Orientation:** on Android, `screen.orientation.lock('landscape')` while a film plays in fullscreen or standalone,
  unlocked on exit; where the lock is refused, nothing is said (R244's rotate rule applies).
- **Background:** on `visibilitychange` to hidden the player pauses and reports progress (the same stop/progress path
  Android's `ON_STOP` takes); on return it does not auto-resume.
- **Media Session:** `navigator.mediaSession.metadata` gets the title, the series/episode line and the artwork, so the
  OS's media controls and lock screen name what plays (the research report's owner check — **asked below**).

### FR-R376-5 — What the web declares is what it can play

`containers` on the web is probed, not hard-coded: `mp4` always; `mkv`/`webm` only where `MediaSource.isTypeSupported`
or `canPlayType` says so (Safari: mp4 only). A container the browser cannot open is transcoded instead of failing.

### FR-R376-6 — Quality reporting

`qoeSnapshot()` reports what a browser can know: dropped and decoded frames
(`video.getVideoPlaybackQuality()`), stalls (count and total time from FR-R376-2's `waiting`), and hls.js's chosen
level / bandwidth estimate where hls.js plays. R216's server reads it as from any other client.

### FR-R376-7 — hls.js current and configured

hls.js is updated to the current 1.x (self-hosted, `vendor/NOTICE.md` updated, CSP unchanged) and configured for the
household: `enableWorker`, a back-buffer bound, `startPosition` from the ticket, and Managed Media Source on iPhone
where hls.js is chosen over native HLS. **Native HLS stays first on Safari** (AirPlay needs it, R265).

### FR-R376-8 — Picture-in-picture where the browser has it

A *Picture in picture* control in the player's ⋯ (desktop Chrome/Safari, Android Chrome): `video.requestPictureInPicture()`;
hidden where unsupported (absent, never greyed). Uses R244's existing string set plus one new string `player.pip`
(en/da/fo).

## Non-goals

- Changing the Android, TV or desktop players.
- A web-only chrome. The point is that the web shows the same player.
- Thumbnail scrub previews (`trickplayUrl` is null on every platform).
- Offline playback, downloads.

## Acceptance

1. **The gate:** the FR-R376-1 spike passes on Chrome desktop, Chrome Android, Safari macOS and Safari iPhone (standalone
   PWA) — keyboard, gamepad, text input, touch scroll and install all still work.
2. On ravilo.jebster.net (Chrome desktop) a film shows the Compose player chrome over the picture: the audio &
   subtitles picker opens over the playing video without the video disappearing.
3. Toy Story (eight audio tracks): picking another language in Chrome changes the sound within ~1 s with no restart
   and no black frame; the same on Safari macOS and the iPhone PWA.
4. A direct-playable single-audio file still direct-plays; a multi-audio file is negotiated as HLS with renditions.
5. A throttled network (DevTools *Slow 4G*) shows R218's stall treatment; a seek shows its seek spinner.
6. Fullscreen on start in a Chrome tab, exit on Back; Android rotates to landscape; the iPhone PWA plays full screen
   with the chrome on top.
7. Hiding the tab pauses and reports progress; the OS media controls name the title.
8. R216's QoE rows arrive from a browser session with frame counts.

## Tests

- wasmJs unit tests for the event → state mapping (FR-R376-2), the rendition lookup by name, the container probe,
  and the multi-audio → `hls_only` decision.
- The existing e2e suite (`reference-e2e-mock-stack`) gains: the player screen renders Compose chrome with a video
  behind it; the picker opens without hiding the video.

## Open questions

1. **Media Session metadata on the web** (the research report's owner check): the browser shows the title and art on
   the OS's media controls and the lock screen. Lean: yes, the same as Android TV's R193 rule allows for a phone.
2. **iPhone: native HLS or hls.js on Managed Media Source?** Lean: native (AirPlay, battery), hls.js only if a
   rendition switch does not work natively.
3. **Should the web ever direct-play?** Forcing HLS for everything would make every browser path one path, at the cost
   of a transcode for files a browser could play. Lean: no — only multi-audio files (FR-R376-3).
