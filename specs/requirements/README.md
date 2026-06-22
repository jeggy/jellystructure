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
| 48 | □ Planned | Artwork manager — fix the Textless / With-text filter: it only re-sorts (a no-op within a language bucket) and overlaps the language chips; unify both into one real single-select filter on TMDB `iso_639_1` (FR-AM2) | [phase-48](phase-48-artwork-textless-filter.md) |
| 49 | ✓ Done | Full episode coverage for large series — the scan probed only a spread of 100 episodes (>100-ep series showed scattered gaps in Seasons & Episodes + Artwork, with no UI recovery); make the cap configurable (`scan_episode_cap`, default 0 = unlimited) + wire an on-demand uncapped "Re-scan all episodes" (FR-EP1) | [archive](archive/phase-49-full-episode-coverage.md) |

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
