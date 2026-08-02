repo: jeggy/jellystructure
branch: main
path: specs/   (plus root STATUS.md — both mirrored read-only from the repo)
tree: 6145f6fc7854   (previous-sync github tree hash; base of this sync's compare — not a commit sha)

## Last sync
date: 2026-08-02T17:47Z
direction: pull (repo → this project)
- Compared `6145f6fc7854...main` (116 files across 27 commits). Pulled only the `specs/` +
  research-report changes into our mirror; **`design/**` was NOT pulled** (design flows out to the
  repo, not back — our working `ravilo/`+`app/` files are ahead with the R190 people-filter work).
- **Repo shipped what we designed:** **Phase 155** (age-rating normalization) and **R187** (browse
  page) are now **Implemented** in code — both arrived with dev-authored "backend/UI addendum" bodies
  that supersede our design drafts. Pulled both canonical versions over our local drafts.
- **New specs pulled:** admin **155** (age normalization, Implemented) · **156** (Seerr per-user
  request attribution, Implemented) · Ravilo **R187** (browse, Implemented) · **R188** (Upcoming
  calendar per-device visibility, Implemented) · **R189** (Samsung Tizen TV client, M1+M2
  implemented, build-verified only). Modified: **R175**, **R184**. Plus 2 research reports
  (backend deployment guide + security review, 2026-08-02) — code-owned, mirrored in.

### Reconciled this session (numbering collision)
- We authored **R188 = filter-by-person** locally last turn, but the dev team had meanwhile taken
  **R188** (Upcoming-calendar visibility) and **R189** (Tizen client). **Renumbered our people-filter
  to `phase-R190-people-filter.md`** and updated CLAUDE.md. R190 now also covers the admin workbench
  **Cast or crew** facet added this turn (§D).
- Our design-drafted `phase-155` and `phase-R187` were **overwritten** by the repo's Implemented
  versions (repo is source of truth).

### Updated in this project
- specs/ mirror now current with repo `main` (admin through 156, Ravilo through R189).
- Design-authored **R190** (filter by person): Ravilo cast face → person browse + Seerr overflow row,
  and the admin workbench Cast-or-crew facet. `Planned`, not yet exported to the repo.
- STATUS.md mirror unchanged by this compare (no root `STATUS.md` diff since the base tree).

## Screen map
| Design file(s) | Repo spec(s) |
|---|---|
| ravilo/Ravilo TV.html, ravilo/ravilo-app.js, ravilo/ravilo-browse.js, ravilo/ravilo.css, ravilo/ravilo-i18n.js | R187 (browse page — shipped), R190 (filter by person + Seerr overflow — design) |
| app/ravilo-builders.js, app/library.html, app/ravilo-config.html | phase-140 (workbench query blocks), R190 §D (Cast-or-crew workbench facet — design) |
| app/metadata.html, app/metadata.css | phase-155 (age-rating normalization — shipped) |
| app/index.html | phase-146 (dashboard breakdown), phase-138 (mobile) |
| app/series-simpsons.html, app/series-johnnybravo.html, app/detail.css | phase-149 (multi-episode files), phase-150 (segment scrubber), phase-151 (artwork lock) |
| app/settings.html | phase-150 (settings toggles), phase-154 (per-run step picker), phase-156 (Seerr per-user attribution — shipped) |
| app/livetv.html | phase-147 (Live TV admin config) |
| ravilo/Ravilo Mobile.html, ravilo-player.js/.css | R177 (Live TV), R179 (combined card), R180 (A&S picker), R181 (remembered tracks), R182 (skip intro/credits) |
| ravilo/ assets/brand, Barna TV Channel Logo.html | R62 brand (Barna Sjónvarp channel branding — no spec yet) |

## Sync history
- 2026-07-31: re-pulled entire `specs/` tree (68 files) + `STATUS.md` from `main`; repo well ahead —
  new dev specs 151–154 + R183–R186; our 149/150/R179–R182 confirmed shipped. github.md written.
- 2026-07-13: pulled credits/intro research report; design-authored 150 + R182 (later shipped).
- 2026-07-13 (earlier): full specs/ + STATUS.md re-pull; only change was new R181 spec.
- 2026-07-12: re-pulled specs/ + STATUS.md mirror.
