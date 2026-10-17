# Phase R376 — Ravilo on the web gets a real video player

> Owner, 2026-10-05: *"The ravilo web doesn't have a proper video player, so it's not even possible [to switch audio].
> Let's create a spec right now to add this video player so it's properly supported on web."*

## Scope

The FR-R376-1 gate passed in headless Chromium (2026-10-05) and Safari 27 on the owner's Mac boots it (2026-10-08).
Written 2026-10-05 (dev-authored, from the owner's ask above), checked against `main` `6ee8197c`. Client only
(`ravilo-ui` wasmJs actuals, `ravilo-web` boot); no server change, no wire change. Picks up the 2026-09-18 research report's option A
(`specs/research-reports/ravilo-web-pwa-player-cast-2026-09-18.md` §4.1, "the single most valuable spike") and its
§4.4 checklist, which no phase took. Supersedes **R169**'s HTML-chrome fallback and **R157**'s DOM transport, and
**R284 FR-R284-5** (web audio is a no-op); closes **R218 FR-R218-6**'s web fallback and **R291 FR-R291-4** for the
browser. Amended 2026-10-17: Play fills the tab, and full screen in a tab is the viewer's own click (FR-R376-4a–4e,
DEB-1671 — see the amendment at the end).

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

> **Amended 2026-10-17 (DEB-1671).** The *Fullscreen* and *Orientation* bullets below are replaced for a browser tab
> by FR-R376-4a–4e in the amendment at the end of this file: Play no longer enters full screen. *Background* and
> *Media Session* are unchanged.

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
3. A film with eight audio tracks: picking another language in Chrome changes the sound within ~1 s with no restart
   and no black frame; the same on Safari macOS and the iPhone PWA.
4. A direct-playable single-audio file still direct-plays; a multi-audio file is negotiated as HLS with renditions.
5. A throttled network (DevTools *Slow 4G*) shows R218's stall treatment; a seek shows its seek spinner.
6. Fullscreen on start in a Chrome tab, exit on Back; Android rotates to landscape; the iPhone PWA plays full screen
   with the chrome on top. *(Replaced 2026-10-17 by the amendment's acceptance, DEB-1671.)*
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

## Build notes

Built 2026-10-05 on branch `r376-web-player` (worktree `~/IdeaProjects/jellystructure-r376`). Not merged, not
deployed, no device touched.

### The gate (FR-R376-1)

Tested in headless Chromium 149 (Playwright's build, `--use-angle=swiftshader`; the default GL path skips Skia's
draws in this headless shell for want of a stencil buffer, with or without this phase) against the production
`wasmJsBrowserDistribution` served by a throwaway Node server that mocks `/api/tv/*` and sends web-static-server's
CSP. Media: an ffmpeg test pattern as a direct-play WebM, a three-audio MKV, and HLS in the backend's composed-master
shape (TS main variant with the muxed `a0`, audio-only `a1`/`a2` renditions).

- **Passed:** typing into the login fields (incl. `æ`, by `keyboard.type` and by `insertText` the way a soft keyboard
  delivers it; the request body carried exactly what was typed), keyboard D-pad focus and Enter, the gamepad path
  (synthetic keys on the viewport's canvas), mouse-wheel and touch scrolling of Home, the install card and the
  service worker. Baseline: the same flows on the current `main` bundle behave the same.
- **The decisive part:** the Compose chrome (TV/desktop chrome in a 1280×720 window, the handset chrome under Pixel 7
  emulation) draws over a playing `<video>`, and the audio & subtitles picker opens over it while it keeps playing
  (screenshots in the lab, `currentTime` advancing under the open picker).
- **Unverified:** Safari macOS, Safari iPhone (standalone PWA, iOS ≥ 18.2), Chrome on a real Android phone, a real
  gamepad, a real on-screen keyboard. No WebKit run was possible on this host (Playwright's WebKit lacks system
  libraries here).

**How transparency was actually reached (deviation from the spec's wording).** `isWindowTransparent` is not a
viewport switch in CMP 1.9.3 — it is a `PlatformContext` flag for dialog scrims — and skiko 0.9.22.2's web
`CanvasRenderer` clears the canvas to **opaque white** every frame. The WebGL context does have alpha
(`alpha: 1, premultipliedAlpha: 1`), so the player screen punches its own hole: the wasmJs `PlayerVideoSurface` draws
a `BlendMode.Clear` rect, writing zero alpha into the canvas, and everything drawn after it (shutter, scrim, chrome,
picker, toasts) paints over the picture. The `<video>` is fixed at `z-index: 0` under `#ComposeTarget` (`z-index: 1`)
for its whole life. ComposeViewport was still required: it is what gives a focused text field a real DOM input.

**ComposeViewport consequences.** `#ComposeTarget` is now a `<div>`; the canvas lives in its open shadow root.
boot.js finds it there for the gamepad path and focuses it on mount. The viewport's own backing input (inside the
shadow root) is what a phone raises its keyboard for, so **R281's hidden `#ravilo-kb-bridge` input is removed**
(focusing it would steal focus from the viewport's input); `TextFieldFocusBridge` keeps only the F-key guard. One
regression found and fixed: when a text field closes its input session the viewport removes the focused input and
focus fell to `<body>`, where no key reached Compose (the D-pad was dead on Home after signing in) — boot.js now hands
focus back to the canvas whenever it falls to nothing. `isA11YEnabled = false` keeps CanvasBasedWindow's behaviour
(no second DOM tree); turning accessibility on is its own decision. `setPointerCursorHidden` also sets the shadow
canvas's own cursor.

**Deleted:** `PlayerChromeBridge` (common, Android, desktop, web), `RaviloPlayer.setChromeVisible` and the z-index
swap, the `videoBehindCanvas` effect in PlayerScreen, and R354's DOM toast cards on the web
(`platformServerMessageOverVideo()` is null everywhere now; `VideoOverApp` stays as an unused seam).

### FR-R376-2 — state

The `<video>` queues `loadstart/loadeddata/playing/canplay/waiting/stalled/seeking/seeked/play/ended/error` (and
hls.js's unrecoverable error as `hlsfatal`) with `performance.now()`; every getter drains the queue into
`WebPlaybackEvents` (commonMain, pure), which yields `firstFrame/buffering/seeking/ended/failed` and the stall count
and time. `stalled` is queued only while `readyState < 3`. A fatal media error is recovered once
(`recoverMediaError`). Seen in the lab: segments delayed by Playwright → R218's moment C (chrome up by itself,
spinner in the play button); a +30 s skip into unbuffered media → moment D (spinner by the time).

### FR-R376-3 — audio

- `RaviloPlayer.selectAudioTrack` now returns `Boolean` (Android and the desktop always `true`, their behaviour
  unchanged). The web answers from the stream: a rendition named `a{position}` → selected; a pick before hls.js or
  Safari has read the manifest → held and applied on `AUDIO_TRACKS_UPDATED` / `loadedmetadata`; nothing to switch →
  `false`, and PlayerScreen restreams with that track (once per item for the resolver; on every manual pick) through a
  new `restreamForAudio` and `bk.ticketAudio`.
- The picker lists the stream's renditions labelled with the server's names by position
  (`audioTracksFromRenditions`); while a master's renditions are not read yet the list is empty, so R181's resolver
  waits for the real one.
- `switchesHlsAudioRenditions()` is **on** in every browser with MediaSource or native HLS. **Measured in headless
  Chromium + hls.js 1.7.3:** picking *Dansk* fetched the first `a1` segment 33 ms after the pick, `currentTime` kept
  advancing, no new start/restream request. **Not measured on Safari** (native `audioTracks`); a pick Safari cannot
  make reports `false` and is restreamed.
- Multi-audio direct play: PlayerStore takes the decision on the first ticket (the client has no track count before
  it): `asksHlsForAudio(directPlay, audio.size, hlsOnly, switchesAudioInFile())` → a second `playback/start` with
  `hls_only = true`, and `lastCapabilities` keeps it for restreams. Seen in the lab: MKV with three tracks → second
  start with `hls_only` → composed master with `a0 English`, `a1 Dansk`, `a2 Føroyskt`. Deviation: the first
  negotiation is wasted (one extra start request per multi-audio title on the web); the server's session supersede
  covers it.
- Chrome on Android plays HLS natively but exposes no audio tracks, so only Safari takes native HLS
  (`WebKitPlaybackTargetAvailabilityEvent` present, or no MediaSource at all); every other browser uses hls.js.

### FR-R376-4 — fullscreen, orientation, background, Media Session

- `PlayerImmersiveEffect` (wasmJs) enters fullscreen on `document.documentElement` when the player opens in a tab
  (not standalone, not already fullscreen, `fullscreenEnabled`), and leaves on exit only a fullscreen it entered.
  Android (UA) locks landscape after fullscreen or in standalone and unlocks on exit, silently on refusal. Never
  `webkitEnterFullscreen`. The shell's Fullscreen button hides while the player is up (`body.rv-playing`) — it sat
  over the chrome's bottom-right controls. Lab: fullscreen on start and off after Back, desktop and Pixel 7 emulation;
  the orientation lock is unverified (emulation stays portrait).
- `PlayerLifecycleEffect` (wasmJs): page hidden → pause and Android's `onBackground` path with `wasPlaying = false`
  (resume record, `playback/stop` with the position); visible again → `onForeground` (a fresh start at the record's
  position) without auto-resume. Not while in picture-in-picture or on AirPlay. Lab: stop at 10 362 ms, the return
  start at 10 362 ms, paused.
- Media Session metadata: title, the kicker line, the artwork URL (open question 1 taken at its lean).

### FR-R376-5 — containers

`supportedContainers()` (new expect; Android/desktop return the old list): the web sends `mp4`, plus `mkv` when
`canPlayType`/MediaSource answer for `video/x-matroska` (with or without codecs) and `webm` when VP9/Opus WebM plays
and the browser is not Safari. Chromium 149 headless answers `mp4,mkv,webm`. Published as `window.__raviloContainers`.

### FR-R376-6 — QoE

`droppedFrames` from `getVideoPlaybackQuality()`, `rebufferCount`/`rebufferMs` from FR-R376-2's stalls (a stall still
running counts up to the report), `bandwidthEstimateBps` from hls.js. The level hls.js chose and the decoded frame
count ride in `videoDecoder` (`web hls.js · 640x360 600 kbps · 262 frames decoded`) — no wire field added. Lab: QoE
rows arrive on the stop.

### FR-R376-7 — hls.js

1.5.13 → **1.7.3** (`hls.min.js` + `hls.worker.js`, registry tarball, integrity checked, unmodified; NOTICE updated).
`enableWorker` with `workerPath: 'vendor/hls.worker.js'` — the CSP's `worker-src 'self'` refuses the blob: worker
hls.js would build otherwise, so the CSP is unchanged; `backBufferLength: 90`, `maxBufferLength: 30`,
`startPosition` from the ticket, `preferManagedMediaSource: true`. Native HLS first on Safari.

### FR-R376-8 — picture-in-picture

The player has no ⋯ menu on any platform, so the control sits where the chrome's other secondary controls are: a
*Picture in picture* pill in the TV/desktop transport row (`PlFocus.PIP`, after Next, before the TV's Speakers) and a
glyph beside the cast glyph in the handset top bar. Both only when `document.pictureInPictureEnabled` and the element
allows it (`pictureInPictureAvailable()`, false on Android/desktop); absent otherwise. String `player.pip` in
en/da/fo. The lab showed the control; entering PiP was not exercised (headless).

### Tests

- `WebPlaybackTest` (commonTest, run on the desktop JVM — this module has no browser test runner, Phase 198): the event
  → state mapping (cold start, stall with its length, a seek's buffering, repeated waits, new source, fatal errors),
  rendition lookup by name and the picker list, the container probe, the multi-audio → `hls_only` decision.
- `ravilo-web.spec.ts`: R281's keyboard test rewritten for the shadow root; a new boot test (canvas in the shadow
  root at z-index 1, containers probed). Not run here (the e2e stack is not on this host's path for this branch).
- Not added: the spec's e2e for *the player renders Compose chrome with a video behind it* — the mock stack has no
  playback endpoints or media; the same checks were run in the lab harness instead.

### Open / unverified

Safari (macOS, iPhone PWA): native rendition switching, the held pick, `playsinline` playback with the chrome on top,
Media Session on the lock screen. Android Chrome: orientation lock, the soft keyboard on the viewport's input, PiP.
Real gamepads. Acceptance 2–3 on ravilo.jebster.net with a film with eight audio tracks (no deploy).

### Rebased onto `main` (2026-10-08)

Merged into a fresh branch from `main` `83ee8db9` (after R381, 316, 315, 311, 309a0, R382, 310/312). Five conflicts:
- **hls.js (dev review item 1):** the union — R376's worker (`vendor/hls.worker.js`), `backBufferLength 90`,
  `startPosition`, Managed Media Source, fatal-error recovery and the held audio pick, **plus 308's**
  `maxBufferLength 60`, `maxBufferSize 300 MB`, `abrBandWidthFactor 0.7` / `abrBandWidthUpFactor 0.5`, the measurement
  seed (`abrEwmaDefaultEstimate`) and the `LEVEL_SWITCHED` counters. The branch's `maxBufferLength 30` is dropped.
- **`RaviloPlayer`:** keeps `seedBandwidthEstimate` (308) and R376's `selectAudioTrack(): Boolean`.
- **`PlayerScreen`:** keeps main's cast play context and R376's picture-in-picture control.
- **`STATUS.md` / this spec:** both kept.

Built on top, from the review and the owner's decisions:
- **FR-R376-3 changed (owner, 2026-10-08): direct play first.** The start no longer re-asks a multi-audio file as HLS
  (`asksHlsForAudio` removed). An audio pick the browser cannot make inside the file (`selectAudioTrack` false, i.e.
  Chrome/Firefox on a direct play) restreams through R284 with `hls_only` set (`audioPickNeedsHls` in `WebPlayback.kt`,
  applied in `PlayerStore.restreamWithSub`); the item stays on HLS for its later restreams. Safari switches inside the
  file (its element has `audioTracks`).
- **R381 on the web:** the web's QoE stalls are now `QoeCounter`'s (per item via `beginQoeItem`, only after the item's
  first frame, never inside a seek, a track switch, a variant switch or after a pause). `WebPlaybackEvents` feeds it
  from the same element events (new `pause` and hls.js `variant` events); `qoeSnapshot` reports `per_item`, the stalls,
  the waits and the session totals with R376's dropped frames and 308's variant fields.
- **FR-309-13 on the web:** hls.js's bandwidth is reported only once its estimator rests on real fragment samples
  (`bwEstimator.canEstimate()`); before that it is its default or 308's seed, never a measurement.
- **Time to first frame (review item 3):** PlayerScreen's start timer already reads `hasRenderedFirstFrame`, which on
  the web is now the element's own `loadeddata`/`playing` — the old constant made every web start read "1 s".
- **Safari's quality (review item 4):** native HLS picks its own variant; the start rung is 308's first listed variant
  (`startOrder`). Noted for 309.
- The acceptance no longer names a library title.

Tests: `WebPlaybackTest` (desktop JVM) — the new `audioPickNeedsHls` rules and three `QoeCounter`-on-the-web cases
(a stall only after the first frame and per item; a seek, a track switch, a variant switch and a pause are never
stalls; a restream of the same item keeps its counts). Full runs: `:shared:desktopTest` 128, `:ravilo-ui:desktopTest`
574, `:ravilo-ui:testDebugUnitTest` 710 — 0 failures; `:ravilo-ui` wasm/desktop/Android and `:ravilo-web` compile;
`wasmJsBrowserDistribution` builds. Every `scripts/check-*.sh` passes; the four Android APK checks (http engine,
min SDK, Play device filter, player dex) need a release APK and were not run here. (`:shared:allTests` fails on its own
test targets without `kotlin-test` — pre-existing on `main`, not this phase.)

### Live: Safari 27 on the owner's Mac (2026-10-08)

The branch's `wasmJsBrowserDistribution`, served from this host on a throwaway Node server that proxies `/api` (HTTP
and the events WebSocket) to the dev backend on the same origin (so the backend's CORS allowlist never applied), with
a script in `index.html` signing the browser in as the Test Stream account. Safari was opened with `open -g` (behind the
owner's apps; their front app never changed) and quit afterwards; the session was wiped from Safari's storage first.
- **Boots:** the Compose canvas in `#ComposeTarget`'s shadow root, the config, Home, Discover, upcoming, Live TV and
  facets fetched (all 200), the hero's artwork loading as the carousel turned; no `error` or `unhandledrejection`.
- **What Safari offers the player:** native HLS `maybe`, H.264 and HEVC `probably`, MediaSource and Managed Media
  Source, `WebKitPlaybackTargetAvailabilityEvent` (so FR-R376-7 takes native HLS), WebGL 2, picture-in-picture, the
  element's `audioTracks`.
- **Not run:** a play. Synthetic keys reached the canvas but a background Safari window has no key focus (and its
  timers are throttled), so nothing opened; bringing Safari to the front would have taken over the owner's screen.
  The events WebSocket reconnected every few seconds through the throwaway proxy, most likely the proxy's own upgrade
  handling; to be watched on a real deploy.
- WebKit in Playwright could not run on this host (missing system libraries), and this host's headless Chromium had no
  WebGL this time, so the play checks still need the Mac in front, the iPhone PWA and a real Android phone.

### Live: Safari 27 in front on the owner's Mac (2026-10-08, evening)

The owner allowed taking over the Mac's screen. The same throwaway same-origin proxy as above (it had to rewrite
`Origin` to the web app's real address: the backend refuses the events socket and every state-changing request from an
unknown origin, which was also why the socket "reconnected every few seconds" in the earlier run — the proxy, not the
app). No accessibility or screen-recording permission over ssh and no `cliclick`, so the page drove itself: a lab
script injected into `index.html` logged the element's events, its `audioTracks`, the app's console and a play() wrapper
to the proxy, and pressed keys on the Compose canvas (synthetic, so never a user gesture). Plays were started the way
a phone or the admin starts one on a screen: `POST /api/remote/play` to the Test Stream web device; seeks and the stop
by `POST /api/remote/command`. Test Stream account only; both films were marked unplayed afterwards (Jellyfin shows
position 0, play count 0, no last-played date), Safari's storage was wiped and Safari quit.

| Check | Result |
|---|---|
| **Direct play on Safari** (before the owner's decision below) | **Never happened:** R265 FR-R265-8 (`playsHlsForAirPlay`) makes Safari ask for HLS only, so even an MP4 Safari can open (an open-licence test film, H.264 + MP3 + AC3) came as R291's composed master (video copied, two audio renditions). FR-R376-3's *direct play first* is therefore Chrome/Firefox only. **For the owner:** keep HLS-only on Safari for AirPlay, or direct-play there and switch to HLS when AirPlay is picked. |
| **Time to first frame** (element `loadstart` → `loadeddata`) | MP4 film, cold: **8.5 s** (playing at 10 s after the push); the same film warm: **4.0 s** (playing at 5.7 s). An MKV with four AC3 tracks (needs HLS), cold: first frame data **1.6 s**, but `playing` only **29.5 s** after `loadstart` (the cold start of Jellyfin's job and R291's four rendition jobs on a file not in the page cache). |
| **Audio switch inside the stream** (Safari's own `audioTracks`, via the app's picker: wake, → → to *Audio & subtitles*, OK, ↓ to Finnish, OK) | **Works:** the element's enabled track moved from `dan` to `fin` at the same moment, no restream. The picture held at the same second for **~7 s** before playing on (the new language's rendition job starting cold); afterwards it played normally. Exactly one track is enabled before and after. |
| **Seek** (remote command to 5:00) | `seeking` → `seeked`/`playing` in **5.8 s** (Jellyfin restarts its job at the new position). |
| **Multi-audio title** | Safari lists the four renditions by language (`dan`, `fin`, `nor`, `swe`, named *Danish – Dolby Digital* …); the picker shows them as four languages. |
| **Sound** | The owner heard nothing during the first runs: **the lab script muted every element on purpose** (to keep the Mac quiet). Without it, **Safari refused the start with sound** (`play()` → `NotAllowedError`: a play pushed by the server, like one started after the ticket's fetch, is not a user gesture), and the element sat paused at 0:00. **Fixed** (below). The system output was not muted (13–25 %). The audio itself is AC-3 in HLS, which Safari on macOS decodes; the composed master's renditions are copies, not AAC. |

**Fixed on this branch: a play the browser refuses with sound plays muted and says how to get the sound.**
`playVideo` (wasmJs) catches `NotAllowedError`, sets the element muted and plays again (allowed), queues
`soundblocked`, and unmutes on the viewer's next **trusted** click, tap or key (`pointerdown`/`keydown`/`touchend`,
capture phase, `isTrusted` only), queueing `soundon`. `WebPlaybackEvents.soundBlocked` carries it (surviving a new
source, since the element stays muted until then; the muted retry counts as the load's start, never a stall), the
player exposes it as `RaviloPlayer.soundBlockedByBrowser` (false on Android and the desktop), and `BrowserSoundHint`
shows one pill at the top of the player: *Click or press a key for sound* (`player.sound_blocked`, en/da/fo).
Re-tested on Safari: the refused play retried muted and was playing in 0.7 s. **Not verified:** that a real click
brings the sound back — that needs a trusted click, which nothing over ssh can produce (and nobody clicked during the
test window). Tests: `WebPlaybackTest` (the blocked fact survives a new source and clears on `soundon`; a refused
play is never a stall).

### Safari: direct play, HLS only for AirPlay (owner, 2026-10-08)

Owner, after the Safari run above: *Safari (Mac and iPhone) direct-plays what it can, and switches to HLS only when
AirPlay is picked* — changing R265 FR-R265-8 for Safari, keeping AirPlay working.
- **The start** (`PlayerStore`): `hls_only`/`hls_subtitles` for Safari only when the picture is already on AirPlay
  (`startsAsAirPlayHls(playsHlsForAirPlay(), platformAirPlay.wireless)` — e.g. the next episode of an AirPlay
  session). Otherwise Safari sends the same probed containers and codecs as any browser (Safari: `mp4`), so an MP4 it
  decodes direct-plays and anything else gets the ladder/transcode exactly as before.
- **Picking AirPlay** (`AirPlayHlsRestart`, one call in `PlayerScreen`): when WebKit reports the wireless target and
  the ticket is a direct play, the same item restarts once (`airplayNeedsHls`) through
  `PlayerStore.restreamForAirPlay` at the current position, keeping the burned-in subtitle and the audio track, with
  `hls_only`, `hls_subtitles` and no HEVC (`airplayCapabilities`); the item stays on HLS for its later restreams. The
  `<video>` keeps its AirPlay target across the new source.
- Android and the desktop have no AirPlay (`platformAirPlay` is null): nothing changes there.
- Tests (`WebPlaybackTest`): the start rule (Safari on the Mac's screen → no HLS; already on AirPlay → HLS; Chrome
  never), the restart rule (only a non-HLS stream, once per item, only on AirPlay), and the restart's capabilities.
- **Live on Safari 27 (2026-10-08, Test Stream, against the dev backend v1.50 with 313's encoder):** the start sent
  no `hls_only` (`containers: ["mp4"]`, the probed codecs). A 1080p H.264/AAC MP4 film **direct-played**
  (`/Videos/…/stream`, `directPlay=true`): first frame **1.55 s** after `loadstart`, playing at 1.7 s (HLS on the same
  Safari: 4–8.5 s), and a remote seek to 10:00 resumed in **0.24 s** (HLS: 5.8 s). The play had no gesture, so the
  muted fallback above engaged as designed. A 4K H.264 MP4 still goes through the encoder: the web reports no decoder
  limits, so the profile's H.264 default of 1080p applies (Safari on a Mac decodes 4K H.264 — a decoder-limit probe for
  Safari would let it direct-play; not done here). **Not verified:** picking AirPlay (Apple's picker needs a real tap,
  and a target the test could not choose) — the restart rule is covered by its tests only.
- Release APK checks after this change: `check-player-dex` 246 registers (limit 250), and the HTTP-engine, min-SDK and
  Play device-filter checks pass.

## Dev review (2026-10-08, against `main` `fde62635`)

Read against branch `r376-web-player` (2 commits, built 2026-10-05, **not merged, 35 commits behind `main`**; its own
copy of this spec says `⚠ Partial` with build notes, so this file on `main` is stale), `RaviloPlayerWasm.kt` on both,
308/309 as they now stand, and 30 days of `playback_start_sample` (web: 21 starts, all ~1 s, all direct play). The
design holds and the risky gate (FR-R376-1) passed in Chromium. This review is about what merging it would do to
streaming. Six items, two for the owner.

1. **Merging the branch as it is would undo 308 on the web.** The branch rewrote hls.js's construction as
   `{ enableWorker, backBufferLength: 90, maxBufferLength: 30, startPosition }`. `main`'s (308: `1e10108d`, `c485da67`)
   is:
   - `maxBufferLength: 60`, `maxBufferSize: 300 MB`;
   - `abrBandWidthFactor 0.7` / `abrBandWidthUpFactor 0.5`;
   - the measurement seed `abrEwmaDefaultEstimate`;
   - the `LEVEL_SWITCHED` QoE counters.

   Both changed the same block, so the merge must take the union: R376's worker, back-buffer and `startPosition` plus
   308's buffer, ABR, seed and QoE. It must also later take 309's one-rung climb (hls.js `autoLevelCapping` raised one
   level per switch) and FR-309-13's rule that hls.js's default estimate is not a measurement (report none until
   `bwEstimator` has real samples). A rebase that keeps the branch's block silently halves the web's buffer and
   removes its ABR tuning.
2. **FR-R376-3 makes starts slower for multi-audio files.** Today every web start is a direct play at about 1 s.
   Forcing `hls_only` for any file with two or more audio tracks turns those into a Jellyfin job: a remux at best, a
   transcode at worst, with the 8–38 s cold start the 309 review measured for transcodes. Most films carry several
   audio tracks, so this would make the common case slower, against the owner's "no waiting" goal. Better: **start
   with direct play, and switch to HLS only when the viewer picks a non-default track**, using R284's restream at the
   current position (a one-off wait, at the moment the viewer asked for something). **For the owner, Q1.**
3. **FR-R376-2 and FR-R376-6 are what 309 needs from the web.** Real `waiting`/`playing` events give stall truth, and
   `qoeSnapshot()` gives frames and stalls. Add **time to first frame** (`loadeddata` minus load) as 309 FR-309-11 asks
   of every player. The web currently reports none, so web starts could not be compared.
4. **Native HLS on Safari (FR-R376-7) means 309's climb rules can't be enforced there.** AVPlayer chooses variants
   itself. 309's AVPlayer rule (`preferredPeakBitRate`) has no web equivalent, so on Safari the start rung must be the
   first listed variant (308's `startOrder`, already built). Climbing stays Safari's own. Say so in 309.
5. **Priority.** The owner's targets for "no buffering" are Android, TV and Chromecast. The web is a secondary
   surface: merge this after 310, 312 and R266, not before. It shares no code with those, so it can go out alone.
6. **Before merging:** rebase (item 1's union), then run the open acceptance on Safari macOS (the owner's Mac) and the
   iPhone PWA. Neither was possible on this host.

**For the owner:**
- **Q1 — Multi-audio files on the web:**
  - (a) direct play first, switch to HLS only when another audio track is picked **(lean: keeps the 1 s start)**;
  - (b) as written, HLS from the start for any multi-audio file (switching is instant, the start is slower).
- **Q2 — When to merge:** (a) after rebasing with item 1's union and a Safari check on the Mac **(lean)**; (b) wait for
  the iPhone PWA too; (c) park it behind 310/312/R266.

## Decided by the owner (2026-10-08, after the streaming re-review)

1. **A file with several audio tracks (Q1): direct play first; switch to HLS only when another audio track is
   picked** (changes FR-R376-3).
2. **Merge (Q2): after a rebase that keeps 308's hls.js settings (and 309's later rules), and a Safari check on the
   Mac.** After 310, 312 and R266 in the order. See `specs/research-reports/ravilo-streaming-plan-2026-10-08.md` for the whole order.

## Owner, 2026-10-09: sound on the first click in Safari

> *"Why is the Safari sound only working after a click and not right away?"* → *"yes"* to fixing it.

**FR-R376-S1 — Unlock the player inside the click.** Safari (macOS and iOS) allows audible playback only when
`play()` is called within a user gesture. Ravilo's Play click first fetches the stream ticket from the server, and the
`play()` that follows is no longer inside the gesture, so Safari starts it muted (today's fallback shows *"Click or press
a key for sound"*). Fix: the Play click (and every other control that starts a play: a resume card, *Next episode*, a
row's play button) synchronously calls `play()` on the **one shared `<video>` element** — muted-false, with no source or
a tiny silent placeholder, catching the rejection — before any network call; when the ticket arrives the same element
gets its source and plays with sound. The element is kept for the whole page lifetime (never recreated per item), so
auto-advance to the next episode keeps the unlocked state. The muted fallback and its pill stay for the cases where no
gesture happened (a cast handing playback to the browser, a restored session).

**Acceptance:** in Safari on the Mac and the iPhone home-screen app, a real click on Play starts the film **with sound**
and no pill; the next episode auto-advances with sound; a seek/track switch keeps sound. **Tests:** the click handler
calls `play()` before any suspension point (a unit test on the web player seam with a fake element recording call
order); one element across two items.

## Live results after the integration deploy (2026-10-09, v1.50-105 → v1.50-106)

Deployed with `ravilo-web` rebuilt from `7ba7ec0c`/`73d15191`. The owner tested Safari on the Mac the same morning:
**sound, seek and next episode all work**.

### Build notes — FR-R376-S1 (2026-10-09, branch `r376-safari-first-click`, not merged, not deployed)

- **One `<video>` for the page's lifetime.** `SharedVideo` in `RaviloPlayerWasm.kt` creates it once (hidden,
  `playsinline`, `pointer-events:none`) and `prepareWebVideo()` creates it at boot (`ravilo-web` `Main.kt`). Every player
  takes it over (`SharedElementOwner`); `release()` resets it only if this player still owns it, so a late release of
  the previous player cannot blank the next item. AirPlay binding is idempotent on the shared element.
- **The unlock runs in the gesture itself.** A capture-phase `window` listener on `pointerdown · mousedown · pointerup ·
  mouseup · click · touchend · keydown` (`WebSoundUnlock.gestureEvents`) calls `play()` then `pause()` on the element,
  unmuted, **synchronously inside every trusted event while the element holds no film**. That way no click handler
  anywhere in the app has to remember to call it: Play, Resume, a row's play button and *Next episode* are all covered.
  WebKit lifts the element's gesture restriction for good on the first such call. The element's own play/pause events
  from the unlock are muted out of the event queue (`_rvQuiet`), and the next real source clears that (`prepareForSource`,
  which also unmutes and shows the element). "Holds no film" means the element's `networkState` is `NETWORK_EMPTY`, not
  that `currentSrc` is empty: Firefox keeps the last film's `currentSrc` after the reset (measured).
- **The muted fallback stays** for a play with no gesture before it (the *Click or press a key for sound* pill).
- **Fixed on the way (live, Opera):** an audio pick on a direct play restreams to HLS (R284). PlayerScreen calls `play()`
  straight after `load()`, but on the page's first HLS stream hls.js is still loading (`vendor/hls.min.js`, lazy), so the
  new source was attached **after** that `play()` and the load algorithm left it paused: the film stopped on the pick,
  with no error. Now the element remembers a `play()` asked for during the swap (`_rvWantPlay`), and the swap plays
  itself once the source is attached (hls.js `MEDIA_ATTACHED`, or the native fallback). A new item's `load()` clears the
  wish, so R290's prepare-then-play is unchanged, and a viewer's pause clears it too. After the fix: playing at the pick's
  position with sound, 1.76 s after the pick.
- **Tests** (`WebPlaybackTest`): a trusted click unlocks; a scripted event never does; a click during a film never does;
  `pointermove`/scroll are not gestures; a stale release does not reset the shared element.

#### Live, every browser on the owner's Mac (2026-10-09, against the dev backend through a local proxy serving this branch's build)

**The click method:** CDP `Input.dispatchMouseEvent` for Chrome, Brave and Opera, and WebDriver BiDi
`input.performActions` for Firefox. Both are trusted input: `isTrusted` is true, the page gets user activation, and the
browser's autoplay policy treats them as a real click. A CGEvent click (the coordinator's suggestion) is impossible from
here: neither the ssh session nor Terminal is trusted for Accessibility (`AXIsProcessTrusted() == false`, checked
through both), so macOS drops the posted clicks. `safaridriver` needs a one-time admin `safaridriver --enable`.

Each browser ran in its own throwaway profile (`--user-data-dir` / `-profile` under `/tmp`); the owner's Brave windows
were not touched. The test titles were a 1080p MP4 film, a four-audio MKV film, and a series with 5-minute episodes.

| Browser | Gesture counted | First click | Path | Time to playing | Audio switch | Seek | Auto-advance |
|---|---|---|---|---|---|---|---|
| Chrome | yes | sound, no pill | MP4 direct; MKV → HLS, 4 renditions | MP4: first frame 0.26 s, playing 0.59 s; MKV ~2.2 s | in-stream, no restart, sound kept | direct 183 ms, sound kept | next one preloaded at the credits (R381), playing 0.12 s after its load, sound |
| Brave | yes | sound, no pill | as Chrome | MP4 ~1.1 s (with a resume) · MKV ~2 s | in-stream, sound kept; the picture held ~3 s | direct 113 ms, sound kept | 0.69 s, sound |
| Opera | yes | sound, no pill | MP4 direct; **MKV direct** (Opera declares it); an audio pick restreams to HLS | MP4 1.1 s | **stopped (paused) on the pick → fixed above**: then 1.76 s, sound. In-stream between HLS renditions: fine | direct 174 ms · HLS 2.27 s, sound kept | 0.24 s, sound |
| Firefox 157 | yes | sound, no pill | MP4 direct; MKV → HLS, 4 renditions | MP4 1.1 s (with a resume seek) · MKV ~2.2 s | in-stream, no stall (6.4 s played in 6 s), sound kept | direct 141 ms · HLS 2.17 s, sound kept | 0.19 s, sound |
| Safari | — | **not run** | — | — | — | — | — |

**Safari is owed to the owner.** A real click on Play is the whole point for Safari, and there is no trusted input into
Safari from here. Owner check: on the Mac (and on the iPhone home-screen app), play a film with a real click: is there
sound, with no pill? Then let an episode auto-advance and seek once. Either of these lets a later session run it
remotely: a one-time `sudo safaridriver --enable` (plus *Allow Remote Automation*), or an Accessibility grant for the
ssh session.

**Found, not ours to fix here:**

- **macOS Local Network privacy blocks Chrome from the household's LAN-resolved hosts.** The Jellyfin host resolves to
  a 10.x address at home. A Chrome without the *Local Network* grant gets `ERR_ADDRESS_UNREACHABLE` / *Failed to fetch*
  for every stream URL, with no prompt in a throwaway profile. To the viewer this looks like "the web player can't play
  anything". The test went through a SOCKS tunnel.
- **Firefox draws its own "Pop out this video" toggle** over the picture on hover. It is the browser's own control, and
  no web API hides it.
- **A Danish-labelled embedded subtitle showed English cues in Firefox** on the four-audio MKV. The file also has an
  external sidecar. Not diagnosed. The hypothesis is the subtitle stream index being off by one when an external
  sidecar is listed; worth checking against 301/302's sidecar numbering.
- **Build gotcha (Kotlin Gradle plugin):** after a change to a `js("…")` string only,
  `:ravilo-web:compileProductionExecutableKotlinWasmJsOptimize` stayed up to date. The dist then shipped the **old** JS
  glue (`import-object.mjs`) beside an unchanged `.wasm`; `--rerun` on that task fixed it. A release built
  incrementally after a JS-only change could ship stale code.
- **The service worker serves the previous build** until a second reload (R263's *Ravilo updated · Reload* toast). This
  is expected, but test runs must unregister it first.

## Amendment 2026-10-17 — Play fills the tab; full screen is the viewer's click (DEB-1671)

> Owner, 2026-10-17: *"Clicking play on Ravilo Web, should not do fullscreen automatically. It should just use 100% of
> the place it has, but the user should still be the one that clicks on full-screen."*

**What it replaces.** FR-R376-4's *Fullscreen* and *Orientation* bullets, and acceptance item 6, for a browser tab.
Today `PlayerImmersiveEffect` (wasmJs) calls `requestFullscreen` on `document.documentElement` when the player opens,
and the shell's own Fullscreen button is hidden while the player is up (`body.rv-playing`), so a viewer who leaves full
screen has only the F key to get back. *Background*, *Media Session*, the iPhone rule (never `webkitEnterFullscreen`)
and the installed app are unchanged.

**Prior art in this tree.** The Mac player already works this way (FR-R329-6/7): it never enters full screen by itself,
**F** and a double-click on the picture toggle it, and leaving the player leaves only a full screen entered while the
player was up. The web takes the same rules, plus a visible control, because a browser viewer has no menu bar to find
full screen in and a phone has no F key.

### FR-R376-4a — Play fills the tab and never enters full screen

Opening the player in a browser tab makes no `requestFullscreen` call. The film fills the tab's viewport — which is
what the player already does underneath today: the `<video>` is `position:fixed` at 100 % × 100 % with
`object-fit:contain` behind the canvas, and the player destination draws edge to edge with no app bar or bottom bar
(`RaviloApp`'s `playingFullscreen`). The picture is letterboxed to its own shape; FR-R244-6's fill/fit still applies. A
viewer who is already in full screen when they press Play (the shell's button or F) plays in it and stays in it.

### FR-R376-4b — The viewer's three ways in, while the player is up

All three toggle, and each runs inside the viewer's own gesture, which `requestFullscreen` requires (call it
synchronously from the click/key handler, as FR-R376-S1 does for `play()` — Safari is the strict one).

1. **A Full screen control in the player chrome**, in the same places FR-R376-8's picture-in-picture already sits:
   - the wide (desktop/TV) chrome: a `TrackButton` in the transport row **immediately after Picture in picture**,
     with its own focus stop (`transportOrder` gains it after `PIP`, so arrow keys and a gamepad reach it);
   - the handset chrome: a `HandsetIconButton` in the top bar **immediately after the picture-in-picture glyph**
     (before rotate), drawn in the same style as its neighbours — four outward corner brackets, inward when in full
     screen.

   The label is the existing `desk.fullscreen` / `desk.exit_fullscreen` (en, da, fo all present) — **no new string**.
   The control follows the real state: it listens to `fullscreenchange` (and `webkitfullscreenchange`), so when the
   browser's own Esc leaves full screen the label and glyph flip back.

   **Shown only where the page can go full screen:** a browser tab (not standalone) with `document.fullscreenEnabled`
   or `webkitFullscreenEnabled` true. **Absent, never greyed**, everywhere else: the installed app (already full
   screen), iPhone Safari (no element full screen), and every non-web player — Android, TV, Tizen, Cast, and the Mac,
   which keeps FR-R329-6/7 as it is. That availability is a seam like `pictureInPictureAvailable()`, `false` off the
   web, so R376's non-goal (*not changing the Android, TV or desktop players*) still holds.
2. **F**, unchanged: `boot.js`'s shell handler toggles full screen on F with the player up too (only the button was
   hidden), and not while a text field has focus. Acceptance 6a checks that F still reaches the page past the canvas.
3. **Double-click on the picture**, where the wide chrome is shown — the Mac's rule (FR-R329-6). **Not on the handset
   chrome:** there a double-tap in the outer thirds seeks (FR-R244-4), and the control in the top bar is the way in.

The shell's own Fullscreen button stays hidden while the player is up (`body.rv-playing`): it sat over the chrome's
bottom-right controls, and the chrome's control replaces it.

### FR-R376-4c — Leaving the player leaves only a full screen entered while it was up

The same rule as FR-R329-7. If the viewer went full screen while the player was on screen (any of FR-R376-4b's three),
Back out of the player leaves full screen too. If the page was already full screen when the player opened, it stays
full screen. Esc remains the browser's own: it leaves full screen and the viewer stays in the player.

### FR-R376-4d — Android orientation follows full screen, not Play

In a tab, Android's `screen.orientation.lock('landscape')` (which a browser grants only in full screen) is taken when
the viewer's full screen begins while a film plays, and released when it ends or the player closes. Outside full
screen a tab follows the phone: rotate it and the tab, and the film in it, go landscape. The installed app is unchanged:
it locks landscape when the player opens, as before. A refused lock still says nothing.

**No phone carve-out.** The owner's ask names no exception, so an Android phone in a tab also starts in the tab,
portrait if the phone is held portrait. It is one tap on the top-bar control, or a turn of the phone, to fill the
screen. (Read from the ask, not asked; the owner can reverse it on DEB-1671.)

### FR-R376-4e — Unchanged

iPhone: no element full screen, never `webkitEnterFullscreen`, and no control in a Safari tab; the installed app is
already full screen. Background and Media Session are as in FR-R376-4.

### Acceptance (replaces item 6)

6a. **Chrome desktop tab:** Play → the film fills the tab and `document.fullscreenElement` is `null`. The chrome's *Enter
   full screen* goes full screen and the label becomes *Exit full screen*; Esc leaves it and the label flips back; F and
   a double-click on the picture each toggle it. Back from a full screen entered in the player leaves full screen; a
   full screen entered with the shell's button **before** Play is still full screen after Back.
6b. **The same on Firefox desktop and Safari macOS** (Safari through the `webkit` prefix).
6c. **Android Chrome (or Pixel emulation):** Play starts in the tab, the handset chrome shows the control in its top
   bar; tapping it goes full screen (and locks landscape on a real phone — emulation stays portrait, as in the R376
   build notes); a double-tap at either side still seeks.
6d. **Installed app (Android and iPhone) and an iPhone Safari tab:** no control; the installed app still fills the
   screen, and the Android installed app still locks landscape on Play.

### Tests

- `transportOrder` unit test: the full-screen stop sits right after `PIP` when available, and is absent when not.
- e2e (`reference-e2e-mock-stack`, Chromium): after Play, `document.fullscreenElement === null` and the control is
  present; a click on it makes `document.fullscreenElement === document.documentElement`; Back leaves it `null`.

### Build notes for the engineer

- The change in `PlayerImmersiveEffect.kt` (wasmJs) is mostly a deletion: drop the `requestFullscreen` on open; keep
  `rv-playing`; record whether the page was already full screen at open, so FR-R376-4c's exit rule has its input (the
  Mac's `fullScreenByPlayer` in `DesktopWindow.kt` is the pattern). Android's landscape lock moves to a
  `fullscreenchange` listener while the player is up; standalone keeps locking at open.
- `PlayerScreen` is at R258's register limit: the control's availability and state go in the existing snapshot-state
  holder, as `hasPip` does — no new `var … by remember` locals.
- No design round: one control in two existing slots, drawn with the components and strings already there.
