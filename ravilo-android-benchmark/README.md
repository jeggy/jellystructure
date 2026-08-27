# ravilo-android-benchmark (R213)

Generates `ravilo-android/src/main/baseline-prof.txt` from a real cold-start/scroll/detail-nav
journey, replacing the hand-authored wildcard file (`HSPLdev/jellystructure/**`,
`HSPLcoil3/**`) that R103 wrote without ever measuring whether a `speed-profile`-only install
(what a real, non-adb-assisted user gets) was actually smooth.

## Regenerating

```
./gradlew :ravilo-android:generateBaselineProfile
```

Run this against **a local emulator or a personal test device you control directly — never a
household TV.** This is the same rule that governs *deploying* the app
([[feedback-no-tv-deploy]]/[[feedback-no-more-stue-tv-testing]]): the benchmark repeatedly
launches, scrolls, and navigates the app, which is not something to run unattended against a
device someone else in the house might be using.

Regenerate whenever a Home-critical-path composable (`HomeScreen.kt`, `HeroCarousel.kt`, `Tile.kt`,
the detail screens) changes meaningfully. There's no CI gate for this — the CI environment has no
Android device/emulator available, so this stays a manual/periodic maintenance task.

## Why this exists instead of hand-writing rules

The constitution (`specs/ravilo/constitution.md`, "Technology Mandates") already documents that
hand-written `androidx.compose.**` rules don't survive R8 minification (source-name rules miss the
renamed classes in the minified release DEX) — this module is AGP's own supported mechanism
(`androidx.baselineprofile` + a `com.android.test` instrumentation module), which applies the R8
mapping file when producing the final ART profile, so it stays correct under minification by
construction rather than by luck.

## What this does NOT replace

Full AOT (`adb shell cmd package compile -m speed -f dev.jellystructure.ravilo`) is still the
documented deploy step for every sideloaded TV — see the constitution and
[[feedback-ravilo-deploy-release-only]]. A generated Baseline Profile raises the *speed-profile*
floor (what an install gets *without* that manual step); it does not match full AOT's measured 0-1
frame-deadline misses (see [[ravilo-tv-aot-speed]]).
