repo: jeggy/jellystructure
branch: main
path: specs/   (plus root STATUS.md — both mirrored read-only from the repo); presentation/ (full mirror, ours to build on)
tree: main @ d8a6a6e3610a (2026-09-01 sync)

## Last sync (2026-09-02)
date: 2026-09-02T07:25:03Z
direction: pull (repo → this project) — one research report, then a design pass
- **Pulled `specs/research-reports/ravilo-per-device-decode-ceiling-warning-2026-09-02.md`** (new repo-side,
  19 KB). Triggered by *Until Dawn (2025)* — a 82 Mbps 4K DV/HDR10+ REMUX — stuttering on stue TV and
  being abandoned mid-watch, the third stutter on that TV in three weeks. Owner's proposal: record what
  bitrate each device can take and warn on the Ravilo detail page before Play.
- **What the report establishes:** the *measuring* half already exists (Phase 177 + R216, 2026-08-28 —
  `detectDecoderLimits()` reads the decoder's own declared ceiling, stue TV = exactly 60 Mbps, and the
  server turns it into a `VideoBitrate` condition at 0.9 margin so Jellyfin transcodes instead of
  breaking). Three blockers: (1) last night's session was **Wholphin**, a third-party client that
  negotiates straight against Jellyfin — architecturally unreachable by anything we design; (2) nothing
  persists the ceiling anywhere queryable at rest (`ravilo_device` has no column; `playback_qoe` is
  session-scoped and pruned); (3) the proposal reverses R216's stated invariant *"no user-visible
  setting, ever"* and R180 FR-RV-ASP1-2's no-delivery-cues rule.
- **Design pass done same day** — `ravilo/Decode Ceiling Warning - Directions.html` (canvas): baseline,
  four directions (A admin-only persistence · B meta-row chip · B′ line above Play · C confirm gate ·
  D full numbers), Noir + phone frames, a copy-candidates panel and panels answering all eight of the
  report's open questions. **Recommendation: A + B′.** Key reframe: if 177/R216 are working the file
  does *not* stutter — it re-encodes and takes ~20 s to start, so the honest signal is an expectation
  (**"Slow to start on Stue TV. Give it a moment after you press play."**), not a capability warning.
  The badge is a function of two static numbers only (recorded ceiling × file bitrate, same 0.9
  predicate as 177) — QoE and link state never feed it, so it can't flicker. C rejected (nothing to
  offer but dismissal), D rejected (undoes R180/R218 deliberately).
- **Owner steer, same day:** the note must be **backend-computed per device from the recorded limit plus
  that device's history**, not derived client-side. Design updated and **B′ built into the Ravilo mockups**
  — one server-pushed `playbackNote { device, basis, seconds }` per file per device; ceiling decides
  *whether*, history decides *how sure* (`measured` adds "about 20 seconds", `expected` says "give it a
  moment"), history can never toggle the note. Touched `ravilo-data.js`, `ravilo-app.js`, `ravilo.css`,
  `ravilo-i18n.js`, `Ravilo Mobile.html`. Admin half (A) **drawn 2026-09-02** in `app/ravilo-users.html` — persisted ceiling as a second line in
  each device row ("picture it can take"), two unknown states, no new column. Owner also confirmed the
  start-timing history and the file-bitrate field don't exist yet and will be built backend-side, so both
  fold into 185 instead of blocking it.
- **Both specs written 2026-09-02** (`Planned`, not dev-reviewed): `specs/requirements/phase-185-device-decode-ceiling-and-start-history.md`
  (persist the ceiling, add `Track.video_bitrate`, record start samples, resolve `playbackNote` server-side,
  admin device row) and `specs/ravilo/requirements/phase-R222-slow-to-start-note.md` (render the sentence,
  three strings × en/da/fo, placement + rejected directions). Next unassigned numbers now **186 / R223**.
- **Previously noted as blocking** — three answers needed first: where the file's bitrate comes from (`Track` has
  no bitrate field today), whether other OEM decoders report honest ceilings, and whether the R216 build
  is actually installed on the stue TV Ravilo. Prospective numbers **185** (admin persistence + device
  row) and **R222** (the Ravilo line).

## Previous sync (2026-09-01)
date: 2026-09-01T19:58:58Z
direction: pull (repo → this project) — mirror refresh, no export
- **Nothing new repo-side since the 2026-08-31 sync.** The full `specs/` diff against `4a2675f524cd`
  returns the same 17 files we already pulled (R215–R220, 177–183, amended 167, two research reports).
  Re-pulled **STATUS.md**, `specs/research-reports/README.md` and the amended `phase-167` to be certain
  our read-only mirrors match `main` — the repo wins on any disagreement, per CLAUDE.md.
- **No numbering collision.** A tree scan for `phase-18[4-9]` / `phase-R22[1-9]` matched **0 of 139**
  spec files, so our two 2026-09-01 design-authored specs keep their numbers: **184**
  (choose the TMDB metadata language) and **R221** (every genre on a media detail). Next unassigned
  numbers stay **185 / R222**. No repeat of the R196→R208 or 179→180 collisions.
- **Still not exported.** Both new specs, both directions files, and the 184 build in `app/media.html`
  + `app/detail.css` remain local — see *Pending export* below.

## Previous sync (2026-08-31)
date: 2026-08-31T15:28:03Z
direction: pull (repo → this project)
- **Both design-authored specs from 2026-08-28 shipped.** `phase-R218` (player loading/buffering states)
  is **Implemented** — built the same day it was spec'd, Android/Compose only, **on-device verified
  2026-08-29** on the stue TV. `phase-180` (playback session teardown) is **✓ Done** — the Jellyfin-side
  stop call was confirmed against 10.11.11's OpenAPI *before* any code was written (the open question we
  flagged), and it was verified live against a real forced 4K/DV/HDR NVENC transcode. Canonical versions
  pulled over our local drafts.
- **5 new dev-authored specs pulled:**
  - **R219 — Continue Watching: one canonical list** (`Implemented`, design-authored with the owner
    2026-08-30). Supersedes R217's FR-1/2/3. jellystructure owns one merged list per (user, visibility
    scope); every surface is a filtered/capped view. Every Jellyfin fetch is paged to `TotalRecordCount`
    (no `Limit` may act as a cap — the same truncation error caused four bugs); conflict resolved by
    **timestamp, never provenance**; See-all mirrors the row's own configured scope. Home/channel cap
    drops 30 → 20. Explicitly **no UI change**.
  - **R220 — video output lost on return from background** (`✓ Built 2026-08-31`, not dev-reviewed, not
    reproduced on-device). Black picture with perfect audio after Home-button/TV-standby round-trip;
    detector on rendered-frame count + a 4-rung recovery ladder. **Touches R218:** none of R218's three
    moments fire in this failure, and FR-R220-5 says a slow recovery should show R218's existing **STALL**
    presentation with no new copy or visual language. Build note admits that signal is **not wired**
    (open question 7) — so a viewer can still see several seconds of frozen black frame with no chrome.
  - **181 — converge on Jellyfin's library, don't predict it** (partially implemented; FR-181-2 built).
    Klovn S11E07 missing for 15h: premiere-year freshness bucketing filed a currently-airing 2005 show as
    monthly-archive (9 of 16 provably-airing series were starved), nothing ever compared our item set to
    Jellyfin's, and the Jellyfin-based realtime ingest has delivered **nothing, ever** since phase 165
    (the WS listener subscribes to nothing and `LibraryChanged` is never sent — dead code reporting
    itself healthy). Fix: id **set-difference** sweep as the backstop (`DateCreated` is the file's mtime —
    50% of files are junk-dated, so no timestamp watermark is sound), activity-based freshness, per-series
    count reconciliation, a persistent dirty-set.
  - **182 — a scan must never stop the server** (`✓ Built 2026-08-31`, not live-verified). Whole 4-thread
    scan pool wedged uncancellably for 21 min while `/api/tv/**` starved behind one global 64-permit
    outbound semaphore. Shipped: `SpinLock`/atomics on the scan write path, per-item deadlines, real
    cancel with a grace period, and **INTERACTIVE/BACKGROUND partitioning** of `OutboundHttp` (16 reserved)
    and `ProcessGate` (4 reserved) with bounded acquires → 503 + `Retry-After`.
  - **183 — pace outbound requests by rate, not concurrency** (`✓ Built 2026-08-31`, not live-measured).
    A 300-episode series fanned out ~1 500–2 000 unbounded TMDB requests from one worker slot, retried on a
    flat jitter-free 3 s; a rate-limited fetch then silently wrote null titles and stamped the item as
    checked. Shipped: `TmdbRateLimiter` token bucket + AIMD on 429, `Retry-After` + jittered backoff, an
    episode fan-out gate sized from `scan_workers`, and skipping already-held episode details/credits.
- **Also pulled:** amended `phase-167` (already-known publish-on-main change), `specs/research-reports/README.md`,
  and the **STATUS.md** mirror. No `design/**` changes to pull — the diff's `design/` entries are our own
  2026-08-27/28 export echoing back (segments/library/media/builders, the presentation deck + screenshots,
  and `Player Loading and Buffering - Directions.html`).
- **Next unassigned numbers: 184 / R221.**

### Design work from this sync — all three drawn 2026-08-31 (pending export)
- **FR-182-9 — banner DROPPED (owner decision, 2026-08-31).** The "Ravilo requests are queuing behind
  background work" banner was drawn and then removed: telling the admin about the queuing accepts it as a
  normal state. Ravilo clients get priority unconditionally, and if interactive work does slow down the
  right response is to **stop the running background activity**, not to display a warning. Only the
  read-only **Capacity** card remains (one bar per gate, background vs Ravilo/admin against the reserved
  share, from the `/api/health` gate blocks). The stop-background-work behaviour is **not spec'd** — it is
  a backend change for the dev team, and FR-182-9 as written no longer matches what we want.
- **FR-183-6 — rate limiting visible while it happens. Drawn.** An **Outbound pacing** card on
  `app/activity.html`: per host, the current rate, the limiter's ceiling (with when it last backed off)
  and refusals in the last minute; plus FR-183-5's plain run-summary line "12 fields not fetched: TMDB
  rate limit" and a matching chip in the Overall strip. Copy deliberately avoids promising a re-check —
  FR-183-5's DB half is not built.
- **R220 open question 7 — the recovery ladder shows nothing. Drawn.** New frame **E · video output
  recovery** on `ravilo/Player Loading and Buffering - Directions.html` (black frame, chrome up, spinner
  in the play button's place) plus a panel section recording the decision: reuse R218's stall treatment
  verbatim under a new trigger — no new copy, no new visual language, nothing to translate.

## Previous sync (2026-08-28, second pull — numbering collision resolved)
date: 2026-08-28T21:20:19Z
direction: pull (repo → this project)
- **New repo-side spec: `phase-179-subtitle-sideload-transcode-stall.md` (✓ Implemented, same evening).**
  R183/161's deferred half: phase 177 made the top bitrate tier transcode far more often, which forces
  `embedContainerSubs = false` and routes text subs onto Jellyfin's on-demand VTT extraction — the path
  R183 once measured at 4m37s. Adds a `prewarm_subtitles` pipeline step, a subtitle-only retry policy on
  the Android player, and `subtitle_load_errors` in the QoE report.
- **Collision:** our design-authored teardown spec also claimed 179. Renumbered **ours** to **180**
  (dev tracker wins, as with R196 → R208); all cross-references in R218, CLAUDE.md and this file updated.
- **R218 is uncontested** — no repo-side R218 exists. Next unassigned: **181 / R219**.
- STATUS.md mirror refreshed.

## Previous sync (2026-08-28, first pull)
date: 2026-08-28T16:08:36Z
direction: pull (repo → this project), no design work required
- **10 commits / 51 files since last sync, all backend/platform + CI + specs — nothing under `design/`.**
  New specs pulled: **phase-177** (delivery-aware playback negotiation — per-codec decode-bitrate
  ceiling, honour client audio-codec list for audio-only transcode, link-derived bitrate cap, QoE
  ingest endpoint; ✓ Done), **phase-178** (playback-aware background I/O — defer segment
  detection/artwork fetch and throttle qBittorrent while a TV is watching, "Paused — TV is watching"
  dashboard banner + Run anyway; ✓ Done), **R215** (Play Store internal-track auto-deploy on GitHub
  Release, CI-overridable signing/version; Planned — Play Console setup is manual), **R216** (client
  half of 177: real decoder bitrate ceilings, Wi-Fi link state, hardened `LoadControl`, ExoPlayer QoE
  capture; ✓ Done), **R217** (Continue Watching starvation fix — round-robin merge of resume+next-up
  before capping, not concatenate-then-cap; **Planned, implementation deliberately deferred** per user
  request). **phase-167 amended**: `publish.yml` now also fires on every push to `main` (not
  release-only), pushing `latest` + commit-SHA tags continuously; release publishing unchanged/additive.
  New research report `stue-tv-4k-playback-stutter-2026-08-28.md` — disproves a third-party DV-demux/
  lossless-audio/24p-matching briefing for the reported title, finds the real causes (60 Mbps decoder
  ceiling, 18.6% Wi-Fi retry ratio + 2.4GHz band-steering, shared-spindle I/O contention) → adopted as
  177/178/R216.
- **STATUS.md mirror refreshed** (read-only). **Deleted locally**: `presentation/observed-issues-
  2026-08-18.md` (removed repo-side).
- No screen map changes — nothing here touches a design file.
- Next unassigned numbers: **179 / R218** (per phase-178's/R217's own numbering — confirm on next sync).

### Design-authored 2026-08-28 (local, not yet pushed)
- `specs/ravilo/requirements/phase-R218-player-loading-buffering-states.md` — client buffering/loading
  states (Direction B cold start · chrome-up stall · scrub-tile seek · 400 ms debounce · no escape hatch).
- `specs/requirements/phase-180-playback-session-teardown.md` — server releases an in-flight transcode
  when a viewer leaves; keyed on the existing `playSessionId`. **Renumbered 179 → 180** on 2026-08-28 —
  the dev team took 179 the same evening (subtitle-sideload transcode stall).
- `ravilo/Player Loading and Buffering - Directions.html` — the design reference both specs cite.
- Mirrored `specs/research-reports/ravilo-player-buffering-loading-states-2026-08-28.md`.

### Sync 2026-08-27
direction: pull (repo → this project) + design catch-up here
- **15 new dev-authored specs pulled** — admin **169–176** (all `✓ Done`) and Ravilo **R208–R214**
  (R213 the only `Planned`). **3 changed specs re-pulled**: `phase-163-segment-editor.md` (12.9 KB
  design draft → 30.5 KB canonical, now `✓ Done` with the publish half **dropped**), `phase-168`,
  `phase-R195`. **STATUS.md mirror refreshed** (269 KB). `presentation/` re-pulled — both markdown
  files unchanged, still 57 screenshots.
- **Stale local file deleted:** `phase-R196-episode-rail-autohide.md` — our episode-rail spec was
  **renumbered R196 → R208** repo-side (the dev tracker had already spent R196 on the
  remembered-track regression).
- **Design updated here to match the shipped code** (4 files): `app/segments.js` — publishing to
  Jellyfin removed end to end (no Publish button, no `pub` state, no "in Jellyfin" column; last
  column is now **Confirmed**, primary action "Take me to what needs me"), because Jellyfin 10.11.11
  answers `POST /MediaSegments` with **405** and accepts segments only from provider plugins.
  `app/library.html` + `app/ravilo-builders.js` — **Music videos** added as a third type-filter value
  (phase 172). `app/media.html` — Artwork tab **"Currently in use"** on-disk block (173) and
  **Clear TMDB match** / per-asset **Clear** / locked Re-pull ▾ item (174).
- **Deck refreshed** (`presentation/Jellystructure & Ravilo - Spec-Driven Development.html`): 322
  numbered phases (158 admin + 164 Ravilo), 113 spec/research documents, 328 Kotlin files, 10 Gradle
  modules, arc endpoint 176 · R214, next free 177 / R215 — and the segments slide now tells the
  publish-was-dropped story instead of claiming markers reach Jellyfin.
- Next unassigned numbers: **177 / R215**.

### Sync 2026-08-21
date: 2026-08-21T07:45:05Z
direction: read-only (repo → this project) — no new mirror files
- **Read `initial-idea.md`** (repo root, 1,865 bytes, the project's founding brief) as source material
  for the deck. Not mirrored into this project — it is a historical document, not a spec, and the deck
  quotes it directly.
- Confirmed the codebase statistics quoted on the deck's "in numbers" slide against the repo tree at
  `main`: **311 `.kt` files**, **9 Gradle modules** (`settings.gradle.kts`), 18 route files, 15 Kotlin
  unit-test files, 13 Playwright specs.
- No spec, `STATUS.md` or `presentation/` changes pulled this turn — the 2026-08-20 mirror is still current.
- **Design-authored this turn:** `presentation/Jellystructure & Ravilo - Spec-Driven Development.html`
  (23-slide dev-audience deck) + `presentation/admin/*.png` (6 captures of our own admin mockups, since
  the repo has no Jellystructure screenshots) + `presentation/scratchpad.md` (outline). Pending export.

### Sync 2026-08-20
direction: pull (repo → this project)
- **26 new spec files pulled** (admin 164–168, Ravilo R196–R207 — R196 is a filename-disambiguated
  numbering collision) **+ STATUS.md refresh + a brand-new `presentation/` directory (59 files)** —
  see `CLAUDE.md`'s "Where the work stands" for the per-spec summary. `presentation/` is new ground:
  we don't own it yet but are about to (per the user), so it's mirrored in full rather than skipped —
  `presentation-context.md` (talk material), `observed-issues-2026-08-18.md` (triage log), and
  `presentation/screenshots/*.png` (57 real on-device captures).
- No `design/**` changes on the repo side this sync (only specs/STATUS/presentation moved).
- Next unassigned numbers: **169 / R208**.

### Sync 2026-08-13
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
| app/segments.html, app/segments.js, app/segments.css, app/series-simpsons.js (entry points), app/Segment Editor - Directions.html | **phase-163** (intro & credits editor — shipped 2026-08-13/14; publishing to Jellyfin dropped in dev review, mockups updated 2026-08-27) |
| app/library.html, app/ravilo-builders.js (Include segment) | phase-172 (music-video filter support — shipped, drawn 2026-08-27) |
| app/media.html (Artwork tab, pagebar Identity card) | phase-173 (current on-disk asset), phase-174 (clear a wrong TMDB match) — both shipped, drawn 2026-08-27 |
| (none — backend/platform only) | 169, 170, 171, 175, 176, R209–R214 (parallel probes, segment process pool, music-video artwork/TMDB, unified scan engine, stale-artwork guards, subtitle delivery, startup performance, image cache busting) |
| ravilo/Ravilo Mobile.html, ravilo-player.js/.css | R177, R179, R180, R181, R182, R184 (autoplay stale position fix — shipped), R188 (Upcoming visibility — shipped, no design change), R191 (single-user sign-out — shipped, no design change) |
| ravilo/ assets/brand, Barna TV Channel Logo.html | R62 brand (no spec yet) |
| ravilo/ravilo-player.js, ravilo-player.css, ravilo-app.js, ravilo/Audio & Subtitles Picker - Same-Language Directions.html | R195 (same-language subtitle picker — shipped) |
| app/towo*.html, app/towo.css, app/settings.html (Towo tab), app/app-shell.js (Towo nav group), claude-console/Dashboard - Direction B.html | **phase-162** (Towo agent control plane — design-authored, shipped 2026-08-11; mockups predate the build's extra settings fields) |
| (none — backend/platform only) | R192/R193/R194 (MediaSession lifecycle, metadata, season artwork — shipped, no design change), phase-160 (scanner numbering fallback) |
| ravilo/Player Loading and Buffering - Directions.html, ravilo/ravilo-player.js/.css | **R218** (player loading/buffering states — shipped 2026-08-28, on-device verified 08-29), **phase-180** (session teardown — ✓ Done), R220 (video-output recovery — reuses R218's STALL; presentation not yet wired) |
| app/activity.html | phase-182 (Capacity card only — FR-182-9's banner dropped by owner decision), phase-183 FR-183-6/FR-183-5 (Outbound pacing card + run summary) — drawn 2026-08-31, backend built, UI not yet in code |
| (none — backend only) | R219 (Continue Watching canonical list — explicitly no UI change), 181 (library sync convergence) |
| ravilo/Decode Ceiling Warning - Directions.html | (no spec yet) research report `ravilo-per-device-decode-ceiling-warning-2026-09-02.md`; builds on phase-177 + R216, constrained by R180 FR-RV-ASP1-2. Admin half would land on app/ravilo-users.html |
| presentation/presentation-context.md, presentation/observed-issues-2026-08-18.md, presentation/screenshots/ | (not a spec — talk source material; documents R202 as its centerpiece and the R203–R207 triage) |

## Pending export (design-authored since the last sync)
- **2026-09-02, not pushed:** `ravilo/Decode Ceiling Warning - Directions.html` plus the B′ build in
  `ravilo-data.js` · `ravilo-app.js` · `ravilo.css` · `ravilo-i18n.js` · `Ravilo Mobile.html` · `app/ravilo-users.html`
  (per-device slow-start note, backend-computed; no spec authored yet — see *Last sync* for the three
  open answers).
- **2026-09-01 specs, not pushed:** `specs/requirements/phase-184-choose-metadata-language.md` and
  `specs/ravilo/requirements/phase-R221-all-genres-media-detail.md`, plus their design files
  (`app/Metadata Language Override - Directions.html`, `ravilo/Media Detail Genres - Directions.html`)
  and the 184 build in `app/media.html` + `app/detail.css`. Next unassigned numbers become **185 / R222**.
- **2026-08-31 mockups, not pushed:** `app/activity.html` (Capacity card, FR-183-6 Outbound
  pacing + FR-183-5 run summary; no FR-182-9 banner) and `ravilo/Player Loading and Buffering - Directions.html` (frame E,
  R220's recovery state). No spec files were authored — all three are surfaces for specs the dev team
  already wrote.
- **phase-163 — intro & credits editor** (`Planned`, 2026-08-13): `specs/requirements/phase-163-segment-editor.md`
  + `design/app/segments.html`/`segments.js`/`segments.css`, the `Segment Editor - Directions.html`
  exploration, and the series-page entry points in `series-simpsons.js`. Not pushed yet.

## Sync history
- 2026-09-02: pulled the per-device decode-ceiling-warning research report; design pass (A+B′ recommended), no spec yet.
- 2026-09-01: mirror refresh; no repo changes since 08-31. Confirmed 184 / R221 uncontested.
- 2026-08-31: R218 + 180 shipped (both design-authored, on-device verified); pulled 5 new dev specs (R219, R220, 181, 182, 183) + amended 167 + STATUS.md. Three design items outstanding — see above.
- 2026-08-28 (#2): pulled phase-179; renumbered our teardown spec 179 → 180.
- 2026-08-28: pulled 177/178/R215–R217 + two research reports; design-authored R218 + 180.
- 2026-08-27: pulled admin 169–176 + Ravilo R208–R214 + 3 amended specs + STATUS.md; deleted the stale R196 episode-rail file; updated segments/library/media/builders mockups and the deck.
- 2026-08-21: read-only — `initial-idea.md` + repo tree stats for the deck; nothing new mirrored.
- 2026-08-20: pulled 26 new spec files (admin 164–168, Ravilo R196–R207) + STATUS.md + new `presentation/` directory (59 files, taking over).
- 2026-08-13: Towo/phase-162 shipped — pulled the canonical spec (3 dev-review addenda) + STATUS.md; no other repo-side spec changes.
- 2026-08-10 (#2): R195 shipped; pulled phase 161 + the Claude Code remote-agent research report.
- 2026-08-10: pulled R192/R193/R194 + 160; renumbered our subtitle-picker draft R192 → R195.
- 2026-08-07: confirmed R190 shipped; no other genuinely new content.
- 2026-08-02: pulled 155/156/R187/R188/R189; renumbered our people-filter draft to R190.
- 2026-07-31: re-pulled entire specs/ tree (68 files) + STATUS.md; repo well ahead of design mirror.
- 2026-07-13: pulled credits/intro research report; design-authored 150 + R182 (later shipped).
- 2026-07-13 (earlier): full specs/ + STATUS.md re-pull; only change was new R181 spec.
- 2026-07-12: re-pulled specs/ + STATUS.md mirror.
