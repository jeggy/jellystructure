# Phase R331 — The Mac app ships as a `.dmg` on every GitHub release

> Owner, 2026-09-29: *"So when fully implemented, the dmg file will be attached to the github release, just like
> android and Tizen."*

## Status

`⚠ Partial` — **the workflow runs: signed, self-tested and installed on a real Mac 2026-09-29; `publish.yml`'s `macos:` job waits for R328–R330** (§Build notes). Written 2026-09-29 (dev-authored). **Dev-reviewed 2026-09-29** against `main` `4c67e49f` (§Dev review) —
build from it; **owner, 2026-09-29: no Apple bills, and (same day) one signing identity of our own, kept in CI's secrets, so
the household approves the app once, not once per update.** Number verified free. **Last of four**
(R328 → R329 → R330 → **R331**).

**Mirrors** R264's `deploy-tizen-tv.yml`, which signs and attaches `ravilo-tizen-<N>.wgt`, and the Android release
build that attaches `ravilo-android-<N>.apk` (named `ravilo-<N>-release.apk` until 2026-09-30). v1.44 carries both. **Builds on** phase 231: a release publishes only if
its own CI is green.

## Decisions (leans)

| # | Question | Lean |
|---|---|---|
| D1 | When the release starts carrying it | **Only once R328–R330 are ✓ Built** (the owner's "when fully implemented"). Until then the workflow runs by hand only and keeps its `.dmg` as a workflow artifact |
| D2 | Signing | ~~Developer ID + notarisation~~ ~~ad-hoc~~ **Our own self-signed code-signing certificate, created once, kept as two repository secrets, the same on every release** (owner, 2026-09-29: no Apple bills; *"supply it to the ci workflow via secrets, so … the users only have to approve the application once and not once per update"*). No notarisation, one path; the notes carry the *Open Anyway* line, which applies once per Mac |
| D3 | Runner | GitHub's **`macos-15`** (Apple Silicon) — free on a public repository |
| D4 | File name | **`ravilo-mac-<N>.dmg`**, beside `ravilo-android-<N>.apk` and `ravilo-tizen-<N>.wgt` |

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
5. **Sign the whole bundle with our identity:** `codesign --force --deep --sign "Ravilo" Ravilo.app` from the
   temporary keychain of FR-R331-3 (dev review 2 — on Apple Silicon every binary must carry a signature, and jpackage
   does not sign the Swift library; `--deep` reaches it).
6. Make the image ourselves: `hdiutil create -volname Ravilo -srcfolder … -format UDZO ravilo-mac-<N>.dmg`.

The bundle carries:

- `LSMinimumSystemVersion` 14.0 and arm64 only (R328 D2);
- R330's Local Network keys;
- R328's icon;
- no hardened runtime and no entitlements — those exist for notarisation, which this app never has.

**FR-R331-3 — Signing: one identity of our own, forever.**

*Created once, by hand, on the MacBook* — a self-signed code-signing certificate with a long life, so it never has to
be replaced (a replacement would cost every Mac one more *Open Anyway*):

```
openssl req -x509 -newkey rsa:3072 -sha256 -days 7300 -nodes -keyout ravilo-signing.key -out ravilo-signing.crt \
  -subj "/CN=Ravilo/O=jellystructure" -addext "keyUsage=digitalSignature" -addext "extendedKeyUsage=codeSigning"
openssl pkcs12 -export -inkey ravilo-signing.key -in ravilo-signing.crt -name "Ravilo" -out ravilo-signing.p12
```

The `.p12` and its password go into the household's password manager, and into two repository secrets —
`MACOS_SIGNING_P12_BASE64` (`base64 -w0 ravilo-signing.p12`) and `MACOS_SIGNING_P12_PASSWORD` — the Tizen workflow's
pattern. Neither ever reaches a log line.

*On every run:* `security create-keychain` with a random password → `security import` the `.p12` (`-T /usr/bin/codesign`)
→ `security set-key-partition-list -S apple-tool:,apple: -s` so `codesign` can use the key without a prompt →
`codesign … --sign "Ravilo"` → delete the keychain. **Both secrets missing:** the job fails with one line naming them —
never a silent ad-hoc build, because an ad-hoc signature would undo the one-approval promise (dev review 1). The job
summary says **signed with the Ravilo certificate — not notarised**.

*What the identity buys (dev review 1):* macOS records a Gatekeeper approval, a Keychain item's access and a privacy
permission by the app's **designated requirement** — its bundle id and the certificate that signed it — so a Mac that
opened one version via *Open Anyway* opens every later one, keeps its sign-in tokens (R328 D5) and keeps its Local
Network permission (R330) without asking again.

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
> Settings → Privacy & Security, scroll to the Ravilo line and choose **Open Anyway** — once per Mac; later versions
> open normally. (From a terminal, `xattr -dr com.apple.quarantine /Applications/Ravilo.app` does the same.)*

R328's update line (FR-R328-8) repeats the *Open Anyway* sentence under *Download*.

**FR-R331-7 — A Mac build between releases.** `ci.yml` gains a `macos` job that runs only when `ravilo-desktop/**` or
the desktop source sets change: it builds the Swift library and the app, with no packaging or signing. Push runs then
catch a broken Swift build before a release does. R328 FR-R328-1 already covers the Kotlin side on Linux for every
push.

## Out of scope

The Mac App Store · notarisation and any Apple account (owner) · a certificate rotation plan (the certificate is made
to outlive the project; if it is ever lost, every Mac approves once more, and that is all) · Homebrew casks · an auto-updater (R328 FR-R328-8 is
the update line) · universal (Intel) builds.

## Acceptance

1. `workflow_dispatch` on an existing tag produces `ravilo-mac-<N>.dmg` as an artifact; it opens on the MacBook, signs
   in and plays (R329) and casts (R330).
2. The job summary says *signed with the Ravilo certificate — not notarised*; on a Mac that has never seen the app it
   opens after one *Open Anyway* — on macOS 14 and on 15 — and **the next release opens with no prompt at all**, keeps
   the sign-in and keeps the Local Network permission (the one-approval promise, checked on two consecutive releases).
3. After R328–R330 are built, the next GitHub release carries `ravilo-android-<N>.apk`, `ravilo-tizen-<N>.wgt` and
   `ravilo-mac-<N>.dmg`.
4. Deleting the Swift library from the bundle makes FR-R331-4's self-test fail the job.
5. With the two signing secrets removed, the job fails naming them; nothing is uploaded.

## Open questions

1. ~~Apple Developer ID~~ **Answered 2026-09-29: no — and, the same day, our own certificate in CI's secrets instead**,
   so *Open Anyway* is once per Mac, not per update (FR-R331-3, dev review 1).
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
   **Same day, the owner's follow-up — a signing identity of our own in CI's secrets — makes that approval once per Mac
   rather than once per update, and it is right.** *Open Anyway* writes a Gatekeeper rule for the app's *designated
   requirement*; `codesign` generates that requirement from the bundle identifier and the signing certificate's leaf,
   so every later build signed with the same certificate under the same identifier matches the rule. An ad-hoc
   signature has no certificate, its requirement is the build's own hash, and that is exactly why every update would
   have asked again. The same identity is what a Keychain item's access list and a TCC permission are bound to, so
   R328 gets the Keychain back and R330's Local Network consent survives updates. The one thing this does not buy is
   the very first approval on a Mac: without notarisation, Gatekeeper still refuses a fresh download once. **Verify on
   the first two releases** (acceptance 2) — Gatekeeper's bookkeeping is Apple's to change. Practicalities: make the
   certificate long-lived (`-days 7300`); `security import` on macOS 14 reads a modern PKCS#12 (fall back to
   `openssl pkcs12 -legacy` only if it refuses, the Tizen lesson); `--timestamp` is not needed for a self-signed
   identity.
2. **Sign the whole bundle ourselves, then make the `.dmg` ourselves.** On Apple Silicon every binary must carry a
   signature to run, and Compose's `packageDmg` signs only what jpackage knows about — not the Swift library shipped
   through `appResourcesRootDir`. The dependable sequence: `:ravilo-desktop:createDistributable` →
   `codesign --force --deep --sign "Ravilo" Ravilo.app` (the identity from FR-R331-3's temporary keychain) →
   `hdiutil create -volname Ravilo -srcfolder … -format UDZO ravilo-mac-<N>.dmg`. This also frees the job from Compose's output name (`Ravilo-<version>.dmg` under
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

## Build notes (2026-09-29)

Built from the dev review; **never run** — the certificate does not exist yet, and a `workflow_dispatch` needs the
workflow on `main`. `⚠ Partial` until acceptance 1–5 are seen on GitHub and on the MacBook.

1. **FR-R331-1 — `deploy-macos.yml`**: `workflow_call` and `workflow_dispatch` with `version` and `skip_ci`, the Tizen
   workflow's plain `MAJOR.MINOR` validation (and jpackage's major ≥ 1), `packageVersion` `MAJOR.MINOR.0` passed as
   `-Pravilo.macPackageVersion`, the app's own string the version (`-Pjellystructure.version`); jobs `version` →
   `ci` → `build`. **One addition:** a `ref` input for a hand run, because the only tag today (`v1.44`) predates the Mac
   app — `ref: main` builds a `.dmg` before any release carries one, `version` then only labels it. Inputs reach the
   shell through `env`, never by interpolation.
2. **FR-R331-3 first, before anything slow:** both secrets missing is one error naming both, in seconds; never an
   ad-hoc build. Then a temporary keychain with a random password, `security import … -T /usr/bin/codesign` (re-wrapped
   once with 3DES/SHA-1 only if macOS refuses the `.p12`), `set-key-partition-list`, and the keychain deleted in an
   `always()` step. **The identity is named by the certificate's SHA-1**, not by the string "Ravilo", so nothing else in
   the runner's keychains can match.
3. **FR-R331-2 — build and sign** on `macos-15`: `buildMacNative` + `createDistributable`, a check that
   `libravilo-mac.dylib` is in the bundle, then every `.dylib`/`.jnilib`/`.so` signed, jpackage's runtime signed as its
   own bundle, and the app signed `--deep` — no hardened runtime, no entitlements, no timestamp. The `.dmg` is made with
   `hdiutil` (the app plus an *Applications* link to drag onto) and is itself signed.
4. **FR-R331-4:** `codesign --verify --deep --strict`, then the bundle's own launcher `--self-test`, whose line goes to
   the job summary with the size and *Signed with the Ravilo certificate — not notarised*.
5. **FR-R331-5/-6:** the `.dmg` is always the run's artifact; it is attached to a release only when the caller's event
   is a published release — and **`publish.yml` has no `macos:` job yet** (D1: added by the commit that marks R328–R330
   ✓ Built). When attached, the fixed *Ravilo for Mac* paragraph is appended to the release notes once, since the notes
   are written by hand.
6. **FR-R331-7:** `ci.yml` gains a `macos` job — built only when something Mac-shaped changed (a `git diff` against the
   PR base or the pushed-over commit, since a reusable workflow cannot filter on `paths:`), then the self-test. It is
   `continue-on-error` until the Mac app first ships: the Swift library has never been compiled, and `ci.yml` gates every
   release. The Android job also runs `:ravilo-castv2:jvmTest`.
7. **The certificate recipe** is in `ravilo-desktop/README.md`: a config file instead of `-addext`, run with macOS's own
   `/usr/bin/openssl` (LibreSSL), whose `.p12` every macOS `security import` reads; the secrets set with `gh secret set`.
   The recipe was checked here with a throwaway password (subject *Ravilo, jellystructure*, 20 years, Code Signing,
   critical) and the files deleted.
8. **Owed:** the first run (acceptance 1), *Open Anyway* once and then **no prompt on the next release** (acceptance 2,
   two consecutive releases), the self-test failing without the library (acceptance 4), the missing-secrets failure
   (acceptance 5), and the `.dmg`'s size (open question 3).
9. **First runs (2026-09-29).** The workflow signs with the owner's certificate (Authority *Ravilo*, identifier
   `dev.jellystructure.ravilo`); `codesign --verify --deep --strict` passes on the Mac, and `--self-test` passes on the
   runner and on the Mac (macOS 27.0.1). The `.dmg` is 86 MB. At the owner's request a release carries the `.dmg`
   before D1's condition is met, uploaded by hand from a `workflow_dispatch` run: v1.45 (whose build could not start a
   film — R329 build note 13), then v1.47. v1.46's pipeline had started before the fix, so it carries none.
   `publish.yml` still has no `macos:` job. On macOS 27 `hdiutil attach` warns that it is deprecated in favour of
   `diskutil image attach`; the runner is macOS 15, so nothing changes yet. Still owed: acceptance 2 (no prompt on
   the next release), 4 and 5.

## Correction (2026-09-30): what the one certificate does and does not keep

FR-R331-3's reason stands for **Gatekeeper** (one *Open Anyway* per Mac) and for **Local Network** access, which
follow the app's designated requirement. It was wrong about the **Keychain**: without a team ID an item is bound to the
build's code-directory hash, so each release asked for the login password again. R328's D5 is amended accordingly —
the tokens are in the app's own file. Acceptance 2 ("a Mac that approved one release opens the next without asking")
is now reachable; it was not before.

## D1 moved (owner, 2026-09-30)

*"So next time dmg will be built and attached as part of the gh ci release workflow."* `publish.yml` has a `mac` job
that calls `deploy-macos.yml` on every published release, after CI, with `skip_ci` — the same shape as the Play,
Samsung and Linux jobs. v1.47 and v1.48 had the `.dmg` built by hand and attached from the run's artifact. The
Linux job now runs after it (pass or fail), because both append a paragraph to the release notes by reading the
body and writing it back. Not yet seen on a release: the first one after this commit is the test.

## FR-R331-6 withdrawn (owner, 2026-09-30)

The release notes carry no install paragraph: *"People downloading dmg already know how to install a dmg, and the
same with flatpak, wgt etc."* The workflow attaches the file and leaves the notes alone. Release notes say
concretely what the release contains — no install steps, no account of what was or was not tested.

