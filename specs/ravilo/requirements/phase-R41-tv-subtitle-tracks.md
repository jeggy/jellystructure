# Phase R41 — TV subtitle tracks (always-zero bug)

reliably from Jellyfin, fix the malformed subtitle URL, and make subtitle selection actually work._

## Problem
The Ravilo player never lists subtitles. Tracing the path turned up several faults that compound:
- **Backend source is fragile.** `tv/PlaybackService.kt` `buildSubtracks()` looks the item up in the
  **scanned mediaStore** by `jellyfinId` and reads its `SUBTITLE` tracks; on any miss it returns
  `emptyList()`, so `StreamTicket.subtitles` is empty. The mediaStore may not hold subtitle streams for
  the exact version being played, or the id may not match.
- **Malformed subtitle URL.** The Stream URL duplicates the id: `/Videos/$id/$id/Subtitles/…` (should be
  `/Videos/$id/Subtitles/…`).
- **Selection is a stub.** `RaviloPlayerAndroid.selectSubtitleTrack()` only toggles ignored-text flags; it
  never selects a specific track (unlike `selectAudioTrack()` which uses `TrackSelectionOverride`).
- **No player-side discovery.** The picker (`PlayerScreen`) reads subtitles **only** from
  `ticket.subtitles`; there's no `subtitleTracks` property to surface embedded subs ExoPlayer finds.

## Goal
The picker lists every available subtitle (embedded + external), they load and toggle correctly, and
"Off" works.

## Requirements
### A. Source subtitles authoritatively
Populate `StreamTicket.subtitles` from Jellyfin **`PlaybackInfo` `MediaStreams`** (Type=Subtitle) at
playback-start — the same resolution call that picks the stream — so it's correct for the actual
item/version. Use Jellyfin's delivery info to distinguish embedded vs external (sidecar) and the correct
URL/format. (Replace or supplement the mediaStore lookup.)

### B. Fix the subtitle URL
Correct the duplicated id and emit the format Jellyfin actually serves
(`/Videos/{id}/{mediaSourceId}/Subtitles/{index}/0/Stream.{ext}?api_key=…` per `DeliveryUrl`/codec, e.g.
`.vtt`/`.ass`).

### C. Real selection
Implement `selectSubtitleTrack()` to actually select the chosen track — embedded → `TrackSelectionOverride`
(mirroring `selectAudioTrack()`); external → enable the matching `SubtitleConfiguration`; "Off" → disable
text rendering.

### D. Player-discovered fallback (recommended)
Expose subtitle tracks ExoPlayer discovers in-container (a `subtitleTracks` property paralleling
`audioTracks`) so the picker still works for embedded subs even when the ticket list is empty.

## Invariants
- Control/report stays via `/api/tv/**`; subtitle URLs/bytes are Jellyfin data-plane (brokered token),
  consistent with the streaming engine.
- The picker reflects server/player truth, not invented state.

## Out of scope
- Subtitle styling/positioning UI; uploading or editing subtitles (Ravilo is read-only).

## Design reference
`tv/PlaybackService.kt` (`buildSubtracks`), `shared/.../tv/Models.kt` (`StreamTicket` / `SubTrack`),
`ravilo-ui/.../seams/RaviloPlayerAndroid.kt` (`load`, `selectSubtitleTrack`), the player chrome
`PlayerScreen` Audio & Subs picker.
