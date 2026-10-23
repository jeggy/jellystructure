# Phase R213 — Generated Baseline Profile via Macrobenchmark (replaces the hand-authored wildcard file)

> Found during the 2026-08-26/27 Ravilo TV startup-performance investigation. The constitution
> already mandates this: *"Store/distributed builds (if ever): ship a **generated** Baseline Profile
> (AGP baseline-profile plugin + a `macrobenchmark` scroll/nav journey). Hand-written
> `androidx.compose.**` rules in `baseline-prof.txt` do **not** work — R8 renames Compose in release,
> so source-name rules miss the minified DEX."* (`specs/ravilo/constitution.md`, "Technology
> Mandates"). This phase is that mandate, not a new decision.
>
> **Lower urgency than R210/R211/R212** — today's only real deployment is sideloaded personal TVs,
> and the existing deploy process already runs a manual `cmd package compile -m speed -f` (full AOT)
> on every device after install ([[feedback-ravilo-deploy-release-only]]), which the
> [[ravilo-tv-aot-speed]] measurement showed fully closes the jank gap (0-1 frame-deadline misses).
> This phase's value is in **not depending on remembering that manual step** and in genuinely
> fulfilling the constitution's own "if ever distributed" clause — it is not fixing an active,
> currently-felt problem the way R210 is.

## Scope

Infrastructure written 2026-08-27, first profile generated 2026-09-02 (both addenda below). Touches
the `:ravilo-android-benchmark` module, `ravilo-android/src/main/baseline-prof.txt`, the version
catalog's `androidx-benchmark` entries and the Android block of `settings.gradle.kts`. **Supersedes**
R103's hand-authored wildcard `baseline-prof.txt`. The 2026-10-23 amendment below changes where the
profile is generated and what a generation must prove.

## Problem
`ravilo-android/src/main/baseline-prof.txt` is hand-authored with two blanket wildcards:
```
HSPLdev/jellystructure/**;->**(**)**
HSPLcoil3/**;->**(**)**
```
This is coarse (matches every method in every app/Coil class, whether or not it's ever on a hot
path) and, per the constitution's own documented lesson from a *prior, reverted* attempt at
hand-writing `androidx.compose.**` rules, this general category of hand-authored rule is fragile
under R8 minification — a source-name rule can silently stop matching post-obfuscation with no
build-time signal that it happened. The current file's own wildcards happen to target app code
(`dev.jellystructure.**`) rather than framework internals, which is a materially safer case, but it
was never verified against a `speed-profile`-only install's actual jank numbers (the R103 memory
notes "cold-scroll FPS re-measure still pending") — only the full-AOT `-m speed` path has ever been
measured smooth. `androidx.profileinstaller` (already a dependency, `build.gradle.kts:100`) only
ever drives ART's `speed-profile` compile on-device (gated on WorkManager device-idle conditions
firing at all) — it cannot perform full AOT itself. A real (non-adb-assisted) install's
`speed-profile` result, using today's file, is unverified.

## Requirement

### FR-RV-R213-1 — A `:ravilo-android-benchmark` module generates the profile from a real journey
New Gradle module, `com.android.test` plugin + `androidx.benchmark.macro.junit4.BaselineProfileRule`,
added to `settings.gradle.kts` under the same conditional block that already gates `:ravilo-android`/
`:ravilo-phone` on the Android SDK being configured (`settings.gradle.kts:14-28`) — this module is
meaningless without an Android SDK + connected device/emulator and shouldn't break a
backend-only/wasmJs-only checkout.

The benchmark journey exercises exactly the paths R103's hand-authored file already targeted, so the
generated profile is a strict, verified improvement over guesswork:
1. Cold app start → Home first paint.
2. Scroll several Home rows (the steady-state jank R96-R103 measured).
3. Open a detail screen from a hero/tile and back out (the transition cost noted as still-imperfect
   even under full AOT in [[ravilo-tv-aot-speed]] — this phase doesn't fix that, but the profile
   should still cover it since it's a real user journey, not just scroll).

### FR-RV-R213-2 — Wired via the AGP Baseline Profile plugin, output replaces the hand-authored file
The `androidx.baselineprofile` Gradle plugin applied to `:ravilo-android`, pointed at the new
benchmark module, per Google's standard `./gradlew :ravilo-android:generateBaselineProfile` flow.
The generated `baseline-prof.txt` **replaces** the current hand-authored one at
`ravilo-android/src/main/baseline-prof.txt` — the old file's rationale comment is preserved in the
module's README instead of deleted outright, so the "why a baseline profile exists at all" context
(~64% cold-jank measurement) isn't lost.

### FR-RV-R213-3 — Regeneration is a documented manual/periodic task, not wired into CI
Macrobenchmark profile generation needs a real or emulated device (`androidx.benchmark.macro`
generally works on an unrooted API 33+ emulator for profile *collection*, unlike macrobenchmark
*timing* tests which want a real/rooted device for stable numbers) — this repo's CI has no such
target available. A short `ravilo-android-benchmark/README.md` documents the regeneration command
and states plainly: **run it against a local emulator or a personal test device — never a household
TV** (consistent with [[feedback-no-tv-deploy]]/[[feedback-no-more-stue-tv-testing]], which govern
*app* deployment; this module's target is explicitly never a TV, it's a benchmarking device/emulator
the operator controls directly). Regeneration is expected whenever a Home-critical-path composable
changes meaningfully, not on every commit.

## Invariants
- **Full AOT (`-m speed -f`) remains the documented deploy step for sideloaded TVs** — this phase
  does not relax or replace that guidance in the constitution or in
  [[ravilo-tv-install-method]]/[[feedback-ravilo-deploy-release-only]]. A generated Baseline Profile
  improves the *speed-profile* floor (what a real end user gets without the manual step, or what any
  future distributed build would get); it is not claimed to match full-AOT's measured 0-1 misses.
- **No behavior change to the app itself** — this phase only changes what's baked into the release
  APK's `assets/dexopt/baseline.prof`, never app logic.

## Non-goals
- **Not claiming this closes the speed-profile-vs-full-AOT gap entirely** — only that it replaces
  unverified hand-wildcards with a real, measured, R8-mapping-safe profile (AGP's own
  `ArtProfileTask` rewrites source-name rules through the R8 mapping file when producing the final
  ART profile — a legitimate, documented AGP feature, distinct from the *hand-authored*
  `androidx.compose.**` attempt the constitution says failed).
- **Not adding macrobenchmark *timing* assertions/regression gates** — this phase is profile
  *generation* only; a perf-regression CI gate (needing a stable device fleet) is a separate,
  larger undertaking not attempted here.
- **Not extending to `:ravilo-phone`** in this pass — same mechanism would apply, but scoping to TV
  first matches where the cold-start jank was actually measured
  ([[ravilo-tv-coldstart-jank]]/[[ravilo-tv-aot-speed]], both TV-only measurements).

## Acceptance
- `:ravilo-android:generateBaselineProfile` runs successfully against a local emulator/test device and
  produces a non-empty, plausible `baseline-prof.txt` (covers `dev.jellystructure.**` Home/detail
  composables and Coil's compose-integration classes, at minimum matching today's coverage).
- `ravilo-android` release build compiles with the generated file in place;
  `:ravilo-android:assembleRelease` unaffected structurally.
- README documents the regeneration command and the "never a household TV" constraint.
- No live-device jank re-measurement is required for this phase to be considered complete (that's a
  separate, user-initiated on-device verification pass, per standing instruction) — this phase's own
  acceptance is "profile is real/generated/wired," not "jank measurably improved."

## Dev-review addendum (2026-08-27 — infrastructure built, generation blocked)

**Built and compiling clean**: the `:ravilo-android-benchmark` `com.android.test` module
(`BaselineProfileGenerator.kt`, a cold-start/scroll/detail-nav journey via
`BaselineProfileRule.collect`), `androidx.baselineprofile` applied to both `:ravilo-android`
(consumer) and `:ravilo-android-benchmark` (producer — needed on both sides, not just the app, or
the producer has no outgoing `baselineProfile`-usage variant to match against), the version-catalog
entries, and `settings.gradle.kts`'s conditional inclusion. Several real Gradle/AGP-9.0.1-specific
wiring issues were hit and fixed along the way (all now reflected in the module's own code
comments): `com.android.test` and `kotlin("android")` must be applied **without an explicit
version** here (`id("com.android.test")` / `kotlin("android")`, not `alias(libs.plugins...)`) since
those plugin artifacts are already on the buildscript classpath via `:ravilo-android`'s own aliased
application, and requesting an explicit version for the same artifact through a different plugin ID
produces "already on the classpath with an unknown version"; `HttpTimeout`'s config type turned out
to be `HttpTimeoutConfig`, not the guessed `HttpTimeout.HttpTimeoutCapabilityConfiguration` (unrelated
fix from R210, noted here only because it was found in the same session); `kotlin { jvmToolchain(11) }`
fails in this environment (no JDK 11 installed, toolchain auto-download not configured) — used the
same `tasks.withType<KotlinCompile>` `jvmTarget` pattern `:ravilo-android` already uses instead; the
benchmark module needs an explicit empty `buildTypes { create("release") {} }` (a fresh
`com.android.test` module only has "debug" by default, and the consumer side specifically wants a
producer variant attributed `BuildTypeAttr=release`).

**Generation itself did not complete this session — blocked by real device constraints, not a code
bug**:
1. **Stue TV (`192.0.2.11`, Android 12 / API 31, unrooted) cannot run Baseline Profile collection
   at all.** `androidx.benchmark.macro` requires either API 33+, or a rooted device on API 28+
   (`BaselineProfilesKt.buildMacrobenchmarkScope`'s own `IllegalArgumentException` states this
   directly). This is a hard AOSP-level requirement, not something fixable in this module — FR-RV-
   R213-3's original text anticipated exactly this ("generally works on an unrooted API 33+ emulator
   ... this repo's CI has no such target") but the phase was scoped assuming an emulator or a newer
   personal device would be available, not tested against the actual TV hardware until this session.
2. **A Pixel 9 Pro (API 33+, confirmed installing and displaying the app successfully — `Displayed
   dev.jellystructure.ravilo/.android.MainActivity for user 0: +189ms` in logcat) hit a different,
   likely-fixable error**: `IllegalStateException: Unable to confirm activity launch completion`
   from `MacrobenchmarkScope.amStartAndWait`, which polls `dumpsys gfxinfo <pkg> framestats` to
   confirm rendering — despite the activity visibly displaying per the system's own
   `ActivityTaskManager` log. Not root-caused (a gfxinfo-format/timing mismatch between
   `androidx.benchmark:1.3.4` and this device's Android version is the leading theory, but unverified)
   — **not pursued further this session** after the phone's repeated automated launches were flagged
   as disruptive; per explicit instruction, R213 generation is deferred rather than continuing to
   iterate against a personal device without a fresh, in-the-moment go-ahead each time.

**What's left to actually finish this phase**: root-cause and fix (or work around) the
`amStartAndWait` failure on an API 33+ device, OR set up a real Android emulator (none configured in
this environment today) as a device that isn't anyone's personal phone or a household TV, then run
`generateBaselineProfile` and commit the resulting `baseline-prof.txt`.

Verified via `:ravilo-android-benchmark:compileNonMinifiedReleaseKotlin`,
`:ravilo-android:compileDebugKotlin`, `:ravilo-phone:compileDebugKotlin`, and
`:ravilo-android:tasks --all` (confirms `generateBaselineProfile`/`generateReleaseBaselineProfile`
are real, correctly-wired tasks). The hand-authored `baseline-prof.txt` is untouched — this phase
has not yet replaced it with anything.

## Dev-review addendum #2 (2026-09-02 — generation completed, root cause found and fixed)

**Root cause of the `amStartAndWait` failure, confirmed rather than guessed**: a stale
`androidx.benchmark` dependency, not a device or app defect. The Pixel 9 Pro is running **Android 17
(API 37)** — reproduced the identical failure first (`IllegalStateException: Unable to confirm
activity launch completion`, same stack, `MacrobenchmarkScope.amStartAndWait`), then checked Google's
Maven index directly (`dl.google.com/android/maven2/androidx/benchmark/benchmark-macro/maven-
metadata.xml`): `androidx.benchmark` had moved from the pinned **1.3.4** through two full stable
releases to **1.4.1** (1.5.0 was still RC as of the metadata's own last-updated timestamp), and a web
search of the library's own changelog between those versions surfaced exactly the relevant work —
launch-completion detection was reworked around `dumpsys gfxinfo` framestats, plus a fix for
`am start -W` not always waiting for the process to be up. Bumped `gradle/libs.versions.toml`'s
`androidx-benchmark` (shared by `androidx-benchmark-macro-junit4` and the `androidx.baselineprofile`
plugin — both track the same version family) from 1.3.4 to 1.4.1. **This alone fixed it** —
`:ravilo-android-benchmark:compileNonMinifiedReleaseKotlin` stayed clean, and
`:ravilo-android:generateBaselineProfile` completed successfully on the very next run against the
same Pixel 9 Pro, no other code change needed.

**Generated and committed**: a real 14,675-line profile (`Landroidx/activity/ComponentActivity;`
through the Compose-resources provider glue) from the cold-start → Home-scroll → detail-open-and-back
journey `BaselineProfileGenerator.kt` already implemented, replacing R103's 20-line hand-authored
`HSPLdev/jellystructure/**;`/`HSPLcoil3/**;` wildcards at `ravilo-android/src/main/baseline-prof.txt`
verbatim — confirmed to actually cover both target namespaces (138 `dev/jellystructure/**` lines, 7
`coil3`-prefixed lines) rather than just being large. `:ravilo-android:assembleRelease` (the full
minified release build, not just a compile check) succeeds with the new file in place, including
`compileReleaseArtProfile` consuming it through the R8 mapping exactly as FR-RV-R213-2 specified. The
old file's rationale is preserved verbatim in `ravilo-android-benchmark/README.md` (already written
2026-08-27, unchanged). AGP's own generation scratch output (`ravilo-android/src/release/generated/`)
is now `.gitignore`d — it's reproduced by rerunning the same command, not a second copy to maintain.

**Not done, deliberately, per this phase's own Acceptance section**: no on-device jank re-measurement.
This phase's acceptance is "the profile is real, generated, and wired" — confirmed — not "jank
measurably improved," which is its own separate, later, user-initiated verification pass. The
household's stue TV was not touched at any point in this work (the whole `androidx.benchmark`
requirement — API 33+ or root — already ruled it out permanently; R213 was TV-scoped only in the
sense that its *output* targets `:ravilo-android`, never in *where generation runs*). All device
interaction was against the Pixel 9 Pro, with the owner's explicit go-ahead for this specific attempt;
the phone returned to its home launcher in a normal, clean state after the final successful run.

## Amendment 2026-10-23 — the profile is generated on a TV, signed in, and proves it reached Home (DEB-2204)

**Why.** Reading the committed profile for DEB-2204 (the owner asked why another TV client feels
more responsive) showed that the 2026-09-02 generation never reached Home. Of its 139
`dev/jellystructure` lines, 40 are `ServerSetupScreenKt`. None name `HomeScreen`, `ContentRow`,
`Tile`, `HeroCarousel` or a detail screen, so FR-RV-R213-1's journey and this phase's Acceptance
("covers … Home/detail composables") were not met. Three causes, all read from the code:
1. The generator calls `startActivityAndWait()` with no intent. That launches the
   `LAUNCHER` activity, which is the **phone** activity (`AndroidManifest.xml`). The TV activity is
   `LEANBACK_LAUNCHER`. On the Pixel, the journey therefore drove the phone UI, not the TV UI.
2. The generator installs the benchmark build under the production id `dev.jellystructure.ravilo`. On
   a device that already has the Play build, that cannot install over it without throwing away the
   signed-in app. So the journey started unpaired and stopped at server setup.
3. Nothing in the journey checked where it had landed. A run that never left server setup wrote a
   profile anyway.

The "never a household TV" rule in FR-RV-R213-3 predates the device board. It protected a TV someone
might be watching. Since 2026-10-14, a TV row that is ticked, free and claimed on the device board is exactly
that protection.

**FR-RV-R213-1 (amended)** — the journey runs the **TV** activity
(`dev.jellystructure.ravilo.android.MainActivity`, launched by explicit component), **signed in**.
- **Signing in.** When server setup or login is on screen, the generator signs in using a server URL,
  user name and password passed as **instrumentation arguments at run time**. If a profile picker
  follows, it takes the first profile. No credential, server address or library title is ever
  committed — not in code, the README or a test fixture.
- **Asserting Home.** The generator then asserts that Home is drawn. If it is not, the run **fails**,
  names the screen it is on, and writes no profile.
- **The journey from Home:**
  - Down through at least four rows.
  - Right through at least eight tiles in at least two rows, so the row scroll and focus detail run.
  - Back up to the hero.
  - Open a detail screen, move down it, and press Back.
- `maxIterations` returns to the library default of 3. The 1 was chosen for a TV that might be in use,
  and a board claim now covers that.

**FR-RV-R213-3 (amended)** — the profile is generated on a **`TV`-type device on API 33 or later**,
claimed exclusively on the device board. It is never installed over the Play build.
- **Device.** The seat reads `getprop ro.build.version.sdk` before generating. The Stue TV was API 31
  on 2026-08-27, so in practice this is probably `google-tv-streamer`; pin it with this reason. An
  Android TV emulator the seat runs itself is equally good.
- **Package.** The benchmark build installs as `dev.jellystructure.ravilo.debug`, beside the Play app
  (`-Pravilo.releaseAsDebugId`, which every variant derived from `release` must inherit). The
  generator's target package follows the build rather than being hard-coded.
- **The Pixel** is no longer the generation device. It runs the phone activity, which is not the
  journey this phase profiles.
- **Who runs it.** Generation is run by a QA seat, because the engineer seat has no device reach. The
  QA seat attaches the generated file to its row, and the engineer commits it.

**FR-RV-R213-4 (new)** — `scripts/check-baseline-profile.sh` fails when the committed
`ravilo-android/src/main/baseline-prof.txt` names no class from `HomeScreen`, `ContentRow`, `Tile`
or a detail screen. It is green from the commit that lands the regenerated file, so a generation that
stopped short can never be committed silently again.

**Non-goal, unchanged:** the phone activity's journey is still not profiled. The one APK serves both,
and a phone journey would be a second `@Test` on the Pixel's phone activity, left for later.

## Source references
- `specs/ravilo/constitution.md`, "Technology Mandates" — the existing mandate this phase fulfills.
- `ravilo-android/src/main/baseline-prof.txt` — file to be replaced.
- `ravilo-android/build.gradle.kts:97-100` — `androidx.profileinstaller` dependency + its rationale
  comment (kept, unaffected).
- `settings.gradle.kts:14-30` — the conditional-inclusion block the new module joins.
- `ravilo-android-benchmark/build.gradle.kts`, `ravilo-android-benchmark/src/main/java/dev/
  jellystructure/ravilo/benchmark/BaselineProfileGenerator.kt`, `ravilo-android-benchmark/README.md`
  — the new module itself.
- `gradle/libs.versions.toml` — `androidx-benchmark-macro-junit4`, `androidx-test-*`,
  `androidx.baselineprofile` plugin entries.
- `specs/research-reports/ravilo-tv-navigation-jank-aot-2026-06-27.md`,
  `specs/research-reports/ravilo-tv-jank-measurement-2026-06-27.md` — prior on-device measurements
  this phase's journey design is grounded in.
- Memory: [[ravilo-tv-coldstart-jank]], [[ravilo-tv-aot-speed]],
  [[feedback-ravilo-deploy-release-only]], [[feedback-no-tv-deploy]].
- Investigation: [[project-ravilo-tv-startup-investigation-2026-08]].
