# Phase 99 — Seeding snapshot cache: one short-lived qBittorrent snapshot, shared everywhere (FR-XS3)

> **Status: Planned** (design done, not built). Performance backing for **[Phase 97](phase-97-seeding-cross-seed-surface.md)**
> (seeding surface) and **[Phase 98](phase-98-tracker-registry.md)** (tracker registry + library filter).
> No new screens — it changes how the seeding data is fetched and adds a freshness/refresh affordance.

## Problem
Phases 97/98 read live qBittorrent state. Done naively that is **heavy and chatty**:
- Each Movie/Series detail open would hit qBittorrent for that title's file(s).
- The Library **"seeded on"** filter (Phase 98 §D) needs seeding membership for the *whole* result set —
  a per-title query there is a fan-out of hundreds of calls per filter/scroll.
- qBittorrent's Web API has login/session cost and rate sensitivity; hammering it per page-view is slow
  for the operator and rude to the client.

But the underlying fact is cheap to get **once**: qBittorrent's `torrents/info` returns the **entire**
torrent list (with trackers + save paths) in a single call. Everything Phases 97/98 show is a
projection of that one list.

## Goal
Fetch the full torrent list **once** into a single **short-lived, process-wide snapshot**, resolve it
against the tracker registry + map torrents → library files **once**, and serve every seeding read
(per-title reports, the library filter, the guard checks) from that cached projection. Opening a title
or flipping the filter becomes instant and never fans out N calls. A small freshness indicator + manual
refresh makes the cached nature honest.

## Current state (as-is)
- `QBittorrentClient.getTorrents()` already fetches the whole list (used today only by `SeedingGuard`
  for a single path check).
- `SeedingGuard.check(path)` is called per write site; under Phases 97/98 it would also back per-title
  reads. No shared cache exists — each consumer would call out independently.

## Requirements

### A. The snapshot
1. A single **`SeedingSnapshot`** service holds the last fetched, fully-resolved projection:
   ```
   SeedingSnapshot {
     takenAt: Instant,
     torrents: [ ResolvedTorrent ],            // qBittorrent list, tracker-resolved (Phase 98)
     byPath: Map<filePath, [ResolvedTorrent]>, // file → torrents containing it (covers mapping)
     byTracker: Map<trackerName, Set<mediaId>> // for the library "seeded on" filter (Phase 98 §D)
   }
   ```
2. Built from **one** `getTorrents()` call: resolve each torrent's announce hosts to a named tracker
   (Phase 98 §C), map each torrent's files to library items/episodes (Phase 97 §A `covers`), and
   pre-aggregate the per-tracker membership set. All consumers read these maps — no re-derivation.

### B. TTL + refresh policy
1. **Configurable TTL** — default **10 minutes** (`seeding_cache_ttl`, stored in seconds under
   `[qbittorrent]`; set on **Settings ▸ Download tools ▸ Seeding snapshot freshness**, range 1–60 min).
   Within the TTL, every read is served from the snapshot with **zero** qBittorrent calls. A longer
   interval is lighter on qBittorrent; operators who cross-seed often can lower it.
2. **Lazy refresh on access**: the first read after the snapshot goes stale triggers a single rebuild;
   concurrent readers during a rebuild get the previous snapshot (no thundering herd — rebuild is
   guarded by a `Mutex`, stale-while-revalidate).
3. **Manual refresh**: a `?refresh=true` read (the UI's "↻ Refresh now") forces a rebuild regardless of
   age.
4. **Coalesced**: at most one in-flight `getTorrents()` rebuild at a time; others await its result.
5. **Degradation** is non-fatal (Phase 97 invariant): if qBittorrent is unreachable the snapshot is
   marked `stale: true` / `reachable: false` and the **last good** projection is still served, flagged
   in the UI — never an error, never a blank "safe to edit".

### C. What reads the snapshot
- `GET /api/media/{id}/seeding` (Phase 97) — projects `byPath` for the title's file(s); returns
  `takenAt` + `ttl` so the UI can show freshness.
- Library **"seeded on"** filter + Metadata **Trackers** usage counts (Phase 98) — read `byTracker`
  (one map lookup, no per-title calls).
- `SeedingGuard.check(path)` (Phases 26/37/96) — reads `byPath` instead of its own call; the guard and
  the surface now share **one** source of truth (no chance of the chip and the banner disagreeing).

### D. UI — freshness & refresh (the only visible change)
1. The seeding tab (movie + series) shows a **freshness bar**: `qBittorrent snapshot · updated <Ns ago>
   · auto-refreshes every <TTL>` (TTL rendered as minutes when whole-minute, e.g. `every 10m`) + a
   **↻ Refresh now** button. The age ticks live.
2. Past the TTL the bar goes **stale** (amber) until the next read rebuilds it; **Refresh now** forces a
   rebuild and re-renders.
3. If qBittorrent is unreachable, the bar reads **"could not reach qBittorrent · showing last snapshot
   from <time>"** (amber) — consistent with the guard's "unknown, not safe" degradation.

### E. Configuration (Settings ▸ Download tools)
The **Cross-seed safety** section gains a **Seeding snapshot freshness** control: a **Refresh every**
number input (1–60) with a **Unit** (minutes) selector that writes `seeding_cache_ttl` (in **seconds**) into `[qbittorrent]`. The live
`config.toml` preview shows `seeding_cache_ttl = 600`. Copy explains the trade-off (longer = lighter on
qBittorrent; **Refresh now** always forces an immediate re-query regardless of the interval).

## Invariants
- **One fetch, many reads** — a snapshot rebuild is exactly one `getTorrents()` call; all projections
  (per-title, per-tracker, guard) derive from it. No consumer calls qBittorrent directly.
- **Short-lived** — the cache exists only to absorb bursts (a page open, a filter flip, a scroll); it is
  **not** a persistent store and is never written to disk. Lost on restart, rebuilt on first read.
- **Stale-while-revalidate, coalesced** — readers never block on more than one in-flight rebuild and
  always get *a* snapshot (possibly stale-flagged), never an error.
- **Frontend renders server state only** (constitution §4) — the UI shows `takenAt`/`ttl` from the
  server; it does not itself poll qBittorrent or compute freshness from guesses.
- **Degradation is non-fatal** — unreachable qBittorrent ⇒ last-good + stale flag, never fail-closed for
  *reads* (writes keep the Phase 26 fail-closed guard).

## Out of scope
- A persistent/cross-restart seeding cache or DB table (deliberately in-memory + short-lived).
- Push/websocket updates of seeding state (TTL polling is enough; live torrent telemetry is not a goal).
- Per-torrent live stats streaming (ratio/peer graphs over time).
- Tuning qBittorrent itself.

## Design reference
`design/app/seeding.js` — the shared module now renders a **freshness bar** (`qBittorrent snapshot ·
updated Ns ago · auto-refreshes every 10m · ↻ Refresh now`) above every seeding view, with a
live-ticking age that flips to **stale** past the TTL and a working **Refresh now** that re-times the
snapshot. `TTL` defaults to 600s (10 min). `design/app/settings.html` — the **Seeding snapshot
freshness** control (a **Refresh every** number input + **Unit: minutes**, default 10) under Cross-seed
safety, writing `seeding_cache_ttl` (seconds) into the live `[qbittorrent]` TOML.
