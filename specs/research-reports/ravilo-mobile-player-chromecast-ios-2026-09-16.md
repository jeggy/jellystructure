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
(one-time US$5 fee; yields the receiver app ID; the app must be **published** before it works on anyone
else's Chromecast — unpublished apps run only on devices registered to the developer account), and make
the page a **Ravilo device**: it calls `POST /api/tv/playback/start` with the Chromecast's own
capabilities (CAF's `canDisplayType` answers HEVC / VP9 / HDR per device generation at runtime),
receives a `StreamTicket`, and plays `hlsUrl` through CAF's built-in player with the ticket's VTT
sideloads as text tracks. Progress, stop, QoE and restream-for-burn-in are the same routes the TV uses.

**Hosting — decided (owner, 2026-09-16, second and third follow-ups): two receivers, same code, the
shared one is the default.** The owner first asked for jellystructure to host everything and no GitHub
Pages, then allowed GitHub Pages *"if it's too hard to host it on the jellystructure end"*. Hosting on
jellystructure is **not hard**: the backend already serves the admin frontend's static files
(`Server.kt` `serveFrontendFile`), and the receiver is one more static bundle at
`https://<jellystructure>/cast/` (CAF's framework script comes from Google's CDN as the SDK requires;
the Ravilo receiver code is `shared` compiled to JS plus thin CAF glue). What *is* hard is what
per-installation hosting forces on **every admin**: a Cast app ID is bound to **one receiver URL at
registration**, so a self-hosted receiver means every installation registers its own Cast application
with Google (US$5 once, a console visit, publish or test-device steps) before the first cast works.
A project-hosted receiver lets the project register **one published app ID that works on every
Chromecast with zero setup**, because the page is static and takes the viewer's server URL at LOAD.

So: the project publishes the receiver bundle to **GitHub Pages from CI** and registers **one Ravilo
app ID** against it — that is the default, and for most households the only step is the enable switch.
The **same bundle is also served by every jellystructure at `/cast/`**, and the Settings card offers
*"host the receiver on this server"* for admins who want no project dependency; choosing it requires
their own Google registration, which the card walks through. The owner's own household can run either.
Either way the only paid or external step is Google's **one-time US$5 developer registration** (nothing
recurring, nothing Apple — AirPlay is dropped), and it is configurable inside jellystructure, never in
code or on a dongle.

**Requirement: Settings → Chromecast card.** Off by default (off means the server tells the phone there
is nothing to cast to and no Cast button is shown — server-pushed state, never a client guess).
Receiver choice: *Ravilo's shared receiver* (recommended, pre-filled app ID, nothing to do) or *Hosted
by this server*, which reveals: (1) this server's receiver URL, read-only and copyable, with a
self-check that it is reachable over public `https://` (the backend fetches its own `/cast/` through
the configured public URL); (2) "Register an application at the Google Cast Developer Console (US$5
once), choose *Custom Receiver*, paste that URL"; (3) "Add your Chromecast as a test device, or publish
the application so every Chromecast can use it"; (4) paste the **Application ID** here. Plus the
concurrent cast-session ceiling from answer 3 and a status line (receiver reachable · app ID in use ·
last cast · devices that have cast, linking to Users & devices). jellystructure cannot verify an app
ID with Google (there is no API for it); the honest check is a first cast from the admin's own phone,
which the card should say.

**Sender consequence:** the receiver app ID is per installation, so the phone learns it from the server
config, not from a manifest constant. On Android `CastContext.setReceiverApplicationId(String)` changes
it at runtime ([CastContext reference](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/CastContext)),
so the sender initialises Cast with a placeholder and applies the real ID once the config snapshot
loads; on iOS `GCKCastContext` options are set once per launch, so the ID is read before Cast is
initialised and a changed ID takes effect on the next app start (*verify on the iOS SDK when that phase
comes*). Because the owner's Jellyfin is always public `https://` (answer 2), media reachability from the
dongle is settled for this house; for other households the receiver must show a plain "can't reach your
server" state rather than spin.

- Every jellystructure invariant keeps working; the Chromecast shows up in **Users & devices** like any
  other device; R222's slow-to-start note, R237's failure copy, R182's 503 + `Retry-After` all apply.
- The phone is the remote: Cast SDK on Android (`media3-cast` `CastPlayer` with a custom
  `MediaItemConverter`, or `RemoteMediaClient` directly), `MediaRouteButton` in the app bar / detail /
  player, Output Switcher discovery via `MediaTransferReceiver`, a mini-controller sheet while casting.
- The receiver can honour **next-episode auto-advance and Skip Intro** on the TV, because it reads the
  same detail payload and segment markers the TV app reads — none of that exists in architecture A.
- **Receiver identity — decided (owner answer 4): the receiver is its own Ravilo device.** The phone's
  LOAD carries a short-lived hand-off code minted by the phone's session; the receiver redeems it for
  its own device token and `ravilo_device` row, so it appears in **Users & devices** as e.g. *"Living
  room Chromecast"*. Phase 110 then gives the rest for free: a device that opens `/api/tv/events` gets
  its own **named Jellyfin session** (`Client="Ravilo", Device="<name>"` via `JellyfinDeviceIdentity`)
  and the **Jellyfin dashboard's pause/stop/seek already reach it** through `JellyfinSessionBridge` —
  the owner's "control the pause from the Jellyfin dashboard" is an existing feature once the receiver is
  a device. The `Device` string should read *"Chromecast via Ravilo · Living room"* (or the Client field
  becomes *"Ravilo Cast"*) so the dashboard says what it is; a small identity change in `forDevice`.
- **Which Chromecasts (owner answer 1):** open source means every generation. Facts: 1st gen (2013,
  H.264/VP8 1080p30) had its **last firmware in November 2022 and lost support in April 2023**; 2nd/3rd
  gen are H.264 1080p; Ultra adds HEVC/VP9 4K HDR10/DV; Chromecast with Google TV adds AV1 and HDR10+.
  Whether a CAF v3 receiver still launches on a 1st-gen stick is **not confirmed by any page fetched**
  and must be tested on a real one — the benchmark is Jellyfin's own receiver on the same stick: if that
  runs, ours will. The receiver never assumes a codec; it probes `canDisplayType` and lets jellystructure
  negotiate, so an old stick simply gets an H.264 1080p HLS encode. The parents' "old chromecast stick
  on an LG TV" is the acceptance device for this whole phase; identify its generation first (1st gen is
  the flat "dongle with a rounded end", 2nd/3rd gen are round discs).
- **Transcoding capacity (owner answer 3, "what is NVENC?"):** NVENC is the video encoder built into
  Nvidia GPUs; Jellyfin uses it (or the CPU) to transcode. Every cast session is one such encode. For
  this house the question is how many run at once next to the TVs' own fallbacks; for open source it
  becomes a jellystructure Settings number and an honest "your server is busy" state on the receiver,
  fed by Phase 182's 503 + `Retry-After`.
- **Session lifecycle (owner answer 5, confirmed in follow-up): the TV keeps playing even if the phone
  dies.** This is why the receiver holds its **own** device token: heartbeats, stop, next-episode
  auto-advance and Skip Intro all run on the receiver, and nothing about the session depends on the
  phone being alive. **Re-connect is a requirement:** when the app starts (or returns) while a cast is
  running, the Cast SDK's session resumption re-attaches to the receiver, the phone shows a
  *reconnecting* state, then the mini bar, and the remote rebuilds its state from the receiver (item,
  position, tracks, next-up) — never from anything the phone remembered before it died. If the receiver
  has finished or is gone, the phone shows nothing and the viewer starts a new session with one tap.
  "Super simple and fast" is a sender requirement: the Cast button is on every screen's app bar, one
  tap opens the system device picker, and pressing Play while connected casts instead of playing
  locally, with the current position handed over.

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

**Decision (owner answer 6): do what Swiftfin does, with no user option — VLCKit.** Swiftfin's default
and recommended engine is its VLCKit-based "Swiftfin" player, and its maintainers have said the Native
player will be removed in favour of consolidating on it. VLCKit gives the iPhone what the Android TV has:
MKV direct play, DTS, ASS/PGS subtitles, and a decoder-capability answer that lets jellystructure
negotiate the best quality exactly as it does for the TV today (`detectDecoderLimits` / `detectHdrSupport`
actuals report VideoToolbox H.264/HEVC hardware limits; software fallback for the rest). What it costs
against AVPlayer: PiP and the lock-screen card are not free and come later or not at all; HDR
tone-mapping is weaker. **AirPlay is dropped outright (owner, 2026-09-16 follow-up)** — not designed,
not built, not a consideration in the engine choice — which removes the strongest argument for AVPlayer
and makes VLCKit the clear choice. AVPlayer + `hlsOnly = true` stays on file
as the alternative (every play becomes a Jellyfin HLS remux/encode; AirPlay/PiP/lock screen free) if
VLCKit's build or licence footprint (LGPL, same containment as R31's GPL decoders) proves a problem.
The seam's `load(streamUrl, …, subtitles, audio, …)` shape maps onto `VLCMediaPlayer` + `VLCMedia`
with `addPlaybackSlave` for sideloaded subtitles, and the R218 moments onto VLC's buffering events.

### 4.3 Chromecast on iOS (AirPlay dropped)

Google Cast iOS SDK 4.8.6 (iOS 16+, CocoaPods or manual XCFramework, no SwiftPM, Objective-C API usable
from Swift) — [ios_sender](https://developers.google.com/cast/docs/ios_sender). It needs
`NSLocalNetworkUsageDescription` and Bonjour entries. Rather than cinterop against the framework, put
the Cast integration behind a small Kotlin `expect interface CastController` with a **Swift actual**;
the Android actual wraps `media3-cast`. The same Ravilo web receiver (architecture B) serves both
senders unchanged, and the re-connect requirement (§3-B) applies identically on iOS. AirPlay: dropped
by owner decision, never to be implemented.

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
| **R245** | **Phone playback lifecycle — narrowed by owner answer 5.** Local playback keeps today's contract (pause on background, session ends on `ON_STOP`, no background audio). What remains: a *local-only* `MediaSession` for headset/Bluetooth keys and, while **casting**, the Cast SDK's own notification + lock-screen controls (`CastOptions.NotificationOptions`, no `MediaSessionService` needed). PiP deferred. | no | R244 |
| **R246** | **Play on a Ravilo TV.** Phase 111 routes under device-token auth, "Play on …" on the detail screen, remote transport while it plays. | light | — |
| **217 + R247** | **Chromecast.** Admin half (217): the receiver bundle is **published to GitHub Pages from CI under the project's app ID (default) and also served by the backend at `/cast/`**, **Settings → Chromecast card** (enable, shared vs self-hosted receiver, guided Google registration for the latter, public-https self-check, session ceiling, status), receiver enrolment via hand-off code, Jellyfin session named *"Chromecast via Ravilo"*, dashboard control via Phase 110, app ID pushed to clients in the config snapshot. Ravilo half (R247): web receiver (architecture B, `shared` as JS, CAF player) that keeps playing with the phone dead, Android sender (`media3-cast`, runtime app ID, `MediaRouteButton` on every app bar, Output Switcher, connecting state, **re-connect on app start**, mini bar, full-screen remote — see the design brief). Acceptance device: the parents' old stick. | **yes** (design brief §B/§C/§F) | R245 for the notification path |
| **R248** | **Cast Connect** on the Android TV activity. | no | R247 |
| **R249** | **Phone-wide mobile pass** — Home, Browse, Detail, Live TV guide + player, Discover on a handset. | **yes** (§5.2) | — |
| **R250** | **iOS bring-up** (targets, 21 non-player actuals, Xcode app, MacBook as build host per the companion guide, TestFlight). | no | — |
| **R251** | **iOS player**: VLCKit engine, the 9 player-seam actuals, capability negotiation with jellystructure as on Android TV; no engine option for the viewer. | no | R250, R244 |
| **R252** | **iOS Cast sender** (Swift actual behind `CastController`, Google Cast iOS SDK 4.8.x via CocoaPods); the parents' iPhones → old stick is the acceptance test. | no | R247, R251 |

R243–R245 are the "perfect player" work; 216/R243 alone removes the worst mobile failure mode. R246 is
the fastest route to "watch this on the TV from my phone". 217/R247 is the Chromecast ask proper.
R250–R252 are the iOS ask and are gated on a Mac, an Apple account, and the AVPlayer-vs-VLC decision.

---

## 7. Owner answers (2026-09-16) and what each changed

| # | Question | Answer | Effect on this report |
|---|---|---|---|
| 1 | Which Cast devices? | Unknown, and the project is going **open source**: modern built-in Cast TVs *and* old HDMI+USB-powered sticks must work. | Receiver probes capabilities at runtime, assumes nothing; 1st-gen stick support is a must-test, not a given (§3-B). Receiver hosting moved to one project-owned URL with a self-hoster override. |
| 2 | Jellyfin reachable from the dongle? | Yes — Jellyfin is always public `https://`. | Reachability settled for this house; the receiver still needs an honest unreachable state for others. |
| 3 | NVENC headroom? | "I don't know what NVENC is." | Explained in §3-B: the GPU encoder Jellyfin transcodes with. Becomes a Settings number plus a "server busy" state, not a design-time assumption. |
| 4 | Receiver identity? | **Own device token**, and the Jellyfin dashboard should show the client as *Chromecast via Ravilo*. | Option (ii) chosen. Phase 110 gives the named Jellyfin session and dashboard control for free once the receiver is a device. |
| 5 | Keep the session while backgrounded? | No — pause or fully stop; a new cast session is made from the phone, and starting one must be **super simple and fast**. **Follow-up:** the question was misread as being about casting; for a cast, *"I want the TV to continue playing, even if the phone dies"*, with a re-connect flow when the app starts again. | Local playback stays foreground-only; R245 narrowed to headset keys + Cast SDK notification; PiP deferred. For casting: receiver independence + **re-connect on app start** are requirements (§3-B, design brief §B1/§B2). |
| 6 | iOS engine? | Whatever the existing Jellyfin iOS client does, **without options**; Ravilo and jellystructure negotiate quality like the Android TV. | **VLCKit**, Swiftfin's default and its future single engine (§4.2). |
| 7 | Mac + Apple account? | Yes; development stays on Debian with the MacBook reached over SSH; guidelines wanted. | Companion guide: `ios-build-host-macbook-setup-2026-09-16.md`. |
| 8 | Apple TV / AirPlay? | The Sony stue TV has AirPlay (testing only). Chromecast must work, specifically **parents' iPhones → an old Chromecast stick on an LG TV**. **Follow-up:** *"if AirPlay is not free, then let's just fully stop it here and not consider implementing it at all."* | AirPlay dropped outright (§4.2/§4.3). That stick + iPhone pair is the acceptance test for R247 + R252. |
| — | Follow-up (same day) | *"I'm fine with making it a requirement that we can optionally set up Chromecast within the jellystructure settings."* | Settings → Chromecast card is a requirement of phase 217 (§3-B, design brief §F). |
| — | Second follow-up (same day) | *"No GitHub Pages. I want our jellystructure backend to host anything needed. And if we need to pay (Google only — I don't want to pay for AirPlay) or configure anything to get proper cast support, then this should be configurable within jellystructure (API keys and whatnot)."* | First recorded as "backend hosts the receiver, every installation registers its own app"; superseded by the next row. |
| — | Third follow-up (same day) | *"If it's too hard to host it on the jellystructure end, then GitHub Pages is fine."* | Hosting on the backend is easy; the per-admin Google registration it forces is the hard part. Decided: **both** — the project publishes the receiver to GitHub Pages under one shared app ID (default, zero setup) and every backend also serves it at `/cast/` with a guided own-registration path in Settings. Sender takes whichever app ID the server config carries (§3-B). |

**Still open after the answers:** the generation of the parents' stick (decides whether 1st-gen CAF
support has to be proven) and the concurrent-encode number to pre-fill in the Settings card. Nothing
else blocks the design round.

**Design hand-over:** the screens this report says do not exist are specified for the design tool in
`specs/ravilo/design-brief-mobile-player-and-cast-2026-09-16.md`.

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
