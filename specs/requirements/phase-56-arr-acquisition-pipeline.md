# Phase 56 — Radarr/Sonarr acquisition pipeline + unified download-status state machine (FR-AQ1)

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
| `failed` | Search found nothing, or the **\*arr dropped/errored** the item (grab/import failure) | `reason`, `retryable` | no |

**`stalled` is a flag, never a terminal state.** A torrent with no peers is still in the \*arr queue and
the \*arr is still trying — so it stays `downloading` + `stalled` (informational), not `failed`. We only
reach `failed` when the **\*arr itself** removes the item from its queue or marks it errored/warning
(`trackedDownloadStatus = error`). This keeps the "\*arr queue is source of truth" + "forward-only"
invariants consistent (a stalled item that resumes simply clears the flag — no illegal `failed →
downloading` bounce).

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
not_requested ──request──▶ requested ──grabbed──▶ queued ──starts──▶ downloading ⇄ (stalled flag) ──complete──▶ importing ──imported──▶ available
                   │                                                       │
                   └── no release found ──▶ failed ◀── *arr drops/errors the item ──┘
```
`available` can also be reached directly (full scan finds the file). `failed` is terminal until a new
request (retry). Status only ever moves **forward** except `failed → requested` on retry; the `stalled`
flag toggles on/off within `downloading` and is **not** a transition.

### Series are episode-aggregates (not movie-shaped)
A movie is one file → one record. A **series request is N episodes**, each at its own stage, so a single
`AcquisitionStatus` can't say "3 of 10 imported." Model it as:
- The `acquisition` row for a series is a **parent** carrying `episodes_total`, `episodes_done`
  (imported/available), and a **roll-up** status = the **least-advanced monitored episode** (so a series
  reads `downloading · 3/10` while episode 4 is grabbing). Per-episode detail lives in a child table
  `acquisition_episode(parent_id, season, episode, status, progress, download_id)` populated from the
  Sonarr queue (which is per-episode).
- A convenience flag `firstAvailable` flips when the **first** monitored episode resolves into the
  library, so the TV can offer "Watch Now (E1)" before the whole season finishes.
- The roll-up `progress` (when `downloading`) is `episodes_done / episodes_total`-weighted, not a single
  torrent %.

**Sonarr add is not symmetric with Radarr.** `POST /api/v3/series` requires a **monitor scope** and a
`seasons[].monitored[]` array — you must say *what* to fetch (whole series / latest season / first
season / pilot). That policy is config (`[acquisition.sonarr].monitor`, below), defaulting to a sane
scope, and is sent on add. Radarr's `POST /api/v3/movie` is the simple single-file case.

## Backend

### Data model — `AcquisitionStore` (SQLDelight, alongside Phase 14 stores)
`acquisition(item_key, media_kind, title, tmdb_id, tmdb_confidence, arr_id, arr_kind, download_id,
status, progress, queue_position, flags, episodes_total, episodes_done, first_available, reason,
requested_by, requested_at, updated_at)`, plus a child
`acquisition_episode(parent_id, season, episode, status, progress, download_id)` for series (the Sonarr
queue is per-episode; the parent rolls up — see "Series are episode-aggregates").
- `item_key` = the Jellystructure item id when known, else `tmdb:<id>` (a request can predate the
  library item existing). Reconciled to the real `itemId` when the scan imports it.
- Idempotent: re-requesting an in-flight title is a no-op that returns the existing record.

### `AcquisitionService`
- `request(mediaKind, tmdbId/title, requestedBy)`:
  1. Resolve which *arr (movies→Radarr, series→Sonarr) and confirm it's `enabled` (else `failed:
     no_arr`).
  2. Resolve the **add parameters** the API requires explicitly (there is no "use default" sentinel in
     an \*arr add payload):
     - `rootFolderPath` ← `[acquisition.<arr>].root_folder`, or — if blank — the \*arr's sole root
       folder (`GET /api/v3/rootfolder`); error `failed: no_root` if blank **and** the \*arr has more
       than one root.
     - `qualityProfileId` ← resolve `[acquisition.<arr>].quality_profile` (a **name**) against
       `GET /api/v3/qualityprofile`; blank → the \*arr's first/default profile id.
     - **Sonarr only:** `monitor` + `seasons[].monitored[]` from `[acquisition.sonarr].monitor`;
       `seasonFolder` from config.
  3. `POST /api/v3/movie` (Radarr) or `POST /api/v3/series` (Sonarr) with those params + a
     `MoviesSearch`/`SeriesSearch` (or per-season search) command (add **and** search). Store `arr_id`
     (+ `episodes_total` for series), set `requested`.
  4. Never called for titles already `available`.
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
  4. Emit a status-changed event (below) on a **meaningful** change (see throttling).
- **No stall→fail timeout.** A stalled item stays `downloading + stalled` indefinitely (the \*arr is
  still trying); `failed` comes only from the \*arr dropping/erroring the item (per the status table).
  An admin can cancel a request explicitly (which removes it from the \*arr); we never auto-fail a
  still-queued torrent.

### Config (constitution §Configuration Shape, additive)
```toml
[acquisition]
enabled = false           # master opt-in — OFF by default (consistent with every other section);
                          # also requires the relevant [radarr]/[sonarr] section enabled
poll_seconds = 10         # reconciler cadence while requests are in-flight (idle otherwise)

[acquisition.radarr]
root_folder = ""          # rootFolderPath for new movie adds; blank = the sole Radarr root (else error)
quality_profile = ""      # profile NAME, resolved to qualityProfileId via GET /qualityprofile; blank = default

[acquisition.sonarr]
root_folder = ""
quality_profile = ""
monitor = "all"           # Sonarr add monitor scope: all | future | firstSeason | latestSeason | pilot
season_folder = true
```

### API + events
- `POST /api/acquisition/request` `{mediaKind, tmdbId}` → `{status record}`. Re-posting a `failed`
  record is the **retry** path (`failed → requested`).
- `POST /api/acquisition/cancel` `{itemKey}` → removes the request from the \*arr (and its download
  client) and deletes/zeroes the record back to `not_requested`. The only mutation we make beyond add.
- `GET /api/acquisition?keys=…` → status records (batch; for hydrating any list).
- `GET /api/acquisition/{itemKey}` → one record.
- **Who may request (permission):** requesting spends disk + bandwidth, so it is a privileged action.
  Default policy: **admins always; non-admin users only if their per-user config grants it**
  (`discover.canRequest`, R48) — kids profiles never. A view-only user sees statuses and `not_requested`
  but the Request button is disabled with a "ask the owner" hint. (Cancel = admin only.)
- WS: `acquisition_changed`. **This carries the updated record inline** — it is *not* the R33 rev-signal
  pattern (a bare `{type, rev}` that forces a full re-pull). Re-pulling the whole
  `GET /api/tv/discover` on every progress tick would be a firehose; instead the message is
  `{type:"acquisition_changed", record:{itemKey, status, progress, flags, episodesDone, episodesTotal, …}}`
  and the client patches the matching tile/detail in place. (Still server-pushed state — the client
  never derives progress locally.) The R33 socket is extended with this typed, payload-bearing event
  alongside its existing rev-signals.
- **Throttle:** emit on every **stage change** (status/flags/episode-count) immediately, but coalesce
  pure `progress` movement to **≥5% delta or ≥3 s** per record, so a continuously-moving % doesn't
  flood every one of the user's TVs.

## Non-goals / invariants
- **Request + track only.** The only writes to an \*arr are **add (+ the one search on add)** and an
  explicit **cancel**. No automatic *upgrade* re-grabs of already-acquired items, no quality
  cutoff-met searches, no library deletes.
- **\*arr queue is source of truth**; qBittorrent is optional enrichment (never required for status).
- **Status never blocks anything** — this is informational acquisition, independent of the Phase 26/40
  seeding *guard* (which is the only fail-closed path).
- **One enum everywhere** — admin and Ravilo never invent parallel status vocabularies.

## Mockups
The TV mockup (`design/ravilo/ravilo-data.js` / `ravilo-app.js`) currently models a reduced set
(`available` / `fetching` / `none`). This phase's full enum (`requested`, `queued`, `downloading`
+flags, `importing`) is the target the indicators should render — see R49 for the TV presentation.
