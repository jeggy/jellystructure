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

**`⏸ Paused` 2026-09-25 — owner: *"Lets stop all tizen development for a while and lets mark them as paused."*** No Tizen work (building, testing on the
emulator or a TV, or the Samsung store submission) happens until the owner resumes it. Where it stood: The release workflow still builds, signs and attaches the `.wgt` to every GitHub Release; nothing else is being done with it. The Samsung Seller Office submission started the same day is paused with it (nothing was created or uploaded there).
The status below is the state it was paused in.

`✓ Built` — built 2026-09-19, **dev-reviewed twice 2026-09-19** (§Dev review at the foot, against
`dcb97f2c`; all 6 actionable findings applied same day — see below). `.github/workflows/deploy-tizen-tv.yml`
wired into `publish.yml` as a new `tizen-tv` job (same `needs: [version, ci, publish]` /
`if: github.event_name == 'release'` shape as the existing `play-store` job). The exact `tz` CLI +
headless-signing recipe was verified end-to-end against a throwaway self-signed certificate in this
project's own Tizen dev container and in a clean disposable `ubuntu:22.04` container matching a GitHub
Actions runner (see §4) — entirely headless, no VNC/X/XFCE required for signing (only ever needed for the
interactive Samsung-account sign-in that *created* the certificate).

**Four real CI runs against `main`, 2026-09-19** (releases v1.28/v1.29 plus two manual `workflow_dispatch`
retries), each finding and fixing one distinct real issue:
1. v1.28 (pre-secrets) failed as expected once it reached signing.
2. v1.29 (secrets added) reached "Decode Tizen certificate" and failed: `base64: invalid input` on
   `TIZEN_AUTHOR_P12_BASE64` — a malformed paste. Hardened same day: the decode step verifies each secret
   individually (non-empty, decodes, opens as a real PKCS12 with its password) before ever reaching `tz`.
3. Dev review (below) found the verify-late-cost problem and 5 smaller things; all applied.
4. A corrected re-paste got past the base64 check but failed the *new* PKCS12-open check:
   `AUTHOR_B64` → `unsupported ... Algorithm RC2-40-CBC`; `DIST_B64` → `Mac verify error: invalid
   password?`. **Root cause: not the secrets — the verification step itself.** This certificate was
   deliberately created with SHA1-MAC/RC2-or-3DES so Tizen's own `tz` (a Go PKCS12 implementation with no
   legacy-provider concept) can read it; OpenSSL 3.x's *default* provider refuses those algorithms outright
   unless `-legacy` is passed. `tz` itself needs no such flag — only this project's own diagnostic
   `openssl pkcs12 -info` check did, and now has it.

**Fifth real CI run, same day, after `-legacy` landed:** `AUTHOR_B64`/`AUTHOR_PW` now decode **and**
verify cleanly end-to-end — the verification code path itself is proven correct. `DIST_B64` still decodes
from base64 fine but `openssl pkcs12 -info -legacy` cannot open it with the password in `DIST_PW` (`Mac
verify error: invalid password?`). Since the exact same code just succeeded for the author cert, this is
no longer a tooling question — **`DIST_B64` and/or `DIST_PW` themselves need to be re-checked/re-pasted**,
the same way `TIZEN_AUTHOR_P12_BASE64` needed a re-paste in run 2. Likely candidates: the distributor
certificate was exported with a different password than the author one and `DIST_PW` doesn't match it, or
`DIST_B64` was generated/copied with the same malformed-paste issue run 2 hit (stray character, missing
bytes, a line-ending artifact from `base64 -w0`/`pbcopy`).

**Sixth real CI run, same day, after the owner re-checked the distributor password locally with the
file-based `openssl -passin file:` method (bypassing shell quoting of `^`/`%` entirely) and re-pasted both
`DIST_B64`/`DIST_PW`: full green.** Both certs decode and verify, `tz security-profiles add` /
`build` / `pack` all succeed, the resulting `ravilo-screen-1.29.wgt` (256,705 bytes, a real signed package
— not an empty/placeholder file) is attached to the `v1.29` GitHub Release via `gh release upload`. This
is the first fully successful end-to-end run of the pipeline against the real production certificate.

**Scope now fully delivered**: every release from here on will have a signed `.wgt` waiting on its GitHub
Release page, ready for the owner to hand-upload to Samsung's TV Seller Office (which, per §1, has no
submission API — that upload step is and remains manual). Nothing left to build in this phase; the six
real CI runs above are the acceptance record.

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

## 3. One-time manual setup

Done as of 2026-09-19 — the owner added all four secrets (see §Status for what's been found while
getting them right). Kept here as the reference for what each secret is and how to regenerate it.

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

Not verified as of this writing: signing has only been exercised against a throwaway certificate, never
the real Ravilo one — see §Status for the four real CI runs against `main` that got progressively closer
to that (SDK install, Gradle build, config.xml patching, headless keyring, and the cert/password decode
chain are ALL now proven in real GitHub Actions; only the final `tz build`/`pack`/upload with the actual
certificate remains unconfirmed).

## 5. Non-goals

- Anything touching the Samsung TV Seller Office API — none exists publicly for TV apps (§1's quote).
- Browser-automating the Seller Office upload/submission — fragile, ToS-uncertain, and still ends at a
  human review regardless of how far automation reaches.
- The one-time Application registration on the Seller Office (Application ID, store listing, demo server
  for reviewers) — human-only, §3.
- Installing/testing on real or emulated Tizen hardware — R264's own remaining scope, untouched here.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

Traced against `.github/workflows/deploy-tizen-tv.yml` (254 lines) and `publish.yml`. The research
conclusion is sound, the scope decision is the right one, the wiring matches the `play-store` job's shape
(`publish.yml:156-163`, `needs: [version, ci, publish]`, `if: github.event_name == 'release'`,
`skip_ci: true`), `permissions: contents: write` is correctly raised at the caller (`:35-39`), and the
cache holds only `${{ runner.temp }}/tizen-studio` — no signing material, as claimed. **Two changes would
materially shorten the retry loop the phase is currently stuck in**, and three smaller things are worth
fixing while the file is open.

1. **Verify the certificate *before* the 20-minute build, not after it.** The step order is checkout →
   Java → Gradle → `syncScreenReceiver` → config.xml patch → SDK install → keyring → **Decode Tizen
   certificate** → sign. That is why the v1.29 run spent the entire build and SDK install before
   discovering a malformed base64 paste. The decode step needs **nothing** from any step before it. Move
   it to immediately after `checkout` and the next attempt fails in about thirty seconds instead of
   twenty minutes. The same-day hardening was exactly right about wanting a precise error; this is the
   other half of the same fix, and it is a two-line move.
2. **`workflow_dispatch` has no `skip_ci`, so every manual retry re-runs the full CI.** `workflow_call`
   takes `skip_ci` (`:64-69`) and `publish.yml` passes `true`; the `workflow_dispatch` inputs (`:70-74`)
   do not, so the `ci` job's `if: ${{ !inputs.skip_ci }}` is always true on a manual run — roughly 40
   minutes of e2e (`publish.yml:41-42`'s own figure) per attempt, against a tag whose commit already
   passed CI when it was released. The manual path is precisely the one that will be used to retry after
   the secret is re-pasted. Add `skip_ci` to the dispatch inputs, defaulting `false` so the safe
   behaviour stays the default and a deliberate retry can opt out.
3. **`find … | head -1` can ship the wrong artefact, and the phase's own §4 explains why it is a `find`
   in the first place.** `OUT_WGT=$(find "$WGT_DIR" -name '*.wgt' | head -1)` searches
   `ravilo-screen/wgt`, which *contains* the build tree `tz build` writes into. `find` has no defined
   ordering, so the moment a second `.wgt` exists anywhere under that root — a restored artefact, an
   intermediate a future `tz` leaves behind, a packaging change — the workflow silently uploads a
   nondeterministic one. On a clean runner today there is exactly one, so this is latent rather than
   broken. Make it deterministic: assert exactly one match
   (`[ "$(find … | wc -l)" -eq 1 ]`) before the `mv`, or pack into a dedicated empty output directory.
   Cheap, and it removes a whole class of "the release has the wrong file in it" that would be very hard
   to notice.
4. **`install --latest || true` hides the failure the next step will be blamed for.** The package-manager
   call at `:158-159` swallows every non-zero exit. If that genuinely fails — network, mirror, a changed
   package name — the run continues and the error surfaces later, in `NativeCLI cert-add-on` or in `tz`
   not existing at all, which reads as a Tizen problem rather than an install problem. The `|| true` is
   presumably there because `--latest` exits non-zero when there is nothing to update; scope it to that,
   or capture the code and log it. Same reasoning as item 1: this phase already decided that a cryptic
   failure is a defect worth fixing.
5. **The cache key's package list is a hand-written label, so it cannot invalidate itself.**
   `key: tizen-studio-${{ env.TIZEN_STUDIO_VERSION }}-nativecli-cert-add-on` names the two packages in a
   string. The install step is gated on `cache-hit != 'true'`, so if a third package is ever added to
   that step without someone remembering to edit the key, a stale cache is restored **missing the new
   package** and nothing re-installs. Either hash the install step into the key or write the maintenance
   rule into the spec — this is the kind of thing that costs an hour of debugging a year later, and one
   sentence prevents it.
6. **§3's heading and §4's closing paragraph are both stale, in opposite directions.** §3 is titled
   "One-time manual setup (**not automatable, not done yet**)" while the Status records that the owner
   added all four secrets — three of them good, one malformed. §4 ends "Not verified: the actual workflow
   YAML has not had a real GitHub Actions run", which the Status directly contradicts with two named runs.
   The workflow's own header carries the same stale line (`deploy-tizen-tv.yml:55-57`: "*this exact
   workflow file has never had a real CI run … First real run may need one small fix*"). Re-stamp all
   three with what v1.28/v1.29 actually established — SDK install, Gradle build, `config.xml` patch,
   headless keyring and NativeCLI/cert-add-on install all proven in real GitHub Actions — so the only
   genuinely unproven step is signing with the real Ravilo certificate. As written, the spec undersells
   its own verification, which is the opposite of the failure mode this project usually guards against
   and just as misleading.
7. **Confirmed, no change needed.** `gh release upload --clobber` makes a re-run idempotent; the
   `-A` on `security-profiles add` is what makes the profile active for the subsequent `build`/`pack`;
   the `MAJOR.MINOR` → `MAJOR.MINOR.0` mapping and the 255 guard (`:99-103`) fail closed exactly as R215's
   `versionCode` guard does; and the cache genuinely never sees certificate material, which was the right
   thing to be careful about.

**Nothing here blocks the owner's next step.** Re-pasting `TIZEN_AUTHOR_P12_BASE64` is still the one
thing standing between this phase and a signed artefact; items 1 and 2 just mean the attempt after that
one costs seconds rather than an hour.

**All six actionable items applied 2026-09-19, same day.** Cert decode now runs immediately after
checkout (item 1); `workflow_dispatch` gained a `skip_ci` input, default `false` (item 2); the `.wgt`
selection now asserts exactly one match rather than trusting `find`'s ordering (item 3); the
package-manager `--latest` step logs its exit code instead of a bare `|| true` (item 4); the cache key's
own comment now states the manual-bump rule explicitly (item 5); §3/§4/the workflow header's stale text is
re-stamped (item 6, folded into the rewritten §Status above). Doing so surfaced item 4's real value
immediately: it's how the RC2/`-legacy` finding above got isolated to one step's output instead of buried
in the SDK/Gradle log preceding it.
