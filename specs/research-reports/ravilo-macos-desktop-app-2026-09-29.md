# A Ravilo app for the Mac — what it would take

> Owner, 2026-09-29: *"Lets investigate whats needed for us to create a macosx desktop app for Ravilo as well?"*
> — and, the same hour: *"I would like to be able to stream music from this app to the wifi speakers etc as well."*

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
5. **Music to the Wi-Fi speakers (and films to the TVs) works from a Mac app — by speaking the Cast protocol
   ourselves.** There is no Google Cast SDK for macOS or the JVM, but the protocol is small and public (the
   2026-09-18 report §5 described it; what stopped the *backend* from speaking it was TLS in Kotlin/Native — the
   JVM has TLS built in). With a Cast v2 client behind the existing `CastSender`/`rememberCastRoutes` seams, all of
   R324's music-mode sheet, hand-off, queue remote and ⋯ block light up from common code, against the same 286
   receiver the phone uses (§4). The web app in the Dock (road 0) **cannot** do this in Safari at all.
6. **Building and shipping needs the MacBook and, for a clean install, an Apple Developer ID** (US$99/yr —
   the same account TestFlight would need): `.dmg` packaging cannot be cross-built from Debian, and an
   unnotarised app has to be opened past Gatekeeper by hand.

Rough size of road 2, as an estimate, not a measurement: **3–5 weeks**, plus **1–2 weeks for casting** (§4); the
first days are two spikes on the MacBook — the player's frames, and discovering and launching on a speaker.

## 1. The three roads, and two rejected

| | What it is | New code | Plays | Cast | Verdict |
|---|---|---|---|---|---|
| **0 · Web app in the Dock** | `ravilo-web` via Safari *File → Add to Dock* ([Apple](https://support.apple.com/en-us/104996)) or Chrome *Install* | none | the browser's: Safari HLS incl. HEVC, no MKV; Chrome MKV only with H.264/VP9 + AAC/Opus; nothing plays DTS/TrueHD | Chrome only (Web Sender, not built yet); *Play on a TV* everywhere; **music has no web build yet** (R321 FR-R321-2) | **available today, films only** |
| **1 · Finish the web player** | the §4 work of `ravilo-web-pwa-player-cast-2026-09-18.md` (track switching, waiting states, honest capabilities, Chrome Cast sender) | web actuals | as road 0, done properly | Chrome + screens | helps the Mac, iPhone, Windows and Linux at once |
| **2 · Compose Desktop app** | a `jvm("desktop")` target of `shared`/`ravilo-i18n`/`ravilo-ui` + a `:ravilo-desktop` app module → `.dmg` | 62 actuals + a player + packaging + a Cast v2 client | §3 — everything via HLS with AVPlayer, or everything direct with libVLC | **speakers, groups, the hub and the TVs** (§4) + screens (+ AirPlay with AVPlayer) | **the native answer** |
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
| **Cast (3)** | `rememberCastSender`, `rememberCastRoutes`, `hasCastSdk` | a Cast v2 sender and mDNS routes of our own (§4); `hasCastSdk = true` |
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

## 4. Music to the household's speakers from the Mac

The owner's ask: stream music from the Mac app to the Wi-Fi speakers (Stue, Gæsteværelse, their group, the Nest
Hub) — and, by the same road, films to the TVs.

**What already exists and is reused unchanged.** 286 made our receiver play music on an audio-only device and own
the queue; R324 made the phone its remote. Everything the phone needs from the platform sits behind three seams —
`CastSender` (load, play/pause/seek, `send(json)` on our namespace, `setVolume`), `rememberCastRoutes` (a list of
`CastRoute(id, name, kind, busyWith, select)`) and `hasCastSdk` — and everything above them is common code:
`MusicCast`/`MusicPlayback`, the *Play on…* sheet with speakers first, take-over (*Stop Spotify and play here?*),
hand-off both ways, the Queue tab as a remote, the ⋯ volume / lyrics / *Play on this phone* block, the mini bar's
*· Stue*. A Mac app that fills those three seams gets all of it.

**Why the Mac can do what the backend could not.** No Cast SDK exists for macOS or the JVM, so the app speaks the
protocol itself — the one the 2026-09-18 report (§5, route 2) and 286's road C describe:

- **Discovery:** mDNS `_googlecast._tcp` (JmDNS, Apache 2.0). Each speaker, group and display advertises itself;
  the TXT record carries the friendly name, the model and a capability mask (no video output ⇒ `speaker`; a
  group is its own service) — *verify the exact TXT keys on the household's five devices in the spike*, including
  whether the running app's name is in the record (it would give *Busy · Spotify* without connecting).
- **Session:** TLS to port 8009 (self-signed — accepted, as every sender does), length-prefixed protobuf
  `CastMessage`s (five fields; hand-encodable), a heartbeat, then `LAUNCH` our application id on the receiver
  namespace, `CONNECT` to the running app, `LOAD` on the media namespace with 286's `CastLoadData` in `customData`,
  and our own `urn:x-cast:dev.jellystructure.ravilo` namespace for `CastCommand`/`CastReceiverMessage`.
  `GET_STATUS` names the app already running (take-over), `SET_VOLUME` is the ⋯ slider, `STOP` is *Stop casting*.
  What blocked the *backend* was opening that TLS socket from Kotlin/Native; the JVM has it.
- **Library or our own:** [chromecast-java-api-v2](https://github.com/vitalidze/chromecast-java-api-v2) (Apache 2.0)
  does this, but describes itself as "not stable … a lot of bugs". **Lean: our own client, ~600–800 lines**,
  written in `shared` behind a small TLS-socket `expect` — the JVM actual now, and the same client becomes 286's
  **road C (the backend as sender, which is what finally reaches an iPhone)** once a Kotlin/Native TLS actual
  exists.

**Mac-specific requirements.**
- **Local Network permission (macOS 15+):** the app bundle must carry `NSLocalNetworkUsageDescription` and
  `NSBonjourServices = _googlecast._tcp` in its `Info.plist`, or macOS silently blocks discovery and never asks
  ([Apple](https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription)).
  Compose's packaging DSL can add raw `Info.plist` keys; *verify in the spike that the permission attaches to the
  bundle when the JVM does the multicast.*
- **Step 5a on the Cast console** (286 FR-286-1) — the same checkbox the phone needs; until it is ticked the
  speakers do not answer for our application id at all.
- **The receiver needs nothing new**: a LOAD from the Mac is indistinguishable from one from the phone.
- **Away from home** (the owner's phone on the VPN today): mDNS does not cross the VPN, so casting from a Mac is a
  home-network feature, as it is on the phone.

**What the Mac does not get:** Google's lock-screen notification for the cast (FR-R324-9) is the SDK's; on a Mac the
equivalent is *Now Playing* in Control Center (`MPNowPlayingInfoCenter` + media keys through the same native shim
as the music engine) — worth a line in the spec, not a blocker.

**The cast spike (1–2 days, with step 5a ticked):** from a JVM `main` on the MacBook — discover all five devices,
read their kinds, launch our app on Stue, send a two-song `CastLoadData`, skip with `next`, set the volume, stop.
Every step is a message the phone already sends through the SDK, so success here means the rest is plumbing.

## 5. Shipping it

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

## 6. Open questions for the owner

1. **What is the Mac app for?** A nicer window than a browser tab ⇒ road 0 now, road 1 next. Plays everything, HDR,
   AirPlay, **music on the speakers** ⇒ road 2 — speakers settle it: neither the web app nor Safari can cast, so the
   owner's second ask needs the native app.
2. Is an Apple Developer ID acceptable (also unblocks TestFlight if an iOS app ever happens)?
3. Which Macs — Apple Silicon only (lean yes: smaller bundle, one architecture) or Intel too?
4. Should the Cast v2 client be written once in `shared` so it later becomes the backend's sender (286 road C —
   the iPhone's only way to the speakers)? Lean yes: it costs a small `expect` now and saves writing it twice.
