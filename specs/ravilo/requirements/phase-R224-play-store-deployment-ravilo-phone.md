# Phase R224 — Merge Ravilo TV and Ravilo phone into one universal app

> Requested 2026-09-02, same session as the Ravilo TV Play Console listing setup: *"we want to deploy apk
> deployments within our github CI setup..."* for the phone app too, having just confirmed the TV app's
> listing is a separate Play Console entry from the phone app's. First drafted as "give `ravilo-phone` its
> own second Play Console listing + CI job" (mirroring R215's exact shape). **Redirected mid-draft**: *"I've
> seen other apps listing them as the same item. How can we also do that?"* — the Netflix/YouTube/Disney+
> pattern, one Play Store listing serving both phone and TV from one APK. Chosen over the two-listing
> approach specifically **because nothing has shipped to Play Console yet** — this was the one-time cheap
> window to consolidate before either package name accumulated a real install base.

**Status:** ✓ Built 2026-09-02/03 — module merge complete, compiles clean. Play Console setup (the single
app entry, already created under `dev.jellystructure.ravilo` per R215) needs its per-form-factor store
assets (TV banner/screenshots were already prepared; phone screenshots captured 2026-09-02 against
`ravilo-phone` remain valid — the merge changed no UI code, only entry-point plumbing). Not dev-reviewed,
not on-device tested.

## 1. Why one listing was possible here

`ravilo-android` (TV) and `ravilo-phone` were always thin entry-point wrappers around the same shared
`:ravilo-ui` + `:ravilo-player` modules (`ravilo-phone`'s own build-file comment: *"reusing :ravilo-ui +
:ravilo-player unchanged. Only the manifest + Activity differ"*). Both `MainActivity`s were near-identical
— same `RaviloAppContext.init` / `RaviloPlayerEngine` wiring, same `RaviloRoot()` composable — differing
only in window/system-bar handling (TV: full-screen immersive + keep-screen-on; phone: standard windowing,
portrait lock). No UI fork was needed to merge them; `Modifier.dpadFocusable` in `:ravilo-ui` already
attaches touch gestures alongside D-pad handling, so the same screens already worked on both inputs.

## 2. What changed

- **One `applicationId`: `dev.jellystructure.ravilo`** (the TV app's, since that's the id the existing
  Play Console app entry and R215's CI pipeline already target). `dev.jellystructure.ravilo.phone` is
  retired — nothing had shipped under it, so no migration/user impact.
- **`ravilo-phone` module deleted.** Its `MainActivity.kt` moved into `ravilo-android`'s source tree
  unchanged (same package `dev.jellystructure.ravilo.phone`, same class body) as a second entry-point
  Activity living alongside the TV one. Its resources (icons, proguard rules) were identical to
  `ravilo-android`'s already and needed no copying. `settings.gradle.kts`'s `include(":ravilo-phone")` line
  removed.
- **`AndroidManifest.xml`: both `<uses-feature>` declarations now optional.**
  `android.software.leanback` flipped from `required="true"` (which is *why* Play only ever distributed
  this app to TV devices) to `required="false"`; `android.hardware.touchscreen` stays `required="false"`
  (unchanged). Neither feature restricts distribution now — matching how Netflix/YouTube/Disney+ declare
  themselves.
- **Two `<activity>` entries, one manifest.** `.android.MainActivity` keeps only the
  `LEANBACK_LAUNCHER` category (dropped `LAUNCHER`, which it held incidentally before merely so a
  sideloaded APK still launched on a phone) — Android TV launchers only look for `LEANBACK_LAUNCHER`.
  `.phone.MainActivity` (moved in) declares the standard `LAUNCHER` category and picks up the phone
  launcher entry instead. Each device's own launcher resolves to the entry point built for it,
  automatically — no runtime device-type branching needed.
- **No CI changes required.** `deploy-play-store.yml` already builds `:ravilo-android:bundleRelease` and
  uploads it to `dev.jellystructure.ravilo`'s Play internal track (R215) — that AAB now simply contains
  both entry points, so the existing pipeline ships both experiences from one release with zero workflow
  edits.

## 3. Play Console — what's still manual

The app entry itself (`dev.jellystructure.ravilo`) already exists per R215's setup. What's left, once
Play Console recognizes the updated manifest's dual `<uses-feature>` declarations (on the first upload
after this change):

1. Confirm Play Console now offers per-form-factor store listing sections for this one app (phone
   screenshots + a TV banner/TV screenshots), rather than assuming TV-only.
2. Upload the prepared phone screenshots (`presentation/screenshots/play-store-demo/`, 8 files, captured
   2026-09-02 against the demo instance) to the phone slot; the TV banner/feature graphic
   (`design/ravilo/assets/store/`) to the TV slot.
3. No new service account, keystore, or secrets — R215's existing five repo secrets and service account
   grant already cover this single app/package.

## 4. Non-goals

- Runtime device-type detection / a single shared Activity — the two-Activity, launcher-category-routed
  approach was simpler and needed no new code beyond moving one file.
- Any change to `:ravilo-ui`/`:ravilo-player` — the merge is entry-point/manifest-only.
- Retiring or renaming the `ravilo-android` Gradle module itself — kept as the canonical name since every
  existing CI/spec/doc reference already points at `:ravilo-android`; only its *scope* grew.
