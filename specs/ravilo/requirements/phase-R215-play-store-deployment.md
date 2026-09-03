# Phase R215 — Automated Play Store deployment for Ravilo TV

> Requested 2026-08-27: *"We want to automatically deploy the ravilo tv app alongside whenever we create
> a github release. Let's prepare all of this."* Follows the same-day Play Store submission-checklist
> conversation (package names, store assets, signing, privacy policy, closed-testing gate).

**Status:** Planned — CI/build-side pieces implemented this session; Play Console setup is manual and
outside this repo, not yet done.

**2026-09-03 addendum — manual first upload done, one blocking error found and fixed.** The user completed
§3 steps 1–4 (app listing, service account, upload keystore, five repo secrets — the keystore was
generated locally via `keytool`, a signed AAB built with it via the same `-Pravilo.keystore.*`/
`-Pravilo.versionCode`/`-Pravilo.versionName` properties CI uses, and delivered for the required manual
first upload). That upload surfaced a **blocking Play Console error**: `compileSdk`/`targetSdk` were still
35, and Play now requires **36** minimum. Bumped to 36 across every Android module
(`ravilo-android`, `ravilo-android-benchmark`, `ravilo-ui`, `ravilo-player`, `shared`) — `minSdk` stays 21,
unaffected; `targetSdk` doesn't gate installability or force new runtime behavior on an older OS, a device
is only ever held to its own OS's own API level regardless of what an app declares. Verified live against
**stue TV (BRAVIA 4K VH21, Android 12 / API 31)** — well below 36, confirming zero compatibility risk before
touching this. Also added `ndk { debugSymbolLevel = "FULL" }` on the release build type in response to a
non-blocking "no debug symbols" warning — confirmed it has **no actual effect** here, since the only native
libraries in the bundle (`libffmpegJNI.so` from Media3's FFmpeg extension, `libandroidx.graphics.path.so`)
are prebuilt third-party `.so` files with no unstripped originals in this repo to extract from; kept anyway
since it's harmless and would help if first-party native code is ever added. The other warning Play Console
showed ("release not available — no testers configured") is a Play Console configuration step, not a build
issue — add testers to the internal testing track there. `:ravilo-android:assembleRelease` and
`:ravilo-android:bundleRelease` both verified clean with the real upload keystore after the bump.

**Second addendum, same day — every TV showed "not compatible" when installing from the tester link.**
Google Pixel 9 Pro installed fine; Google TV Streamer and both Sony BRAVIA TVs were greyed out. Root
cause: `.phone.MainActivity`'s `android:screenOrientation="portrait"` (R224) makes the Android manifest
merger **imply** `android.hardware.screen.portrait` as a *required* feature — Android TV has no portrait
mode at all, so Play's device compatibility filter excluded every TV on that basis alone, independently
of the already-correct `leanback`/`touchscreen` `required="false"` declarations. Confirmed via
`aapt2 dump badging` on the built APK before and after: the feature was listed as `uses-feature` (required)
before, `uses-feature-not-required` after adding an explicit
`<uses-feature android:name="android.hardware.screen.portrait" android:required="false" />` to
`AndroidManifest.xml` — an explicit declaration wins over the merger's implied one. This is a known trap
for any manifest combining an orientation-locked activity with TV distribution in the same app; worth
remembering if another orientation-locked entry point is ever added.

## 1. Scope

Ship `ravilo-android` (`dev.jellystructure.ravilo`, the TV/leanback app) to the Google Play **internal
testing track** automatically whenever a GitHub Release is published, reusing the same trigger and
`vMAJOR.MINOR` tag convention `publish.yml` (Phase 167) already established for Docker images. A release
becomes the single action that ships everything: Docker images to GHCR *and* a build to Play Console.

`ravilo-phone` is explicitly **out of scope** — not requested, no Play listing exists for it yet.

## 2. Decisions

- **Internal track only, never production.** Google requires a closed test with 12+ opted-in testers for
  14+ continuous days before a new developer account can publish to production at all, and even past
  that gate, promoting a build to production should stay a deliberate action taken in Play Console, not
  an implicit side effect of tagging a release. This workflow uploads to `internal`; promotion is manual.
- **Version derivation matches the Docker convention.** The release tag is `vMAJOR.MINOR` (same format
  `publish.yml` validates). `versionName` = the raw `MAJOR.MINOR` string. `versionCode` (Play's required
  monotonic integer) = `MAJOR * 1000 + MINOR`, giving headroom for 999 minor releases per major before a
  collision — enough for this project's cadence.
- **Separate workflow file, not folded into `publish.yml`.** Different deploy target (Play Console vs.
  GHCR), different secrets, different failure domain — a Play Console outage or credential problem
  shouldn't be able to block the Docker publish job or vice versa.
- **Signing:** `ravilo-android/build.gradle.kts`'s existing `signingConfigs.release` already falls back
  local-dev-only to the debug keystore when `local.properties` has no `keystore.*` entries (Phase 167).
  Extended here to also accept the same four values via Gradle project properties
  (`-Pravilo.keystore.*`), so CI can inject a real upload keystore from GitHub secrets without touching
  `local.properties`. Priority: CI property > `local.properties` > debug-keystore fallback (unchanged for
  local dev).
- **`versionCode`/`versionName` become CI-overridable** the same way (`-Pravilo.versionCode` /
  `-Pravilo.versionName`), defaulting to the existing hardcoded `1` / `"1.0"` when unset so local/dev
  builds are unaffected.

## 3. One-time manual setup (not automatable, not done yet)

The Play Publishing API cannot create a new app listing or fill in the store listing, content rating,
data safety form, or privacy policy — all of that has to happen once in Play Console by a human before
this workflow can upload anything:

1. Create the "Ravilo" app in Play Console (this reserves the package name `dev.jellystructure.ravilo`
   permanently) and complete the store listing using the assets already in
   `design/ravilo/assets/store/` (icon-512, feature graphic, TV banner) plus the description/privacy
   policy/content-rating/data-safety items from the earlier checklist conversation.
2. Play Console → Setup → API access → link or create a Google Cloud project, create a service account,
   download its JSON key, and grant it access in Play Console (Release manager, at minimum, on the
   Testing tracks + view app info).
3. Upload one build to the internal track **manually** via Play Console — the API can't create the very
   first release either, only add to an existing release history.
4. Generate a real Android upload keystore (`keytool -genkeypair ...`) — do not reuse the shared debug
   keystore for anything uploaded to Play.
5. Add five repo secrets consumed by `.github/workflows/deploy-play-store.yml`:
   `PLAY_SERVICE_ACCOUNT_JSON`, `ANDROID_KEYSTORE_BASE64` (`base64 -w0 upload.keystore`),
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## 4. Known trigger fragility (carried over from Phase 167)

`publish.yml`'s `release: published` trigger was found this same day to **never fire** when the release
is created via `gh release create` under a fine-grained personal access token — `workflow_dispatch` works
immediately under the same token, isolating the gap to the `release` event specifically, not Actions or
permissions generally. Root cause not yet confirmed (working theory: a known fine-grained-PAT gap in
triggering certain repository events for downstream Actions workflows). This workflow shares the exact
same trigger and will have the exact same gap. Until resolved: publish releases via the GitHub web UI to
reliably fire both `publish.yml` and this workflow, or fall back to each workflow's `workflow_dispatch`
input. See `publish.yml`'s header comment for the live status of that investigation.

## 5. Functional requirements

### FR-R215-1 — CI-overridable version + signing in `ravilo-android/build.gradle.kts`

`defaultConfig.versionCode`/`versionName` and `signingConfigs.release`'s four keystore values each read,
in priority order, a Gradle project property (`-Pravilo.*`, set by CI) → `local.properties` (unchanged
local-dev path) → hardcoded default (`1` / `"1.0"` / debug keystore). No behavior change for existing
local/sideload builds that pass neither.

### FR-R215-2 — `deploy-play-store.yml`

New workflow, triggered on `release: published` (same tag-format validation as `publish.yml`) and
`workflow_dispatch` (manual re-run against an existing release tag). Builds
`:ravilo-android:bundleRelease` with the derived version + injected signing secrets, then uploads the AAB
to the Play `internal` track via the Play Publishing API. Fails closed (non-`vMAJOR.MINOR` tag → error,
not a silent no-op), matching `publish.yml`'s existing validation behavior.

## 6. Non-goals

- `ravilo-phone` deployment (no Play listing exists yet).
- Automatic promotion internal → production, or any staged rollout percentage — always manual in Play
  Console.
- Creating the Play Console app listing, service account, or upload keystore — human-only steps, §3.
