# Phase 36 — Operator Controls: Per-Library Scan/Push, Scheduled Scans, Notifications (FR-OC1)

**Status:** Planned

Three related operator-ergonomics features. They can ship together or split 36a/36b/36c.

## Problem
- **Scan is all-or-nothing.** `POST /api/scan` scans every mapped library; there's no way to scan or
  push just one (e.g. after adding files to Movies only).
- **No scheduling.** `watch_enabled` reacts to filesystem events, but there's no periodic full rescan.
- **No notifications.** Background scan finish/failure and unmatched items surface only in-app (dock /
  Activity); self-hosted operators expect a webhook ping.

## Current state (as-is)
- `POST /api/scan` (global), `/scan/cancel|resume|status`. `POST /api/media/batch/jellyfin-push`
  (write all NFOs + refresh). Per-item `sync` / `repull-jellyfin` exist; per-**library** does not.
- `[behavior]` config (Phase 16) has scan workers/threads; no schedule, no notifications.
- `Logger` + `ActivityLog` (Phase 17) already centralize events that would feed notifications.

## Requirements

### A. Per-library scan & push
1. `POST /api/scan?library={jellyfinId}` (or `/api/libraries/{id}/scan`) — scan a single mapped
   library; same WS events + `ScanTracker` semantics as the global scan; 409 while a scan runs.
2. `POST /api/media/batch/jellyfin-push?library={jellyfinId}` — scope the batch NFO-write + refresh to
   one library.
3. **Settings → Library mapping:** each library row gets **Scan** and **Push to Jellyfin** buttons.

### B. Scheduled rescan
4. `[behavior]` gains `scan_schedule` (cron string; empty = off). A scheduler (coroutine timer) kicks
   `POST /api/scan` on the cron; skips if a scan is already running.
5. **Settings → Scanning:** a "Scheduled rescan" toggle + Frequency (Daily/Weekly/Custom) + time, with
   a cron field for advanced use; show "next run" + "last full scan" times.

### C. Notifications
6. New `[notifications]` config: `webhook_url`, and per-event toggles `on_scan_done`, `on_no_match`,
   `on_error` (+ optional `on_drift`, Phase 33). When `webhook_url` is set, POST a JSON payload
   (`{event, summary, counts, ts}`) on those events — generic enough for Discord/Slack/ntfy/Gotify.
7. Emit from the existing `Logger`/scan-finish/`SeedingGuard`-block/no-match points (no new event bus).
8. **Settings → Notifications (new section):** webhook URL, event toggles, **Send test notification**.

## Invariants
- A scheduled scan obeys the same single-scan lock and `SeedingGuard` as a manual one.
- Notifications are **fire-and-forget**, fail-soft (a dead webhook never blocks a scan).
- Config remains TOML via `PUT /api/config`.

## Out of scope
- Per-event message templating / multiple webhooks; retry queues.
- Per-library schedules (one global schedule first).
