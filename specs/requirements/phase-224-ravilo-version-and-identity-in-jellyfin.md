# Phase 224 — What Jellyfin's dashboard says about a Ravilo device: its real version, its real name

> Asked 2026-09-16, minutes after v1.18 went out:
>
> *"Lets check what we need to do to get ravilo to report is correct version? So that in jellyfin
> dashboard i can see which version ravilo people are using."*
>
> Today it cannot. Every Ravilo device in Jellyfin's Devices page reads **0.1.0**, and thirteen of the
> twenty are named **"Server"**. Neither number nor name comes from the device. Both come from string
> literals in the backend, and one of them is a bug that renames devices behind the operator's back.

## Status

`✓ Built` — written 2026-09-16 from a read of `JellyfinClient.kt`, `PlaybackService.kt`,
`RaviloDeviceService.kt`, `AuthPlugin.kt`, `TvRoutes.kt`, both Dockerfiles, `publish.yml`, Jellyfin
10.11.11's `AuthorizationContext.cs` / `SessionManager.cs`, and a read-only `GET /Devices` +
`GET /Sessions` against the household's Jellyfin; **implemented 2026-09-17** (see §8). Not dev-reviewed, not deployed, the
dashboard not yet observed. Backend, admin frontend, images and workflow. The client half is **R252**, which this phase needs before any number other than "unknown"
can appear; each half is safe to ship without the other (§4, FR-224-3 and R252 FR-R252-5).

**Numbering:** verified against `STATUS.md` on 2026-09-16 — admin taken through 223, Ravilo through R251.

## 1. What the code does today (traced against `main` `0ef6ff36`)

- **The version is a literal.** `JellyfinClient.kt:28` builds the server's own header with
  `Version="0.1.0"`; `:230` (`authenticateByName`) and `:1220` (`jellyfinAuth`) build the per-device
  Ravilo header with the same literal. Nothing reads a version from anywhere, because nothing has one:
  the backend has no build-time constant, `/api/health` reports no version, and the only version string
  in the whole repo is `:ravilo-android`'s `versionName`, which the Play Store workflow sets from the
  release tag and which no code reads (`buildConfig` is off).
- **The device never says which build it is.** `TvLoginRequest` carries `device_id` and `device_name`;
  `TvApiClient.auth()` attaches a bearer token and nothing else. The `ravilo_device` row has no column
  for it. Device tokens are durable across app updates (phase 141), so even a login-time field would go
  stale the first time the Play Store updated the app under it.
- **`tvToken()` hands a device's Jellyfin token to the server's identity.** `PlaybackService.kt:1020`
  returns `device.jellyfinUserToken` when the paired token checks out, and its callers (seven in
  `PlaybackService`, plus `BrowseService`, `LiveTvService`, `PlaystateCache`, `JellyfinSessionBridge`)
  pass that token into `JellyfinClient` methods that call `jellyfinAuth(token)` with **no identity** —
  56 of the 59 call sites. That sends `Client="Jellystructure", Device="Server",
  DeviceId="jellystructure-server-v01"` together with a token Jellyfin minted for
  `ravilo-<device>-<user>` / "BRAVIA 4K VH2". Only `authenticateByName`, `postCapabilities` and
  `closeLiveStream` carry the device identity. `checkToken` (`:321`, the phase 194 probe that runs
  *inside* `tvToken()`) is one of the 56, so the very act of validating a device's token misnames it.

## 2. What Jellyfin does with that (10.11.11 source, verified 2026-09-16)

`Jellyfin.Server.Implementations/Security/AuthorizationContext.cs` resolves the token to its stored
`Device` row and then reconciles the header against it, **on every request**:

- lines 156–163: a non-blank `Device` that differs from the stored `DeviceName` **renames the device**;
- lines 167–176: a non-blank `Version` that differs from the stored `AppVersion` **updates it**; a blank
  `Version` keeps what is stored;
- line 143: `Client` is read only when the header's is blank — a differing `Client` is ignored;
- line 188: the row is saved when anything above changed.

`Emby.Server.Implementations/Session/SessionManager.cs:530` copies the header's version onto the live
`SessionInfo.ApplicationVersion`, which is what the dashboard's session cards show.

Two consequences shape this phase. First, **fixing the header fixes the dashboard on the next request**,
with no re-login and no repair script: a device whose stored name is "Server" gets its real name back
the moment one request carries it. Second, **the "Server" rename is exactly line 158**: the server
identity's `Device="Server"` arriving with a device's token. The devices that are currently playing read
correctly only because playback calls happen to be among the three that carry the identity — the name
flip-flops with every idle/playing transition.

What the household's Jellyfin held at 21:14 on 2026-09-16 (`GET /Devices`, `AppName == "Ravilo"`):

| Stored name | AppVersion | Rows |
|---|---|---|
| `Server` | 0.1.0 | 13 |
| `BRAVIA 4K VH2`, `Pixel 9 Pro` (×3), `Ravilo Web`, … | 0.1.0 | 7 |

All eight live Ravilo sessions in `GET /Sessions` read `0.1.0`.

## 3. The model

**One version, resolved once per build, carried on every request, forwarded on every call.**

- A build has one version string. It comes from one Gradle property, defaults to `git describe`, and is
  the same value the Android `versionName` already takes from the release tag — so the Play Store, the
  Docker images and a sideload all agree with themselves and differ from each other honestly
  (`1.18` · `0ef6ff36` · `1.18-3-g6d4499e-dirty`).
- The device states it on **every** request, as headers, not in a login body: a durable token outlives
  the build that minted it, so the only truthful moment is now. The backend stores it, writes it only
  when it changes, and forwards it in the Jellyfin header **together with the device's identity**, which
  is the invariant §1's third bullet breaks today.
- Unknown stays unknown. No placeholder, no `0.1.0`, no `Version` at all in the Jellyfin header when the
  device has never said — Jellyfin then keeps whatever it last learned (§2, lines 167–169).

## 4. Requirements

**FR-224-1 — jellystructure knows its own version.**
A generated `dev.jellystructure.BuildInfo.version` (root project, `commonMain` generated source, built
like the SQLDelight output so every compile task depends on it). The value is, in order: the Gradle
property `jellystructure.version`, else `ravilo.versionName` (the property `deploy-play-store.yml`
already passes), else `git describe --tags --always --dirty` with a leading `v` stripped, else `dev`.
At runtime the environment variable `JELLYSTRUCTURE_VERSION`, when set and non-blank, wins over the
constant (FR-224-6 explains why). The resolved value appears (a) in `/api/health` as `"version"`, and
(b) in the server's own `MediaBrowser` header in place of `0.1.0`, so the "Jellystructure · Server"
device on the dashboard reads the release too.

**FR-224-2 — the device row remembers which build and which platform.**
`ravilo_device` gains `app_version TEXT` and `platform TEXT` (migration 46; the `.sq` `CREATE TABLE`
updated byte-for-byte). Sources, in order of precedence per request: the `X-Ravilo-Version` and
`X-Ravilo-Platform` headers (R252 FR-R252-2). Written on `POST /api/tv/login` (the login route reads
the same headers; the body is unchanged), on Cast enrolment (`castRedeem`, phase 218 FR-218-9, the
receiver's own headers), and on any authenticated `/api/tv/**` request whose header pair differs from
what the token's cached `DeviceData` holds — **one write per change, never one per request**; the
comparison happens in `validateDeviceToken`'s existing cache, next to the once-a-minute `last_seen`
write. A request without the headers changes nothing. Values are trimmed and capped at 64 characters
(the header-safe rule `headerSafe` already applies to names); `platform` is one of `tv · phone · web ·
tizen · cast`, anything else stored as sent but never interpreted. `DeviceData` carries both as
nullable fields.

**FR-224-3 — the Jellyfin header carries the device's version, or no version.**
`JellyfinDeviceIdentity` gains `appVersion: String?`; `forDevice()` fills it from `DeviceData`, the
login-time identity from the login request's headers. The one header builder emits `Version="…"` only
when it is non-null and non-blank, and omits the field otherwise. `Client` stays `Ravilo` (§7 OQ1).
Acceptance is literal: a device that has never reported a version must never appear in Jellyfin as
anything it did not say — in particular never as `0.1.0` again after this ships, because a stored
`0.1.0` is replaced on the device's first versioned request and left alone until then.

**FR-224-4 — a device's token never travels under the server's identity.**
Invariant: for every request the backend makes with a token Jellyfin minted for a `(device, user)`
identity, the header's `Device`, `DeviceId` and `Version` are that identity's. Mechanism: a registry
keyed by Jellyfin user token (`auth/DeviceIdentityRegistry`), filled wherever a `DeviceData` is
resolved — `RaviloDeviceService.validateDeviceToken` (both the cache hit and the DB load),
`loginDevice`, `allDevices()`, `PlaybackService.tvToken()` and `pairedTokenCheck()` — and consulted by
`jellyfinAuth(token, identity = null)` before it falls back to the server header. Entries are removed
when the row is deleted or re-minted (`removeSession`, `deleteAllForUser`, a fresh login for the same
pair). Admin web sessions (`SessionService`, minted under the server identity) are deliberately not
registered and keep the server header. Explicit identities passed by callers still win. Consequence,
stated so it is tested: after this ships, every Ravilo device row in Jellyfin regains its real name on
that device's next request, including devices that have only ever been idle. No migration touches
Jellyfin.

**FR-224-5 — Users & devices shows it.**
`OverviewDevice` (`TvRoutes.kt`, `RaviloApi.kt`) gains `app_version` and `platform`; the device row's
caption in `RaviloUsers.kt` gains one line in phase 185's idiom: `Ravilo 1.18 · TV`, or `version ·
unknown` italic when neither is known. Platform is spelled `TV · Phone · Web · Tizen · Chromecast`.
`design/app/ravilo-users.html` shows the same line on its device rows.

**FR-224-6 — the images carry the version without recompiling.**
Both Dockerfiles declare `ARG BUILD_VERSION=dev` **in the runtime stage, after every compile layer**,
and set `ENV JELLYSTRUCTURE_VERSION=$BUILD_VERSION` (main image) / `ENV RAVILO_VERSION=$BUILD_VERSION`
(ravilo-web image). `publish.yml` passes `build-args: BUILD_VERSION=<MAJOR.MINOR>` on a release and
`BUILD_VERSION=<8-char sha>` on a push. Why not bake it at compile time: the release build of a commit
that was pushed minutes earlier is a full cache hit today (v1.18's release run finished in minutes
behind a 20-minute push build); a compile-time value would recompile both images for the tag alone.
`web-static-server` reads `RAVILO_VERSION` and injects `window.__RAVILO_VERSION__="…"` into
`index.html` exactly as R225's `injectDefaultServer` injects the default server (one inline script,
every other asset untouched); R252 FR-R252-3 reads it. `ci.yml`'s test stack passes nothing and builds
as `dev`.

## 5. Non-goals

- **No `Client` rename** (`Ravilo TV` / `Ravilo Web`). Jellyfin reads `Client` only at token creation
  (§2), so it would reach the dashboard only after every device re-logged in. §7 OQ1.
- **No repair of Jellyfin's stored rows.** FR-224-4 heals them on use; a device that never returns keeps
  its last stored state, which is the truth about it.
- **No behaviour keyed on the version.** Nothing gates, warns, or adapts by it. It is reporting.
- **No per-platform Client name, no User-Agent.** Two headers, one place.
- **No version in the TV UI** — R216's no-settings rule stands; R252 §OQ owns the "About" question.

## 6. Verification

1. `./gradlew linkReleaseExecutableLinuxX64` on this checkout: `/api/health` reports `"version":"1.18"`
   (this tree sits on the tag) and the server device in Jellyfin's dashboard reads `1.18` after one
   request.
2. Unit: `RaviloDeviceService` — a versioned request writes `app_version`/`platform` once; the same
   pair again writes nothing (assert on the row's value and on a second call being a no-op through the
   cache); a request without headers leaves stored values untouched; `DeviceData.appVersion` round-trips.
3. Unit: the header builder — identity with version ⇒ `Version="1.18"` present; identity without ⇒ no
   `Version` field at all; no identity but a registered token ⇒ the registered identity's fields; an
   unregistered token ⇒ the server header, unchanged from today.
4. Live, after deploy: `GET /Devices` shows no Ravilo device named `Server` once each has made one
   request; the two BRAVIAs and the web build show their versions; `GET /Sessions` Ravilo rows match.
   A sideloaded build shows a `git describe` string; a Play Store build shows the plain release.
5. `docker build --build-arg BUILD_VERSION=9.9 .` then `docker run … wget -qO- /api/health` prints
   `"version":"9.9"`; the ravilo-web image's `index.html` contains `__RAVILO_VERSION__="9.9"`.
6. `linuxX64Test` green; `compileKotlinWasmJs` (admin) clean.

## 7. Open questions

1. **Platform in the `Client` name?** `Ravilo TV` / `Ravilo Phone` / `Ravilo Web` would make the
   dashboard readable at a glance, but only after re-login per device. Could be done on the *login*
   identity only (new tokens) and left alone for existing ones. Deferred until the version alone has
   been seen in use.
2. **The in-backend `/tv/**` serving of ravilo-web** (`RAVILO_WEB_DIR`) is used by no deployment; it
   keeps the compiled-in constant and does not inject. Say so if it ever becomes a deployment.
3. **`git describe` in CI push builds.** The runner clones at depth 1, so a push build carries the short
   sha rather than `1.18-1-g0ef6ff3`. A `fetch-depth: 0` (93 MB pack) would allow the richer string.
4. **Retention.** `app_version` is one value per row, no history. If "when did this TV update" ever
   matters, that is a new table, not a column.

## 8. Implementation notes (2026-09-17)

**Built as specified**, one commit after the spec's. `build.gradle.kts` resolves `buildVersion` once
(property → `ravilo.versionName` → `git describe` → `dev`) and generates `dev.jellystructure.BuildInfo`
(plain srcDir plus an explicit dependency on every Kotlin compile task, because the SQLDelight
`afterEvaluate` flattens srcDirs to files and would drop a `builtBy`). `ServerVersion.current` prefers
`JELLYSTRUCTURE_VERSION`. `JellyfinDeviceIdentity.appVersion`, one `jellyfinIdentityHeader()` (emits
`Version` only when non-blank), `jellyfinAuth()` falling back to `DeviceIdentityRegistry.identityFor(token)`,
and the server header carrying the real version. `ravilo_device.app_version`/`platform` (migration 46),
`RaviloDeviceService.validateDeviceToken(token, appVersion, platform)` with `recordAppInfo` as the
change-only write, `loginDevice(appVersion, platform)`, the registry filled on every resolution and on
`pairedTokenCheck` (which every `tvToken()` / `tvTokenForClient()` call enters first), forgotten on
`unpair` / `removeSession` / `deleteAllForUser`. `AuthPlugin` reads both headers and hands them through;
the login route reads them for the login identity and the row, the cast-redeem route for the receiver's
row (`CastService.redeem` gained the two parameters). `OverviewDevice.app_version`/`platform` and the
`RaviloUsers.kt` caption line; `design/app/ravilo-users.html` rows updated. `/api/health` carries
`"version"`. Both Dockerfiles take `ARG BUILD_VERSION=dev` after the compile layers; `publish.yml`
passes the release number or the short sha; `web-static-server` injects `window.__RAVILO_VERSION__`
through one `injectRuntimeConfig()` shared with R225's default-server injection.

**Two clarifications of the text above, both in the direction of "say nothing rather than something
false":** `loginDevice` keeps the row's previous pair when the login carries none (an older client
signing in again must not blank a newer client's report), and a blank or whitespace header is treated
as absent — `RaviloDeviceVersionTest` pins both.

**A limitation found on the way, not fixed here (OQ5).** A Chromecast receiver's row (218) holds the
*phone's* Jellyfin user token, so two device rows share one token and the registry maps it to whichever
of the two resolved last. Jellyfin shows that token's device under whichever name spoke last — the same
phone/receiver flip-flop 218 already has through the explicit identities on playback calls. A receiver
that should appear as its own device in Jellyfin needs its own Jellyfin token, which is 218's to mint.

**Verified:** `compileKotlinLinuxX64` clean; `linuxX64Test` **331/0** (new: `RaviloDeviceVersionTest` ×4,
`JellyfinIdentityHeaderTest` ×3); admin `compileKotlinWasmJs` and `:web-static-server` clean. `BuildInfo`
on this tree reads `1.18-1-g809fc650-dirty`, and `9.9` under `-Pravilo.versionName=9.9`. The static
server, run locally with `RAVILO_VERSION=9.9` and a default server, serves
`<script>window.__RAVILO_DEFAULT_SERVER__="…";window.__RAVILO_VERSION__="9.9";</script>` on `/` and on a
deep link, and a byte-identical `index.html` with neither set. **Not verified:** a Docker build with the
arg (left to the next publish run — the ARG/ENV lines are the whole change), the live dashboard (needs
a deploy: §6.4), and `/api/health` on a running backend.
