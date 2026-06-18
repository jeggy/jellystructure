# Tasks

Step-by-step TODO for the **active** phase. Keep this short and current — when a phase finishes,
clear its checklist and seed the next phase from its spec in [`requirements/`](requirements/).

See [`STATUS.md`](STATUS.md) for the overall snapshot.

---

## Active: Phase 14 — Fix Library Path Matching (FR-B1)

Spec: [`requirements/phase-14-library-path-matching.md`](requirements/phase-14-library-path-matching.md)

### Backend
- [ ] Augment the `[WARN] No matching library for ...` log with the list of configured match prefixes.
- [ ] Add `GET /api/config/path-check` returning per-library `{ name, jellyfinPath, localPath, matchPrefix, localExists }`.
- [ ] `matchPrefix` = `jellyfinPath` if non-blank else `localPath`; `localExists` via `SystemFileSystem.exists(Path(localPath))`.

### Settings UI
- [ ] After a successful "Test connections", call `/api/config/path-check` and render results inline below the Jellyfin/TMDB badges.
- [ ] Per-library row: name, `matchPrefix`, and status badge (green found / red not-found + hint / amber jellyfinPath-not-set).
- [ ] Also run path-check after a successful "Save".
- [ ] Library mapping card: read-only "Matching prefix: {matchPrefix}" line per entry, updating live as the Jellyfin path field changes.
- [ ] Add the collapsed `<details>` help element explaining jellyfinPath vs localPath.

### Verify
- [ ] Reproduce the original mismatch fixture, confirm the diagnostic surfaces it, then confirm a corrected config matches movies during a scan.

---

## Backlog (next phases, in order)
- [ ] **Phase 15** — Multi-worker scanner (FR-W1)
- [ ] **Phase 16** — Activity log backend (FR-A1)
- [ ] **Phase 17** — Settings page cleanup (FR-C1) — depends on 15 (Scanning section) + 16 (Clear-log)
- [ ] **Phase 18** — Studios/Networks/Genres/Tags metadata page (FR-M1)

## Loose ends (track in STATUS.md)
- [ ] Decide on `<lockdata>` — restore in `NfoWriter` or amend the constitution.
- [ ] Resolve the port free-check `TODO`.
