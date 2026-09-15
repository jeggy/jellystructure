# Ravilo on phones: the player, mobile fit, Chromecast, and an iOS target

**Date:** 2026-09-16
**Status:** investigation only — nothing built, nothing spec'd. Prospective phase numbers at the end
are proposals against the next free numbers on this date (**216 / R243**) and must be re-verified
against `STATUS.md` before any of them is taken.

> Asked 2026-09-16: *"We want to fix our mobile app, so the video player experience becomes perfect and
> enterprise-like feeling. We want everything to look good and nice and mobile friendly. And we want
> proper support for Chromecast."* — then, mid-investigation: *"And we want to work across Android and
> iOS phone devices."*

This report answers four questions against the code as it stands today: what the phone app actually
is, how far its player is from what a viewer expects of a mobile video app, what "proper Chromecast"
can mean here (three architectures, one recommended), and what an iOS target would cost given that
every playback seam is ExoPlayer or a DOM `<video>`.

---

## 1. What the phone app is today

**One universal APK, two activities.** Since R224 `:ravilo-android` ships both the TV entry
(`android.MainActivity`, leanback, immersive) and the phone entry (`phone.MainActivity`, portrait-locked,
normal system bars). The old `:ravilo-phone` module is gone; only a stale `ravilo-phone/build/` directory
remains on disk. Both activities compose the same `RaviloRoot()` from `:ravilo-ui`. Play Store setup is
done (R215 `✓ Done` 2026-09-12); the listing is the one universal app.

**Phone detection is by window size, not platform.** `LocalCompact` (window width < 600 dp) drives
gutters and detail layout (R145); `LocalHandset` (smallest side < 600 dp, orientation-stable) drives the
few "is this actually a phone" sites; `isTvPlatform` (Android `UI_MODE_TYPE_TELEVISION`) gates
platform-level decisions (R234). These three signals exist and are correct. What is thin is how many
screens consult them:

| Screen / component | Phone handling today |
|---|---|
| `PlayerScreen.kt` (3 691 lines) | 6 sites: tap-to-toggle chrome, cold-start sizes. **Chrome layout itself is the TV one** (see §2). |
| `RaviloApp.kt` | computes compact / handset / portrait, provides them. |
| `AppBar.kt` | compact → horizontally scrolling top tab strip (the TV nav pattern, made scrollable). No bottom navigation. |
| `SettingsScreen.kt`, `AccountScreens.kt` | R229 / R234 fixes. |
| `MovieDetailScreen.kt`, `SeriesDetailScreen.kt` | full-width hero, `raviloHPad` gutters. |
| `HomeScreen.kt`, `ContentRow.kt`, `Tile.kt` | **no** compact/handset site. Tiles are fixed 155×232 dp posters; rows are the TV rows with a 20 dp gutter. |
| `LiveTvGuideScreen.kt`, `LiveTvPlayerScreen.kt` | **zero** phone handling. |
| `BrowseScreen.kt`, `SeededBrowseScreen.kt`, `DiscoverScreen.kt` | gutters only; facet popovers were designed for D-pad capture. |

Every phone bug fixed so far (R227 profile menu unclosable, R229 settings squeeze, the R195 picker
having no touch dismissal, the tap-to-toggle-chrome regression in landscape) was found by someone
using the phone, not by a systematic pass. There has never been one.

**Design side: there is no phone player design.** `design/ravilo/Ravilo Mobile.html` is a Pixel 9 frame
containing exactly three screens — Settings, Your profile, Change password — and the word "player"
does not appear in it. The only phone frames of the player anywhere are the two R218 frames (cold start,
mid-playback stall) in `Player Loading and Buffering - Directions.html`. There is no phone Home, Detail,
Browse or Live TV mockup either. Given this project's rule that the mockups are the visual target, the
phone work below is design-first by construction.

---

## 2. The player on a phone, measured against what a viewer expects

### 2.1 What it is

`PlayerScreen` renders the **TV chrome verbatim** on a phone: a 68 dp top bar and a bottom transport
column both padded `horizontal = 48.dp`, a 230 dp / 280 dp scrim pair, kicker + 20 sp title, seek row,
then a control row of **−10s · play/pause · +30s · Audio & subtitles · Next**. Every button is a
D-pad-styled pill with a hover/focus ring. `PlayerImmersiveEffect` forces
`SCREEN_ORIENTATION_SENSOR_LANDSCAPE` and hides the system bars for as long as the screen is composed.
The engine is a `RaviloPlayer` remembered **per screen** (an `ExoPlayer` owned by the Activity's
composition, no service), and `PlayerLifecycleEffect` **ends the playback session on `ON_STOP`** and
re-arms it on return — correct for a TV that was switched off, wrong for a phone that was locked or
switched to another app mid-episode.

The concessions that *are* phone-specific and correct: tap on video toggles chrome (keyed on
`LocalHandset`, fixed after the `LocalCompact` rotation bug), drag-to-scrub on the seek bar (R157),
touch parity in the two-level picker plus a tap-away scrim (R195), R218's phone-sized cold-start
treatment, R237's per-cause failure copy.

### 2.2 The gap table

"Enterprise-like" on a phone means the conventions Netflix, Disney+, YouTube and Plex have made
invisible. Status against the code:

| Convention | Status | Where / why |
|---|---|---|
| Tap to show/hide controls, auto-hide | ✓ | `CHROME_HIDE_MS = 3 600` |
| Drag to scrub | ✓ bar only | `SeekBar` `detectDragGestures`; no thumbnail preview — `trickplayUrl` is always `null` in `PlaybackService.startPlayback` |
| Double-tap left/right to seek ±10 s | ✗ | only the pill buttons; `detectTapGestures` has no `onDoubleTap` |
| Swipe up/down for volume (right) / brightness (left) | ✗ | no gesture surface, no brightness API in any seam |
| Pinch to fill / aspect toggle | ✗ | `PlayerVideoSurface` letterboxes by DAR only (R77) |
| Portrait playback / rotate button / rotation lock | ✗ | forced sensor-landscape; a phone held upright has no player at all |
| Lock controls (child-proof) | ✗ | — |
| Playback speed | ✗ | `RaviloPlayer` has no speed member; would need seam + Wasm + (later) iOS actuals |
| Audio / subtitle picker | ✓ | R180/R195 two-level, flags, touch parity |
| Subtitle size / style on a phone | ✗ | `SubtitleView` styling is the TV treatment (R55/R110) |
| Skip intro / next-up / episode rail | ✓ but TV-sized | `SkipIntroPill` anchored `end 28 dp / bottom 160 dp`; `NextUpCard` and `EpisodeRail` are TV cards at TV sizes — unverified against a 412 × 915 frame |
| Background / audio-only playback | ✗ | session ends on `ON_STOP` by design; engine is Activity-scoped |
| Picture-in-picture | ✗ | — |
| Lock-screen / notification / headset buttons | ✗ **by decision** | R193: *"The phone app never creates an OS-level `MediaSession`, full stop"* — chosen to stop the phone advertising its own session across devices; it also removed Bluetooth transport keys and the media notification on the phone |
| Cast button | ✗ | no Cast/`MediaRouter` code anywhere (R192 confirmed the same grep) |
| Quality / data-saver / Wi-Fi-only | ✗ | see 2.3 — a phone on cellular gets **no** bitrate cap at all |
| Safe areas (notch, punch-hole, gesture bar) | ✗ | fixed 48 dp gutters; `viewport-fit=cover` exists on web only; no `WindowInsets` reads in the player |
| Haptics on seek / skip | ✗ | — |
| Loading / stall / failed-start states | ✓ | R218 (phone sizes), R220, R237 |
| Resume / Continue Watching | ✓ | Jellyfin-backed, unchanged |
| Offline downloads | ✗ | not in scope of this ask; noted as the next thing viewers expect after Cast |
| Live TV player on phone | ✗ | `LiveTvPlayerScreen` has no touch or size handling at all |
| TalkBack / accessibility | unknown | not examined |

### 2.3 The one gap that is a backend fact, not a UI choice: no cap on cellular

Phase 177 + R216 give the server two inputs for `MaxStreamingBitrate`: the device's decoder ceiling and
a **link-derived** cap from `detectLinkState()`. That detector handles `TRANSPORT_ETHERNET` and
`TRANSPORT_WIFI` (WifiInfo link speed) and returns `LinkState.UNKNOWN` for everything else — including
cellular. `ClientCapabilities.linkKind = "unknown"` means, by its own doc, *"Phase 177's link-derived
MaxStreamingBitrate cap never applies"*. A phone on LTE therefore direct-plays a 93 Mbps remux exactly as
the stue TV did before Phase 177, with the extra cost that it is someone's data plan. R183 also
established that a `VideoBitrate` *condition* disqualifies direct play, so the right knob is
`MaxStreamingBitrate` on PlaybackInfo (already the Phase 177 path), fed by a viewer-facing quality
setting rather than a link guess. This is the single most consequential mobile change and it is small.

---

## 3. Chromecast — three architectures

Ground facts that shape all three:

- **The stream URL is already Cast-shaped.** `StreamTicket.hlsUrl` is a direct Jellyfin URL with
  `api_key=<token>` in the query (direct play: `/Videos/{id}/stream?Static=true&…&api_key=…`; transcode:
  Jellyfin's `TranscodingUrl`). A receiver can fetch it with no headers. `StreamTicket` also carries
  `jellyfinBaseUrl`, `accessToken`, per-track `SubTrack` URLs (VTT sideloads) and `AudioTrack` metadata.
- **A Google Cast receiver never direct-plays MKV.** Jellyfin's own receiver profile declares
  `mp4,m4v` and `webm` containers, HLS/fMP4 as the transcode target, external VTT only, no burn-in
  ([deviceprofileBuilder.ts](https://github.com/jellyfin/jellyfin-chromecast)). Whatever sender is
  built, **every cast session of this MKV-heavy library is a Jellyfin remux or encode**, inside the
  Phase 182/183 gates and NVENC budget. That is a capacity fact to plan around, not a blocker.
- **The house already has a "play on a TV" API.** Phase 111 `POST /api/remote/play` +
  `/api/remote/command` push play / pause / stop / seek / home to any connected Ravilo device over its
  `/api/tv/events` socket, and R155 handles them on the TV. It is fenced to API-key auth today.
- **`shared` already compiles to plain JS** (`js(IR)` for the Tizen client, which uses `TvApiClient`
  from it). A JS web receiver could reuse the Kotlin API client instead of re-implementing it.

### A. Jellyfin's own receiver (the stopgap)

Jellyfin ships two Cast web receivers with the server (stable `F007D354`, unstable `6F511C87`; the
user's `CastReceiverId` setting selects one — confirm both IDs against the server's own list before
relying on them). The sender sends a custom-namespace message (`urn:x-cast:com.connectsdk`) carrying
`userId`, `deviceId`, `accessToken`, `serverAddress`, `serverId`, `maxBitrate`, subtitle appearance and
item stubs; the **receiver itself** calls `PlaybackInfo` with its runtime-probed profile, picks direct
vs transcode, builds the URL, and answers `SetAudioStreamIndex` / `SetSubtitleStreamIndex` / `Seek`
([jellyfin-web chromecastPlayer plugin](https://github.com/jellyfin/jellyfin-web/blob/master/src/plugins/chromecastPlayer/plugin.js),
[jellyfin-chromecast playbackManager.ts](https://github.com/jellyfin/jellyfin-chromecast)).

- **Pro:** exists, maintained, handles negotiation and track switching; Media3's `CastPlayer` is not
  even needed — the Cast SDK's `CastSession.sendMessage` is the whole integration.
- **Con, and it is the deciding one:** it makes jellystructure a bystander. The receiver talks to
  Jellyfin directly, so the playback tracker, Phase 180 teardown, R216 QoE, `requireVisible()` gating,
  per-user library ACL, kids gating and R183 pacing all see nothing — the exact class of session this
  project has already documented as "architecturally unreachable" (the Wholphin finding in
  `ravilo-per-device-decode-ceiling-warning-2026-09-02.md`). It also requires the **phone to hold a
  durable Jellyfin user token**, which Phase 141/175 deliberately keep server-side behind the device
  token. Continue Watching would still work (Jellyfin receives progress from the receiver).
- **Verdict:** build only as a one-day spike to validate the Cast SDK wiring, never as the product.

### B. A Ravilo web receiver (recommended)

A CAF web receiver is an HTML/JS page. Register a Cast app in the Google Cast Developer Console
(one-time fee; yields the receiver app ID), host the page over HTTPS where the Chromecast can reach it
(the `web-static-server` image behind the existing TLS termination on `jelly.example.net` is the natural
home), and make it a **Ravilo device**: it calls `POST /api/tv/playback/start` with the Chromecast's own
capabilities (CAF's `canDisplayType` answers HEVC / VP9 / HDR per device generation at runtime),
receives a `StreamTicket`, and plays `hlsUrl` through CAF's built-in player with the ticket's VTT
sideloads as text tracks. Progress, stop, QoE and restream-for-burn-in are the same routes the TV uses.

- Every jellystructure invariant keeps working; the Chromecast shows up in **Users & devices** like any
  other device; R222's slow-to-start note, R237's failure copy, R182's 503 + `Retry-After` all apply.
- The phone is the remote: Cast SDK on Android (`media3-cast` `CastPlayer` with a custom
  `MediaItemConverter`, or `RemoteMediaClient` directly), `MediaRouteButton` in the app bar / detail /
  player, Output Switcher discovery via `MediaTransferReceiver`, a mini-controller sheet while casting.
- The receiver can honour **next-episode auto-advance and Skip Intro** on the TV, because it reads the
  same detail payload and segment markers the TV app reads — none of that exists in architecture A.
- **Open auth question, needs a decision before spec:** the receiver needs a Bearer device token.
  Either (i) the phone hands its own token in `customData` on LOAD — simplest, Jellyfin then sees the
  phone's per-(device,user) identity, "Living room Chromecast" never appears as its own device; or (ii)
  the receiver enrols as its own `ravilo_device` via a short-lived hand-off code minted by the phone's
  session — cleaner, more work, and it is what makes the Chromecast a first-class row in Users & devices.
  Recommend (i) first, (ii) as a follow-up.
- **Verify before building:** that the `jellyfin_base_url` in the ticket is reachable from a Chromecast
  on the LAN (a CAF receiver loaded over HTTPS may fetch LAN `http://` media, but this must be tried on
  the real dongle, not assumed), which Chromecast generations the house has (1st/2nd gen: H.264 1080p;
  Ultra: HEVC/VP9 4K HDR; Chromecast with Google TV: HEVC, AV1, DV), and NVENC headroom for one more
  concurrent encode per cast session.

### C. Play on a Ravilo TV (cheap, orthogonal, worth doing regardless)

Reuse Phase 111: a **"Play on Stue TV"** action on the phone's detail screen that calls `/remote/play`
under device-token auth (today it is API-key only — a small route change), listing the viewer's own
connected Ravilo devices. Zero Google SDK, works for the Android TVs and the Tizen client, native TV
playback (MKV direct play, DV, FFmpeg audio decoders) with the phone as a remote via `/remote/command`.
In a household where every screen already runs Ravilo, this is the higher-value "cast" and it is a few
days of work.

**Later, the two meet: Cast Connect.** With a registered Cast app whose Android TV receiver flag is
on, and `CastReceiverContext` added to the TV activity, the phone's ordinary Cast button lists the
Android TV, and tapping it launches Ravilo TV with the item — native playback, Cast-standard remote.
Architecture B's web receiver remains the fallback for dongles. This is the end state; it is not the
first step.

**Recommendation:** C now (it is nearly free), B as the Chromecast phase, Cast Connect after B. A only
as a spike.

---

## 4. iOS — what it takes

**There is no iOS target anywhere.** `shared` builds for `androidTarget`, `linuxX64`, `wasmJs` and
`js(IR)`; `ravilo-ui` for `androidTarget` and `wasmJs`. No `ios*` source set, no Xcode project, no
Swift, no `AVPlayer` in the repo. The good news is that both `commonMain` trees are clean — no `java.*`,
`android.*`, `org.w3c` or `kotlinx.browser` imports — and the toolchain is already there: Compose
Multiplatform 1.9.3 (iOS stable since 1.8.0, May 2025), Kotlin 2.3, Ktor 3.5 (Darwin engine available),
Coil 3 (iOS supported), kotlinx-datetime. Compose resources already carry the fonts.

### 4.1 Bring-up (no player)

- Add `iosArm64` + `iosSimulatorArm64` to `shared` and `ravilo-ui`; a thin `:ravilo-ios` Xcode app
  hosting `ComposeUIViewController`; a macOS runner in CI; an Apple Developer account and TestFlight.
- **30 `expect` declarations in `ravilo-ui` need iOS actuals.** Grouped: storage ×4
  (`TokenStore`, `DeviceIdStore`, `MultiTokenStore`, `PlaybackPrefsStore` → `NSUserDefaults`, tokens in
  Keychain), routing ×3 (`pushRoute`/`replaceRoute`/`installHashListener` → no-ops), `PlatformBackHandler`
  (iOS has no back button; edge-swipe is Compose's), `rememberExitAction` (no-op — iOS apps do not
  exit), `deviceDisplayName`, photo pickers ×2 (`PHPickerViewController`, camera), `systemPrefersReducedMotion`,
  `FrameTracker`, `TrailerEmbed` (`WKWebView`), `reportTextFieldFocus`, `wakeOnPointerMove` /
  `setPointerCursorHidden` (no-ops), `HomeSnapshotCache`, `isTvPlatform = false`, `createTvApiClient`
  (Darwin engine), `raviloBaseUrl`/`saveBaseUrl`, and the **player seams ×9** below.
- `shared` has no `expect` declarations at all.

### 4.2 The player is the hard part

`RaviloPlayer`, `PlayerVideoSurface`, `PlayerImmersiveEffect`, `PlayerLifecycleEffect`,
`PlayerChromeBridge`, `detectHdrSupport`, `detectDecoderLimits`, `detectLinkState`,
`supportsEmbeddedTextSubtitles`, `playerBackdropColor`, `playerTapTogglesChrome` all need an iOS actual,
and the engine choice decides what the library looks like from an iPhone:

| | AVPlayer (native) | VLCKit |
|---|---|---|
| MKV direct play | **no** — MP4/MOV/TS/HLS only | yes |
| DTS / TrueHD | no (transcode) | DTS yes, TrueHD no |
| Dolby Vision | profiles 5, 8.1, 8.2, 8.4 natively | partial, profile 5 unsupported |
| Subtitles | VTT / TTML / CC only; PGS needs server burn-in | ASS/SRT/PGS natively |
| AirPlay, PiP, lock screen, headset | free | manual / partial |
| Licence | Apple | LGPL (`MobileVLCKit`) |

(Feature matrix per Swiftfin's own [players.md](https://github.com/jellyfin/Swiftfin/blob/main/Documentation/players.md);
Swiftfin ships both engines for exactly this reason.)

**Recommendation: AVPlayer + `hlsOnly = true`.** `ClientCapabilities.hlsOnly` already exists (Live TV
sets it), so an iOS client that declares it gets a Jellyfin HLS/fMP4 remux or transcode for every file —
codec-compatible files (H.264/HEVC + AAC/AC3/EAC3) remux without an encode, everything else encodes
under the same R183 target-profile rules. The cost is one Jellyfin session per iOS play, the same cost
as Chromecast, and the reward is that AirPlay, PiP, the lock screen and background audio are the
platform's, not ours. VLCKit is the fallback if remux latency (Phase 179's subtitle-sideload stall
applies here too) proves unacceptable. The seam's `load(streamUrl, …, subtitles, audio, …)` shape
already fits AVPlayer's `AVPlayerItem` + sideloaded VTT through `AVMediaSelection`, and the R218
moments map onto `AVPlayerItem.status` / `isPlaybackLikelyToKeepUp`.

### 4.3 Chromecast and AirPlay on iOS

Google Cast iOS SDK 4.8.6 (iOS 16+, CocoaPods or manual XCFramework, no SwiftPM, Objective-C API usable
from Swift) — [ios_sender](https://developers.google.com/cast/docs/ios_sender). It needs
`NSLocalNetworkUsageDescription` and Bonjour entries. Rather than cinterop against the framework, put
the Cast integration behind a small Kotlin `expect interface CastController` with a **Swift actual**;
the Android actual wraps `media3-cast`. The same Ravilo web receiver (architecture B) serves both
senders unchanged. AirPlay comes with AVPlayer and is what iPhone owners reach for first; a
`AVRoutePickerView` beside the Cast button covers it.

---

## 5. What is design work, not code

1. **A phone player directions canvas** (round 1): portrait *and* landscape frames of the chrome,
   gesture zones (double-tap seek, swipe volume/brightness), a locked state, the picker as a bottom
   sheet, next-up and skip-intro at phone scale, the mini-controller and expanded controller while
   casting, safe-area behaviour on a punch-hole phone. Nothing of this exists.
2. **A phone Home / Detail / Browse pass**: tile scale for a 412 dp column, whether the top tab strip
   becomes a bottom bar (the TV navbar concepts are the only nav explorations on file), Live TV on a
   phone, the facet popovers as sheets.
3. Both extend `Ravilo Mobile.html` and `mobile/ravilo-mobile.css`, which today carry only R234's three
   screens. `LocalHandset` is the switch that keeps the TV untouched while all of this lands.

---

## 6. Proposed phasing (prospective numbers, next free today: 216 / R243)

Ordered so that each phase is independently shippable and the earliest ones are the cheapest wins.

| # | Scope | Design first? | Depends on |
|---|---|---|---|
| **216 + R243** | **Mobile data policy.** Viewer setting *Quality on mobile data* (Auto / Data saver / Always high) and *Wi-Fi only*; client reports `link_kind = "cellular"`; server feeds `MaxStreamingBitrate` from it (never a `VideoBitrate` condition — R183). | no | — |
| **R244** | **Phone player chrome.** Handset layout via `LocalHandset`, safe-area insets, double-tap seek, rotate button + portrait player, lock, playback speed (new seam member, Wasm actual too), subtitle size, phone-scale next-up / skip-intro / picker sheet. TV chrome untouched. | **yes** (§5.1) | — |
| **R245** | **Phone playback lifecycle.** `MediaSessionService` + media notification + PiP + audio-only continuation; revisits R193 with a *local-only* session on phone (R193's concern was cross-device surfacing, which `MediaSession` visibility controls; the notification is the feature on a phone). Backend half: the `ON_STOP → stopSession` contract becomes pause + heartbeat, reconciled with Phase 180 teardown and the watchdog. | no | R244 |
| **R246** | **Play on a Ravilo TV.** Phase 111 routes under device-token auth, "Play on …" on the detail screen, remote transport while it plays. | light | — |
| **217 + R247** | **Chromecast.** Ravilo web receiver (architecture B, `shared` as JS, CAF player, HTTPS hosting, Cast app registration), Android sender (`media3-cast`, `MediaRouteButton`, Output Switcher, mini/expanded controller), receiver auth hand-off (option i). | yes (controllers) | R245 for the notification path |
| **R248** | **Cast Connect** on the Android TV activity. | no | R247 |
| **R249** | **Phone-wide mobile pass** — Home, Browse, Detail, Live TV guide + player, Discover on a handset. | **yes** (§5.2) | — |
| **R250** | **iOS bring-up** (targets, 21 non-player actuals, Xcode app, macOS CI, TestFlight). | no | — |
| **R251** | **iOS player**: AVPlayer + `hlsOnly`, the 9 player-seam actuals, AirPlay, PiP. | no | R250, R244 |
| **R252** | **iOS Cast sender** (Swift actual behind `CastController`). | no | R247, R251 |

R243–R245 are the "perfect player" work; 216/R243 alone removes the worst mobile failure mode. R246 is
the fastest route to "watch this on the TV from my phone". 217/R247 is the Chromecast ask proper.
R250–R252 are the iOS ask and are gated on a Mac, an Apple account, and the AVPlayer-vs-VLC decision.

---

## 7. Open questions — for the owner, not guessed at

1. **Which Cast devices are in the house?** Generation decides whether HEVC/4K ever direct-streams on
   Cast or every 4K title is a 1080p H.264 encode.
2. **Is Jellyfin reachable from a Chromecast at the URL the ticket carries** (LAN `http://` vs the
   public `https://` name)? Try it on the dongle before writing the receiver.
3. **NVENC headroom.** Every Cast and every iOS session is a Jellyfin remux/encode. What concurrency is
   acceptable alongside the TVs' own transcode fallbacks and the Phase 182/183 gates?
4. **Receiver identity** — the phone's token (fast) or a device of its own (clean)? See §3-B.
5. **Should phone playback keep its session open while backgrounded** (R245), given Phase 180's
   teardown was written to stop invisible work? The answer is "pause with heartbeat, tear down on a
   timeout", but the timeout is a product number.
6. **iOS engine**: AVPlayer-only (every play is HLS) or ship VLCKit alongside as Swiftfin does?
7. **Apple side**: is there a Mac for the build, an Apple Developer account, and is TestFlight-only
   distribution acceptable for the household?
8. **Apple TV** in the house? If yes, AirPlay matters more than Chromecast for the iPhone owners.

---

## 8. Source references

Code (this repo, `main` at `fbee64fe`):
- `ravilo-android/src/main/AndroidManifest.xml`, `…/phone/MainActivity.kt`, `…/android/MainActivity.kt`
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`
  (constants 120–150, `PlayerImmersiveEffect()` 1122, tap-to-toggle 1329, `PlayerChrome` 1697–1850,
  `SkipIntroPill` anchor ~1595)
- `…/ui/seams/RaviloPlayer.kt` (seam members), `…/androidMain/…/seams/RaviloPlayerAndroid.kt`
  (`MediaSession` 183–200), `PlayerLifecycleEffect.kt` (`ON_STOP` → `onBackground`),
  `PlayerImmersiveEffect.kt` (sensor landscape), `PlayerVideoSurface.kt` (SurfaceView, DAR), `HdrCapabilities.kt`
  (`detectLinkState` 138: ethernet/wifi only)
- `…/ui/theme/Dimens.kt` (`LocalCompact`, `LocalHandset`), `…/ui/Platform.kt` (`isTvPlatform`),
  `…/ui/RaviloApp.kt` 550–575, `…/ui/components/Tile.kt` 58–63
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt` (`ClientCapabilities` 62–112,
  `StreamTicket` 171–183, remote envelopes 640–660)
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` 402–408 (stream URL), 441–453 (ticket)
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/RemoteRoutes.kt` (Phase 111 `/remote/play`, `/remote/command`)
- `src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt` 40–60 (`deviceProfile`, `MaxStreamingBitrate`)
- `ravilo-tizen/build.gradle.kts` (`js(IR)` + `projects.shared`), `ravilo-ui/src/wasmJsMain/…/RaviloPlayerWasm.kt` (hls.js / native HLS)
- Specs: R145, R192, R193, R195, R218, R224, R227, R229, R234, 111, 177, 180, 183; `ravilo-per-device-decode-ceiling-warning-2026-09-02.md`
- Design: `design/ravilo/Ravilo Mobile.html`, `design/ravilo/Player Loading and Buffering - Directions.html`

External (fetched 2026-09-16):
- [Media3 Cast — Getting started with CastPlayer](https://developer.android.com/media/media3/cast/create-castplayer) · [Media3 Cast overview](https://developer.android.com/media/media3/cast) · [CastOptions](https://developer.android.com/media/media3/cast/customize-castoptions)
- [ExoPlayer cast demo README](https://github.com/google/ExoPlayer/blob/release-v2/demos/cast/README.md)
- [jellyfin-chromecast](https://github.com/jellyfin/jellyfin-chromecast) (receiver; `playbackManager.ts`, `deviceprofileBuilder.ts`) · [jellyfin-web chromecastPlayer plugin](https://github.com/jellyfin/jellyfin-web/blob/master/src/plugins/chromecastPlayer/plugin.js) · [Jellyfin cast receiver configuration](https://github.com/jellyfin/jellyfin-meta/issues/45)
- [Google Cast iOS sender setup](https://developers.google.com/cast/docs/ios_sender)
- [Compose Multiplatform 1.8.0 — iOS stable](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)
- [Swiftfin players.md — Native vs VLC](https://github.com/jellyfin/Swiftfin/blob/main/Documentation/players.md)
- [Background playback with a MediaSessionService](https://developer.android.com/media/media3/session/background-playback)
- [Adding Swift packages / cinterop in KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-spm-import.html)
