# Phase R333 — Ravilo as a Flatpak

> Owner, 2026-09-29: *"Let's start making the ravilo flatpak app."* — after the research
> (`research-reports/ravilo-linux-flatpak-2026-09-29.md`) and two decisions the same evening: the app id is
> **`net.jebster.Ravilo`**, and *"I want to do all changes outside this repo manually unless I tell you specifically."*

## Scope

Written 2026-09-29 (dev-authored) from the research report's §2–§4.

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

## Triage (2026-10-09, against `main` `12bffb29`)

- **Code: nothing left.** **Owed:** the two lint findings are the screenshot URL of a commit not yet pushed — they
  clear once `main` is pushed (the owner's call); HiDPI on a real display.

## `main` pushed (2026-10-12) — the lint gate is cleared

The owner answered the DEB-668 card with **push now, cut v1.52 after the QA device sweep**, and `main` was pushed to
`origin` the same day (`d37f1a7a..6c386d6a`, 8 commits). The triage note above said the two lint findings *"are the
screenshot URL of a commit not yet pushed — they clear once `main` is pushed"*. That condition is now met, so the
findings are expected to clear on the next lint run; **they have not yet been re-run**, and this amendment records the
precondition falling rather than the result.

**Still owed:** the lint re-run showing both findings gone, and HiDPI on a real display (a device test — no device on
the board has a HiDPI Linux display, so that half may stand as a limitation rather than an owed test; decide it when
the lint re-run lands).

## Amendment 2026-11-22 — the icon at every size a desktop looks for (DEB-5246)

> Owner, DEB-5246, 2026-11-22: after installing the Flatpak, no Ravilo icon showed *"on the launcher in the task bar
> etc"*. Asked where: *everywhere — app menu and taskbar*, on **KDE Plasma**, *"but make it work both in gnome and
> kde"*.

**What was measured** (v1.71's release `ravilo-linux-1.71.flatpak`, imported into a throwaway Fedora 42 container;
nothing was installed on a device):

- The bundle exports what FR-R333-4 promises and nothing more. It has the desktop file (`Icon=net.jebster.Ravilo`,
  `StartupWMClass=dev-jellystructure-ravilo-desktop-MainKt`), the metainfo, and **one** theme icon,
  `hicolor/512x512/apps/net.jebster.Ravilo.png`. `flatpak-builder` adds appstream icons at 64 and 128 px, but those are
  for software centres; no desktop shell reads them.
- Run from the bundle under Xvfb with a window manager, the window is one taskbar client. Its WM_CLASS is the desktop
  file's `StartupWMClass`, and it sets `_NET_WM_ICON` (96×96). The second top-level window, class `Ravilo`, is GDK's
  leader window, which is never mapped. Open question 2's lean still holds.
- With the export directory on `XDG_DATA_DIRS`, KDE's `kiconfinder6` and GTK 3's icon theme both resolve
  `net.jebster.Ravilo` to the 512 px file at every size asked. Without it, neither finds anything.

So the 512-only icon resolves in a clean session, but not on the owner's Plasma, and the cause on that machine is not
measurable from here. Every Flathub app ships its icon at the common sizes as well, and nearly all of them ship an SVG.
That puts the icon in directories that every icon cache and theme index already lists, so no desktop has to fall back
across sizes to find it. FR-R333-4's *"the icon is the existing 512×512 PNG"* and §Out of scope's *"an SVG icon"*
are superseded by:

**FR-R333-7 — The icon is installed at every common size, and as an SVG.** The Flatpak installs
`net.jebster.Ravilo` into `hicolor` as a PNG at **32, 48, 64, 128, 256 and 512 px**
(`/app/share/icons/hicolor/<n>x<n>/apps/net.jebster.Ravilo.png`), plus
`/app/share/icons/hicolor/scalable/apps/net.jebster.Ravilo.svg`.
- Every size is R341's Mac/Linux icon (FR-R341-1/2), the same drawing as today's 512: the about-66 % mark with its
  glow on the flat `#000B25` tile. The Linux icon keeps its tile at every size (owner, R341). The bare small jellyfish
  of FR-R341-3 is the web tab's, not this.
- `scripts/render-brand-icons.sh` renders each PNG from the same SVG document it already renders `ravilo.png` from, and
  writes that document as the scalable SVG. The files are committed under `ravilo-desktop/linux/icons/`. The 512 stays
  byte-identical to `ravilo-desktop/icons/ravilo.png`.
- The manifest installs each file with its own line. The desktop file's `Icon=` is unchanged. Nothing outside the
  Flatpak changes: not the Mac's `.icns`, not the window icon.

**Acceptance (FR-R333-7):**
1. The built bundle's `/export/share/icons/hicolor/` holds all six PNG sizes and `scalable/apps/…svg`, each the size
   its directory names (`ostree ls` after `flatpak build-import-bundle`, as measured above).
2. `flatpak-builder-lint` stays clean on the manifest and the appstream.
3. In a clean container with the bundle's export on `XDG_DATA_DIRS`, `kiconfinder6` and GTK's icon theme each resolve
   `net.jebster.Ravilo` to the scalable SVG or a PNG that is not the 512.
4. The owner installs the next release's `.flatpak` on the KDE Plasma machine. The app menu entry and the running
   window's taskbar entry both show the Ravilo icon. No QA device runs KDE Plasma or GNOME (this host has no desktop
   session), so that test is the owner's, and GNOME's proof is acceptance 3.

If acceptance 4 still shows no icon, the cause is that session's icon cache or `XDG_DATA_DIRS`, not the package.
That goes back to DEB-5246 as a note to the owner, not as more code.
