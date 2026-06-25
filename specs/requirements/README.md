# Requirements

The **"what"** — one file per development phase. This table is the **single source of truth** for
which phases exist and whether each is done or planned.

- **Active / planned** phases live directly in this directory.
- **Completed** phases are archived in [`archive/`](archive/).
- [`_investigation-findings.md`](_investigation-findings.md) — cross-cutting findings (SQLite live,
  studio/network not populated, TMDB routing constraints) that several active specs depend on. **Read
  before starting phases 19–25.**

See [`../STATUS.md`](../STATUS.md) for current focus, recent work, and open issues.
See [`../tasks.md`](../tasks.md) for the active TODO breakdown.

## Phase index

| Phase | Status | Focus | Spec |
|-------|--------|-------|------|
| 0 | ✓ Done | Scaffolding — Gradle KMP, Docker Compose, native binary + WASM bundle | — |
| 1 | ✓ Done | Config, DB, auth (Jellyfin sign-in, session cookie, admin gate), library mapping | — |
| 2 | ✓ Done | Jellyfin-driven discovery, ffprobe track data, TMDB match, Library + Media Detail UI | — |
| 3 | ✓ Done | NFO write (movie + tvshow), artwork download, language resolver, language settings UI | — |
| 4 | ✓ Done | Track editing (mkvpropedit/ffmpeg), triage queue, live WebSocket scan, Jellyfin refresh | — |
| 5 | ✓ Done | Folder watcher, Playwright E2E tests, fixture builder, series episode tab | — |
| 6 | ✓ Done | Per-episode TMDB metadata, episodedetails.nfo, episode stills, per-episode track editing | — |
| 7 | ✓ Done | Persistent scan state + resume (FR-S1) | [archive](archive/phase-07-persistent-scan-state.md) |
| 8 | ✓ Done | Three-way theme picker — Light / Dark / System (FR-T1) | [archive](archive/phase-08-three-way-theme-picker.md) |
| 9 | ✓ Done | Per-field dirty indicators + diff popup (FR-D2) | [archive](archive/phase-09-dirty-indicators-diff-popup.md) |
| 10 | ✓ Done | External links — Jellyfin + TMDB (FR-X1) | [archive](archive/phase-10-external-links.md) |
| 11 | ✓ Done | Language pickers — searchable dropdowns (FR-L1) | [archive](archive/phase-11-language-pickers.md) |
| 12 | ✓ Done | Simplify TV series language UI (FR-U1) | [archive](archive/phase-12-simplify-series-language-ui.md) |
| 13 | ✓ Done | Sync single media item (FR-S2) | [archive](archive/phase-13-sync-single-media-item.md) |
| 14 | ✓ Done | Persistence layer — SQLite/SQLDelight (FR-P1) | [archive](archive/phase-14-persistence-sqlite.md) |
| 15 | ✓ Done | Library path-match diagnostics + path-check (FR-B1) | [archive](archive/phase-15-library-path-matching.md) |
| 16 | ✓ Done | Multi-worker scanner — dynamic workers + thread pool (FR-W1) | [archive](archive/phase-16-multi-worker-scanner.md) |
| 17 | ✓ Done | Activity log backend — persistent log, filters, live runners (FR-A1) | [archive](archive/phase-17-activity-log-backend.md) |
| 18 | ✓ Done | Settings page cleanup — nav fix, section reorganisation (FR-C1) | [archive](archive/phase-18-settings-page-cleanup.md) |
| 19 | ✓ Done | Studios, Networks, Genres & Tags metadata page (FR-M1) | [archive](archive/phase-19-metadata-page.md) |
| 20 | ✓ Done | Library audio-track filter — find audio-description/commentary tracks (FR-LF1) | [archive](archive/phase-20-library-audio-track-filter.md) |
| 21 | ✓ Done | Flag multiple default audio tracks in Triage (FR-DA1) | [archive](archive/phase-21-multiple-default-audio-triage.md) |
| 22 | ✓ Done | Remove lockdata & detect Jellyfin field locks (FR-LK1) | [archive](archive/phase-22-remove-lockdata-detect-locks.md) |
| 23 | ✓ Done | Remove the Language page (FR-RL1) | [archive](archive/phase-23-remove-language-page.md) |
| 24 | ✓ Done | Manage the TMDB ID field on Media Detail (FR-TI1) | [archive](archive/phase-24-manage-tmdb-id.md) |
| 25 | ✓ Done | Fix Sync button → "Re-pull from Jellyfin…" (FR-RJ1) | [archive](archive/phase-25-repull-from-jellyfin.md) |
| 26 | ✓ Done | qBittorrent seeding guard — block mkvpropedit when file is actively seeded (FR-QB1) | [archive](archive/phase-26-qbittorrent-seeding-guard.md) |
| 27 | ✓ Done | Merge Triage into Media Detail + floating Triage dock (FR-MT1) | [archive](archive/phase-27-merge-triage-into-media-detail.md) |
| 28 | ✓ Done | URL-based tab & view-state navigation (FR-UN1) | [archive](archive/phase-28-url-based-tab-navigation.md) |
| 29 | ✓ Done | Multi-language library search — remember every title ever pulled (FR-ML1) | [archive](archive/phase-29-multi-language-library-search.md) |
| 30 | ✓ Done | Library multi-axis filters — studio/network/genre/tags dropdowns + meta-facets (FR-LMF1) | [archive](archive/phase-30-library-multi-axis-filters.md) |
| 31 | ✓ Done | Studio & network logo artwork — fetch/cache/serve + batch (FR-SNA1) | [archive](archive/phase-31-studio-network-artwork.md) |
| 32 | ✓ Done | In-app TMDB match picker — search & pick the right match (FR-TM1) | [archive](archive/phase-32-tmdb-match-picker.md) |
| 33 | ✓ Done | Jellyfin ⇄ NFO drift detection + re-assert (FR-DR1) | [archive](archive/phase-33-jellyfin-nfo-drift.md) |
| 34 | ✓ Done | Undo / revert from history (FR-UR1) | [archive](archive/phase-34-undo-revert-history.md) |
| 35 | ✓ Done | System health panel — real test-connections + tool/disk checks (FR-HC1) | [archive](archive/phase-35-system-health-panel.md) |
| 36 | ✓ Done | Operator controls — per-library scan/push, scheduled scans, notifications (FR-OC1) | [archive](archive/phase-36-operator-controls.md) |
| 37 | ✓ Done | Surface the qBittorrent seeding guard (FR-QS1) | [archive](archive/phase-37-surface-qbittorrent-guard.md) |
| 38 | ✓ Done | Command palette ⌘K + attention-queue keyboard nav (FR-KB1) | [archive](archive/phase-38-command-palette-keyboard-nav.md) |
| 39 | ✓ Done | Subtitle management — forced flag toggle on subtitle tracks, MKV only (FR-SUB1) | [archive](archive/phase-39-subtitle-management.md) |
| 40 | ✓ Done | Configure qBittorrent in Settings — guard opt-in; off ⇒ no cross-seed safety (FR-QC1) | [archive](archive/phase-40-configure-qbittorrent-settings.md) |
| 41 | ✓ Done | Merge track-order page into Media Detail "Tracks & order" tab — unified movie editor + warnings (FR-TO1) | [archive](archive/phase-41-merge-track-order-into-detail.md) |
| 42 | ✓ Done | Per-episode track & order editor on Series detail — same editor in a modal (FR-TO2) | [archive](archive/phase-42-series-episode-track-editor.md) |
| 43 | ✓ Done | Library page: design fidelity (Movies/TV switch) + infinite virtual scroll replacing pagination (FR-LV1) | [archive](archive/phase-43-library-fidelity-infinite-scroll.md) |
| 44 | ✓ Done | NFO raw viewer — read-only on-disk XML with a multi-file tree sidebar (FR-NR1) | [archive](archive/phase-44-nfo-raw-viewer-tree.md) |
| 45 | ✓ Done | Fix the track-editor language picker — unstyled `.langmenu` popup; unify on the shared styled picker (FR-LP1) | [archive](archive/phase-45-track-editor-language-picker-css.md) |
| 46 | ✓ Done | Track language writes must persist — 2↔3-letter code mapping, verify-after-write, truthful response + preview (FR-TL1) | [archive](archive/phase-46-track-language-write-persist.md) |
| 47 | ✓ Done | Artwork manager on Movie & Series detail — asset rail + inline TMDB gallery, resolved-language fallback (no-language ≠ All), drag-drop/upload/URL, season posters + episode stills (FR-AM1) | [archive](archive/phase-47-artwork-manager.md) |
| 48 | ✓ Done | Artwork manager — fix the Textless / With-text filter: it only re-sorted (a no-op within a language bucket) and overlapped the language chips; unified both into one real single-select filter on TMDB `iso_639_1` — `All · Textless · With text · <langs>` (FR-AM2) | [archive](archive/phase-48-artwork-textless-filter.md) |
| 49 | ✓ Done | Full episode coverage for large series — the scan probed only a spread of 100 episodes (>100-ep series showed scattered gaps in Seasons & Episodes + Artwork, with no UI recovery); make the cap configurable (`scan_episode_cap`, default 0 = unlimited) + wire an on-demand uncapped "Re-scan all episodes" (FR-EP1) | [archive](archive/phase-49-full-episode-coverage.md) |
| 50 | ✓ Done | Fix Jellyfin item-refresh auth + single-item fetch — `refreshItem`'s `Authorization` header was malformed (missing `, Token=`) → 401 on every targeted refresh; `getItem` used the unreliable non-user-scoped `/Items/{id}` endpoint and the client had no response validator, so a non-2xx `text/plain` body threw `NoTransformationFoundException` (400). Routed all calls through one `jellyfinAuth` helper, added a 2xx-gated `bodyOrNull`, re-pointed `getItem` to the proven `/Items?Ids=` list shape, and returned the refresh outcome from `pushToJellyfin` (FR-JR1) | [archive](archive/phase-50-jellyfin-refresh-auth-fix.md) |
| 51 | ✓ Done | Tag population & merge lifecycle — item tags were never read from Jellyfin (`Tags` field unrequested, no DTO slot, scanner left `tags=[]`) and the Phase 19 §15 merge was unimplemented (full re-scan wiped even JS tags). Now: full scan = Jellyfin `Tags` + JS; re-pull from TMDB = TMDB **keywords** + JS; re-pull from Jellyfin = union; JS-defined tags always survive (FR-TG1) | [archive](archive/phase-51-tag-population-lifecycle.md) |
| 52 | ✓ Done | Tag UX — sync the media-detail tags section to `design/app/media.html` (own card, manage-tags link, dotted JS chips, outline dirty ring) + distinguish JS vs normal tags in the filter workbench & Library pickers (server-colored facet, dotted + grouped). Also corrected the stale "Tailwind" note: the app ships `wf.css`/`app.css` verbatim via `syncDesignAssets` (FR-TG2) | [archive](archive/phase-52-tag-ux-design-sync.md) |
| 53 | ✓ Done | Scanner data-quality fixes — from a post-reset full-sync review: full-scan `year` was null everywhere (now `searchYear = name ?? ProductionYear` for search/slug, stored year prefers TMDB); `slugify` made empty/colliding ids that silently dropped items (non-Latin → `""`, dup titles collide) → `itemId` falls back to `jf-<jellyfinId>` + `disambiguateIds` de-dupes collisions; widened the `SxxExx` season regex (`S2025E01`); scan now logs a skipped/unmatched-items summary; minor (zero-track-episode warn) (FR-SQ1) | [archive](archive/phase-53-scanner-data-quality.md) |
| 54 | ✓ Done | Configure Radarr & Sonarr in Settings — opt-in, read-only `[radarr]`/`[sonarr]` integrations (default off): read root folders → import as `[[libraries]]`, and best-effort `RescanMovie`/`RescanSeries` after JS writes. No acquisition/mutation; never blocks a write; api-key masked + `##KEEP##` like qBittorrent (FR-AR1) | [archive](archive/phase-54-configure-radarr-sonarr.md) |
| 55 | ✓ Done | Settings as URL-addressable tabs — convert the long-scroll Settings into 6 `?tab=` panels (Connections · Libraries [mapping+scanning] · Metadata · Download tools [Radarr/Sonarr+cross-seed] · Notifications · Advanced) via the Phase 28 `replaceState` Router; drop the scroll-spy; health-check failures aggregate to per-tab badges + switch-to-tab (FR-ST1) | [archive](archive/phase-55-settings-tabbed-navigation.md) |
| 56 | ✓ Done | Radarr/Sonarr acquisition pipeline + unified download-status state machine — request a not-in-library title (add+search) and track it through one shared `AcquisitionStatus` enum: `requested` (not yet at the download client) → `queued` (in the client queue) → `downloading` (% + `stalled`/`metadata` flags) → `importing` → `available` (+ `failed`); `*arr` queue is source of truth, qBittorrent enriches; `acquisition_changed` WS events; request+track only (FR-AQ1) | [archive](archive/phase-56-arr-acquisition-pipeline.md) |
| 57 | ✓ Done | Chart/Discover feed ingestion (vendor-abstracted; Netflix via Tudum first) — `ChartProvider` abstraction + `ChartEntry` model; country movies/TV (rank-only), global movies, non-English, all-time (views); weekly history for trend badges; TMDB-resolve + library-match at ingest; country feeds carry no views by construction (FR-CH1) | [archive](archive/phase-57-chart-discover-ingestion.md) |
| 58 | ✓ Done | Dark-theme comfort palette ("Soft Charcoal") — retune the **dark** surface/ink tokens only: lift the near-black floor (`--bg #0b0c12 → #16181f`), dim ink to ~88% (`--ink #eef0f7 → #dde1ec`) to kill glare, and raise card surfaces (`--card-bg`) so panels still read as elevated. Light theme, accent + status tokens, and all per-screen CSS untouched; retunes from one `:root` edit (FR-DK1) | [archive](archive/phase-58-dark-theme-comfort-palette.md) |
| 70 | ✓ Done | Brand mark "Quartet Play" + app logo wiring — replace the placeholder CSS glyph with a real SVG mark (2×2 structure grid whose open slot resolves into a play triangle) on the Aurora gradient; wired into the sidebar, mobile topbar and login, with a full asset set (icon sizes, favicon, mono, lockups, OG). No new color tokens; theme-independent (FR-BR1) | [archive](archive/phase-70-brand-mark-quartet-play.md) |
| 71 | ✓ Done | Detail-page **write-through editing** + **artwork lightbox** — drop the stage→apply model on Movie/Series detail: artwork candidates and track flag/order edits now write to disk immediately (toasts convey `mkvpropedit` ~40ms vs `ffmpeg -c copy` remux cost; **Save & sync to Jellyfin** pushes, else Jellyfin picks up on next scan). The "Staged changes" card is removed. Artwork gallery gains a hover ⤢ **lightbox** — large preview + metadata, ←/→ to browse, **Use this artwork** writes (FR-DE1) | [archive](archive/phase-71-detail-write-through-lightbox.md) |
| 72 | ✓ Done | Canonical language-code equivalence across all comparisons — fix the false **"Default ≠ resolved"** cascade (raw `eng` track tag compared to normalised `en` → mis-fires on nearly every tagged movie). One `LanguageResolver.sameLanguage()` routed through every language equality check (cascade, triage mismatch, majority/mix, filters); comparison/display-only (FR-LC1) | [archive](archive/phase-72-canonical-language-equivalence.md) |
| 73 | ✓ Done | **Write-through metadata sync to Jellyfin** — `PATCH …/metadata` saved to DB only, never writing the NFO or refreshing Jellyfin; fix launches `pushToJellyfin` after `store.updateOne`. **⚠ Trigger model revised by Phase 74** (design): the PATCH should persist to DB only, with the NFO write moved to the explicit Save action (FR-WM1) | [phase-73](phase-73-write-through-metadata-sync.md) |
| 74 | ✓ Done · design | **Metadata write-through to the library DB; Save→NFO / Sync→Jellyfin** — title/year/plot/director/studio + tags write **directly to the DB** on edit (toasts), dropping the **"Save changes"** button, amber dirty borders and the diff popup (supersedes Phase 9). The top split button is reframed into **Save → NFO** (write our setup to the .nfo files), **Sync Jellyfin** (ask Jellyfin to re-read them), and a **Save & Sync** primary. Revises Phase 73's per-PATCH push: the PATCH persists to DB only (FR-WM2) | [archive](archive/phase-74-metadata-write-through.md) |
| 75 | ✓ Done · design | **Cast & crew — TMDB people, detail editor, NFO sync + person images** — fetch cast/crew from TMDB credits; new **Cast & crew** tab on Movie + Series detail (cast grid with drag-reorder + editable role, crew grouped by department, TMDB person-search add). Write-through (Phase 74): DB on edit, **Save → NFO** writes `<actor>`/`<director>`/`<writer>`. Person photos cached & served (Phase 31 pattern) and written as `<actor><thumb>` so Jellyfin pulls them — across movies, series & episode guest stars. New `Person` model; `cast[]`/`crew[]` on items (FR-CC1) | [archive](archive/phase-75-cast-crew.md) |
| 76 | ✓ Done | **Scope-aware season/episode cast** — Series-detail Cast & crew gains a **Series · Season · Episode** switcher. Main cast = TMDB `aggregate_credits` (inherited by every episode, → `tvshow.nfo`); episodes show inherited cast (read-only) + editable **guest stars** + crew (→ `episodedetails.nfo`). Season scope is a **presence matrix** (All-seasons numbered view; per-season E1…En tap-to-toggle). ⓘ help popup explains the TMDB→NFO→Jellyfin flow (FR-CC2) | [archive](archive/phase-76-scope-aware-season-episode-cast.md) |
| 77 | ✓ Done | **External IDs in NFO (IMDb / TVDB)** — fetch `imdbId` + `tvdbId` from TMDB `/external_ids`; store on `MediaItem`; write `<imdbid>` / `<uniqueid type="imdb">` (movies + series) and `<uniqueid type="tvdb">` (series) in NFOs so Jellyfin cross-links items without a separate scraper (FR-XI1) | [archive](archive/phase-77-external-ids-nfo.md) |
| 78 | Planned | **File-descriptor exhaustion crash** — backend died with `File descriptor 1024 is larger or equal to FD_SETSIZE (1024)` under a flood of `/api/people/{id}/image` requests. Native CIO server uses `select()` (FD_SETSIZE=1024 hard ceiling); the Phase 75/76 cast feature renders 50–200 eager `<img>` per detail page, each opening an inbound socket + (cold-cache) an outbound TMDB download + temp file, with **no concurrency cap** → simultaneous FDs cross 1024 → fatal selector throw. Fix is layered: semaphore-bound `LogoDownloader.download`, scan-time people-cache pre-warm, O(1) image lookup, `loading="lazy"` on person imgs, FD-budget invariant in the constitution (FR-FD1) | [phase-78](phase-78-fd-exhaustion-crash.md) |
| 79 | Planned | **Cast tab: episode-count badge overlaid on the photo** — the `▸ N eps` badge on series cast cards floats in the text area below the photo (anchored `bottom:42px` to the card, lands over `.pbody`). Move the badge markup inside the `.ph` photo div + re-anchor `.epb`/`.tag` to `bottom:6px` so it overlays the photo's lower-left corner (matching the `.gtag` guest pill). Display-only (FR-CC3) | [phase-79](phase-79-cast-badge-overlay.md) |
| 80 | Planned | **Cast presence matrix reflects real TMDB per-season data** — the Season-scope matrix shows every actor in every season/episode because `Person.episodePresence` is **never populated** (zero writers) and the UI treats empty as "present in all." Add TMDB `/season/{n}/aggregate_credits`, populate a real `Person.seasonEpisodeCounts` at scan time, render per-season counts (absent seasons blank), keep guest stars episode-accurate, and revise NFO inheritance + the empty-default semantics. TMDB constraint: recurring cast is season-granular, only guest stars are per-episode (FR-CC4) | [phase-80](phase-80-cast-presence-matrix-real-data.md) |

## Adding a new phase
1. Create `phase-NN-short-name.md` in this directory using an existing planned spec as a template.
2. Add a row to the table above.
3. On completion: move the file to `archive/`, flip status to `✓ Done`, update STATUS.md focus line.

## Sibling product — Ravilo (Android TV + Web)
**Ravilo** — the Compose Multiplatform streaming front-end (Android TV + browser/WASM canvas) for
jellystructure-managed libraries — lives in [`../ravilo/`](../ravilo/) with its own
[`constitution.md`](../ravilo/constitution.md), [`plan.md`](../ravilo/plan.md), and
[phase index](../ravilo/requirements/README.md) (the single source of truth for Ravilo's phase
status). Its backend work
adds a **`/api/tv/**`** namespace to *this* server and a shared **`:shared`** KMP module (DTOs +
client) reused by the admin frontend too — but Ravilo phases are tracked separately under
`../ravilo/`, not in the table above. **[R32](../ravilo/requirements/phase-R32-unified-filter-workbench.md)**
shares one filter-workbench builder across the Ravilo config screen **and** this Library page, and
folds the Phase-20 audio-track filter into it.
