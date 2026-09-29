# Ravilo on Linux as a Flatpak — what it would take, and how it reaches Flathub and the GitHub release

> Owner, 2026-09-29, the same day the Mac app landed: *"Lets do a research in how to do a flatpak as well, including a
> ci setup to deploy to flathub and attach the flatpak package to the gh release."*

**Date:** 2026-09-29. Research, measured against local `main` `27aae1d1` (R328–R331 built, unpushed). Nothing built,
no spec written. Prospective phases at the end (§9); numbers to be verified free on `main` before writing.

## 0. The answer in eight lines

1. **The Linux app already exists — as far as browsing goes.** R328 runs on Debian today (`./gradlew :ravilo-desktop:run`,
   Xvfb-verified: sign-in, Home, keys, menu, About, quit) and R330's Cast client discovers with JmDNS on Linux. What
   does not exist is a **player**: R329 is AVPlayer through a Swift library, so on Linux every load fails at once and
   music mode is absent (§1). A Flatpak shipped today would be a catalogue you cannot play. The Linux work is **one
   player phase and one packaging phase**, not four.
2. **Flathub builds from source, offline, on its own machines.** No network in the sandbox, no prebuilt binaries
   ([requirements](https://docs.flathub.org/docs/for-app-authors/requirements)). For a Gradle project that means: every
   Maven artefact listed with a SHA-256 in a sources file, the Gradle distribution itself as a source, the JDK from the
   `org.freedesktop.Sdk.Extension.openjdk21` extension, and a build that configures **only the desktop target** —
   the root project is the Kotlin/Native backend and three modules apply the Android plugin, none of which can
   configure in that sandbox (§3). This is the real engineering; the manifest is a page.
3. **The player is mpv, through `libmpv`, into the frame ring R329 already built** (§5). It plays the household's MKVs
   direct (HEVC, DTS, TrueHD, PGS — everything Android direct-plays), renders subtitles itself, switches audio tracks,
   and its software render API writes BGRA frames into a buffer we own — the same `takeFrame()` path Compose already
   draws. Flathub has the exact recipe (jellyfin-media-player builds mpv + libplacebo + libass as modules). GStreamer
   is the runtime-native alternative and the fallback (§5).
4. **The app id is `net.jebster.Ravilo`** (owner, 2026-09-29): the household's own domain, which Flathub accepts
   because the owner controls it and it answers over HTTPS. The Mac bundle and the Android package stay
   `dev.jellystructure.ravilo` (`jellystructure.dev` is unregistered — Google's RDAP: *not found*, 2026-09-29 — and
   nothing links the three ids). What the choice costs and what stays scrubbed is in §4; an id cannot change later
   without an end-of-life rebase.
5. **Permissions are six lines, and one of them is X11.** OpenJDK 21 has no Wayland toolkit (the pure-Wayland
   *Wakefield* toolkit is a prototype on OpenJDK 25), so the app needs `--socket=x11` + `--share=ipc`, served by
   XWayland on a Wayland desktop; plus network, PulseAudio, `dri` for Skia's OpenGL and mpv's hardware decode, an
   MPRIS `own-name` and nothing else. Flathub's linter allows exactly that shape; both-x11-and-wayland is the one it
   refuses without proof (§2.3).
6. **The `.flatpak` on the GitHub release is the easy half:** `flatpak/flatpak-github-actions/flatpak-builder@v6`
   builds the same manifest on `ubuntu-latest` inside Flathub's own container image and emits a single-file bundle;
   `deploy-linux.yml` shaped like `deploy-macos.yml` attaches `ravilo-linux-<N>.flatpak` (§6.1). The bundle needs no
   Flathub account and installs with one command on any distro with Flatpak.
7. **"Deploy to Flathub" is not a deploy.** Flathub builds from the manifest in **its** repository
   (`flathub/<app-id>`); nothing external pushes builds to it. Getting there is a one-time human submission (a PR to
   `flathub/flathub` against `new-pr`, a reviewer, `bot, build`); after that **each release is a pull request to that
   repository that bumps the tag and commit and replaces the regenerated sources file**, which our release workflow
   can open itself with `peter-evans/create-pull-request` — the one third-party action Flathub allows — and a merge
   publishes within 1–2 hours (§6.2). Flathub's own update bot cannot do this for us, because it cannot regenerate the
   Gradle sources file (§6.3).
8. **Size, as an estimate:** the Flatpak without a player ≈ 1 week (offline Gradle is most of it); the mpv player
   ≈ 2–3 weeks including a frame-delivery spike; CI + the Flathub submission ≈ 3–5 days plus the reviewers' queue.

## 1. What the desktop app does on Linux today (measured)

| Area | On the Mac | On Linux, today | Source |
|---|---|---|---|
| The app, window, keys, menu, About, quit | R328 | **the same** — Compose Desktop is a JVM target; only the app menu is drawn by macOS | `ravilo-desktop/src/desktopMain`, R328 build notes ("run on Linux only") |
| Data directory | `~/Library/Application Support/Ravilo` | **`$XDG_DATA_HOME/ravilo`**, else `~/.local/share/ravilo`; `-Dravilo.data.dir` overrides | `DesktopPaths.kt` |
| Tokens | the Keychain via the Swift library | **`tokens.json`, mode `0600`** | `SecretStore.kt`, `PrefsFile.kt` |
| `X-Ravilo-Platform` / device state | `mac` / `macos…` | **`linux` / `linux…`** — deliberate (R328 dev review: honest and free text) | `RaviloRootActuals.kt:38`, `DesktopWindowSeams.kt:47` |
| Films | AVPlayer through `libravilo-mac.dylib` | **nothing plays** — every load fails at once and R237's card shows | `RaviloPlayerDesktop.kt:55`, `MacPlayer.kt:22` |
| Music and books | an audio-only AVPlayer | **music mode is absent** (`MusicEngine.supported = false`) | `MusicEngineDesktop.kt` |
| Capabilities told to the server | asked of AVFoundation | the conservative answers: `aac`/`mp3`, `h264` only, no HEVC, no HDR | `DesktopCapabilities.kt` |
| Cast: discovery | Bonjour (`NWBrowser`) | **JmDNS** — written for the Linux dev build | `CastDiscovery.kt`, `ravilo-castv2` |
| Cast: the session | the same JVM Cast v2 client | **the same** (TLS to :8009 is the JVM's) | `ravilo-castv2` |
| Now Playing / media keys | `MPNowPlayingInfoCenter` | nothing | `MacNowPlaying.kt` |
| Display sleep while a film plays | `Power.swift` | nothing | `MacNative.kt:52` |
| Reduce motion | `defaults read` | always false | `DesktopWindowSeams.kt:108` |
| Update line | offers the `.dmg` | **nothing** — right for a Flatpak, which Flathub updates | `UpdateCheck.kt:38` |
| Self-test | loads the library | passes without it (`lib != null || !isMac`) | `SelfTest.kt:26` |
| Icon | `ravilo.icns` | `ravilo.png`, **512×512** — Flathub wants SVG or ≥256 px, so it qualifies as is | `ravilo-desktop/icons` |
| CI | `ci.yml` `macos` job, non-blocking | `:ravilo-desktop:compileKotlinDesktop` + the three `desktopTest`s on every push | `ci.yml:144` |

So the Linux target is not a new port. It is the Mac app minus the Swift library, and the Swift library is: the
player, Now Playing, display sleep, the Keychain, Bonjour, the Computer Name. Each of those already has a Linux
fallback except the two that matter — the player, and (small) Now Playing/sleep.

## 2. The Flatpak's shape

### 2.1 The files

Flathub requires four things ([requirements → required files](https://docs.flathub.org/docs/for-app-authors/requirements)):

| File | Where it lives | What it must be |
|---|---|---|
| **Manifest** `<app-id>.yml` | in *our* repo under `flatpak/` as the template the CI renders (§6), and the rendered copy in `flathub/<app-id>` | runtime `org.freedesktop.Platform` **26.08** (released 2026-09-01, supported to 2028-09-01 — [endoflife.date](https://endoflife.date/freedesktop-sdk); 24.08 died 2026-09-08 and *"submissions using an end-of-life runtime … will not be accepted"*), `sdk-extensions: [org.freedesktop.Sdk.Extension.openjdk21]` (on `branch/26.08` already), modules: gradle, the Gradle sources, mpv's stack, ravilo |
| **Metainfo** `<app-id>.metainfo.xml` | `ravilo-desktop/linux/`, installed by the build to `/app/share/metainfo/` | id = the app id exactly; name, summary, description, developer, project licence (GPL-3.0), content rating (OARS, *none* everywhere), **screenshots at stable HTTPS URLs** (the linter refuses a metainfo without them — *never granted*), a `<releases>` list with one `<release version="1.45" date="…">` per release (the CI adds the row), `launchable` = the desktop file. Validated with `appstreamcli validate` |
| **Desktop file** `<app-id>.desktop` | same folder → `/app/share/applications/` | `Exec=ravilo`, `Icon=<app-id>`, `Categories=AudioVideo;Video;Player;`, `StartupWMClass` set to what AWT reports (`dev-jellystructure-ravilo-desktop-MainKt` unless we set `-Dsun.awt.…`/`Xtoolkit` class — measure it in the spike, or the dock shows a duplicate icon) |
| **Icon** | `ravilo-desktop/icons/ravilo.png` (512²) → `/app/share/icons/hicolor/512x512/apps/<app-id>.png`; better: export an SVG from `ravilo/assets/brand/*.svg` once | SVG preferred, ≥256 px PNG accepted |

Plus `flathub.json` in the Flathub repository: `{"only-arches": ["x86_64"]}` for round 1 (§7 D4).

### 2.2 The build, inside the sandbox

```yaml
app-id: net.jebster.Ravilo                   # owner's choice — §4
runtime: org.freedesktop.Platform
runtime-version: '26.08'
sdk: org.freedesktop.Sdk
sdk-extensions: [org.freedesktop.Sdk.Extension.openjdk21]
add-extensions:
  org.freedesktop.Platform.ffmpeg-full:      # the patented decoders mpv/ffmpeg link against at run time
    version: '26.08'
    directory: lib/ffmpeg
    add-ld-path: .
command: ravilo
finish-args: [...]                            # §2.3
modules:
  - shared-modules/…/libass, libplacebo, mpv  # §5 — the jellyfin-media-player recipe, pinned by tag
  - name: gradle                              # the distribution the wrapper would download
    buildsystem: simple
    sources: [{type: archive, url: https://services.gradle.org/distributions/gradle-9.2.1-bin.zip, sha256: …, dest: gradle}]
    build-commands: [cp -r gradle /app/gradle]     # build-only; cleaned out below
  - name: ravilo
    buildsystem: simple
    build-options: { env: { JAVA_HOME: /usr/lib/sdk/openjdk21/jvm/openjdk-21 } }
    sources:
      - {type: git, url: https://github.com/jeggy/jellystructure, tag: v1.45, commit: …}
      - flatpak-sources.json                        # §3 — every Maven artefact, generated per release
      - {type: file, path: gradle-offline.init.gradle.kts}
    build-commands:
      - /app/gradle/bin/gradle --offline --no-daemon -Pravilo.desktopOnly=true -Pjellystructure.version=1.45
          --init-script gradle-offline.init.gradle.kts :ravilo-desktop:createDistributable
      - cp -r ravilo-desktop/build/compose/binaries/main/app/Ravilo /app/ravilo   # jpackage's app image: bin/Ravilo, lib/app/*.jar, lib/runtime (jlink)
      - install -Dm755 flatpak/ravilo.sh /app/bin/ravilo
      - install -Dm644 ravilo-desktop/linux/*.desktop  -t /app/share/applications
      - install -Dm644 ravilo-desktop/linux/*.metainfo.xml -t /app/share/metainfo
      - install -Dm644 ravilo-desktop/icons/ravilo.png /app/share/icons/hicolor/512x512/apps/${FLATPAK_ID}.png
cleanup: [/gradle, /include, /lib/pkgconfig, '*.la', '*.a']
cleanup-commands: [mkdir -p /app/lib/ffmpeg]
```

Two things in that block are already true of the project and cost nothing: `createDistributable` produces a
self-contained app image with a **jlinked runtime** (the `modules(…)` list in `ravilo-desktop/build.gradle.kts`, the
same list the `.dmg` uses), so the Flatpak ships a trimmed JRE, not the extension's whole JDK; and jpackage's Linux
launcher (`bin/Ravilo`) reads `lib/app/Ravilo.cfg` and runs from wherever it is copied, so `/app/ravilo` works
unchanged. Compose's `packageVersion` rule (MAJOR ≥ 1) was macOS's; Linux takes `1.45.0` as it is.

### 2.3 Permissions, and why each

Flathub: *"static permissions must be kept to an absolute minimum"*; portals where they exist. The linter's rules
([linter](https://docs.flathub.org/docs/for-app-authors/linter)) decide the exact shape:

| `finish-args` | Why | Linter |
|---|---|---|
| `--socket=x11` `--share=ipc` | OpenJDK 21's AWT is X11-only (XWayland serves it under Wayland) | `finish-args-x11-without-ipc` requires the pair; **not** `--socket=wayland` (the linter *never grants* `wayland` without `fallback-x11`, and `x11`+`wayland` together only *"if the application crashes … without both"*) |
| `--share=network` | the server; mDNS multicast for the speakers (works inside the sandbox with the network shared) | — |
| `--socket=pulseaudio` | mpv's audio (PipeWire's Pulse front) | `--nosocket=pulseaudio` is *never granted* for a player |
| `--device=dri` | Skia's OpenGL, mpv's VA-API decode | `--device=all` is *never granted* |
| `--own-name=org.mpris.MediaPlayer2.Ravilo` | media keys and the desktop's Now Playing (§5.4) | own-names are blocked *"unless matching Flatpak ID, subname thereof, or MPRIS subname"* — verify the exact subname the linter wants on the first lint run |
| *(no talk-name)* | display sleep through the **Inhibit portal** (`org.freedesktop.portal.Inhibit`), which every sandboxed app may reach | `--talk-name=org.freedesktop.ScreenSaver` would work too and jellyfin-media-player carries it, but a portal is what Flathub asks for first |

Not needed and not asked for: `--filesystem=*` (no file access — the photo picker uses the **File Chooser portal**
automatically when AWT's `FileDialog` runs under `GTK_USE_PORTAL`, to verify), `--device=all`, `--socket=session-bus`.
The `$XDG_DATA_HOME` the sandbox sets lands `tokens.json` in `~/.var/app/<app-id>/data/ravilo/` with no code change.

### 2.4 What the app itself must change for Linux

- **`-Pravilo.desktopOnly=true`** (§3.2) — the one build change every other piece depends on.
- **`StartupWMClass`** and the AWT class name, so the dock and the desktop file agree; `-Dawt.useSystemAAFontSettings=on`
  and `-Dsun.java2d.uiScale` from `GDK_SCALE` for HiDPI on X11 (jpackage's `Ravilo.cfg` takes `java-options`).
- **Now Playing → MPRIS**, **display sleep → the Inhibit portal**, both over D-Bus. The JVM has no D-Bus; the library is
  `com.github.hypfvieh:dbus-java` (MIT, Java 21+). Note its [issue #220](https://github.com/hypfvieh/dbus-java/issues/220):
  the sandbox's `xdg-dbus-proxy` has closed its connection before — a reconnecting wrapper, tested inside the Flatpak,
  not on the host.
- **Reduce motion**: GNOME's `org.gnome.desktop.interface enable-animations` via the Settings portal (`org.freedesktop.portal.Settings`), or stay `false`.
- The update line stays silent (already), and the About window says *installed from Flathub* when `$FLATPAK_ID` is set.

## 3. Building offline — the hard part

### 3.1 The rule

*"There is no network access during the build process"* and *"all source available submissions must be built entirely
from source code"*; prebuilt binaries are refused, exceptions are for *well-known vendors* and *"niche tooling or
uncommon build setups may not constitute grounds"* ([requirements](https://docs.flathub.org/docs/for-app-authors/requirements)).
So a Gradle build must run with `--offline` against a directory of artefacts the manifest downloaded by URL and
checksum. Three sources of downloads have to be caught:

1. **The Gradle distribution** — the wrapper would fetch `gradle-9.2.1-bin.zip`; it becomes an `archive` source (§2.2).
2. **Every Maven artefact**, including plugins resolved in `pluginManagement {}` (KGP, the Compose plugin, the
   serialization plugin — and AGP's own jars if it stays on the plugin classpath, §3.2) and **platform-specific natives**:
   `compose.desktop.currentOs` resolves to `desktop-jvm-linux-x64`, which carries `skiko-awt-runtime-linux-x64`; Coil,
   OkHttp and Ktor are pure JVM; JNA ships its natives inside its jar.
3. **The JDK** — from the extension: `JAVA_HOME=/usr/lib/sdk/openjdk21/jvm/openjdk-21` (a full JDK: `jlink` and
   `jpackage` are there, which is what `createDistributable` calls).

### 3.2 What stops today's build from configuring in that sandbox

Gradle configures every included project before it runs anything, so what is *in* `settings.gradle.kts` matters,
not just what `:ravilo-desktop` depends on:

| Obstacle | Where | Why it fails offline in the sandbox |
|---|---|---|
| The **root project is the backend**: `linuxX64` + `wasmJs`, SQLDelight | `build.gradle.kts:25–45` | KGP resolves the Kotlin/Native distribution for a native target; the sandbox has no network and no `~/.konan` |
| `shared`, `ravilo-i18n`, `ravilo-ui` **apply the Android library plugin** | `plugins { alias(libs.plugins.android.library) }` | AGP needs an SDK at `sdk.dir`/`ANDROID_HOME` the moment it configures (`:ravilo-android` is already gated on that in settings — the libraries are not) |
| The same three declare `linuxX64()`, `wasmJs`, `js(IR)` | their `kotlin {}` blocks | K/N again; Wasm/JS pull Node, Yarn and Binaryen at task time and metadata at configure time |
| `:ravilo-web`, `:ravilo-cast`, `:ravilo-screen`, `:web-static-server` | `settings.gradle.kts` | Kotlin/JS, Wasm and Native modules with nothing the desktop needs |

The answer is one Gradle property, **`ravilo.desktopOnly`**, read in `settings.gradle.kts` and in the four build
scripts: settings includes only `:shared`, `:ravilo-i18n`, `:ravilo-ui`, `:ravilo-castv2`, `:ravilo-desktop`; the root
script declares no targets; the three libraries apply AGP through `apply(plugin = …)` under `if (!desktopOnly)`
(the catalogue alias stays, as `apply false`) and declare only `jvm("desktop")`. `ci.yml` then builds
`:ravilo-desktop:compileKotlinDesktop` **twice**, once each way, so the switch cannot rot. Everything else — the
`buildVersion` generator, `BuildInfo`, the string table — is plain Kotlin and works either way.

### 3.3 Generating the sources file

Two tools exist. Flatpak's own [`flatpak-gradle-generator`](https://github.com/flatpak/flatpak-builder-tools/tree/master/gradle)
parses a `--info` build log from a Freedesktop **21.08** sandbox and has not been touched since; it predates Gradle 8's
variant-aware resolution and says nothing about plugins. The one to use is the
[**`org.meshtastic.flatpak.sources.settings`** plugin](https://github.com/meshtastic/gradle-flatpak-sources) (0.2.1, Gradle
9.0+, JDK 17+ — both true here): a *settings* plugin that hooks Gradle's download events before any resolution, backfills
`pluginManagement` downloads, finds each artefact in `caches/modules-2/files-2.1`, and writes `build/flatpak-sources.json`
as `type: file` entries (`url`, `sha256`, `dest: offline-repository/<group path>`) with Maven Central mirror URLs.

The rules the README insists on, worth repeating because they are silent failures: capture with an **empty Gradle
home** every time (`-Dgradle.user.home=$(mktemp -d)`) — a reused home *"silently produces a short manifest"*, and
`--refresh-dependencies` is not enough; and name the platform artefacts it must pull even when the capture host differs:

```kotlin
// settings.gradle.kts (only under -Pravilo.desktopOnly=true)
flatpakSources {
    targetPlatforms.set(setOf("linux-x64"))                      // + "linux-arm64" when aarch64 comes (§7 D4)
    platformDependencies.set(setOf("org.jetbrains.compose.desktop:desktop-jvm-{platform}:1.9.3"))
}
```

```sh
GRADLE_HOME=$(mktemp -d)
./gradlew --no-build-cache -Dgradle.user.home="$GRADLE_HOME" -Pravilo.desktopOnly=true \
    :ravilo-desktop:createDistributable :captureFlatpakSources
```

Then an init script (`gradle-offline.init.gradle.kts`, a source of the manifest) points **every** repository —
`pluginManagement`, `dependencyResolutionManagement`, and the buildscript's — at
`file:///run/build/ravilo/offline-repository` and the build runs `--offline`. The JSON is regenerated on **every
release** by the CI (§6) and committed beside the manifest in the Flathub repository, never by hand; it changes whenever
`gradle/libs.versions.toml` does, which is why the Flathub bot cannot own updates (§6.3).

### 3.4 Verifying it here, first

This Debian box has no `flatpak` or `flatpak-builder` installed (checked). Installing both (`apt install flatpak
flatpak-builder`, Flathub added user-wide) makes the whole loop local: `flatpak-builder --user --install --force-clean
build-dir flatpak/<app-id>.yml`, `flatpak run <app-id>`, and the linter Flathub runs,
`flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest <manifest>` plus `… appstream` and `… repo`
on the built repo. **The first spike is exactly this: the Flatpak without a player, built and run here, lint-clean.**
Nothing about it needs the MacBook or a device.

## 4. The application id, and the domain

The id is the one thing that can never change (a rename is an *end-of-life rebase* that every installed copy must
follow). Flathub's rule ([requirements → application id](https://docs.flathub.org/docs/for-app-authors/requirements)): a
reverse-DNS id of a domain *"directly related to the project"* under the authors' control and reachable over HTTPS;
projects on GitHub that use a code-hosting id **must** use `io.github.<user>.<app>` (≥4 components); `com.github.*` is
reserved. Verification (the badge) later asks for a token at `https://<domain>/.well-known/org.flathub.VerifiedApps.txt`
or, for `io.github`, a repository named after the app.

Three ids were possible: `dev.jellystructure.Ravilo` (registering `jellystructure.dev`, unregistered on 2026-09-29 —
Google's RDAP answers *not found*), `io.github.jeggy.Ravilo` (free, tied to the account name), and the household's
own domain. **Owner, 2026-09-29: `net.jebster.Ravilo`.** It costs nothing, it is verifiable (one text file at
`/.well-known/org.flathub.VerifiedApps.txt` on the domain, over HTTPS), and the last component is capitalised by
convention. The Mac bundle id and the Android package stay `dev.jellystructure.ravilo`; nothing links them.

**What the choice makes public.** The household's domain was scrubbed from this public repo and its history on
2026-09-23. An app id is the most public string an app carries — the Flathub repository `flathub/net.jebster.Ravilo`,
the store page, the manifest, the metainfo, the desktop file, `FLATPAK_ID`, the release notes and every install's
`~/.var/app/net.jebster.Ravilo` — so **the owner lifted the ban on the domain the same day**: its hash is out of
`scripts/check-deanonymization.sh`, and the domain may be written in the repo (the fence keeps guarding the IP
addresses, the trackers and the library's titles). Two things still hold:

- the metainfo's `homepage` and `bugtracker` point at the GitHub repository, because that is where the project is;
- **everything outside this repository is the owner's to do by hand** (owner, 2026-09-29): the verification file on
  the web server, the Flathub submission, the Flathub repository's settings and secrets. This plan lists those steps;
  it does not perform them.

Note the reviewers test what they can: an app that needs its own server is accepted (every Jellyfin client on Flathub
is one), but the metainfo should say plainly in its first sentence that Ravilo is the viewer for a household's
jellystructure server, and the screenshots should show real screens (stand-in titles — the repo's rule).

## 5. The player on Linux

### 5.1 The seam it fills

R329 left the Linux side of `RaviloPlayer` empty but built the path Compose draws: an engine hands the newest decoded
frame as BGRA bytes into a **three-buffer ring**, `takeFrame()` wraps it as an `ImageBitmap` once per display frame, and
`PlayerVideoSurface` draws it with the WebVTT cue on top (`RaviloPlayerDesktop.kt:225–260`). A Linux engine needs to:
load a URL with a start position, play/pause/seek, set rate and volume, pick an audio track, report position, duration,
buffering, first frame, dropped frames, errors — `Player.swift`'s fifteen `ravilo_player_*` exports, in Kotlin.

### 5.2 The engines

| | **mpv** via `libmpv` | **GStreamer** via `gst1-java-core` | libVLC via vlcj | ComposeMediaPlayer | ✗ the web player in a window |
|---|---|---|---|---|---|
| Plays | everything: MKV, HEVC, DTS, TrueHD, PGS, HLS; HDR tone-mapped | everything with `gst-libav` + the `ffmpeg-full` extension; HLS via `hlsdemux2` | everything | its Linux backend is GStreamer, documented for files, not for track switching | the web player's limits (no track switching, no waiting states) |
| In the runtime? | **no — built as modules** (mpv, libplacebo, libass; jellyfin-media-player's Flathub manifest does exactly this, pinned `mpv v0.39.0`, `libplacebo v7.349.0`, `libass 0.17.3`, ffmpeg from the extension) | **yes** — `gstreamer`, `-base`, `-good`, `-bad`, `-ugly`, `-rs`, `gstreamer-libav` are components of Freedesktop 26.08 (checked in the SDK's element list) | no — VLC is an app on Flathub, not a library; building it is heavy | as GStreamer | — |
| Frames into Compose | **`MPV_RENDER_API_TYPE_SW`**: mpv writes a BGRA frame into a buffer we pass — the ring, one copy, `hwdec=auto-copy` keeps decode on the GPU | `appsink` with caps `video/x-raw,format=BGRA` → a sample per frame → the ring | `CallbackVideoSurface` → a bitmap per frame (the Mac report's numbers) | its own Compose surface | — |
| Subtitles | **rendered by mpv** (text and PGS alike, styled) — we can still hand it R180's position, or draw text ourselves as on the Mac | text from our WebVTT as today; PGS by restream (R285) as on the Mac | by VLC | by it | — |
| Audio track switch | `set_property("aid", n)` — in place | `playbin3` `current-audio` | yes | not documented | no |
| Bindings | hand-written **JNA** over ~20 C functions (`mpv_create`, `_set_option_string`, `_command`, `_render_context_create/_render`, `_wait_event`); JNA is already in the app for the Mac | maintained: **1.4.0, 2025-01-14**, Maven Central, JNA | vlcj (GPLv3, fine) | Maven | — |
| Licence | LGPL-2.1+ (mpv built `-Dgpl=false`; GPL is fine too — the repo is GPLv3) | LGPL | GPLv3/LGPL | MIT | — |
| Verdict | **lean** | **the fallback**, and the choice if building mpv in the sandbox proves fragile | no | no (an extra layer over the fallback) | rejected: road 0 already gives it |

**Why mpv over the runtime's GStreamer:** it is one engine that answers everything the seam asks, including the two
things GStreamer makes us do ourselves (PGS, HLS rendition handling), it tone-maps HDR to SDR honestly on an 8-bit
frame path (the Mac's open question), and the Flathub recipe for it is proven. The cost is three modules that build
in the sandbox (~10 minutes) and a JNA binding of our own — smaller than `libravilo-mac.dylib`.

**What changes for the server:** this Linux app **direct-plays**, like Android — so `DesktopCapabilities` on Linux
becomes the honest list from mpv (or a fixed generous one), `playsOnlyHls()` is false, and the server's negotiation
does what it does for the TV. Nothing new on the backend.

### 5.3 The spike, before the phase

Two days on this box: a Compose Desktop window playing one of the household's 4K HEVC MKVs through `libmpv`'s software
renderer into the ring, measuring copies per second and dropped frames at 1080p and 4K with `hwdec=auto-copy`, an audio
switch, a seek, and a PGS file. If frame delivery is not smooth at 4K, mpv's OpenGL render API into a `SwingPanel`-hosted
heavyweight canvas is the second road on Linux (it exists on X11 — the reason the Mac could not take it), and only then
GStreamer.

### 5.4 The small Linux actuals

MPRIS (`org.mpris.MediaPlayer2.Ravilo`: play/pause, next/previous, title/artist/artwork — the same facts
`MacNowPlaying` publishes) and the Inhibit portal while a film plays, both through dbus-java; the cursor and full screen
already work (`DesktopWindowSeams.kt`).

## 6. CI: the bundle on the release, and Flathub

### 6.1 `deploy-linux.yml` — `ravilo-linux-<N>.flatpak` on every release

Shaped like `deploy-macos.yml`: `version` → `ci` (unless `skip_ci`) → `build`, `workflow_call` from `publish.yml` and
`workflow_dispatch` with a `ref` for a bundle before any release carries one. The build job:

```yaml
build:
  runs-on: ubuntu-latest
  container:
    image: ghcr.io/flathub-infra/flatpak-github-actions:freedesktop-26.08   # Flathub's own image; needs --privileged
    options: --privileged
  steps:
    - uses: actions/checkout@v4
    - name: Capture the Gradle sources                # §3.3 — an empty Gradle home, desktopOnly
      run: ./gradlew --no-build-cache -Dgradle.user.home="$RUNNER_TEMP/gh" -Pravilo.desktopOnly=true
             :ravilo-desktop:createDistributable :captureFlatpakSources
    - name: Render the manifest                       # tag + commit into the template, the sources file beside it
      run: flatpak/render.sh "$VERSION" "$GITHUB_SHA" > "$RUNNER_TEMP/out/$APP_ID.yml"
    - uses: flatpak/flatpak-github-actions/flatpak-builder@v6
      with:
        manifest-path: ${{ runner.temp }}/out/${{ env.APP_ID }}.yml
        bundle: ravilo-linux-${{ env.VERSION }}.flatpak
        runtime-repo: https://flathub.org/repo/flathub.flatpakrepo   # the bundle names where the runtime comes from
        arch: x86_64
    - run: flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest … && … appstream …
    - name: Attach to the GitHub Release
      if: github.event_name == 'release'
      run: gh release upload "$TAG" ravilo-linux-$VERSION.flatpak --clobber   # + one fixed paragraph in the notes, once
```

The action caches the runtime and the built modules between runs (mpv's stack builds once); the bundle carries the
runtime repo's address, so `flatpak install ravilo-linux-1.45.flatpak` on a clean machine adds Flathub and pulls
`org.freedesktop.Platform//26.08` itself. The release note paragraph mirrors the Mac's: *download, `flatpak install`
it, or install from Flathub*. Everything runs on GitHub's free runners; no secret is needed for this half.

### 6.2 Flathub — once by hand, then one PR per release

Getting on Flathub ([submission](https://docs.flathub.org/docs/for-app-authors/submission)) is a human act, once:
fork `flathub/flathub` with all branches, `git clone --branch=new-pr`, a branch from `new-pr` holding the manifest,
`flatpak-sources.json`, `flathub.json` and (if not in our repo) the metainfo and desktop file; a PR **against `new-pr`**
titled *Add net.jebster.Ravilo*; `bot, build` for a test build; reviewer comments answered on the same PR. On
approval Flathub creates `flathub/net.jebster.Ravilo`, invites the owner's GitHub account (2FA required, accept
within a week), and the first official build publishes within 1–2 hours.

After that ([maintenance](https://docs.flathub.org/docs/for-app-authors/maintenance)): updates are **pull requests to
that repository**; every PR gets a test build; a merge to `master` (the only branch besides `beta`) starts the official
build, published within 1–2 hours unless held. Our `deploy-linux.yml` release job does this itself, after the bundle:

1. check out `flathub/<app-id>` with a fine-grained PAT (`FLATHUB_TOKEN`, contents + pull requests on that one
   repository — the owner's account is the maintainer);
2. copy the rendered manifest and the fresh `flatpak-sources.json` over the old;
3. `peter-evans/create-pull-request` — *the* third-party action Flathub allows in its repositories
   ([GitHub actions](https://docs.flathub.org/docs/for-app-authors/github-actions)) — opens *Ravilo 1.45*; Flathub's
   bot test-builds it; the owner merges (one click; `automerge-flathubbot-prs` applies only to the bot's own PRs and
   needs a linter exception — not needed here).

Flathub asks that pushes be *"run in a controlled manner and only when necessary"* because every push is a build:
one PR per release is exactly that. Two arches, `x86_64` and `aarch64`, are built by Flathub's own machines from the
same manifest; `flathub.json`'s `only-arches` keeps it to one until the arm64 artefacts are in the sources file (§7 D4).

### 6.3 Why Flathub's own update bot is not the answer here

`flatpak-external-data-checker` runs on every Flathub repo and opens PRs when a source with `x-checker-data` has a
new tag (`type: git`, `tag-pattern: "^v([\\d.]+)$"`). It would bump the git tag and commit — **and leave
`flatpak-sources.json` as it was**, so the first release after a dependency bump fails to build on Flathub. It is fine
as a belt (it costs one `x-checker-data` block), but the braces are our own PR with the regenerated file; the bot's PR
should be closed when ours exists.

### 6.4 Runtime cadence

A new Freedesktop runtime every September, each supported two years. The manifest's `runtime-version` (and the
`ffmpeg-full` and `openjdk21` branches, which move with it) has to be bumped once a year — a one-line PR, but one a
calendar reminder should own, because an EOL runtime is refused for new submissions and users see a warning.

## 7. Decisions for the owner (leans)

| # | Question | Lean |
|---|---|---|
| D1 | **The app id** — register `jellystructure.dev` for `dev.jellystructure.Ravilo`, `io.github.jeggy.Ravilo`, or the household's own domain? | **Decided (owner, 2026-09-29): `net.jebster.Ravilo`** (§4). The domain stays out of the repo as a hostname; every step outside the repo is the owner's, by hand |
| D2 | **The player** — mpv (built as modules) or the runtime's GStreamer? | **mpv** (§5.2), decided by the two-day spike |
| D3 | **Flathub at all, or only the `.flatpak` on the release?** The bundle alone needs no account, no review, no yearly runtime PR; Flathub gives discoverability and automatic updates for every household Linux machine | **both** — the bundle first (one phase), Flathub when the player is in, so reviewers see a player that plays |
| D4 | **Architectures** — `x86_64` only, or `aarch64` too (a Raspberry Pi as a Ravilo box is not absurd) | `x86_64` first (`only-arches`); arm64 is one more `targetPlatforms` entry plus a QEMU or `ubuntu-24.04-arm` job later |
| D5 | **Who merges the Flathub PR** — the owner by hand, or GitHub auto-merge on the repository | by hand for the first releases; auto-merge is granted only to verified apps with modest volume |
| D6 | **Wayland** — accept X11 through XWayland now (every Java desktop app on Flathub does), and revisit when Compose Desktop ships a Wayland toolkit | **accept** |

## 8. What is deliberately not in this plan

AppImage, `.deb`/`.rpm` (Compose can emit them, but the Flatpak is one artefact for every distro and the owner asked
for it), Snap, a Windows build (nothing here stops it later — the same `desktopOnly` switch and a `Msi` target), the
Secret Service for tokens (a `0600` file inside the app's own sandbox directory is as private as the portal's per-app
key; revisit if the household wants a password-manager-shaped store), and any change to the Mac app.

## 9. Prospective phases and size

| Phase | What | Size |
|---|---|---|
| **Rx — Ravilo on Linux plays: mpv** (a later phase; numbered when written) | the spike; `libmpv` JNA binding; the Linux `RaviloPlayer` and `MusicEngine` on it (music and books on the same engine, audio-only); honest capabilities and direct play; subtitles by mpv at R180's position; MPRIS + the Inhibit portal via dbus-java; `X-Ravilo-Platform: linux` becomes the shipped value | 2–3 weeks |
| **R333 — Ravilo as a Flatpak** (written and built 2026-09-29) | `ravilo.desktopOnly`; the meshtastic plugin and the offline init script; the manifest template, metainfo, desktop file, icon; a local build and lint on this box; `ci.yml` builds `desktopOnly` too | ~1 week — a Flatpak that browses is a real first milestone |
| **R334 — the `.flatpak` on every release, and Flathub** (written 2026-09-29) | `deploy-linux.yml` (bundle, lint, attach, notes), the Flathub submission by hand, the per-release PR from CI, `flathub.json`, the yearly runtime bump | 3–5 days + the reviewers' queue |

R333 and R334 were written and built the same evening (the owner: *start making the Flatpak*); the player phase takes
the next free number when its spike is done.

## Sources

- Flathub: [requirements](https://docs.flathub.org/docs/for-app-authors/requirements) ·
  [submission](https://docs.flathub.org/docs/for-app-authors/submission) ·
  [maintenance](https://docs.flathub.org/docs/for-app-authors/maintenance) ·
  [linter rules](https://docs.flathub.org/docs/for-app-authors/linter) ·
  [GitHub actions policy](https://docs.flathub.org/docs/for-app-authors/github-actions) ·
  [runtimes](https://docs.flathub.org/docs/for-app-authors/runtimes)
- [freedesktop-sdk release cycle](https://endoflife.date/freedesktop-sdk) (26.08: 2026-09-01 → 2028-09-01; 24.08 EOL 2026-09-08);
  the SDK's `elements/components` on `release/26.08` (gstreamer, -base, -good, -bad, -ugly, -rs, gstreamer-libav, ffmpeg)
- [`org.freedesktop.Sdk.Extension.openjdk21`](https://github.com/flathub/org.freedesktop.Sdk.Extension.openjdk21) (branches through `branch/26.08`)
- [`meshtastic/gradle-flatpak-sources`](https://github.com/meshtastic/gradle-flatpak-sources) 0.2.1 ·
  [`flatpak-builder-tools/gradle`](https://github.com/flatpak/flatpak-builder-tools/tree/master/gradle)
- [`flatpak/flatpak-github-actions`](https://github.com/flatpak/flatpak-github-actions) v6 (images at `ghcr.io/flathub-infra/flatpak-github-actions`)
- [`flatpak-external-data-checker`](https://github.com/flathub-infra/flatpak-external-data-checker)
- [jellyfin-media-player on Flathub](https://github.com/flathub/com.github.iwalton3.jellyfin-media-player) — the mpv module recipe and finish-args
- [gst1-java-core releases](https://github.com/gstreamer-java/gst1-java-core/releases) (1.4.0, 2025-01-14) ·
  [mpv-examples/libmpv](https://github.com/mpv-player/mpv-examples/tree/master/libmpv) (`main_sw`, the software render API) ·
  [`hypfvieh/dbus-java`](https://github.com/hypfvieh/dbus-java) and its [Flatpak proxy issue #220](https://github.com/hypfvieh/dbus-java/issues/220)
- Java on Wayland: [Wakefield](https://wiki.openjdk.org/display/wakefield/Pure+Wayland+toolkit+prototype) (OpenJDK 25 prototype) ·
  [flathub#1452](https://github.com/flathub/flathub/issues/1452) (the x11+wayland tracker)
- Domain: Google Registry RDAP for `jellystructure.dev` → 404 *not found* (2026-09-29)
- This repo: `deploy-macos.yml`, `ravilo-desktop/build.gradle.kts`, `ravilo-ui/src/desktopMain/**`, `settings.gradle.kts`,
  `research-reports/ravilo-macos-desktop-app-2026-09-29.md`
