repo: jeggy/jellystructure
branch: main
path: specs/   (plus root STATUS.md — both mirrored read-only from the repo)
tree: 023eff89cbea   (2026-08-13 resolved tree hash — not a commit sha)

## Last sync
date: 2026-08-13T08:47:34Z
direction: pull (repo → this project)
- **Towo (Phase 162) shipped — our design spec is now `✓ Done`.** Authored here 2026-08-10 as `Planned,
  not yet dev-reviewed`; the dev team reviewed and built **all 8 build-order steps on 2026-08-11**, plus a
  same-day completeness pass, and verified it live end to end (real Claude account, real browser via
  Playwright, isolated scratch backend on port 19505 — the real backend was never touched). Pulled the
  canonical 49 KB spec (three dev-review addenda) over our 20 KB draft.
- **Only one spec file changed repo-side this sync** — a byte-for-byte compare of all 89 mirrored files
  found no additions, deletions or other edits. `STATUS.md` mirror refreshed. No new dev-authored phases.
- **What the build changed vs. our design** (all additive; **mockups updated the same day** — new Settings
  fields, the runners-list `mirror_error` chip, and a "runner offline" preview state on the session view):
  Towo settings are **backend-owned in `towo_settings`, not localStorage** (the auto-continue scheduler
  has no browser to read from) — only the `js-towo` feature flag stays client-side; new settings fields
  we never designed: **low-quota threshold %**, **permission-request timeout + reason** (default 30 min),
  **"where runners connect" URL** override, and the five notification toggles now actually fire.
  Auto-continue is **control-plane-owned**, not runner-owned. There is **no runner-detail screen** in the
  build, so the new `mirror_error` warning rides the runners **list row** instead of §B's detail page.
  Session view gained a live **"runner offline" banner**; a permission decision made while the runner is
  down is durably **queued and redelivered** (chosen over fail-fast, closing our open question); a
  timed-out request records `decision = "timeout"`.
- Both SDK unknowns we flagged are **resolved**: `canUseTool`'s signature is pinned
  (`@anthropic-ai/claude-agent-sdk@0.3.227`; returning `null` is a first-class out-of-band-approval
  mechanism, and there is **no built-in timeout**), and `rate_limit_event` is real + camelCase with
  `utilization` a **0–1 fraction, not 0–100** — the live account's active bucket is `seven_day`.
- Still open in the spec: subscription-vs-API-key, unattended OAuth on a headless host, and whether Towo
  eventually graduates out of this admin app. Remaining build polish: a resumed session loses its
  permission profile (falls back to Normal).
- Next unassigned numbers: **163 / R196** (unchanged).

### Sync 2026-08-10 (#2)
### Sync 2026-08-10 (earlier, numbering collision)
- **Numbering collision resolved:** the dev team took **R192/R193/R194** (MediaSession trio) while our
  same-language subtitle-picker draft sat at R192. Our spec renumbered **R192 → R195**
  (`phase-R195-same-language-subtitle-picker.md`); every R192 reference in `CLAUDE.md`,
  `ravilo/ravilo-player.js` and `ravilo-player.css` updated. Still `Planned`, not yet dev-reviewed.
- **New dev-authored specs pulled (all `Implemented`):** **R192** (release the OS MediaSession when the
  TV player backgrounds, not only on screen exit — lingering phone media card), **R193** (rich
  media-session metadata: title/episode/artwork, deliberately TV-only — phone gets no MediaSession at
  all, trading headset transport keys for the stronger privacy guarantee), **R194** (session artwork
  prefers the season poster, falling back to the series poster — never an episode still),
  **160** (scanner falls back to Jellyfin's own season/episode numbering when filename parsing fails —
  the numbering counterpart to phase-152's id join).
- Re-pulled `specs/plan.md`, `specs/ravilo/plan.md` and the `STATUS.md` mirror.
- No change to R190 or phase-157 (both still `Implemented`).
- Next unassigned numbers: **161 / R196**.

### Previous sync
date: 2026-08-09T00:08:26Z
direction: pull (repo → this project)
- Compared `6145f6fc7854...main` (173 files, 56 commits). Most were our own `design/**` export
  echoing back, or code (ravilo-tizen module, backend/UI wiring) not pulled here. **Pulled the real
  spec changes:**
- **Phase 157 (Bazarr subtitles) — our design spec is now `Implemented`.** The dev team built it
  end to end with a dev-review addendum: id-join matching (`imdbId`/`tvdbId`, no path-matching —
  simpler than either the spec or the Radarr precedent assumed), the full Bazarr command surface
  confirmed live against a real instance (4,999 wanted episodes + 35 movies, validating our "5,000+
  is normal" framing), "Upgrade" composed client-side (no single-item Bazarr endpoint exists),
  per-title History merges at render time only (never persisted — keeps "stores nothing" intact).
  Series per-title History merge deliberately NOT built (movies only, noted as a limitation).
- **New dev-authored specs pulled:** **158** (IMDb ratings — imdbapi.dev died; pivoted to IMDb's own
  bulk ratings dataset after the planned title-page scrape hit an unsolvable AWS WAF JS challenge),
  **159** (intro/credits detection overhaul — offset-histogram alignment for variable cold opens,
  outro fingerprinting, credits-heuristic hardening). Both `Implemented`.
  **R188** (Upcoming calendar per-device visibility — restricted/kids profiles no longer see
  unscoped *arr calendar speculation), `Implemented`. **R191** (single-profile sign-out without
  unpairing the whole TV — also fixed a real "Unpair this TV" 404 bug found in passing), `Implemented`.
- **R190 (our people-filter design) — confirmed still `Implemented`**, no change since last sync.
- **New research report pulled:** `subtitle-picker-same-language-disambiguation-2026-08-09.md` —
  quantifies same-language subtitle-track collisions in the live library (46.5% of movies, 47.5% of
  series affected) and scopes a future **R192** picker redesign. Research only, not a spec yet.
- **Modified specs re-pulled:** `specs/ravilo/plan.md` (R175/R191 route-table updates), `phase-R175`
  (D-pad field-navigation bug fix addendum), `phase-R184` (third root-cause addendum — non-deterministic
  Jellyfin episode-duplicate lookup).
- **STATUS.md mirror refreshed** (read-only, per CLAUDE.md — never edited here).
- Design-authored **158 = filter-by-person** is NOT a conflict — R190 already covers that; no
  renumbering needed this sync.

### Previous sync — 2026-08-07T11:26Z (pull)
- Compared `6145f6fc7854...main`, 135 files/35 commits — mostly our own design echo. Confirmed R190
  flipped `Planned → Implemented` in the repo (dev team shipped our people-filter design end to end,
  incl. the ravilo-tizen module).

### Previous sync — 2026-08-02T17:47Z (pull)
- Pulled specs/ + research-report changes only (`design/**` not pulled — design flows out, not
  back). Repo shipped Phase 155 (age-rating normalization) and R187 (browse page) — both
  `Implemented`, canonical dev versions pulled over our drafts. New specs: 155, 156, R187, R188,
  R189 (Tizen client, build-verified). Reconciled a numbering collision: our people-filter renamed
  R188 → **R190** (dev team had taken R188/R189 meanwhile).

## Screen map
| Design file(s) | Repo spec(s) |
|---|---|
| ravilo/Ravilo TV.html, ravilo/ravilo-app.js, ravilo/ravilo-browse.js, ravilo/ravilo.css, ravilo/ravilo-i18n.js | R187 (browse page — shipped), R190 (filter by person + Seerr overflow — shipped) |
| app/ravilo-builders.js, app/library.html, app/ravilo-config.html | phase-140 (workbench query blocks), R190 §D (Cast-or-crew workbench facet — shipped) |
| app/metadata.html, app/metadata.css | phase-155 (age-rating normalization — shipped) |
| app/index.html | phase-146 (dashboard breakdown), phase-138 (mobile), phase-157 (Subtitles dashboard card — shipped) |
| app/series-simpsons.html, app/series-johnnybravo.html, app/detail.css | phase-149 (multi-episode files), phase-150 (segment scrubber), phase-151 (artwork lock), phase-159 (segment-detection accuracy — backend only, no design change) |
| app/settings.html | phase-150, phase-154, phase-156 (Seerr per-user attribution — shipped), phase-157 (Bazarr connection — shipped) |
| app/subtitles.html, app/app-shell.js (Subtitles nav) | phase-157 (Bazarr subtitle overview — shipped) |
| app/media.html, app/series.html | phase-157 (Tracks & subtitles / season Bazarr cards — shipped), phase-158 (IMDb re-sync label fix — shipped) |
| app/livetv.html | phase-147 (Live TV admin config) |
| app/segments.html, app/segments.js, app/segments.css, app/series-simpsons.js (entry points), app/Segment Editor - Directions.html | **phase-163** (intro & credits editor + publish to Jellyfin — design-authored, Planned) |
| ravilo/Ravilo Mobile.html, ravilo-player.js/.css | R177, R179, R180, R181, R182, R184 (autoplay stale position fix — shipped), R188 (Upcoming visibility — shipped, no design change), R191 (single-user sign-out — shipped, no design change) |
| ravilo/ assets/brand, Barna TV Channel Logo.html | R62 brand (no spec yet) |
| ravilo/ravilo-player.js, ravilo-player.css, ravilo-app.js, ravilo/Audio & Subtitles Picker - Same-Language Directions.html | R195 (same-language subtitle picker — shipped) |
| app/towo*.html, app/towo.css, app/settings.html (Towo tab), app/app-shell.js (Towo nav group), claude-console/Dashboard - Direction B.html | **phase-162** (Towo agent control plane — design-authored, shipped 2026-08-11; mockups predate the build's extra settings fields) |
| (none — backend/platform only) | R192/R193/R194 (MediaSession lifecycle, metadata, season artwork — shipped, no design change), phase-160 (scanner numbering fallback) |

## Pending export (design-authored since the last sync)
- **phase-163 — intro & credits editor** (`Planned`, 2026-08-13): `specs/requirements/phase-163-segment-editor.md`
  + `design/app/segments.html`/`segments.js`/`segments.css`, the `Segment Editor - Directions.html`
  exploration, and the series-page entry points in `series-simpsons.js`. Not pushed yet.

## Sync history
- 2026-08-13: Towo/phase-162 shipped — pulled the canonical spec (3 dev-review addenda) + STATUS.md; no other repo-side spec changes.
- 2026-08-10 (#2): R195 shipped; pulled phase 161 + the Claude Code remote-agent research report.
- 2026-08-10: pulled R192/R193/R194 + 160; renumbered our subtitle-picker draft R192 → R195.
- 2026-08-07: confirmed R190 shipped; no other genuinely new content.
- 2026-08-02: pulled 155/156/R187/R188/R189; renumbered our people-filter draft to R190.
- 2026-07-31: re-pulled entire specs/ tree (68 files) + STATUS.md; repo well ahead of design mirror.
- 2026-07-13: pulled credits/intro research report; design-authored 150 + R182 (later shipped).
- 2026-07-13 (earlier): full specs/ + STATUS.md re-pull; only change was new R181 spec.
- 2026-07-12: re-pulled specs/ + STATUS.md mirror.
