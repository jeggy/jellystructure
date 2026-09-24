# Phase R289 — Ravilo's Android floor is API 24, so a release can reach the Play Store again

> Play Console, rejecting v1.37's upload (run `35748110733`, 2026-09-22): *"Play automatic protection
> requires a minimum SDK version of 24 or higher. The uploaded App Bundle has a minimum SDK version of 21.
> Update the minSdkVersion in your app's Android manifest or build.gradle file."*
>
> Owner, 2026-09-24: *"Yes, let's fix this one. But write spec first and then we'll fix it later."*

## Status

**✓ Built and proven 2026-09-25.** FR-R289-1/-2/-4 built 2026-09-24; **FR-R289-3 met by v1.38** (run
`36065731242`): the fence passed against the real bundle in CI and again before the upload, and
`play-store / deploy` went green — the first Play upload accepted since v1.36. Version code 1038 is on
the closed-testing track. Written 2026-09-24 from a failed release, found during the soveværelse-TV test sweep.
**Dev-reviewed 2026-09-24 against `main` `9d2636bb`** (see §Dev review at the bottom: FR-R289-4 has to
be rewritten as a CI fence plus a failure summary, because the upload action cannot report Google's
reason; FR-R289-1 and -2 are one change, not two; open question 2 closes against the workflow). Not
built.

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

### FR-R289-4 — A low floor never reaches the upload, and a failed upload is not silent
The `publish.yml` release run already fails red; that is not enough, because the release *looks*
published everywhere else — the images are on GHCR and the signed APK is attached to the GitHub Release
before the Play step runs. Two things, neither of which asks the upload action for its error text (a job
cannot read its own log — dev review, item 3):
1. **A fence, in CI and before the upload.** `scripts/check-min-sdk.sh` reads the release APK's declared
   `minSdkVersion` (`aapt2 dump badging`) and fails, with the reason in plain words, when it is below 24.
   It runs in `ci.yml`'s release job beside the dex guard — a commit that lowers the floor goes red on
   push, before any tag exists — and again in the deploy job after the APK is attached (R273 attaches
   first on purpose; a low-floor APK is still sideloadable) and before *Upload to Play Store*.
2. **A summary line on failure.** If the upload step itself fails, an `if: failure()` step writes the
   version, the track and *where Google's reason is* to `$GITHUB_STEP_SUMMARY`, so the next person
   reading the release run sees what is missing before opening logs.

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
   **Closed 2026-09-24 (owner):** the Play Console lists Android 9 and up. Nothing below API 24.
2. Must v1.37's missing Android build be re-uploaded on its own, or does the next release simply carry
   everything since v1.36? (Lean: the next release carries it; Play version codes only need to rise.)
   **Closed — dev review item 4:** the next release carries it, and v1.37 cannot be re-run at all.

## Verification
- Compile: every Android module, `:ravilo-android:assembleRelease`.
- `scripts/check-player-dex.sh`, the ART verify step in CI.
- `scripts/check-min-sdk.sh` against the release APK (and, deliberately, against a `minSdk = 21` build
  once, to see it fail).
- A GitHub Release whose Play job uploads successfully (FR-R289-3).

## Dev review (2026-09-24, against `main` `9d2636bb`)

Every citation in Context holds: `minSdk = 21` at the five lines named, the benchmark module at 28
(`ravilo-android-benchmark/build.gradle.kts:28`), no `uses-sdk` or `tools:overrideLibrary` in any
manifest, no `@RequiresApi`/`@TargetApi` anywhere, no `values-vNN` resource folder. The phase is as small
as it reads. Seven corrections and closures.

1. **Only `:ravilo-android`'s number reaches Play; the other four matter for FR-R289-2, not for the
   upload.** A library's `minSdk` is a floor the manifest merger checks against the app's, never a value
   that propagates upward, so `ravilo-android/build.gradle.kts:17` alone decides what the AAB declares.
   The reason to raise all five anyway is lint: `NewApi` runs per module against *that module's* floor,
   and the three guards FR-R289-2 removes all live in `:ravilo-ui` — `HdrCapabilities.kt:43` and `:56`
   on `N` (`HEVCProfileMain10HDR10` and `MIMETYPE_VIDEO_DOLBY_VISION` are both API 24 constants) and
   `:58` on `M` (`maxSupportedInstances`, API 23). Take them out with `:ravilo-ui` still at 21 and lint
   flags every one. FR-1 and FR-2 are one change, and FR-1's single declaration is what stops a module
   drifting back to 21 and re-flagging them later.
2. **"Six places" is ten guard sites in three files, of which three go.** `HdrCapabilities.kt`
   `:43/:45/:50/:56/:58/:126`, `ReducedMotion.kt:17`, `PlayerImmersiveEffect.kt` `:27/:48/:62`. Only the
   two `N` guards and the one `M` guard become dead at 24; the `O`, `P` and `Q` ones stay, as the FR
   says. One more edit rides with them: the comment at `HdrCapabilities.kt:47-49` reasons from "minSdk 21
   devices" and should say 24 — a comment that names the old floor is how the next reader concludes it
   never moved.
3. **FR-R289-4 asks the upload action for something it cannot give; what the FR wants is better served
   by a fence.** The upload is `r0adkll/upload-google-play@v1` (`deploy-play-store.yml:183`). Its failure
   text lives only in the step log — a step cannot read its own job's log, and the action exposes no
   output on failure — so "one line naming the reason Play gave" is unreachable without replacing the
   action. Two things do reach the goal. **(a)** A pre-flight check on the release APK the job already
   holds in `$APK_PATH` (`:168`): `aapt2 dump badging` prints `minSdkVersion:'NN'` (`aapt2` sits in the same
   build-tools directory `check-player-dex.sh` already finds `dexdump` in), and a `scripts/check-min-sdk.sh`
   failing with *"the bundle declares minSdk 21; Play's automatic protection refuses anything below 24"*
   names the reason more plainly than Google did. It belongs in **`ci.yml`, next to the dex guard
   (`:135`)**, not only in the deploy job: 231 made every release run its own CI, so a commit that lowers
   the floor goes red on push, before a tag exists. In the deploy job it sits after *Attach to the GitHub
   Release* (`:177`) — R273 attaches first on purpose, and a low-floor APK is still a perfectly
   sideloadable one. **(b)** An `if: failure()` step after the upload writing the version, the track and
   *"Google's reason is in the 'Upload to Play Store' step"* to `$GITHUB_STEP_SUMMARY`. Rewrite the FR
   as (a) + (b); the sentence "the reason Play gave" goes.
4. **Open question 2 closes: the next release carries it, and v1.37 cannot be re-shipped at all.**
   `versionCode = MAJOR*1000 + MINOR` (`deploy-play-store.yml:112`): Play holds 1036, refused 1037, the
   next tag is 1038 — Play needs only a code above the last one it *accepted*. The other half is stronger
   than the lean: a `workflow_dispatch` re-deploy checks out the tag it is given (`:130`), and `v1.37`
   has `minSdk 21` in it, so re-running 1.37 fails identically. The fix ships in a new tag or not at all.
5. **The "no behaviour change" invariant is real work for D8, and CI already does it.** AGP passes the
   floor to D8/R8 as `--min-api`; at 24 the compiler stops desugaring default and static interface
   methods (native from API 24), so the shipped dex changes even though no source does — exactly the
   class of change the 231 fences exist for. Both run on every push (`ci.yml:135` the register guard,
   `:141` ART on an API 31 emulator), so the invariant is checked by construction rather than by a
   manual step; the spec should say so. The emulator at 31 needs no change.
6. **Context's "nothing depends on that inertness" misreads R261.** R261's dev review says the opposite
   of inert: at `minSdk 21`, `setDecorFitsSystemWindows(false)` is *what lets the player draw behind the
   bars* on Android 14 and below, and the calls were **kept** for that reason (R261 dev review item 1).
   None of that changes at 24 — API 24–34 devices still need the call — so the conclusion (nothing
   depends on the floor) is right, for the reverse reason. Drop the sentence or restate it.
7. **Open question 1 stays with the owner, and the repo cannot help.** `ravilo_device` stores no API
   level (`RaviloDevice.sq` — display name, policy, ceilings; nothing from `Build.VERSION`), and the
   client never sends one, so the Context table is inferred from model names exactly as it says. The only
   measured source is Play Console → *Statistics → Android version* (or *Reach and devices*), and only
   Play-track testers are in scope: the household's own devices are all 7.0+ by model, and a sideloaded
   APK from the GitHub Release is not subject to Play's rule — though an API 21–23 device could not
   install a 24-floor APK from either channel. One minute in the Console, before tagging.

**Precision on FR-R289-3.** `track: alpha` (`publish.yml:150`) is Play's closed testing, and a
closed-testing release passes Google's review before testers see it (R215's 2026-09-21 addendum).
"Appears on the track" means the release shows in the Console under closed testing — not that a
tester's TV has it, which can lag by a day.

**Catalog mechanics, for the build.** `[versions] android-minSdk = "24"` in `gradle/libs.versions.toml`,
read as `libs.versions.android.minSdk.get().toInt()`. `compileSdk = 36` is repeated in the same six files
and can ride the same entry set in the same edit — recommended, not required; it is the same drift, not a
new floor.

**Net effect.** Five one-line edits plus one catalog entry, three guards and one comment in
`HdrCapabilities.kt`, a `check-min-sdk.sh` in CI, a failure-summary step, and a tag. Nothing about the
household's devices, R215's track or 231's fences needs touching.
