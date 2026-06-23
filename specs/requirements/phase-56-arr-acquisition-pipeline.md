# Phase 56 — Radarr/Sonarr acquisition pipeline + unified download-status state machine (FR-AQ1)

**Status:** Planned
**Depends on:** Phase 54 (Radarr/Sonarr connection), Phase 40/26 (qBittorrent client + path mappings)

## Goal

Phase 54 connects Radarr/Sonarr (URL, key, root folders, rescan). This phase adds the **acquisition
engine**: ask Radarr/Sonarr to *get* a title we don't have, then track its journey to the library and
expose **one normalized status** with a **rich, multi-stage indicator** — not just a download
percentage.

The key requirement: a requested title moves through several distinct stages, and the UI must be able
to tell them apart:

- it can sit **requested** before the *arr has even handed it to a download client (no client, no %);
- it can sit **queued** in the download client without actively downloading yet;
- it can be **downloading** with a real %, and even then be **stalled** or still **fetching metadata**;
- it can be **importing** (downloaded, *arr is renaming/moving into the library) before it's playable;
- and finally **available** in Jellyfin.

This engine is consumed by the admin app and by Ravilo's `/api/tv/discover` (R48). It is **request +
track only** — it never auto-grabs, upgrades, or deletes (the scope fence from Phase 54 holds).

## The status model — `AcquisitionStatus`

One enum, defined here, shared by `:shared` so backend + admin + Ravilo all speak it. Each acquisition
record carries the status plus stage-specific detail.

| Status | Meaning | Detail fields | Has % |
|--------|---------|---------------|-------|
| `not_requested` | Known title (chart/search) not in library, no active request | — | no |
| `requested` | We told Radarr/Sonarr to add + search; it is searching for a release or queued **inside the \*arr**, **not yet handed to a download client** | `requestedAt`, `indexerSearch` (searching/queued) | no |
| `queued` | Grabbed; sitting in the **download client's queue**, not actively downloading yet | `queuePosition`, `client` | no |
| `downloading` | Actively downloading | `progress` (0–100), `sizeLeft`, `eta`, `downloadRate`, `flags`: `metadata` (resolving magnet/metadata), `stalled` (no peers/0 B/s) | yes |
| `importing` | Download complete; \*arr is importing/renaming into the library root folder | `importStartedAt` | no (treat as ~100) |
| `available` | Present in the Jellyfin library (resolved by Jellystructure) | `itemId` | n/a |
| `failed` | Search found nothing, grab/import failed, or stalled past the timeout | `reason`, `retryable` | no |

Notes:
- `requested` vs `queued` is exactly the distinction the operator asked for: **requested = not yet at
  the download client**; **queued = at the client, waiting its turn**; **downloading = actually moving
  bytes**.
- `progress` is **only meaningful for `downloading`** (and implicitly 100 for `importing`). The UI must
  render the other stages as labels/pills, never a 0% bar.
- `stalled` and `metadata` are **flags on `downloading`**, not separate statuses (a stalled torrent is
  still "downloading", just at 0 B/s) — so the indicator can show "Fetching · 47% · stalled".

### State transitions
```
not_requested ──request──▶ requested ──grabbed──▶ queued ──starts──▶ downloading ──complete──▶ importing ──imported──▶ available
                   │                                   │                  │                        │
                   └──no release / error──▶ failed ◀───┴── stalled-timeout/grab fail ─────────────┘
```
`available` can also be reached directly (full scan finds the file). `failed` is terminal until a new
request (retry). Status only ever moves forward except `failed → requested` on retry.

## Backend

### Data model — `AcquisitionStore` (SQLDelight, alongside Phase 14 stores)
`acquisition(item_key, media_kind, title, tmdb_id, arr_id, arr_kind, download_id, status, progress,
queue_position, flags, reason, requested_by, requested_at, updated_at)`.
- `item_key` = the Jellystructure item id when known, else `tmdb:<id>` (a request can predate the
  library item existing). Reconciled to the real `itemId` when the scan imports it.
- Idempotent: re-requesting an in-flight title is a no-op that returns the existing record.

### `AcquisitionService`
- `request(mediaKind, tmdbId/title, requestedBy)`:
  1. Resolve which *arr (movies→Radarr, series→Sonarr) and confirm it's `enabled` (else `failed:
     no_arr`).
  2. `POST /api/v3/movie` (or `/series`) with the configured root folder + quality profile + a
     `searchForMovie/Series` command (add **and** search). Store `arr_id`, set `requested`.
  3. Never called for titles already `available`.
- `poll()` — a scheduled reconciler (default every ~10 s while any record is non-terminal; idle
  otherwise):
  1. `GET /api/v3/queue` from each enabled *arr → per record derive `queued` / `downloading`
     (`timeleft`, `sizeleft`, `status`, `trackedDownloadState`) / `importing`
     (`trackedDownloadState = importPending/importing`).
  2. If the *arr exposes a download client id and **qBittorrent is configured (Phase 40)**, optionally
     enrich `downloading` with live `progress`/`dlspeed`/`eta`/`state` (qB `state` = `stalledDL`,
     `metaDL`, `queuedDL`, `downloading`) for finer flags. The *arr queue is the **source of truth**;
     qB is enrichment only (and the Phase 40 path mappings already align paths).
  3. On `trackedDownloadState` reaching imported / the file appearing under a mapped root → trigger the
     existing scan for that path; when the scan resolves the item, set `available` + reconcile
     `item_key → itemId`.
  4. Emit a status-changed event (below) whenever a record's status/progress/flags change.
- A **timeout/stall policy**: `downloading` with the `stalled` flag for longer than
  `acq_stall_timeout` (config, default 30 min) → `failed: stalled` (retryable). Configurable.

### Config (constitution §Configuration Shape, additive)
```toml
[acquisition]
enabled = true            # master; also implicitly requires [radarr]/[sonarr] enabled
poll_seconds = 10         # reconciler cadence while requests are in-flight
stall_timeout_minutes = 30
default_quality_profile = ""   # blank = the *arr's default profile
```

### API + events
- `POST /api/acquisition/request` `{mediaKind, tmdbId}` → `{status record}`.
- `GET /api/acquisition?keys=…` → status records (batch; for hydrating any list).
- `GET /api/acquisition/{itemKey}` → one record.
- WS: `acquisition_changed` broadcast (admin WS + the R33 per-user `/api/tv/events`) with the updated
  record, so indicators move **live** with no polling from the client. (Frontend renders server-pushed
  state only — constitution invariant.)

## Non-goals / invariants
- **Request + track only.** No `MoviesSearch` upgrades, no `/queue` deletes, no library deletes.
- **\*arr queue is source of truth**; qBittorrent is optional enrichment (never required for status).
- **Status never blocks anything** — this is informational acquisition, independent of the Phase 26/40
  seeding *guard* (which is the only fail-closed path).
- **One enum everywhere** — admin and Ravilo never invent parallel status vocabularies.

## Mockups
The TV mockup (`design/ravilo/ravilo-data.js` / `ravilo-app.js`) currently models a reduced set
(`available` / `fetching` / `none`). This phase's full enum (`requested`, `queued`, `downloading`
+flags, `importing`) is the target the indicators should render — see R49 for the TV presentation.
