# Phase R356 — The phone stays the remote for a cast: alive while locked, never silent, and a small status

> Owner, 2026-10-02 15:49–15:55 (debug build `1.48-95`, music cast to the Gæsteværelse speaker): the phone was locked;
> *next* in Ravilo's cast notification did nothing; the song played to its end; opening Ravilo, the Playing page sat on
> the loading dots at 0:00 / -0:00 under *Playing on Gæsteværelse* for 20 s while the speaker played on. Removing the
> app from recents and opening it again fixed it.
>
> Owner, 2026-10-02 15:45 (added): the lock-screen media card (*Caster til Stue*, chip *Stue + 1*) showed the right
> song with **another album's cover**, while Ravilo's Playing page showed the right one.

## Status

`Planned` — written 2026-10-02 (dev-authored, owner-approved the same day) against `main` `f628c2f2`. Number checked
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
