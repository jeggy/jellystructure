# Phase R289 — Ravilo's Android floor is API 24, so a release can reach the Play Store again

> Play Console, rejecting v1.37's upload (run `35748110733`, 2026-09-22): *"Play automatic protection
> requires a minimum SDK version of 24 or higher. The uploaded App Bundle has a minimum SDK version of 21.
> Update the minSdkVersion in your app's Android manifest or build.gradle file."*
>
> Owner, 2026-09-24: *"Yes, let's fix this one. But write spec first and then we'll fix it later."*

## Status

`Planned` — written 2026-09-24 from a failed release, found during the soveværelse-TV test sweep. Not
dev-reviewed, not built.

## Context

Every Android module still declares `minSdk = 21` — the value R01 picked when Ravilo was a TV-only app
and nobody had looked at the household's hardware:

| Module | File |
|---|---|
| `:ravilo-android` | `ravilo-android/build.gradle.kts:17` |
| `:ravilo-ui` | `ravilo-ui/build.gradle.kts:13` |
| `:ravilo-player` | `ravilo-player/build.gradle.kts:17` |
| `:shared` | `shared/build.gradle.kts:12` |
| `:ravilo-i18n` | `ravilo-i18n/build.gradle.kts:24` |

(`:ravilo-android-benchmark` is already 28 and is not shipped.)

The Play Console has since turned on **automatic protection** for this app, and automatic protection
refuses any bundle below API 24. So **v1.37's Android build never reached Play** — the `play-store /
deploy` job failed at *Upload to Play Store* after the rest of the release succeeded — and **every
future release fails the same way**. What Play serves today is v1.36. Nothing warns about this: the
release page looks published, the backend image is live, and only the Play job is red.

### Nobody in the household is below 24

Every Ravilo device the production backend has ever seen (`ravilo_device`, 2026-09-24):

| Device | Kind | Android (inferred from the model, except where it says measured) |
|---|---|---|
| BRAVIA 4K VH21 (stue) | TV | 12+ |
| BRAVIA 4K VH2 (soveværelse) | TV | 12 (API 31, measured) |
| BRAVIA 4K GB ATV3 | TV | Sony's 2018+ line — Android 8 or later |
| Pixel 9 Pro | phone | 16+ |
| SM-S911B (Galaxy S23) | phone | 13+ |
| LE2123 (OnePlus 9 Pro) | phone | 11+ |

API 24 is Android 7.0 (2016). No Android TV that can run the current Media3 build well is older.

### What is already written for 24+

The code already branches on levels above 21 in six places (`HdrCapabilities.kt` on N/M/Q,
`PlayerImmersiveEffect.kt` and `ReducedMotion.kt` on O/P). The `N` and `M` branches become always-true
at 24 and are dead code afterwards. R261's dev review noted `minSdk 21` made some window calls inert;
nothing depends on that inertness.

## Requirements

### FR-R289-1 — One floor, declared once
`minSdk = 24` in every shipped Android module. The number lives in **one place** (a
`gradle/libs.versions.toml` version entry, e.g. `ravilo-minSdk = "24"`, read by all five build files), so
the modules cannot drift apart again. The benchmark module keeps its own 28.

### FR-R289-2 — Dead version branches go
Branches that can no longer be false at 24 (`SDK_INT >= N`, `SDK_INT >= M`) are removed, not left as
always-true guards. Branches on O/P/Q stay.

### FR-R289-3 — The release proves it reached Play
The phase is done when a published GitHub Release's `play-store / deploy` job goes **green** and the
build appears on Play's closed-testing track (R215). A green compile is not the acceptance test; the
upload is.

### FR-R289-4 — A failed Play upload is not silent
The `publish.yml` release run already fails red; that is not enough, because the release *looks*
published everywhere else. The Play job's failure message is surfaced in the run summary
(`$GITHUB_STEP_SUMMARY`) as one line naming the reason Play gave, so the next person reading the
release run sees *why* before opening logs.

## Invariants
- No behaviour change on any device at API 24 or above. The APK a TV installs from Play behaves exactly
  as a v1.36 install does, plus whatever else the release carries.
- The release ART verification (phase 231, FR-231-3) and the player dex guard keep passing.

## Out of scope
- Turning Play's automatic protection **off** to keep 21. Considered and rejected: nothing in the
  household needs 21, and the protection is worth having.
- Raising the floor further (e.g. to 26 for adaptive icons without compat). Only what Play requires.

## Open questions
1. Does the Play Console list **any** install below API 24 (its *Android vitals → Devices* or
   *Statistics → Android version* page)? If it does, raising the floor stops that device's updates — the
   owner should see the number before the release, not after.
2. Must v1.37's missing Android build be re-uploaded on its own, or does the next release simply carry
   everything since v1.36? (Lean: the next release carries it; Play version codes only need to rise.)

## Verification
- Compile: every Android module, `:ravilo-android:assembleRelease`.
- `scripts/check-player-dex.sh`, the ART verify step in CI.
- A GitHub Release whose Play job uploads successfully (FR-R289-3).
