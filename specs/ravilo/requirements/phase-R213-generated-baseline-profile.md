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

**Status:** Planned.

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

## Source references
- `specs/ravilo/constitution.md`, "Technology Mandates" — the existing mandate this phase fulfills.
- `ravilo-android/src/main/baseline-prof.txt` — file to be replaced.
- `ravilo-android/build.gradle.kts:97-100` — `androidx.profileinstaller` dependency + its rationale
  comment (kept, unaffected).
- `settings.gradle.kts:14-28` — the conditional-inclusion block the new module joins.
- `specs/research-reports/ravilo-tv-navigation-jank-aot-2026-06-27.md`,
  `specs/research-reports/ravilo-tv-jank-measurement-2026-06-27.md` — prior on-device measurements
  this phase's journey design is grounded in.
- Memory: [[ravilo-tv-coldstart-jank]], [[ravilo-tv-aot-speed]],
  [[feedback-ravilo-deploy-release-only]], [[feedback-no-tv-deploy]].
- Investigation: [[project-ravilo-tv-startup-investigation-2026-08]].
