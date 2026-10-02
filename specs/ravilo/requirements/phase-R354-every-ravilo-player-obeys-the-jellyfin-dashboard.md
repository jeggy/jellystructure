# Phase R354 — Every Ravilo player obeys the Jellyfin dashboard

> Owner, 2026-10-02: pause/play, seek, next/previous, stop and volume from the Jellyfin dashboard must reach Ravilo
> wherever it plays — the phone, the Mac/Linux app, the web app, a TV, a Chromecast, a Nest Hub, a speaker — for music
> and for films, and look exactly like a press on the device itself.

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed, not device-tested. Written 2026-10-02 (dev-authored), against `main` `3127421c`. Number given by the coordinator (Ravilo specs
top at R353). The server half is admin **296**; both ship together. The hand-back bug found with it is fixed as an
amendment to R353 (FR-R353-5).

**Amends** R155 (what a remote command does, and to which player), R293 (FR-R293-1/-5: playing media holds the events
socket off screen), R245/286 (the receiver opens the events socket), and implements phase 236's `player_command`
(FR-236-11) in the app for the commands that move playback.

## What is missing today (found 2026-10-02)

1. **The Cast receiver never opens `/api/tv/events`**, so the server has no way to reach it (296's root cause).
2. **Music ignores every remote command.** Commands reach `PlayerScreen` only (`LocalPlaystateCommands`); with no film
   open they are dropped. `MusicEngine` (Android ExoPlayer service, Mac AVPlayer, Linux mpv) has no route in.
3. **The film player knows four commands** (`stop`, `pause`, `unpause`, `seek`); `PlayPause`, `NextTrack`,
   `PreviousTrack`, `Rewind`, `FastForward` and every volume command fall on the floor, and `player_command` is only
   logged.
4. **A phone playing music off screen has no socket.** R293 closes it on `ON_STOP`/screen-off, and drops any command
   that arrives in the gap — so the dashboard can never pause the phone's music with the screen off, which is when it
   matters.
5. **No player has a volume a command can set** except the desktop's music engine (`setUserVolume`); Android's music
   `setUserVolume` is a no-op and `RaviloPlayer` has no volume at all.

## Requirements

**FR-R354-1 — Every app says what it obeys.** The app's events socket sends 296's `remote` list: `DisplayMessage,
Play, PlayState, SetVolume, VolumeUp, VolumeDown, Mute, Unmute, ToggleMute` (phone, TV, Mac, Linux, web — one
`RaviloApp`). The receiver sends `PlayState, SetVolume, VolumeUp, VolumeDown, Mute, Unmute, ToggleMute`.
`TvApiClient.connectEvents` takes it as an optional argument (absent = today's URL).

**FR-R354-2 — One reading of a command.** `RemoteCommand` (`:shared` commonMain) turns both carriers into one of:
play, pause, toggle, stop, seek to, seek by, next, previous, set volume, volume step, mute (on/off/toggle). From
`playstate_command`: `Stop`, `Pause`, `Unpause`, `PlayPause`, `Seek` (`seek_position_ms`), `NextTrack`,
`PreviousTrack`, `Rewind` (−10 s) and `FastForward` (+30 s) — the same sizes as the players' own skip buttons. From
`player_command`: phase 236's `seek {position_ms}`, `skip {delta_ms}`, `next`, `previous`, `set_volume {volume}`,
`mute {muted?}`, and 296's `volume_up`/`volume_down` (±10 points). Anything else is not a playback command and is
ignored (case-insensitive). `RemoteVolume` keeps level and mute together: a step unmutes, unmute restores the level
before the mute, levels are clamped 0–100. One `applyTo(target)` drives every player through one small interface,
so the phone, the desktop, the web app and the receiver cannot read a command differently.

**FR-R354-3 — The player that holds the screen takes it.** In the app: the open film player if there is one; else the
music player if it holds a song or an audiobook (also while it casts, through `MusicPlayback`); else nothing. Off
screen, only these commands are accepted; `play_item` and `navigate` keep R293's rule.

**FR-R354-4 — A command is a press.** Each command calls what the device's own control calls, so the screen, the
mini bar, the Playing page, the system's Now Playing / lock screen and the server's progress report all follow on
their own:
- **Film:** play/pause/toggle as the play button; seek and seek-by as the scrubber and skip buttons; *stop* as Back
  (leaves the player; the stop report is the player's own); *next* as the next-up card's *Play now* (the next episode,
  or nothing on a film); *previous* the episode before in the series' list (nothing on the first episode or a film);
  volume the player's output level.
- **Music:** `MusicPlayback`'s play/pause/toggle/seek/next/previous (R322's *previous*: the start of the song after
  3 s); *stop* stops the song and keeps the queue paused where it was (what a film taking the screen does, FR-R322-12),
  so the mini bar stays and Play resumes; an audiobook's next/previous are its ±30 s; volume the player's output level
  (on Android multiplied with *Even out volume*'s gain).

**FR-R354-5 — Playing media holds the socket off screen** (amends R293 FR-R293-1/-5). The events socket is wanted
while the app is on screen **or** the music player plays, and for 10 minutes after it pauses (so the dashboard can
resume it). When neither holds, it closes with `1000 background`, as today. A film player off screen does not hold it
(R292 pauses and leaves it).

**FR-R354-6 — Players have a volume.** `RaviloPlayer.setVolume(level)` (0–1) on Android (ExoPlayer), desktop (the
engine's own) and web (the video element); Android's music `setUserVolume` scales the output instead of doing nothing.

**FR-R354-7 — The receiver obeys** (`ravilo-cast`, every device it runs on: Chromecast, Google TV, Nest Hub, an
audio-only speaker; the Mac's Cast v2 client loads the same receiver, so its casts are covered too).
- It opens `/api/tv/events` with its own device token while an item is loaded (after its first enrolment), and closes
  it (`1000 idle`) when it returns to the idle view; reconnects with backoff (2 s → 30 s) in between.
- Play/pause/toggle/seek/seek-by through CAF's `PlayerManager`; *stop*: a song ends the queue (the idle view, *ended*
  to the senders, as the Stop key), a film stops and shows the idle view (*ended*, never *failed*); *next*/*previous*:
  a song by the queue's rule (R286), a film the next or previous episode the sender handed over; volume through
  `CastReceiverContext.setSystemVolumeLevel`/`setSystemVolumeMuted` — the device's own volume, the one the senders'
  sliders show.
- After every command the senders get a fresh `status`, so the phone's and the Mac's remote follow at once.
- **A paused heartbeat:** while paused, progress is reported every 30 s, so a dashboard *Pause* longer than 90 s is not
  force-stopped by the server's watchdog.
- **Older senders:** nothing a sender sends or reads changes.

**FR-R354-8 — The sender follows the receiver** — see R353 FR-R353-5 (a song changed by another controller and a cast
stopped from outside the app).

## Out of scope

The dashboard's *Play on* for music · `ravilo-screen` (Tizen paused) · volume shown back on the dashboard · system
(hardware) volume on a phone or TV (the player's level is what a command moves) · a background socket for a TV.

## Acceptance

Per device, from the Jellyfin dashboard's session card (see the verification plan in the build notes): pause, play,
play/pause, seek, next, previous, rewind/fast-forward, stop and volume act as the device's own controls do, and the
device's screen, mini bar and lock screen show it within a second. An app older than this phase ignores what it does
not know, as before.

## Dev review (2026-10-02, against `main` `3127421c`)

1. **Where commands arrive.** `RaviloApp` runs the one events loop for every platform (phone, TV, Mac, Linux, web);
   `connectEvents`' callbacks run off the main thread, and `MusicEngine` is main-thread only (ExoPlayer throws off it,
   R353's crash). So commands go through a `SharedFlow` collected in a `LaunchedEffect` (the main thread), as R155's
   film path already does.
2. **Telling a film from music.** The film player registers itself while composed (`RemoteVideoCommands`, its own
   small composable so `PlayerScreen`'s method does not grow: R258's register-count verifier and
   `check-player-dex.sh`). With none registered, music takes the command.
3. **`MusicPlayback` is the right door** for music: it routes to the receiver while the phone casts and to the engine
   otherwise. While the phone casts, the phone's own Jellyfin session is not the one playing (the receiver's is), so
   the dashboard drives the receiver's; a command that still reaches the phone's session simply moves the cast.
4. **R293's reasons still hold for everything but playing media.** R293 closed the socket off screen because a TV in
   the background cannot honestly act on a command and some TVs flapped. Music playing in the background is the
   opposite case: the media runs, the foreground media service keeps the process and its network, and a pause is
   exactly what the viewer wants. The 10-minute tail bounds it.
5. **The receiver's volume is the device's.** CAF's `CastReceiverContext` has `getSystemVolume`,
   `setSystemVolumeLevel` and `setSystemVolumeMuted`; that is the volume the senders' sliders already read and set
   (R353 FR-R353-4), so the dashboard and the phone agree.
6. **Test placement.** The receiver has no test source set; the pure parts (`RemoteCommand`, `RemoteVolume`,
   `applyTo`) live in `:shared` commonMain with tests in `commonTest` (CI: `:shared:desktopTest`). The app's routing
   (`RemoteControl`) and the socket rule (`MediaSocketHold`) are commonMain with `commonTest` (CI:
   `:ravilo-ui:testDebugUnitTest` and `:ravilo-ui:desktopTest`).

## Build notes (2026-10-02)

**Built 2026-10-02, not deployed, not device-tested.** Commits `d4479b20` (app), `a7021fd5` (receiver), `209e631c`
(R353 FR-R353-5), on `main` `3127421c`.

1. **FR-R354-1** — `REMOTE_DECLARATION_APP` / `_RECEIVER` (`:shared` `RemoteCommand.kt`); `TvApiClient.connectEvents`
   takes `remote` and appends `remote=` (after `?token=` on a browser build; `check-events-query-token.sh` still
   finds its gate). `RaviloApp` and the receiver pass theirs.
2. **FR-R354-2** — `RemoteCommand`, `remoteCommandOf(PlaystateCommandEnvelope|PlayerCommandEnvelope)`, `RemotePlayer`,
   `RemoteVolume`, `applyTo` (`:shared` commonMain).
3. **FR-R354-3** — `RemoteControl` (`ravilo-ui` commonMain): `RaviloApp` turns both carriers into `RemoteCommand`s on
   a `SharedFlow` collected on the main thread and calls `RemoteControl.dispatch`; the film player registers with
   `attachVideo` while composed (`RemoteVideoCommands`, its own composable), else `MusicRemotePlayer` when
   `MusicPlayback.state.active`. `LocalPlaystateCommands` is gone (PlayerScreen was its only reader).
4. **FR-R354-4** — film: play/pause through the play button's `togglePlay` (only when the state differs), seek as the
   scrubber, ±10/30 s as `skip`, stop = `onBack`, next = `advanceNext` (only when there is a next episode), previous =
   the episode before in the player's list. Music: `MusicPlayback` (speaker while casting); stop =
   `MusicEngine.stopForVideo()` (or *Stop casting* while linked); seek-by on a song clamps to it, on a book is
   `skipBy`.
5. **FR-R354-5** — `MediaSocketHold` + `acceptsPlayerCommand` (`seams/EventsCatchUp.kt`); `RaviloApp`'s socket loop
   waits on `socketWanted` (on screen, or the music engine playing / paused < 10 min, re-evaluated on every state
   change and every 30 s) instead of `onScreenFlow`. `play_item`/`navigate` keep R293's gate.
6. **FR-R354-6** — `RaviloPlayer.setVolume` (Android: ExoPlayer volume, re-applied to a rebuilt engine; desktop: the
   engine's; web: the video element's). Android's `MusicEngine.setUserVolume` multiplies into every place the output
   was set (song gain, a book's 1.0, the sleep fade). On the desktop the music level is the bar's (`MusicVolume.set`,
   the slider follows); a mute there silences the engine and leaves the slider and the remembered level alone.
7. **FR-R354-7** — `Receiver.kt`: `HttpClient(Js) { install(WebSockets) }`; `eventsKey` (server + token while an item
   is loaded, set from `sendStatus`, cleared at `idle()`), `eventLoop` (closes with `idle` when the key changes,
   2 s → 30 s backoff); `onRemote` syncs `remoteVolume` from `context.getSystemVolume()`, asks
   `receiverRemoteAction` (`:shared` `ReceiverRemote.kt`) and carries it out (`stopFilm` sends *ended* and sets
   `current = null` before the player's stop; `loadEpisode(±1)` generalises `loadNext`), then `sendStatus()`;
   `beatWhilePaused` every 30 s. Each command is logged on the log channel (`remote … -> …`).

**Deviations:** a film's *previous* is the episode before, not "restart within 3 s" (that rule stays music's); a
dashboard *Stop* on music keeps the queue paused rather than clearing it; volume is the player's level, not a phone's
or a TV's hardware volume. `ravilo-screen` unchanged (Tizen paused).

**Tests (all in existing CI tasks; no ci.yml change was needed):**
- `:shared` commonTest — `RemoteCommandTest` (5) and `ReceiverRemoteTest` (4): run by `:shared:desktopTest`
  (android-release job) and `:shared:linuxX64Test` (unit job's `./gradlew linuxX64Test`).
- `:ravilo-ui` commonTest — `RemoteControlTest` (7: routing film → music → nobody, an older film's detach, every
  dashboard command on music, a volume kept for the next film, the 10-minute socket hold, the off-screen gates) and
  `CastHandBackTest` (5, R353): run by `:ravilo-ui:testDebugUnitTest` and `:ravilo-ui:desktopTest`.
- Totals this round: `:ravilo-ui` Android 380 / desktop 336, `:shared` desktop 88 / linuxX64 101, backend 846 — 0
  failures. Built: `:ravilo-android:assembleRelease`, `:ravilo-web:compileKotlinWasmJs`,
  `:ravilo-cast:jsBrowserProductionWebpack`, `:ravilo-desktop:compileKotlinDesktop` (both with and without
  `-Pravilo.desktopOnly=true`), `:ravilo-castv2:jvmTest`. `check-player-dex.sh`: 242 registers (limit 250).

**Not covered by CI:** the receiver's CAF glue itself (no JS test target; the e2e fake CAF has no
play/pause/volume — a Playwright spec that pushes a Playstate frame from the mock Jellyfin's `/socket` would be the
next step) and the Android/desktop players' real output.

### Device verification plan (for the main session)

Where: Jellyfin web → the **cast/remote icon** (top bar) → pick the session → the now-playing bar and its *Remote
control* page (play/pause, stop, previous/next, seek bar, volume slider, mute); or Dashboard → *Active devices*. First
read `/Sessions` (read-only, server token): every Ravilo session that is on screen or playing reads
`SupportsRemoteControl: true`; an app's `SupportedCommands` = `DisplayMessage, Play, PlayState, SetVolume, VolumeUp,
VolumeDown, Mute, Unmute, ToggleMute`, a receiver's = `PlayState, SetVolume, VolumeUp, VolumeDown, Mute, Unmute,
ToggleMute` with `PlayableMediaTypes: []`. `/api/health/full`'s `session_bridges` lists each device's `commands`.

- **Pixel 9 (debug; media volume 0 throughout):** music mode, play an album. Dashboard *pause* → the mini bar, the
  Playing page and the lock-screen card show paused within ~1 s; *play* resumes; *next*/*previous* change song (previous
  after 3 s restarts the song); drag the seek bar → the Playing page's position jumps; *stop* → the song stops, the
  mini bar stays, paused at that place, Play resumes; volume slider/mute → check the engine (no sound expected at
  media volume 0; the Playing page shows no change — acceptable). **Lock the phone while playing**, then dashboard
  *pause* and *play* → act on the music (the socket stays open: the server log has no `client close 1000 background`
  while it plays). Leave it paused and locked 10+ minutes → `client close 1000 background`, ~90 s later the session
  leaves `/Sessions`. A film: pause/play, seek, *fast forward* (+30 s) / *rewind* (−10 s), *next* on an episode → the
  next episode starts, *previous* → the one before, volume 20 → quieter, *stop* → the player closes to the detail page.
- **Stue TV (Android TV, release) — if allowed:** a film/episode: the same film commands; the TV's own remote keeps
  working after; off screen (HOME) the session leaves `/Sessions` ~90 s later and commands do nothing.
- **Mac app:** music — the dashboard's volume slider moves the bar's slider; mute silences without moving it;
  pause/next/seek/stop as on the phone; close the window while music plays → still controllable. A film — as on the
  phone. **Linux (`~/fedora`)**: the same with mpv.
- **Web (`ravilo-web` in a browser):** films only (no music on the web): pause/play/seek/±/next episode/stop/volume.
- **Cast receiver — music to the Stue speaker from the Pixel:** `/Sessions` shows *Chromecast via Ravilo · Stue* with
  `SupportsRemoteControl: true`. Dashboard *pause* → the speaker pauses and the phone's mini bar shows paused within
  ~1 s; *play*; *next*/*previous* → the speaker changes song and the phone's mini bar and queue follow; seek; volume
  20 → the speaker's volume 20 % and the phone's ⋯ slider shows it; mute/unmute; leave it paused > 2 minutes → the
  session stays (paused heartbeat); *stop* → the speaker goes idle, the phone reads ended, and within ~90 s + one sweep
  the session leaves `/Sessions`. Then **R353 FR-R353-5**: cast again, Google Home *next*, Google Home *Stop cast* →
  the phone's mini bar shows the speaker's song, paused at its place.
- **Cast receiver — a film or an episode to a TV Chromecast / the Nest Hub (from the Pixel, then from the Mac):**
  pause/play/seek/±; *next* → the next episode; *previous* → the episode before (nothing on the first); *stop* → the
  TV shows the idle screen and the phone reads ended (never "couldn't play"); volume moves the TV's cast volume and the
  sender's slider.
- **Old sessions:** right after deploy, the 2026-09-30 leftovers leave `/Sessions` within ~2 minutes (one
  `Jellyfin session ended` line each in the backend log).
