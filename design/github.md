repo: jeggy/jellystructure
branch: main
path: specs/   (plus root STATUS.md — both mirrored read-only from the repo)
tree: 6145f6fc7854   (github tree hash at this sync; not a commit sha)

## Last sync
date: 2026-07-31T09:36Z
direction: pull (repo → this project)
- Re-pulled the entire `specs/` tree (68 files) + root `STATUS.md` from `main`.
- Repo was well ahead of our mirror. New spec files pulled that we lacked:
  admin **151–154**, Ravilo **R183–R186** (all dev-authored bug-fix/UX phases).
- Our earlier design work is now **shipped in code**: `STATUS.md` marks **150** (intro/credits
  segment detection) and **R182** (Skip Intro / Skip Credits) **✓ Done** (dev implementation
  2026-07-13, incl. cross-episode Chromaprint fingerprinting). Same for **R180/R181** (audio/subtitle
  picker + remembered tracks) and **149/R179** (multi-episode files).
- `CLAUDE.md` refreshed to match. `CLAUDE.md` is design-project-owned (not pulled from repo).

### Updated in this project
- specs/ mirror now matches repo `main` (admin 0–154, Ravilo R01–R186).
- STATUS.md mirror refreshed.
- 8 new dev-authored specs added locally (151–154, R183–R186).

### ⚠ Repo-side inconsistency observed (dev team's to resolve — not edited here)
STATUS.md has rows for 150/151 and R180–R183, but **no rows for admin 152, 153, 154 or Ravilo
R184, R185, R186**, though those spec files exist and their bodies say *Implemented*.
`scripts/check-phases.sh` will flag each as a missing row. STATUS.md is code-owned/read-only in
this project, so it was mirrored as-is, not patched.

## Screen map
| Design file(s) | Repo spec(s) |
|---|---|
| app/index.html | phase-146 (dashboard breakdown), phase-138 (mobile) |
| app/series-simpsons.html, app/series-johnnybravo.html, app/detail.css | phase-149 (multi-episode files), phase-150 (segment scrubber), phase-151 (artwork lock) |
| app/settings.html | phase-150 (settings toggles), phase-154 (per-run step picker) |
| app/livetv.html | phase-147 (Live TV admin config) |
| app/ravilo-config.html | phase-148 (Ravilo nav), phase-137 (request builder), R182 (skip behaviour fields) |
| ravilo/Ravilo TV.html, ravilo/Ravilo Mobile.html, ravilo-player.js/.css, ravilo-app.js | R177 (Live TV), R179 (combined card), R180 (A&S picker), R181 (remembered tracks), R182 (skip intro/credits) |
| ravilo/ assets/brand, Barna TV Channel Logo.html | R62 brand (Barna Sjónvarp channel branding — no spec yet) |
| app/library.html, ravilo-builders.js | phase-140 (workbench query blocks) |

## Sync history
- 2026-07-13: pulled credits/intro research report; design-authored 150 + R182 (later shipped by dev).
- 2026-07-13 (earlier): full specs/ + STATUS.md re-pull; only change was new R181 spec.
- 2026-07-12: re-pulled specs/ + STATUS.md mirror.
