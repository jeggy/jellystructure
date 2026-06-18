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
| 29 | Planned | Multi-language library search — remember every title ever pulled (FR-ML1) | [spec](phase-29-multi-language-library-search.md) |

## Adding a new phase
1. Create `phase-NN-short-name.md` in this directory using an existing planned spec as a template.
2. Add a row to the table above.
3. On completion: move the file to `archive/`, flip status to `✓ Done`, update STATUS.md focus line.
