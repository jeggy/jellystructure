# Phase R273 — The real, signed Ravilo APK gets attached to the GitHub Release

## Status

`✓ Built` — written and built 2026-09-20, and **proven by release `v1.31` the same day**: that
release carries `ravilo-1.31-release.apk` (9,461,747 bytes), signed with the real upload keystore,
attached alongside R272's `ravilo-tizen-1.31.wgt`. Before this, the only artifacts a release produced
were an `.aab` nobody can install directly and `ci.yml`'s throwaway-keystore APK that was never meant
to be installed.

## What is wrong

Cutting a release currently produces two signed artifacts that never leave their own distribution
channel: `deploy-play-store.yml` builds `:ravilo-android:bundleRelease` (an `.aab`, Play Store only —
nobody can install an AAB directly) and uploads it to the internal testing track, and `ci.yml`'s
`android-release` job builds a REAL R8-minified release-shaped APK but signs it with a **throwaway debug
keystore generated fresh every run**, solely so ART can verify the release build's class-loading —
explicitly "only ever verified and uploaded as a CI artifact," never meant for anyone to install.

So today, the only way to get a real, installable Ravilo build onto a device that isn't opted into the
Play Store's internal testing track (the household's own TVs before they're added as testers, anyone the
owner wants to hand a build to, a sideload for a device Play Services doesn't cover) is to build it by
hand, locally, with the real keystore. R272 already established the pattern for exactly this shape of gap
— the signed Tizen `.wgt` is otherwise stuck the same way (no store submission API) and gets attached to
the release directly.

## Requirements

**FR-R273-1 — One Gradle invocation builds both real, signed artifacts.** `deploy-play-store.yml`'s
existing `Build release AAB` step becomes `:ravilo-android:bundleRelease :ravilo-android:assembleRelease`
in one call, with the identical `-Pravilo.versionCode`/`-Pravilo.versionName`/`-Pravilo.keystore.*`
properties already used for the AAB — the **same real upload keystore**, not a second one, and no second
keystore-decode step. `applicationVariants.all`'s existing output-naming rule
(`ravilo-<version>-release.apk`, `build.gradle.kts:94`) names it predictably; nothing there changes.

**FR-R273-2 — The APK reaches the release even if the Play Store upload fails.** The attach-to-GitHub-
Release step runs **before** `Upload to Play Store`, not after — a Google Play API hiccup must not be
able to prevent the simpler, more foundational distribution channel (a direct download link) from
shipping. Mirrors R272's own `find`-count-guard style: exactly one `.apk` under
`ravilo-android/build/outputs/apk/release/`, a build artifact upload for inspection outside the release
(30-day retention, matching R272's), then `gh release upload <tag> <apk> --clobber`.

**FR-R273-3 — `contents: write` on this workflow file, matching R272's own precedent.**
`deploy-play-store.yml`'s workflow-level `permissions:` gains `contents: write` (was `contents: read`);
`deploy-tizen-tv.yml:90-91` already proves this exact pattern works for a `workflow_call`-invoked file.

**FR-R273-4 — No new secrets, no new keystore.** Every credential used is one already required and
documented in this file's own header (`ANDROID_KEYSTORE_BASE64`/`_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`) — this phase adds no setup step and no new secret.

## Non-goals

- Renaming `deploy-play-store.yml` or splitting the APK-attach step into its own workflow file. The
  header's own "different deploy target, different secrets, different failure domain" reasoning for
  keeping deploy targets in separate files doesn't apply here — this shares the exact same secrets and
  the exact same signed build as the AAB it already produces; it is one build reaching two
  destinations, not a second deploy target. A comment records this reasoning inline for the next
  reader who wonders why the Play-Store-named file also touches `gh release`.
- Uploading `mapping.txt` (R8's deobfuscation map) anywhere for the sideloaded APK. The AAB path's
  crash symbolication is unaffected (Play Console handles it from the AAB upload, per
  `build.gradle.kts:76-79`'s `debugSymbolLevel = "FULL"` comment); a sideload installer's own crash
  reports being unreadable stack traces is a pre-existing, orthogonal gap this phase doesn't close.
- Any change to `ci.yml`'s `android-release` job or its throwaway debug keystore — that job verifies
  ART loads the release build's classes and has never been about producing something installable.
- Changing what track(s) the Play Store deploy targets, or anything about the manual
  `workflow_dispatch` re-deploy path beyond it now also (harmlessly, idempotently via `--clobber`)
  re-attaching the APK on every manual re-run.

## Acceptance

1. `deploy-play-store.yml` builds exactly one `.apk` and one `.aab` from a single Gradle invocation,
   both signed with the real upload keystore (verified locally with a throwaway keystore standing in
   for the real one, proving the command chain and naming — the real keystore itself is never
   available outside CI).
2. The APK is attached to the GitHub Release as `ravilo-<version>-release.apk` before the Play Store
   upload is attempted.
3. Reverting `permissions: contents: write` back to `read` makes the attach step fail with a clear
   permissions error, not a silent no-op — confirms the permission is actually load-bearing.
4. A real published release shows both `ravilo-tizen-<version>.wgt` (R272) and
   `ravilo-<version>-release.apk` (this phase) as downloadable assets.

## Open questions

None — this is a direct, minimal extension of R272's already-proven pattern, using a keystore and
version-numbering scheme this workflow already has in production use.
