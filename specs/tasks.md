# Tasks

Step-by-step TODO for the **active** phase. Keep this short and current — when a phase finishes,
clear its checklist and seed the next phase from its spec in [`requirements/`](requirements/).

See [`STATUS.md`](STATUS.md) for the overall snapshot.

---

## Active: Phase 18 — Settings page cleanup (FR-C1)

Spec: `requirements/phase-18-settings-page-cleanup.md`

### Steps

_(seed from spec before starting)_

---

## Backlog (next phases)
Rough dependency order — confirm sequencing before starting each.
- [ ] **Phase 18** — Settings page cleanup (FR-C1) — hosts Phase 16 scanning fields + Phase 23 fallback-lang
- [ ] **Phase 19** — Studios/Networks/Genres/Tags metadata page (FR-M1) — needs P1/P2 scanner prerequisites; js_tags in SQLite (Phase 14 ✓)
- [ ] **Phase 20** — Library audio-track filter (FR-LF1)
- [ ] **Phase 21** — Flag multiple default audio tracks in Triage (FR-DA1)
- [ ] **Phase 22** — Remove lockdata & detect Jellyfin field locks (FR-LK1)
- [ ] **Phase 23** — Remove the Language page (FR-RL1) — small; pairs with Phase 18
- [ ] **Phase 24** — Manage the TMDB ID field (FR-TI1)
- [ ] **Phase 25** — Fix Sync button → "Re-pull from Jellyfin…" (FR-RJ1) — benefits from Phase 15 + Phase 22

## Loose ends (track in STATUS.md)
- [ ] Remove the `<lockdata>` mandate from the constitution (Phase 22 drops it for good).
- [ ] Resolve the port free-check `TODO` in `Main.kt`.
