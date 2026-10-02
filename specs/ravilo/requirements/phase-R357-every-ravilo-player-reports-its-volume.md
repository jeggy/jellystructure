# Phase R357 — Every Ravilo player tells Jellyfin its volume and whether it is muted

> Owner, 2026-10-02 (after the dashboard tests of 299/R354): *"What does this mean 'its Jellyfin session reports no
> volume'?"* — then *"Lets fix it"*.

## Status

`Planned` — written 2026-10-02 (dev-authored, owner-approved the same day) against `main` `91a12ee8`. Number checked
free (Ravilo specs top at R356). **Amends** R354 (players got a volume, but never said what it is) and 299 (the
progress a device reports is forwarded to Jellyfin unchanged except for these two fields). Shared wire models, the
backend's progress forwarding, the film player, the music player (phone, desktop, web) and the Cast receiver.
Additive wire change only; no new string; no UI change.

## What is wrong

Since R354 every Ravilo session tells Jellyfin it obeys `SetVolume`, `Mute`, `Unmute`, `ToggleMute`, `VolumeUp` and
`VolumeDown`, and obeys them. But the playback reports the app sends (`POST /api/tv/playback/progress`, forwarded by
the backend to Jellyfin's `POST /Sessions/Playing/Progress`) carry only the position and the pause. Jellyfin's
session therefore never has a `PlayState.VolumeLevel` (always absent) and `PlayState.IsMuted` is always false, even
right after the dashboard muted the device. Seen live on the Mac (music) and the Ravilo web app (a film), 2026-10-02.
The dashboard can command the volume but cannot show it.

## Requirements

- **FR-R357-1 — the wire.** `PlaybackProgressRequest` gains two optional fields, `volume_percent: Int?` (0–100) and
  `muted: Boolean?`, both absent by default. An app that predates this phase sends neither; a backend that predates
  it ignores them (it decodes with unknown keys ignored — verify, and if it does not, the client must not send them
  to an old server). Nothing is removed or renamed.
- **FR-R357-2 — the backend forwards them.** When a progress report carries them, the backend's Jellyfin progress
  body (both `reportPlaybackProgress` and the 219 writer's `postPlaybackProgress`, so a queued report keeps them)
  adds `"VolumeLevel": <0–100>` and `"IsMuted": <bool>`. When absent, the body is byte for byte today's. Clamped to
  0–100 server-side. The 219 writer's coalescing keeps the latest values.
- **FR-R357-3 — what each player reports.** The level is the one the dashboard's commands move, so a `SetVolume 35`
  reads back as 35:
  - **Film player:** `RemoteControl.videoVolume` (percent + muted).
  - **Music, playing on this device:** the music player's own level — on a desktop the bar's slider level
    (`MusicVolume`) and the mute R354 applies; on a phone and the web the player's output level for the session
    (`RemoteControl.musicVolume`, synced the way R354 syncs it before a command).
  - **Music cast to a speaker from this device:** the progress is the receiver's to report (below); the sender does
    not report the speaker's level as its own.
  - **Cast receiver:** the device's own volume (`CastReceiverContext.getSystemVolume()` level × 100 and muted), the
    level its senders' sliders show and R354's commands move.
- **FR-R357-4 — sent with every report, and once at a change.** The two fields ride every progress report a player
  already sends. A volume or mute change from any source the app sees (a dashboard command, the desktop slider, the
  receiver's system-volume event) also sends one progress report at once, so the dashboard does not wait for the next
  heartbeat. No new timer.
- **FR-R357-5 — honest only.** A player that cannot know its level sends neither field (never a guessed 100). A
  phone's hardware volume keys move the system stream, not the player's level; Ravilo does not report the system
  stream as the player's level.
- **FR-R357-6 — what is not changed: `PlayableMediaTypes`.** A session keeps listing `Video` only (when it takes
  *Play on*). Listing `Audio` would make the dashboard offer to send songs to Ravilo, and today a `play_item` for a
  song opens the film player. Playing a song sent from the dashboard is its own later phase.

## Verification

- Unit: the backend's progress body with and without the fields (byte-identical without); the client request
  encoding; each player's report after `SetVolume 35`, `Mute`, `ToggleMute`.
- Live: after a deploy, a dashboard `SetVolume 35` then `Mute` to the Mac (music), the Ravilo web app (a film) and
  a Cast receiver; `GET /Sessions` shows `PlayState.VolumeLevel = 35` and `IsMuted = true` within a few seconds.
  Never on the Stue speaker, the Stue TV or the Soveværelse TV.
