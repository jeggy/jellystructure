# Tasks

Step-by-step TODO for the **active** phase. Keep this short and current — when a phase finishes,
clear its checklist and seed the next phase from its spec in [`requirements/`](requirements/).

See [`STATUS.md`](STATUS.md) for the overall snapshot.

---

## Active: Phase 14 — Persistence Layer (SQLite/SQLDelight) (FR-P1)

Foundational — pick up first; later phases build on it. Spec:
[`requirements/phase-14-persistence-sqlite.md`](requirements/phase-14-persistence-sqlite.md)

### Build & schema
- [ ] Add the SQLDelight Gradle plugin + native SQLite driver for `linuxX64`/`linuxArm64`; define the `JellystructureDb`.
- [ ] DB file under data dir (`./data/jellystructure.db`, env `DB_FILE`). Config stays TOML — not in the DB.
- [ ] Write `.sq` schema: `media` (blob + indexed scalars), `session`, `media_history`, `scan_state` (+ `scan_processed`). Decide in `plan.md` whether to add `media_genre`/`media_track` child tables.

### Migrate stores (no API surface change)
- [ ] Reimplement `MediaStore`, `SessionService`, `ScanTracker`, `MediaHistory` on SQLDelight, keeping public signatures so routes don't change.
- [ ] Push `MediaStore.list(...)` filter/sort/pagination + stats into SQL.
- [ ] One-time import of legacy `media.json` / `sessions.json` / `scan-state.json` on first boot; rename imported files to `*.imported`.
- [ ] Confirm native-driver threading is safe under CIO and (later) concurrent scan workers.

### Verify & constitution
- [ ] All existing endpoints behave identically; `media_history` survives a restart; Phase 7 resume still works via the DB checkpoint.
- [ ] Update the constitution to describe what's stored in the DB vs TOML; drop the "JSON files" caveats in STATUS / `_investigation-findings.md`.

---

## Backlog (next phases)
Rough dependency order — confirm sequencing before starting each.
- [ ] **Phase 15** — Fix library path matching — diagnostics + path-check (FR-B1)
- [ ] **Phase 16** — Multi-worker scanner, dynamic scaling (FR-W1)
- [ ] **Phase 17** — Activity log backend + live runners (FR-A1) — surfaces Phase 16's worker counts; use SQLite (Phase 14)
- [ ] **Phase 18** — Settings page cleanup (FR-C1) — hosts Phase 16 scanning fields + Phase 23 fallback-lang
- [ ] **Phase 19** — Studios/Networks/Genres/Tags metadata page (FR-M1) — needs P1/P2 scanner prerequisites; js_tags in SQLite (Phase 14)
- [ ] **Phase 20** — Library audio-track filter (FR-LF1)
- [ ] **Phase 21** — Flag multiple default audio tracks in Triage (FR-DA1)
- [ ] **Phase 22** — Remove lockdata & detect Jellyfin field locks (FR-LK1)
- [ ] **Phase 23** — Remove the Language page (FR-RL1) — small; pairs with Phase 18
- [ ] **Phase 24** — Manage the TMDB ID field (FR-TI1)
- [ ] **Phase 25** — Fix Sync button → "Re-pull from Jellyfin…" (FR-RJ1) — benefits from Phase 15 + Phase 22

## Loose ends (track in STATUS.md)
- [ ] Remove the `<lockdata>` mandate from the constitution (Phase 22 drops it for good).
- [ ] Correct the constitution's "SQLite/SQLDelight" claim — realised by Phase 14.
- [ ] Resolve the port free-check `TODO` in `Main.kt`.
