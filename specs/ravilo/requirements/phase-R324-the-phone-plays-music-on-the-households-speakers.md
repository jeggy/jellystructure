# Phase R324 — The phone plays music on the household's speakers

> Owner, 2026-09-28: Ravilo music plays on the Cast speakers, keeps playing when the phone is closed, and is picked
> up again on reopen.

## Status

`✓ Built` 2026-09-29 (§Build notes; commit `86addb87`) — written 2026-09-28 from `research-reports/music-cast-to-speakers-2026-09-28.md` (§6.6–§6.11, §7, §8)
and the mockups `design/ravilo/Speakers - Directions.html` (round 1) and the build in `design/ravilo/Ravilo
Mobile.html` → `mobile/ravilo-speakers.js` (+ the `spk-*` rules in `mobile/ravilo-music.css`). **Dev-reviewed 2026-09-28** against `main` `32daeee2` (§Dev review).
Number verified free on `main` 2026-09-28. The receiver side is admin **286**; both ship together.

**Builds on** R265 (the sheet) · R270 (a busy TV names its viewer) · R245 (hand-off, reconnect) · R321/R322 (the
listening mode, the Playing tab, the mini bar). Android only: no browser has a Cast sender.

## Decisions (owner, 2026-09-28)

| # | Question | Answer |
|---|---|---|
| Q1 | Starting a cast from an album | **Replaces the speaker's queue** with that album; *Add to queue* still appends |
| Q2 | Swipe-down on the mini bar while casting | **Only hides the bar** — the room plays on; a toast says so with *Stop* |
| Q3 | Volume | **The phone's keys + a slider in ⋯** |
| Q7 | The iPhone | **One footnote in the sheet** (*best experience*) |
| — | A speaker busy with another app | **Tappable**, and it asks *Stop Spotify and play here?* first (owner) |
| — | The sheet's title in music mode | ***Play on…*** (a speaker is not a TV); video mode keeps *Play on a TV* |

## Requirements

**FR-R324-1 — Speakers in R265's sheet, music mode only.** The sheet is R265's, unchanged in shape — *All your TVs
(n)*, *Add a TV* and the AirPlay footnote stay. In music mode it is titled **Play on…** and *On this network* lists
the Cast audio routes first: a speaker (speaker glyph · *Speaker · Ready*), a group (group glyph · *Speaker group ·
Ready*), a display (the hub), then the TVs as before. **In video mode no audio-only route is listed, and nothing
says so** (Google's rule; R265's absent-not-empty).

**FR-R324-2 — Row states.** Ready · **Playing · {song}** (a speaker already playing Ravilo — tapping it **joins**:
this phone becomes a second remote for the same queue, nothing restarts) · **Busy · {app}** (the Cast status's
running-app name). A busy row is **tappable**: it opens a small sheet — ***Stop {app} and play here?*** · *{device} is
playing {app}. Playing here stops it for whoever started it.* · **Play on {device}** · *Cancel*.

**FR-R324-3 — Hand-off both ways.** Casting a song that is playing on the phone hands **position and queue** over
(R245 FR-R245-4 for music); the bar says *Sending to Stue…* → *Playing on Stue* (~2 s) and retires. *Play on this
phone* pulls position and queue back and stops the speaker. Q1: starting an album while casting replaces the
speaker's queue.

**FR-R324-4 — The Playing tab is the remote.** Under the artist, a device chip *Playing on Stue* (tap → the sheet);
the cast glyph lit; transport acts on the receiver; the position is the receiver's report; the Queue tab mirrors
the receiver's queue (drag and remove are sent to it) with *on Stue* by its label. **Lyrics stay on the phone** and
scroll by the receiver's position.

**FR-R324-5 — ⋯ while casting** gains, above the song's own entries, a block titled with the device: a **volume
slider** in 5 % steps · **Lyrics on {device}** (displays only; a switch, *Off* at first, remembered per display;
disabled with *This song has no timed lyrics* when it has none — 286 FR-286-6) · **Play on this phone** · **Stop
casting** (in the warning colour).

**FR-R324-6 — The phone's volume keys drive the speaker** (the cast session hands them over); Android's own panel,
named for the speaker.

**FR-R324-7 — The mini bar** reads *{song} · {artist} · {speaker glyph} Stue*, the device never truncated before the
artist. **Swipe-down hides the bar only** (Q2): the room keeps playing and a toast says ***Still playing on Stue*** ·
**Stop** (5 s). Opening Playing, or starting a song, brings the bar back. With a film casting at the same time, the
two bars stack, the film's on top (R322's stacked frame).

**FR-R324-8 — Reconnect** is FR-R245-5 unchanged: on reopen, either the bar shows **the song the room is on now**
at its position (Playing and Queue rebuilt from the receiver), or — the queue ended while the phone was away —
nothing at all, and Playing shows the last-played song paused (R322 FR-R322-3). No *Reconnecting…*, no *the speaker
finished* line.

**FR-R324-9 — The lock screen** while a cast runs is the Cast SDK's notification: cover · title · artist with
play/pause · previous · next · stop casting (music actions, not ±30 s), replacing R322's own media session.

**FR-R324-10 — The iPhone** lists no speakers, groups or hub, and says once, under the rows: ***Speakers need the
Android app for now***.

**FR-R324-11 — Strings** × en · da · fo (da/fo drafts in `ravilo-i18n.js`; the shipped `i18n/*.json` wins):
`cast_sheet_music` *Play on…* · `cast_speaker` *Speaker* · `cast_group` *Speaker group* · `cast_busy_with` *Busy ·
{app}* · `cast_take_over` *Stop {app} and play here?* · `cast_take_over_sub` · `cast_play_on` *Play on {device}* ·
`cast_speakers_ios` · `cast_still_playing` *Still playing on {device}* · `cast_stop_room` *Stop* · `cast_lyrics_on`
*Lyrics on {device}*. `cast_play_here` (*Play on this phone*) is R299's, reused.

## Out of scope

The iPhone reaching a speaker (needs 286's road C) · AirPlay speakers · casting a film to a speaker.

## Acceptance

1. Pixel 9, music mode: the sheet is titled *Play on…* and lists Stue, Gæsteværelse (*Busy · Spotify*), the group,
   the hub and the TVs; in video mode only the hub and the TVs.
2. Tapping Gæsteværelse asks first; confirming plays there and Spotify stops.
3. A song playing on the phone moves to Stue at the same position; *Play on this phone* brings it back.
4. The volume keys move Stue's volume; the ⋯ slider agrees.
5. Swiping the bar down leaves Stue playing and shows the toast; *Stop* stops it.
6. Locked for an hour and reopened: the bar shows the song Stue is playing now.
7. iPhone: no speaker rows, one footnote.

## Dev review (2026-09-28, against `main` `32daeee2`)

Buildable. The sheet, the sender and the music engine all exist; what is missing is that a route knows what kind of
device it is, and that the music engine can hand its queue to the sender. Twelve items.

1. **A route carries no kind.** `CastRoute(id, name, selected, select)` (`CastSender.kt:89`); `CastRoutesAndroid.kt`
   filters `MediaRouter.routes` by the app's category. Add `kind` (display · speaker · group) from
   `CastDevice.getFromBundle(route.extras)` → `hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)` (false ⇒ speaker) and
   `route.deviceType == RouteInfo.DEVICE_TYPE_GROUP`; and `busyWith` from `route.description` (the Cast route provider
   publishes the running receiver app's name there — **verify on the Pixel with Spotify on Gæsteværelse**; if it is
   empty, the row reads *Busy* with no name, never a guess). Audio routes appear only once 286's box is ticked and
   the speaker has restarted — nothing to do on the phone for discovery.
2. **Video mode hides audio routes (FR-R324-1):** `ScreensSheetBody`'s `castRows` drop `speaker`/`group` when the sheet
   is opened outside music mode; the hub (a display) stays in both. The sheet's title switches on the same flag.
3. **Take-over.** Selecting a route where another app runs launches ours — that *is* the stop; the confirm sheet is
   the only gate. A route already running Ravilo: the SDK joins the running session (R265's build notes confirm the
   rejoin), `onSessionStarted` arrives with a media status, and Playing/Queue rebuild from `CastReceiverMessage.queue`
   (286 item 3).
4. **Hand-off (FR-R324-3).** `MusicEngine` (commonMain) is the phone's player. Casting = the engine stops locally and
   `cast.sender.load(CastLoadData(tracks…, currentIndex, positionMs))`; while linked, a `MusicCastBridge` in
   `ravilo-ui` mirrors the engine's observable state from the receiver's reports (position from the
   `RemoteMediaClient.ProgressListener` already wired at `CastSenderAndroid.kt:122`, the queue from the receiver's
   snapshot). *Play on this phone*: `loadPaused(tracks, index, position)` + `play()`, then `sender.stop()`.
5. **Queue edits go to the receiver:** `CastCommand` gains `queue_move(from, to)`, `queue_remove(index)`,
   `queue_add(track)`, `queue_play_next(track)`, `repeat`, `shuffle`, `lyrics(on)` — the existing
   `command(type, index, size)` shape, extended (additive).
6. **Volume (FR-R324-5/6).** The slider is `CastSession.setVolume(0.0–1.0)` in 0.05 steps. The phone's keys already
   reach the selected route through `MediaRouter` while the app is in the foreground (API ≥ 16) — nothing to add;
   Android's own panel names the route.
7. **Lock screen (FR-R324-9).** `NotificationOptions` is app-wide (`CastSenderAndroid.kt:41–52`: toggle · rewind ·
   forward · stop, 30 s steps). Music needs previous/next: a `NotificationActionsProvider` that returns the music set
   when the loaded media's metadata type is `MEDIA_TYPE_MUSIC_TRACK`, else today's. R322's `RaviloMusicService`
   media session releases while a cast runs (R192's rule for video), or two cards appear.
8. **Mini bar, swipe, toast, stacking:** R322's composables; the `· Stue` suffix, the toast and *Stop* are the listed
   strings; the stacked-under-a-film frame exists in R322.
9. **Reconnect (FR-R324-8)** is FR-R245-5 as built for video — one foreground resume at start-up
   (`setEnableReconnectionService(false)`); the queue rebuild needs `CastReceiverMessage.queue` on `onSessionResumed`
   (286 item 3). A phone that was killed is R265's open item, unchanged here.
10. **iPhone and web (FR-R324-10):** `rememberCastRoutes` is `expect`; the iOS and web actuals return no routes ✓;
    the footnote is a string.
11. **Strings:** the eleven keys → `i18n/en.json` (`cast.sheet_music` …); da/fo drafts from `ravilo-i18n.js`, the
    shipped table wins (R279).
12. **Wire:** shares 286's additive shapes; the music-mode sheet is offered only when `RaviloConfig.cast.music` is
    true (286 item 10), so a new app against an older server keeps today's sheet. **Not the same bug as R327:** the
    server's receiver *records* (`kind = cast`) never appear in this sheet — speakers are routes.

## Build notes (2026-09-29)

Built from the dev review (commit `86addb87`, together with 286):

1. **FR-R324-1/2** — `CastRoute.kind` (from `CastDevice.CAPABILITY_VIDEO_OUT` and the group route type) and
   `busyWith` (the route's description — verify against Spotify on a speaker; empty ⇒ *Ready*, never a guess). In music
   mode the sheet is *Play on…*, the audio routes lead *On this network* (speaker glyph · *Speaker · Ready*, group glyph
   · *Speaker group · …*), a busy route opens *Stop {app} and play here?* with *Play on {device}* · *Cancel*; a route
   already running Ravilo reads *Playing Ravilo* and tapping joins. Video mode lists no audio route and says nothing.
   The app bar's glyph opens the music-mode sheet through `LocalMusicMode`; `RaviloConfig.cast.music` gates it
   (`CastController.musicEnabled`).
2. **FR-R324-3/4** — `MusicCast` (the bridge) and `MusicPlayback` (the facade every music screen now calls): the
   session connecting from the music sheet hands the engine's queue and position over (`castMusic`) and the engine
   stops with its queue parked; while linked, the Playing tab, the Queue tab and the mini bar are rebuilt from the
   receiver's snapshot and every command is a `CastCommand` (`prev` · `play_at` · `queue_move` · `queue_remove` ·
   `queue_add` · `queue_play_next` · `repeat` · `shuffle` · `lyrics`); an album started while casting replaces the
   speaker's queue (Q1). *Playing on {device}* chip under the credits; lyrics stay on the phone and follow the
   receiver's position.
3. **FR-R324-5/6** — the ⋯ sheet's block titled with the device: a volume slider in 5 % steps (`CastSession.setVolume`),
   *Lyrics on {device}* only when the receiver reports `lyrics_on` (a display), disabled with *This song has no timed
   lyrics*; *Play on this phone* (queue and position back into the engine, the speaker stops); *Stop casting* in the
   warning colour. The volume keys already reach the selected route through `MediaRouter`.
4. **FR-R324-7** — the mini bar's *{artist} · 🔈 {device}* (the device never truncated before the artist); swipe-down
   only hides the bar (`MusicCast.barHidden`) with the *Still playing on {device}* · **Stop** toast (5 s); opening
   Playing or a new song brings it back; the film cast bar no longer shows for a music cast (`castMiniBarVisible`).
5. **FR-R324-8** — reconnect is R245's: the receiver's `status` on resume carries the queue, so the bar shows the song
   the room is on; a finished queue is silence (no bar), and Playing shows the engine's last paused song.
6. **FR-R324-9** — `NotificationActionsProvider`: previous · play/pause · next · stop for a `MEDIA_TYPE_MUSIC_TRACK`,
   R245's four for a film.
7. **FR-R324-10/11** — `hasCastSdk` (Android true, web false) shows *Speakers need the Android app for now* once, under
   the rows; twelve strings × en/da/fo (`cast.sheet_music` … `cast.volume`, plus `cast.no_timed_lyrics`).

**Owed to a device:** every acceptance step (the Pixel 9 was not available this round); in particular the route
description's contents, the take-over's actual stop, and Google's `QUEUE_*` messages from the Home app.

Not deployed and not device-tested: the owner withdrew backend-restart and device permission on 2026-09-29, mid-round. Verified by compile (`compileKotlinLinuxX64` · `compileKotlinWasmJs` · `:ravilo-ui:compileDebugKotlinAndroid` · `:ravilo-web:compileKotlinWasmJs` · `:ravilo-cast:compileKotlinJs`), the unit tests named below, and the six fences.

### Verified locally (2026-09-29, Android emulator, no Cast hardware)

The debug build on a Pixel 9 AVD (API 35, Google APIs) against the local backend: the listening mode switches, Listen
lists the seeded albums, an album plays (the e2e Jellyfin mock now streams the seeded files with `AUDIO_MAP`), the mini
bar and the Queue tab follow, the ⋯ sheet has no cast block while nothing is linked, and the sheet opened from the
app bar **and from Now playing's own glyph** (added on this pass — the design's placement) is titled *Play on…*; in
films mode it is *Play on a TV* and lists **no receiver records** (four `kind = cast` rows in the DB — R327). An
emulator sees no Cast routes (no mDNS across its NAT), so every route-dependent step — speaker rows, take-over,
hand-off, the ⋯ block, volume, the notification actions, reconnect — is still owed to the Pixel 9 and a speaker.

## Seen on a real speaker (2026-09-30, the Mac's sender — R337's third pass)

The first route-dependent steps seen on a device: the speaker row, the hand-off (the song continues on the speaker
from where the computer was), the counter and the bar, pause and seek, the speaker's own next song, volume, the
queue, *Stop casting*. Two things changed in the shared code, so the phone has them too:

- **Stop casting brings the speaker's queue back, paused where it stopped** (`MusicCast.stop`; FR-R324-5's *keeps
  what it had* used to mean the song the hand-off left behind — wrong once anything else had been started on the
  speaker). *Play on this phone* is the same with playback going on.
- **A connected speaker is where a new queue goes**, also after its own queue has ended (`MusicCast.holdsDevice`).

*Next*, *Previous*, a song picked from the queue and an album started over a playing one fail on a real device
with the receiver as deployed — 289. Still owed to the Pixel 9 and a speaker: take-over of a busy speaker, the
notification's actions, the volume keys, reconnect.

