# ravilo-android-benchmark (R213)

Generates `ravilo-android/src/main/baseline-prof.txt` from a real cold-start/scroll/detail-nav
journey, replacing the hand-authored wildcard file (`HSPLdev/jellystructure/**`,
`HSPLcoil3/**`) that R103 wrote without ever measuring whether a `speed-profile`-only install
(what a real, non-adb-assisted user gets) was actually smooth.

## Regenerating

```
JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-android:generateBaselineProfile -Pravilo.releaseAsDebugId \
  -Pandroid.testInstrumentationRunnerArguments.ravilo.server=<server address> \
  -Pandroid.testInstrumentationRunnerArguments.ravilo.user=<user name> \
  -Pandroid.testInstrumentationRunnerArguments.ravilo.password=<password>
```

The three arguments are used only if server setup or sign-in is on screen, and are **never committed** — not in
this file, not in code, not as a default. Supply them at run time; once the build is signed in on the device
they are not read.

### The device

- A **`TV`-type device on API 33 or later**, claimed exclusively on the device board
  before anything runs. Read `getprop ro.build.version.sdk` first. In practice this is
  probably `google-tv-streamer` (the Stue TV was API 31); an Android TV emulator you run yourself is equally good.
- The build installs as **`dev.jellystructure.ravilo.debug`** (`-Pravilo.releaseAsDebugId` — every variant derived
  from `release`, `nonMinifiedRelease` and `benchmarkRelease`, inherits the suffix), beside the Play build. It is
  **never installed over the Play build**, and the generator's target package is read from the build
  (`androidx.benchmark.targetPackageName`), not hard-coded.
- The Pixel is not a generation device: it runs the phone activity, which is not the journey profiled here.

### What the run does

It launches the **TV** activity (`dev.jellystructure.ravilo.android.MainActivity`) by explicit component, signs in
if server setup, sign-in or the profile picker is in the way (first profile), then asserts Home is drawn. If Home
is not there after 90 s the test **fails**, names the screen it is on, and no profile is written. From Home it goes
down five rows (Right ×8 then Left ×8 in the first two), back up to the hero, opens a detail screen, moves down it
and presses Back. `maxIterations` is the library default (3).

The TV activity exposes its test tags as UiAutomator resource ids (`setup-address`, `login-username`,
`profile-picker`, `home-loaded`, …), so the screens are found without depending on the device language.

### Who runs it

A QA seat runs it (the engineer seat has no device reach) and attaches the command's output and the generated
`ravilo-android/src/main/baseline-prof.txt` to its board row; the engineer commits the file. Regenerate whenever a
Home-critical-path composable (`HomeScreen.kt`, `HeroCarousel.kt`, `Tile.kt`, the detail screens) changes
meaningfully. There is no CI gate for the generation itself — CI has no TV or emulator.

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
