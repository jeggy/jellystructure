# Phase 231 — Nothing publishes before CI passes, and CI runs the tests we already have

> 2026-09-17: a sideloaded release build crashed the living-room TV the moment *Resume* was pressed —
> the second time the same release-only `VerifyError` has shipped (first: 2026-09-06, to a GitHub
> release **and** the Play Store internal track). Every check passed both times. Looking at why: CI
> has never run a single unit test, and neither publishing workflow waits for CI at all.

## Status

`✓ Built` 2026-09-17 — owner request the same day, not dev-reviewed. Workflows + two scripts; no
product code. The first green run on GitHub is the acceptance and is still owed.

**Numbering:** verified against `STATUS.md` 2026-09-17 — admin taken through **230**.

## Current state (traced 2026-09-17)

- `ci.yml` runs one job: the Docker test stack + Playwright, plus `check-css-scoping.sh`. It does
  **not** run `linuxX64Test` (342 tests), `:ravilo-ui:testDebugUnitTest` (126), `check-phases.sh` or
  `check-mobile-css.sh` — the two fences the design-sync incidents produced are run by hand.
- `publish.yml` (GHCR images, on every push to `main` and on a release) and
  `deploy-play-store.yml` (Play internal track, on a release) have **no dependency on CI**. v1.21 was
  released while CI for its commit was still running.
- The release-only player crash is invisible to everything above: `PlayerScreen`'s R8-compiled dex
  method needed 257–266 registers; past ~256 ART's verifier rejects the class. Debug builds, unit
  tests, Wasm and every compile check pass.

## Requirements

- **FR-231-1 — CI is one reusable workflow with four jobs.** `ci.yml` gains `workflow_call`:
  `checks` (the three fence scripts), `unit` (`linuxX64Test` + `:ravilo-ui:testDebugUnitTest`),
  `android-release` (FR-231-2/3) and the existing `e2e` job, unchanged.
- **FR-231-2 — the register guard.** `scripts/check-player-dex.sh` reads the release APK with
  `dexdump`, resolves `PlayerScreenKt`/`LiveTvPlayerScreenKt` through the APK's own `mapping.txt`
  and fails when the widest method uses more than **250** registers (cliff ≈ 256). Proven: 266 on the
  crashing commit (`0f55569c`) → FAIL; 236 after the fix → OK.
- **FR-231-3 — ART verifies the real release APK.** `scripts/verify-release-apk-on-art.sh` installs
  the R8 release APK on an emulator, forces `cmd package compile -m verify -f`, and fails on any
  `dex2oat` *Verification error / failed to verify* line. Proven locally on an Android 16 emulator:
  the crashing commit fails **on the exact method the TV reported**; the fixed build passes. In CI it
  runs on API 31 (the TV's Android 12) via KVM. This is the definitive test; FR-231-2 is its fast,
  device-free proxy and its explanation.
- **FR-231-4 — nothing publishes without CI.** `publish.yml` runs `ci.yml` first; the GHCR `publish`
  job and the Play Store deploy both `needs: ci`. A release therefore costs one CI run, not zero, and
  the Play deploy is a job of the same pipeline (so it cannot race ahead of the images or of CI).
  `deploy-play-store.yml` becomes `workflow_call` + `workflow_dispatch`; a manual dispatch runs CI
  itself first.
- **FR-231-5 — CI no longer double-runs on `main`.** `ci.yml` triggers on `pull_request` and
  `workflow_dispatch`; pushes to `main` get their CI through `publish.yml`.
- **FR-231-6 — the release rule changes with it.** "Release only after the push-triggered publish run
  is green" (v1.18) becomes: *cut the release whenever; it publishes only if its own CI passes.*

## Non-goals / follow-ups

- **An e2e test of the TV API** (e.g. 229's stop → Home keeps its Continue row, end to end): the
  mock Jellyfin has no login-by-name, Resume, NextUp or playback endpoints, and no spec under
  `tests/e2e` touches `/api/tv/**`. 229's race is pinned deterministically by
  `HomeFeedServiceStopKeepsContinueRowTest`, which FR-231-1 finally runs. Worth its own phase.
- Driving the player UI on the emulator (needs a backend and media). FR-231-3 catches the class of
  failure without it.
