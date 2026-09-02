# CLAUDE.md — design project notes

This Cosmos project holds the **HTML/CSS design mockups** for **jellystructure**
(`app/`), the **Ravilo** TV companion (`ravilo/`), plus low-fi wireframes
(`wireframes/`). These mirror the `design/` folder of the repo
**github.com/jeggy/jellystructure**.

## How this project syncs with the repo (2-way)
GitHub is the **source of truth**; we layer designs on top of it.

- **Pull (repo → here):** the canonical specs live in the repo under `specs/`. This
  project keeps an exact mirror of `specs/` at its root, plus `CLAUDE.md`. Re-pull
  with the GitHub tools (ref `main`) whenever the specs move.
- **Export (here → repo):** export the whole project; `specs/` and `CLAUDE.md` go to the
  **repo root**, and *everything else* (`app/`, `ravilo/`, `flags/`,
  `wireframes/`, `flags.css`, `scraps/`, `uploads/`, the standalone logo HTML…) goes
  under the repo's **`design/`** folder. Then commit + push.
  **⚠ The export must LEAVE ALONE:** the repo's **`STATUS.md`** (code-owned — a 2026-07-04
  export overwrote it with our stale mirror and reverted ~14 phases; the dev team restored it
  and asked us to never ship it again), **`specs/research-reports/`** (code-owned; re-add
  nothing, delete nothing), and **`scripts/`**. Never delete repo-side spec files our mirror
  lacks — re-pull first instead. After a push the dev team runs `scripts/check-phases.sh` and
  `scripts/check-mobile-css.sh`; keep both green.
- **`STATUS.md` here is a read-only mirror** (single source of truth for phase status lives at
  the repo root, maintained by the dev team). **Re-pull it (ref `main`) whenever you need
  current status** — never edit locally, never export it.

### Spec layout (mirrored at `specs/`)
- `specs/constitution.md` — non-negotiable architecture, language-resolution
  algorithm, config shape, visual system. **Wins on conflict.**
- `specs/plan.md` — source layout, data models, API routes, UI pages, WS protocol.
- `specs/requirements/phase-NN-*.md` — one flat file per admin phase (content only;
  status lives in `STATUS.md`, mirrored at this project's root).
- `specs/ravilo/` — Ravilo's own tree: `constitution.md`, `plan.md`,
  `requirements/phase-R*.md`, plus `SYNC-AUDIT-2026-06.md`.
- `specs/research-reports/` — dated deep-dives (research, not spec; may go stale).

## Where the work stands (read the repo `STATUS.md` for the live table)
- **2026-08-31 sync — both our 2026-08-28 specs shipped, and 5 new dev-authored specs landed.**
  Next unassigned numbers: **184 / R221**.
  - **R218 (player loading & buffering) is `Implemented`** — built the day it was spec'd, Android/Compose
    only, **on-device verified 2026-08-29** (stue TV, Severance S2E4 resume). **Phase 180 (session
    teardown) is `✓ Done`** — the Jellyfin stop call was confirmed against 10.11.11's OpenAPI before any
    code was written (the open question we flagged), and verified live against a real forced 4K/DV/HDR
    NVENC transcode.
  - **R219 — Continue Watching, one canonical list** (`Implemented`, design-authored with the owner
    2026-08-30, supersedes R217). One merged list per (user, visibility scope); Home rows, channel rows
    and See-all are filtered/capped **views** over it. Every Jellyfin fetch pages to `TotalRecordCount` —
    a `Limit` may never act as a cap (that one error caused four separate bugs); the card and the order
    are decided by **timestamp, never by which endpoint an entry came from**; See-all mirrors its row's
    own configured scope, not the page it was opened from. Row cap 30 → **20**. **No UI change** — nothing
    for us to draw.
  - **R220 — black picture, perfect audio, after Home/TV-standby** (`✓ Built 2026-08-31`, not dev-reviewed,
    not reproduced on-device). Frame-count detector plus a 4-rung recovery ladder (re-attach surface →
    seek flush → recreate `SurfaceView` → re-prepare item). **Touches our R218 design:** none of R218's
    three moments fire here (the player reports playing, not buffering, first frame already rendered), and
    FR-R220-5 says a recovery outliving the 400 ms debounce should show R218's **existing STALL**
    presentation — no new copy, no new visual language. The build note admits that signal is **not wired**
    (its open question 7), so a viewer can still sit on a frozen black frame for ~4.5 s with no chrome
    change. **Drawn here 2026-08-31** as frame **E** on
    `ravilo/Player Loading and Buffering - Directions.html` — R218's stall treatment verbatim, new trigger.
  - **181 — converge on Jellyfin's library, don't predict it** (FR-181-2 built; the rest `Planned`).
    Klovn S11E07 sat in Jellyfin for 15 h unnoticed: premiere-year freshness bucketing files a
    currently-airing 2005 show as monthly archive (9 of 16 provably-airing series were starved — now fixed
    via `sonarrNextAiringDate`), nothing ever compares our item set against Jellyfin's, and the
    Jellyfin-based realtime ingest has delivered **nothing, ever** since phase 165 (the WS listener
    subscribes to no message type and `LibraryChanged` is never sent — dead code reporting itself
    healthy). Remaining: an **id set-difference** sweep as the backstop (`DateCreated` is the file's mtime
    and 50% of files carry junk mtimes, so no timestamp watermark is sound), per-series count
    reconciliation, and a persistent dirty-set.
  - **182 — a scan must never stop the server** (`✓ Built 2026-08-31`, not live-verified). The whole
    4-thread scan pool wedged uncancellably for 21 minutes while `/api/tv/**` starved behind one global
    64-permit outbound semaphore — indistinguishable from the server being down. Shipped: locks/atomics
    on the scan write path, per-item deadlines, a cancel that actually cancels, and
    **INTERACTIVE/BACKGROUND partitioning** of `OutboundHttp` (16 reserved) and `ProcessGate` (4 reserved)
    with bounded acquires → 503 + `Retry-After`. **Design item: FR-182-9's Activity-page banner is not
    built** — `GET /api/health` now reports both gates per class, but there is no surface. Needs a
    plain-language banner on `app/activity.html`: "Ravilo requests are queuing behind background work".
  - **183 — pace outbound requests by rate, not concurrency** (`✓ Built 2026-08-31`, not live-measured).
    A 300-episode series fanned out ~1 500–2 000 TMDB requests from a single worker slot, retrying on a
    flat jitter-free 3 s that made every 429'd batch re-converge; an exhausted retry then wrote null
    title/overview and stamped the item as freshly checked. Shipped: a TMDB token bucket with AIMD on 429,
    `Retry-After` + jittered exponential backoff, an episode fan-out gate derived from `scan_workers`, and
    skipping details/credits we already hold. FR-183-6's per-run aggregate is **drawn here 2026-08-31** — an **Outbound pacing** card (rate now,
    the limiter's current ceiling, refusals in the last minute, per host) plus FR-183-5's run-summary line
    "12 fields not fetched: TMDB rate limit", on `app/activity.html`.
  - **This sync's design items, resolved 2026-08-31:** the **Outbound pacing** card and frame **E** are
    drawn; the **Capacity** card stays as read-only reporting; FR-182-9's banner was **dropped** (see
    above). Everything else is backend/platform. Nothing exported to the repo yet.
- **2026-09-02 — research pulled, design pass done, no spec yet.** New repo report
  `specs/research-reports/ravilo-per-device-decode-ceiling-warning-2026-09-02.md`: *Until Dawn (2025)*, an
  82 Mbps 4K DV/HDR10+ REMUX, stuttered on stue TV (decoder rated 60 Mbps) and was abandoned — third
  stutter on that TV in three weeks. The owner wants a per-device "this might not play well" warning on the
  Ravilo detail page. The report finds the measuring half already shipped (**177 + R216**) but three
  blockers: the failing session was **Wholphin** (third-party client, architecturally unreachable), nothing
  persists the ceiling at rest, and the ask reverses **R216**'s "no user-visible setting, ever" plus **R180
  FR-RV-ASP1-2**. Design: `ravilo/Decode Ceiling Warning - Directions.html` — **A + B′ recommended**
  (persist the ceiling and show it on `app/ravilo-users.html` as "picture it can take"; one plain line above
  Play in Ravilo). **Reframe that drives it:** with 177/R216 live the file doesn't stutter, it re-encodes and
  takes ~20 s to start, so the copy is an expectation not a warning — *"Slow to start on Stue TV. Give it a
  moment after you press play."* Badge inputs are the recorded ceiling × the file's bitrate on 177's own 0.9
  predicate and nothing else (QoE/link never feed it, so it can't flicker); per **file** so it rides episode
  rows not the series hero, one badge per Phase 149 combined row; Noir drops the amber tint per R221's
  precedent; no action, not focusable, not dismissible. **C (confirm gate) and D (real numbers) drawn and
  rejected.** Blocked on three answers before a spec: where the file bitrate comes from (`Track` has no
  bitrate field), whether other OEM decoders report honest ceilings, and whether the R216 build is actually
  installed on the stue TV. Prospective **185** (admin) / **R222** (Ravilo).
  **Owner decision same day — the note is backend-computed and history-informed, and B′ is now built into
  the mockups.** The client gets one resolved `playbackNote { device, basis, seconds }` per file per device
  on the detail payload and renders a sentence or nothing — no thresholds, bitrates or ceilings cross into
  Ravilo (R180's rule and the constitution's *server-pushed state only*). Split responsibility keeps the
  flicker out: **the ceiling decides whether the note shows** (deterministic, 177's own 0.9 predicate),
  **that device's own history decides how sure** — `basis:"measured"` yields *"Slow to start on Stue TV.
  The last few times it took about 20 seconds."* (median over that device's recent starts of that file,
  5 s steps, re-derived only on session completion), `basis:"expected"` yields *"…Give it a moment after
  you press play."* History may **never** toggle the note on or off, only choose the sentence. Built into
  `ravilo-data.js` (`playbackNoteFor`), `ravilo-app.js` (`playNoteHTML`, above `.dactions`), `ravilo.css`
  (`.dplaynote` + Noir ink/weight override), `ravilo-i18n.js` (3 strings × en/da/fo) and
  `Ravilo Mobile.html`. **Admin half (A) drawn 2026-09-02** — `app/ravilo-users.html` shows the persisted
  ceiling as a **second line inside each device row** ("picture it can take · up to 60 Mbps · measured
  yesterday"), not a sixth column, because that table is shared with the Admin web sessions and Recently
  watched sections; two honest unknown states (`not measured yet` = no Ravilo session since the update,
  `not measured` = browser never reports), and `.usr-cap` styles. Device names now agree across both
  mockups (Bedroom TV · Chromecast HD is the constrained one, Pixel 8 the phone).
  **Owner answers 2026-09-02:** start-timing history does **not** exist and will be built backend-side;
  the file bitrate should come from jellystructure and will be added if `Track` lacks it — so both are in
  scope for **185** rather than blockers. **Both specs written 2026-09-02, `Planned`, neither dev-reviewed
  — next unassigned numbers are now 186 / R223.**
  - **185 — remember what each device can take, and how long it actually took**
    (`specs/requirements/phase-185-device-decode-ceiling-and-start-history.md`). Three small gaps that
    together make the sentence impossible: the R216 ceiling is computed inside one `PlaybackInfo` call and
    thrown away, `Track` has no video bitrate, and nothing times a start. Adds `decode_max_bitrate` /
    `decode_codec` / `decode_measured_at` on `ravilo_device`, `video_bitrate` on `Track` (from Jellyfin's
    `MediaStreams[].BitRate` via Phase 175's unified ingest, backfilled on next scan), an append-only
    `playback_start_sample` written **only on session completion**, and the resolved `playbackNote` on the
    detail payload. **FR-185-6: one predicate, two consumers** — the note fires on exactly 177's
    0.9 × ceiling comparison. **FR-185-7: history chooses the sentence, never toggles the note** —
    `measured` needs **≥3** samples, `seconds` is their median rounded to 5 s, re-derived only on session
    completion so two page views can't disagree. Two permanent unknown states, and `NULL` ceiling ⇒ no
    note ever. Admin half is FR-185-8.
  - **R222 — one plain line: "slow to start on this TV"**
    (`specs/ravilo/requirements/phase-R222-slow-to-start-note.md`). Render-never-compute; absent field ⇒
    nothing renders, no reserved space, no layout shift. Three strings (`slow_lead`,
    `slow_tail_measured`, `slow_tail_expected`) × en/da/fo, with the three rejected copy candidates
    recorded so they aren't re-proposed. Above `.dactions`, never the meta row; Play must not move;
    episode rows not the series hero; not focusable, no action, no dismissal; Noir drops the tint.
  - **Still open (both specs):** whether the R216 build is actually installed on the living-room TV — if
    it isn't, the transcode fallback isn't live there either, the file genuinely stutters, and "slow to
    start" is a false sentence. Also: whether other OEM decoders report honest ceilings, per-codec vs
    per-device keying of the ceiling, and retention N for start samples.
- **2026-09-01 — two design-authored specs written, both `Planned`, neither dev-reviewed.**
  Next unassigned numbers: **185 / R222**.
  - **184 — choose the TMDB metadata language for a single title** (`specs/requirements/phase-184-choose-metadata-language.md`).
    올드보이's Korean first audio track makes the resolver fetch Korean metadata — correct by the rules,
    wrong for this house. Adds a nullable `metadataLanguage` consulted **above** the resolver (the
    cascade is not modified and its trace stays on screen, dimmed, after a choice), a picker offering
    only what TMDB actually holds for the title with per-language coverage (title · overview · poster
    count), artwork following the same pick via `include_image_language=<chosen>,null`, and a lock with
    the Phase 151/174 preserve shape so no scan can undo it. **Deliberately no new empty-field warnings
    or states** (owner decision — a first draft flagged missing fields and it invented a state the rest
    of the page doesn't have). Per title only: no rules, no per-library default, no global preference,
    no Ravilo work. Design: `app/Metadata Language Override - Directions.html` (Direction A chosen; B and
    C recorded as rejected) — **built into `app/media.html` + `app/detail.css` 2026-09-01**.
  - **R221 — every genre on a media detail** (`specs/ravilo/requirements/phase-R221-all-genres-media-detail.md`).
    The detail hero shows one genre; the library stores a set. Genres get their own labelled row built
    from the AUDIO/SUBTITLES flag-strip pattern, in TMDB's order with the primary one accent-tinted,
    capped at four visible plus a focusable `+N`, **never wrapping on TV**. Each chip opens the R187
    browse page seeded to that genre — the same contract R190 gave cast faces — with Back restoring
    focus to the chip. Row sits above the synopsis so Play doesn't move. Design:
    `ravilo/Media Detail Genres - Directions.html` (Direction B chosen with C's primacy tint; A and C
    rejected) — **built into the Ravilo mockups 2026-09-01** (`ravilo.css`, `ravilo-app.js`,
    `ravilo-browse.js`, `ravilo-i18n.js`, `Ravilo Mobile.html`, plus a canonical per-title genre list in
    `ravilo-data.js`). Noir's amber accent did read as a warning, so its primary chip is marked by weight
    and ink rather than tint. **Biggest open question: whether the Ravilo payload carries the full genre list or
    flattens it — that decides whether this is a UI phase or a backend one.**
- **2026-08-27 sync — 15 new dev-authored specs (admin 169–176, Ravilo R208–R214), all shipped, plus three
  amended specs that change *our* mockups.** Next unassigned numbers: **177 / R215**. Counts now:
  158 admin rows + 164 Ravilo rows = **322 numbered phases**; 46 + 53 phase files + 14 research reports;
  328 Kotlin files; 10 Gradle modules (new: `ravilo-android-benchmark`, `ravilo-web`, `web-static-server`).
  - **Phase 163 (our segment editor) is `✓ Done` — and its §E "publish to Jellyfin" was dropped entirely.**
    The 2026-08-13 dev review probed this house's Jellyfin **10.11.11** live: `GET /MediaSegments/{itemId}`
    is the only MediaSegments operation in its whole OpenAPI document, `POST` returns **405**, and Jellyfin
    takes segments only from server plugins implementing `IMediaSegmentProvider`. Jellyfin *read-in* as a
    candidate source stays (returns empty until such a plugin exists; none is installed).
    **`app/segments.js` updated 2026-08-27** — the Publish button, the "in Jellyfin" column and the
    `pub` state are gone; the sheet's last column is now **Confirmed** (checked-by-me · locked-by-me ·
    a guess), the header chip counts what still waits on you, and the primary action is
    "Take me to what needs me". The deck's segments slide tells the same story. Build notes: `media_segment`
    table replaced the flat blob, `manuallyConfirmed` back-filled onto **`locked`** (not `checked_at`),
    multi-episode files (`partCount > 1`) are permanently unsupported and read "not supported yet".
  - **Admin 169–176, all `✓ Done`:** **169** parallelises `syncSeriesEpisodes`'s per-episode probes to match
    `scanSeries`. **170** gives `detect_segments` its own process-slot pool (`SegmentProcessGate`), makes
    `force=true` respect marker **source precedence**, and logs segment writes to a title's own History.
    **171** fixes music-video artwork paths (`<basename>-poster.jpg` per file, since an artist folder holds
    several) and lets a music video match TMDB like anything else. **172 — filter support everywhere:**
    `"musicvideos"` is now a third value at every binary Movie/Series type-filter site — **updated here in
    `app/library.html`'s top-right picker and `app/ravilo-builders.js`'s Include segment**. **173** — the
    Artwork tab now shows the **asset actually on disk**, not only TMDB candidates (a music video routinely
    has zero); **drawn in `app/media.html` as a "Currently in use" block**. **174 — Clear a wrong TMDB
    match:** clears id + every TMDB-written field + the poster/backdrop **files**, then **locks** the item so
    the next `pull_tmdb` can't re-apply it; **drawn in `app/media.html`** (pagebar `Clear TMDB match`,
    per-asset `Clear`, and the Re-pull ▾ → From TMDB item greying out when locked). **175** unifies plain
    scan / pipeline / webhook ingest onto one engine and makes the freshness cooldown apply to the manual
    Scan button for the first time (567s → 11–60s for the equivalent window); `SCAN_ON_START` now runs the
    full configured pipeline. **176** guarantees an on-disk poster can never disagree with the current match.
  - **Ravilo R208–R214:** **R208** is our episode-rail 30s auto-hide, **renumbered from R196** repo-side
    (the dev tracker had already spent R196) — the stale local `phase-R196-episode-rail-autohide.md` is
    deleted. **R209** external text subs were dropped and PGS double-delivered (161's follow-on).
    **R210–R213 are the 2026-08-26/27 startup-performance cluster:** CIO kept for the WebSocket only with
    `ktor-client-android` for REST (routes around a live "loads forever" bug), session-store caching +
    FontFamily churn, a bounded-size **client-side Home-feed + display-settings cache** (no more shimmer
    or wrong-skin flash on cold start), and a generated Baseline Profile (**R213 is the only `Planned` row**
    — infra built, generation blocked). **R214** — Ravilo's image cache outliving a corrected poster.
  - **No design work is outstanding from this sync** beyond the four mockup updates above; everything else
    is backend/platform.
- (shipped — see the 2026-08-31 entry above) **2026-08-28 — two design-authored specs written for the player wait states (both `Planned`, not yet
  dev-reviewed).** Triggered by `specs/research-reports/ravilo-player-buffering-loading-states-2026-08-28.md`,
  which catalogued **four waiting moments** and found only one of them drawn.
  - **`phase-R218-player-loading-buffering-states.md` (client)** — moment A (session negotiation) unchanged;
    **B (cold start)** gets **Direction B "Grounded"**: black, three-dot brand pulse, title context
    (series · episode title · S/E), indeterminate sweep, the existing `"Loading…"` string; **C (stall)**
    keeps the frozen frame and **raises the transport chrome by itself**, spinner standing in the play
    button's place, so the viewer keeps their position — no centre overlay, no text; **D (seek)** is
    lightest: a spinner inside the scrub tile only. **~400 ms debounce on all three** (a direct play must
    never flash), deepen at **60 s** without changing a word, **one already-translated string**, and
    **no escape hatch** — Back is always available, never prompted, and ends the session.
    Binding constraint: **R180 FR-RV-ASP1-2**, nothing may vary by delivery method.
  - **`phase-180-playback-session-teardown.md` (backend)** — leaving must actually stop the work.
    `stopPlayback` (`tv/PlaybackService.kt:357`) reports the stop but nothing releases an in-flight
    transcode, so Back during a 20 s spin-up leaves NVENC encoding for nobody — invisible background I/O
    that Phase 178's `anyActive()` can't even see. Teardown keyed on the existing per-item
    `playSessionId` (`:218`), converging the client stop, the Phase 110 watchdog and session-supersede
    on one routine, plus a **queued stop** for the abandon-during-negotiation race. **Open first:** confirm
    an active-encoding stop actually exists in Jellyfin **10.11.11**'s OpenAPI before building — phase 163's
    405 is the standing reminder.
  - Design file: `ravilo/Player Loading and Buffering - Directions.html` (canvas; A/B/C cold-start
    directions, chosen stall + seek frames, two phone frames, and a panel answering the report's seven
    open questions). **Direction C "Title card" rejected**, **A "Quiet"** kept as the fallback if copy
    ever fails locale review. Next unassigned numbers now **181 / R219**. (Our teardown spec was **renumbered 179 → 180** on 2026-08-28: the dev team took **179** the same evening for `phase-179-subtitle-sideload-transcode-stall.md` — R183/161's deferred half, reactivated because phase 177 made the highest-bitrate tier transcode far more often; ✓ Implemented. Same collision shape as R196 → R208.)
- **2026-08-20 sync — large pull, 26 new spec files + a new `presentation/` directory we're taking
  over.** Next unassigned numbers: **169 / R208**.
  - **New admin specs (164–168, all `Planned`, dev-authored):** **164** takes `detect_segments` off
    the pipeline's critical path into a de-duplicated two-lane job queue (extends the Phase 109 media
    job queue rather than a second system). **165** replaces the *arr webhook ingest with Jellyfin's
    own Webhook plugin (id-based, fires only once Jellyfin has matched the item — no more 5-min path
    poll); a live delivery failure found mid-implementation added a real end-to-end delivery probe
    (FR-165-7/8) instead of a self-loop test. **166** makes the pipeline schedule any valid cron
    (Daily/Weekly/Every N hours/Custom), shared-parser validated on both save and while typing, with a
    15-min safety floor. **167** ships Docker packaging for internet exposure: `.dockerignore`,
    `fpcalc` in the runtime image, self-hosted fonts (fixes a real CSP break), a new plain-Kotlin
    `ravilo-web` static-server image, and versioned (`MAJOR.MINOR`, starting `1.0`) GHCR publishing
    gated on a GitHub Release — shipped 2026-08-17 with three rounds of real CI/runtime bugs found and
    fixed (a skiko-wasm race, dead Docker layer caching, CI runner OOM/disk). **168** adds filename-only
    Music Video library support (new `MediaKind.MUSIC_VIDEO`, no TMDB ever, no new UI surface — folded
    into the existing "all kinds" views since Movies/Series tabs are slated for removal later anyway).
  - **New Ravilo specs (R196–R207)** — mostly bug fixes from a live screenshot-capture session on
    2026-08-14/18: **R196** is a numbering collision (episode-rail 30s auto-hide, *and* a dev-found
    regression where R195 broke the entire remembered audio/subtitle tier via a stale Compose closure
    — both kept as R196, disambiguated by filename). **R197** reverses an R195 decision: picking a
    version in the picker's level 2 now closes the picker (was deliberately stay-open; felt broken).
    **R198** re-sorts Continue Watching by actual `LastPlayedDate` instead of trusting Jellyfin's own
    sort. **R199** falls back to jellystructure's own scanned episode number when Jellyfin's filename
    parse fails (found immediately after deploying R198). **R200/R201** fix the same underlying focus-
    bridge failure shape (a `FocusRequester` never attached because its target composable was off-
    screen/torn down) — R200 in content-row back-return restore (could permanently strand Down-nav,
    only an app restart recovered), R201 in the season picker (Klovn's 11-season, fully-watched case
    auto-selected the last season, which the picker never scrolled itself to reach). **R202** is the
    project's own case-study bug: a misleading code comment attributed an inherit-mode channel's missing
    Continue Watching row to R59; the user pushed back, `git log -S` traced it to an R05 leftover R143
    mislabeled — now fixed, and written up in `presentation-context.md` as the talk's centerpiece.
    **R203–R207** are small triage fixes (Faroese label typo, My List's missing heading, Loading-text
    not centered, a blank/mic-icon picker row for an unmapped language, and generation-guard + Retry
    hardening on the detail-screen Loading state) — two other triaged items turned out NOT to be bugs
    on closer inspection (TV Guide row-height/blank-row claim, Back-inside-a-channel behavior) and are
    recorded as such rather than silently dropped.
  - **New `presentation/` directory — we will be taking this over.** Mirrored in full:
    `presentation-context.md` (raw material for a talk on the project + its spec-driven method — the
    R202 story is its centerpiece) and `observed-issues-2026-08-18.md` (the triage log behind
    R203–R207), plus `presentation/screenshots/` (57 real 1920×1080 PNGs captured over adb from the
    live Android TV — home, channels, browse/filter, detail, the audio/subtitle picker, Discover, Live
    TV, settings). Not yet built into anything here — next step when we pick this up is turning
    `presentation-context.md`'s suggested structure into an actual HTML deck.
  - **New research reports pulled:** `internet-exposure-docker-packaging-2026-08-17.md` (phase-167's
    investigation), `music-video-support-investigation-2026-08-17.md` (phase-168's).
  - **STATUS.md mirror refreshed** (551 lines now; read-only here per CLAUDE.md).
- **All admin phases through 151 and Ravilo through R183 are ✓ Done** in code (per repo
  `STATUS.md`, ref `main`, synced 2026-07-31). Everything we designed has now shipped:
  **149/R179** (multi-episode files/combined card), **R180/R181** (flag-forward Audio &
  Subtitles picker + default/remembered tracks), and **150/R182** (intro & credits segment
  detection + Skip Intro / Skip Credits, implemented 2026-07-13 incl. cross-episode Chromaprint
  fingerprinting once `fpcalc` landed on the backend).
- **New since our last sync — 8 dev-authored specs pulled (not design work):**
  - **151** — manually-selected images never auto-overwritten (closes the Phase-133 hole on the
    Sync / Re-pull `updateOne` path; extends the lock to clearlogo, season posters, episode stills). ✓ Done.
  - **152** — scanner falls back to filename `(season, episode)` when Jellyfin has no `IndexNumber`. Implemented.
  - **153** — scheduled scan actively repairs an unmatched episode's numbering (writes corrective
    episode NFO + triggers Jellyfin refresh). Implemented.
  - **154** — pre-run dialog to untick slow pipeline steps (e.g. `detect_segments`) **for one run only**,
    nothing written to config. Implemented.
  - **R183** — Dolby Vision playback + decodable transcode fallback (extends R56/R173; DV was R173's
    non-goal and every DV title was force-transcoded to an undecodable Baseline/L4.1 4K stream). ✓ Done.
  - **R184** — auto-advance no longer starts the next episode minutes in (stale position leak). Implemented.
  - **R185** — Continue Watching hides fully-watched titles (Jellyfin `Played`/`PlaybackPositionTicks`
    desync, 62/115 rows corrupted live). Implemented.
  - **R186** — Continue Watching fetch window widened past the global top-20 so channel rows (e.g. DanskTV)
    aren't starved. Implemented.
- Recent landings: Live TV (147 + R177), Seerr pivot (136/137 + R170/R171), request-language
  steering (139 + R172), Workbench query blocks (140), HDR tone-map fix (R173), grid-columns
  config (R174), cover-as-video (144), event-driven pipeline (145).
- **⚠ Repo-side STATUS gap (dev team's to fix, not us):** `STATUS.md` on `main` has no rows for
  admin **152/153/154** or Ravilo **R184/R185/R186** though their spec files exist and read
  *Implemented* — `scripts/check-phases.sh` will flag them. `STATUS.md` is a read-only mirror here.
- (superseded — see the 2026-08-27 entry above) Next unassigned numbers were 164 / R196; `phase-163-segment-editor.md`
  was then design-authored and `Planned`. (2026-08-13 sync: **Phase 162 / Towo shipped** — dev-reviewed and
  built in full 2026-08-11 with three dev-review addenda, verified live against a real Claude account. Only
  spec change repo-side this sync; nothing else new. The build is backend-owned settings, not localStorage,
  and added fields our mockups don't have yet — see the Towo entry below.) (2026-08-10 sync #2: **R195 shipped** — dev-reviewed and built
  the same day; and new **161** — stop double-delivering embedded text subtitles on direct play, ✓ Done —
  which was found *because* R195's two-level picker made the duplication visible as two identical
  "English" rows. Earlier that day: dev took 158/159/160 and R192/R193/R194 — the
  MediaSession trio: R192 release-on-background, R193 rich metadata TV-only, R194 season-poster artwork,
  all Implemented; 158 IMDb ratings dataset, 159 segment-detection accuracy, 160 scanner Jellyfin
  numbering fallback, all Implemented. Our same-language subtitle-picker spec was renumbered
  **R192 → R195**.)
- (previous sync) 155 (age-rating normalization) and R187 (browse page)
  **shipped in code** (Implemented, pulled from repo `main` 2026-08-02). New dev specs pulled this sync:
  156 (Seerr per-user request attribution, Implemented), R188 (Upcoming-calendar per-device
  visibility, Implemented), R189 (Samsung Tizen TV client, M1+M2 build-verified). R190 =
  filter-by-person + Seerr overflow row + admin workbench Cast-or-crew facet (design-authored, was
  drafted as R188 but the dev team took R188/R189 — renumbered to R190). **Now `Implemented`
  (2026-08-02): the dev team adopted our design, dev-reviewed it, and built §A–§D + i18n end to end
  across backend, admin WASM workbench, ravilo-ui/Compose and ravilo-tizen — compile-clean, not yet
  live-tested; one deliberate Tizen omission (§C Seerr overflow row, since Discover is out of the
  Tizen build). Pulled the canonical Implemented spec over our stale `Planned` draft 2026-08-07.**
- **Same-language subtitle picker — `phase-R195-same-language-subtitle-picker.md` (✓ Done / Implemented
  2026-08-10; design-authored 2026-08-09, dev-reviewed and built the same day, not yet on-device tested).**
  Shipped as designed, with three notes: no `flag_br`/`flag_tw` assets yet (text-only fallback, never a
  wrong flag); **audio got the same two-level treatment** (was a §2 non-goal); a null-language cluster
  needed `groupDisplayName()` to fall back to the dominant kind word; and every picker row gained real
  tap/touch handling for the first time (shipped R180 was D-pad-only). Bazarr `hi`-flag plumbing deferred.
  Follow-on: **161** (below). Original brief: R180 assumed one row per language = one choice; the live library says
  otherwise (46.5% of movies / 47.5% of series have a file with 2+ same-language subtitle tracks;
  251 file-instances are identical on every stored field; worst case 32 unnamed tracks in one file).
  Picker becomes **two levels**: level 1 is one row per language, flag beside the native name, with
  a count + **›** arrow only when the language holds several versions — **no arrow means OK selects
  it outright** (the absence of the arrow is the whole cue; no extra badge). Level 2 lists the
  versions with the **flag shown once in the header bar**, never repeated per row, each version
  carrying a one-sentence plain-language line instead of a raw track title; a version shows its own
  flag **only** when its region differs from the header (🇧🇷 on 🇵🇹). Indistinguishable tracks are
  numbered under an **Unnamed** group — copy never says "disc". Backend prerequisites: detect SDH
  (title text + Bazarr `hi`), a region **synonym table** (never substring matching), suppress
  release-provenance duplicates, and **remember the variant, not the language** (fixes R181's
  always-picks-SDH-then-loses-it bug). Exploration: `ravilo/Audio & Subtitles Picker - Same-Language
  Directions.html` (Direction A chosen; B and C recorded as rejected). Not yet dev-reviewed — open
  question is whether R180's "nothing hidden or merged" invariant yields for provenance merging.
- **Bazarr subtitle integration — design-authored `phase-157-bazarr-subtitles.md` (`Planned`, 2026-08-07).**
  Optional Bazarr connection so subtitles never need Bazarr's own UI. Principle: **JS stores nothing**
  — reads Bazarr live and issues commands; Bazarr keeps owning providers, scoring and language
  profiles (mirrored read-only). Surfaces: Settings → Download tools **Bazarr** card (`[bazarr]` TOML,
  path-matched like *arr); a new sidebar **Subtitles** page (`app/subtitles.html` — live queue, wanted
  list, history, providers, read-only profiles) + a dashboard summary card; per-title Bazarr sections
  on the movie **Tracks & subtitles** tab (renamed from "Tracks & order") and the series **Seasons &
  episodes** tab. Actions driven through Bazarr: manual search/download, auto-search, sync-to-audio,
  upgrade, delete, full scan. Not yet dev-reviewed — open question is the exact Bazarr command API +
  how a JS item resolves to a Bazarr radarrId/sonarrId (confirm live before build).
- **2026-07-31 sync:** re-pulled the entire `specs/` tree (68 files) + `STATUS.md` from repo `main`;
  repo was well ahead. Wrote `github.md` as the sync receipt. Design now matches shipped code across
  admin 0–154 / Ravilo R01–R186. See `github.md` for the screen map and details.

## Design constraints to respect
- `design/app/` + `design/ravilo/` mockups are the **visual target** for the
  Kotlin/WASM admin frontend and the **Compose Multiplatform** Ravilo app
  (Android TV · phone · web). Real frontend styling is **Tailwind** (admin, scanned
  from Kotlin) / Compose tokens (Ravilo); our CSS-variable system is the *visual* spec.
- Admin frontend is **DOM-based** (kotlinx.browser) — no Canvas, no Compose for Web.
- **Frontend renders server-pushed state only** — no derived/optimistic state.

## Admin visual system
- Dark-primary with a three-way **Light / Dark / System** picker (default System).
  Single **Aurora** direction (cinematic, glassy, gradient); theme persisted in
  localStorage `js-theme` by `app/app-shell.js`. Purple→blue accent. Phase 58 retuned
  the dark surfaces ("Soft Charcoal").
- Type: **Space Grotesk** (display) · **Sora** (UI) · **JetBrains Mono** (code/IDs).
- Tokens: `--ok` resolved/success · `--warn` dirty/mixed · `--bad` error.
- Shared `app/wf.css` (tokens + components) + `app/app.css` (shell) + per-page
  `app/detail.css` (media/series) and `app/metadata.css`. `app/app-shell.js`
  injects the sidebar, mobile drawer, ambient scan dock, floating **Triage dock**,
  and ⌘K command palette.

## Admin screen set (current)
- **Towo** (`towo*.html`, Phase 162 — **✓ Done / shipped 2026-08-11**; our mockups are the visual target
  but now lag the build, see the delta note at the end of this entry) — a control plane for
  **Claude Code** sessions running on machines we own; unrelated to media. Feature-flagged **off by
  default** in Settings → **Towo** (`js-towo` in localStorage, read by `app-shell.js`, which shows/hides
  the sidebar **Towo** group: Overview · Approvals · Runners). Screens: `towo.html` (overview — runner
  shelf + quota + sessions grouped by folder), `towo-sessions.html`, `towo-session.html` (live
  transcript: one-line tool cards, streaming turn, composer, editable turn cap, and idle / awaiting-
  approval / paused-on-quota / picked-back-up states), `towo-session-new.html`, `towo-approvals.html`
  (desktop queue + phone notification and sheet), `towo-runners.html`, `towo-runner-new.html`
  (enrollment: one pasted `npx` command), `towo-limits.html`. Styles in `app/towo.css`, scoped under
  `.towo` over `wf.css` tokens. **Principles:** usage is Claude's own two refilling allowances (100% per
  5 hours, 100% weekly) — **no money, budgets or cost anywhere**; the runner's `--root` *is* the
  configuration, so folders are **discovered, never declared**; Claude's sign-in stays on the host;
  **idle is a normal resting state** you continue by typing; only quota pauses ever auto-resume, and
  only when armed per session (default off, 6-hour sweep). Turn caps are two-level: a global default in
  Settings, overridable per session at start and mid-run. Spec:
  `specs/requirements/phase-162-towo-agent-control-plane.md`; research report
  `specs/research-reports/claude-code-remote-agent-management-2026-08-10.md`; rejected overview
  direction kept at `claude-console/Dashboard - Direction B.html`.
  **Design caught up to the shipped build 2026-08-13** (settings fields, `mirror_error` chip, offline banner — all drawn). **Deltas the build introduced:** Towo settings are
  **backend-owned** (`towo_settings`), not localStorage — only `js-towo` stays client-side; Settings gained a
  **low-quota threshold %**, a **permission-request timeout + reason** (default 30 min) and a **"where runners
  connect" URL** override, and the five notification toggles now genuinely fire. Auto-continue is
  control-plane-owned. **No runner-detail screen was built**, so the new `mirror_error` (transcript-mirror
  failure) warning chip rides the **runners list row**, not §B's detail page (drawn there too). The session
  view gained a live **"runner offline" banner** (drawn, with its own preview state); a decision made while the runner is down is **queued and redelivered** rather
  than failing; a timed-out request records `decision = "timeout"`. Remaining code polish: a resumed session
  loses its permission profile (falls back to Normal).
- **Intro & credits editor** (`segments.html` + `segments.js` + `segments.css`, Phase 163 design) — one
  fullscreen tool, no sidebar, opened from a season / episode row / **movie detail** and returning there.
  Reached via a dedicated **Intro & credits** tab — its own tab, not a card in Overview or a link in a
  toolbar — on both `media.html` (→ `segments.html?movie=<slug>`, straight into the trim view with a
  films-to-check rail) and every series page (→ the season sheet).
  **Season sheet** (front door): every episode on one aligned timeline so an outlier sticks out, season
  stats incl. **what the season agrees on** (median intro), multi-select bulk apply/lock/re-detect,
  drawer with player per row. **Trim view** (behind it): Jellyfin playback in the admin (direct play or
  nothing — never transcode for this), timeline with draggable handles, **waveform + evidence lane**
  (black frames, silences, the fingerprint's matched span, chapter marks, Jellyfin's own segments),
  per-marker rows with steppers/loop-the-cut/**per-marker lock**, review-queue rail, keyboard
  (`I`/`O`/`,`/`.`/`L`/`↵`). Five kinds: recap · intro · next time · credits · after-credits.
  Jellyfin is a **source only** — its own segments read in as one candidate; **publishing was dropped**
  in the 2026-08-13 dev review (Jellyfin 10.11.11: `POST /MediaSegments` → 405, plugins only). What a human
  confirms (checked or locked) is still the thing that matters — it just never leaves jellystructure.
  Locks are per marker and `detect_segments` never overwrites them (phase-151's guarantee, extended).
  Exploration: `Segment Editor - Directions.html` (A+C shipped as one tool; **Direction B rejected** —
  its CSS is kept in `segments.css` under an exploration-only comment so the directions file still renders).
- **Subtitles** (`subtitles.html`, Phase 157 design) — global **Bazarr** overview (**no left-nav item**;
  reached from the dashboard summary card): live queue/tasks, wanted list (filter by kind + language),
  download/sync/
  upgrade/remove history, provider health, read-only language profiles. Companion: a dashboard summary
  card + per-title Bazarr sections on `media.html` (Tracks & subtitles tab) and `series.html` (Seasons
  & episodes). Bazarr connection lives in Settings → Download tools. JS persists no subtitle state.
- **Dashboard** (`index.html`) · **Library** (`library.html` — audio-track filter,
  multi-axis filters, multi-language search, infinite scroll, shared filter
  workbench) · **Activity** (`activity.html`).
- **Metadata** (`metadata.html`) — Studios · Networks · Genres · Tags (JS-tag color
  **swatches** + dotted chips, Phase 82) · **Age ratings** (design-complete 2026-07-31, no spec
  yet): maps every raw certification (G, TV-MA, “Från 15 år”, Btl…) to a normalized age 0–18 —
  ladder summary, per-cert stepper (write-through pulse), unmapped-cert triage (NR/Btl → treated
  as 18 in filters/kids gating — never shown as an 18+ badge — and listed when no range is set).
  Feeds Ravilo’s Maturity filter, which shows only numbers
  (`0+ · 7+ · 13+…`, `normAge()` in `ravilo-browse.js`). **Spec: `phase-155-age-rating-normalization.md`
  (shipped/Implemented in code, synced 2026-08-02).**
- **Settings** (`settings.html`) — URL-addressable **tabs** (Phase 55): Connections ·
  Libraries · Metadata · **Download tools** (Radarr/Sonarr + Seerr + **Bazarr** subtitles + cross-seed) ·
  Notifications · Advanced · **Users & devices** (Phase 143 design: per-user Ravilo
  devices + admin web sessions, revoke / sign-out-everywhere).
- **Live TV** (`livetv.html`, Phase 147) — surfaces Jellyfin's Live TV into Ravilo: master
  enable toggle, connection/status badge + Test connection + Refresh from Jellyfin, a channel
  lineup (show/hide · number · logo · order · category, keyed by Jellyfin **channel id**),
  lineup-change diffing (new→hidden · removed→unavailable), and EPG source + refresh cadence.
  **Read-only** against Jellyfin (never touches the tuner). Lives in the sidebar's **Ravilo**
  group (Phase 148 nav restructure, which renamed "Apps" → **Ravilo** and split the config editor).
- **Movie detail** (`media.html`) / **Series detail** (`series.html`) — the single
  editing surface. **Write-through** editing (Phases 71/74): edits commit to disk
  immediately; the split button is **Save → NFO / Sync Jellyfin / Save & Sync** (the
  old staged "Save changes" + amber dirty borders are gone). Tabs: Tracks & order /
  Seasons & episodes · **Artwork** (manager + lightbox, Phases 47/48/71/81) ·
  **Cast & crew** (Phases 75/76/79/80; `Season Episode Cast.html`, `series-cast.js`) ·
  NFO raw viewer (Phase 44) · History. Pagebar shows **audio-language flags** (Phase
  87, via `flags/` + `flags.css`), TMDB-id edit, external links, drift banner,
  Jellyfin field-lock banner, **★ Feature in Ravilo**. **Login** (`login.html`).
  **Multi-episode files** (Phase 149, design-complete / awaiting code): Seasons & episodes
  renders **one combined row per file** (`S01E01–E03` · combined runtime · "3 in 1 file" badge ·
  triptych thumbnail, expandable to per-episode chapter offsets), and the Artwork tab picks
  stills **per episode** with a file switcher — target `series-johnnybravo.html`.

## Ravilo companion app (`ravilo/`)
- **Ravilo TV** (`ravilo/Ravilo TV.html` → `ravilo-app.js` + `ravilo.css`) — the TV UI:
  Home (hero carousel, channel rail, content rows), Movies/Series/My List grids,
  Search, Movie/Series **detail** (merged audio+subtitle flag line R75/R78/R134, cast &
  crew R81), **Discover** (profile-hub nav R170; Coming Soon + Seerr **Request** tab
  R171), Player. The in-player **Audio &amp; Subtitles** picker is **flag-forward** (Direction 2):
  a flat list where a country flag + plain native language name anchor every track, with
  jargon-free badges only — **Default · Surround 5.1 / Stereo · Signs only** (forced) ·
  **Sound described** (SDH) · **Describes action** (audio-description) · **Commentary** — no
  codec names and no delivery-method cues (`ravilo-player.js` `renderPicker`/`PL_KIND`/`plFlag`/
  `plBadges` + `ravilo-player.css`; track data in `ravilo-app.js` `tracksFor()`; exploration in
  `Audio &amp; Subtitles Picker.html`). **R195 (Planned) makes it two-level** — languages first,
  versions inside — see the phase list above. **Browse page** (**shipped/Implemented in code**, spec
  `specs/ravilo/requirements/phase-R187-browse-page.md`; admin half `phase-155`, also shipped):
  Movies/Series nav + an end-of-row **→ See all** tile (rows with &gt;8
  items, incl. Continue Watching + channel-scoped rows) open a shared browse page
  (`ravilo-browse.js`, Direction A: top facet bar → checklist popover). A **cast/crew face** on a media detail is now a link into this page seeded to that person's
  filmography (R190, `phase-R190-people-filter.md`, Planned): the browse facets narrow within it, and
  when Seerr is enabled a single **⚡ Seerr overflow row** ("More with {name} · request on Seerr")
  sits below all library results, opening the normal Seerr request flow (Back returns to the person
  page). The same person filter is a **Cast or crew** facet (People group) in the admin
  workbench (`ravilo-builders.js`, R190 §D). Facets Genre · Type ·
  Maturity · Year · Watched · Audio · Channel · Quality — multi-select OR within a facet, AND
  across facets, stacking on the row seed (breadcrumb + "Úr …" subtitle, no seed chip); popover
  values sort by count desc then A–Z. **Maturity is a D-pad range picker**, not a checklist:
  From / Up-to rows over the normalized age ladder (◂ ▸ adjusts, OK confirms, ladder viz
  highlights the span) expressing ≤7, 7–12, 15+, 4–14 — any integer bounds 0–18; chip shows the range
  label ("≤ 7", "7–12", "15+"). No/unmapped rating ⇒ treated as 18 (never badged). Sort defaults to Recently added (A–Z · Z–A · Year ·
  Maturity · IMDb). Popover owns the D-pad via capture keys (langPicker pattern); exploration in
  `Ravilo Browse - Filter UI Directions.html`. **Live TV** (`Ravilo Live TV.html` → `livetv-app.jsx` · `livetv.css` ·
  `livetv-data.js`, R177) is woven into Home — an **“On now”** row → full EPG guide, channel
  zapping + number entry, Now/Next overlay, and a live player; **never a top-nav tab**
  (placement configured in `ravilo-config.html`, Phase 147 §F). Three skins
  (Aurora/Midnight/Noir). `ravilo-i18n.js`
  (en/da/fo). `Ravilo Mobile.html` + `mobile/` = the Android **phone** target (R60).
  Series detail groups **multi-episode files** into one combined **triptych** card (R179,
  Option B, design-complete) on both TV and phone — the phone detail gains an Episodes section.
- **Ravilo config editor** (`app/ravilo-config.html`) — the Jellystructure-side editor
  for a viewer's TV layout. Has: **Global vs per-user scope switcher** (R51), Home
  hero carousel (single global height 40–100% + auto-advance, R58), Channels &
  collections, Content rows (system rows = Continue + Newly Added, R54/R61),
  **Top 10 / Discover** lists (R50), Behaviour (skin, tile shape, ui-lang),
  **Pair-a-TV** modal + **sticky** action navbar (R57), live preview iframe. TV sign-in
  is now the R175 **username/password login** (pairing code removed from the TV mockup).
- **Shared filter workbench** powers filters everywhere: `app/ravilo-builders.js`
  (+ `ravilo-builders.css`) exposes `window.RaviloBuilders` and is loaded by **both**
  `ravilo-config.html` and `library.html` (R32). Facets = **Studio · Network · Genre ·
  Tag · Age rating · Audio track · Cast or crew** (People group, R190 §D) plus the contextual
  Ravilo-layout facets (Hero, Content row). Library ↔ Ravilo
  round-trip: `⚙ Add filter` opens the builder; `Save filter as… → Channel / Content
  row`; per-poster `★ Save as hero item`.
- **Brand (R62):** the Ravilo **brand** mark/asset pack recolors to a
  **Jellyfin-inspired palette** — gradient `#AA5CC3 → #00A4DC` on `#000B25` navy,
  "lit-mark" treatment (`ravilo/assets/brand/*.svg` masters → `assets/android/**` +
  `store/**`; `Ravilo - Android TV Assets.html`). The **in-app Aurora UI accent**
  (focus rings/buttons in `ravilo.css`, still purple `#7b6ef0`) is a **separate token
  set and is intentionally unchanged** by R62.

## Config shape
`[[libraries]]` are auto-discovered from the Jellyfin API (no static `[paths]`).
Optional `[qbittorrent]` (cross-seed guard), `[radarr]`/`[sonarr]` (read-only
root-folder import + best-effort rescan), and per-user Ravilo layout/discover blocks.
