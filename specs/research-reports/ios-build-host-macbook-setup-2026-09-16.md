# Using the MacBook as the iOS build host — setup and workflow guidelines

**Date:** 2026-09-16 · **Status:** guide, written before the Mac is set up; every step marked *verify*
is to be confirmed on first run and this file corrected. Companion to
`ravilo-mobile-player-chromecast-ios-2026-09-16.md` (§4).

## 1. The one fact that shapes everything

Kotlin/Native can only build **Apple targets on a macOS host** — `iosArm64` / `iosSimulatorArm64`
compile, link and test need Xcode's toolchain and SDKs
([Kotlin/Native supported targets and hosts](https://kotlinlang.org/docs/native-target-support.html)).
On the Debian machine the Kotlin Gradle plugin skips Apple targets with a warning and disables their
tasks, so **the Debian workflow keeps building exactly as today** once iOS targets are added (*verify on
the first `./gradlew build` after adding them*). Everything iOS — the shared framework, the Xcode app,
simulator runs, device installs, TestFlight uploads — runs on the MacBook.

So the model is: **edit on Debian, build and run on the Mac.** Kotlin in `iosMain` and the Swift host
app are ordinary files in this repo; they are pushed (or synced) to the Mac and built there over SSH.

## 2. One-time Mac setup

1. **Xcode** from the App Store (Compose Multiplatform 1.9.x wants a current Xcode — *verify the exact
   minimum in the CMP 1.9.3 release notes*). Open it once, accept the licence, install the iOS
   platform: `xcodebuild -runFirstLaunch`, then `xcode-select --install` for the command-line tools.
2. **Homebrew**, then: `brew install git openjdk@21 cocoapods` (`cocoapods` because the Google Cast iOS
   SDK ships via CocoaPods or a manual XCFramework, not Swift Package Manager). Point `JAVA_HOME` at the
   JDK 21 (the Debian side uses JBR 21 — keep the major version the same).
3. **Android SDK command-line tools** — even for iOS-only builds, `:ravilo-ui` applies the Android
   library plugin, and AGP refuses to configure without an SDK. Install `cmdline-tools`, then
   `sdkmanager "platforms;android-36" "build-tools;36.0.0"` (*verify the build-tools version AGP 9.0.1
   asks for*), and write `sdk.dir=…` into `local.properties`. This also makes `:ravilo-android` and
   `:ravilo-player` configure, which is fine.
4. **`kdoctor`** (`brew install kdoctor`) and run it — JetBrains' environment checker for KMP on macOS.
5. **Clone the repo** to the Mac (`~/jellystructure`). The Mac is a build host, not a second editing
   machine: never commit from it unless deliberately, to avoid the concurrent-commit sweep seen before.
6. **Apple Developer account**: sign in to Xcode → Settings → Accounts; use *automatic signing* for the
   `:ravilo-ios` app target so certificates and profiles are managed by Xcode on this machine. For CI
   uploads, create an **App Store Connect API key** (Users and Access → Integrations) and keep the `.p8`
   on the Mac only.
7. **Keep it awake and reachable**: System Settings → Energy → *Prevent automatic sleeping when the
   display is off* (on power), enable *Remote Login* (SSH). Put the Mac on the same LAN or the existing
   VPN; add an SSH key from Debian; an `ssh mac` alias in `~/.ssh/config`.

## 3. Day-to-day workflow from Debian

```bash
# push the branch you are on, then build the iOS framework on the Mac
git push
ssh mac 'cd ~/jellystructure && git pull --ff-only && ./gradlew :ravilo-ui:linkDebugFrameworkIosSimulatorArm64'

# run the app on a simulator (headless-friendly)
ssh mac 'cd ~/jellystructure/ravilo-ios && xcodebuild -scheme Ravilo -destination "platform=iOS Simulator,name=iPhone 16" build'
ssh mac 'xcrun simctl boot "iPhone 16"; xcrun simctl install booted <path to .app>; xcrun simctl launch booted dev.jellystructure.ravilo'
ssh mac 'xcrun simctl io booted screenshot ~/shot.png' && scp mac:~/shot.png .
```

- For uncommitted work, `rsync -a --exclude build --exclude .git ./ mac:~/jellystructure/` instead of
  push/pull; treat the Mac copy as disposable.
- A **physical iPhone** plugged into the Mac: `xcrun devicectl device install app --device <id> <.app>`
  and `… process launch …` (Xcode 15+, *verify*). Wireless debugging works after pairing once in Xcode.
- Logs: `xcrun simctl spawn booted log stream --predicate 'subsystem contains "ravilo"'`, or
  `idevicesyslog` (`brew install libimobiledevice`) for a device.
- Editing Swift or `iosMain` Kotlin on Debian is plain text editing; the IDE cannot resolve Apple APIs
  on Linux. If that becomes painful, **JetBrains Gateway** or **VS Code Remote-SSH** into the Mac gives a
  resolved IDE over SSH — but the source of truth stays the Debian checkout.

## 4. CI and TestFlight

- Add the MacBook as a **self-hosted GitHub Actions runner** (Settings → Actions → Runners → New
  self-hosted runner, macOS, label `macos-self-hosted`). A workflow `ios.yml` runs on that label:
  Gradle framework build → `xcodebuild archive` → `xcodebuild -exportArchive` with an `ExportOptions.plist`
  (method `app-store-connect`) → upload with `xcrun altool --upload-app --type ios --apiKey … --apiIssuer …`
  (*verify: Apple is moving uploads to `xcrun notarytool`/Transporter-style APIs; use whatever the
  current Xcode documents*). Runner secrets: the API key id, issuer id, and the `.p8` path.
- Mirror `deploy-play-store.yml`'s shape: tag-driven, version derived from the tag, release-only,
  silent (per the household's deploy conventions). TestFlight internal testing is enough for the
  household; App Store review is a separate later decision.
- The runner must be *online* for iOS CI to run; the workflow should skip, not fail, when it is not
  (`if: github.event_name == 'push' && contains(github.ref, 'refs/tags/')` plus a runner-availability
  timeout), so the existing Linux `ci.yml` never goes red because the laptop is closed.

## 5. Project changes the iOS target needs (for the bring-up phase)

- `shared/build.gradle.kts` and `ravilo-ui/build.gradle.kts`: add `iosArm64()` and
  `iosSimulatorArm64()`; `ravilo-ui` exports a framework (`binaries.framework { baseName = "RaviloUi" }`),
  Ktor `ktor-client-darwin` in `iosMain`.
- New `:ravilo-ios` — an Xcode project (or `xcodegen` spec kept in the repo) with a Swift `App` that
  hosts `ComposeUIViewController`, `Info.plist` entries for local network / Bonjour (Cast) when that
  phase comes, and a `Podfile` for `google-cast-sdk` (CocoaPods; the manual XCFramework is the fallback).
- VLCKit: `pod 'VLCKit'` (formerly `MobileVLCKit`; *verify the current pod name and version Swiftfin
  pins*). LGPL — keep it in its own contained module the way `:ravilo-player` fences the GPL decoders.
- The 30 `expect` declarations in `ravilo-ui` need `iosMain` actuals (list in the companion report §4.1).

## 6. Sanity checks before calling the host "set up"

```bash
ssh mac 'xcodebuild -version; xcrun simctl list devices available | head; java -version; pod --version; kdoctor'
ssh mac 'cd ~/jellystructure && ./gradlew :shared:compileKotlinIosSimulatorArm64'
```

Both must succeed from Debian over SSH with no interactive prompt. If `kdoctor` reports anything red,
fix it before the bring-up phase starts.
