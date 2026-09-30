# Phase R334 — The `.flatpak` on every GitHub release, and Flathub

> Owner, 2026-09-29: *"… including a ci setup to deploy to flathub and attach the flatpak package to the gh release"*
> — and, the same evening, *"provide me the instructions that I need to do in caddy (and other stuff)"*.

## Status

`⚠ Partial` — **workflow written 2026-09-29, never run: it waits for these commits to reach `main`, and its Flathub
half for the owner's one-time submission** (§Build notes). Written 2026-09-29 (dev-authored) from
`research-reports/ravilo-linux-flatpak-2026-09-29.md` §6. Number verified free (R333's sibling). **Second of two**
(R333 → **R334**). **Mirrors** R331 (`deploy-macos.yml`) and R264 (`deploy-tizen-tv.yml`). **Builds on** phase 231.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | When the release starts carrying it | ~~Only once R333 is ✓ Built~~ **From the next release (owner, 2026-09-30: a Flathub publish on every release)** — `publish.yml`'s `linux:` job calls this workflow on a published release; by hand it still keeps its bundle as a run artifact and touches nothing |
| D2 | Where it builds | **Flathub's own container image** (`ghcr.io/flathub-infra/flatpak-github-actions:freedesktop-26.08`, `--privileged`) with `flatpak/flatpak-github-actions/flatpak-builder@v6` — the same tools Flathub's builders run |
| D3 | How Flathub learns of a release | **a pull request to `flathub/net.jebster.Ravilo`, opened by this workflow** with `peter-evans/create-pull-request` (the one third-party action Flathub allows in its repositories), carrying the rendered manifest and the fresh sources file; the owner merges. Not a push (Flathub asks that pushes be rare and deliberate), and not the `flatpak-external-data-checker` bot alone — it bumps the tag but cannot regenerate the sources file, so its PR would fail the first time a dependency moved |
| D4 | The file name | **`ravilo-linux-<N>.flatpak`**, beside `ravilo-<N>-release.apk`, `ravilo-tizen-<N>.wgt` and `ravilo-mac-<N>.dmg` |
| D5 | Flathub secrets | one repository secret, **`FLATHUB_TOKEN`** (a fine-grained personal token with *contents* and *pull requests* on `flathub/net.jebster.Ravilo` only); **absent, the Flathub step says so and skips** — a release never fails over Flathub |
| D6 | Everything outside this repository | **the owner's, by hand** (R333 D8): the submission, the token, the verification file on the domain, the merge |

## Requirements

**FR-R334-1 — `deploy-linux.yml`**, three jobs after `version` → `ci`:

- **`sources`** on `ubuntu-latest` with a JDK: the capture from an *empty* Gradle home (R333 FR-R333-2), a check that
  the list holds the app's own dependencies (Compose's Linux artefact, Skiko's Linux runtime, OkHttp, JmDNS — a short
  list means the capture ran too early), the offline replay (R333 FR-R333-5), then `flatpak/render.sh` for the tag
  and commit (a `main` build drops the `tag:` line so the git source is the commit alone). The rendered directory is
  the run's first artifact.
- **`flatpak`** in Flathub's image: `flatpak-builder-lint manifest` on the rendered manifest, `flatpak-builder` from it
  (no network inside; the runtime from Flathub), `flatpak-builder-lint repo` on the result, the bundle as the run's
  second artifact.
- **`attach`** on a published release only: `gh release upload v<N> ravilo-linux-<N>.flatpak --clobber`, one fixed
  paragraph in the notes (FR-R334-3), and the Flathub pull request (FR-R334-4).

**FR-R334-2 — Attach.** From a job with `contents: write`, as R331 and R264 do. `publish.yml` gains a `linux:` job —
`needs: [version, ci, publish]`, `if: github.event_name == 'release'`, `secrets: inherit`, `skip_ci: true` — in the
commit that marks R333 ✓ Built (D1).

**FR-R334-3 — The release notes gain one fixed paragraph**, added once:

> *Ravilo for Linux: download `ravilo-linux-<N>.flatpak` and run `flatpak install ravilo-linux-<N>.flatpak` (Flatpak
> fetches the runtime from Flathub on first install) — or install Ravilo from Flathub, which updates itself.*

**FR-R334-4 — The Flathub pull request.** With `FLATHUB_TOKEN` set: check out `flathub/net.jebster.Ravilo`, replace
`net.jebster.Ravilo.yml` and `flatpak-sources.json` with this release's, write `flathub.json` (`only-arches:
x86_64`) if none exists, open a PR titled *Ravilo <N>* on branch `ravilo-<N>` linking the GitHub release. Flathub's
bot test-builds it; the owner merges; Flathub publishes within an hour or two.

**FR-R334-5 — Nothing here runs on `main`'s push pipeline.** The bundle is a release artefact and a `workflow_dispatch`
convenience; `ci.yml`'s desktop-only compile (R333 FR-R333-1) is what every push pays.

## The owner's steps (outside this repository — by hand, D6)

In order; none is needed before the first hand-run bundle, all before the first Flathub release.

1. **On this box, to build and run the Flatpak locally** (R333 acceptance 3–4): `sudo apt install flatpak
   flatpak-builder`, `flatpak remote-add --if-not-exists --user flathub https://flathub.org/repo/flathub.flatpakrepo`,
   then `flatpak install --user flathub org.flatpak.Builder` for the linter. After that the README's *The Flatpak*
   section has the commands.
2. **The web server — the verification file.** Flathub verifies a domain-based id by a file the domain serves over
   HTTPS at `/.well-known/org.flathub.VerifiedApps.txt`. Its content is a token Flathub shows on the app's page under
   *Verification* (flathub.org → the app → *Verify*), so this step comes **after** the app is on Flathub. In Caddy,
   one `handle_path` or `respond` for that path on the site that serves the bare domain, for example:

   ```
   jebster.net {
       respond /.well-known/org.flathub.VerifiedApps.txt "<the token Flathub shows>" 200
   }
   ```

   Verification is optional for publishing; it gives the *verified* badge and unlocks auto-merge later.
3. **The one-time submission** ([docs](https://docs.flathub.org/docs/for-app-authors/submission)): fork
   `flathub/flathub` with *Copy the master branch only* unticked; `git clone --branch=new-pr` the fork; a branch from
   `new-pr`; in it, `net.jebster.Ravilo.yml`, `flatpak-sources.json` and `flathub.json` from a rendered release
   (`flatpak/render.sh out 1.47 <commit>` on the tagged commit, or the `ravilo-flatpak-manifest` artifact of a
   hand-run of this workflow); a PR **against `new-pr`** titled *Add net.jebster.Ravilo*; comment `bot, build` for a
   test build; answer the reviewers on the same PR. On approval Flathub creates `flathub/net.jebster.Ravilo` and
   invites the GitHub account — 2FA on, accept within a week.
4. **The token.** GitHub → Settings → Developer settings → Fine-grained tokens: repository access
   `flathub/net.jebster.Ravilo` only, permissions *Contents: read and write* and *Pull requests: read and write*; then
   `gh secret set FLATHUB_TOKEN --repo jeggy/jellystructure` (or the repository's Settings → Secrets → Actions).
5. **Each release afterwards:** merge the PR the workflow opens. Once a year (September): bump `runtime-version` and
   the `ffmpeg-full`/`openjdk21` branches in the template when the Freedesktop runtime moves.

## Out of scope

aarch64 (one `targetPlatforms` entry, a QEMU or `ubuntu-24.04-arm` job, and `only-arches` dropped) · a `beta` branch
on Flathub · auto-merge · Flathub's *quality guidelines* beyond what the linter demands (an SVG icon, more screenshots)
· AppImage/`.deb`/`.rpm`.

## Acceptance

1. `workflow_dispatch` on an existing tag produces `ravilo-linux-<N>.flatpak` as an artifact; `flatpak install` of it
   on a clean machine opens the server screen.
2. Both lint steps pass.
3. After R333 is ✓ Built, the next GitHub release carries the bundle and the paragraph.
4. With `FLATHUB_TOKEN` set and the Flathub repository existing, a release opens *Ravilo <N>* there and its test build
   passes; without the token the release still succeeds and the run says why there was no PR.

## Open questions

1. **`flatpak-builder-lint` inside the action image:** installed as `org.flatpak.Builder` from Flathub in the job
   (the image carries the remote); if that install is slow or refused in the container, the lint moves to the
   `sources` job with the linter's own container.
2. **The first Flathub build's memory** (R333 open question 3).
3. **Screenshots at a tag URL** exist only once the tag is pushed with the PNG in it — true from R333's commit on.

## Build notes (2026-09-29)

Written beside R333, never run (D1: the caller in `publish.yml` waits for R333 ✓ Built; a hand-run needs these commits
on `main`). The `sources` job is the same three commands this host ran (capture → replay → render); the `flatpak`
job is the action's documented shape; the `attach` job is `deploy-macos.yml`'s with the Flathub PR added. The first
run will say which of the container assumptions (open question 1) holds.
