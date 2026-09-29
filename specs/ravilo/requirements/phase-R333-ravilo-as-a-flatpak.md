# Phase R333 — Ravilo as a Flatpak

> Owner, 2026-09-29: *"Let's start making the ravilo flatpak app."* — after the research
> (`research-reports/ravilo-linux-flatpak-2026-09-29.md`) and two decisions the same evening: the app id is
> **`net.jebster.Ravilo`**, and *"I want to do all changes outside this repo manually unless I tell you specifically."*

## Status

`⚠ Partial` — **built 2026-09-29; `flatpak-builder` built it in a Fedora container, it installed and ran on that
desktop; the two remaining lint findings are the screenshot URL of a commit not yet pushed** (§Build notes). Written 2026-09-29 (dev-authored) from the research report's §2–§4. Number verified
free: `origin/main` `2c36a521` and local `main` top at R332.

**First of two:** **R333** (this — the Flatpak, built offline the way Flathub builds) → **R334** (the `.flatpak` on
every GitHub release, and Flathub). The Linux **player** is a later phase of its own (the report's mpv lean); until it
lands the Flatpak browses, signs in and casts, and plays nothing — R328's Linux state, packaged. **Builds on** R328
(the desktop app runs on Linux; `DesktopPaths` follows XDG), R330 (JmDNS discovery on Linux), R331 (the release
artefact pattern), phase 231 (nothing publishes before CI passes).

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | The app id | **`net.jebster.Ravilo`** (owner, 2026-09-29) — the household's own domain, Flathub-verifiable; the Mac bundle and the Android package stay `dev.jellystructure.ravilo`, nothing links them. The domain came off the de-anonymisation fence the same day |
| D2 | Runtime | **`org.freedesktop.Platform` 26.08** (released 2026-09-01, supported to 2028-09-01); the JDK from `org.freedesktop.Sdk.Extension.openjdk21` at build time only — the app ships the jlinked runtime `createDistributable` already makes |
| D3 | How the build stays offline | **the Gradle distribution and every Maven artefact as manifest sources**, listed by `:captureFlatpakSources` (the `org.meshtastic.flatpak.sources.settings` plugin, 0.2.2) and consumed through one init script that makes every repository the one offline directory |
| D4 | What Gradle configures in the sandbox | **only the desktop modules** — `-Pravilo.desktopOnly=true` (FR-R333-1); the alternative, teaching the sandbox Android, Kotlin/Native and Node, is not a build Flathub can run |
| D5 | Windowing | **X11** (`--socket=x11 --share=ipc`): OpenJDK 21 has no Wayland toolkit; XWayland serves a Wayland desktop. Revisit when Compose Desktop runs on a Wayland-capable runtime |
| D6 | Where the app's files live | unchanged — `DesktopPaths` reads `$XDG_DATA_HOME`, which the sandbox sets to `~/.var/app/net.jebster.Ravilo/data`; tokens stay the `0600` file (R328 D5's Linux fallback) |
| D7 | Architectures | **x86_64** in round 1; aarch64 is one more `targetPlatforms` entry and a builder later |
| D8 | Everything outside this repository | **the owner's, by hand** (owner, 2026-09-29): the Flathub submission, its repository, the domain's verification file, any secret. This phase and R334 list those steps; they perform none |

## Requirements

**FR-R333-1 — `ravilo.desktopOnly`.** A Gradle property, read in `settings.gradle.kts` and the four build scripts it
touches, under which the build configures nothing Flathub's sandbox cannot: settings includes only `:shared`,
`:ravilo-i18n`, `:ravilo-ui`, `:ravilo-castv2` and `:ravilo-desktop`; the root project applies neither the Kotlin
Multiplatform nor the SQLDelight plugin and declares no targets, database or backend task (it still resolves
`buildVersion` and generates nothing the desktop needs); the three libraries apply the Android plugin only outside this
mode (`apply false` in `plugins {}`, `apply(plugin = …)` + `configure<LibraryExtension>` otherwise) and declare only
`jvm("desktop")`. Every other build — every task the repo runs today — is unchanged by the property's absence.
`ci.yml` compiles `:ravilo-desktop` in both modes on every push, so the switch cannot rot unnoticed.

**FR-R333-2 — The sources file, and the offline build.** `settings.gradle.kts` applies the capture plugin; the root
script orders `:captureFlatpakSources` after `:ravilo-desktop:createDistributable` (a compile classpath is resolved when
its compile task runs — captured first, the file lists the plugins and nothing the app links against) and names the
Linux natives (`desktop-jvm-linux-x64`) so a capture on another host would still list them. The capture is always
made from an **empty Gradle home** (`-Dgradle.user.home=$(mktemp -d)`), because the plugin records downloads and a
warm cache downloads nothing. The result, `build/flatpak-sources.json`, is a list of `type: file` sources in Maven
layout under `offline-repository/`; it is **generated per release by CI and never edited by hand** (R334).
`flatpak/gradle-offline.init.gradle.kts` replaces every repository — the settings' plugin repositories before and
after `settings.gradle.kts` runs, the projects' buildscript and dependency repositories — with that directory, and the
manifest's build runs `gradle --offline` with it. The Gradle distribution itself (the wrapper would download it) is an
`archive` source with its checksum.

**FR-R333-3 — The manifest, a template.** `flatpak/net.jebster.Ravilo.yml` carries `@VERSION@`, `@COMMIT@` and
`@DATE@`; `flatpak/render.sh <out> <version> <commit>` fills them for a release (a `git` source pinned to the tag
*and* the commit, an `x-checker-data` block so Flathub's bot can at least notice a new tag) and copies the sources file
beside it, and `render.sh <out> --local` renders the working tree as a `dir` source for a local `flatpak-builder` run.
One module: run Gradle (`--offline`, `--no-daemon`, the Kotlin compiler in-process — the builders are small), copy
`createDistributable`'s app image to `/app/ravilo`, install the launcher, the desktop file, the metainfo (with this
build's release row filled in) and the icon. `finish-args` are exactly the six the report justified: `x11`, `ipc`,
`network`, `dri`, `pulseaudio` — nothing else, and the audio socket is there so the player phase adds no permission.

**FR-R333-4 — What the desktop shows.** `ravilo-desktop/linux/net.jebster.Ravilo.desktop` (`Exec=ravilo`,
`Icon=net.jebster.Ravilo`, `Categories=AudioVideo;Video;Audio;Player;`, `StartupWMClass` = what AWT names the window,
`dev-jellystructure-ravilo-desktop-MainKt`, measured) and `net.jebster.Ravilo.metainfo.xml`: id, name, a summary
without a full stop, `CC0-1.0` metadata licence, `GPL-3.0-only`, `developer id="net.jebster"`, a description that
says in its first sentence that Ravilo is the viewer for a household's jellystructure server, the launchable, homepage
/ bugtracker / vcs-browser at the GitHub repository (never the household's domain as a URL), one screenshot at a
tag-pinned raw GitHub URL, the release row as `%VERSION%`/`%DATE%` placeholders the manifest fills, OARS 1.1 with
nothing to declare, the brand's colours, categories, keywords, `requires` keyboard + pointer + a 768 px display,
`supports internet=always`. The icon is the existing 512×512 PNG (Flathub accepts ≥ 256; an SVG from
`design/ravilo/assets/brand/ravilo-mark.svg` is a later nicety).

**FR-R333-5 — Proof without the sandbox.** `flatpak/verify-offline.sh` fills an `offline-repository` from the sources
file (the capture's own cache by SHA-256, else a fetch), unpacks the very Gradle the manifest names, and runs the
manifest's build command with an empty Gradle home and `--offline`, then the built launcher's `--self-test`. It is
what this host can prove today (no `flatpak-builder` here), and what R334's CI runs before it spends a
`flatpak-builder` run.

**FR-R333-6 — The launcher.** `/app/bin/ravilo` execs jpackage's own `bin/Ravilo`, which reads `lib/app/Ravilo.cfg`
beside the jlinked runtime — the app image works from `/app/ravilo` unchanged.

## Out of scope

The Linux player (mpv, the report's §5 — its own phase) · MPRIS and the Inhibit portal (with the player) · the
`.flatpak` on the release and the Flathub submission (R334) · aarch64 · Wayland · AppImage, `.deb`, `.rpm`, Snap ·
an SVG icon · more screenshots than the one (the owner's, from a real session with stand-in data, before the Flathub
submission) · any change to the Mac app.

## Acceptance

1. `./gradlew -Pravilo.desktopOnly=true :ravilo-desktop:createDistributable` builds the Linux app image on a machine
   with no Android SDK and no Kotlin/Native toolchain (an empty Gradle home is the test); the plain build is unchanged.
2. A capture from an empty Gradle home lists every artefact the desktop build resolves — Compose, Skiko's Linux runtime,
   OkHttp, Coil, JNA, JmDNS, the Kotlin compiler and the plugins — and `flatpak/verify-offline.sh` rebuilds the app
   image from that list alone, offline, and its `--self-test` passes.
3. `flatpak-builder` builds the rendered manifest (`--local` here once installed; a tag in CI, R334), and
   `flatpak run net.jebster.Ravilo` opens the server screen, signs in and browses, its files under
   `~/.var/app/net.jebster.Ravilo/data/ravilo/`.
4. `flatpak-builder-lint manifest` and `… appstream` pass on the rendered manifest and the built metainfo.
5. `ci.yml` fails a commit that breaks the desktop-only configuration.

## Open questions

1. **HiDPI under X11.** Java 2D reads `GDK_SCALE`/`GDK_DPI_SCALE`; whether the sandbox forwards them from a scaled
   desktop is unverified here (no HiDPI display). If not, the launcher can derive `-Dsun.java2d.uiScale` from Xft.dpi.
2. **The window class.** `StartupWMClass` names AWT's default (the main class); giving the window the app id would
   need `--add-opens java.desktop/sun.awt.X11` and a reflective write, which a Mac build would warn about. Lean: leave
   it — the desktop file's field exists for exactly this mapping.
3. **Builder memory.** Gradle runs with `-Xmx4g` (`gradle.properties`) plus the in-process Kotlin compiler; Flathub's
   builders are modest. If the first Flathub build dies for memory, `org.gradle.jvmargs` is overridable per invocation.

## Build notes (2026-09-29)

Built on Debian. The owner does not want Flatpak on the host, so the sandbox build ran in **a Fedora 44 desktop
container the owner asked for the same evening** (`~/fedora`, outside the repo: XFCE on TigerVNC, sshd, noVNC,
`flatpak` + `flatpak-builder`, the checkout bind-mounted) — acceptance 3 passed there, 4 all but the screenshot.

1. **FR-R333-1 works, and cost one surprise.** With the Kotlin Multiplatform plugin applied to the root but no target
   declared, KGP prints an *error*-level diagnostic (*No Kotlin Targets Declared*) even though the build passes; the
   root now applies KGP and SQLDelight only outside desktop-only mode (`apply false` + `apply(plugin = …)`, the
   `kotlin {}` block became `configure<KotlinMultiplatformExtension> {}`). `settings.gradle.kts` had to be reordered:
   Gradle requires `pluginManagement {}` first and the capture plugin's `plugins {}` block second. The normal build:
   `:linkDebugExecutableLinuxX64 --dry-run` resolves, and the desktop tests pass in both modes.
2. **The desktop-only app image:** 172 MB (`bin/Ravilo`, `lib/app/*.jar`, `lib/runtime`), built in 2 m 43 s from an
   empty home. `--self-test` prints *Ravilo 1.46-… · no library (not a Mac) · data … writable · OK*. Under Xvfb the
   window opens (Skia falls back from GL to software there — Xvfb has no GL; a real desktop has `dri`), renders the
   server screen, and AWT names it `dev-jellystructure-ravilo-desktop-MainKt`; that is the desktop file's
   `StartupWMClass`, and the 1280×800 window is the metainfo's screenshot.
3. **The capture's first run listed 477 artefacts and none the app links against** — `:captureFlatpakSources` ran
   before the compile tasks. Ordered after `createDistributable` it lists **834** (`repo.maven.apache.org` 419,
   `plugins.gradle.org` 267, `dl.google.com` 148 — the Android plugin's jars are on the classpath through the
   catalogue's `apply false` and come along; ≈250 MB in all). The plugin's extension lives on the root *project*, not
   on settings, which is where it is configured.
4. **FR-R333-5, the offline replay passed:** `verify-offline.sh` filled the offline repository from the capture's cache
   (834 copied, 0 fetched, 253 MB), unpacked Gradle 9.2.1 from the manifest's own archive, and with an empty Gradle home
   and `--offline` built the app image in 2 m 10 s; `--self-test` printed *Ravilo 1.46 · no library (not a Mac) · … · OK*.
   Every plugin — Kotlin, Compose, serialization, the capture plugin itself — resolved from the directory, which is the
   part that had to be seen to be believed.
5. **`jpackage`'s version:** `ravilo.macPackageVersion` (R331's property, `MAJOR.MINOR.0`) serves Linux too; the name
   is the Mac's and was not changed, since `deploy-macos.yml` passes it.

6. **Acceptance 3, in the Fedora container:** `render.sh --local`, then `flatpak-builder --user --install
   --install-deps-from=flathub --force-clean --disable-rofiles-fuse --state-dir ~/.flatpak-builder --repo ~/repo`:
   every one of the 834 sources downloaded, Gradle ran offline inside the sandbox (*BUILD SUCCESSFUL in 2m*), the app
   exported and installed (162 MB); `flatpak run net.jebster.Ravilo` opened the server screen on the VNC desktop in
   three seconds, WM_CLASS as the desktop file names it, files under `~/.var/app/net.jebster.Ravilo/data/ravilo/`.
   Two things the container taught: Fedora's `flatpak-builder` unpacks a zip with **`bsdunzip`** (its own package),
   and without `/dev/dri` the sandbox has no GL, so Skia draws in software there (a real desktop has `dri`).
7. **Acceptance 4:** `flatpak-builder-lint manifest` — clean. `appstream` and `repo` — one finding each, the same one:
   the screenshot URL (now the commit's, not the tag's — `%COMMIT%`) is unreachable until that commit is pushed, so
   the catalogue mirrors no screenshot (`appstream-missing-screenshots`, `appstream-screenshots-not-mirrored-in-ostree`).
   The `control` relations moved from `requires` to `recommends` on the linter's advice. Both pass once pushed;
   R334's CI lints only pushed commits.
8. **The metainfo's placeholders are `%VERSION%`/`%DATE%`/`%COMMIT%`, not `@…@`:** the manifest's own `sed` that fills them is
   itself rendered, and `s/@VERSION@/@VERSION@/` would have become `s/1.46/1.46/`.
