# Phase R22 — Android TV APK packaging (FR-RV22)


## What was built

### Brand assets
- **TV banner** (required for Android TV launcher): 320×180 PNG in `drawable-xhdpi/banner.png`.
- **Adaptive launcher icon**: foreground + background + monochrome PNGs in `mipmap-anydpi-v26/`
  with `ic_launcher.xml` / `ic_launcher_round.xml`; legacy density mipmaps (mdpi → xxxhdpi) as
  pre-API-26 fallback.

### Splash screen
`core-splashscreen 1.0.1`; `Theme.Ravilo.Splash` sets brand background `#0A0C13` + `splash_logo.png`
center icon; `installSplashScreen()` called in `MainActivity.onCreate()`.

### Release build
R8 minification + resource shrinking enabled in `release` build type. `proguard-rules.pro` keeps
Ktor/serialization/Compose reflection entries. Signing: `local.properties` `keystore.*` override;
falls back to debug key if not present so CI builds without credentials.
