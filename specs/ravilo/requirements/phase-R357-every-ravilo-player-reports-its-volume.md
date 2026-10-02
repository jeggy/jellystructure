# Phase R357 — Every Ravilo player tells Jellyfin its volume and whether it is muted

> Owner, 2026-10-02 (after the dashboard tests of 299/R354): *"What does this mean 'its Jellyfin session reports no
> volume'?"* — then *"Lets fix it"*.

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed, not device-tested. Written 2026-10-02 (dev-authored, owner-approved the same day) against `main` `91a12ee8`. Number checked
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

## Build notes (2026-10-02)

**Built 2026-10-02, not deployed, not device-tested.** Commits `7e92a9f5` (wire + backend), `3161c9e1` (app players),
`0457b66f` (receiver), on `main` `7b498ea6`.

1. **FR-R357-1** — `PlaybackProgressRequest` gains `volume_percent: Int?` and `muted: Boolean?` (both `null` by default,
   so `RaviloWireJson`, which does not encode defaults, sends exactly the old body without them). `progressRequest()`
   and `VolumeReport` in `:shared` `RemoteCommand.kt`; `TvApiClient.reportProgress(…, volume: VolumeReport? = null)`.
   Old server: verified — `Server.kt`'s `ContentNegotiation` has decoded with `ignoreUnknownKeys = true` since
   `86f7e69a` (2026-06-21), so no app needs to hold the fields back. **Beyond the spec:** a book's heartbeat goes
   through `PUT /tv/music/audiobook/{id}/progress`, not the progress route, so `AudiobookProgressRequest` gains the same
   two optional fields and the server's mirror (`AudiobooksTvService.mirror`) forwards them — otherwise a book playing
   on the Mac or the phone would never show its volume.
2. **FR-R357-2** — `jellyfinProgressBody()` (`JellyfinClient.kt`) is the one body for `reportPlaybackProgress` and the
   219 writer's `postPlaybackProgress`: `VolumeLevel` (clamped 0–100) and `IsMuted` appended when present, byte for byte
   the old body when not. `PlaybackService.reportProgress` clamps too; `PlaybackWriter.PendingWrite` carries the two
   values, and a coalesced progress tick keeps its own volume or else the one it replaces (a server-made write such
   as R343's write-now carries none and must not drop the player's).
3. **FR-R357-3** — film: `RemoteControl.videoVolumeReport()` (R354's `videoVolume`, kept across films) on every
   `PlayerStore` heartbeat. Music on this device: `RemoteControl.musicVolumeReport()` — on the desktop the bar's level
   (`MusicVolume`, synced as R354 syncs it) with R354's mute; on the phone the session's `musicVolume` (the player's
   output level, never `STREAM_MUSIC`); **null while `MusicCast.linked`** (the receiver reports). Both engines'
   `reportProgress()` (songs and books). The web has no music engine yet (`MusicEngineWasm` is empty), so nothing to
   report there; the web *film* player reports through `PlayerStore` like every other platform. Receiver:
   `systemVolume()` = `volumeReportOf(getSystemVolume().level, .muted)` on the 10 s tick, the pause report and the
   paused beat; `null` (fields absent) if CAF gives no level.
4. **FR-R357-4** — `RemoteControl.volumeChanged` (a `SharedFlow<RemoteTarget>`) fires after a dispatched volume command
   that reached a player, and when the desktop bar's slider/keys move the level (`MusicVolume.set(byViewer = true)`,
   which also unmutes `musicVolume` — the slider after a dashboard mute makes the music audible, and the report says
   so). `onVolumeSettled` reports once the level has held still for `VOLUME_SETTLE_MS` = 300 ms (a dragged slider is
   one report, not one per pixel). `PlayerStore` listens inside its heartbeat job; each music engine from its first
   song on (`watchVolume()` in `startTicks()`). Receiver: after a dashboard volume command and on CAF's
   `SYSTEM_VOLUME_CHANGED` (a sender's slider, the TV's own remote), settled 300 ms and never the same volume twice in a
   row; the listener is registered on its own `runCatching`, like every event this framework build may lack.
5. **FR-R357-5** — no player guesses: the receiver sends nothing without a CAF level; the sender casting music sends
   nothing; the phone's hardware keys and the handset swipe (system stream) are not reported.
6. **FR-R357-6** — untouched: `REMOTE_DECLARATION_APP` and `PlayableMediaTypes` unchanged.

**Small fix on the way:** `RemoteVolume.sync` now rounds (`roundToInt`) instead of truncating, so a device that stores
0.29 as 0.2899… reads back as 29 — else a receiver set to 29 would report 28 and the next `VolumeUp` would land on 38.

**Tests:**
- `:shared` commonTest — `VolumeReportTest` (5: the body without a volume is the pre-R357 one, the two fields with it,
  an older app's body decodes, SetVolume 35 / Mute / ToggleMute → 35/false, 35/true, 35/false, 35/true, a device level
  rounded and never guessed): `:shared:desktopTest` 99, `:shared:linuxX64Test` 112 (incl. `WireCompatTest`, the
  additions are optional) — 0 failures.
- Backend `linuxX64Test` — `JellyfinProgressBodyTest` (3: byte-identical without, both fields with, clamped),
  `PlaybackWriterTest` +1 (a coalesced tick keeps the latest volume), `AudiobooksTvServiceTest` +1 (the heartbeat's
  volume reaches the mirror): 885 tests, 0 failures.
- `:ravilo-ui` commonTest — `RemoteControlTest` +3 (the film reports 35/35-muted/35 after SetVolume 35, Mute,
  ToggleMute and announces each as a change, a non-volume command does not; music the same, nothing while casting,
  nothing announced when no player holds the command; the desktop slider after a mute unmutes and announces):
  `:ravilo-ui:desktopTest` 366, `:ravilo-ui:testDebugUnitTest` 418 — 0 failures.
- Built: `:ravilo-ui:compileKotlinDesktop` / `compileDebugKotlinAndroid` / `compileKotlinWasmJs`,
  `:ravilo-web:compileKotlinWasmJs`, `:ravilo-cast:jsBrowserProductionWebpack`, `:ravilo-desktop:compileKotlinDesktop`,
  `:ravilo-android:assembleRelease`, `:compileKotlinLinuxX64`. `check-player-dex.sh`: 241 registers (limit 250;
  `PlayerScreen.kt` itself is unchanged). `check-deanonymization.sh`, `check-ravilo-strings.sh`, `check-phases.sh` green.

**Not covered:** the receiver's CAF glue (no JS test target — `SYSTEM_VOLUME_CHANGED`'s payload shape is read as
`ev.data.level`/`.muted` with `getSystemVolume()` as the fallback, unverified on a device), the engines' collectors
(platform code), and the live check in *Verification* (`GET /Sessions` showing `VolumeLevel 35` / `IsMuted true` after
a deploy). Not done: a song sent from the dashboard (FR-R357-6, its own later phase).
