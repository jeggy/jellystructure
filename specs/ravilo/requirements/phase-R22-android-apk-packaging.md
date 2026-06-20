# Phase R22 — Android TV APK packaging (FR-RV22)

**Status:** ✓ Done

## Problem

`:ravilo-android` built a functional app (R01–R21) but shipped only a placeholder dark-box TV
banner (XML shape drawable), no launcher icon, no splash screen, a release build type with
minification disabled, and no signing setup. Before distributing or sideloading the APK for real
use, the app identity, splash screen, and release build pipeline need to be in place.

## Requirements

1. **TV launcher banner** — 320×180 PNG in `drawable-xhdpi/` wired via `android:banner`. The banner
   is the primary visual in the Android TV / Google TV home launcher; it must show the Ravilo brand
   mark and wordmark on the Aurora background.

2. **Adaptive launcher icon** — covers icon contexts outside the launcher (Android Settings app
   list, sideload installers, ADB):
   - Foreground + background PNGs in `mipmap-anydpi-v26/` + XML adaptive icon referencing them.
   - `<monochrome>` layer for Android 13+ themed icons.
   - Legacy density mipmaps (mdpi → xxxhdpi) as pre-v26 fallback for `ic_launcher` and `ic_launcher_round`.

3. **Android 12+ splash screen** (`core-splashscreen`):
   - Background `#0A0C13` (brand dark).
   - Animated icon: the padded Ravilo mark (`splash_logo.png`, 432² transparent PNG).
   - Theme chain: `Theme.Ravilo.Splash` (extends `Theme.SplashScreen`) → `postSplashScreenTheme`
     → `Theme.Ravilo` (extends `Theme.AppCompat.NoActionBar`).
   - `installSplashScreen()` called in `MainActivity.onCreate` before `super.onCreate`.

4. **Release build type** with R8 minification + resource shrinking, configured in `build.gradle.kts`.

5. **Signing config** — reads `keystore.*` properties from `local.properties`; falls back to the
   well-known debug keystore (`~/.android/debug.keystore`) so `assembleRelease` works out-of-the-box
   for sideloaded home use. To sign with a real key:
   ```
   # local.properties (not committed)
   keystore.file=<absolute path to .keystore / .jks>
   keystore.password=<store password>
   keystore.alias=<key alias>
   keystore.keyPassword=<key password>
   ```

6. **Output filename** — APK renamed to `ravilo-{versionName}-{buildType}.apk` for legibility.

## Asset source

All PNG assets are generated from the Ravilo vector masters (`assets/brand/ravilo-mark.svg`,
`assets/brand/ravilo-lockup.svg`) and documented in `design/ravilo/Ravilo - Android TV Assets.html`
(R22 asset pack). Drop `assets/android/**` into `ravilo-android/src/main/res/` to update; the
Gradle config then picks them up automatically.

## What was done

- Deleted placeholder `res/drawable/tv_banner.xml`.
- Copied all 15 PNG files from `design/ravilo/assets/android/` into the right `res/` subfolders.
- Created `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` (adaptive icon XML with
  `<background>`, `<foreground>`, `<monochrome>`).
- Created `res/values/themes.xml` with `Theme.Ravilo` and `Theme.Ravilo.Splash`.
- Added `androidx.core:core-splashscreen:1.0.1` to `gradle/libs.versions.toml` and `build.gradle.kts`.
- Added `installSplashScreen()` in `MainActivity.onCreate`.
- Updated `AndroidManifest.xml`: `android:icon`, `android:roundIcon`, `android:banner="@drawable/banner"`,
  `android:theme="@style/Theme.Ravilo.Splash"`.
- Configured `signingConfigs.release` (local.properties / debug-key fallback) in `build.gradle.kts`.
- Enabled R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) + `proguard-rules.pro`.
- Added debug build suffix (`.debug` appId, `-debug` versionName) to keep side-by-side installs distinct.
- Output APK: `ravilo-android/build/outputs/apk/release/ravilo-1.0-release.apk`.

## Install command (sideload via ADB)

```bash
# Build
./gradlew :ravilo-android:assembleRelease

# Install (TV must be in developer mode + ADB debugging enabled)
adb connect <TV_IP>:5555
adb install ravilo-android/build/outputs/apk/release/ravilo-1.0-release.apk
```
