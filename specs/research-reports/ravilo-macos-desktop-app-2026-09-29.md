# A Ravilo app for the Mac — what it would take

> Owner, 2026-09-29: *"Lets investigate whats needed for us to create a macosx desktop app for Ravilo as well?"*

Research only — no spec, no code. Measured against `main` `429cffe9`. Next free numbers are 288 / R328 if this
becomes a phase.

## 0. The answer in six lines

1. **A Mac can already "install" Ravilo** — `ravilo-web` added to the Dock from Safari (macOS 14+) or installed
   from Chrome runs in its own window from the Dock and Spotlight. No new code. What it lacks is the web
   player's (§4): no track switching, no waiting states, and Cast only from Chrome.
2. **A real native app is Compose Desktop — a JVM target of `ravilo-ui`**, packaged as a signed `.dmg`. Native
   Kotlin for macOS is not an option: Compose Multiplatform does not support a `macosArm64` UI target
   ([CMP-4580](https://youtrack.jetbrains.com/projects/CMP/issues/CMP-4580/Compose-Multiplatform-support-for-Mac-OS-native-apps) is still open).
3. **The shared code is ready for a third platform.** Everything platform-specific already sits behind
   **62 `expect` seams** in `ravilo-ui` (+2 in `shared`); a Mac build needs 62 `desktopMain` actuals, and about
   three quarters of them are storage, no-ops or one-liners (§2).
4. **The player is the work, and the one real unknown is how video frames reach Compose on macOS** — the
   heavyweight AWT surface VLC used to draw into no longer exists there. Two engines fit (§3); the
   recommended one is **AVPlayer**, because the backend already negotiates exactly what it needs (the
   Chromecast receiver's `hls_only` path) and it brings hardware decode, HDR and AirPlay.
5. **Cast from a Mac is the screens path only** (R265's *Play on a TV* to a Ravilo TV works from common code);
   there is no Google Cast SDK for macOS or the JVM.
6. **Building and shipping needs the MacBook and, for a clean install, an Apple Developer ID** (US$99/yr —
   the same account TestFlight would need): `.dmg` packaging cannot be cross-built from Debian, and an
   unnotarised app has to be opened past Gatekeeper by hand.

Rough size of road 2, as an estimate, not a measurement: **3–5 weeks**, the first 2–3 days a player spike that
decides the engine.

## 1. The three roads, and two rejected

| | What it is | New code | Plays | Cast | Verdict |
|---|---|---|---|---|---|
| **0 · Web app in the Dock** | `ravilo-web` via Safari *File → Add to Dock* ([Apple](https://support.apple.com/en-us/104996)) or Chrome *Install* | none | the browser's: Safari HLS incl. HEVC, no MKV; Chrome MKV only with H.264/VP9 + AAC/Opus; nothing plays DTS/TrueHD | Chrome only (Web Sender, not built yet); *Play on a TV* everywhere | **available today** |
| **1 · Finish the web player** | the §4 work of `ravilo-web-pwa-player-cast-2026-09-18.md` (track switching, waiting states, honest capabilities, Chrome Cast sender) | web actuals | as road 0, done properly | Chrome + screens | helps the Mac, iPhone, Windows and Linux at once |
| **2 · Compose Desktop app** | a `jvm("desktop")` target of `shared`/`ravilo-i18n`/`ravilo-ui` + a `:ravilo-desktop` app module → `.dmg` | 62 actuals + a player + packaging | §3 — everything via HLS with AVPlayer, or everything direct with libVLC | screens (+ AirPlay with AVPlayer) | **the native answer** |
| ✗ Kotlin/Native macOS UI | Compose on `macosArm64` | — | — | — | not supported by Compose Multiplatform |
| ✗ Electron / Tauri / WKWebView shell | the web bundle in a native window | a shell | the web player's, unchanged | as the web | nothing road 0 does not already give |

A third native route exists only as a by-product: an **iOS app runs unmodified on Apple-Silicon Macs** ("Designed
for iPad"). The 2026-09-18 decision was the PWA *instead of* an iOS app, so this is not a reason to build one —
but if an iOS app ever ships, a Mac version comes with it for the cost of a checkbox.

## 2. What `ravilo-ui` needs from a Mac — the 62 seams, sorted

Measured: `androidMain` 34 files / 62 actuals / 3 543 lines; `wasmJsMain` 34 files / 62 actuals / 1 576 lines.

| Kind | Seams | Mac answer |
|---|---|---|
| **Constants / no-ops (≈20)** | `isTvPlatform`, `isWebPlatform`, `isIOSWebPlatform`, `hasCastSdk`, `platformAirPlay`*, `playsHlsForAirPlay`, `installCardDismissed`/`dismissInstallCard`/`triggerNativeInstall`/`rememberInstallPromptAvailable`/`rememberIsStandaloneWebApp`, `installHashListener`, `pushRoute`/`replaceRoute`, `reloadForUpdate`/`rememberUpdateAvailable`, `rememberNotificationAsk`, `rememberCastRoutes` (empty), `playerTapTogglesChrome`, `playerBackdropColor` | `false` / empty / nothing. *AirPlay becomes real with AVPlayer (`AVRoutePickerView`). |
| **Storage (≈10)** | `TokenStore`, `MultiTokenStore`, `DeviceIdStore`, `DeviceLanguageStore`, `PlaybackPrefsStore`, `ScreensSheetPrefs`, `HomeSnapshotCache`, `MusicDeviceStore`, `saveBaseUrl`/`raviloBaseUrl` | files under `~/Library/Application Support/Ravilo` (tokens in the Keychain later) |
| **Device facts (≈8)** | `deviceDisplayName`, `detectLinkState`, `systemPrefersReducedMotion`, `rememberDeviceStateProbe`, `rememberAppOnScreen`, `createTvApiClient` | host name; `NetworkInterface`; window focus; Ktor **OkHttp** (+ CIO for the WebSocket, as Android does) |
| **Window & input (≈8)** | `PlatformBackHandler`, `rememberExitAction`, `Modifier.safeAreaPadding`, `Modifier.wakeOnPointerMove`, `setPointerCursorHidden`, `Modifier.reportTextFieldFocus`, `PlayerImmersiveEffect`, `rememberHandsetPlayerControls` | Esc = Back; native full screen; hide the cursor while playing. The layout is the TV's (`isHandset` is false), driven by arrow keys and the mouse — what the web app already does on a desktop |
| **Photos (2)** | `rememberChoosePhotoLauncher`, `rememberTakePhotoLauncher` | `FileDialog`; no camera |
| **Cast (1)** | `rememberCastSender` | `ActiveCastSender(chromecast = null, ScreenSender)` — common code already handles *Play on a TV* |
| **Player (≈11)** | `RaviloPlayer`, `PlayerVideoSurface`, `PlayerLifecycleEffect`, `PlayerChromeBridge`, `FrameTracker`, `supportedVideoCodecs`, `supportedAudioCodecs`, `supportsEmbeddedTextSubtitles`, `supportsHevcOverHls`, `switchesHlsAudioRenditions`, `warmAudioRendition`, `detectDecoderLimits`, `detectHdrSupport`, `TrailerEmbed` | **§3** |
| **Music (1, large)** | `MusicEngine` | the same engine as video, audio only; Now Playing / media keys need `MPNowPlayingInfoCenter` (native) |

Build plumbing around them: `jvm("desktop")` in `shared`, `ravilo-i18n` and `ravilo-ui` (all pure Kotlin + Ktor +
serialization today; Coil 3 and Compose already ship JVM artefacts), and a `:ravilo-desktop` module applying the
Compose Desktop application plugin. On Debian the JVM target compiles and runs; only the `.dmg` needs the Mac.

## 3. The player — two engines, one unknown

`RaviloPlayer`'s seam asks for: load with a start position and track lists, play/pause/seek, audio and subtitle
selection, `hasRenderedFirstFrame`, `isBuffering`, release. Android fills it with Media3; web with `<video>` (and
today leaves track switching and waiting states empty).

| | **AVPlayer** (Apple's) | **libVLC** via vlcj |
|---|---|---|
| Plays | HLS, MP4/MOV; **no MKV** | everything: MKV, HEVC, DTS, TrueHD, PGS |
| Server side | everything as HLS — the Chromecast receiver's `hls_only` negotiation (`2b19966f`), video copied when H.264/HEVC, audio re-encoded only for DTS/TrueHD, PGS burned in by restream (R285) | nearly all direct play; almost no server work |
| Decode | hardware (VideoToolbox), **HDR/Dolby Vision** | software/partial; no HDR through the frame path |
| Extras | **AirPlay** and Now Playing for free | none |
| Getting frames into Compose | `AVPlayerItemVideoOutput` → pixel buffers → Skia, or an `IOSurface` shared with Compose's Metal context (fast, hard) | `CallbackVideoSurface` → a bitmap per frame (CPU copies: ~8 MB/frame at 1080p, ~33 MB at 4K). The embedded surface vlcj used needs heavyweight AWT, which macOS no longer has ([vlcj-player](https://github.com/caprica/vlcj-player)) |
| How | [ComposeMediaPlayer](https://github.com/kdroidFilter/ComposeMediaPlayer) (MIT, AVPlayer over JNI on macOS, HLS on desktop, SRT/VTT drawn in Compose — but no documented audio-track selection and "fullscreen is experimental", [README](https://kdroidfilter.github.io/ComposeMediaPlayer/)) or our own small Swift + JNI shim | vlcj (GPLv3 — compatible with this repo's GPLv3) + libVLC (LGPL) bundled in the `.app` (~100 MB of plugins, each dylib signed for notarisation) |
| Size | small | large |

**Lean: AVPlayer, through our own shim if ComposeMediaPlayer cannot select HLS audio renditions** — it fits what
the backend already does for the Chromecast, puts decode on the hardware, and gives HDR and AirPlay. The cost is
server work for DTS/TrueHD audio and PGS, which the household's server already does for every cast.

**The spike (2–3 days, before anything else):** a Compose Desktop window on the MacBook playing one of Jellyfin's
HLS masters through AVPlayer with Compose chrome drawn over it, measuring frame delivery at 1080p and 4K (dropped
frames, CPU) and trying an HLS audio-rendition switch. If frames cannot be delivered smoothly, libVLC's CPU path
won't be better, and road 1 becomes the Mac answer.

## 4. Shipping it

- **Build on the Mac.** Compose's native distributions cannot cross-build: `packageDmg` runs on macOS only, needs
  JDK 17+ for `jpackage`, and `jlink` trims the bundled runtime to the modules listed
  ([docs](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)). The MacBook set up for iOS
  (`ios-build-host-macbook-setup-2026-09-16.md`) is that host; CI would need a macOS runner.
- **Sign and notarise** with a Developer ID (US$99/yr) — configured in the Compose DSL
  ([tutorial](https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/Signing_and_notarization_on_macOS/README.md)).
  Without it a household member opens it once via *System Settings → Privacy & Security → Open Anyway*.
- **Updates are not built in.** Either the backend serves the `.dmg` and the app shows R263's *Ravilo updated* toast
  pointing at it (cheap, fits what exists), or Conveyor (external; free for open source).
- **Backend:** additive only — a `desktop` value for R252's `X-Ravilo-Platform` (Users & devices label *Mac*) and the
  capabilities the chosen engine really has. No new routes.

## 5. Open questions for the owner

1. **What is the Mac app for?** A nicer window than a browser tab ⇒ road 0 now, road 1 next. Plays everything, HDR,
   AirPlay, feels native ⇒ road 2.
2. Is an Apple Developer ID acceptable (also unblocks TestFlight if an iOS app ever happens)?
3. Which Macs — Apple Silicon only (lean yes: smaller bundle, one architecture) or Intel too?
