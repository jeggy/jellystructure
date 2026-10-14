# Phase R380 — The TV plays music too

> Owner, 2026-10-08, on R266's question of what happens to a music cast once a TV running Ravilo takes casts itself:
> **"TV music mode first"**, chosen over the review's lean (a second, music-only Cast app that keeps sending music to the
> web receiver). R266 does not ship until a TV running Ravilo can play a music cast in its own app.

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

Written 2026-10-08 (dev-authored) from the owner's decision above and R266's re-dev review (item 5, 2026-10-08);
dev-reviewed the same day (see the end). It touches **Ravilo** — the TV build of `ravilo-android` plus `ravilo-ui` —
with a small backend part (one TV row in *Play on…*). It was built on the R266 branch, together with R266, and **R380
must land with or before R266's merge** (see *Open questions* 1). No design round exists for a TV music mode: the
shapes followed are 286's *Now playing on a display* (FR-286-5/-6) and the phone's Playing page (R322). Status, and
what is still owed on devices, live in `STATUS.md`.

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

## Dev review (2026-10-08, against `main` `cfbba310` and `r266-cast-connect` `026410fc`)

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

Built on the R266 branch (`worktree-agent-a97c82f5673ccb803`, rebased from `r266-cast-connect` onto `main` `bd682d55`),
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

## Triage (2026-10-09, against `main` `12bffb29`)

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
- **Found live 2026-10-09 — fixed the same day (FR-R380-5, FR-R380-3):** (1) the panel reads, top to bottom: the songs
  already played (no heading), **Now playing** over the song playing, then **Up next from {album}** over the rest —
  the song playing never sits under *Up next* (`queueLines`). A session started through the server carries no
  `MusicContext`, so when every song still to come is from one album, that album names the queue
  (`upNextHeading(context, upNext)`; a named context still wins; Recently played and a search stay unnamed);
  (2) the controls are a `FlowRow` (10 dp gaps, 14 dp cell padding), so a long label wraps to a second line instead
  of clipping — en fits on one line in the 1920×1080 column; (3) the panel paints the page's ground and then the
  surface, fully opaque in every skin. Tests: `TvNowPlayingKeysTest` (album fallback, the panel's lines).
  **TV re-test owed:** ▲ on Now playing from a server-road album session, and the controls row in en/da/fo.
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

### FR-R380-9 — The server road behaves like a Cast LOAD (2026-10-09 evening, from the three findings above)

Music a phone hands to the TV app through the server (*Play on… ▸ Stue TV*, `session_load`) is the common road; it now
does what a Cast Connect LOAD does:

1. **Playing from {device}.** `session_load` carries `sender_name` (optional, additive), the starting or moving device's
   own name (its device row: *Pixel 9 Pro*; none from an admin's *Move to…*). The TV app shows it on Now playing exactly
   as it shows a Cast LOAD's (`TvCastChannel.startServerMusic`); an older server sends none and the line is left out.
2. **Home stops it.** The TV app marks music the server started there; leaving the screen (Home, the activity
   stopping) stops it and clears *Playing from* (`TvCastChannel.appLeftScreen()`, from `MainActivity.onStop`), as
   `receiverStopped()` does for a Cast LOAD — which only ran while the Cast Connect receiver was up. A phone never sets
   the mark, so a phone's own music keeps playing in the background.
3. **One session.** When a start of music on another Ravilo app comes from a device that plays music itself, the
   caller's own live music session ends (`moved`) and the caller is told to stop (`playstate_command` *Stop*: the
   phone's queue stays, paused), so *Playing everywhere* lists the song once; the caller's late report does not bring
   its row back. A film started on the TV leaves the phone's music alone. This is done on the server, so the installed
   phone apps get it too.

**Tests:** `SessionHandOffIntegrationTest` (music handed to a TV app names the sender, ends the phone's session, stops
it, one live row after the TV reports; a film start leaves the phone's music alone; `handedOver` picks only the
caller's own music), `ServerMusicOnTvTest` (`sender_name` on the wire and absent from an older server; Home stops
server-started music and clears the line; nothing to stop otherwise).

### Live, 2026-10-10 (Stue TV debug 1.50-157 = 9ed2b326's code, the Pixel 9 Pro debug, dev stack v1.50-158, volume 0–1)

- **FR-R380-9 passed on the real phone:** music playing on the Pixel → *Play on… ▸ Stue TV* (the server road,
  `session_load`): the TV's Now playing reads **Playing from Pixel 9 Pro**; the server logged *… on 16149… ended — its
  music went to …*, the phone's player stopped (mini bar paused, its remote showed *Playing on Stue TV*), and *Playing
  everywhere* listed the song once (the phone's ended row greyed for its linger, then gone).
- **Home stops it — passed twice:** Home on the TV → the media session went to state 0 at once, the server session read
  *ended* ~15 s later (the stop hold); same with an album started through the server from the phone's device.

## Amendment, 2026-10-13 — the TV is offered as a book place and drops every book (DEB-919)

Found on devices (QA-D, DEB-788, Stue TV, `.debug` of `main` `6e7c7ce7`), and it is not only QA's problem: the TV
advertises `book = true` on `GET /api/tv/playback/targets`, so **any viewer who picks the TV in *Play on…* for an
audiobook** — from the phone, the web app or the admin's *Move to…* — gets a dead session and a blank screen. Two
`POST /api/tv/playback/sessions` with `kind: "book"` answered 200 and then read `state=ended`, `end_reason=failed`
~11 s later with `position_ms` unmoved, twice, the second on a fully idle TV. Nothing crashes; the load is dropped.

The cause is a capability that outran its handler. `RaviloApp.kt` declares
`plays = playsQuery(video = true, music = MusicEngine.supported, book = MusicEngine.supported)` — correct, FR-R380-1
and this phase's scope both say audiobooks are in — but the TV's `session_load` handler branches on `"music"` and on
`"film", "episode"` only, with no `"book"` arm and **no `else`**, so a `book` envelope falls out of the `when`.

This is why **acceptance 4 has no working road at all**: the Cast road was already recorded as absent in the dev review
(*"a book cast: built (book facts, ∓30 s), not cast — the phone's sender does not send audiobooks over Cast today"*),
and the server road, which FR-R380-9 calls the common one, fails. The ◀ ▶ ∓30 s key map of FR-R380-4 has therefore
never been exercised on a device.

**FR-R380-11 — A `book` envelope plays on the TV, and no envelope is ever dropped in silence.** The TV app's
`session_load` handler takes `kind = "book"` and hands the book to `BookPlayback` and the TV's Now playing with the
book's own controls — the chapter, the chapter's progress and the whole book's, speed if not 1×, ◀ ▶ as ∓30 s — exactly
as FR-R380-3 and FR-R380-4 already specify for a book. The `when` gains an **`else` that refuses loudly**: an unknown
kind is reported back on the session as a failure naming the kind, rather than left to the server's watchdog to end as
`failed` eleven seconds later. The advertised capability and the handled set are the same set, and a test holds that:
every kind `playsQuery` can report `true` has an arm, so a future capability cannot outrun its handler again.

**FR-R380-12 — A book session's queue row carries its own facts.** `queue_json` for a book carries title, subtitle and
artwork as a music session's does; today it carries `{"id": …}` alone, so the book shows as a nameless entry in
*Playing now* for as long as it lasts. Additive on the wire (R372's reader tolerates the fields' absence from an older
sender), so no installed client is affected.

**Tests:** the key-map test of *Tests* 2 extended to the book arm on a real `session_load` envelope (fake engine); a
`session_load` of each advertised kind reaches the engine and an unknown kind is refused with the kind named; the
capability/handler set equality above.

**Out of scope here:** nothing about the Cast road changes — the phone's sender still does not send audiobooks over
Cast, which stays R323's to decide. Acceptance 4 is re-run on the **server** road once this is built.

**What is not covered automatically, and why** (the delivery process of 2026-10-14): the arms, the kind vocabulary, the
book's place in book time and the loud refusal are all held by `SessionLoadArmsTest` (`:ravilo-ui:desktopTest`,
`:ravilo-ui:testDebugUnitTest`) and `BookSessionLoadTest` (`:linuxX64Test`), including the set equality FR-R380-11 asks
for. What no test on a build host can reach is the **sound**: that the book is audible on the TV from the place the
envelope named, that FR-R380-4's ◀ ▶ move the listening by ∓30 s under a real remote's key events, and that the TV's
Now playing draws the chapter and both progress lines on a 10-foot screen. That is a device's word, on the queue with
the TV named — not a test that could be written and is not.

**Built 2026-10-14 (DEB-919).** Two readings of the wire were wrong and both are fixed here, because without them the
arm would have had nothing to load: QA's `kind: "book"` is the **`plays=` token**, not a session kind, and the server
took it verbatim into a session in the **video** lane (`sessionLane`), so even an app with a book arm could not have
played it — `sessionKindOf` now reads it as `audiobook` and refuses a kind this server does not know with a 400 instead
of answering 200 and letting the load watchdog end the row `failed` 10 s later. And a book's place is two numbers in two
index spaces: a live book row's `position_ms` is the position in the **part** it plays (281's heartbeat), while a book
plays from **book time**, so `session_load` carries `book_id` and `book_ms` (additive; a start's `start_ms` for a book
is book time, a move's is converted from the part by `bookTimeOf`). The move gate is now the kind, not *audio-ish*: a
session is only moved to a place whose `plays=` covers its kind.
- **The queue panel and controls (fix-tv-live) passed:** *Now playing* over the song, **Up next from {album}** over
  the rest (an album started through the server, no context), opaque panel; ▼ raises ⏮ ⏯ ⏭ *Lyrics on* *Queue*, nothing
  clipped, hint *▲ Queue · ▼ Lyrics off*.

## Amendment, 2026-10-14 — FR-R380-12's nameless row was found on the heartbeat route; the fix is R368's and R372's (DEB-1054)

Found by QA proving this phase's **acceptance 4** on the server road (DEB-1005 → DEB-1054, Stue TV, prod
`v1.51-174-g5b13710e`, TV `.debug` `1.51-177-g5dad383b`). A book's heartbeat that names a part the session is not on
mints a **second** session beside the book — `kind = film`, `book_id` NULL, no title, no subtitle, no artwork: exactly
the nameless row **FR-R380-12** exists to forbid, arriving by the heartbeat route rather than the start route DEB-919
fixed. A listener crossing a part boundary is listed twice in *Playing now*, one row nameless, and on a backward
crossing the real book session is ended `end_reason = failed` when it did not fail.

**The requirement this breaks is this phase's; the defects are not.** Both live in the session layer, so this
amendment allocates no requirement of its own and the fix is written where the machinery is:

- **The mint — [R368](phase-R368-every-playback-is-a-session-and-every-app-sees-them.md), *Amendment 2026-10-14*,
  FR-R368-12.** A book's session is found by its book, not by the part it is on. `session_id` on this route is not
  merely optional but **inert** (the route never passes it, so a client that sends it changes nothing), and the real
  match is `book_id`; threading the field is a tidy-up that rides along. A part is never a film.
- **The two ending rules — [R372](phase-R372-several-sessions-move-to-play-here.md), *Amendment 2026-10-14*,
  FR-R372-13 and FR-R372-14.** `failed` means a play that never reported, not a position that reads 0; and no live
  session may outlive every clock. Neither is book-specific and neither is fixed by FR-R368-12.

**What this phase owes, and it is not new work.** Nothing here changes what R380 requires — FR-R380-12 was right and
was simply reachable from a route nobody had closed. **Acceptance 4 on the server road re-runs when the fix lands**,
together with DEB-1005's test 3 (the book is listed **once** in *Playing now*), which is the assertion this bug breaks.
The whole chain is server-side and one of its two reproductions needs no device at all, so the fix owes no device proof
of its own; the device half is the backward crossing, which stays on the QA queue with the TV named.

**Measured exact during the same run and not to be touched by the fix:** the ∓30 s key map in both directions, the
book-time → part resolution, the boundary seek arithmetic, and the TV's own screen — correct chapter, correct book
time, nothing clipped. A viewer in the room notices nothing. This is session bookkeeping.

**FR-R380-13 and FR-R380-14 are withdrawn and are not to be reused.** They were allocated here at 08:25Z on
2026-10-14 (commit `384fd544`) for the mint and the ending rules, by a drainer tick that wrote this amendment while a
second run of the same seat was writing the better-sited pair above. The numbers are recorded as spent rather than
quietly freed, because a reused requirement number is worse than a gap: DEB-1033's duplicate twelfth amendment
(`5dad383b`) is three commits old and began the same way. The substance was not lost — FR-R368-12 and FR-R372-13/-14
say everything those two said and more, and they corrected one thing this phase's version had wrong: the fix is the
book-aware match, not the `session_id` threading, which on its own would have fixed nothing.

## Amendment, 2026-10-14 — the phone's *Play on…* sheet has no book arm, so acceptance 4 has no road from the phone either (DEB-1064)

Found by QA proving this phase's **acceptance 4, test 3** (DEB-1055 → DEB-1064, `pixel-9` + `stue-tv`, prod
`v1.51-174-g5b13710e`, both apps `.debug` `1.51-177-g5dad383b`). With the audiobook **playing on the Pixel**
(`kind=audiobook`, position advancing), the app bar's *Play on…* sheet → **Stue TV** prints, at its own foot,
*"Play something first, then pick where it plays."* — directly under its own **PLAYING EVERYWHERE** section, which in
the same screenshot lists that very book on *Pixel 9 Pro* with playing bars and a pause button. Nothing moves and
nothing starts: no `playback_session` row was created and the Pixel's row was untouched across the tap
(571 948 → 591 956 ms).

**DEB-919 gave the TV a book arm; nothing gave the sheet one.** FR-R380-11 closed the receiving half — the TV now takes
a `book` envelope — and QA then measured the move itself correct through the endpoint the button would have called
(`POST …/{id}/move`: the TV took the book over in **335 ms**, resumed exactly 2 000 ms behind the phone *in book time*,
right chapter, *Playing from Pixel 9 Pro*, listed once in *Playing everywhere*). So R372's move is not broken and
`6c5925e4`'s `book_ms` is not broken. What is broken is the only affordance a viewer has: in
`ScreensSheet.kt` every branch of this sheet was written when it knew films and music queues, and a book is neither —
it has no `queue`, it *is* `st.book`.

Three gates, all verified against `main` at this amendment's commit, and a fourth QA did not reach:

1. `startRequestFor` (`:178-184`) returns `null` in music mode whenever a book is loaded —
   `if (st.queue.isEmpty() || st.book != null) return null` — and that `null` is what raises
   `screens.nothing_to_start_music`, the string above.
2. `startOn`'s move branch (`:190-199`) is gated `!music && playContext != null` and then on `kind == "film" ||
   kind == "episode"`, so a book session never qualifies for the move that is this card's whole point.
3. The other way in is shut too: a *Playing everywhere* row for **this** device deliberately opens the local player
   rather than `SessionRemoteScreen` (`RaviloApp.kt:1786-1789`), and `SessionRemoteScreen` is the only screen that
   renders *Move to…*. The local book player's overflow offers *Start over*, *Mark as finished*, *Go to author*.
4. **Not in QA's filing, found writing this amendment, and it is the one a viewer could be hurt by:** line `:232` asks
   `playOnTiers(tierRows, if (music) "music" else "film")` — the mode, never the kind. With a book loaded the tiers are
   therefore filtered by `canPlay(…, "music", …)` = `c.audio`, not by `"audiobook"` = `!castPlace && c.book`. So the
   sheet **offers a book to Cast speakers**, which owner decision 4 of this phase forbids outright, and to any place
   that plays music but not books. Nobody has hit it because gate 1 refuses every tap before it reaches a start — fix
   gates 1–2 without this one and a viewer gets a dead receiver instead of a wrong hint.

**FR-R380-15 — *Play on…* offers a book exactly where a book can play, and a tap does what the sheet already shows.**
Four obligations, and the first is a precondition of the rest:

- **The kind the sheet asks about is the kind that is loaded**, not the mode it is in: a loaded book asks
  `"audiobook"`, so `canPlay`'s own rule applies and a Cast place and a music-only place are **absent, not greyed**
  (FR-R370-2's rule, unchanged). `canPlay` already answers this correctly — it is not called with the right kind.
- **A book playing here moves.** With the book live on this device, tapping a free book place hands that **same
  session** over — R372's move, its id and its place carried across, 2 s back in book time — exactly as a film already
  does. It does not start a second session at 0:00.
- **A book that is loaded but playing nowhere starts there**, at this viewer's place in **book time**.
- **The hint tells the truth.** `screens.nothing_to_start_music` appears only when there is genuinely nothing loaded.
  It may never appear in the same sheet that is listing the thing on *Playing everywhere*.

**No wire change, and this is the reason the requirement is small.** Everything the sheet needs shipped with DEB-919
and is already documented in `shared/…/tv/Targets.kt`: `SessionStartRequest.kind` accepts `audiobook` (and `book` as a
spelling of it), a book's `items` are book ids with `start_ms` in **book time**, `canPlay` has the `audiobook` arm with
owner decision 4's Cast exclusion in it, `playOnTiers` already filters by the kind it is handed, and
`SessionLoadEnvelope` carries `book_id` / `book_ms`. The server also already refuses a move to a place whose `plays=`
does not cover the kind, so obligation 1 is the client agreeing with a rule prod enforces rather than a new rule.
Client-only, `ravilo-ui/commonMain`, so every Ravilo app that shows this sheet gets it at once and no installed client
is affected.

**Tests:** `startRequestFor` with a book loaded builds an `audiobook` start naming the book at book time (and still
`null` with nothing loaded at all); `startOn` with a live book session on this device calls the **move**, not a start;
the tier kind is `audiobook` whenever a book is loaded, so a Cast speaker and a music-only place are filtered out of
all four tiers; and the hint is raised in neither of the two book cases. All four are
`:ravilo-ui:desktopTest` / `:ravilo-ui:testDebugUnitTest` — none of them needs a device, because the sheet's decision
is a pure function of the loaded state and the places.

**What a device still owes, and it is one tap:** that the tap on a real phone against a real TV lands the book audibly
on the TV at the right place. The arithmetic and the TV's screen were already measured exact under DEB-1055 and are
not to be re-measured. That goes on the QA queue with `pixel-9`, `stue-tv` and `prod-backend` named, as the re-run of
acceptance 4 test 3 — not as a new test.

**Out of scope here:** the Cast road for a book stays closed and stays R323's to decide (owner decision 4 of this
phase keeps a book off a Cast place, so obligation 1 enforces the decision rather than reopening it); and nothing in
this amendment adds *Move to…* to the local book player's overflow — the app bar's sheet is reachable from that player
and is the one route, so no new screen and no second affordance.

### As built, 2026-10-14 (DEB-1066) — three decisions the four obligations left open

The sheet's decision is now one pure function, `sheetTap`, with `sheetKind` in front of it (both in `ScreensSheet.kt`,
both `internal`): the three branches that each knew half the answer — `startRequestFor`, `startOn`'s move gate and
`:232`'s tier kind — read the same `SheetLoaded`, so the kind the tiers filter by and the kind a tap starts can no
longer disagree. That is what made the bug possible: nothing in the old shape tied the hint to the tap.

- **A book loaded while the sheet is in *video* mode leaves the video sheet alone** — it still asks `"film"`. A book
  plays only behind *Music & audiobooks* (R323), so "the kind that is loaded" is read as the kind this sheet would
  start; asking `"audiobook"` from a film page's sheet would take every Cast display out of it and break FR-R370-2
  for films to fix nothing for books. The music-mode sheet is the one obligation 1 is about, and the hint obligation 4
  names is `screens.nothing_to_start_music`, which only that sheet raises.
- **A book time of 0 is sent as `start_ms: null`** — "this viewer's stored place", which the server already resolves
  (FR-R370-3). The engine answers 0 for a book it has loaded but not yet made ready, and a literal 0 would have
  restarted the book on the TV: the one way this affordance could still lose a viewer's place.
- **A busy place's *Play {title} here instead* names the book.** `musicNow.current` is null with a book in the
  engine, so that button rendered with a hole in it — the same "a book is neither" shape as the four obligations, in
  the same sheet, found while building this.

**Checks run here (this seat cannot reach CI):** `:ravilo-ui:desktopTest` 724 tests green, `:ravilo-ui:testDebugUnitTest`
845 green, `:ravilo-player:testDebugUnitTest` green, `:shared:desktopTest` + `:ravilo-i18n:desktopTest` green,
`:ravilo-ui:compileKotlinWasmJs` and `:ravilo-android:assembleRelease` clean; `check-ravilo-strings`,
`check-theme-whites` and `check-phases` OK. The 15 new tests are `commonTest`, so they run in both the desktop and the
Android job. `check-player-dex` reads **250 registers (limit 250)** on the release APK — and **250 on `83a4bc87`
without this change**, measured in a detached worktree, so this change costs `PlayerScreen` nothing. The headroom this
amendment's own build notes recorded (245 after the R380 closure, 246 after R376) is nevertheless gone on `main`
today; that is a fact for the board, not for this fix.

## Amendment, 2026-10-14 — the *video*-mode sheet still says *"Play something first"* over a book playing on this device (DEB-1090)

The residue of the narrowing recorded in the *As built* block above, and the sentence QA photographed under DEB-1064
is still on screen in one case. It is **not** a reopening of that decision: the video-mode sheet keeps asking `"film"`,
because asking `"audiobook"` from a film page would take every Cast display out of the film tiers and break FR-R370-2
for films to fix nothing for books. What the narrowing leaves behind is the **hint**, not the kind.

The case, verified against `main` at `2068b7d8`:

- a book is playing audibly on the phone — the mode is **persisted** and leaving *Music & audiobooks* does not stop the
  playback, so a viewer listening while browsing films is the ordinary way to reach this;
- the app bar's *Play on…* is opened in **video** mode from a page with no film, so `playContext == null`;
- `sheetTap` computes `kind = "film"` (`ScreensSheet.kt:139`), the film/episode lane is `emptySet()` because
  `loaded.title == null` (`:147`), the book is skipped because the kind is not `"audiobook"` (`:153`), `loaded.music`
  is false (`:159`), and the last line returns `SheetTap.NothingLoaded` (`:166`);
- the sheet raises `screens.nothing_to_start` (`:326-329`) **underneath its own *Playing everywhere* row for that very
  book** — the session list that section draws is not filtered by mode (`:229`, `:306`, `:315`).

FR-R380-15's obligation 4 named `screens.nothing_to_start_music`, so its letter is kept: the string raised here is the
other one. Its **second** sentence is not — *"It may never appear in the same sheet that is listing the thing on
Playing everywhere."*

**The viewer is not stuck, which is why this is `low`.** Tapping the book's *Playing everywhere* row opens the local
book player (`RaviloApp.kt:1786-1789`), and that player's own *Play on…* is the music-mode sheet FR-R380-15 fixed. So
this is a false sentence on the way to a working road, not a dead end, and it must not jump DEB-1088 or anything the
one engineer is holding.

**FR-R380-16 — a sheet that is listing something live on this device never says there is nothing to play.** When the
open sheet's session list holds a live session (`state != "ended"`) whose target is **this device** (`SessionView.here`),
a tap that finds nothing this sheet can start raises **neither** hint — not `screens.nothing_to_start` and not
`screens.nothing_to_start_music` — and does nothing else. In every other case FR-R265-7a's hint stands exactly as it
is, including when the only live session is somewhere **else**: nothing is loaded here then, and the sentence is true.

Three things make this the narrowest shape rather than a second mode rule:

- **It is a property of the sheet, not of the mode or the kind.** The condition is written on `here` and live, never on
  `audiobook`, so it cannot rot the next time a kind is added — the same reason DEB-1066 collapsed three branches into
  one `sheetTap`. A book is simply the first loaded thing that outlives the screen that started it; a film session left
  live on this device would read the same false sentence through the same line.
- **It needs no new input.** `sheetTap` is already handed `sessionsState.sessions` (`:279`), which is the list
  *Playing everywhere* is drawn from, so the whole condition is decidable inside the pure function.
- **The hint stays driven by the decision.** The outcome must be distinguishable from `SheetTap.NothingLoaded`, so that
  `nothingToStartShown` is still set by exactly one branch at the draw site and no second condition is evaluated there.
  How that outcome is spelled is the engineer's call; what the spec requires is that the sheet cannot raise the hint for
  it.

**The trade-off, recorded rather than hidden:** a tap that does nothing at all is what FR-R265-7a was written to stop.
It is accepted here because this sheet is not silent — the live row sits directly above the tiers, is tappable, and is
two taps from the TV — so the choice is between a sentence that contradicts the screen and an unresponsive row on a
screen that is visibly playing something. A third, truer sentence would mean a new string in every language table for a
cosmetic defect, which is deliberately not taken now; if the unresponsive row is ever itself reported, that is the line
to pick up, and it is a new requirement rather than a change to this one.

**Tests** (`commonTest`, alongside `PlayOnBookTapTest`'s fifteen; the decision is a pure function, so neither needs a
device):

1. **The defect.** Video mode, `title = null`, a live `audiobook` session with `here = true` in the list → the decision
   is **not** `NothingLoaded`, so the hint is not raised.
2. **The true case kept.** The same video sheet with nothing loaded and no live session on this device → still
   `NothingLoaded` and the hint still appears, *and* a live session **elsewhere** (`here = false`) does not suppress it.

**Scope.** Client-only, `ravilo-ui/commonMain`, one condition inside `sheetTap` plus its outcome; **no wire change, no
new string, no field added or retyped**, so no installed client is affected. Not in scope: offering the book from the
video sheet (decided the other way in the *As built* block above and confirmed 2026-10-14), filtering *Playing
everywhere* by mode (it is the true affordance and the viewer's route to the fixed sheet), and any change to
`screens.nothing_to_start` / `screens.nothing_to_start_music` themselves. **Devices: none** — DEB-1088 already owns the
one device tap this phase owes.

### As built, 2026-10-14 (DEB-1092) — the outcome is spelled `NothingStartableWhileLiveHere`

The outcome the amendment left to the engineer is a fourth `SheetTap` member,
`SheetTap.NothingStartableWhileLiveHere`, and the condition is one local function inside `sheetTap` that both of the
old `return SheetTap.NothingLoaded` sites now go through — `sessions.any { it.here && it.isLive() }`, reusing
`PlaybackSessions.kt`'s `isLive()` rather than repeating `state != "ended"` a third time. So:

- **The hint is still set by exactly one branch.** `startOn`'s `when` gained `NothingStartableWhileLiveHere -> Unit`
  beside the untouched `NothingLoaded -> nothingToStartShown.value = true`; the draw site reads no second condition,
  and `nothingToStartShown` is still the only state the hint depends on.
- **Both empty-handed paths are covered, not just the video one.** The music-mode sheet's empty-queue return
  (`loaded.queue.isEmpty()`) goes through the same function, so a music-mode sheet with nothing loaded and a live
  session here is silent too. FR-R380-16 is written on the sheet, and that is the whole point of it.
- **One pre-existing test changed its assertion**, `the join-only sheet moves nothing and starts nothing`: it asserted
  `NothingLoaded` for a video sheet with `title = null` and a live **film** session here, which is exactly the case the
  amendment says reads the same false sentence through the same line. It now asserts what it was written to assert —
  that nothing is moved and nothing is started — and the hint for that case is covered by the new tests instead.

**Checks run here (this seat cannot reach CI):** `:ravilo-ui:desktopTest` **727 tests green**,
`:ravilo-ui:testDebugUnitTest` **848 green** (the three new tests are `commonTest`, so they run in both jobs),
`:ravilo-player:testDebugUnitTest`, `:shared:desktopTest` and `:ravilo-i18n:desktopTest` green,
`:ravilo-ui:compileKotlinWasmJs` and `:ravilo-android:assembleRelease` clean. `check-player-dex` reads
**250 registers (limit 250)** on the release APK — unchanged from the DEB-1066 measurement above, so the fourth
member and the draw site's new branch cost `PlayerScreen` nothing, and the zero headroom recorded there is still
zero and still the board's fact rather than this fix's. `check-phases`, `check-ravilo-strings`, `check-theme-whites`,
`check-deanonymization` and `check-play-device-filter` OK.

**Left to a device, as DEB-1097 (`low`, the Pixel alone, foldable into DEB-1088's claim):** no unit test composes the
sheet, so what the tests cannot say is whether the draw site renders what the decision decides. The ticket asks for
the sentence's absence with a book playing and the app bar in video mode on a page with no film, the hint's presence
when nothing is loaded, and — the half easiest to get backwards — the hint's presence when the only live session is
**elsewhere**. The unresponsive row is named in that ticket as the accepted trade-off it is, so it is not reported
back as a finding.
