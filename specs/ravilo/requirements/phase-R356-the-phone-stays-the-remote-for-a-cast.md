# Phase R356 — The phone stays the remote for a cast: alive while locked, never silent, and a small status

> Owner, 2026-10-02 15:49–15:55 (debug build `1.48-95`, music cast to the Gæsteværelse speaker): the phone was locked;
> *next* in Ravilo's cast notification did nothing; the song played to its end; opening Ravilo, the Playing page sat on
> the loading dots at 0:00 / -0:00 under *Playing on Gæsteværelse* for 20 s while the speaker played on. Removing the
> app from recents and opening it again fixed it.
>
> Owner, 2026-10-02 15:45 (added): the lock-screen media card (*Caster til Stue*, chip *Stue + 1*) showed the right
> song with **another album's cover**, while Ravilo's Playing page showed the right one.

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed, **device-tested on the Pixel 9 against the Gæsteværelse
speaker** (the phone side; the receiver change needs a backend deploy and was measured against the real bundle in a
harness). Written 2026-10-02 (dev-authored, owner-approved the same day) against `main` `f628c2f2`. Number checked
free on `main` (Ravilo specs top at R355). Context: `specs/research-reports/ravilo-playback-sessions-and-casting-2026-10-02.md`
§1–§2. **Amends** R245 (FR-R245-11: the Cast SDK's own notification is replaced), R322 (FR-R322-1/13: the music
service also hosts the cast remote), R324 (FR-R324-9: the notification's actions), 286 (dev review 10: the receiver's
queue snapshot) and R330 (FR-R330-3: the shared status merge). Android sender, the Cast receiver (`ravilo-cast`) and
the common merge both senders use (the Mac's included). Additive wire change only; no new string; no backend change
except that the receiver is served by the backend (a deploy ships it).

## What happened (logs of the owner's session, reproduced on the Pixel 9 2026-10-02 16:11–16:15)

1. **Casting, the phone plays no audio, so Ravilo held no foreground service.** The Cast SDK's notification is posted
   by the app but does not keep the process up. Locked (or simply behind another app), Ravilo became a cached process
   and Android's freezer froze it (`ActivityManager: freezing <pid> dev.jellystructure.ravilo.debug`; reproduced ~70 s
   after Ravilo left the screen).
2. **Every receiver status is the whole queue.** `ravilo-cast` sends its `status` message with `queue` = every song,
   on every play/pause/buffer change and every song change (about three per song). Measured: **55 289 bytes for a
   199-song queue** (≈ 280 B a song); the owner's artist queue made 322 272-byte binder transactions (≈ 1 150 songs).
3. **A frozen app's binder buffer fills, and Play services drops the app.** Play services' Cast service delivers each
   message to the app over binder. Into a frozen process those calls queue up; with ~55 KB a message the buffer was
   full after ~10 messages: `Binder transaction failure … error: -28 (No space left on device - Binder buffer full)`,
   then `CastService: [dev.jellystructure.ravilo.debug] Disposing ConnectedClient`. From then on nothing reaches the
   app and nothing it sends reaches the speaker.
4. **While frozen, the notification's *next* went nowhere** (`cmd media_session dispatch next` against the Cast SDK's
   media session: the speaker stayed on the same song).
5. **Unfrozen, the app is a dead remote that believes it is connected.** The Cast session object still reads
   CONNECTED, the Playing page extrapolates the last position (or shows the dots with 0:00 when the SDK has no media
   status), the app's *next* does nothing, and no status ever comes. Only a new process (the owner's relaunch) resumed
   a working session.
6. **The cover.** The lock-screen card was the Cast SDK's own media session (subtitle *Caster til …*), whose artwork the
   SDK loads by itself from the receiver's media metadata, asynchronously and per notification; the Playing page draws
   the song's album cover from Ravilo's own track item. Two sources, two loaders — and a frozen/unfrozen process lets the
   SDK's late image land on a newer song.

## Requirements

### A — One media session, and the app stays alive while it is the remote

**FR-R356-1 — One Ravilo media control while casting.** While the phone is the remote for a cast (a Cast session is
connected and the receiver has something loaded — a song or a film), the notification, the lock-screen card and the
media keys are **Ravilo's own** Media3 session (`RaviloMusicService`, the R322 service). Its player is a *remote
player* that mirrors the cast and forwards every command. The Cast SDK's own notification and media session are turned
off (`CastMediaOptions`: no `NotificationOptions`, `setMediaSessionEnabled(false)`), so there is one Ravilo card, not
two. When the cast ends (or hands back, R353 FR-R353-5) the session returns to the phone's own music player.

**FR-R356-2 — A foreground service of type `mediaPlayback` while the cast plays.** The service is started when a
cast becomes live, which happens with the app on screen (the viewer chose the device), and Media3 keeps it in the
foreground while the remote reports playing (or buffering) and for **10 minutes after a pause** (Media3's foreground
timeout, the same 10 minutes as R354's media hold); then it leaves the foreground and Android may cache and freeze the
app again, as before. It stops being foreground when the cast ends. The manifest already declares the type and
permissions (R322). A start Android refuses (`ForegroundServiceStartNotAllowedException` — e.g. a remote play from
Google Home after a long pause, app in the background) is caught by Media3 and costs only the foreground state.

**FR-R356-3 — The card's controls drive the device.** Music: play/pause, previous, next, seek, and *Stop casting* (a
custom button) go through `MusicPlayback` / `MusicCast` exactly as the Playing page's buttons do. A film: play/pause,
−10 s / +30 s, seek, *Stop casting*. The session's playback type is **remote** (20 steps, 5 % each, R324's slider step),
so the volume keys on the lock screen move the device's volume, not the phone's.

**FR-R356-4 — The cover is the song's album cover, the same one the Playing page draws, and follows every song.** The
remote player's metadata (title · artist · album · artwork) is built from the item the Playing page shows
(`MusicPlayback.state.current`, its `imageUrl` made absolute with `w=720`, as the phone's own player does) — one
source for the app and its card. Each song is its own media item, so the card's artwork is reloaded with every song
and an image loaded for an earlier song is never shown on a later one (Media3's loader keys the bitmap to the item).
The receiver already sends each song's own album cover in its media metadata (`MusicTrackMediaMetadata.images`, 286
FR-286-3), which is what Google Home and other phones' cards read — unchanged, verified per song.

**FR-R356-5 — Local music, R322 and R354 unchanged.** Playing on the phone uses the same session with the phone's
own player, as before. Removing the app from recents while casting stops the service and leaves the speaker playing
(FR-R245-5); while playing on the phone it stops the music, as before (R322 acceptance 1). R354's socket hold is not
touched (casting does not hold the phone's events socket).

### B — Connected but silent

**FR-R356-6 — Ask, then rejoin.** The sender notes when it last heard from the receiver (a media status or a message on
Ravilo's channel; the SDK's own progress ticks do not count). Two moments expect an answer:
- **the app comes on screen** with a cast connected: it asks at once — `RemoteMediaClient.requestStatus()` and
  Ravilo's `status` command;
- **a command** (play, pause, seek, next, a queue change, from the app or the card): an answer is due within **3 s**
  (the receiver answers a state change at once); if none came, it asks.
If **3 s after asking** nothing has been heard, the sender **rejoins**: it ends the stale local session object
*without* stopping the receiver (`endCurrentSession(false)`), and selects the same Cast route again, so the SDK joins
the receiver app that is running (no relaunch, no `LOAD`, no new enrolment) — what the owner's relaunch did. One rejoin
per nudge; a rejoin that has not connected within **15 s** ends the session the ordinary way (R353's hand-back).
The decision is a pure function in commonMain (`CastSilenceWatch`), unit-tested.

**FR-R356-7 — Last known, never zeros.** Through a rejoin the phone keeps the link *connected* and shows the last
known song, place, length and play/pause state; the mini bar and the Playing page never fall to the loading dots with
0:00 / -0:00 because the SDK has no media status yet (`mergeCastStatus` keeps the last known values while the media
snapshot has no player state; the SDK's progress listener's 0/0 without a status is ignored). The frontend renders what
it was told last, nothing derived beyond that.

### C — A small status

**FR-R356-8 — The receiver sends the queue only when it changed.** Every music `status` carries `queue_rev` (a number
the receiver increases whenever its queue's songs or order change) and `queue_size`. The full `queue` is included only
when the revision changed since the receiver last sent it, after a sender connects (CAF `SENDER_CONNECTED`), and in
answer to `status` or `get_queue` (a new command) — never in the other state changes. `queue_index` always refers to the
full queue. The `ended` message keeps the full queue (it is sent once and is what a hand-back resumes from). The
receiver's own media status no longer carries the queue either: the `customData` CAF echoes in every media status is
the load's data without its `tracks`.

**FR-R356-9 — Senders keep their copy by revision.** The shared merge (`mergeCastStatus`, both the Android sender and
the Mac's) keeps the last full queue and its revision; a status without a queue leaves it as it was. A status whose
revision differs from the one held, with no queue in it, is a gap: the sender asks `get_queue` once for that revision,
and meanwhile keeps showing the song the receiver named (`item_id`) rather than an index into the old queue.

**FR-R356-10 — Old senders and old receivers keep working.** An installed sender that predates this phase still has
the whole queue: it receives the full queue after it connects (`SENDER_CONNECTED`, and it asks `status` on every
resume), on every change, and its merge already keeps the last queue when a status has none. A new sender against an
old receiver sees `queue_rev` absent and the queue in every status, as before. The new fields are optional
(`queue_rev`, `queue_size`; absent ⇒ the old behaviour); `get_queue` is a new value of a string field that old
receivers ignore (`onCommand`'s `else -> return`).

**FR-R356-11 — The size is logged once.** The receiver notes on its log channel (289) the byte size of each full-queue
status and of the first status without the queue after it; the Android sender logs the size of the first message it
receives on Ravilo's channel per session and every one above 32 KB.

## Out of scope

- Keeping a TV app or the Mac alive off screen (other platforms' lifecycles); the phone's own events socket while
  casting (R354 stands).
- Stream transfer between speakers (R355 open question 1), a server-owned playback session (the 2026-10-02 report §4).
- A queue *window* around the current song (the revision rule makes the per-event status small already; a huge queue
  is still sent in full once per change).

## Open questions

1. Play services' own remote-control card (`cast_rcn_media_session`) — does it still appear beside Ravilo's once the
   SDK's session is off? (Seen on the device: see Build notes.)
2. Media3 cannot hand its session to `MediaRouter.setMediaSession*` (it takes the framework/compat session object), so
   the system's output switcher links the card to the route by package only.

## Acceptance

1. Music casting to a speaker, the phone locked (or Ravilo behind another app) for minutes: the app is **not frozen**
   (`dumpsys activity`: a foreground service of type `mediaPlayback`); the card's / a media key's *next* skips on the
   speaker; pause and play work.
2. After a forced freeze/unfreeze (or a long lock), opening the app: the Playing page shows the right song and place
   within ~3 s — by a fresh status, or by a rejoin — and the app's buttons reach the speaker again.
3. A 199-song queue: the per-event status drops from ~55 KB to well under 2 KB; a full-queue status only on a queue
   change, a sender connecting, `status` or `get_queue`. An old sender keeps showing the queue.
4. The notification, the lock screen card and the Playing page show the same album cover for every song of a queue
   whose songs come from several albums, changing with each song; Google Home's card (the receiver's metadata) the same.
5. One Ravilo media card while casting; none of the Cast SDK's.

## Build notes (2026-10-02)

**Built** (commits on the R356 branch): the spec; the receiver's queue revision (`queue_rev`, `queue_size`,
`get_queue`, the slim media `customData`) with the shared merge and both senders' gap handling; Ravilo's one media card
(`CastSessionRemote` + `CastRemotePlayer` in `RaviloMusicService`'s session, the Cast SDK's notification and media
session off); the silent-session watch (`CastSilenceWatch`) with ask and rejoin in `CastSenderAndroid`.

**Root causes, confirmed on the Pixel 9 (debug `1.48-95`, before the fix):** Ravilo had no service while casting
(`dumpsys activity services`: none) and was frozen ~70 s after leaving the screen (`ActivityManager: freezing <pid>`);
the production receiver's status was **55 289 B for a 199-song queue**; 15 such statuses into the frozen app gave
`Binder transaction failure … error: -28 (… Binder buffer full …)` and `CastService: … Disposing ConnectedClient` for
the app; a media-key *next* (`cmd media_session dispatch next`, the Cast SDK's session) did not reach the speaker;
unfrozen, the Playing page showed the last song with an extrapolated place while the app's own *next* did nothing.

**Deviations from the spec, and why:**
- A plain *join* of a playing receiver (the sheet's *Play on {speaker}* with nothing to hand over) also asks for the
  status at once: the receiver speaks on change only, so the joined phone showed a film-style bar until the next
  song. Not in the spec's two moments; same mechanism.
- The card is built from the receiver's report read as the Playing page reads it (`MusicCast.state`), not from
  `MusicPlayback.state`: the latter follows the report a moment later, and the card dropped to the phone's own player
  and back on the first report.
- The card outlives a report with no live song for **5 s** while the session stays connected (a song's end reports
  idle for a moment before the next one loads). Swapping to the phone's own player and back stopped and restarted the
  foreground service at every song change, and a start from the background may be refused.
- *Stop casting* on the card uses the existing `cast.stop` string (no new string).
- Removing the app from recents while casting stops the service and keeps the phone's queue; the device plays on.

**Verified on the Pixel 9 after the fix** (debug builds of this branch; the Gæsteværelse speaker at 2 %; the phone's
media volume could not be set from the shell on this Android build — it stayed at 7 of 25 — so nothing was played on
the phone itself):
- (a) Casting a 199-song queue from several albums: `RaviloMusicService` is a foreground service of type
  `mediaPlayback` (`isForeground=true types=0x2`); Ravilo behind another app for 100 s and **locked for 100 s: not
  frozen** (procState 4, receiver messages keep arriving); a media key *next* moved the speaker on both times
  (the speaker started the next song, locked too); *pause* and *play* reached it (the session read PAUSED with a still
  position, then PLAYING from the same place). `dumpsys media_session`: Ravilo's Media3 session is the media-button
  session, remote volume (max 20), custom action *Stop casting*; the shade shows **one** Ravilo card (Play services'
  `CastRCN` card is not shown for it; another sender's cast to a TV kept its own card).
- (b) Forced freeze (`cmd activity freeze --sticky`) + 15 statuses → *Disposing ConnectedClient*; unfrozen and opened:
  `asking the receiver for its status (the app came on screen)` → 3 s later `rejoining Gæsteværelse` → 1.2 s later
  `rejoined the running receiver` → status 0.4 s later (**4.5 s** in all, twice). The mini bar and the Playing page
  showed the right song and place throughout (no loading dots, no 0:00), and the app's *next* reached the speaker
  afterwards.
- (d) The cover: four song changes across four albums — the Playing page and the shade's card showed the same album
  cover each time, and it changed with every song. (The owner's wrong cover — a green one with faint text at the top —
  matches another album of the same queue: the Cast SDK's card had kept an earlier song's image.)
- A stop from outside (the receiver app stopped over the Cast protocol): `the cast ended; the media card is the
  phone's own player again`, the service left the foreground, nothing played on the phone.
- The lock screen showed Ravilo's card with the right song and cover.

**Measured in a harness** (the real `ravilo-cast.js` with a fake CAF and stubbed API, 1 150 songs — the owner's
artist queue): the status after the load **333 234 B** (the full queue, revision 1); every play/pause/buffer/song
change after it **562 B**; a queue edit, `status`, `get_queue` and `SENDER_CONNECTED` each one full status; the media
`customData` CAF echoes **354–451 B** (was the whole load data). Per-song images unchanged (each song's own album).

**Tests:** `CastStatusMergeTest` (+4: revision kept, gap, old receiver, last known), `CastSilenceWatchTest` (6),
`CastCardTest` (3); `:ravilo-ui:testDebugUnitTest` (405, 0 failures), `:shared:desktopTest`, `:shared:linuxX64Test`
(WireCompat), `:ravilo-castv2:jvmTest`, `-Pravilo.desktopOnly=true :ravilo-desktop:compileKotlinDesktop`,
`:ravilo-web:compileKotlinWasmJs`, `:ravilo-cast:jsBrowserProductionWebpack`, `:ravilo-android:assembleRelease` +
`assembleDebug`; `check-player-dex` (241 registers), deanonymization, phases, mobile CSS, CSS scoping, strings.

**Needs a deploy / still to see:**
- The receiver change ships with the backend (`/cast/`). After the deploy: a music cast's `status` messages should
  read ~0.5 KB (Ravilo's log line `R356: receiver message … B`, logged for the first message and any above 32 KB); a
  phone on the old app and the Mac should still show the queue.
- Not tried on a device: a film cast's card (a TV — not in this round's devices), the card's *Stop casting* button
  (a tap on the lock screen landed under a dream window; the stop path was exercised from outside instead), the volume
  keys on the lock screen (the phone's rule: no volume up), local music playback after the change (the phone's volume
  could not be silenced).

## Amendment (2026-10-02 evening) — the second card, and Stop stops the cast

> Owner, 2026-10-02 ~17:50 (debug build of `8fdce182`, production backend `v1.48-102-gef72608c`, music cast to the
> Gæsteværelse speaker): two media cards for one cast — Ravilo's, with the cover and the title, and a second one with
> **no title, artist or cover**, only the right progress. And ■ on the lock-screen card stopped the music, but opening
> the app and pressing Play **played on the speaker again**. Stop should stop casting (the music comes back to the
> phone, paused at its place, and Play plays it on the phone); Pause should pause.

### What was found (the Pixel 9's `dumpsys media_session` and logcat, read-only, 17:46–17:56)

1. **The second card is Google Play services' own** — the Cast *remote control* card (`com.google.android.gms/
   cast_rcn_media_session`, notification tag `CastRCN`, custom actions *Mute* or *Virtual remote*, and *Stop cast*),
   which Play services posts for Cast sessions it sees on the network. Its text reads `Ravilo, null, null` (the app's
   name only).
   It is **not** caused by R356's slimmer receiver status: the receiver's CAF media still carries each song's
   `MusicTrackMediaMetadata` — title, artist, album, album artist and the song's album cover (`interceptMusic`, 286
   FR-286-3; R356 removed only the `tracks` from the media's `customData`; the R356 harness read the images back per
   song). The same blank card is in the dump taken during R356's own device check (old receiver), and the one for a
   film cast to a TV reads the same. Play services fills this card from something the app does not control. (R356's
   build note "Play services' `CastRCN` card is not shown for it" was wrong: that check's notification dump lists two
   `CastRCN` notifications.)
2. **No documented API stops Play services posting that card for an app's own session.** `CastMediaOptions`
   (`setNotificationOptions`, `setMediaSessionEnabled`) governs only the Cast SDK's own card, which R356 already turned
   off; `MediaRouterParams` governs the output switcher. What Media3's own Cast player does with the SDK's session off —
   the setup the Media3 maintainers recommend for one card (androidx/media#2089) — is **link the app's media session to
   the cast's MediaRouter2 routing session**: `DeviceInfo.routingControllerId` = the cast's `RoutingController` id,
   which the platform session's volume provider carries as `volumeControlId`, the field Android 14+ uses to tie a
   remote media session to its route (output switcher, the card's device chip). Ravilo's card did not
   (`volumeType=REMOTE(volumeControlId=null)`).
3. **The ■.** Logcat 17:55:34: Play services' card's session went to `NONE` (the speaker's media stopped) while
   Ravilo's Cast session **stayed connected**; Ravilo's card held for its five-second grace, then gave way (17:55:39.19);
   0.6 s later the phone loaded the speaker again at the song's place (queue revision 1, a new load) — Play pressed in
   the app. A media **STOP from a controller other than Ravilo** (Play services' card's *Stop cast*; Google Home's and a
   display's own Stop do the same) reaches `ravilo-cast` as a CAF `STOP`: CAF stops the player and
   `MEDIA_FINISHED(STOPPED)` was read as a **failure** (`failed()`), the receiver showed its idle view, the receiver app
   kept running and the phone kept its session; `MusicCast.holdsDevice` still held the speaker, so Play sent the song
   back there (R353 FR-R353-5's resume, meant for the dashboard's Stop).
   Ravilo's own *Stop casting* — the card's button, the ⋯ menu, the R245 remote, the sheet, the mini bar's toast — ends
   the session with the receiver stopped (`endCurrentSession(true)`) and was right; but pressed while the phone held no
   live song (a failed or ended report, inside the card's grace) it set `endedByApp` without taking anything back, so
   the hand-back from the last live song was skipped as well.

### Requirements

**FR-R356-12 — The card is linked to the cast's route.** While the card mirrors a cast, the remote player's
`DeviceInfo` carries the cast's routing controller id: on Android 11+ (`MediaRouter2`), the one controller that is not
the system's (Media3 `RemoteCastPlayer`'s rule; none or more than one ⇒ none, as today). It is read again whenever a
routing controller is created, changed or released, and when the card starts mirroring. Whether Play services then
withholds its own card for this session is its decision and is seen on the device. If it still posts it, what remains
is the phone's own setting (Google settings → Devices & sharing → Cast options → *Media controls for Cast devices* /
*Show remote control notifications*), which no app can change; the build notes say which.

**FR-R356-13 — The receiver's media keeps the current song's facts** (already true; restated so it stays): title,
artist, album, album artist and the song's album cover as its one image, rewritten on every song. Only the queue left
the CAF media (FR-R356-8).

**FR-R356-14 — Stop is stop casting, from anywhere.**
- (a) **Ravilo's card**: *Stop casting* and the platform's Stop (a headset, a car, a watch: Media3 `COMMAND_STOP`, now
  offered) end the Cast session with the receiver stopped, exactly as the app's own *Stop casting*; the speaker's song
  comes back to the phone paused at its place. Pause stays pause. Play, next, previous, seek and the volume are
  unchanged.
- (b) **Any other controller's Stop** (a CAF `STOP` request — Play services' card, Google Home, the Assistant, a
  display's own Stop, a TV remote's Stop key where CAF delivers one) ends the receiver app: the receiver reports the
  stop to the server, forgets the item (so CAF's `MEDIA_FINISHED` is never read as a failure) and closes
  (`CastReceiverContext.stop()`). Every sender's session ends; the phone takes the speaker's song back paused at its
  place (R353 FR-R353-5, a session that ends from outside); Play plays on the phone. A film the same way (the phone's
  R245 rule for a session that ends from outside: no bar, no error).
- (c) The Jellyfin dashboard's Stop is **unchanged**: it is a server command, not a CAF `STOP`, and keeps the session
  (R353 FR-R353-5, second amendment).
- (d) `MusicCast.stop()` takes the speaker's song back itself only when a song is live; otherwise it leaves the
  hand-back from the last live report to the session's end.

**FR-R356-15 — Tested.** The card's commands (what the platform's play, pause, stop, next, previous and the custom
*Stop casting* each do) and the receiver's stop rule are unit tests.

### Acceptance (amendment)

6. Casting music to a speaker, ■ on Ravilo's lock-screen card: the speaker goes quiet and leaves the app, the cast
   glyph goes dark, the phone's player shows the speaker's song paused at its place; Play plays it **on the phone**.
7. The same with Play services' card's *Stop cast* (if it is shown) and with Google Home's Stop.
8. Pause on either card pauses the speaker; Play resumes it there.
9. `dumpsys media_session`: Ravilo's session reads `volumeType=REMOTE(volumeControlId=<id>)` while casting (Android
   11+); whether `cast_rcn_media_session` still has a card is recorded in the build notes.
