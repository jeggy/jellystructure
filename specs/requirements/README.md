# Requirements

The **"what"** — features, acceptance criteria, and goals, one file per development phase.

- **Active / planned** phases live directly in this directory.
- **Completed** phases are archived in [`archive/`](archive/) for reference (the spec is preserved as it was written, even though the work is done).

Phases are sequential. Do not begin a phase until the prior phase's core deliverables work
end-to-end. See [`../STATUS.md`](../STATUS.md) for where work currently stands and
[`../tasks.md`](../tasks.md) for the active TODO breakdown.

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
| 14 | Planned | Fix library path matching — diagnostics + path-check (FR-B1) | [spec](phase-14-library-path-matching.md) |
| 15 | Planned | Multi-worker scanner — configurable workers + thread pool (FR-W1) | [spec](phase-15-multi-worker-scanner.md) |
| 16 | Planned | Activity log backend — persistent log, filters, worker display (FR-A1) | [spec](phase-16-activity-log-backend.md) |
| 17 | Planned | Settings page cleanup — nav fix, section reorganisation (FR-C1) | [spec](phase-17-settings-page-cleanup.md) |
| 18 | Planned | Studios, Networks, Genres & Tags metadata page (FR-M1) | [spec](phase-18-metadata-page.md) |

## Adding a new phase
1. Create `phase-NN-short-name.md` in this directory using an existing planned spec as a template (Problem → Current state → Requirements → Invariants).
2. Add a row to the table above.
3. When complete, move the file to `archive/` and flip its status to `✓ Done`.
