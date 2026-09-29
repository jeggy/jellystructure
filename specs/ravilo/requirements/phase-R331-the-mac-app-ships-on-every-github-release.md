# Phase R331 — The Mac app ships as a `.dmg` on every GitHub release

> Owner, 2026-09-29: *"So when fully implemented, the dmg file will be attached to the github release, just like
> android and Tizen."*

## Status

`Planned` — written 2026-09-29 (dev-authored). Not dev-reviewed. Number verified free. **Last of four**
(R328 → R329 → R330 → **R331**).

**Mirrors** R264's `deploy-tizen-tv.yml`, which signs and attaches `ravilo-tizen-<N>.wgt`, and the Android release
build that attaches `ravilo-<N>-release.apk`. v1.44 carries both. **Builds on** phase 231: a release publishes only if
its own CI is green.

## Decisions (leans)

| # | Question | Lean |
|---|---|---|
| D1 | When the release starts carrying it | **Only once R328–R330 are ✓ Built** (the owner's "when fully implemented"). Until then the workflow runs by hand only and keeps its `.dmg` as a workflow artifact |
| D2 | Signing | **Developer ID + notarisation when the Apple secrets exist; ad-hoc signing otherwise** — the release is never blocked on the account, and the notes say which one it is |
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
3. Build R328's Swift library.
4. Run `:ravilo-desktop:packageDmg`. Compose's native distribution uses `jpackage` and `jlink`, and a `.dmg` can only
   be built on macOS.
5. Rename the output `ravilo-mac-<N>.dmg`.

The bundle carries:

- `LSMinimumSystemVersion` 14.0 and arm64 only (R328 D2);
- R330's Local Network keys;
- R328's icon;
- the hardened runtime with the entitlements a JVM needs (JIT and unsigned executable memory — Compose's defaults).

**FR-R331-3 — Signing, one of two ways, never half.**

- **All five secrets present** — `MACOS_DEVELOPER_ID_P12_BASE64`, `MACOS_DEVELOPER_ID_P12_PASSWORD`, `APPLE_ID`,
  `APPLE_APP_SPECIFIC_PASSWORD`, `APPLE_TEAM_ID`:
  1. import the Developer ID Application certificate into a temporary keychain;
  2. sign every nested binary and the app (Compose's signing DSL);
  3. notarise with `xcrun notarytool submit --wait`;
  4. staple the ticket to the `.dmg`;
  5. delete the keychain.
- **None of them present:** sign ad-hoc. The job summary says **not notarised**.
- **Some but not all:** the job fails. A half-configured account is a mistake to report, not a mode to ship.

Secrets never reach a log line (the Tizen workflow's rule).

**FR-R331-4 — Verify before uploading.**

- `codesign --verify --deep --strict` on the app.
- When notarised, `spctl --assess --type exec`.
- Mount the `.dmg` and run the app's **`--self-test`**. That new flag loads `libravilo-mac`, reads its own version and
  the Keychain service, prints one line and exits 0 without opening a window. It catches the failure that actually
  happens with bundled JVM apps: a missing or unsigned nested library.

**FR-R331-5 — Attach.**

- `gh release upload v<N> ravilo-mac-<N>.dmg --clobber`, from a job with `contents: write` (as `publish.yml` already
  grants for Tizen).
- `publish.yml` gains a `macos:` job — `needs: [version, ci, publish]`, `if: github.event_name == 'release'`,
  `secrets: inherit`, `skip_ci: true` — exactly like `tizen-tv:`. **This job is added by the commit that marks
  R328–R330 ✓ Built** (D1). Before that commit, `deploy-macos.yml` runs by hand only and uploads to the run's
  artifacts, never to a release.

**FR-R331-6 — The release notes gain one fixed paragraph** under the house-style prose, in two versions:

- **Notarised:** *Ravilo for Mac: download `ravilo-mac-<N>.dmg`, open it and drag Ravilo to Applications (macOS 14 or
  later, Apple Silicon).*
- **Not notarised:** the same, plus *The first time, macOS will refuse to open it: open System Settings → Privacy &
  Security and choose Open Anyway.*

**FR-R331-7 — A Mac build between releases.** `ci.yml` gains a `macos` job that runs only when `ravilo-desktop/**` or
the desktop source sets change: it builds the Swift library and the app, with no packaging or signing. Push runs then
catch a broken Swift build before a release does. R328 FR-R328-1 already covers the Kotlin side on Linux for every
push.

## Out of scope

The Mac App Store · Homebrew casks · an auto-updater (R328 FR-R328-8 is the update line) · universal (Intel) builds.

## Acceptance

1. `workflow_dispatch` on an existing tag produces `ravilo-mac-<N>.dmg` as an artifact; it opens on the MacBook, signs
   in and plays (R329) and casts (R330).
2. With no Apple secrets, the job summary says *not notarised* and the app opens after *Open Anyway*. With all five, it
   opens with no warning and `spctl` accepts it.
3. After R328–R330 are built, the next GitHub release carries `ravilo-<N>-release.apk`, `ravilo-tizen-<N>.wgt` and
   `ravilo-mac-<N>.dmg`.
4. Deleting the Swift library from the bundle makes FR-R331-4's self-test fail the job.

## Open questions

1. **Apple Developer ID (US$99/yr)** — the owner's decision. Without it every household Mac needs *Open Anyway* once
   per install. With it, the same account also covers TestFlight if an iOS app ever happens.
2. On 2026-09-24 GitHub stopped this repository's jobs over billing. macOS minutes are free on a public repository,
   but a billing block stops them too.
3. The `.dmg` size: Compose's `jlink` runtime plus the app should land somewhere around 100 MB; measure and record.
