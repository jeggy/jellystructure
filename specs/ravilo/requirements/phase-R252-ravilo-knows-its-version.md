# Phase R252 — Ravilo knows which build it is, and says so on every request

> The client half of **224**. Jellyfin's dashboard shows every Ravilo device as version `0.1.0` because
> the backend forwards a literal — and it forwards a literal because no Ravilo build, on any platform,
> knows its own version or tells the backend. This phase gives every build one version string and puts
> it on every request. Nothing changes on screen.

## Status

`✓ Built` — written 2026-09-16 alongside 224 from a read of `shared/build.gradle.kts`,
`TvApiClient.kt`, the `createTvApiClient` actuals (Android, wasmJs), `ravilo-tizen/App.kt`,
`ravilo-cast/Receiver.kt`, `ravilo-android/build.gradle.kts` and `deploy-play-store.yml`; **implemented
2026-09-17** (see §Implementation notes). Not dev-reviewed, not device-tested. `:shared`, `:ravilo-ui`, `:ravilo-android`, `:ravilo-tizen`, `:ravilo-cast`. Safe to
ship before or after 224 (FR-R252-5).

## What the code does (traced against `main`, 2026-09-16)

- `:ravilo-android` sets `versionName` from `-Pravilo.versionName` (the Play Store workflow derives it
  from the release tag: `1.18`) and otherwise `"1.0"` — so every sideloaded build, including the ones
  `deploy-ravilo.sh` puts on the stue TV, is `1.0-rel`. `buildFeatures.buildConfig` is off; no code
  reads the value. Web, Tizen and the Cast receiver have no version string of any kind.
- `TvApiClient.auth()` attaches `Authorization: Bearer <device token>` and nothing else; `login()`,
  `castRedeem()`, `unpair()` and the WebSocket connect attach nothing identifying. The backend therefore
  has nothing to store and nothing to forward.
- `deviceDisplayName()` is the only per-platform identity the client states (`Build.MODEL`, `"Ravilo
  Web"`), and it goes in the login body once.

## Requirements

**FR-R252-1 — one version string per build, resolved once.**
`:shared` generates `dev.jellystructure.shared.BuildInfo` (`object`, `val version: String`) into a
generated `commonMain` source directory on every build, from one value resolved in the root build
script and shared with the backend (224 FR-224-1): `jellystructure.version` property, else
`ravilo.versionName`, else `git describe --tags --always --dirty` with a leading `v` stripped, else
`dev`. `:ravilo-android`'s `versionName` is that same value (the existing `-Pravilo.versionName` path
is one of its inputs, so `deploy-play-store.yml` needs no change), so the installed package's
`versionName` and the constant agree by construction. Tizen's `config.xml` version is not derived (§OQ).

**FR-R252-2 — every request states version and platform.**
`TvApiClient` adds `X-Ravilo-Version: <BuildInfo.version>` and `X-Ravilo-Platform: <platform>` to
**every** request it makes — authenticated or not, including `login`, `castRedeem`, `unpair` and the
WebSocket handshake — through one helper that `auth()` and the unauthenticated calls both use. The
platform is a constructor argument (`platform: String`) supplied by each entry point: Android passes
`tv` or `phone` from the existing runtime check (`RaviloAppContext.isTelevision`, R234's rule that a
phone in landscape is still a phone), wasmJs passes `web`, `ravilo-tizen` passes `tizen`,
`ravilo-cast` passes `cast`. The version is not a constructor argument; it is `BuildInfo.version`, so no
caller can pass a different one. The login body is unchanged — headers are the only channel, because a
device token outlives the build that minted it and the truthful moment is each request.

**FR-R252-3 — web takes the served version first.**
On wasmJs, `BuildInfo.version` is overridden by `window.__RAVILO_VERSION__` when that is a non-blank
string (224 FR-224-6 injects it from the image's `RAVILO_VERSION`); otherwise the compiled constant.
Mirrors R225's `__RAVILO_DEFAULT_SERVER__` read, including "undefined means absent".

**FR-R252-4 — nothing on screen.**
No settings row, no toast, no About line, no log line above debug. The version leaves the device in two
headers and nowhere else. (§OQ1 is the only place this may change.)

**FR-R252-5 — safe in either order.**
A backend without 224 ignores both headers (Ktor drops unknown headers; nothing parses them). A backend
with 224 receiving requests from a build without this phase sees no headers, stores nothing, and omits
`Version` from the Jellyfin header, so the dashboard keeps whatever it last learned — never a regression
to a placeholder.

## Non-goals

- No "update available" check, no minimum-version gate, no feature switch keyed on the version.
- No User-Agent. Two named headers.
- No per-platform `Client` name toward Jellyfin — that is 224's decision (its OQ1), and it is deferred.
- No Tizen `config.xml` automation.

## Verification

1. `:shared:compileKotlinWasmJs`, `:shared:compileKotlinJs`, `:shared:compileKotlinLinuxX64`,
   `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`,
   `:ravilo-tizen:compileKotlinJs`, `:ravilo-cast:compileKotlinJs` clean; the generated `BuildInfo.kt`
   on this checkout reads `1.18` (the tree is on the tag) and `1.18-dirty` with a local edit.
2. `./gradlew :ravilo-android:assembleRelease -Pravilo.versionName=9.9`: the APK's `versionName` is
   `9.9-rel` and the bundled `BuildInfo.version` is `9.9`.
3. Against a 224 backend on a Pixel 9 (never blind on the stue TV — [[feedback-test-on-pixel9-not-tv]]):
   after one request, the admin Users & devices row reads `Ravilo <version> · Phone` and Jellyfin's
   Devices page shows the same version on `Pixel 9 Pro`.
4. Against a pre-224 backend: login and playback unchanged.

## Open questions

1. **An "About" line.** R216 keeps settings off the TV, but "which build is this" is not a setting.
   If support ever needs it, a single non-focusable line at the foot of Settings → Account is the
   candidate; not drawn, not built.
2. **Tizen's `config.xml`** carries `version="1.0.0"` by hand. The header will say the truth
   (`BuildInfo.version`); the Tizen store listing will not until someone derives it.
3. **The wasm dev server** (`runDev`) serves `index.html` without injection, so a dev web build reports
   the compiled `git describe` — which is right, but worth knowing when reading the dashboard.

## Implementation notes (2026-09-17)

`shared/build.gradle.kts` generates `dev.jellystructure.shared.BuildInfo` from the root's `buildVersion`
(same wiring as the backend's generator). `RaviloVersion.kt`: `RaviloHeaders` (the two names, shared
with the backend), `raviloVersion()` = the platform override else `BuildInfo.version`, and an
`internal expect fun runtimeVersionOverride()` with four actuals — wasmJs reads
`window.__RAVILO_VERSION__`, Android / linuxX64 / JS return null. `TvApiClient` gained a `platform`
constructor argument (default `unknown`) and a private `identify()` that `auth()`, `login()`,
`castRedeem()`, `unpair()` and the WebSocket request block all call. Entry points: Android `tv`/`phone`
from `RaviloAppContext.isTelevision`, wasmJs `web`, Tizen `tizen`, Cast `cast`. `:ravilo-android`'s
`versionName` is now `rootProject.extra["buildVersion"]`.

**Verified:** `:shared:compileKotlinWasmJs`, `:shared:compileKotlinJs`, `:ravilo-ui:compileKotlinWasmJs`,
`:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-tizen:compileKotlinJs`, `:ravilo-cast:compileKotlinJs`
clean. `BuildInfo.version` on this tree: `1.18-1-g809fc650-dirty`; with `-Pravilo.versionName=9.9` the
constant is `9.9` and the merged release manifest carries `android:versionName="9.9"` (the `-rel`
suffix the verification text guessed applies at APK naming, not in the manifest). **Not done:** the
Pixel 9 run against a 224 backend (§Verification 3) — no device this session.
