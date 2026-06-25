# Phase R08 — Playback brokering + progress reporting (FR-RV8)


## Problem
Ravilo plays media **directly from Jellyfin** (data plane) but must never hold Jellyfin as its API.
jellystructure must **broker** a playback ticket (Jellyfin URL + scoped token + start position) and
**relay progress** back to Jellyfin so watched/resume/next-up stay correct on every device.

## Current state (as-is)
- After R03, jellystructure can obtain the paired user's Jellyfin access token server-side and knows
  the Jellyfin base URL. After R07, resume positions are readable.
- No playback session or progress-reporting endpoints exist.

## Requirements

### Stream brokering
1. `POST /api/tv/playback/start {itemId, capabilities}` → a **`StreamTicket`**. The device sends its
   **`ClientCapabilities`** (containers/codecs/channels it can decode — for Android, what the forked
   **`:ravilo-player`** engine + its FFmpeg decoders support; R14). jellystructure builds a Jellyfin
   **device profile** from those capabilities, calls Jellyfin's **`PlaybackInfo`**, and returns the
   ticket: Jellyfin **base URL**, a **scoped, short-lived access token**, the resolved **stream URL**
   (direct-play container **or** an HLS URL), the **start position** (resume point from R07),
   **subtitle/trickplay** info, and `expiresAt`. The device streams from Jellyfin with this;
   **jellystructure is not in the byte path**.
2. The ticket's token is minted/scoped for that user+item and **refreshable**; the device never does
   Jellyfin sign-in and never receives the user's password.

### Progress reporting (write path)
3. `POST /api/tv/playback/progress {itemId, positionMs, isPaused}` — heartbeat (e.g. every ~10s and on
   pause/seek); jellystructure relays to Jellyfin's **playback-state API** so resume points persist.
4. `POST /api/tv/playback/stop {itemId, positionMs}` — ends the session; writes the final position;
   if the item finished, lets the server mark it played and **advance the series next-up pointer**.
5. `POST /api/tv/mark {itemId, watched}` — mark played/unplayed → Jellyfin user-data. The TV requests
   it; **the TV never writes Jellyfin user-data directly.**
6. After a write, subsequent `GET /api/tv/home` / `…/series/{id}` reflect the new watched/resume state
   (no client-side patching required — though the client may optimistically update the just-watched
   item pending refresh).

## Invariants
- **Data plane = Jellyfin directly; control plane = jellystructure.** Tickets broker access; bytes
  never transit jellystructure (unless the constitution's opt-in relay is explicitly enabled).
- All watched/resume/next-up writes go **through jellystructure → Jellyfin**; never client→Jellyfin
  user-data.
- Tokens in tickets are scoped, short-lived, refreshable; password never exposed.

## Out of scope
- The player UI / engine (`RaviloPlayer` `actual`s) — R14 consumes this API.
- Transcoding policy decisions beyond choosing direct-play vs HLS (delegate to Jellyfin's defaults
  initially).
