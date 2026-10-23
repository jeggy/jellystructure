# Ravilo for the Mac

The Mac app: Compose Desktop on the JVM, showing `ravilo-ui`'s TV layout driven by a keyboard and a mouse
(R328), playing through AVPlayer (R329), casting with its own Cast v2 client (R330), shipped as a `.dmg` on every
GitHub release (R331).

- `src/desktopMain/` — the window, the menu bar, closing and quitting, About.
- `native/` — `libravilo-mac.dylib`, the Swift library (the Keychain, the Computer Name, the player, Now Playing,
  display sleep, Bonjour). Only a Mac with Xcode's command-line tools builds it (`buildMacNative`); anywhere else the
  app runs without it.
- `icons/` — `ravilo.icns` for the bundle, `ravilo.png` for Linux.

## Running it

```
./gradlew :ravilo-desktop:run
```

works on a Mac and on Linux. On Linux, and on a Mac without the Swift library, the app browses and signs in but
plays nothing. `XDG_DATA_HOME` (or
`-Dravilo.data.dir`) gives a second copy its own sign-in.

**Sign-in lives in a file.** Device tokens are in `tokens.json` (mode `0600`) in the app's data directory, on the Mac
as on Linux. Not the Keychain: an app without a team ID gets a Keychain item bound to the exact build, so every update
asked for the login password again (R328 D5, amended 2026-09-30). A token an older build left in the Keychain is moved
across on first use.

**Discovery needs the packaged app.** macOS grants Local Network access to an app bundle, not to a bare `java`
process: develop Chromecast discovery against `createDistributable`'s `Ravilo.app`, not against `run` (R330).

## Checking a bundle

```
Ravilo.app/Contents/MacOS/Ravilo --self-test
```

loads the Swift library, reads the version and the data directory, prints one line and exits 0 — without a window.
The release workflow runs it before uploading (R331 FR-R331-4).

## Seeing and driving a build without its screen

`RAVILO_TESTDRIVER=<dir>` (or `-Dravilo.testdriver=<dir>`) makes the app listen on `<dir>/ctl.sock` and answer one line
per command — a picture of what the window draws, a click, a key, a menu command, what speaker discovery sees:

```
open -g --env RAVILO_TESTDRIVER=/tmp/ravilo-td Ravilo.app
echo "shot home"      | nc -U /tmp/ravilo-td/ctl.sock     # → /tmp/ravilo-td/home.png, one pixel per point
echo "click 120 264"  | nc -U /tmp/ravilo-td/ctl.sock
echo "cmd MODE_MUSIC" | nc -U /tmp/ravilo-td/ctl.sock     # an AppCommand: what a menu item sends
echo "chrome"         | nc -U /tmp/ravilo-td/ctl.sock     # the traffic lights' place, who takes a title-area click
```

It is how a build is checked on a Mac over SSH, where macOS gives a remote shell neither the screen nor the keyboard.
Off unless asked for; input commands refuse while the person at the computer used the app in the last minute. The
commands are listed at the top of `TestDriver.kt`. `w2` before a command points it at the Settings window while that is open
(`w2 shot settings`).

## What it costs on a Mac (R383)

`scripts/check-mac-resources.sh <idle|hidden|cast|music|film>` samples the running Ravilo for 30 s — CPU, footprint,
threads, idle wakeups — against FR-R383-2's budget for that state, and exits 1 on a breach. Start the app with
`-Dravilo.debug.frames` (or `RAVILO_DEBUG_FRAMES=1`) and it also logs, once a minute, how many frames Skia drew
(`~/Library/Logs/Ravilo/ravilo.log`, `FrameCounter.kt`): a hidden window should read 0. `--histogram` adds
`jcmd <pid> GC.class_histogram`; the release's runtime has no `jdk.jfr`, so a JDK 21 on the Mac supplies `jcmd`.

## The signing certificate (once, on a Mac)

Every release is signed with **one** self-signed certificate of our own (R331 FR-R331-3). It is what lets a Mac
approve Ravilo once and open every later version without asking; there is no Apple account and no notarisation.
Make it once, on the MacBook, in an empty folder:

```sh
cat > ravilo-signing.cnf <<'CNF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = ext
[dn]
CN = Ravilo
O = jellystructure
[ext]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
CNF

/usr/bin/openssl req -x509 -newkey rsa:3072 -sha256 -days 7300 -nodes \
  -config ravilo-signing.cnf -keyout ravilo-signing.key -out ravilo-signing.crt
/usr/bin/openssl pkcs12 -export -inkey ravilo-signing.key -in ravilo-signing.crt \
  -name Ravilo -out ravilo-signing.p12          # asks for the export password twice
```

`/usr/bin/openssl` is macOS's own (LibreSSL), which writes the `.p12` in the form `security import` reads on every
macOS version. The certificate is valid for 20 years; it must never need replacing, since a new one costs every Mac one
more *Open Anyway*.

Then give it to GitHub as two repository secrets (with the GitHub CLI signed in, from that folder):

```sh
base64 -i ravilo-signing.p12 | gh secret set MACOS_SIGNING_P12_BASE64 --repo jeggy/jellystructure
gh secret set MACOS_SIGNING_P12_PASSWORD --repo jeggy/jellystructure    # paste the export password
```

(or on github.com: Settings → Secrets and variables → Actions → *New repository secret*, same two names). Put
`ravilo-signing.p12` and its password in the password manager, then delete the four files from the folder — the
`.key` is the private key, unencrypted.

`deploy-macos.yml` refuses to build without both secrets; it never falls back to an ad-hoc signature.

## The `.dmg` window (DEB-1469)

The image opens to a fixed window: the drawn background (steps 4 and 5 of *Open Anyway*, and an arrow), Ravilo and
the Applications shortcut on their drawn targets. `deploy-macos.yml` builds it with
[dmgbuild](https://dmgbuild.readthedocs.io), which writes Finder's layout file with a library (no GUI session needed
on the runner) and copies the app with `ditto` (the signature survives). Then it mounts the result and reads the
layout back (`verify-layout.py`) and re-runs `codesign --verify --deep --strict` on the copy that ships — and only
then signs the image and the appcast signs the final bytes.

- `dmg/dmg-settings.py` — the window: 720 × 520 pt of content (the window frame is 28 pt taller, for the title bar),
  icon centres (190, 190) and (530, 190), 128 pt icons.
- `dmg/background.png` and `background@2x.png` — drawn, not screenshots. They are **rendered, then committed**:
  `scripts/render-mac-open-anyway.mjs` (the command is in its header) turns the drawing in `design/` into these two
  files, and the download page's five pictures. Re-run it only when the drawing changes. The background is the same
  image in Light and Dark, because a Finder background does not follow the appearance.
- `scripts/check-dmg-layout.sh` runs in CI on every push; the rest only runs on the macOS runner.

## The update (R386)

The app updates itself with [Sparkle 2](https://sparkle-project.org) from the release it came from. Three pieces:

- **Sparkle in the bundle.** `fetchSparkle` downloads the pinned release (`build.gradle.kts` holds the version and its
  SHA-256), `bundleSparkle` copies `Sparkle.framework` into `Ravilo.app/Contents/Frameworks`, and `native/Update.swift`
  drives one `SPUUpdater` through the same C bridge as everything else. The Swift library links Sparkle *weakly*: a
  bundle without it still loads and the updater reports itself unavailable.
- **An appcast on every release.** `deploy-macos.yml` signs the `.dmg` with the update key and writes
  `appcast.xml` — one item, the release itself — beside it (`gh release upload --clobber`; on a by-hand run, into the
  run's artifact). It then checks the appcast's signature against the public key the bundle carries.
- **The server floor.** `UpdateCheck` points Sparkle at `releases/download/v<S>/appcast.xml`, where S is the release
  the server is based on, so a Mac never moves past its server (`UpdateRules.plan`, tested in `UpdatePlanTest`).
- **The screens.** Settings ▸ About (`SoftwareUpdate.kt`) draws the *Software update* block from one value,
  `UpdateView.of(status, …)` — the last check and Sparkle's last report, nothing else (`UpdateViewTest`,
  `SoftwareUpdateScreensTest`). A development build and Linux have no block. The ready notice is a row at the sidebar's
  foot, a tile on the rail and a row atop the phone layout's Profile page; *Check for Updates…* is a menu item
  `Update.swift` puts under *About Ravilo*. To look at a state on a build that has none, start the app with the test
  driver and say `update ready`, `update ready-playing`, `update failed-verify` … (`update off` takes it away): it
  changes what is drawn and checks or installs nothing. The names are `UpdatePreview.names`.

### The update key (once, on a Mac)

The EdDSA key pair that signs every appcast. Its public half is committed and baked into every bundle; its private
half is one more repository secret, `SPARKLE_ED_PRIVATE_KEY`. Losing it means no installed Mac can ever verify another
update — **back it up where the signing certificate's `.p12` lives.**

Nothing here needs the repository, a JDK or Gradle: `generate_keys` is a macOS Keychain tool that ships inside
Sparkle's release tarball, and it is **not on `PATH`** until you have downloaded and unpacked that. On the MacBook, in
Terminal (pinned version and SHA-256 are the ones in `build.gradle.kts`):

```sh
# 1. Download Sparkle 2.10.0 and check it against the pin. The last line must print "...: OK".
mkdir -p ~/sparkle-2.10.0 && cd ~/sparkle-2.10.0
curl -fL -o Sparkle-2.10.0.tar.xz https://github.com/sparkle-project/Sparkle/releases/download/2.10.0/Sparkle-2.10.0.tar.xz
echo "c2bf58aa8387266ac179357b1415d6f2635f044da8be41042af32425dae6da0c  Sparkle-2.10.0.tar.xz" | shasum -a 256 -c -
tar -xJf Sparkle-2.10.0.tar.xz
ls bin/generate_keys                         # must exist; the tool is run as ./bin/generate_keys, never bare

# 2. Make the pair. It lives in the login Keychain; macOS may ask to allow access — click Allow.
./bin/generate_keys --account ravilo
#    Prints "A key has been generated…" and the public key (a ~44-character base64 string). Already ran it?
#    It says the key exists and prints the same public key again — safe to repeat.

# 3. Print only the public half. This is NOT secret; it is the one line the repository needs.
./bin/generate_keys --account ravilo -p

# 4. Export the private half to a file (readable by you alone).
( umask 077; ./bin/generate_keys --account ravilo -x ~/ravilo-sparkle.key )
```

Then three things, in this order:

1. **The public key goes to the repository.** Paste the one line from step 3 into a comment on the task (it is public
   by design) and the engineer commits it as `ravilo-desktop/sparkle/SUPublicEDKey.txt`. Or, from a checkout of the
   repository: `./bin/generate_keys --account ravilo -p > ravilo-desktop/sparkle/SUPublicEDKey.txt`, then commit and push.
2. **The private key becomes the secret.** Either of:
   - the browser — `pbcopy < ~/ravilo-sparkle.key`, then
     <https://github.com/jeggy/jellystructure/settings/secrets/actions/new>, name `SPARKLE_ED_PRIVATE_KEY`, paste, *Add secret*;
   - or `gh secret set SPARKLE_ED_PRIVATE_KEY --repo jeggy/jellystructure < ~/ravilo-sparkle.key`, if `gh` is installed
     and signed in on that Mac (`gh auth status`).
3. **Back it up, then delete the file.** Put `ravilo-sparkle.key` in the password manager beside `ravilo-signing.p12`,
   then `rm ~/ravilo-sparkle.key`. The key also stays in the Keychain under the account `ravilo`, so `-x` can be re-run.

**The private key never goes in the repository, an issue, a comment or a log**; the workflow reads it from the secret
on standard input. Only the *public* line in step 3 may be pasted anywhere.

Troubleshooting: `command not found` — you ran `generate_keys` instead of `./bin/generate_keys`, or you are not in
`~/sparkle-2.10.0`. "System denied access to the Keychain" — open Keychain Access, lock the *login* keychain, unlock it,
and run step 2 again. "conflicting key … Please delete the existing item from the keychain" — the tool's own
words: remove the stale Sparkle item it names in Keychain Access, then run step 2 again.

### Trying an update without a release

A `workflow_dispatch` run builds with `-Pravilo.updateFeedOverride=true`: that build honours
`RAVILO_UPDATE_FEED=<appcast url>` (or `-Dravilo.update.feed`) in place of the server-floor logic, and may read a feed
over plain `http` on the local network. A release is never built that way, and ignores both. Serve a later build's
`.dmg` and its `appcast.xml` from a folder (`python3 -m http.server`), edit the appcast's enclosure `url` to the
local address (the signature covers the file, not its URL), and start the older build with the variable set.


## The Flatpak (Linux)

R333 packages the same app for Linux as `net.jebster.Ravilo`, built the way Flathub builds — offline, from a manifest
whose sources are the Gradle distribution and every Maven artefact — and R334 attaches `ravilo-linux-<N>.flatpak` to
each release. It is **not** submitted to Flathub: Flathub's generative-AI policy forbids AI-written manifests and
AI-opened pull requests (R334 §Flathub's AI policy). The pieces:

- `flatpak/net.jebster.Ravilo.yml` — the manifest **template** (`@VERSION@`, `@COMMIT@`, `@DATE@`).
- `flatpak/render.sh` — fills it for a release, or renders the working tree for a local build.
- `flatpak/gradle-offline.init.gradle.kts` — makes every Gradle repository the offline directory.
- `flatpak/verify-offline.sh` — proves the offline build here, without `flatpak-builder`.
- `flatpak/ravilo` — the launcher (`/app/bin/ravilo` → jpackage's `bin/Ravilo`).
- `linux/` — the desktop file, the metainfo (release row filled at build time) and the screenshot.

**The desktop-only build.** `-Pravilo.desktopOnly=true` configures only `:shared`, `:ravilo-i18n`, `:ravilo-ui`,
`:ravilo-castv2` and `:ravilo-desktop`, each with its `desktop` target alone — no Android SDK, no Kotlin/Native, no
Node — which is all Flathub's sandbox can run. `ci.yml` compiles it on every push.

```sh
# 1. Capture every download from an EMPTY Gradle home (a warm cache downloads nothing, so it would capture nothing).
GH=$(mktemp -d)
./gradlew --no-build-cache --no-daemon -Dgradle.user.home="$GH" -Pravilo.desktopOnly=true \
  -Pravilo.macPackageVersion=1.0.0 :ravilo-desktop:createDistributable :captureFlatpakSources
#    → build/flatpak-sources.json (≈830 artefacts, ≈250 MB)

# 2. Prove the offline build: an offline repository from that list, the manifest's own Gradle, an empty home.
flatpak/verify-offline.sh /tmp/ravilo-offline "$GH"

# 3. Render the manifest — for this working tree (a `dir` source) …
flatpak/render.sh /tmp/ravilo-manifest --local
#    … or for a release (the tag and its commit):
flatpak/render.sh /tmp/ravilo-manifest 1.46 "$(git rev-parse v1.46)"

# 4. Build and run it (needs flatpak + flatpak-builder on this machine, and the Flathub remote):
flatpak-builder --user --install --force-clean /tmp/ravilo-build /tmp/ravilo-manifest/net.jebster.Ravilo.yml
flatpak run net.jebster.Ravilo
#    and lint as Flathub does (org.flatpak.Builder from Flathub):
flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest /tmp/ravilo-manifest/net.jebster.Ravilo.yml
```

Inside the Flatpak the app's files live in `~/.var/app/net.jebster.Ravilo/data/ravilo/` (`$XDG_DATA_HOME`, which
the sandbox sets). It plays nothing yet on Linux — the player is a later phase; it browses, signs in and casts.

### A release `.flatpak` in the Fedora QA container (DEB-2340)

Before you test a release bundle there, check that the installed app is that bundle. All the bundles and local builds
share the ref `app/net.jebster.Ravilo/x86_64/master`. A `--local` build stamps itself with the `git describe` tag's
MAJOR.MINOR (`render.sh`). The container still held one from 2026-09-29, built between the v1.46 and v1.47 tags. On
2026-10-23 that build answered as "Ravilo 1.46" when the v1.58 bundle had just been installed, though the bundle's own
metainfo says 1.58. A copy in the other installation (`--user` against `--system`) or one left behind by a refused
install is what `flatpak run` starts.

```sh
# as ravilo, in the container (docker exec -u ravilo -e XDG_RUNTIME_DIR=/run/user/1500 fedora-desktop …)
flatpak list --app --all --columns=application,version,installation,origin | grep -i ravilo   # every copy
flatpak uninstall --user -y net.jebster.Ravilo; sudo flatpak uninstall --system -y net.jebster.Ravilo   # all of them
flatpak install --user -y --bundle ~/ravilo-linux-<N>.flatpak
flatpak info --user net.jebster.Ravilo | grep -E 'Version|Commit'    # must say <N> before anything is tested
pkill -f 'app/Ravilo/bin/Ravilo'   # a running dev build holds the single-instance socket, and the Flatpak shares it
```

Then start it in the GNOME session the way the dev build is started (`~/fedora/README.md`), with two differences.
The test driver's directory must be one the sandbox shares with the container: the sandbox's own `$HOME` and `/tmp`
are not the container's. Pass the variables with `--env=`:

```sh
mkdir -p ~/.var/app/net.jebster.Ravilo/td
export XAUTHORITY=$(ls /run/user/1500/.mutter-Xwaylandauth.* | head -1) DISPLAY=:0
flatpak run --env=SKIKO_RENDER_API=SOFTWARE \
  --env=RAVILO_TESTDRIVER=/home/ravilo/.var/app/net.jebster.Ravilo/td net.jebster.Ravilo
# the log's first line says "Ravilo <N> starting"; the socket is ~/.var/app/net.jebster.Ravilo/td/ctl.sock
```

`IllegalArgumentException: Width (0) and height (0) cannot be <= 0` on AWT-EventQueue-0 is Skiko's software
renderer flushing a 0 × 0 layer: the window has no size, usually because the headless session has no monitor yet.
Builds before v1.48 lack `4c3c476b` (a window opened with no display) and show this most often. If a current build
shows it, run the dev build in the same session at the same moment. The fault is the product's only when the dev
build gets a window and the Flatpak does not.

## Linux: the player (R335)

On Linux the engine is **mpv through `libmpv`** (`ravilo-ui/src/desktopMain/…/desktop/Mpv.kt`, `MpvPlayer.kt`), behind
the same `DesktopEngine` surface the Mac's AVPlayer sits behind. It direct-plays what the household has (MKV, HEVC,
AV1, DTS, TrueHD, PGS), decodes on the GPU where `hwdec` finds one, tone-maps HDR itself, and renders subtitles itself.
Two ways to the screen:

- **the ring** (default): mpv's software renderer writes each new frame, at the surface's size, into R329's
  three-buffer ring and Skia draws it — 3 ms a frame at 1080p, 5 ms for 4K scaled to a 1080p surface (measured in the
  Fedora container, software decode);
- **the GPU window** (`-Dravilo.video=gpu`): mpv's own `vo=gpu-next` into an X11 child window embedded in the Compose
  window, hardware decode to display with no copy; the chrome over it needs Compose's experimental interop blending,
  which `Main` switches on for this mode.

Knobs, all system properties: `ravilo.video=sw|gpu` · `ravilo.hwdec=<mpv hwdec>` · `ravilo.mpv.vo=<vo>` (gpu mode) ·
`ravilo.mpv.ao=null` (a machine with no sound server).

**Without libmpv** the app browses and casts but plays nothing (R237's card); a Linux distribution's `libmpv.so.2`
or the Flatpak (which builds mpv) provides it. This host has none, so:

```sh
# the bench, in the Fedora container (~/fedora, libmpv from Fedora, the app image built here):
./gradlew -Pravilo.desktopOnly=true :ravilo-desktop:createDistributable
docker exec fedora-desktop su - xfce -c 'cd /srv/jellystructure/ravilo-desktop/build/compose/binaries/main/app/Ravilo && \
  JAVA_TOOL_OPTIONS=-Dravilo.mpv.ao=null ./bin/Ravilo --mpv-bench /home/xfce/media/test1080.mkv 6'
#   → first frame, fps through the ring, render ms/frame, hwdec, the tracks, a seek, an audio switch, a subtitle pick
docker exec fedora-desktop su - xfce -c 'cd … && DISPLAY=:1 JAVA_TOOL_OPTIONS="-Dravilo.mpv.ao=null -Dravilo.video=gpu" \
  ./bin/Ravilo --mpv-window /home/xfce/media/test1080.mkv 12'
#   → the real surface in a window with a red Compose box over the picture; screenshot :1 to see whether it draws over
```
