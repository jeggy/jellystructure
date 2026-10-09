# Phase R380 — The TV plays music too

> Owner, 2026-10-08, on R266's question of what happens to a music cast once a TV running Ravilo takes casts itself:
> **"TV music mode first"**, chosen over the review's lean (a second, music-only Cast app that keeps sending music to the
> web receiver). R266 does not ship until a TV running Ravilo can play a music cast in its own app.

## Status

`⚠ Partial` — written 2026-10-08 (dev-authored) from the owner's decision above and R266's re-dev review (item 5,
2026-10-08). **Dev-reviewed 2026-10-08** (see the end). **Built 2026-10-08 on the R266 branch, together with R266, not
merged** (see *Build notes*). Live-tested on Stue TV with the debug build and a host-side Cast Connect sender; **not yet
cast from a real phone**. Ravilo (TV build of `ravilo-android` + `ravilo-ui`), with a small backend part (one TV row in
*Play on…*). Designs: none for a TV music mode yet; the shapes followed are
286's *Now playing on a display* (FR-286-5/-6) and the phone's Playing page (R322).

## Why

- R266 turns Cast Connect on (`androidReceiverCompatible`). A cast to a TV that runs Ravilo then **launches the Ravilo
  TV app instead of the web receiver**, for every load from that Cast application, music included: `LaunchOptions` are
  set once per `CastContext`, so one Application ID cannot mean "the TV app for films, the web receiver for music".
- On the branch, the TV app **refuses** a music load (`castConnectPlayOf` returns null when `tracks` is not empty;
  the TV has no music mode). Today that same cast plays on the web receiver (286), so shipping R266 alone would break
  music to the household's BRAVIAs.
- The TV app already carries everything a phone uses to play music: the music engine (`RaviloMusicService`, a Media3
  `MediaSessionService`, `MusicEngineAndroid`), the queue (`MusicQueue`), the book engine (`BookPlayback`) and the
  279 routes. Only the listening *layout* is limited to phones and desktops today
  (`RaviloApp.kt`: `listeningLayout = handset || desktop`; the launch path checks `!isTvPlatform`).

## Scope (decided here)

This phase makes the TV **play what is cast to it** and shows it well. **It does not add music browsing on the TV**
(no Listen or Browse pages, no music mode switch in the TV app's own navigation): that is a separate, later phase.
Why: the owner's decision is about not breaking casts; a 10-foot music browser is its own design round (D-pad grids of
albums, search, the switch) and would hold R266 back for weeks. What is cast always comes from a phone or desktop that
already has a full music UI and stays the remote (R356, R369).

Audiobooks are in scope: a book cast from the phone's book player (R323 has a cast glyph) plays in the same engine path
and shows the same screen, with the book's own controls.

## Requirements

**FR-R380-1 — A music LOAD is accepted.** The TV app's Cast Connect receiver (R266 `CastConnectReceiver`) takes a LOAD
whose `CastLoadData.tracks` is not empty (or whose `queue_id` names a server-held queue, R359) under the same viewer rule
as a film (`castConnectVerdict`: the TV must already hold a token for the casting viewer; switch profile and back
afterwards as FR-R266-4). It hands the queue, `current_index`, `position_ms`, `repeat` and `shuffle` to the music engine
and opens the TV's Now playing (FR-R380-3). A refused load still answers with R266's media error, so the phone says so.

**FR-R380-2 — The TV's own engine plays it.** The queue plays through `RaviloMusicService` on the TV, exactly as on a
phone: each song through `POST /tv/music/play` with the TV's own device and token, progress and stop per song (279), so
*Recently played*, play counts and R358's kept place all work. A video started on the TV (or cast to it) stops the music
(R322 FR-R322-12). Direct play is the rule: the BRAVIA decodes MP3, AAC, FLAC, Opus, AC3/EAC3 and DTS itself; anything
else is the server's conversion as for a phone.

**FR-R380-3 — Now playing on the TV.** A full-screen page in the TV's skin (Aurora · Midnight · Noir), built from 286's
display screen and R322's cover-first layout, at 1920×1080 and 10-foot sizes:
- the cover large on the left; title, artist, album · year; version chips (R344, three + N); a progress line with times;
  *Next · {song}* in one quiet line;
- **synced lyrics** (279 FR-279-8) as the current line and two either side, off at first, remembered per TV (286
  FR-286-6's rule); no unsynced lyrics on a TV;
- for a book: the chapter, the chapter's progress and the whole book's (R323), speed if not 1×;
- no on-screen buttons until a key is pressed; then a transport row (⏮ ⏯ ⏭, Lyrics, Queue) that hides after 5 s.

**FR-R380-4 — The TV's remote** (the owner's TV-remote rules, CLAUDE.md 2026-09-28, as 286 FR-286-5):
- **OK / Enter** and the remote's **Play/Pause** key toggle play/pause;
- a **Stop** key, where the remote has one, stops the music and ends the session;
- **◀ ▶** previous / next song (a book: −30 s / +30 s, R323); **hold ◀ ▶** seeks 10 s;
- **▼** turns lyrics on or off; **▲** opens the queue;
- **Back** only hides the page (FR-R380-6); it never stops.
Focus follows R350 (D-pad first class): the transport row and the queue are ordinary focus groups, and nothing on the
page is reachable only by touch.

**FR-R380-5 — The queue on the TV.** ▲ opens a side panel with the queue (current song marked, the next ones, *Up next
from {album}*). OK on a row jumps to it (`jump {index}`). Reordering and removing stay on the phone (no drag on a TV). A
long queue (R359) loads in pages as on the phone.

**FR-R380-6 — Back hides; the music keeps playing.** Back from Now playing returns to where the TV was (its Home, or the
page it was on) and the music plays on, with the TV's media session (R193/R322 FR-R322-13) showing it in the system UI.
A small **now-playing pill** on the TV's app bar (cover, title, ⏯) shows while music plays; OK on it reopens Now playing.
Starting a film from the TV stops the music (FR-R380-2).

**FR-R380-7 — The phone stays the remote.** The cast session on the server (R368) has the TV app as its target; every
R369 command (play, pause, seek, next, previous, jump, shuffle, repeat, stop, queue edits) reaches the TV as a
`session_command` event on its own events socket and acts on the engine. The phone's Now playing shows what the TV
reports (R356). Volume follows R357 (the TV's own volume, reported).

**FR-R380-8 — When the queue ends.** The page shows the last song as finished for 3 s, then closes and the TV returns
to where it was; the session ends (R372). No *finished* card (286's rule).

**FR-R380-9 — Not a way into browsing.** Nothing on Now playing opens an album or artist page on the TV (that comes
with the later browsing phase). Long-press / ⋯ is not offered.

**FR-R380-10 — Strings** × en · da · fo (drafts; the shipped table wins where a string exists — most do, from R322/286):

| Key | en | da | fo |
|---|---|---|---|
| `tvmusic.now_playing` | Now playing | Afspiller nu | Spælir nú |
| `tvmusic.next` | Next · {song} | Næste · {song} | Næsta · {song} |
| `tvmusic.queue` | Queue | Kø | Bíðirøð |
| `tvmusic.lyrics_on` | Lyrics on | Sangtekst til | Sangtekstur á |
| `tvmusic.lyrics_off` | Lyrics off | Sangtekst fra | Sangtekstur av |
| `tvmusic.from_phone` | Playing from {device} | Afspilles fra {device} | Spælist frá {device} |

## Acceptance

1. With R266 on, a phone casts an album to Stue TV (debug build): the Ravilo TV app opens Now playing within 3 s and the
   first song plays; *Recently played* on the phone lists it afterwards.
2. On the TV remote: OK pauses and plays, ▶ skips, hold ◀ seeks back 10 s, ▼ shows synced lyrics, ▲ opens the queue
   and OK on a row jumps there, Back hides the page and the music plays on, the pill reopens it, Stop ends it.
3. The phone's Now playing follows every change made on the TV, and the phone's pause, next and seek act on the TV.
4. A book cast from the phone plays with its chapter shown; ◀ ▶ step 30 s.
5. Starting a film on the TV stops the music; the queue's end closes the page and ends the session.
6. Every state in Aurora, Midnight and Noir.
7. A web-receiver cast (a plain Chromecast) is unchanged.

## Tests

1. `castConnectPlayOf` / a new `castConnectMusicOf`: a LOAD with tracks (inline and server-held `queue_id`) is
   accepted for a held viewer and refused without a token; a film LOAD is unchanged.
2. The remote's key map as a pure function (key, page state → engine command): OK/Play-Pause toggle, Stop stops,
   ◀ ▶ previous/next for music and ∓30 s for a book, hold-seek, ▼ lyrics, ▲ queue, Back hides.
3. A `session_command` for each R369 op reaches the engine on a TV target (fake engine).
4. The queue's end closes the page and ends the session; Back keeps the engine playing.
5. A Compose UI test of Now playing at 1920×1080 in the three skins (screenshot), and a focus test: every control
   reachable with the D-pad.

## Non-goals

- Browsing music on the TV (Listen, Browse, albums, artists, search, the mode switch): a later phase.
- Editing the queue on the TV (reorder, remove).
- A second, music-only Cast application (declined by the owner).
- Speakers and groups (286/R324/R355 stay as they are).

## Open questions (dev)

1. R266's release order: R380 must land with or before R266's merge. Simplest: build R380 on the rebased
   `r266-cast-connect` branch and merge both together.
2. Does the BRAVIA's Back key always reach the app while music plays in the background, or can the system take it?
   (286's open point for the Chromecast with Google TV.)
3. The TV's media session already exists for video (R193); confirm one session can carry music metadata without a
   second `MediaSession` (R322 FR-R322-1 owns its own on the phone).
4. Should the screensaver (ambient mode) be held off while Now playing is open? Lean: yes while the page is open,
   no once it is hidden.

## Dev review (2026-10-08, against `main` `fdd48f10` and `r266-cast-connect` `026410fc`)

Read against `r266-cast-connect` (`CastConnectReceiver.kt`, `CastConnect.kt`, `TvPlayerSessionHooks.kt`, the `RaviloApp.kt`
and `CastSenderAndroid.kt` diffs), and on `main`: `RaviloApp.kt` (the listening layout, the events socket's `plays` and
`features`, `session_load`), `PlaybackSessions.eventsFeaturesFor`, `MusicEngineAndroid`, the manifest, `CastMessages.kt`
(the Ravilo Cast channel) and `ravilo-cast/…/Receiver.kt`'s command handler. The scope is right, and the engine half is
smaller than it looks. **But FR-R380-7 rests on a wrong premise, and that decides most of the work.** Nine items, three for
the owner.

1. **The engine half holds, and is mostly there.** `RaviloMusicService` is in the one manifest the TV build shares,
   `MusicEngine.supported` is true on Android, and `session_load` with `kind = "music"` already plays a queue on the
   device's own engine on any Android app (`RaviloApp.kt` ~1404), the TV included. What the TV lacks is the page (FR-R380-3),
   the key map (FR-R380-4) and the pill (FR-R380-6), all new UI gated on `isTvPlatform`, not on `listeningLayout` (which must
   stay false on a TV so no music *browsing* appears, FR-R380-9).

2. **FR-R380-7 is wrong as written: the phone does not control a music cast through the server.** A cast is driven over
   the **Ravilo Cast channel** (`CAST_NAMESPACE`, `urn:x-cast:dev.jellystructure.ravilo`). The phone sends `CastCommand`s
   (`next`, `prev`, `play_at`, `queue_move`, `queue_remove`, `queue_add`, `queue_play_next`, `repeat`, `shuffle`,
   `lyrics`, `status`/`get_queue`, with R358's `expect_index`/`expect_item`). The web receiver answers with
   `CastReceiverMessage` statuses (`queue`, `queue_index`, `queue_rev`, `queue_size`, `queue_part` pages for R359,
   `repeat`, `shuffle`, `lyrics_on`, `failed`), and the phone's cast remote (R356) is built from those. Standard
   play/pause/seek go through the Cast media channel. **The R266 branch never registers that channel on the TV**
   (`CastReceiverContext` has no message listener for it), so against the TV app the phone would send queue commands into
   the void and show no queue, no lyrics state and no *Next*. This is also why R266's remote lacks subtitles, audio and
   *Next episode* (its review item; the owner made that parity a release condition): those are the same channel
   (`subtitle`, `audio`, `subsize`, `next`, `nextup`). **One TV-side implementation of the Ravilo Cast channel fixes both**
   (see the owner question). R369's `session_command` path is for *other* controllers (the admin, a second phone); keep it,
   but it isn't the casting phone's road.

3. **Make the channel one piece of code, not two.** The web receiver's handler lives in `ravilo-cast` (Kotlin/JS,
   `Receiver.kt` `onCommand`, ~1360). Lift the protocol (command → state change, state → the status message, the queue
   pages, the `expect_*` checks) into a pure reducer in `shared/commonMain`, used by both the web receiver and the TV app.
   The TV then registers it with `CastReceiverContext.registerEventCallback`/`setMessageReceivedListener(CAST_NAMESPACE, …)`
   and answers with `sendMessage(CAST_NAMESPACE, senderId, …)`. Tests run once against the reducer.

4. **Hand Cast Connect the music session, not the video one.** `TvPlayerSessionHooks` tracks only the video player's
   R44 session; `CastConnectReceiver.bindSession` gives that token to `MediaManager`. A music cast must hand over
   `RaviloMusicService`'s own session token instead (play/pause/seek from the phone's standard controls, and the system UI).
   Generalise the hook to "the session the current cast drives", set by whichever engine the load started. This answers
   open question 3: one session per engine, and Cast Connect points at the one in use.

5. **Leaving the app must not end the cast while music plays.** On the branch the receiver context is started while the
   TV activity is started and stopped in `onStop` (`CastConnectReceiver.stop`). With music, FR-R380-6 keeps the engine
   playing when Back hides the page, which is fine (the activity stays). But the TV's **Home** key stops the activity, so
   the receiver stops and the phone loses its remote while the music plays on in the service. Keep the receiver started
   while the music service is playing a cast, and stop it when the music stops (see the owner question).

6. **`plays` must say music once this ships.** The TV reports `plays = video only` (`music = !isTvPlatform && …`,
   `book = …`, R370), so the server never lists a TV as a music place, and *Move to…* never offers it. Once R380 is in,
   the TV should report `music = true, book = true`. Then the same TV is reachable twice: as a Cast route (Cast Connect)
   and as a Ravilo app place (server road, `session_load`, which already plays music, item 1). R370's single-place list
   must merge the two into one row (the BRAVIA's Cast device and its Ravilo app are one place), or the sheet shows
   *Stue TV* twice (see the owner question).

7. **A music LOAD needs its own parse, and the viewer rule holds.** `castConnectPlayOf` returns null when `tracks` is not
   empty; add `castConnectMusicOf` beside it (inline tracks, or `queue_id` + `queue_total` for R359's server-held queue,
   fetched by the TV with the casting viewer's token). The viewer rule (`castConnectVerdict`) and FR-R266-4's switch-back
   work unchanged; the switch-back fires when *the music* stops, not when a player screen closes (today it keys on
   `Dest.Player` leaving the stack).

8. **Build on the rebased branch, and renumber the migration.** The branch is 5 commits on a base 46 commits behind
   `main`. Its files overlap `main` in `RaviloApp.kt` (4 later commits), `RaviloPlayerAndroid.kt` (3), `Cast.kt` (2),
   `MainActivity.kt`, `Models.kt`, and **`69.sqm`, which `main` already uses** (`main` has 69 and 70). Rebase first, give
   `CastConnectLaunch` the next free migration at merge (309 and 310 also want one), and build R380 on top. Merge the two
   together, as the spec's open question 1 says. Order inside: the shared channel reducer (item 3) → the TV channel bridge
   (films: subtitles, audio, *Next*; this closes R266's remote-parity condition) → the music LOAD, the session hand-over,
   the receiver lifetime → the TV Now playing page, keys, pill → `plays`.

9. **Smaller notes.**
   - Back (open question 2): inside the app it is always ours; the risk is only when the app isn't in front, which
     item 5 covers.
   - Screensaver (open question 4): the lean is fine. Hold it with `FLAG_KEEP_SCREEN_ON` on the page only.
   - **Tests to add:** the shared reducer (each `CastCommand` and the status it produces, the `expect_*` mismatch, the
     queue pages); the session-token hand-over (music vs video); the receiver staying started while music plays and stopping
     after; `plays` and the merged single place in R370's list.
   - The strings mostly exist (R322/286). Of the new keys, only `tvmusic.from_phone` is new copy.

**For the owner**

1. **How the phone controls a music cast on a TV running Ravilo** (item 2):
   (a) the TV app speaks the Ravilo Cast channel, the same one the web receiver speaks. One shared implementation, which
   also gives films their subtitles, audio and *Next* (R266's release condition). **Lean.**
   (b) after the LOAD the phone ends the Cast session and controls the TV through the server's session road (R369)
   instead. No channel on the TV, but the phone's cast screen becomes a session remote, and the film side still needs
   (a) for R266's parity.
2. **The TV as a music place in Ravilo's own *Play on…* list** (item 6):
   (a) yes, merged with its Cast route into one row. **Lean.**
   (b) only through Cast.
3. **The TV's Home key while a cast plays music** (item 5):
   (a) the music keeps playing in the background, and the phone stays the remote. **Lean.**
   (b) leaving the Ravilo app stops the music.

## Decided by the owner (2026-10-08, after the dev review)

1. **The phone keeps the same remote against anything** (Q1, the owner's words: *"the phone keeps the same nice TV
   remote as it has for Chromecast currently, but it'll work against anything"*): the receiver side of Ravilo's Cast
   message channel moves into one shared module used by the web receiver and the TV app (and any later receiver), so
   music and R266's films get the full remote: queue, next/previous, subtitles, audio, Next episode.
2. **The TV appears once in Play on…** (Q2): its Cast entry and its server entry merge into one row; music and
   audiobooks are offered.
3. **Home stops the music** (Q3, against the lean): leaving Ravilo on the TV ends the music cast; the phone's remote
   closes quietly (as R245's ended session does).

## Build notes (2026-10-08)

Built on the R266 branch (`worktree-agent-a97c82f5673ccb803`, rebased from `r266-cast-connect` onto `main` `97b9e588`),
in the same commits' series as R266's remote work, because the owner's decision 1 makes them one piece: **one
receiver-side module for Ravilo's Cast channel**, used by the web receiver and the TV app.

### What was built

- **One Cast channel, two receivers (owner decision 1).** `shared/…/tv/CastChannel.kt`: `castChannelStep(cmd, music)`
  turns every `CastCommand` into one `CastChannelStep` (status, stale, queue part, next/previous, play at, queue edit,
  repeat, shuffle, lyrics, subtitle size, Next episode, next-up, audio, subtitle), plus `castCommandIsStale` (R369's
  `expect_index`/`expect_item`), `castQueueEdit` and `CastQueueRevision` (R356). The web receiver
  (`ravilo-cast/…/Receiver.kt`) now dispatches through it; the TV app does too (`ravilo-ui/…/seams/TvCastChannel.kt`,
  wired by `CastConnectReceiver` through `setCustomNamespaces` + `setMessageReceivedListener`). The TV answers with the
  same `status` messages, queue windows and `queue_part`s (R359) as the web receiver, so the phone's remote is the same
  one whichever receiver plays.
- **A music LOAD is accepted (FR-R380-1).** `castConnectLoadFromJson` → `CastConnectPlay` (a film) or
  `CastConnectMusic` (`castConnectMusicOf`: tracks, current index, position, repeat, shuffle, `queue_id`), under R266's
  held-token verdict. The root (`RaviloApp.kt`) attaches the engine, loads the queue paused at the phone's place, sets
  repeat, plays, and opens `Dest.TvNowPlaying`. A film LOAD stops the music first (FR-R380-2).
- **The TV's own engine plays it (FR-R380-2).** `MusicEngine` on the TV (it was already in the build), so each song goes
  through `POST /tv/music/play` with the TV's own device and token. `MusicEngine.replaceQueue` (new, all three actuals)
  serves the phone's queue edits.
- **Now playing on the TV (FR-R380-3).** `ravilo-ui/…/music/TvNowPlaying.kt`: cover left, title, artist, album, version
  chips, progress, *Next · {song}*, synced lyrics (off at first, remembered per TV as `tv_lyrics`), a book's chapter and
  speed; no buttons until a key, then a transport row (previous · play/pause · next · Lyrics · Queue) with a ▲ ▼ hint,
  hidden after 5 s. The row is an **echo of the key just pressed**, lit on the action it did, not a focus row: the keys
  themselves carry the owner's rules, so there is nothing to move focus to (a deviation from FR-R380-4's "ordinary focus
  groups", recorded here). Icons are `DeskIcon` drawings: a ⏸ glyph rendered as a colour emoji on the BRAVIA.
- **The remote's keys (FR-R380-4)** as a pure function, `tvMusicAction(key, book)`: OK / Play-Pause toggle, Play, Pause,
  Stop (stops and ends the cast), ◀ ▶ previous/next (a book: ∓30 s), held ◀ ▶ seek 10 s, ▼ lyrics, ▲ queue, Back hides.
- **The queue (FR-R380-5)** is a side panel with its own D-pad handling: ▲ ▼ move, OK jumps (`playAt`), Back and ◀
  close it. No reordering on the TV.
- **Back hides; the pill (FR-R380-6).** `hideTvNowPlaying()` in `RaviloApp.kt` takes the page off and lands on the page
  under it, or on Home when there is none (a cast that cold-starts the app has nothing under Now playing, or only a
  sign-in/profile screen or the cast's finished film). The page reads Back itself, down and up, so the key-up cannot
  reach the activity and close the app. `TvMusicPill` sits on the app bar between the tabs and Search while music plays
  (cover, title, play/pause), in the D-pad chain; OK reopens Now playing.
- **Home stops the music (owner decision 3).** Leaving the activity stops the engine and ends the cast; the phone's
  remote closes as an ended session does.
- **The phone stays the remote (FR-R380-7, owner decision 1).** Through the Cast channel above, not the events socket:
  play/pause/seek arrive as the standard Cast media commands on the music engine's own session (`TvMusicSession`: its
  token goes to `MediaManager.setSessionCompatToken` for a music cast, the video player's for a film), and every richer
  command arrives on the Ravilo channel. Film casts get the same remote: `CastVideoSource` + `CastChannelVideoHost`
  (`screens/RemoteVideoCommands.kt`) expose the player's audio and subtitle lists (`castVideoLists`), its picks
  (`applyPick`, the picker's own path), subtitle size and Next episode. The receiver rebuilds a film LOAD's
  `MediaInfo` with explicitly no Cast tracks, so the phone sends subtitle and audio picks on the channel; a standard
  track selection that still arrives is mapped too (`onSelectTracksByType`).
- **The TV appears once in Play on… (owner decision 2).** The TV app reports its Android device name on its events
  socket (`cast_name=`, `TvDeviceName` from `Settings.Global.DEVICE_NAME`) and `features` music/audiobooks when its
  engine is supported; the server's `mergeTvApps` (`tv/PlaybackTargets.kt`) folds the Cast place of that name into the
  app's row, which keeps the app's id (a start goes to the app, R370) and gains the Cast device id.
- **FR-R380-8:** the queue's end shows the last song finished, then the page closes and the cast ends.
- **Strings (FR-R380-10):** `tvmusic.*` × en/da/fo in `i18n/*.json` (da/fo drafts as tabled).
- **R258:** `PlayerScreen`'s cast binding is one `SideEffect` closure; the release APK's widest player method is
  **245 registers** (limit 250) after it (250 before the closure).

### Tests

`CastChannelTest` (shared: every command → step, stale `expect_index`, queue edits, the revision), `CastConnectMusicTest`
(a LOAD with tracks and with a server-held `queue_id` → music; refused without a held token; a film LOAD unchanged),
`TvNowPlayingKeysTest` (the key map for music and a book), `CastVideoListsTest` (the film remote's lists, ids and the
Off row), and three `PlaybackTargetsTest` cases (the TV app and its Cast place merge into one row; no merge without a
name; two TVs stay two). Totals at the end: shared desktopTest 138/138, ravilo-ui desktopTest 564/564, backend
`linuxX64Test` green, `ravilo-cast` JS + Wasm + Android debug/release compile; `check-phases`, `check-ravilo-strings`,
`check-web-glyphs`, `check-theme-whites`, `check-mobile-css`, `check-player-dex` green. `check-deanonymization` fails on
files this phase did not touch (R291/R376/R377 specs, `CastDiscoveryRulesTest`, `TvCastSeenTest`); it adds nothing.
**Not written:** Tests 3 (a `session_command` per op on a fake engine — the commands travel the Cast channel instead,
covered by `CastChannelTest` at the decision level, not on an engine), 4 (queue end / Back keeps playing, as a test) and
5 (the Compose screenshot and focus test at 1920×1080 in three skins).

### Verified live (Stue TV, 2026-10-08, debug build `dev.jellystructure.ravilo.debug`, development Cast app)

The phone could not be driven (not reachable over adb), so the sender was a host-side Cast Connect sender
(`pychromecast`, LAUNCH with `supportedAppTypes` WEB + ANDROID_TV, then LOAD and channel messages exactly as the phone's
`CastLoadData` / `CastCommand`), against the viewer's held token:
- **Music:** a three-song queue launched the debug app from cold and opened Now playing with the first song playing;
  `status` returned the queue (3 songs, index 0, `queue_rev` 1); `next` moved to song 2; a second `next` with a stale
  `expect_index` was refused (status unchanged); `lyrics on` came back `lyrics_on: true`.
- **TV remote:** OK paused and resumed (media session state 2 → 3), ▶ skipped to the next song, ▼ toggled lyrics and
  showed the transport row, ▲ opened the queue with the playing song marked, Back closed the queue, Back hid the page
  **onto Ravilo's Home with the pill, the music still playing** (after the fixes below), → → → → reached the pill, OK
  reopened Now playing, **Home stopped the music** (session state 0).
- **Found and fixed live:** (1) Back from a cold-started Now playing left the app and stopped the music (nothing under
  the page; the page did not read Back) → `hideTvNowPlaying` + the page consumes Back; (2) the transport row overflowed
  the column and ⏸ drew as a colour emoji → narrower cover, one-line labels, drawn icons; (3) the pill's fixed title
  width pushed the avatar off the bar → `widthIn(max = 110.dp)`. Fix (2)'s final icon version and fix (3) compiled and
  passed the checks but were **not seen on the TV again** (it was put to sleep).
- **Film (R266):** see R266's build notes of the same day.
- Clean-up: every play count, played mark, resume point and last-played date the tests left on the viewer's account
  (one film, three songs) was reset through Jellyfin (`DELETE /UserPlayedItems` + `PlaybackPositionTicks 0`), checked in
  Jellyfin's database afterwards; the TV was left asleep on the launcher.

### Not verified / not built

- **A real phone casting** (acceptance 1 and 3): the phone's Now playing following the TV, *Recently played* listing the
  songs, the merged *Play on…* row (backend unit-tested, not deployed).
- **A book cast** (acceptance 4): built (book facts, ∓30 s), not cast — the phone's sender does not send audiobooks over
  Cast today.
- Hold ◀ ▶ seek, a Stop key, the queue's end (acceptance 2, 5 in part), the three skins (acceptance 6), a plain
  Chromecast after this build (acceptance 7), Soveværelse TV (its test viewer has no music library).
- *Up next from {album}* in the queue panel and `tvmusic.from_phone` are not shown; the ▲ ▼ hint does not change with the
  lyrics state; `nextup_cancel` from the phone is accepted and does nothing on the TV app (it has no next-up countdown
  card to cancel); `subsize` sets the player's subtitle size, whose effect on the TV's captions was not checked.
- **Merge:** R266's `cast_connect_launch` migration is `72.sqm` on this branch; `main` now has 72 and 73 (R381 and the
  backend batch), so it must become **74** on the rebase. R381 also edits `PlayerScreen` (QoE counter, prefetch):
  re-run `scripts/check-player-dex.sh` on the merged release APK.

## Triage (2026-10-09, against `main` `9ea5da3c`)

- **Built (branch `worktree-agent-ae4b9a6114c15ce4b`):** the ▲ ▼ hint now names what ▼ will do
  (`lyricsHintKey`: *Lyrics off* while lyrics show); the queue panel has *Up next from {album}* (`upNextHeading`,
  from the cast queue's context; *Up next* where the queue has no place to name), string `tvmusic.up_next_from`
  × en/da/fo (drafts). Tests in `TvNowPlayingKeysTest` (6 pass).
- **Not built: `tvmusic.from_phone` (*Playing from {device}*).** Nothing on the wire tells the TV which device sent
  the cast (the session view has no starter name, Cast Connect gives only a sender id); it needs a new optional field
  in the cast LOAD's customData or the session view. Left owed rather than guessed.
- **Still owed on devices:** a real phone casting (acceptance 1, 3), a book cast (the phone's sender does not send
  books over Cast — a sender change), hold ◀ ▶, a Stop key, queue end, the three skins, a plain Chromecast; tests 3
  and 5 (an engine-level command test, the Compose screenshot/focus test).

## Owner, 2026-10-09: *Playing from {device}*

> Owner: yes — add the field and show it on the TV's Now playing, with a test (never remove anything).

**Built (branch `worktree-agent-ae4b9a6114c15ce4b`):** `CastLoadData.sender_name` (optional, additive) is the sender's
own device name (`deviceDisplayName()`: *Pixel 9 Pro*, the Mac's Computer Name, *Ravilo Web*); the existing
`device_name` keeps naming the receiver's device row. `CastController` sets it on every LOAD it sends (and on a moved
LOAD) through `castLoadFromSender`, which keeps a name the LOAD already carries and drops a blank one.
`castConnectMusicOf` carries it to `CastConnectMusic.senderName`, `TvCastChannel.startMusic` keeps it (cleared when the
music ends), and the TV's Now playing shows *Playing from {device}* (`tvmusic.from_phone`) under the kicker; with no
name (an older phone) the line is left out. Tests: `CastConnectWireTest` (round trip beside `device_name`; an older LOAD
has none), `CastLoadSenderTest` (3), `CastConnectMusicTest` (the TV reads it, blank = none), `TvNowPlayingKeysTest`
(`fromLineArgs`). shared desktop 149 + linuxX64 167, ravilo-ui desktop 614 + Android 750: 0 failures; the backend and
the web receiver compile. **Owed:** seeing the line on Stue TV from a real phone cast (with the R266 phone test).

## Live, 2026-10-09 (Soveværelse TV debug 1.50-131, dev stack v1.50-130)

A music session started on the TV app through the server (`session_load`, the Pixel's sign-in by API — the Pixel
itself was taken back by its owner mid-test, so no phone-side Cast SDK music cast was run):
- **Now playing — passed:** cover, title, artist, album, progress, *Next · {song}*; on the last song no *Next* line.
- **Hold ▶ seeks — passed** (19.1 s → 31.0 s on one long press). **▼** raises the controls with the hint row
  *▲ Queue · ▼ Lyrics off*. **▲** opens the queue panel headed *Queue · Up next*.
- **Queue end — passed (FR-R380-8):** the last song finished, the page closed to Home and the session read *ended*.
- **Findings (not fixed):** (1) the panel says *Up next*, not *Up next from {album}*, and lists the song now playing
  first under it; (2) the controls row's last button is clipped to *Queu* at 1920×1080; (3) the Now playing text
  shows faintly through the queue panel.
- **Not run:** *Playing from {device}* (only a Cast Connect LOAD carries `sender_name`; the server road has none, so
  its absence here is by design) — owed with a real phone cast; the three skins on the music page (Aurora seen).

## Live on Stue TV (2026-10-09 evening, debug build 1.50-119 on the TV and the Pixel)

Stue TV's debug app signed in as the owner (password typed from Proton Pass by a helper, never printed); all at volume 0–1.

- **Skins on the TV's Now playing: passed.** Switching the profile's dark theme on the Pixel (Settings ▸ Appearance)
  re-skinned the TV's Now playing live: Midnight (teal), Noir (black, amber kicker); restored to Aurora.
- **"Playing from {device}" is NOT shown when the TV app is the target (gap).** With Ravilo open on the TV, the phone's
  *Play on… ▸ Stue TV* (the merged row) hands the music over **through the server** (`Playback sessions: … start music on
  <TV> from <Pixel> → session_load`), not as a Cast Connect load, and only a Cast load carries `sender_name`. So the
  line never appears on this path, which is the common one. **Fix (to spec/build):** `session_load` carries the starting
  device's name (`playback_session.started_by_device_id` → its display name) and the TV shows it the same way.
- **Home did NOT stop the music on this path.** FR/owner decision "Home stops the music" held for a Cast load
  (Soveværelse TV, earlier today) but not for a server `session_load`: after Home the TV's media session kept
  *PLAYING* in the background (position advancing).
- **Two sessions after the hand-off.** *Playing everywhere* on the phone listed the song twice, one on the Pixel 9 Pro
  and one on Stue TV: the phone's own session wasn't ended when the music moved to the TV (or was re-created when Home
  was pressed on the TV). Both were paused from the phone's sheet.
- **Tapping a TV in *Play on…* with nothing playing does nothing** (no log line, no hint) — same as the R266 finding from
  a film page; the music-mode sheet behaves the same.
