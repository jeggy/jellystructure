# Phase R331 — The Mac app ships as a `.dmg` on every GitHub release

> Owner, 2026-09-29: *"So when fully implemented, the dmg file will be attached to the github release, just like
> android and Tizen."*

## Status

`Planned` — written 2026-09-29 (dev-authored). **Dev-reviewed 2026-09-29** against `main` `4c67e49f` (§Dev review) —
build from it; **owner, 2026-09-29: no Apple bills — the `.dmg` is ad-hoc signed, never notarised.** Number verified free. **Last of four**
(R328 → R329 → R330 → **R331**).

**Mirrors** R264's `deploy-tizen-tv.yml`, which signs and attaches `ravilo-tizen-<N>.wgt`, and the Android release
build that attaches `ravilo-<N>-release.apk`. v1.44 carries both. **Builds on** phase 231: a release publishes only if
its own CI is green.

## Decisions (leans)

| # | Question | Lean |
|---|---|---|
| D1 | When the release starts carrying it | **Only once R328–R330 are ✓ Built** (the owner's "when fully implemented"). Until then the workflow runs by hand only and keeps its `.dmg` as a workflow artifact |
| D2 | Signing | ~~Developer ID + notarisation when the Apple secrets exist; ad-hoc otherwise~~ **Ad-hoc, always** (owner, 2026-09-29: *"I will not pay any apple bills. I only want dmg version of the app"*). No secrets, no notarisation, one path; the notes always carry the *Open Anyway* line |
| D3 | Runner | GitHub's **`macos-15`** (Apple Silicon) — free on a public repository |
| D4 | File name | **`ravilo-mac-<N>.dmg`**, beside `ravilo-<N>-release.apk` and `ravilo-tizen-<N>.wgt` |

## Requirements

**FR-R331-1 — `deploy-macos.yml`**, shaped like `deploy-tizen-tv.yml`:

- **Entry points:** `workflow_call` with `version` and `skip_ci`, from `publish.yml`, and `workflow_dispatch` with the
  same two inputs, for re-running a tag.
- **Version:** validated the same way — plain `MAJOR.MINOR`, anything else fails the job — and turned into jpackage's
  `packageVersion` as `MAJOR.MINOR.0`. The app's own version string is the tag's.
- **Jobs:** `version` → `ci` (the reusable CI unless `skip_ci`) → `build`.

**FR-R331-2 — Build.** The `build` job runs on `macos-15`:

1. Check out the tag.
2. Set up Temurin JDK 21 (arm64).
3. Build the Swift library (R329's).
4. Run `:ravilo-desktop:createDistributable` — Compose's native distribution uses `jpackage` and `jlink`, and an app
   bundle for macOS can only be built on macOS.
5. **Sign the whole bundle ad-hoc ourselves:** `codesign --force --deep --sign - Ravilo.app` (dev review 2 — on Apple
   Silicon every binary must carry at least an ad-hoc signature, and jpackage does not sign the Swift library).
6. Make the image ourselves: `hdiutil create -volname Ravilo -srcfolder … -format UDZO ravilo-mac-<N>.dmg`.

The bundle carries:

- `LSMinimumSystemVersion` 14.0 and arm64 only (R328 D2);
- R330's Local Network keys;
- R328's icon;
- no hardened runtime and no entitlements — those exist for notarisation, which this app never has.

**FR-R331-3 — Signing: ad-hoc, always** (owner, 2026-09-29: no Apple bills). No Developer ID, no notarisation, no
secrets. The job summary says **ad-hoc signed — not notarised** on every run, so nobody wonders.

**FR-R331-4 — Verify before uploading.**

- `codesign --verify --deep --strict` on the app.
- Run the app's **`--self-test`** from the bundle's own launcher (`Ravilo.app/Contents/MacOS/Ravilo --self-test`, which
  returns the app's exit code). That new flag loads the Swift library, reads its own version and its data directory,
  prints one line and exits 0 without opening a window. It catches the failure that actually happens with bundled JVM
  apps: a missing or unsigned nested library.

**FR-R331-5 — Attach.**

- `gh release upload v<N> ravilo-mac-<N>.dmg --clobber`, from a job with `contents: write` (as `publish.yml` already
  grants for Tizen).
- `publish.yml` gains a `macos:` job — `needs: [version, ci, publish]`, `if: github.event_name == 'release'`,
  `secrets: inherit`, `skip_ci: true` — exactly like `tizen-tv:`. **This job is added by the commit that marks
  R328–R330 ✓ Built** (D1). Before that commit, `deploy-macos.yml` runs by hand only and uploads to the run's
  artifacts, never to a release.

**FR-R331-6 — The release notes gain one fixed paragraph** under the house-style prose:

> *Ravilo for Mac: download `ravilo-mac-<N>.dmg`, open it and drag Ravilo to Applications (macOS 14 or later, Apple
> Silicon). The first time, macOS will refuse to open it because the app is not registered with Apple: open System
> Settings → Privacy & Security, scroll to the Ravilo line and choose **Open Anyway** — once per install. (From a
> terminal, `xattr -dr com.apple.quarantine /Applications/Ravilo.app` does the same.)*

R328's update line (FR-R328-8) repeats the *Open Anyway* sentence under *Download*.

**FR-R331-7 — A Mac build between releases.** `ci.yml` gains a `macos` job that runs only when `ravilo-desktop/**` or
the desktop source sets change: it builds the Swift library and the app, with no packaging or signing. Push runs then
catch a broken Swift build before a release does. R328 FR-R328-1 already covers the Kotlin side on Linux for every
push.

## Out of scope

The Mac App Store · notarisation and any Apple account (owner) · Homebrew casks · an auto-updater (R328 FR-R328-8 is
the update line) · universal (Intel) builds.

## Acceptance

1. `workflow_dispatch` on an existing tag produces `ravilo-mac-<N>.dmg` as an artifact; it opens on the MacBook, signs
   in and plays (R329) and casts (R330).
2. The job summary says *ad-hoc signed — not notarised*, and on a Mac that has never seen the app it opens after one
   *Open Anyway* — on macOS 14 and on 15.
3. After R328–R330 are built, the next GitHub release carries `ravilo-<N>-release.apk`, `ravilo-tizen-<N>.wgt` and
   `ravilo-mac-<N>.dmg`.
4. Deleting the Swift library from the bundle makes FR-R331-4's self-test fail the job.

## Open questions

1. ~~Apple Developer ID~~ **Answered 2026-09-29: no.** Every household Mac opens the app once via *Open Anyway* per
   install (dev review 1).
2. On 2026-09-24 GitHub stopped this repository's jobs over billing. macOS minutes are free on a public repository,
   but a billing block stops them too.
3. The `.dmg` size: Compose's `jlink` runtime plus the app should land somewhere around 100 MB; measure and record.

## Dev review (2026-09-29, against `main` `4c67e49f`)

The owner's decision reshapes FR-R331-3; the rest is the Tizen workflow with `jpackage` instead of `tz`. Ten items.

1. **No Apple bills ⇒ ad-hoc signing, one path.** FR-R331-3 loses the notarised branch, the five secrets, the
   temporary keychain and the "some but not all" failure; `spctl --assess` (FR-R331-4) goes too — an ad-hoc app never
   passes it. What the household sees: macOS 14 and 15 refuse an ad-hoc app downloaded from the internet
   (*"Ravilo" cannot be opened because Apple cannot check it*); the way in is **System Settings → Privacy & Security →
   Open Anyway**, once per install — on Sequoia the old right-click → *Open* shortcut is gone. The FR-R331-6 paragraph
   is therefore always the second version, and R328's update line (FR-R328-8) should repeat it under *Download*, so the
   step is read at the moment it is needed. Terminal users may `xattr -dr com.apple.quarantine /Applications/Ravilo.app`
   instead; the notes may say so in one line.
2. **Sign the whole bundle ourselves, then make the `.dmg` ourselves.** On Apple Silicon every binary must carry at
   least an ad-hoc signature to run, and Compose's `packageDmg` signs only what jpackage knows about — not the Swift
   library shipped through `appResourcesRootDir`. The dependable sequence: `:ravilo-desktop:createDistributable` →
   `codesign --force --deep --sign - Ravilo.app` → `hdiutil create -volname Ravilo -srcfolder … -format UDZO
   ravilo-mac-<N>.dmg`. This also frees the job from Compose's output name (`Ravilo-<version>.dmg` under
   `build/compose/binaries/main/dmg/`). Hardened runtime and entitlements are notarisation's concern; without it,
   leave them off.
3. **`macos-15` is arm64 and free here** — GitHub-hosted macOS runners cost nothing on a public repository (the 10×
   minute multiplier applies to private ones). The 2026-09-24 billing block stopped every runner, macOS included; the
   job fails cleanly then, as the others do.
4. **Version rules of `jpackage`:** `packageVersion` must be `N.N.N` with the major ≥ 1, so `MAJOR.MINOR.0` from the
   tag (D1's Tizen shape). It becomes `CFBundleShortVersionString`; the app's own R252 string stays `BuildInfo.version`
   (the tag), so the update line (R328 FR-R328-8) compares like with like.
5. **`--self-test` (FR-R331-4) runs the inner launcher, not `open`:** `Ravilo.app/Contents/MacOS/Ravilo --self-test`
   returns the app's exit code; `open -W --args` does not. Its one line of output goes to the job summary.
6. **FR-R331-7's path filter cannot be a `paths:` key** — the job lives in a reusable workflow. Use a `git diff
   --name-only` step (or `dorny/paths-filter`) that sets an output, and `if:` the build on it.
7. **`gh release upload --clobber` needs `contents: write`** — `publish.yml` already grants it for the Tizen job.
8. **The `macos:` job's guard (D1)** is the commit that flips R328–R330 to ✓ Built; until then `deploy-macos.yml` has
   only `workflow_dispatch` and uploads to the run's artifacts. Written that way, the workflow can be exercised against
   an existing tag (v1.44) as soon as it exists, without touching a release.
9. **`LSMinimumSystemVersion`:** Compose's `nativeDistributions.macOS { minimumSystemVersion = "14.0" }` — *verify the
   property name against Compose 1.9.3's DSL when building.* Info.plist extras (R330's Local Network keys) go through
   `infoPlist { extraKeysRawXml = … }`.
10. **Size, to record:** a `jlink`ed JDK 21 runtime (~45 MB) + Compose/Skiko (~30 MB) + the app — expect a `.dmg`
    around 80–100 MB compressed. **Wire:** none.
