repo: jeggy/jellystructure
branch: main
path: specs/   (plus root STATUS.md — both mirrored read-only from the repo); presentation/ (full mirror, ours to build on)
tree: main @ 42885163eeee (2026-09-12 pull)

## Last sync (2026-09-12, later — pull; 19 new dev specs, no renumbering needed)
date: 2026-09-12T07:25:00Z
direction: pull (repo → this project). 29 files mirrored (STATUS.md, both plans/constitution, 19 new
specs, 8 amended), nothing exported. **Repo wins on every disagreement, per CLAUDE.md — our local 187 and
R234 drafts were overwritten by the canonical, dev-reviewed, now-built versions.**
- **No numbering collision, so nothing was renamed.** `main` tops out at **201** / **R239**: no
  `phase-202-*` or `phase-R240-*` file, and `STATUS.md` has no row for either number. Our 2026-09-12
  focus-detail pair keeps **202 / R240**. **Next free: 203 / R241.**
- **187 + R234 are `✓ Built` (2026-09-05)** — the profile-photo/password pair we design-authored on
  2026-09-03 and renumbered on 2026-09-04. 187's backend landed with both write routes, the change-keyed
  avatar URL (R214 pre-empted) and the admin read-only photo; R234 was **live-verified on stue TV**, and a
  pre-existing Settings D-pad focus-chain bug (Change password / Sign out / Unpair unreachable) was found
  and fixed in the same pass. Canonical specs are 32 KB / 23 KB against our 12 KB drafts — pulled over.
  **FR-187-1's live Jellyfin probe closed every open question:** all three operations exist, none at the
  assumed path, the OpenAPI document is wrong about the image body (raw binary → 500, base64-with-MIME →
  204), and a wrong password answers **403**.
- **⚠ Our mockups now lead both specs in two places** (they must change before either ships, and they are
  the only design work this sync inherits from our own earlier passes): **preset colours are dropped**
  (owner decision on 187 OQ3 — `AV_PRESETS`/`colorFor`/`setColor` in `ravilo/ravilo-data.js` and the Your
  profile sheet in `ravilo/Ravilo Mobile.html`), and the **"Photo and name"** row promises a rename that is
  out of scope. 187 also records that `.usr-av`/`.has-photo`/`.av-img` and 185's `.usr-cap` existed only in
  our mockup's inline `<style>` and had never reached `wf.css` — fixed dev-side, now fenced by
  `check-mobile-css.sh`.
- **19 new specs mirrored:** admin **188–201** (capped-scan episode destruction, segment-editor markers +
  audio, metadata-language override on every write, clearlogo pipeline, Jellyfin-behind banner, transient
  failure vs rejected token, retry-set drain, `last_checked` semantics, four days of red CI, a constraint
  violation breaking the build, JS tags surviving a slug rename, invisible sidecar subtitles,
  `mkvpropedit` evicting `Tracks`) and Ravilo **R235–R239** (signs-only subtitle auto-select, Home-hero
  Down stranding focus, don't retry an unretryable failure, wrong version badges on the language row,
  honest subtitle flag strip). All `✓ Built` except **R239** `⚠ Partial`. Also re-pulled as amended:
  `specs/constitution.md` (invariant #6's predecessor rule, per 199), `specs/plan.md`, `phase-155`,
  `phase-178`, `phase-186`, `R202`, `R219`.
- **Nine design items land on us** — see `CLAUDE.md` for the detail: 201's MKV-layout health card +
  per-title Fix now banner, 200's SUBTITLES strip + Sidecar subtitles group, 192's logo-as-logo picker fix
  + artwork-repair button, 193's honest banner copy, 196's `last_examined_at` labels, 189/190's stepper +
  lock toast + audio-transcode badge, 191's mismatch card, R239's honest flag counting, and R237's
  per-cause failed-start copy + the *"Still trying…"* line on R218's cold start. **None drawn yet.**
- **Counters re-derived:** `STATUS.md` = **387** rows (190 admin + 197 Ravilo, no gaps, no duplicates —
  the 2026-09-04 repo-side STATUS gap for R227–R232/186 is **closed**); **179** documents under `specs/`
  (177 on `main` + our two unpushed drafts); highest **202 / R240**; next free **203 / R241**. Deck
  counters updated in `presentation/Jellystructure & Ravilo - Spec-Driven Development.html`.
- `presentation/` is unchanged upstream (compare against `e8a11120` returns nothing) — the mirror is
  still current and ours to build on.

## Previous sync (2026-09-12 — read-only: numbering check before writing two specs)
date: 2026-09-12T06:43:33Z
direction: read-only (repo → this project). Nothing copied, nothing exported — the repo tree was queried
to pick collision-free numbers, and one adjacent spec was read in full because it touches the same code.
- **The dev team has moved a long way since 2026-09-04.** A tree scan at `main` shows admin phases taken
  through **201** and Ravilo through **R239**: admin `188` (scan episode cap destroys existing episodes),
  `189`/`190` (segment-editor markers + audio), `191` (metadata-language override survives every write —
  our 184's follow-up), `192` (clearlogo pipeline), `193`–`196` (Jellyfin-behind banner, transient failure
  vs rejected token, ingest retry set, last-checked semantics), `197`/`198` (CI red four days; a constraint
  violation breaks the build), `199` (js tags survive a slug rename), `200`/`201` (sidecar subtitles
  invisible; `mkvpropedit` evicts the Tracks element); Ravilo `R235` (never auto-select a signs-only
  subtitle), **`R236`** (Down from the Home hero can stop working entirely), `R237` (don't retry a failure
  that cannot succeed), `R238` (picker language row shows the wrong version badges), `R239` (honest
  subtitle flag strip).
- **Our two new specs are therefore 202 / R240**, written the same day, both `Planned`, neither
  dev-reviewed — the focus-detail pair (config + payload, and the viewer half incl. J's motion rules).
  **Next free: 203 / R241.** No renumbering needed this time.
- **R236 read in full, because it owns code R240 touches.** It rewired Home's Down bridge from a
  per-item `FocusRequester` to a row-level one decorated with `focusRestorer()` (✓ Built 2026-09-06, not
  device-tested). R240 cites it: J inserts and removes a `LazyRow` child on every settled focus move, so
  nothing J draws may carry a requester or be reachable by the restorer — and R236's own open question 2
  (does the restorer compose a *disposed* remembered child?) now has a sibling-resizing case layered on it.
- **⚠ This project's `STATUS.md` mirror and spec mirror are ~14 documents stale.** Neither was refreshed
  this turn (the task was two specs, not a sync). Re-pull both — and re-derive every presentation counter —
  before quoting status, phase counts or document counts anywhere.

## Previous sync (2026-09-04 — pull; two numbering collisions resolved)
date: 2026-09-04T18:08:00Z
direction: pull (repo → this project). 5 new spec files mirrored, STATUS.md refreshed, our two unpushed
drafts renumbered out of the way of numbers the dev team had already taken.
- **Collision 1 — admin 186.** The repo's `phase-186-request-intent-lifecycle-cleanup.md` (Planned,
  design-authored 2026-09-04) took the number our profile-photo draft was holding. **Ours is now 187**
  (`specs/requirements/phase-187-account-photo-and-password.md`); all `FR-186-*` ids inside renumbered.
- **Collision 2 — R230.** The repo's `phase-R230-fully-disable-skip-intro-credits.md` (Implemented
  2026-09-03) took R230. **Ours is now R234** (`specs/ravilo/requirements/phase-R234-profile-photo-and-password.md`);
  `FR-R230-*` → `FR-R234-*`, and the mockup comments that cited R230 (`app/ravilo-users.html`,
  `ravilo/ravilo-app.js`, `ravilo/Ravilo Mobile.html`) now cite R234. Both spec files carry a
  renumbering note at the top. Same shape as R196 → R208 and 179 → 180.
- **New specs pulled:** **R231** (Continue Watching timeout cache poisoning — Implemented; the report we
  folded into the deck yesterday, now mirrored), **R232** (series-detail & player D-pad polish — ✓ Built
  2026-09-04, live-tested on stue TV: season-row focus/clipping, player Right no longer teleports to
  Back), **R233** (system rows always scoped to the page they are on — Planned), repo **R230** (Skip
  Credits Off was inert since R182 — Implemented), repo **186** (request-intent lifecycle cleanup — Planned).
- **One outstanding design item, deliberately not built yet: R233 FR-R233-7** asks for the per-channel
  `scope` segmented controls to be removed from `app/ravilo-builders.js` (`:364` new-channel template,
  `:534`, `:540`, `:736`) and the two row descriptions made unconditional. R233 is Planned and not
  dev-reviewed, so the mockup still shows the control. Apply when R233 is accepted.
- **Counts re-derived:** STATUS.md now has **359** rows (174 admin + 185 Ravilo); **158** documents under
  `specs/`. Deck counters updated (359 unchanged, 154 → 158, highest 187 / R234, next free 188 / R235).
- **STATUS.md gap widened (repo-side, not ours):** no rows for R227–R232 or admin 186, though their spec
  files read Implemented / ✓ Built. R233 does have a row. `scripts/check-phases.sh` will flag it.

## Previous sync (2026-09-03, latest — R231 report folded into the deck)
date: 2026-09-03T21:04:12Z
direction: no repo I/O — a dev-authored bug report (R231) was handed over in chat and built into the deck
- **R231 — Continue Watching timeout cache poisoning** (`specs/ravilo/requirements/phase-R231-continue-watching-timeout-cache-poisoning.md`,
  committed `e8a11120`). R219's spec said a failed Jellyfin build must serve the previous good cache value;
  the shipped `buildCanonicalContinueList` returned `emptyList()` on its 6 s timeout and wrote that empty
  list into the shared 5-minute SWR cache, blanking Continue Watching on Home, every channel row and
  See-all at once. Return type is now `List<ContinueEntry>?` — `null` = failed (never cached, falls back to
  the prior value even past TTL), empty = genuinely nothing. Compile clean; `linuxX64Test` inconclusive on
  a from-scratch rerun; not device-verified.
- **Deck updated:** new slide **15a** "The spec said it. The code did half of it." after the R202→R219
  chain, and the phase/document counters moved 358→**359** and 153→**154** on the cover, household and
  spec-before-code slides (next free numbers now 187 / R232).
- **Not yet mirrored:** the R231 spec file itself is not in this project's `specs/` copy — pull it on the
  next sync.

## Previous sync (2026-09-03, later — read-only counting pass)
date: 2026-09-03T18:13:38Z
direction: read-only (no copy, no export) — counted the repo to refresh the presentation's stats
- **No files pulled or written to the project.** Every figure in
  `presentation/Jellystructure & Ravilo - Spec-Driven Development.html` was re-counted against `main`
  rather than carried over: **342** Kotlin source files, **9** Gradle modules (8 subprojects + root, from
  `settings.gradle.kts`), **18** route files under `server/routes/`, **27** Kotlin unit-test files (25
  backend + 2 `ravilo-ui` commonTest), **5** Playwright `.spec.ts` files under `tests/e2e/`. Largest
  files by bytes: `ui/MediaDetail.kt` 262 KB · `screens/PlayerScreen.kt` 186 KB · `ui/Settings.kt` 185 KB ·
  `ui/RaviloConfig.kt` 173 KB · `routes/MediaRoutes.kt` 158 KB.
- Counted from this project's own mirror: **153** documents under `specs/` (56 admin phase files, 69
  Ravilo phase files, 18 research reports), **358** STATUS.md phase rows (174 admin + 184 Ravilo), **305**
  design mockup files.
- **Lines of Kotlin (~84k) is an estimate**, not a count — derived from summed file bytes at ~56
  bytes/line, calibrated against three real files. Replace with real `wc -l` output when convenient; it is
  the one figure on that slide nobody has actually measured.
- **Commits shown as "1,300+"** for the same reason: the previous 1,130 could not be re-verified with the
  tools available, and the floor is safe. Worth replacing with a real `git rev-list --count` before the talk.
- Also on that deck: rewrote the Kotlin/Native constraint callout in plain language (was `select()` /
  `fd 1024`), and rebuilt the R202 case-study pair to end on **R219** — deleting R202's line exposed that
  there was no single Continue Watching list at all, which is the better story and the current truth.

## Previous sync (2026-09-03, earlier)
date: 2026-09-03T12:10:31Z
direction: pull (repo → this project) — 31 commits since the 2026-09-02 sync, no new export needed
- **Both our specs shipped as designed. Open question resolved on-device — the copy is confirmed correct.**
  **Phase 185** (✓ Built 2026-09-02, incl. the client-side start timer) and **R222** (✓ Built 2026-09-02)
  are both in `main` now, canonical versions pulled over our drafts. The one deviation: the ceiling is
  **two columns** (`decode_max_bitrate_hevc`/`_h264`, one shared timestamp), not one — found live-necessary
  because R216/R183 force an AVC transcode target, so a single column would silently record the wrong
  codec's ceiling for an HEVC file. **Open question 1 answered on-device 2026-09-02**: R216 has been live on
  the stue TV since 2026-08-30 (105 `playback_qoe` rows carrying its fields, `direct_play=0` on heavy
  sessions, `dropped_frames=0` throughout) — the *Until Dawn* stutter was a Wholphin session, architecturally
  unreachable by any of this. Through Ravilo the file re-encodes and starts slowly; it does not stutter.
  `slow_lead`/`slow_tail_measured`/`slow_tail_expected` are the right copy, unblocked for translation.
  `basis: "measured"` is reachable in practice now (timer built) but unreached on any real device yet —
  needs 3 completed starts of the same file on the same device, and there's been no device access since.
- **181/182/183 all moved from Planned/partial to ✓ Built, with real measurements**, and both surfaced a
  genuine new follow-up bug each — logged for the next unassigned number, not fixed inline:
  - **181**: FR-181-1/1a/2/4/5 built (id set-difference sweep, reverse-diff-to-History, activity-based
    freshness, dead WS listener deleted, narrow dirty-set). FR-181-3 (count reconciliation) deliberately
    **not built** — FR-181-1's full sweep already runs at the fastest cadence a separate count check would,
    making it strictly redundant.
  - **182**: all of §A/§B built. **Live-measured 2026-09-02** (dev-restart authorized): `/api/tv/series/{id}`
    unaffected by a concurrent scan; `/api/tv/home` degrades **~120× at p50** during a scan — root cause is
    `HomeFeedService`'s cache invalidating almost every request because `libraryVersion` (correctly, per
    182's own fix) bumps on every item write. **New candidate follow-up, unfixed**: a home-feed cache keyed
    on a coarser signal than per-item `libraryVersion`.
  - **183**: all FRs built (TMDB token bucket + AIMD, jittered backoff, bounded per-episode fan-out, skip
    already-held metadata, DB-level rate-limit marking via 181's dirty-set, Outbound pacing card). **New
    candidate follow-up, unfixed**: `POST /api/media/{id}/sync` on a 505-episode series reliably
    self-saturates the 4-permit **interactive** `ProcessGate` reserve (not the background one) via a
    completely unbounded ffprobe fan-out in `syncSeriesEpisodes` that 183 never bounded (183 only bounded
    the TMDB fetch in the same loop) — confirmed live, household playback unaffected throughout.
- **7 new dev-authored Ravilo specs pulled**, none requiring a design/mockup change (all Compose-only or
  deploy/CI): **R223** (season-picker focus + rapid-Up scroll-stranding fixes, ✓ Built, deployed to both
  TVs), **R224** (merge Ravilo TV + phone into one universal Play listing, ✓ Built), **R225** (login-screen
  server indicator + `DEFAULT_SERVER_URL` for `ravilo-web`, Planned), **R226** (drop the http/https toggle
  on server setup, infer scheme, Planned), **R227** (profile menu now closable via system Back on mobile,
  ✓ Built), **R228** (a channel empty for this viewer no longer shows as a tile, ✓ Built), **R229**
  (Settings screen phone-width padding + toggle-row squeeze fix, ✓ Built). Also: **R213** (generated
  Baseline Profile) completed generation 2026-09-02 (root cause was a stale `androidx.benchmark` pin);
  **R217** superseded/closed by **R219** (re-pulled canonical, unchanged status); **R216**'s open question 4
  (on-device verification) is now answered by 185's finding above.
- **STATUS.md mirror refreshed.** No `design/**` changes needed this sync — every new spec is
  backend/Compose-only.
- **Next unassigned numbers: 186 / R230.**

## Previous sync (2026-09-02)
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
| app/segments.html, app/segments.js, app/segments.css, app/series-simpsons.js (entry points), app/Segment Editor - Directions.html | **phase-163** (intro & credits editor — shipped 2026-08-13/14; publishing to Jellyfin dropped in dev review, mockups updated 2026-08-27), **189** (markers cannot be moved — ✓ Built; FR-189-2's 1 s stepper + tenths readout and FR-189-5's locked-marker toast **not drawn**), **190** (editor audio — ✓ Built; FR-190-3's honest badge replaces the hard-coded "direct play · no transcode", **not drawn**) |
| app/library.html, app/ravilo-builders.js (Include segment) | phase-172 (music-video filter support — shipped, drawn 2026-08-27) |
| app/media.html (Artwork tab, pagebar Identity card) | phase-173 (current on-disk asset), phase-174 (clear a wrong TMDB match) — both shipped, drawn 2026-08-27 |
| (none — backend/platform only) | 169, 170, 171, 175, 176, R209–R214 (parallel probes, segment process pool, music-video artwork/TMDB, unified scan engine, stale-artwork guards, subtitle delivery, startup performance, image cache busting) |
| ravilo/Ravilo Mobile.html, ravilo-player.js/.css | R177, R179, R180, R181, R182, R184 (autoplay stale position fix — shipped), R188 (Upcoming visibility — shipped, no design change), R191 (single-user sign-out — shipped, no design change) |
| ravilo/ assets/brand, Barna TV Channel Logo.html | R62 brand (no spec yet) |
| ravilo/ravilo-player.js, ravilo-player.css, ravilo-app.js, ravilo/Audio & Subtitles Picker - Same-Language Directions.html | R195 (same-language subtitle picker — shipped) |
| app/towo*.html, app/towo.css, app/settings.html (Towo tab), app/app-shell.js (Towo nav group), claude-console/Dashboard - Direction B.html | **phase-162** (Towo agent control plane — design-authored, shipped 2026-08-11; mockups predate the build's extra settings fields) |
| (none — backend/platform only) | R192/R193/R194 (MediaSession lifecycle, metadata, season artwork — shipped, no design change), phase-160 (scanner numbering fallback) |
| ravilo/Player Loading and Buffering - Directions.html, ravilo/ravilo-player.js/.css | **R218** (player loading/buffering states — shipped 2026-08-28, on-device verified 08-29), **phase-180** (session teardown — ✓ Done), R220 (video-output recovery — reuses R218's STALL; presentation not yet wired), **R237** (per-cause failed-start copy + one *"Still trying…"* line on the cold-start treatment — ✓ Built dev-side, **not drawn here**) |
| ravilo/Ravilo Mobile.html, ravilo/ravilo.css (flag strips) | **R239** FR-R239-7 (`+N` counts every language, mapped or not; a wholly unmapped group still renders its label) — ⚠ Partial dev-side, **mockup sync not done**; FR-R239-3's 12 missing flag assets unbuilt on both sides |
| app/activity.html | phase-182 (Capacity card only — FR-182-9's banner dropped by owner decision), phase-183 FR-183-6/FR-183-5 (Outbound pacing card + run summary) — drawn 2026-08-31, backend built, UI not yet in code; **201** FR-201-10 (MKV track layout health card — **not drawn**) |
| app/media.html, app/series.html (pagebar + Tracks & subtitles + Artwork tab) | **200** FR-200-5 (SUBTITLES flag strip + read-only Sidecar subtitles group), **201** FR-201-11/12 (Fix now banner per title), **192** FR-192-5/6 (logo shown as a logo, not a cropped 16/9 backdrop; nothing-to-show copy), **191** FR-191-5 (metadata-language mismatch card + inline re-pull), **193** FR-193-4 (honest Jellyfin-behind banner copy, inline Sync Jellyfin, suppressed during a scan), **196** FR-196-3/5 (`last_examined_at` labels, explicable skips) — all ✓ Built dev-side, **none drawn here yet** |
| app/index.html (Dashboard) | **192** FR-192-2 (artwork-repair sweep button — **not drawn**) |
| (none — backend only) | R219 (Continue Watching canonical list — explicitly no UI change), 181 (library sync convergence) |
| ravilo/Decode Ceiling Warning - Directions.html | (no spec yet) research report `ravilo-per-device-decode-ceiling-warning-2026-09-02.md`; builds on phase-177 + R216, constrained by R180 FR-RV-ASP1-2. Admin half would land on app/ravilo-users.html |
| ravilo/Ravilo Mobile.html, ravilo/ravilo-app.js, ravilo/ravilo-data.js, ravilo/ravilo.css, ravilo/ravilo-i18n.js, app/ravilo-users.html | **187** (`phase-187-account-photo-and-password.md`) + **R234** (`phase-R234-profile-photo-and-password.md`) — profile photo + change password. Design-authored 2026-09-03, renumbered from 186 / R230 on 2026-09-04, **both `✓ Built` 2026-09-05** (R234 live-verified on stue TV) and canonical specs pulled 2026-09-12. **⚠ The mockups now lead the specs:** the preset-colour row (`AV_PRESETS`/`colorFor`/`setColor` + the Your profile sheet) is dropped per R234 FR-R234-3, and the "Photo and name" row must be relabelled or wired |
| app/ravilo-builders.js (per-channel system rows), app/ravilo-config.html | **R233** (system rows always scoped — Planned; FR-R233-7 removes the `scope` segmented controls, **not yet applied to the mockup**), R143 (introduced `scope`, retired by R233) |
| (none — backend/client-only) | **R231** (Continue Watching timeout cache poisoning), **R232** (series-detail & player D-pad polish), repo **R230** (Skip Credits Off), repo **186** (request-intent lifecycle cleanup — explicitly no new Ravilo UI) |
| ravilo/Focus Detail - Directions.html, ravilo/Focus Detail - Round 2 Directions.html (+ -print copy), ravilo/ravilo-focus.js, ravilo/ravilo.css, ravilo/ravilo-app.js, ravilo/ravilo-i18n.js, ravilo/Ravilo TV.html, app/ravilo-config.html | **202** (`phase-202-focus-detail-config-and-payload.md`) + **R240** (`phase-R240-focus-detail-on-home-rows.md`) — focus detail on Home rows: L ships on, J off behind the switch, delay configurable 0–600 ms. Both `Planned`, design-authored 2026-09-12. Constrained by invariant 11, R216 (no viewer setting), R221 (genres), R236 (focus bridge topology) |
| presentation/presentation-context.md, presentation/observed-issues-2026-08-18.md, presentation/screenshots/ | (not a spec — talk source material; documents R202 as its centerpiece and the R203–R207 triage) |

## Pending export
- **2026-09-06 → 2026-09-12, design-authored — Ravilo focus detail (L + J), its motion, and the
  Jellystructure switch.** Specs: `specs/requirements/phase-202-focus-detail-config-and-payload.md` and
  `specs/ravilo/requirements/phase-R240-focus-detail-on-home-rows.md` (both `Planned`, neither
  dev-reviewed, written 2026-09-12). Mockup build: `ravilo/ravilo-focus.js` (the whole treatment,
  incl. the dwell, the held row band, the animated open/close and live config apply), `ravilo/ravilo.css`
  (`.fdline`, `.jopen`/`.jpanel` + the `jp-open`/`jp-close` animations, Noir overrides, the preview
  chrome's `ms` field), `ravilo/ravilo-app.js` (`fieldsFor()`, the one-target reveal in `focusEl`, the
  same-clock horizontal tween), `ravilo/ravilo-i18n.js`, `ravilo/Ravilo TV.html` (FOCUS picker,
  `?focus=`/`?dwell=`), `app/ravilo-config.html` (Preferences → Focus detail: two switches + the delay
  slider; no longer reloads the live preview), plus the two directions files and the 8-page print copy.
  **One deliberate spec-over-mockup deviation to carry into the build:** 202 FR-202-2 has the *server*
  resolve `focusDetail: "none"|"line"|"rowOpen"`; the mockup still reads both booleans client-side.
- **2026-09-03 — profile photo + password change: the specs are now on `main` and BUILT** (187 / R234,
  both `✓ Built` 2026-09-05), so nothing spec-side is pending. What is still local is the **mockup build**
  in `ravilo/ravilo-data.js`, `ravilo/ravilo.css`, `ravilo/ravilo-app.js`, `ravilo/ravilo-i18n.js`,
  `ravilo/Ravilo Mobile.html` and `app/ravilo-users.html` — and it needs the two corrections above (drop
  the preset-colour row; relabel or wire "Photo and name") **before** it is exported, or the export ships
  a control the shipped spec has deleted.
- **All earlier design/spec work is already on `main`** — the 2026-09-02 decode-ceiling
  mockups + specs (185/R222) and the 2026-09-01 metadata-language/genre work (184/R221) were exported and
  are confirmed present in the repo diff this sync pulled.
- **phase-163 — intro & credits editor** (`Planned`, 2026-08-13): `specs/requirements/phase-163-segment-editor.md`
  + `design/app/segments.html`/`segments.js`/`segments.css`, the `Segment Editor - Directions.html`
  exploration, and the series-page entry points in `series-simpsons.js`. Still not confirmed pushed —
  check on next export pass.

## Sync history
- 2026-09-12: 19 new dev specs pulled (admin 188–201, Ravilo R235–R239) + 8 amended + STATUS.md; **no numbering collision — our 202 / R240 kept their numbers**; 187 + R234 came back `✓ Built` and their canonical specs replaced our drafts; nine design items logged; counters 359 → 387 phases, 158 → 179 documents.
- 2026-09-03: 31 commits pulled — 185/R222 shipped incl. on-device confirmation (R216 live on stue TV since 08-30, copy correct); 181/182/183 all built with real measurements, two new candidate follow-up bugs logged (home-feed cache thrashing, unbounded ffprobe fan-out); 7 new Ravilo specs (R223-R229), all backend/Compose-only.
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
