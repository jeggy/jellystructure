# Ravilo on the web: install it on a phone, play properly, and cast — instead of an iOS app

**Date:** 2026-09-18 · **Status:** research, not spec. Companion to
`ravilo-mobile-player-chromecast-ios-2026-09-16.md` (§4 costed a native iOS target) and
`ios-build-host-macbook-setup-2026-09-16.md`. This report answers the owner's follow-up: *"iOS is very
expensive to deploy to the App Store — investigate getting the web client to work even better, support
PWA, with a proper media player, Chromecast support, and installable on iOS and Android."*

## 0. The answer in six lines

1. **Installing works on both phones without new native code.** Since iOS 26 every site added to the Home
   Screen opens as a web app by default, and Chrome on Android installs a site that meets a short manifest
   checklist. Ravilo web has none of the pieces today (no manifest, no icons, no service worker, no iOS meta
   tags), and its static server answers `manifest.json` with `index.html`. This is one small phase.
2. **The floor is iOS 18.2.** Compose Multiplatform for web runs on WasmGC, which Safari gained in 18.2
   (December 2024). An older iPhone gets a blank page unless the Kotlin/JS fallback bundle ships too. The
   parents' iPhones' iOS version is the first question to answer.
3. **The app already boots on WebKit and at phone width.** Measured here: the production bundle renders the
   sign-in screen in Playwright's WebKit (Safari 26.5-era) and in Chromium under iPhone 14 and Pixel 7
   emulation, zero page errors, ~8 s to first paint on loopback. Nothing about the Compose side blocks this.
4. **The player is the real work.** Today's web player is a `<video>` behind an opaque canvas with a
   z-index swap, a second hand-written DOM transport bar, no buffering states, no track switching, hls.js
   and JASSUB fetched from a CDN, and it tells the server the browser can direct-play MKV/HEVC/E-AC-3 —
   the TV's list, copied. On an iPhone that yields a black screen. R244's handset chrome (built 2026-09-16)
   is unreachable on web because Compose cannot draw over the video. §4 gives the way out and its one
   unknown (whether `ComposeViewport`'s canvas can be transparent).
5. **Chromecast from the browser is Chrome-only, and iOS has no Cast at all.** Google's Web Sender SDK runs
   on Chrome (desktop, Android) — never in Safari, and "casting is not supported on the iOS Chrome
   browser". A `CastSender` web actual for Chrome is straightforward (the seam, the mini bar and the remote
   already exist in common code). For an iPhone there are exactly two routes: *Play on a Ravilo TV* (phase
   111, nearly free) or a **backend Cast launcher** that speaks the Cast v2 protocol to the dongle itself —
   which would make casting a server feature for every client, at a real cost (§5).
6. **Serving needs fixing regardless.** The live site ships 15.3 MB of wasm **uncompressed** (gzip would be
   5.2 MB), with a one-hour cache and no Content-Security-Policy at all on `ravilo.jebster.net`.

## 1. What the web client is today (measured 2026-09-18)

### 1.1 Shape

- `:ravilo-web` is a 30-line wasmJs executable around `RaviloRoot()` using the **deprecated
  `CanvasBasedWindow`** (CMP 1.9 escalated it to an error, suppressed with a TODO). The `index.html` is
  hand-written: an opaque `<canvas id="ComposeTarget">`, a DOM *Fullscreen* button, gamepad polling.
- Served two ways: the standalone `:web-static-server` image at `ravilo.jebster.net` (prod, with
  `DEFAULT_SERVER_URL=https://jelly.jebster.net`, so the app is **cross-origin** to its API), and
  optionally by the backend at `/tv/**` when `RAVILO_WEB_DIR` is set (prod does not set it).
- **Bundle:** `81efe6ce…wasm` 6.9 MB (app) + `bccfa839…wasm` 8.4 MB (Skiko) + `ravilo.js` 0.6 MB + 0.5 MB
  of fonts. Content-hashed, so cacheable forever — but served with `max-age=3600, must-revalidate`.

### 1.2 What the live server does and does not send

| Check | `ravilo.jebster.net` | Consequence |
|---|---|---|
| `Content-Encoding` on `.wasm` / `.js` | **none** (gzip −66 %: 6.9→1.9 MB, 8.4→3.2 MB) | 15.9 MB per cold load on a phone; Caddy does not compress unless `encode` is configured, and the static server never does |
| `Cache-Control` on hashed assets | `max-age=3600, must-revalidate` | Revalidates every hour; should be `immutable, max-age=1y` |
| `Content-Security-Policy` | **none** | The backend sets a strict CSP for `/tv/` (`script-src 'self'`), the static server sets nothing — the two deployments behave differently, and the `/tv/` one would block the CDN-loaded hls.js/JASSUB |
| `HEAD /` | **404** | Only `GET` is routed; some install checkers and uptime probes use HEAD |
| `GET /manifest.json`, `/sw.js`, `/icon.png` | **200 with `index.html`** | The SPA fallback answers *every* unknown path with HTML — a browser asked for a manifest gets a page. Any PWA work must first make the fallback apply only to extension-less paths |
| `<link rel=manifest>`, `apple-mobile-web-app-*`, icons, service worker | none | Not installable on Android; on iOS 26 it installs anyway but with a screenshot-derived icon and no name control |

### 1.3 The player, honestly

`RaviloPlayerWasm.kt` (308 lines) and `PlayerChromeBridgeWasm.kt` (118 lines):

- **Compose cannot paint over the video.** The canvas is opaque, so the `<video>` sits *behind* it and is
  promoted above it (`z-index` swap) whenever the chrome is hidden. While the chrome is visible, a
  hand-written **DOM transport bar** (⏪ ▶ ⏩, range slider, two times) is what the viewer sees — a second
  implementation of the chrome, which R244's *one component, two destinations* rule forbids, and none of
  R244's handset chrome (thumb rail, gestures, lock, sheets) exists on web. The R157 comment records why:
  this CMP version's `CanvasBasedWindow` has no canvas-alpha parameter.
- **No waiting states.** `hasRenderedFirstFrame = true`, `isBuffering = false`, `isSeeking = false` are
  constants, so R218's moments B/C/D and R237's copy never show on web; only moment A does.
- **No track switching.** `selectAudioTrack` is empty; `selectSubtitleTrack` is empty (the comment says
  `TextTrackList` indexing "is not bridged" — a `js()` one-liner bridges it). Only the `default` `<track>`
  ever renders.
- **Third-party script from a CDN at runtime:** hls.js 1.5.13 and JASSUB 1.7.0 from `cdn.jsdelivr.net`,
  which an installed offline-capable app cannot rely on and the backend's CSP already blocks.
- **The capability lie.** `PlayerStore.kt:172` sends the same hard-coded list on every platform:
  `containers = mkv,mp4,avi,mov`, `hevc`, `ac3,eac3`. `deviceProfile()` turns that into a direct-play
  profile, so a browser is handed a raw MKV URL. Chrome plays MKV only with H.264/VP9 + AAC/Opus (HEVC
  needs a hardware decoder, AC-3/E-AC-3 are not decoded); **Safari plays no MKV at all**, and there is no
  DTS or TrueHD anywhere on the web. This is the exact bug fixed for the receiver on 2026-09-18
  (`1aa2562f`: "a client that says `hls_only` gets no direct-play profile") — the browser needs the same
  honesty, one level finer (§4.3).
- Also true today and worth keeping: `wireMediaSession` already routes OS media keys; `::cue` styling
  matches the TV; VTT is fetched and stripped of ASS tags; `object-fit` is the fit/fill seam (R244).

### 1.4 Does it run on a phone? (measured)

Script: `scratchpad/mobile-boot.mjs` / `webkit-boot.mjs`, against the local production bundle over
loopback.

| Engine | Emulation | Boot | Page errors | Notes |
|---|---|---|---|---|
| Chromium (Playwright 1.61) | Pixel 7, dpr 2.625 | sign-in painted, ~8.2 s incl. wasm compile | 0 | canvas 1081×2202, 11 MB JS heap |
| Chromium | iPhone 14, dpr 3 | ~8.1 s | 0 | layout at 390 css px is the compact one |
| **WebKit** (Playwright 1.61 in the `mcr.microsoft.com/playwright:v1.61.0-noble` image; UA reports Safari 26.5) | iPhone 14 | ~12 s | 0 | **WasmGC app boots and renders** — the first WebKit evidence this project has |

Caveats, stated rather than glossed: this is Linux WebKit, not iOS Safari — its media probes answered
"probably" for MKV/HEVC/E-AC-3 through GStreamer and are **not** iOS evidence; touch gestures, the IME,
the standalone-mode chrome, rotation and the safe areas were not exercised; and the mock e2e stack was
down, so nothing past sign-in was measured on a phone viewport. A pass on the Pixel 9 (Chrome, adb) and on
a real iPhone is the first acceptance test of any phase below.

## 2. Platform facts, verified 2026-09-18

| Fact | Source |
|---|---|
| Kotlin/Wasm needs **Chrome 119+, Firefox 120+, Safari/WebKit 18.2+** (iOS 18.2+); older not supported | kotlinlang.org *Supported versions and configuration* |
| Compose Multiplatform for web is **Beta** (1.9.0, Sept 2025); `WebElementView` embeds an HTML element that **overlays the canvas, intercepts input, and Compose cannot draw on top of it**; only with the `ComposeViewport` entry point; `CanvasBasedWindow` deprecated | kotlinlang.org *What's new in CMP 1.9.3* |
| `composeCompatibilityBrowserDistribution` ships a **Kotlin/JS fallback** alongside wasm for browsers without WasmGC, at the cost of a second bundle | same |
| Web accessibility: labels, buttons, headings; **not** scroll containers, sliders, interop views, traversal order | same |
| **iOS 26: every site added to the Home Screen opens as a web app by default**; manifest optional but honoured for icon/name; "zero requirements for installability"; service worker optional | webkit.org *WebKit Features in Safari 26.0* |
| **`requestFullscreen()` on a non-video element does not work on iPhone** (works on iPad/macOS); Apple engineer: "no supported way", FB16057121 open | developer.apple.com forums thread 770080 |
| Managed Media Source (MSE for iPhone) since **iOS 17.1**; hls.js supports it; hls.js recommends checking native HLS via `canPlayType('application/vnd.apple.mpegurl')` first on Safari | hls.js README |
| hls.js fMP4: HEVC, AV1, Dolby Vision, AC-3/EC-3 "subject to runtime support"; TS: H.264, AAC, MP3 (H.265/AC-3 full build only); subtitles WebVTT, IMSC1, CEA-608/708 | hls.js README |
| Cast **Web Sender SDK** runs on "Cast-supported web browsers on Mac, Windows, Linux, ChromeOS, and Android"; **"Casting is not supported on the iOS Chrome browser"**; HTTPS required | developers.google.com *Web Sender* |
| Chrome install criteria: `name`/`short_name`, icons **192 and 512 px**, `start_url`, `display` ∈ standalone/fullscreen/minimal-ui, HTTPS, engagement heuristic | web.dev *Install criteria* |
| Safari 26: WebAssembly is first run by a new in-place interpreter (faster launch, less memory for large modules) | webkit.org 26.0 |

Things I could not verify from here and mark **verify on device**: whether `screen.orientation.lock()`
works in an installed web app on iPhone (documented as unsupported on iOS historically); whether an iOS
standalone web app rotates to landscape freely; whether `<track>` cues render alongside *native* HLS on
iPhone outside Apple's own fullscreen player; whether the Cast Web Sender initialises inside an Android
WebAPK (an installed PWA) as it does in a tab.

## 3. Installing it — what each platform needs

### 3.1 Common
- `manifest.webmanifest`: `name` *Ravilo*, `short_name`, `start_url: /`, `display: standalone`,
  `background_color`/`theme_color` `#0d0d1a`, `orientation: any`, icons 192/512 (+ `maskable`) generated
  from the R62 brand masters (`design/ravilo/assets/brand/*.svg`, lit-mark on `#000B25`), `id`, `lang`.
- A **service worker** — not for "offline Ravilo" (a library app without a server is nothing) but for
  three concrete things: (1) the 16 MB shell served from cache so a launch from the icon is instant, (2) a
  **versioned update** — precache the hashed assets of the current build, activate on next launch, one
  toast *"Ravilo updated"* (R252 already knows the version), (3) Chrome's richer install surface.
  Scope = the app's own origin, which is why the app should keep its own origin (§6).
- The static server must (a) stop answering asset-shaped paths with `index.html`, (b) serve
  `.webmanifest` as `application/manifest+json`, (c) serve `sw.js` with `no-cache`, (d) answer `HEAD`.
- **Safe areas.** The canvas fills the viewport (`viewport-fit=cover` is already set); a standalone web app
  on a notched phone paints under the island and the home indicator. R244 FR-R244-13 reads safe areas
  "on both platforms" — the web actual has to feed `env(safe-area-inset-*)` into Compose (one `js()`
  read of `getComputedStyle` on a probe element; there is no seam for it today).
- The DOM *Fullscreen* button and the gamepad polling in `index.html` are TV/desktop affordances; on a
  phone the button is dead weight on iPhone and a duplicate of the player's own on Android — hide below
  a width or move it into the app.

### 3.2 iOS
- Add `apple-mobile-web-app-capable`, `apple-mobile-web-app-status-bar-style: black-translucent`,
  `apple-touch-icon` 180 px, `apple-mobile-web-app-title`. Splash images are optional and a lot of files;
  skip.
- **There is no install prompt.** The app has to *teach* it once: share sheet → *Add to Home Screen*. One
  card on the sign-in / Settings screen, shown only when `navigator.standalone` is false and the UA is
  iPhone; dismissible; never on the TV. This is design work (§7).
- Storage: `localStorage` holds the token and device id. Safari's seven-day script-storage cap does not
  apply to Home Screen web apps, and an installed app keeps its own storage partition — so a viewer who
  signs in inside Safari signs in again after installing. Say so on the install card.
- Below iOS 18.2 the wasm build shows nothing. Options: ship the JS fallback (doubles the bundle, and
  the Kotlin/JS build of Compose is the slower, larger one), or show a plain HTML *"Ravilo needs iOS 18.2
  or later"* page from a tiny feature check before the bundle loads (recommended — it costs nothing and
  is honest). Which one is the owner's call and depends on the parents' phones.

### 3.3 Android
- Chrome installs it as a WebAPK once the manifest criteria hold: an app icon, a splash from
  `background_color` + icon, and `display: standalone`. `beforeinstallprompt` lets the app offer *Install*
  from inside Settings instead of relying on Chrome's mini-infobar.
- Chrome honours manifest `orientation`; keep it `any` and let the player lock (§4.4).
- **This overlaps the native Android phone app** (R224's universal APK). Two phone clients on one
  platform is a maintenance split; the honest position is that the Android APK is the phone client with
  the real player (Media3, MKV, DTS, HDR, brightness, haptics), and the web install exists for the
  household's iPhones and for anyone without the Play Store. Not a replacement.

## 4. The player

### 4.1 The wall, and the door out of it

Compose draws on a WebGL canvas. The video is a DOM element. Today the two cannot overlap in the right
order. Three ways through, in order of preference:

**A. A transparent canvas over the video (the prize).** If `ComposeViewport`'s canvas can be created with
an alpha channel and Compose's root background left unpainted, the `<video>` stays behind the canvas
permanently and **all of Compose's chrome renders over it** — R244's handset chrome, the R180/R195 picker
sheet, the next-up card, Skip Intro, the R218 states, unchanged. The DOM transport bar and the z-index
swap are deleted. The R157 note says the old entry point had no such parameter; whether the new one does,
or whether Skiko's context can be asked for `alpha: true` and the surface cleared to transparent, is the
**single most valuable spike in this whole report** — half a day, before any phase is written.

**B. `WebElementView` for the video.** Places the `<video>` *inside* the Compose layout so the picture is
laid out like any composable (letterbox, fit/fill, subtitle inset all become layout). But the docs are
explicit: the element overlays the canvas and Compose cannot draw over it. So B fixes layout, not chrome;
it is worth having *with* A, and it forces the `ComposeViewport` migration anyway.

**C. Keep the DOM chrome and grow it.** Extend `PlayerChromeBridge` until it is the phone chrome in HTML.
That is what R244 exists to forbid (two implementations of one design), and it would never track the
Compose one. Only if A is impossible; and then the honest framing is "the web player is a smaller player".

### 4.2 Fullscreen and orientation, per platform

- **iPhone:** there is no `requestFullscreen` for the page. The **installed web app is the fullscreen** —
  no browser chrome, and R261's *fullscreen only while something plays* becomes "the app is always
  fullscreen, the player just hides the app bar". Never call `video.webkitEnterFullscreen()`: it hands
  the picture to Apple's player, which discards every piece of Ravilo chrome (picker, skip, next-up).
  In a Safari *tab* the player has to live with the address bar; that is the case the install card
  exists to move people out of. Rotation: follow the sensor, as R244 FR-R244-7 decided; whether
  `screen.orientation.lock` works in the installed app is a device check.
- **Android Chrome / WebAPK:** `requestFullscreen()` on the canvas container plus
  `screen.orientation.lock('landscape')` on play, unlock on stop — the web actual of
  `PlayerImmersiveEffect` is an empty body today.
- **Desktop:** the existing F-key toggle, moved into the player.

### 4.3 Tell the server the truth about the browser

Replace the copied TV list with a probe at start-up, once, cached:

| Question | API | Sent as |
|---|---|---|
| MKV? | `canPlayType('video/x-matroska; codecs="avc1.640028, mp4a.40.2"')` | `containers` (Chrome: mkv, mp4, webm; Safari: mp4 only) |
| HEVC / AV1 / VP9? | `MediaCapabilities.decodingInfo({type:'file', video:{contentType:'video/mp4; codecs="hvc1.1.6.L153.B0"', …}})` (async, so probe before the first play, not during) | `video_codecs` |
| E-AC-3 / AC-3 / DTS? | `canPlayType('audio/mp4; codecs="ec-3"')` etc.; DTS/TrueHD are never supported → never sent | `audio_codecs` |
| Native HLS? | `canPlayType('application/vnd.apple.mpegurl')` | on Safari **`hls_only = true`** (no direct-play profile, Jellyfin remuxes/transcodes into HLS which the iPhone plays natively, including HEVC/E-AC-3 where the file allows a codec copy) |
| Link | `navigator.connection` (Chrome only; absent on Safari → `unknown`) | `link_kind` cellular/wifi, so phase 177's cap applies on mobile data |

Nothing changes in the backend: `deviceProfile()` already honours `containers` and `hlsOnly`. What the
viewer gets: on an iPhone every title plays (as HLS), on Android Chrome H.264 titles direct-play and the
rest convert. HDR and Dolby Vision stay tone-mapped to SDR (`detectHdrSupport() = NONE`) until someone
proves a browser path; that is a correct floor, not a bug.

### 4.4 The rest of the player, itemised

- **Waiting states:** wire `waiting`/`playing`/`seeking`/`seeked`/`loadeddata` into the three booleans
  R218 reads. That alone brings moments B/C/D and R237's copy to web with no new design.
- **Track switching:** text tracks by index via a `js()` bridge (`video.textTracks[i].mode`); audio via
  hls.js `audioTrack` when hls.js is driving, and via a **new playback ticket with the chosen stream
  indices** on native HLS (Safari exposes no per-track control over an HLS stream; the server-side switch
  is how the transcoding path already works and how the receiver does it).
- **Self-host hls.js and JASSUB** under `/vendor/` in the bundle, versioned, precached by the service
  worker; the CDN loads go, and the backend's `script-src 'self'` stops being a landmine. Load hls.js only
  when `canPlayType` says no native HLS (Safari uses native; MMS via hls.js is the fallback for when native
  cannot do something we need — not the default).
- **Subtitles:** VTT `<track>` with the existing `::cue` style and the R244 S/M/L scale (already wired);
  PGS stays burn-in (profile already says `Encode`); ASS via self-hosted JASSUB. Verify on iPhone that
  `::cue` styling and `<track>` rendering hold under native HLS.
- **Brightness / volume swipes:** stay *not offered* on web (R244's decision; a page cannot set screen
  brightness, and iOS ignores `video.volume`). Haptics: `navigator.vibrate` exists on Android Chrome only;
  the `expect` for haptics can be a no-op on web as R244's review suggested.
- **Backgrounding:** iOS pauses a backgrounded web app's video; `PlayerLifecycleEffect`'s web actual is
  empty, and the server-side watchdog reaps the session. Wire `visibilitychange` so a return resumes
  where it was rather than relying on the reaper — small, and it makes phase 180's teardown honest on web.
- **Media Session metadata** (title, art) is one call away and gives the iOS lock screen a card while a
  cast or a local play runs; R193's "no local MediaSession on phone" was an Android decision about a
  native service, and a browser's own media session is the browser's — worth a one-line owner check.
- **Live TV** (R244 FR-R244-15) follows the same chrome once A holds; nothing web-specific.

## 5. Chromecast from a browser

### 5.1 Chrome (desktop, Android tab, Android WebAPK): the Web Sender actual

The seam is already there — `CastSender` (link, status, load/play/pause/seek/stop, track selects, the
custom-namespace `send`) with `rememberCastSender()` returning `null` on web, which is exactly the
*absent, never greyed* rule. A web actual is: load `cast_sender.js?loadCastFramework=1` from
`www.gstatic.com` (a `script-src` entry, as `/cast/` already has for the receiver), `CastContext` with the
per-installation app id from config (218 FR-218-11), the SDK's own `<google-cast-launcher>` as the
`PlatformCastButton`, `RemotePlayer` events → `CastRemoteStatus`, the same
`urn:x-cast:dev.jellystructure.ravilo` namespace the Android sender uses. **The mini bar, the remote, the
seven states and the subtitles sheet are common code and arrive for free.** The receiver, the session
ceiling, the Jellyfin identity — all 218/R245, untouched. Same LAN requirement as Android: Cast discovery is
the *browser's*, so the phone and the dongle share a network.

### 5.2 iPhone: no SDK will ever run in the page

Every iOS browser is WebKit and Google states Cast is not supported even in Chrome for iOS. Two honest
routes, not mutually exclusive:

1. **Play on a Ravilo TV** (phase 111's `POST /api/remote/play` + `/remote/command` over the per-device
   `/api/tv/events` socket, listed as prospective *R246* in the 2026-09-16 report). The stue TV is a
   Ravilo device, so from an iPhone web app: *Play on Stue TV*, then a remote that is R245's remote
   pointed at a TV instead of a dongle. Nearly free; covers this house's living room; does **not** cover
   the parents' LG + old stick.
2. **A backend Cast launcher.** The Cast v2 protocol is public and small: mDNS `_googlecast._tcp`
   discovery, a TLS connection to port 8009 (self-signed cert), protobuf `CastMessage`, the
   `connection`/`heartbeat`/`receiver` namespaces, one `LAUNCH {appId}` — what pychromecast and Home
   Assistant do. Because the receiver **is already its own Ravilo device** (R245 FR-R245-13), once
   launched it authenticates, plays and reports through the backend like any TV; the phone's only unmet
   need is *starting* it. Then a `POST /api/tv/cast/launch {device}` makes casting a **server feature
   available to every client** — the iPhone web app, the Android app without media3-cast, the TV, the
   admin UI, Home Assistant — with one sender implementation instead of one per platform.
   What it costs, named so it can be tested first:
   - **TLS from Kotlin/Native.** The backend's HTTP goes through Curl; Ktor's raw-socket `tls()` is
     JVM-only. The launcher needs an OpenSSL/mbedTLS cinterop over a plain socket (libssl is already in
     the image because Curl links it — verify) or a small helper. This is the spike that decides the
     phase.
   - **The LAN.** The backend runs on the `caddy` bridge network; mDNS does not cross it. Either
     `network_mode: host` for discovery, or the admin enters the dongle's address on the Settings →
     Chromecast card (218's card grows a *Devices* list). The parents' stick is on *their* LAN: their
     jellystructure would have to be on it too, which for a self-hoster is the normal case and for this
     household means the launcher only reaches the stue Chromecast, not the parents' — unless the
     parents' phones simply use the Cast sender from Chrome on Android, or an iPhone leans on route 1.
   - **Control after launch.** Today the phone drives the receiver over the Cast custom namespace, which
     a browser on iOS cannot open. The receiver would have to accept the same `CastCommand`s over the
     backend — the `/api/tv/events` socket phase 111 already pushes through — which 218's amendment says
     a receiver does not open today. One more consumer of an existing bus.
   - **Re-connect on app start** (FR-R245-5) becomes a backend query (*is a receiver device of mine
     playing?*) instead of SDK session resumption — arguably cleaner, and it makes the two-outcome rule
     (live ⇒ mini bar; gone ⇒ silence) trivially observable.

**Rejected:** the Remote Playback API (`video.remote.prompt()`) on Android Chrome casts a plain URL to
Google's *Default Media Receiver* — it bypasses the Ravilo receiver and every invariant 218 was written to
keep (same class as "Jellyfin's own Cast receiver" in the 2026-09-16 report), and it does not work with
MSE-driven playback anyway.

## 6. Serving and deployment

- **Keep one app origin (`ravilo.jebster.net`)** for the service worker scope, the manifest `id` and the
  installed app's identity; cross-origin API stays (CORS is already configured). Serving at `/tv/` from the
  backend remains an option for single-container self-hosters — but then the manifest `start_url`/`scope`
  must be `/tv/` and the CSP must admit the Cast SDK; support both, with `/tv/` the documented default for
  new installs? That is an owner question (§8).
- Fix in `:web-static-server` (a 235-class backend phase): gzip/brotli (or a Caddy `encode` label in the
  compose file — cheaper, but every self-hoster then has to know), `immutable` caching for hashed assets,
  correct types for `.webmanifest`/`.woff2`, `HEAD`, asset-path 404s, a CSP of its own equal to the
  backend's plus `www.gstatic.com` for the sender, and a `Service-Worker-Allowed` header only if the SW
  is not at the root.
- The e2e suite has one web test ("boots without a JS crash"). Add: a manifest fetch returns JSON, the SW
  registers, a phone-viewport sign-in, and — once §4.3 lands — a Playwright check that the capabilities
  sent by Chromium contain no `mkv` when the UA is WebKit. Playwright WebKit needs `libgtk-4`,
  `libevent`, `libwoff2dec` on the host or the Playwright image (used above).

## 7. What is design work

- The **install card** for iPhone (no prompt exists): where it lives, its one sentence, its dismissal, the
  storage-partition note. And the Android *Install* row in Settings.
- The **update toast** ("Ravilo updated · reload") — R252 already knows the version string.
- **Icons and splash** from the R62 brand masters: maskable 512, monochrome for Android 13 themed icons,
  180 px `apple-touch-icon`.
- **The web player needs no new design if §4.1-A holds** — it *is* R244's phone player. If A fails, the
  DOM chrome needs its own drawing, which is precisely the outcome to avoid.
- *Play on Stue TV* beside the cast button on the phone (round-2 §D of the mobile brief, already
  chosen 2026-09-16) and the iPhone-without-Cast case: the cast glyph absent, *Play on Stue TV* present.
- The **"needs iOS 18.2"** page, one sentence, in three languages.

## 8. Owner questions

1. **Which iOS do the parents' iPhones run?** ≥ 18.2 ⇒ wasm only; older ⇒ the JS fallback (bigger,
   slower) or the plain "needs iOS 18.2" page. My lean: the page.
2. **One origin (`ravilo.jebster.net`) or `/tv/` on the backend** as the canonical home of the web
   app for self-hosters? My lean: keep the origin for this house, support `/tv/` in the manifest/SW
   for single-container installs.
3. **Is *Play on Stue TV* enough for iPhones for now**, with the backend Cast launcher as a later phase
   gated on the TLS spike? My lean: yes — ship the Chrome sender and phase 111 first; the launcher is the
   phase that makes cast universal and is worth its own investigation report once the TLS question is
   answered.
4. **Host networking for the backend container** (mDNS discovery) vs. typing the dongle's address on the
   Chromecast card? My lean: the address field, because it is explicit and works for everyone.
5. **Does the web install replace the Android phone APK?** My lean: no — the APK stays the Android phone
   client; the web install is for iPhones and for people without the Play Store.
6. **Media Session metadata on web** (a lock-screen card in the browser's own session) — allowed, given
   R193 was about Android's service, not the browser's?
7. **Accepted floors for v1 of the web player:** SDR only, no DTS/TrueHD (converted), no brightness swipe,
   no haptics on iPhone. All follow from platform facts; listed so nobody files them as bugs.

## 9. Proposed phasing (prospective; next free today: **235 / R263**)

| # | Phase | Depends on |
|---|---|---|
| **R263** | **Ravilo web installs** — manifest, icons, iOS meta, service worker with versioned update + toast, safe-area seam, the install card / Settings *Install* row, the "needs iOS 18.2" page. | 235 |
| **235** | **Serve the web app like an app** — compression, immutable caching, asset-path 404s, `HEAD`, manifest/SW types, a CSP on the static server (+ `www.gstatic.com`), self-hosted hls.js/JASSUB in the bundle. | — |
| **R264** | **The browser tells the truth** — capability probe → `containers`/`video_codecs`/`audio_codecs`/`hls_only`/`link_kind`; native HLS on Safari, hls.js elsewhere. Backend unchanged. | — |
| **R265** | **The web player is the phone player** — `ComposeViewport` migration, transparent canvas over the video (spike first) or the documented fallback, R218 moments wired, track switching, fullscreen + orientation lock on Android, never native fullscreen on iPhone, `visibilitychange`. | R264, the A spike |
| **R266** | **Cast from Chrome** — the `CastSender` web actual over the Web Sender SDK; absent everywhere else. Acceptance: Pixel 9 Chrome → stue Chromecast. | 218, R245 |
| **R267** | **Play on a Ravilo TV** from the phone and the web (phase 111 under device-token auth; the 2026-09-16 report's R246). | 111 |
| **236 / R268** | **A backend Cast launcher** — gated on a TLS-from-Native spike and the LAN answer; makes casting a server feature for every client. Its own research report first. | 218, R245, R267 |

R263 and 235 are small and independent of the player; R264 is a day and fixes the black screen on
iPhones by itself; R265 is the real work and hinges on one spike that should run before it is spec'd.

## 10. Sources

- Kotlin docs — [Supported versions and configuration (Kotlin/Wasm)](https://kotlinlang.org/docs/wasm-configuration.html)
- Kotlin docs — [What's new in Compose Multiplatform 1.9.3](https://kotlinlang.org/docs/multiplatform/whats-new-compose-190.html)
- JetBrains — [Compose Multiplatform 1.9.0: Compose for Web goes Beta](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/)
- WebKit — [WebKit Features in Safari 26.0](https://webkit.org/blog/17333/webkit-features-in-safari-26-0/)
- Apple Developer Forums — [Fullscreen API missing on iPhone (thread 770080)](https://developer.apple.com/forums/thread/770080)
- hls.js — [README: supported platforms, MMS, formats](https://github.com/video-dev/hls.js/blob/master/README.md)
- Google Cast — [Web Sender](https://developers.google.com/cast/docs/web_sender)
- web.dev — [Install criteria](https://web.dev/articles/install-criteria)
- caniuse — [Fullscreen API](https://caniuse.com/fullscreen)
- This repo: `ravilo-ui/src/wasmJsMain/.../RaviloPlayerWasm.kt`, `PlayerChromeBridgeWasm.kt`,
  `CastSenderWasm.kt`, `RaviloRootActuals.kt`; `ravilo-web/src/wasmJsMain/resources/index.html`;
  `web-static-server/.../Main.kt`; `src/linuxX64Main/.../auth/JellyfinClient.kt` (`deviceProfile`),
  `server/Server.kt` (CSP), `server/routes/RemoteRoutes.kt` (phase 111); `PlayerStore.kt:172`;
  commit `1aa2562f` (the receiver's `hls_only` fix).
