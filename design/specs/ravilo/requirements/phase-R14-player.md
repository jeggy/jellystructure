# Phase R14 — Player: `RaviloPlayer` expect/actual (FR-RV14)

**Status:** Planned · _play the bytes, from Jellyfin, report progress to jellystructure._

## Problem
Ravilo must play media **directly from Jellyfin** (data plane) on both targets, with one shared player
UI/chrome, resume, and progress reporting — while the byte stream never transits jellystructure.

## Current state (as-is)
- R08 provides `playback/start` (→ `StreamTicket`), `playback/progress`, `playback/stop`, `mark`.
  `:ravilo-ui` declares `expect RaviloPlayer` (R02). No real player.

## Requirements

### Engine (`actual`s)
1. `actual RaviloPlayer` on **Android** = **Media3/ExoPlayer**; on **Web** = an HTML5 `<video>`/MSE
   element bridged to Compose. Both consume the `StreamTicket` (direct-play container or HLS URL +
   Jellyfin base + scoped token) from `POST /api/tv/playback/start`.
2. The player streams **directly from Jellyfin** using the ticket; jellystructure is not in the byte
   path. Start at the ticket's **resume position**.

### Chrome (shared Compose)
3. A focus-aware player overlay: play/pause, seek bar with scrubbing, skip ±10s, position/duration,
   title (and S·E for episodes), and — for series — a **"Next episode"** affordance near the end.
4. D-pad / key / pointer controls; auto-hiding chrome; **Back** exits playback to the detail screen.

### Reporting & binge
5. Send `playback/progress` heartbeats (~10s, on pause/seek); `playback/stop` on exit with the final
   position. On finishing an episode, offer/auto-advance to the **next-up** episode (server pointer),
   and let the server mark played + advance next-up.
6. After exit, the detail/home state reflects the new watched/resume position on next load (optionally
   optimistic in the just-watched item).

## Invariants
- **Data plane = Jellyfin directly**; control/report = jellystructure (`/api/tv/**`). Bytes never
  transit jellystructure (unless the constitution's opt-in relay is enabled).
- One shared player UI; engine is the only platform-specific part (`actual`).
- Progress/played writes go through jellystructure → Jellyfin, never client→Jellyfin.

## Out of scope
- Transcoding/quality selection beyond direct-play vs HLS (Jellyfin defaults initially).
- Offline/downloads; PiP.
