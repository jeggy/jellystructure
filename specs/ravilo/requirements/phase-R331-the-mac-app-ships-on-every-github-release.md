# Phase R331 — The Mac app ships as a `.dmg` on every GitHub release

> Owner, 2026-09-29: *"So when fully implemented, the dmg file will be attached to the github release, just like
> android and Tizen."*

## Scope

Written 2026-09-29 (dev-authored). **Dev-reviewed 2026-09-29** against `main` `4c67e49f` (§Dev review) — build from
it; **owner, 2026-09-29: no Apple bills, and (same day) one signing identity of our own, kept in CI's secrets, so the
household approves the app once, not once per update.** Number verified free. **Last of four**
(R328 → R329 → R330 → **R331**). The workflow was signed, self-tested and installed on a real Mac on 2026-09-29, the
day it was written.

Touches CI and nothing in the app: `.github/workflows/deploy-macos.yml` (new that day), `ci.yml`'s `macos` job and
`publish.yml`'s `mac` job. **Supersedes** nothing; it is the release half of R328's app.

This block named `publish.yml`'s `macos:` job as *waiting for R328–R330* until 2026-10-13. That was D1, and **the
owner moved it on 2026-09-30** (§D1 moved) — the job has attached the `.dmg` to every release since v1.49. The
sentence outlived the decision by two weeks and was read as current by work planned off it, which is why it is gone
rather than corrected in place: the status of this phase is [`STATUS.md`](../../../STATUS.md)'s R331 row and nothing
here.

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

## Triage (2026-10-09, against `main` `12bffb29`)

- **Code: nothing left.** **Owed:** the next published release (the owner's to cut) to show the `.dmg` attached and
  acceptance 2 (no prompt on the next release).

## Walked (2026-10-12) — acceptance 1 (CI half), 3, 4 and 5; the Mac half named

D1 moved on 2026-09-30 with *"the first one after this commit is the test."* Three releases have since passed it,
and the evidence is on GitHub, not in prose:

| Release | `publish.yml` run | `mac / build` | `mac / ci` | `ci / macos` | asset |
|---|---|---|---|---|---|
| v1.49 (2026-10-02) | `37060019108` | success | skipped | success | `ravilo-mac-1.49.dmg` · 91 385 641 B |
| v1.50 (2026-10-05) | `37338091341` | success | skipped | success | `ravilo-mac-1.50.dmg` · 91 569 678 B |
| v1.51 (2026-10-10) | `38040744575` | success (3 m 24 s) | skipped | success | `ravilo-mac-1.51.dmg` · 92 658 145 B |

**Acceptance 3 ✓.** Each of those three releases carries `ravilo-android-<N>.apk`, `ravilo-tizen-<N>.wgt` and
`ravilo-mac-<N>.dmg` (plus R334's Flatpak), and each `.dmg` was built and attached by `publish.yml`'s `mac` job on the
published release — not by hand. v1.47 and v1.48 were the hand-uploaded pair; the `workflow_dispatch` runs stop at
2026-09-30, one minute after v1.48's release.

**Acceptance 1, the CI half ✓** — `workflow_dispatch` on an existing tag, run `38294271606`, 2026-10-12, 4 m 02 s,
`version=1.51` with `skip_ci=true`, checked out at v1.51 (`d37f1a7a`). All thirteen steps green:

- signing certificate `CN = Ravilo, O = jellystructure`, SHA-1 `5B3EAC32D6564F942C33101D4D07FDB31E0D9274`;
- `libravilo-mac.dylib` found at `Contents/app/resources/`;
- `codesign --verify --deep --strict`: *valid on disk*, *satisfies its Designated Requirement*;
  `Identifier=dev.jellystructure.ravilo`, `Authority=Ravilo`, `TeamIdentifier=not set`;
- the self-test (FR-R331-4) printed `Ravilo 1.51 · library ABI 4 · 14 Dock pictures · data …/Application Support/Ravilo
  writable · OK` — *library ABI 4* is the Swift library loading through JNI, so the self-test does prove the `.dylib`,
  not just its presence;
- `ravilo-mac-1.51.dmg` made, signed, uploaded as the run artifact `ravilo-mac-dmg`;
- **Attach to the GitHub Release: skipped**, as FR-R331-5 requires of a dispatch. No release was touched.

The device half of acceptance 1 (opens, signs in, plays, casts) is owed from the Mac.

**Open question 3 answered: 92 246 731 B — 88.0 MiB.** Up from 86 MB on 2026-09-29, growing roughly 0.5 MB a release,
comfortably inside the ~100 MB the question expected. Note the `.dmg` is *not* byte-identical between the release build
and a dispatch build of the same tag (92 658 145 B vs 92 246 731 B): UDZO compression and jpackage's embedded
timestamps are not reproducible. Size is a sanity check, never an identity check.

**Acceptance 5 ✓ as guard logic, deliberately not on a runner.** The *The signing secrets are there* step is pure
bash, so it was run verbatim off the workflow with the environment emptied: both secrets absent → one error naming
**both** (`MACOS_SIGNING_P12_BASE64 MACOS_SIGNING_P12_PASSWORD`), exit 1, before anything slow; either one absent →
the error names exactly that one, exit 1; both present → exit 0 and the keychain path exported. What is *not* done, on
purpose, is deleting the two repository secrets to see it on `macos-15`: the `.p12` and its password are the one
certificate the household's Gatekeeper and Local Network approvals hang off, and pulling them to prove a `[ -n … ]`
test is a bad trade. That is the owner's to do if they ever want it seen on a runner.

**Acceptance 4 — the job fails, but at a different guard than the acceptance names.** Removing the Swift library makes
the *Build the Swift library and the app* step fail first — `::error::libravilo-mac.dylib is not in the bundle (R328
FR-R328-4)`, exit 1 — so FR-R331-4's self-test is never reached. Proven with the same two lines run off the workflow
against a bundle with and without the file. The acceptance is met in substance (no library ⇒ no `.dmg`, no upload) and
its wording is wrong about which check catches it. Reaching the self-test with the library gone would need the file
deleted *between* the two steps, which only exists as a deliberately broken branch; not worth a push to a public
repository. The self-test's own ability to catch it is instead shown positively by *library ABI 4* above.

**Acceptance 2 does not need v1.52.** It reads *two consecutive releases*, and two already exist: install
`ravilo-mac-1.50.dmg` on a Mac that has never seen the app (one *Open Anyway*), then `ravilo-mac-1.51.dmg` must open
with no prompt at all, keep the sign-in (R328 D5's own 0600 file, per the 2026-09-30 correction) and keep the Local
Network permission. Both are signed by the same certificate, so the designated requirement is identical and the
promise is testable today. Owed from the Mac; it is **not** gated on the owner cutting a release.

## FR-R331-7 amended (2026-10-12): `continue-on-error` narrowed, not removed

The build note made `ci.yml`'s `macos` job `continue-on-error` *"until the Mac app first ships: the Swift library has
never been compiled, and `ci.yml` gates every release."* The first half is spent — the app has shipped on v1.48–v1.51
and `ci / macos` has been **green on every run visible today**, pushes and releases alike. The second half still
stands, and is the reason this is a narrowing rather than a deletion: `ci.yml` gates `publish.yml`'s push pipeline too,
and that pipeline publishes the backend image prod runs on. A Compose or Swift break on a Mac must not hold back a
backend image.

So: `continue-on-error: ${{ github.event_name == 'push' }}`.

- **pull request → blocking.** The cheapest place to see a Mac break.
- **published release → blocking.** Otherwise `publish.yml` attaches every other platform's file and leaves the
  `.dmg` out, which is exactly the silent loss acceptance 3 is about.
- **push to main → advisory, as before.** The backend image keeps flowing; the break is reported.

The path filter is unchanged, so on anything that touches no Mac-shaped file the job still short-circuits in seconds
and the blocking/advisory distinction never arises.

**Approved as written (Tróndur, 2026-10-13, DEB-890)**, with the third option — a blocking push path — refused for a
reason worth keeping: *a macOS runner flake must not be able to hold the backend image back for a client job that no
release depends on*. The two gates that decide anything are already blocking, the pull request that lands a change
and the release that ships it, so making the push path blocking buys no coverage and costs availability on the one
pipeline prod depends on. This was written and pushed as a dev-authored narrowing on 2026-10-12 and flagged for the
decision rather than assumed; the decision is now on the record and the line does not need revisiting.

## Amendment (2026-10-12): acceptance 2 is not provable on the MacBook as it stands, and the install path has a trap

From the Mac walk (DEB-721) and DEB-756.

**Acceptance 2's prompt half cannot be observed on that Mac.** `spctl --status` reads **`assessments disabled`**, so
Gatekeeper refuses nothing there: neither the first *Open Anyway* nor its absence on the second release is a visible
event, and a pass on that Mac would prove nothing about any other. Acceptance 2 is therefore split:

- **2a — the mechanism, proved structurally and ✓ as of 2026-10-12.** `ravilo-mac-1.50.dmg` and `ravilo-mac-1.51.dmg`
  carry an identical designated requirement (same bundle identifier, same certificate leaf — `CN = Ravilo`, SHA-1
  `5B3EAC32D6564F942C33101D4D07FDB31E0D9274`), which is exactly what a Gatekeeper rule written by *Open Anyway* matches
  on. This is what FR-R331-3 claims and it holds.
- **2b — the observation, owed; the Gatekeeper grant is now given.** Seeing the one prompt and then no prompt needs
  `sudo spctl --master-enable` on that Mac and a re-walk of the v1.50 → v1.51 pair. **Owner, 2026-10-12 (asked on
  DEB-756): granted for one walk — enable assessments, walk, then `sudo spctl --master-disable` to put the Mac back.**
  The restore is part of the test and is not optional: this Mac's normal state is `assessments disabled`
  (`reference-macbook-gatekeeper-off-and-translocation`), and leaving it enabled changes how every other app on it
  behaves. Until the walk happens acceptance 2 is **not** claimed, and no later note may read it as met.

The sign-in and Local Network halves of acceptance 2 are unaffected by `spctl` and stay as written.

**And the install path the acceptance assumes is not the one everyone takes.** Acceptance 2 says *"install
`ravilo-mac-1.50.dmg` on a Mac"*, and the walk found that **how** it is installed decides whether the app works at
all: a quarantined `Ravilo.app` that Finder did not move is App-Translocated, runs from
`/private/var/folders/…/AppTranslocation/<uuid>/d/Ravilo.app`, and reaches nothing on a LAN-resolved server because
the household's Local Network consent is bound to the copy in `/Applications`. So:

- Acceptance 2 (and acceptance 1's device half) means **dragged to `/Applications` with Finder**, run from there.
  Anything else is testing a different app, in TCC's eyes and in Gatekeeper's.
- `xattr -dr com.apple.quarantine /Applications/Ravilo.app`, offered in FR-R331-6 as a shortcut past *Open Anyway*, is
  also what prevents translocation. That is worth knowing and is recorded here.
- **FR-R331-6 stays withdrawn** (owner, 2026-09-30): the release notes gain nothing, here or ever, unless the owner
  moves it. The place the viewer is told to drag the app to Applications is **in the app**, under the update line that
  already carries the *Open Anyway* sentence — written as **R385 FR-R385-6**, with the rest of what a translocated Mac
  should say in **R385**.

## Amendment (2026-10-13): what the Mac walk did prove, and the three sentences left in acceptance 2

The 2026-10-12 amendment above split acceptance 2 and recorded why its prompt half is unobservable on that Mac. What
it did not record is that **the rest of acceptance 2 was walked on hardware the same day and passed**, so a later
re-walk owes the prompt and not the pair. From DEB-721 (walked on the MacBook, macOS 27.0.1 `26A434`, arm64,
2026-10-12 20:30Z); the `.dmg` files were the published v1.50 and v1.51 assets, verified byte-identical to the copies
the owner's browser had already downloaded.

**Acceptance 2's sign-in half ✓ on hardware.** Three installs over the top (1.51 → 1.50 → 1.51) left the app's own
token file **never rewritten** — `-rw-------`, dated 2026-10-06, which is the sign-in the *first* of those installs
made. The account screen read signed in to the household server throughout. This is R328 D5's 2026-09-30 amendment
working as intended, and it is the half that the Keychain would have broken by asking for the login password.

**Acceptance 2's Local Network half ✓ on hardware.** After the v1.51 install the app's discovery listed all five of
the household's LAN cast targets, which cannot happen without the consent grant — so nothing was asked again.

**And that same result settles a question the 2026-10-12 amendment leaves open.** That walk installed with
`hdiutil attach` + `cp -R`, not a Finder drag, after setting a browser-shaped `com.apple.quarantine` flag by hand to
match how a download arrives. A quarantined copy Finder did not move is the translocation case, and a translocated
copy **cannot** see a LAN target — so the discovery pass is positive evidence that this copy ran **in place**, from
`/Applications`. The two results above therefore hold for the in-place path acceptance 2 means, and are not an
artefact of the install method.

**So acceptance 2b, restated — one observation, not a re-walk.** With `sudo spctl --master-enable` (the owner's
one-walk grant, still unspent as of 2026-10-13): drag v1.50's `Ravilo.app` to `/Applications` **with Finder**, open
it, expect exactly one *Open Anyway*; drag v1.51's over it and expect **no prompt at all**. Both `.dmg`s are already on
that Mac carrying a real browser quarantine flag, so there is nothing to download. The evidence is `ps -o lstart`
process start times — blocked for the first, immediate for the second — because a system dialog cannot be
screenshotted. Then `sudo spctl --master-disable`. The sign-in and Local-Network halves need not be redone.

**Acceptance 1's device half is now one step: a film plays.** Everything else it names is covered — the `.dmg` opens,
the app runs, it is signed in, it reaches the server, and it sees its cast targets (that last is R330's acceptance,
not this one's, but the discovery above is the part acceptance 1 asks for). The play needs the library and the backend
as well as the Mac, which is why it is the one piece that could not ride a Mac-only claim.

**Two things observed and deliberately not filed here.** ⌘W terminated the whole app from a music screen with nothing
playing — R330's known ⌘W-quits behaviour showing up outside a cast, which is already the owner's call, and not an
R331 defect. And the app's own version line read **`1.51`**, the tag, while jpackage's bundle version reads `1.51.0`;
both are correct and the pair is `deploy-macos.yml`'s two version properties doing what §Walked's wiring note says
they do.

## Amendment (2026-10-13): 2b can fail, and what it would mean — plus the order it has to be walked in

Everything above treats 2b as a formality waiting for a Gatekeeper switch. It is not. It is the **only** test of
FR-R331-3's actual claim, and it has two legitimate outcomes.

**What is still assumed.** FR-R331-3 says *Open Anyway* is once per Mac, not once per update. 2a proved the two
releases carry an identical designated requirement — but that only decides the question **if** a user approval is
keyed to the signing identity. If macOS instead keys it to the individual bundle (its cdhash, or the quarantine
attribute on that one copy), then a newly downloaded v1.51 is a new subject, it prompts too, and FR-R331-3 is simply
wrong. **Nobody has observed either outcome.** The household Mac has `assessments disabled`, so its silent v1.51
launch on 2026-10-11 is not evidence either way, and the `XProtect suppress first launch warning: true` line in that
log is the quarantine resolver talking, not an approval being reused. So 2b is a real experiment, and the walker
should record which of these happened rather than looking for the expected one:

- **No prompt on v1.51** — FR-R331-3 holds as written, and the one-certificate decision bought what it promised.
- **A second prompt on v1.51** — FR-R331-3's claim is false, and so is the sentence in §Scope about the household
  approving the app once rather than once per update. **The fix is then the claim, not the certificate.** The owner's
  2026-09-29 *no Apple bills* decision is not reopened by this: notarisation is the thing that would remove the
  prompt, it costs a paid Apple account, and it is out of scope (§Out of scope). What changes instead is this spec,
  and the in-app copy that leans on it — R385's FR-R385-6 update-line sentence tells the viewer about the drag and
  the *Open Anyway*, so it is the surface that would need to stop implying once-ever. Write it down and hand it to the
  owner as information; do not treat a failed 2b as a reason to buy anything.

**And the two pieces of advice already in this file conflict, so the order matters.** The 2026-10-12 amendment notes
that `xattr -dr com.apple.quarantine` is what prevents App Translocation — useful for acceptance 1's device half. But
a copy with no quarantine flag is a copy Gatekeeper never assesses, so clearing it **voids 2b**. The sequence that
satisfies both:

1. **2b first**, with the quarantine flag intact on both `.dmg`s, each app **dragged to `/Applications` with
   Finder** — the Finder drag is what avoids translocation *without* clearing the flag, which is why 2b cannot be
   walked with `cp -R`.
2. Only afterwards, if a later step needs translocation gone, clear the flag.

Evidence for both launches is `ps -o lstart` process start times, because a system dialog cannot be screenshotted, and
a note of **how** the first approval was given — System Settings ▸ Privacy & Security ▸ *Open Anyway* and nothing
else. An approval given any other way (`spctl --add`, clearing the flag) tests a different mechanism and does not
count.

## Amendment (2026-10-15): the workflow also ships Sparkle and an appcast (R386)

Owner, 2026-10-15 (DEB-1230): the Mac app updates itself with Sparkle 2, from this phase's release assets. The
out-of-scope entry *an auto-updater (R328 FR-R328-8 is the update line)* no longer holds. **R386** owns the behaviour;
what it adds to this phase's workflow:

- **`Sparkle.framework` in the bundle.** Its nested helpers are signed with the Ravilo identity, inside-out, before the
  bundle's own signature (R386 FR-R386-1). FR-R331-2's `codesign --verify --deep --strict` must still pass, and
  FR-R331-4's self-test is unchanged.
- **One more repository secret, `SPARKLE_ED_PRIVATE_KEY`.** It is set with `gh secret set` like the two signing
  secrets, and backed up wherever the certificate is. The `.dmg` is EdDSA-signed with it, and a one-item
  `appcast.xml` is uploaded beside `ravilo-mac-<N>.dmg` on every release (R386 FR-R386-2).
- **Acceptance 5 extends to the new secret:** with it removed, the job fails naming it and uploads nothing.

**What this does to acceptance 2b.** An update Sparkle installs never carries a browser quarantine flag. If R386's
spike confirms that, *Open Anyway* stays a once-per-Mac question for the **first** download, and every later version
arrives through Sparkle. 2b is unchanged as the test of a hand-downloaded second version, and it is still unwalked.

## Amendment (2026-10-16): 2b walked, and FR-R331-3's once-per-Mac claim is false

Walked on 2026-10-16 (DEB-763; Høgni on the household's personal Mac, Gatekeeper on, official v1.50, v1.51 and v1.52
`.dmg`s downloaded with a browser and dragged with Finder; consolidated by Turið, 08:56Z). **Every hand-downloaded
version is refused twice:** first the disk image, then the installed app. Each refusal needs System Settings ▸ Privacy
& Security ▸ *Open Anyway* plus the user's credentials. v1.51 over an approved v1.50 prompted exactly as v1.50 did, and
v1.52 did the same again.

So the first outcome this file allowed for did not happen. An approval is **not** reused across versions signed with
the same certificate. §Scope's *"approves the app once, not once per update"*, D2's *"applies once per Mac"* and
FR-R331-3's reasoning about the designated requirement no longer describe what a user sees, and should not be quoted
as fact again. **As the 2b note already said: the fix is the claim, not the certificate.** The certificate stays, and
the 2026-09-29 decision to have no Apple account is the owner's to keep or reopen. Two things are unaffected:

- **Whether the same certificate keeps the Keychain sign-in and the Local Network consent across updates**
  (FR-R331-3's other reasons) was not tested by 2b.
- **Updates installed by Sparkle** (R386) carry no browser quarantine flag, so they should not be assessed at all.
  That is still unproven: DEB-1292 is open, and its last sitting saw the appcast fetched but no `.dmg` downloaded.

What a user sees today: **two** *Open Anyway* approvals on the first install, and two more on every version they
download by hand. The public download page's line *"The first time: … Open Anyway"* and R385's update-line sentence
both understate this. The owner was asked again on DEB-1076, with this fact, whether to notarise or to explain the
step in pictures.
