# Phase 14 — Persistence Layer: SQLite / SQLDelight (FR-P1)

**Status:** Planned · **Foundational — pick up first.** Several later phases depend on it.

## Problem
The constitution mandates "**SQLDelight with native SQLite driver** — sessions, scan cache, audit
log, triage issues," but **none of it is wired**. There are no `.sq` files and no SQLite dependency.
Actual persistence today (see [`_investigation-findings.md`](_investigation-findings.md)):

| Data | Today | Problem |
|------|-------|---------|
| Media cache | `MediaStore` → `media.json`, full-file rewrite on every change | O(n) rewrite per item; no queries; whole library in memory |
| Scan state | `ScanTracker` → `scan-state.json` | OK-ish, but ad hoc |
| Sessions | `SessionService` → `sessions.json` | OK-ish |
| Audit history | `MediaHistory` → **in-memory only** | **lost on restart**; cap 2000 |

This blocks/over-complicates upcoming work: the activity log ([`phase-17`](phase-17-activity-log-backend.md))
and JS tags ([`phase-19`](phase-19-metadata-page.md)) would each invent another JSON file; the
metadata aggregations and the audio-track / studio / network / genre filters
([`phase-19`](phase-19-metadata-page.md), [`phase-20`](phase-20-library-audio-track-filter.md)) want
real queryable storage; large libraries make the rewrite-everything-on-every-item pattern slow.

## Goal
Introduce SQLDelight + the native SQLite driver as the **real persistence layer**, migrate the
existing stores onto it behind their current public APIs (so routes/UI don't change), and make the
constitution's claim true. Do this **before** the feature phases so they build on SQLite from the
start.

## Requirements

### Build & dependencies
1. Add the SQLDelight Gradle plugin and the **native SQLite driver** (`app.cash.sqldelight:native-driver`)
   to `build.gradle.kts`, configured for the `linuxX64` / `linuxArm64` targets. Define a SQLDelight
   database (e.g. `JellystructureDb`) with a `linuxX64Main`/native source set for the generated code.
2. The DB file lives under the data dir, e.g. `./data/jellystructure.db`, with an env override
   (`DB_FILE`), mirroring the existing `CONFIG_FILE`/`MEDIA_FILE` conventions in `Main.kt`.
3. Schema lives in `.sq` files under the conventional SQLDelight source dir; queries are typed and
   generated at build time.

### Schema (define precisely in `plan.md`; pragmatic first cut)
4. Tables to introduce:
   - `media` — one row per `MediaItem`. **Hybrid approach:** a `json` blob column holding the full
     serialized `MediaItem`, **plus** indexed scalar columns used for listing/filtering: `id` (PK),
     `kind`, `title`, `year`, `studio`, `network`, `issue_count`, `language_mix`, `scanned_at`,
     `tmdb_id`, `poster_path` (nullable). This keeps the model authoritative as JSON while making
     `MediaStore.list(...)` filters (kind/attention/search/sort, and later studio/network/genre)
     index-backed.
   - Child rows for query-heavy needs **if** the audio-track filter ([`phase-20`](phase-20-library-audio-track-filter.md))
     and genre aggregation ([`phase-19`](phase-19-metadata-page.md)) prove awkward against the blob:
     `media_genre(media_id, genre)`, `media_track(media_id, kind, language, title, codec, is_default)`.
     **Decision left to `plan.md`** — start with blob + indexed scalars; add child tables only where a
     filter genuinely needs them. Document the choice.
   - `session` — replaces `sessions.json` (token PK, jellyfin user id/name/token, expires_at).
   - `media_history` — replaces the in-memory `MediaHistory` (id PK, media_id, ts, action, detail);
     now **survives restart**. Keep the 2000-ish cap via trim-on-insert (or raise it).
   - `scan_state` — single-row table replacing `scan-state.json` (status, job_id, started_at,
     updated_at, total_seen), plus a `scan_processed(job_id, jellyfin_id)` table for the resume
     checkpoint (replaces the `processedIds` array). Preserve the exact Phase 7 semantics
     (RUNNING→CANCELLED on startup, resume skips processed ids, etc.).
5. Reserve space for later phases (they add their own `.sq`): `activity_log`
   ([`phase-17`](phase-17-activity-log-backend.md)) and `js_tags` ([`phase-19`](phase-19-metadata-page.md)).
   Those phases' specs currently say "JSON-file pattern (no SQLite)" — once this phase lands, **they
   should use SQLite tables instead.** Update those two specs when this is implemented.

### Migration from existing JSON
6. On startup, run a one-time import: if the DB has no `media` rows but `media.json` exists, import it;
   same for `sessions.json` and `scan-state.json`. After a successful import, rename the legacy files
   to `*.imported` (don't delete) so there's a rollback path. `MediaHistory` has nothing to migrate
   (in-memory).
7. Config stays in **TOML** (`config.toml`) — it is user-editable and intentionally not in the DB.
   Do **not** move config into SQLite.

### Store reimplementation (no API surface change)
8. Reimplement `MediaStore`, `SessionService`, `ScanTracker`, and `MediaHistory` against SQLDelight,
   **keeping their existing public method signatures** so `MediaRoutes`, `TriageRoutes`, auth, etc.
   need no changes. The `Mutex`-guarded mutation pattern can be retained where helpful, but per-item
   writes should become single-row upserts (no whole-file rewrite).
9. `MediaStore.list(...)` should push filtering/sorting/pagination into SQL where practical (at minimum
   the indexed scalar filters and `LIMIT/OFFSET`), instead of loading all items and filtering in
   Kotlin. Stats (`movieCount`, `tvShowCount`, `totalIssueCount`, `nfoCoveragePercent`, …) become
   `COUNT`/aggregate queries.

### Threading
10. Kotlin/Native + the SQLDelight native driver: confirm the connection/threading model works under
    the Ktor CIO coroutine model and (later) the multi-worker scanner
    ([`phase-16`](phase-16-multi-worker-scanner.md)). Use a single connection with serialized access
    (mutex) or the driver's supported concurrency model — document the chosen approach. Writes from
    concurrent scan workers must be safe.

### Constitution
11. Once implemented, the constitution's "Local DB: SQLDelight with native SQLite driver" line becomes
    **true** — update [`../constitution.md`](../constitution.md) to describe what is actually stored
    where (DB tables vs TOML), and remove the now-stale "JSON files" caveats from STATUS and
    `_investigation-findings.md`.

## Acceptance
- App starts, imports legacy JSON once, and thereafter reads/writes the SQLite DB.
- All existing endpoints behave identically (media list/detail/scan/triage/sessions/history) — verified
  against current behaviour.
- `media_history` survives a restart.
- A scan still resumes correctly after a cancel/restart (Phase 7 semantics intact) using the DB
  checkpoint.

## Invariants
- Public store APIs unchanged — this is an internal swap of the persistence mechanism.
- Config remains TOML, not DB.
- No data loss on migration (legacy files retained as `*.imported`).
