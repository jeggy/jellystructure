# Phase R272 — CI builds and signs the Tizen .wgt on every release

> Requested 2026-09-19: *"Let's set up some github ci setup where we auto publish the tizen app to the
> samsung tizen tv app store."* Researched before building: Samsung's TV Seller Office has no public
> submission API (unlike Galaxy Store's documented Content Publish API), and publishing there is a
> manual, human-reviewed workflow — an Application ID, a web-form upload, an automatic pre-test, then a
> human reviewer's certification/verification pass that can kick a submission back with defects. No CI
> can get around that review; see jellyfin-tizen's own submission
> ([issue #222](https://github.com/jellyfin/jellyfin-tizen/issues/222), 109 comments) for what the process
> actually looks like for a near-identical client app. Given that, the owner picked the realistic scope:
> CI builds and Samsung-signs the `.wgt` and attaches it to the GitHub Release; the Seller Office upload
> itself stays a manual, few-minutes step per release.

## Status

`⚠ Partial` — built 2026-09-19, `.github/workflows/deploy-tizen-tv.yml` wired into `publish.yml` as a new
`tizen-tv` job (same `needs: [version, ci, publish]` / `if: github.event_name == 'release'` shape as the
existing `play-store` job). The exact `tz` CLI + headless-signing recipe was verified end-to-end against a
throwaway self-signed certificate in this project's own Tizen dev container and in a clean disposable
`ubuntu:22.04` container matching a GitHub Actions runner (see §4) — entirely headless, no VNC/X/XFCE
required for signing (only ever needed for the interactive Samsung-account sign-in that *created* the
certificate). **Not yet run for real**: needs the four repo secrets in §3 before a live CI run can sign
with the actual Ravilo certificate rather than fail at that step.

## 1. Scope

On every published GitHub Release (the same trigger/tag convention R215 and Phase 167 already
established), build the `ravilo-screen` Kotlin/JS bundle, package it as a Tizen `.wgt`, sign it with the
project's real Samsung-issued "Ravilo" certificate, and attach the signed file to the release as a
downloadable asset. Explicitly **not** in scope: anything past that — see §5.

## 2. Decisions

- **Build + sign + attach, not publish.** See the quote above — there's no vendor-sanctioned way to
  automate the Seller Office's human-reviewed submission, and browser-automating a login portal would
  mean storing a Samsung account password in CI secrets with no supported automation surface behind it,
  for a process that still ends at a human reviewer regardless.
- **Separate workflow file** (`deploy-tizen-tv.yml`), same reasoning as R215's `deploy-play-store.yml`:
  different target, different secrets, different failure domain, called from `publish.yml` rather than
  folded into it.
- **Sign with the real Ravilo certificate, not a generic Tizen sample cert.** A `.wgt` signed with
  Tizen Studio's bundled sample distributor certificate is exactly what fails Samsung TV Extension
  7.0.1+'s install-time check ("Invalid certificate chain") — the whole reason a real Samsung-issued
  certificate profile was created this session in the first place. `tz security-profiles add` silently
  falls back to the bundled sample distributor cert when `-d`/`-P` are omitted, so the workflow always
  passes them explicitly.
- **Version derivation matches the existing convention**: the release tag is `vMAJOR.MINOR` (same format
  `publish.yml`/R215 validate); mapped to Tizen's own `config.xml` version ceiling
  (`[0-255].[0-255].[0-65535]`) as `MAJOR.MINOR.0`, failing closed if MAJOR or MINOR exceeds 255 — same
  fail-fast-on-a-scheme-mismatch shape as R215's `versionCode` guard.
- **The Tizen Studio SDK install is cached** (`actions/cache`, keyed on a pinned SDK version) — never the
  certificate/profile files themselves, which are recreated from secrets on every run, so no signing
  material is ever written into a cache entry.

## 3. One-time manual setup (not automatable, not done yet)

1. A Samsung-issued "Ravilo" certificate profile already exists (created via Tizen Studio's Certificate
   Manager, OAuth-signed with a Samsung Developer account, in this project's own Tizen dev container —
   see [[reference-tizen-docker-emulator-setup]]). Its two halves, `author.p12` and `distributor.p12`,
   each have their own password known only to the owner (never recorded anywhere in this repo or its
   backups).
2. Add four repository secrets:
   - `TIZEN_AUTHOR_P12_BASE64` — `base64 -w0 author.p12`
   - `TIZEN_AUTHOR_P12_PASSWORD`
   - `TIZEN_DISTRIBUTOR_P12_BASE64` — `base64 -w0 distributor.p12`
   - `TIZEN_DISTRIBUTOR_P12_PASSWORD`
3. That's it for this workflow. Uploading the signed `.wgt` to the TV Seller Office — and the one-time
   Application registration there before the first upload (Application ID, store listing, a demo/test
   server for Samsung's reviewers to test against) — stays manual, done by the owner when ready to
   actually submit a version. Not part of this phase.

## 4. Verification performed this session

`tz`'s exact CLI surface (`security-profiles add`, `build`, `pack`) was probed live rather than guessed,
against two environments:

- The project's own `tizen_env` Tizen dev container: confirmed `tz security-profiles add -p <pw> -P <pw>`
  still routes the password through gnome-keyring's Secret Service even when passed as a CLI flag
  (`profiles.xml` stores a `*.pwd` lookup key, never the literal password, matching what the interactive
  Certificate Manager flow had already forced this session to learn), and confirmed `tz build`/`tz pack`
  both succeed (exit 0) against a plain directory with no `.tproject` scaffolding, producing a `.wgt`
  containing `author-signature.xml` + `signature1.xml` (exactly what the Seller Office's own package
  requirements state).
- A disposable `ubuntu:22.04` container (representative of a GitHub Actions runner, non-root user, no
  desktop/X/VNC at all): confirmed the full chain — a hand-written passwordless `login.keyring`,
  `dbus-run-session` + `gnome-keyring-daemon --start --components=secrets` (no XFCE/D-Bus-session-sharing
  needed, since there's no competing interactive session to share a bus with in CI), `tz security-profiles
  add`, `tz build`, `tz pack` — all succeed headlessly end to end with a throwaway self-signed certificate.
  Also found: `tz pack`'s output filename is the basename of the packed project directory, not
  `config.xml`'s package id or the generated `tizen_web_project.yaml`'s `output_name` — the workflow
  renames the output explicitly rather than trusting a derived name.

Not verified: the actual workflow YAML has not had a real GitHub Actions run (blocked on §3's secrets),
and signing has only been exercised against a throwaway certificate, never the real Ravilo one.

## 5. Non-goals

- Anything touching the Samsung TV Seller Office API — none exists publicly for TV apps (§1's quote).
- Browser-automating the Seller Office upload/submission — fragile, ToS-uncertain, and still ends at a
  human review regardless of how far automation reaches.
- The one-time Application registration on the Seller Office (Application ID, store listing, demo server
  for reviewers) — human-only, §3.
- Installing/testing on real or emulated Tizen hardware — R264's own remaining scope, untouched here.
