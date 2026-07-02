# Phase 114 — Realtime ingest: new media reaches Ravilo in minutes, not scan-cycles (FR-RI1)

## Goal
When a new file lands in the media directory (Sonarr/Radarr import, or a manual copy), today's path is:
wait for Jellyfin to notice → wait for jellystructure's **next scheduled pipeline run** → full scan finds
it → metadata/NFO/artwork → it finally appears in Ravilo. Replace the middle with **push**: *arr
webhooks and Jellyfin's own change events trigger a **targeted single-item ingest** within seconds of
Jellyfin knowing about the item — no full scan, no schedule wait.

## Current state (verified in code)
- **Nothing inbound exists**: no Jellyfin WebSocket client, no webhook receiver routes (the only
  "webhook" is the outbound notifier `fireWebhook`), no *arr push. Discovery is exclusively
  `runScan` (full or per-library `POST /api/media/scan?library=`).
- **Targeted building blocks exist**: `POST /api/media/{id}/repull-jellyfin` →
  `Scanner.rescanFromJellyfin` (`Scanner.kt:110-132`: `getItem` + full `scanItem`) re-ingests one
  *known* item; `scanItem` (ffprobe → TMDB → `addOrUpdate`) is the per-item pipeline; `pushToJellyfin`
  (`MediaRoutes.kt:1663-1709`) writes NFO + artwork + refresh for one item. What's missing is only
  "ingest a Jellyfin item id that is **not in the store yet**".
- jellystructure never calls `POST /Library/Media/Updated` (path-targeted Jellyfin pickup) — its only
  Jellyfin scan trigger is the heavyweight `POST /Library/Refresh`.
- **Jellyfin facts** (confirmed 10.10): the `/socket` WebSocket automatically delivers `LibraryChanged`
  (`ItemsAdded/ItemsUpdated/ItemsRemoved`, batched ~30 s) to **user-token** sessions (API-key sockets
  don't get it); `POST /Library/Media/Updated {Updates:[{Path, UpdateType:"Created"}]}` asks Jellyfin to
  examine one path (subject to its ~60 s monitor settle delay); `POST /Items/{folderId}/Refresh`
  validates a folder's children immediately. Jellyfin's own realtime monitor is unreliable on network
  mounts (the usual reason "it takes long for Jellyfin to pick up").

## Requirements

### A. Inbound *arr webhooks (the earliest signal)
1. New open routes `POST /api/webhooks/sonarr` and `POST /api/webhooks/radarr`, authenticated by a
   per-hook **secret** query param (generated, shown in Settings; requests with a bad secret → 403,
   logged). Handle `eventType: "Download"` (import/upgrade) payloads — extract the imported file/folder
   path(s); `Test` events respond 200 so the *arr "Test" button works. Other events are ignored.
2. On import: map the *arr path to the Jellyfin-visible path (existing `LibraryMapping.jellyfinPath` /
   `local_path` mapping) and **nudge Jellyfin**: `POST /Library/Media/Updated` with the path (new
   `JellyfinClient` method). This makes Jellyfin ingest promptly even on network mounts where its
   realtime monitor is blind.
3. Then wait for the item to exist in Jellyfin (the LibraryChanged listener below, plus a bounded
   fallback poll on `/Items?Path=`-style lookup for ~5 min) and run the targeted ingest (C).
4. Settings ▸ Download tools shows the two webhook URLs (with secret) next to the existing Radarr/Sonarr
   cards, with copy buttons and a "how to add it in *arr" hint.

### B. Jellyfin LibraryChanged listener (covers manual adds too)
1. One persistent **user-token** WebSocket to Jellyfin `/socket` (a Jellyfin server-listener identity —
   *not* one of the per-TV Phase 110 sockets), sending `KeepAlive` per protocol, auto-reconnecting with
   capped backoff, read-loop hardened (an escaping WS exception kills the native process — same rules as
   Phase 110/118).
2. On `LibraryChanged.ItemsAdded` (and `ItemsUpdated` for items we don't hold): fetch the ids
   (chunked `/Items?Ids=`), filter to configured libraries' Movies/Series/Episodes, debounce 5 s, and
   feed the targeted ingest (C). `ItemsRemoved` is **ignored** (Phase 95 non-destructive invariant —
   removal detection stays with full scans flagging `missingFromSource`).
3. The listener is on/off with a single config toggle: `[ingest] realtime = true` (default **on** when a
   Jellyfin token is configured). Its status (connected/last event) shows on the System health panel.

### C. Targeted single-item ingest
1. New `Scanner.ingestByJellyfinId(id)`: fetch the Jellyfin item; if it resolves to an **existing**
   store item (by jellyfinId, or an episode of one) → `rescanFromJellyfin`-equivalent re-scan of that
   item; if it's **new** → run the standard per-item pipeline (ffprobe → TMDB match → metadata/credits →
   `addOrUpdate`), then the post-steps the scheduled pipeline would do: artwork download, NFO write, and
   a targeted Jellyfin refresh (`pushToJellyfin`) so Jellyfin shows jellystructure's metadata
   immediately. New-episode-of-existing-series lands as a re-scan of the series item (which stamps the
   episode's `createdAt` — Phase 108 — and thus re-floats Newly Added).
2. Ingest runs are serialized on a small dedicated queue (concurrency 1–2, reusing the scan dispatcher
   bound), tagged as a **run** (`RunContext` "realtime ingest") so Activity shows them like any scan,
   with `ItemScanned` WS events (the Library live-insert path just works).
3. The home-feed cache invalidates via the normal `libraryVersion` bump → connected Ravilo devices get
   the standard feed-changed signal → **the new item appears in Newly Added within seconds of ingest**,
   no app action needed.
4. Failure is non-fatal and retried once after 60 s (TMDB hiccups); a still-failing ingest logs an
   Activity warning and is left for the next scheduled scan (which remains the reconciliation baseline).

### D. Scheduled scans remain the source of truth
Nothing about Phase 91/93 changes: the pipeline still reconciles everything (missing-from-source
flagging, freshness re-pulls, artwork gap-fill). Realtime ingest is an accelerator, not a replacement —
an event missed while jellystructure was down is simply covered by the next scheduled run.

## Scope
- Backend: webhook routes (+ secrets in config), `JellyfinClient` WS listener + `/Library/Media/Updated`
  method, `Scanner.ingestByJellyfinId`, ingest queue + run tagging, health-panel status.
- Config: `[ingest]` block (`realtime`, `webhook_secret`).
- FE: Settings ▸ Download tools webhook card; System health listener status. No Ravilo change.

## Non-goals
- No filesystem watching in jellystructure (inotify is exactly what's unreliable on network mounts;
  Jellyfin + *arr webhooks are the signal sources).
- No handling of *arr rename/delete events (rename lands as ItemsUpdated via Jellyfin; deletes stay
  scan-reconciled per Phase 95).
- No change to acquisition tracking (Phase 56 polls the *arr queue for Discover; unrelated pipeline).

## Acceptance
- Sonarr imports an episode: within ~2 min (Jellyfin monitor settle included) the episode exists in
  jellystructure with TMDB metadata + NFO, and the series is at the top of Ravilo's Newly Added — with
  **no** scheduled scan having run.
- Manually copying a movie into the library (no *arr involved) reaches Ravilo via the LibraryChanged
  path in ~2–3 min.
- Killing jellystructure during an import loses nothing: the next scheduled pipeline run picks the item
  up as before.
- The *arr "Test" webhook button reports success; a wrong secret is rejected and logged.
- With `[ingest] realtime = false`, behaviour is exactly today's.
