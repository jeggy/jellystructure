# A Google-free Ravilo for Android (F-Droid) that still casts — investigation

**Date:** 2026-09-19 · **Status:** research only, nothing built, no spec · **Author:** dev (Claude) with the owner

## The question

Can Ravilo's Android app ship with **no dependency on Google** (so it is eligible for F-Droid), stay
**Kotlin + Compose**, and **still cast to a Chromecast / Google TV**? The owner had heard of microG and
found [microg/GmsCore#580](https://github.com/microg/GmsCore/issues/580). We are willing to contribute
upstream if that is what it takes.

## Short answer

**Yes, it is possible — but microG is the wrong road for us.** There are two entirely different ways
to read "cast without Google", and they do not overlap:

| | Road A — microG | Road B — speak CASTV2 ourselves |
|---|---|---|
| What it is | Keep Google's proprietary Cast SDK in the APK; hope the *phone* has microG instead of Play Services | Drop the SDK; the app opens a TLS socket to the Chromecast and speaks the protocol directly |
| F-Droid eligible | **No.** The SDK itself (`play-services-cast-framework`) is the proprietary part; F-Droid's scanner rejects it no matter what runs on the phone | **Yes** — pure FLOSS code |
| Works on a stock Google phone | yes (real Play Services) | yes |
| Works on a de-Googled phone | only if microG's Cast is finished — it is not | yes, needs nothing on the phone |
| Who must do the work | microG (8 years open, two unmerged PRs) | us — one Kotlin module |
| Risk | someone else's roadmap | an undocumented (but very stable, widely re-implemented) protocol |

**Recommendation: Road B.** microG matters to us only as a *nice-to-have for the Play-flavoured build
on de-Googled phones*, and Road B makes even that moot.

## 1. What is Google in our app today

A sweep of the build (`libs.versions.toml`, every `build.gradle.kts`, manifests):

- **Exactly one proprietary dependency:** `com.google.android.gms:play-services-cast-framework:22.1.0`.
- `androidx.mediarouter` is Apache-2.0 (AOSP) — F-Droid-fine, though without the Cast SDK's
  `MediaRouteProvider` it discovers no Chromecasts, so the `MediaRouteButton` goes with it.
- No Firebase, no FCM, no Play Billing, no Maps, no Crashlytics, no ML Kit. ExoPlayer/Media3 is Apache-2.0.
- All Cast SDK use is confined to **one 290-line file** behind an existing seam:
  `ravilo-ui/src/androidMain/.../seams/CastSenderAndroid.kt` implements the `CastSender` interface
  (`link`, `deviceName`, `status`, `setAppId`, `load`, `play`, `pause`, `seekTo`, `stop`,
  `selectSubtitle`, `selectAudio`, `send`), plus `RaviloCastOptionsProvider` in the manifest and
  `PlatformCastButton`.

This is an unusually good starting position: most apps that gave up on FOSS casting (AntennaPod, Jellyfin)
had the SDK woven through their player. We have a seam, **our own receiver** (`ravilo-cast`, phase 218)
and **our own namespace** (`CAST_NAMESPACE`) — and R265 already replaced the platform's device dialog
with Ravilo's own three-tier sheet, so losing `MediaRouteButton` costs nothing we still want.

## 2. Road A — microG, and why #580 is not our ticket

What the thread and its PRs actually say (read 2026-09-19):

- Opened **2018-07**, still **open**. microG implements the old Cast **v1** device-controller API; modern
  apps use the **Cast framework** (`CastContext`, loaded as a *dynamite module*) and the
  **connectionless** API (`Cast.API_CXLESS`, service id 161). Logs on microG read
  *"No such module known: com.google.android.gms.cast.framework.dynamite … Cast API will not be functional!"*
- The maintainer (mar-v-in, 2023-07) has not worked on Cast and has limited knowledge of it. A contributor
  who understood the wire protocol gave up on the *framework integration* — the layers between app, SDK
  and GmsCore are a black box.
- Bounties: US$500 (expired end-2025), then ~US$400 on BountyHub (2026-01). Only bot claims so far.
- **Live work, both unmerged:** [PR #3570](https://github.com/microg/GmsCore/pull/3570) (peterhel,
  2026-06, updated to 2026-08-31) implements the connectionless controller and is **validated on real
  hardware** — Prime Video, Netflix and Disney+ play from a Pixel 2 on LineageOS-for-microG. Its own
  stated gaps: `CastMediaRouteController.onSelect` is a stub (companion PR #3567),
  **`registerNamespace` / `unregisterNamespace` are logged no-ops**, no session resume.
  [PR #3781](https://github.com/microg/GmsCore/pull/3781) (2026-09-05) overlaps it, untested on hardware.

Why this does not solve our problem, even if both merge tomorrow:

1. **F-Droid forbids the SDK, not the service.** The inclusion policy: *"proprietary … libraries … such as
   Google Play Services … are strictly forbidden in all applications"*; an app must *"implement either a
   FLOSS alternative or a build flavour that does not require these dependencies"*. microG replaces the
   service on the phone; the proprietary client library is still inside our APK.
2. **The stated gap is exactly what Ravilo needs.** Our remote is driven by a **custom namespace**
   (status, tracks, busy, nextup, noserver) and **session resume with two outcomes** (FR-R245-5). Those
   are the two things #3570 lists as not done.

Where microG *is* relevant: a Play-Store-flavoured Ravilo installed on a /e/OS, CalyxOS or
LineageOS-for-microG phone. If we want to contribute upstream, the useful contribution is
**custom-namespace + session-resume support on top of #3570**, tested against our receiver — but Road B
removes our need for it.

## 3. Road B — a FOSS CASTV2 sender in Kotlin

### The protocol, and the one fact that makes this legal to attempt

CASTV2 = mDNS discovery (`_googlecast._tcp`) → TLS socket to port 8009 (self-signed device cert) →
length-prefixed **protobuf** `CastMessage` frames carrying **JSON** on namespaces
(`…tp.connection`, `…tp.heartbeat`, `…receiver` for LAUNCH/STOP/GET_STATUS/volume, `…media` for
LOAD/PLAY/PAUSE/SEEK/EDIT_TRACKS_INFO/status, plus any custom `urn:x-cast:…`).

**Device authentication runs one way only: the sender may challenge the receiver to prove it is genuine
Google hardware. The receiver never authenticates the sender**, and the challenge is optional. Evidence:

- Every open sender skips it and works: VLC (libvlc, shipped on F-Droid), pychromecast (the engine under
  Home Assistant's Cast integration), catt, node-castv2, go-castv2, chromecast-java-api-v2.
- The natural experiment of **2025-03-09**: the 2nd-gen Chromecast's intermediate CA expired; every
  *official* sender refused to connect for days, while VLC and friends carried on — precisely because
  they do not check.
- Google itself publishes the protocol's reference code: **Open Screen `libcast`** in the Chromium tree
  (BSD-style licence) contains `cast_channel.proto`, the JSON schemas for receiver-control messages, and
  a sender. That is the authoritative spec to build against, not a reverse-engineering blog.

Launching a **custom receiver by app id** and talking on a **custom namespace** are ordinary messages —
pychromecast does both daily. The registration rules are unchanged (they are enforced by the *device*
asking Google whether an app id is allowed): our app id still has to be registered (226), and an
unpublished receiver still needs the Chromecast listed as a test device. So **Google is not fully out of
the picture** — the admin still pays US$5 and the Chromecast still phones home to fetch the receiver
URL. What leaves is every Google *byte in our APK* and every Google *requirement on the phone*.

### Existing libraries — none we should adopt as-is

| Library | Lang | Licence | State |
|---|---|---|---|
| [vitalidze/chromecast-java-api-v2](https://github.com/vitalidze/chromecast-java-api-v2) | Java | Apache-2.0 | 0.11.3; README: *"API is not stable, the quality is pretty low"*; blocking I/O, Jackson + jmdns; effectively unmaintained |
| [thirdegg/chromecast-android-api-v2](https://github.com/thirdegg/chromecast-android-api-v2) | Java | Apache-2.0 | 18-commit Android fork of the above, 2 stars |
| pychromecast | Python | MIT | the best-maintained behavioural reference (reconnect, heartbeat, status edge cases) |
| Open Screen libcast | C++ | BSD-style | the authoritative protos and JSON schemas |

No Kotlin or Kotlin-Multiplatform CASTV2 library turned up. Jellyfin's maintainers evaluated the Java
library in 2020 and found it lacking; AntennaPod closed its request in 2023 for want of volunteers.
**This is the open-source contribution worth making:** a small, coroutine-native Kotlin CASTV2 sender
(`kotlinx-serialization` for JSON, Wire or hand-rolled protobuf for the one tiny message, Ktor/okio or
plain `SSLSocket` for transport). Every FOSS Android media app wants it; nobody has written it.

### Sizing it against our seam

What `CastSenderAndroid` gets from the SDK, and what replaces it:

| SDK gives us | Road B replacement | Effort |
|---|---|---|
| Discovery via MediaRouter | `NsdManager` for `_googlecast._tcp` (TXT: `fn` name, `md` model, `id`); jmdns + multicast lock as fallback — NsdManager has a history of flaky TXT/lost-found churn | small, but **the** on-device risk |
| `MediaRouteButton` + system dialog | R265's own sheet already exists; Chromecasts become rows in it | ~none — arguably a simplification |
| `CastSession` connect / LAUNCH app id | CONNECT → LAUNCH → wait RECEIVER_STATUS → CONNECT to transportId | small |
| `RemoteMediaClient` load/play/pause/seek/tracks/status | JSON on `…cast.media`; we consume ~6 message types | small–medium |
| Custom namespace send/receive | trivial — one more namespace on the same socket | trivial |
| Heartbeat, reconnect, **session resume** | PING/PONG every 5 s; on app start GET_STATUS and re-join if our app id is still running — FR-R245-5's "silence" outcome falls out naturally | medium; this is where pychromecast's edge-case handling is worth reading |
| **Cast media notification** + lock-screen controls (FR-R245-11) | our own foreground service + `MediaSession`/`MediaStyle` notification | medium — the biggest single loss |
| Volume-key routing to the TV | `VolumeProviderCompat` on our MediaSession → SET_VOLUME | small |
| Output Switcher integration (`MediaTransferReceiver`) | lost unless we write our own `MediaRouteProvider` — optional, later | defer |

Rough total: a **~1 000–1 500-line Kotlin module**, most of it testable off-device against a fake
receiver (an open one exists: `yukarikaname/openchromecast`, verified against pychromecast), before it
ever meets the parents' stick.

### Packaging

- Two product flavours are **not required** if Road B is good enough — one sender for both stores is
  the cheaper thing to maintain, and it makes the Play build work on microG phones for free. Keep the
  flavour option in reserve (`play` = SDK, `foss` = ours) only if Road B proves worse on-device; F-Droid
  explicitly allows that shape.
- The existing R224 universal APK (TV + phone in one listing) is unaffected.
- F-Droid extras to check when we get there: reproducible-build friendliness of the KMP/Compose build,
  no prebuilt binaries in the repo, fastlane metadata, and an honest look at anti-features — the app
  needs a self-hosted server (fine, it is FLOSS too) and casting touches Google's device infrastructure
  (no anti-feature label applies to talking to hardware the user owns, but it is worth saying in the
  description).

## 4. Blockers and open questions

1. **Cast Connect (our R266)** — waking the *Ravilo Android TV app* instead of the web receiver. In the
   SDK this is `LaunchOptions.setAndroidReceiverCompatible(true)` plus credentials data. Whether a raw
   LAUNCH message carrying the equivalent field is honoured from a third-party sender is
   **unverified** — no open sender we found does it. The TV side also depends on Play Services' Cast
   Connect library, which is itself proprietary, so a FOSS TV build cannot be a Cast Connect receiver at
   all. **Mitigation already in hand:** 236/R264's backend-relayed "screens" reach a TV running Ravilo
   with no Cast anywhere, and R266's own header already says it shrinks if 236 lands first. Under Road B,
   R266 probably reduces to "not on the FOSS build".
2. **Discovery reliability** on real phones (NsdManager vs jmdns; VPN-on phones; Android 13+ needs no
   special permission for NSD, jmdns needs `CHANGE_WIFI_MULTICAST_STATE`). Device-only question — test on
   the Pixel 9.
3. **Undocumented protocol.** Stable for a decade and relied on by Home Assistant's installed base, with
   Google's own open reference code — but there is no compatibility promise. Accept and say so.
4. **TLS**: the Chromecast presents a self-signed cert, so the socket needs a trust-all manager scoped to
   that one connection. Fine technically; must be fenced so lint/review and F-Droid reviewers see why.
5. **Groups / audio devices / multizone** — out of scope; we cast video to one screen.
6. **iOS** is untouched by any of this (Google's iOS Cast SDK is a separate proprietary question; the
   household's iPhone route is the PWA → screens path, R263–R265).

## 5. Suggested next step (when we stop "only investigating")

A **half-day spike, no product code**: a JVM/Kotlin script that discovers the household Chromecast,
launches our registered app id, joins `CAST_NAMESPACE`, LOADs one item by hand-off code and prints the
status messages. If that works — and everything above says it will — the rest is ordinary engineering
behind a seam we already have, and it becomes two phases: an admin-side nothing, a Ravilo-side
"the Cast sender is ours" (next free R-number), plus a standalone open-source library repo.

## Sources

- microG: [#580](https://github.com/microg/GmsCore/issues/580) · [PR #3570](https://github.com/microg/GmsCore/pull/3570) · [PR #3781](https://github.com/microg/GmsCore/pull/3781) · [/e/OS forum thread](https://community.e.foundation/t/cast-chromecast-support-for-microg-prime-video-co-now-cast-upstream-pr/82589)
- F-Droid: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)
- Other apps' attempts: [jellyfin-android#249](https://github.com/jellyfin/jellyfin-android/issues/249) · [AntennaPod#6794](https://github.com/AntennaPod/AntennaPod/issues/6794) · [stratus-app#6](https://github.com/C0piIot/stratus-app/issues/6)
- Protocol & auth: [Tristan Penman — Chromecast device authentication](https://tristanpenman.com/blog/posts/2025/03/22/chromecast-device-authentication/) · [The Register on the 2025-03 cert outage](https://www.theregister.com/2025/03/10/google_chromecast_outage/) · [Open Screen castv2](https://chromium.googlesource.com/openscreen/+/HEAD/cast/protocol/castv2/) · [libcast README](https://chromium.googlesource.com/openscreen/+/HEAD/cast/README.md)
- Libraries: [chromecast-java-api-v2](https://github.com/vitalidze/chromecast-java-api-v2) · [chromecast-android-api-v2](https://github.com/thirdegg/chromecast-android-api-v2) · [pychromecast](https://github.com/home-assistant-libs/pychromecast) · [node-castv2](https://github.com/thibauts/node-castv2) · [openchromecast (test receiver)](https://github.com/yukarikaname/openchromecast)
- Cast Connect: [Android TV receiver docs](https://developers.google.com/cast/docs/android_tv_receiver/core_features)
