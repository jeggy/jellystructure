# Phase R334 — The `.flatpak` on every GitHub release, and Flathub

> Owner, 2026-09-29: *"… including a ci setup to deploy to flathub and attach the flatpak package to the gh release"*
> — and, the same evening, *"provide me the instructions that I need to do in caddy (and other stuff)"*.

## Status

`⚠ Partial` — **workflow written 2026-09-29, never run: it waits for these commits to reach `main`, and its Flathub
half for the owner's one-time submission** (§Build notes). Written 2026-09-29 (dev-authored) from
`research-reports/ravilo-linux-flatpak-2026-09-29.md` §6. Number verified free (R333's sibling). **Second of two**
(R333 → **R334**). **Mirrors** R331 (`deploy-macos.yml`) and R264 (`deploy-tizen-tv.yml`). **Builds on** phase 231.

## Flathub's AI policy (2026-09-30) — what it withdraws

Read on the owner's pointer the day the submission was to be made: Flathub's requirements carry a
**Generative AI policy** ([docs](https://docs.flathub.org/docs/for-app-authors/requirements#generative-ai-policy)):

> Submitters must disclose any AI-generated code, documentation, packaging, or other material they know or reasonably
> believe is included in the application or its Flathub packaging. · Flathub manifests must not contain AI-generated
> or AI-assisted content. · AI tools or agents must not open or automate Flathub submission pull requests, or generate
> their commit messages, descriptions, review comments, or replies. · Undisclosed or materially misrepresented
> AI-generated material, or prohibited AI-generated submission or review interactions, may result in rejection.
> Repeated violations may result in a permanent ban from future submissions and activities.

This project is written with an AI end to end — the app, `flatpak/net.jebster.Ravilo.yml`, `render.sh`, the dependency
capture and this workflow. So **D3, D5 and FR-R334-4 are withdrawn**: the pull-request steps are removed from
`deploy-linux.yml`, no `FLATHUB_TOKEN` is to exist, and no agent working on this repository opens, writes or answers
anything on Flathub. What stays is the `.flatpak` on every GitHub release (FR-R334-1–3), which Flathub's policy does not
touch. A Flathub listing is possible only as the owner's own work: a manifest the owner writes by hand, a submission the
owner opens and answers, the AI-written application disclosed — and reviewers may still decline "based on the extent
or role of generated material". The manifest in this repository is what the `.flatpak` on the release is built from;
it must never be copied into a Flathub pull request.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | When the release starts carrying it | ~~Only once R333 is ✓ Built~~ **From the next release (owner, 2026-09-30: a Flathub publish on every release)** — `publish.yml`'s `linux:` job calls this workflow on a published release; by hand it still keeps its bundle as a run artifact and touches nothing |
| D2 | Where it builds | **Flathub's own container image** (`ghcr.io/flathub-infra/flatpak-github-actions:freedesktop-26.08`, `--privileged`) with `flatpak/flatpak-github-actions/flatpak-builder@v6` — the same tools Flathub's builders run |
| D3 | How Flathub learns of a release | ~~a pull request to `flathub/net.jebster.Ravilo`, opened by this workflow~~ **Withdrawn 2026-09-30 — Flathub's generative-AI policy forbids it** (§Flathub's AI policy). This workflow opens nothing on Flathub |
| D4 | The file name | **`ravilo-linux-<N>.flatpak`**, beside `ravilo-android-<N>.apk`, `ravilo-tizen-<N>.wgt` and `ravilo-mac-<N>.dmg` |
| D5 | Flathub secrets | ~~`FLATHUB_TOKEN`~~ **none** — withdrawn with D3; the secret must not be created for this project |
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
  paragraph in the notes (FR-R334-3). (The Flathub pull request, FR-R334-4, is withdrawn.)

**FR-R334-2 — Attach.** From a job with `contents: write`, as R331 and R264 do. `publish.yml` gains a `linux:` job —
`needs: [version, ci, publish]`, `if: github.event_name == 'release'`, `secrets: inherit`, `skip_ci: true` — in the
commit that marks R333 ✓ Built (D1).

**FR-R334-3 — The release notes gain one fixed paragraph**, added once:

> *Ravilo for Linux: download `ravilo-linux-<N>.flatpak` and run `flatpak install ravilo-linux-<N>.flatpak` (Flatpak
> fetches the runtime from Flathub on first install) — or install Ravilo from Flathub, which updates itself.*

**FR-R334-4 — ~~The Flathub pull request~~.** Withdrawn 2026-09-30 (§Flathub's AI policy); the steps are removed.

**FR-R334-5 — Nothing here runs on `main`'s push pipeline.** The bundle is a release artefact and a `workflow_dispatch`
convenience; `ci.yml`'s desktop-only compile (R333 FR-R333-1) is what every push pays.

## The owner's steps (outside this repository — by hand, D6)

Nothing is needed for the `.flatpak` on the release: the next published release builds and attaches it. The Flathub
steps that stood here (the verification file, the submission, `FLATHUB_TOKEN`, merging per-release pull requests) are
withdrawn with D3 (§Flathub's AI policy).

## Out of scope

aarch64 (one `targetPlatforms` entry, a QEMU or `ubuntu-24.04-arm` job, and `only-arches` dropped) · a `beta` branch
on Flathub · auto-merge · Flathub's *quality guidelines* beyond what the linter demands (an SVG icon, more screenshots)
· AppImage/`.deb`/`.rpm`.

## Acceptance

1. `workflow_dispatch` on an existing tag produces `ravilo-linux-<N>.flatpak` as an artifact; `flatpak install` of it
   on a clean machine opens the server screen.
2. Both lint steps pass.
3. After R333 is ✓ Built, the next GitHub release carries the bundle and the paragraph.
4. ~~The Flathub pull request~~ — withdrawn (§Flathub's AI policy).

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

## FR-R334-3 withdrawn (owner, 2026-09-30)

The release notes carry no install paragraph: *"People downloading dmg already know how to install a dmg, and the
same with flatpak, wgt etc."* The workflow attaches the file and leaves the notes alone. Release notes say
concretely what the release contains — no install steps, no account of what was or was not tested.

