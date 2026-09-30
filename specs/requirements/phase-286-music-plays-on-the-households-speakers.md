# Phase 286 — Music plays on the household's speakers: the receiver on an audio-only device

> Owner, 2026-09-28: Ravilo music should play on the two Cast speakers (**Stue**, **Gæsteværelse** — Nest Wifi
> points), keep playing when the phone is closed, and be picked up again when it is reopened.

## Status

`✓ Built` 2026-09-29 (§Build notes; commit `86addb87`) — written 2026-09-28 from `research-reports/music-cast-to-speakers-2026-09-28.md` (§0–§9, road B) and the
mockups `design/ravilo/Speakers - Directions.html` (§E, the display receiver), `design/app/settings.html` (the
Chromecast card's step 5a and registered-state lines), `design/app/ravilo-users.html` (a speaker row) and
`design/app/dashboard-data.js` (the Services line). **Dev-reviewed 2026-09-28** against `main` `32daeee2` (§Dev review). Number verified free on `main`
2026-09-28. The phone side is **R324**; both ship together.

**Builds on** 218 / 226 / 235 (the receiver, its registration, the card) · 279 (`/api/tv/music/**`) · R245
(enrolment, reconnect, FR-R245-15's no-controls rule). **Amends** FR-R245-15 for music on a display (FR-286-5).

## Decisions (owner, 2026-09-28)

| # | Question | Answer |
|---|---|---|
| Road | Default receiver · our receiver registered for audio · backend as sender | **B — our receiver, registered for audio-only devices.** C is a later phase of its own |
| Q4 | A speaker against the session ceiling | **Only while it transcodes** (WMA); a direct-played MP3 is a file download |
| Q5 | Speaker groups | **Round 1** |
| Q6 | Backend verifies the audio registration itself | **No** ("best experience"): the first real cast to a speaker confirms it and flips the card |
| Q8 | Music on a TV or the hub | **The TV's own remote works** (FR-286-5), and the hub is tap |
| Q9 | Lyrics on a display receiver | **Yes, round 1** — synced lyrics only, per display, off at first (FR-286-6) |

## Requirements

**FR-286-1 — One more box on the existing application.** No new Cast app, no new receiver URL, the same
application id. The Chromecast card's *Register the receiver* group gains step **5a**: *For the household's
speakers: on the console's Applications page, open Ravilo → Edit and tick **Supports casting to audio-only
devices** — this is what lets Ravilo see the speakers. Same application ID, no second fee. It can take up to 15
minutes to reach the devices, and a speaker may need a restart before it asks again.* Marked *Optional · music
only — a speaker never gets video.*

**FR-286-2 — The registered state says it honestly** (218 FR-218-7's rule): *Speakers **reachable** · Stue played
music yesterday 18:02 — only a real cast confirms it; jellystructure can't ask Google*, or *Speakers **not
confirmed** — tick step 5a, then cast a song to one once.* The first successful cast to an audio-only device flips
it. No backend probe (Q6).

**FR-286-3 — Headless on a speaker.** On `display_supported: false` the receiver builds **no screens at all** (no
idle mark, no gradients — the DOM stays empty), uses §2's audio device profile, caps the media-source buffer, and
sends a metadata block (title · artist · album · album artist · cover URL) so the Home app and the Assistant show
what is playing.

**FR-286-4 — The receiver owns the queue.** A LOAD carries a queue of track items; the receiver resolves each
item's ticket as it comes up (per-item playback-info handler), reports the queue back, advances, honours repeat
and shuffle, and reports progress and stop **per song** through 279's `progress` / `stop`, so *Recently played* and
play counts are right. Nothing changes in 279's DTOs.

**FR-286-5 — Now playing on a display** (hub 1024×600, TV 1920×1080; Aurora · Midnight · Noir): the receiver's
eleventh screen — cover, *Now playing*, title, artist, album · year, a hairline progress with times, and *Next ·
{song}* in one quiet line. No on-screen controls. **The display's own remote works** (Q8; amends FR-R245-15 for
music): **OK / Enter** and the remote's **Play/Pause** key toggle; a **Stop** key, where the remote has one, stops
the music; **◀ ▶** previous / next; **hold ◀ ▶** seeks 10 s; **▼** turns lyrics on or off; **Back** only hides the
transport row — it never stops. On the hub, a tap does the same. When the queue ends, the screen **is** the idle
view — no *finished* card.

**FR-286-6 — Lyrics on a display** (Q9): only a song with **synced** (timed) lyrics; the receiver shows the current
line and two either side under the cover row, following its own position. A per-display switch, **off at first**,
set from the phone's ⋯ menu (R324 FR-R324-5) or with ▼ on the remote, and remembered per display.

**FR-286-7 — Users & devices.** A speaker enrols as `kind = cast` with an **audio only** badge; the capability line
reads *audio only · MP3 · AAC · FLAC · Opus direct; WMA converted on the server*; the receiver line adds *counts as
a session only while converting* (Q4). A group enrols as its leader.

**FR-286-8 — The session ceiling** (218 FR-218-8) counts a speaker only while it transcodes.

**FR-286-9 — Dashboard** (285's Services domain): *Chromecast · speakers not confirmed — step 5a* (information)
until the first speaker cast; nothing once they are.

## Out of scope

Road C (the backend as a Cast sender — the only way an iPhone reaches the speakers) · AirPlay speakers · video to
any audio device · multi-room sync beyond Google's own groups.

## Acceptance

1. With the box ticked and Stue restarted, the phone lists Stue; an MP3 album plays on it; the receiver's DOM is
   empty.
2. The phone is locked for an hour; Stue plays through the queue; *Recently played* has every song.
3. The card flips from *not confirmed* to *reachable · Stue played music …* after that first cast.
4. On the hub, Now playing shows the song and the next; OK pauses; Back hides the row and the music plays on.
5. A WMA song on Stue counts one session while it converts; an MP3 counts none.

## Open questions (for the dev review)

1. Whether a Nest Wifi point accepts a queue LOAD of 30 items in one message, or the receiver must page it.

## Dev review (2026-09-28, against `main` `32daeee2`)

Buildable on the receiver as it stands; the receiver already runs its own item list for episodes, which is the
shape the music queue reuses. Eleven items. The household has five Cast devices: two TVs, the Nest Hub, and two
screenless speakers — today the phone's sheet sees only the three displays (the app is not registered for audio).

1. **Registration and the card.** FR-286-1's step 5a is a change inside `Settings.kt`'s `#cc-steps` (step 5 today).
   FR-286-2 needs the backend to know a speaker cast happened: the receiver passes `platform = "cast-audio"` on
   redeem when `display_supported` is false (the `platform` column is free text — no schema change);
   `ChromecastStatus` gains `speakers_confirmed_at` (additive) = the newest `last_seen` of a `cast-audio` device.
2. **Headless (FR-286-3).** CAF's `context.getDeviceCapabilities().display_supported`; today `Receiver.kt` builds its
   DOM unconditionally (`start()` · `idle()` · `show()`), so every `el()` path is gated on a `headless` flag. The
   metadata block is `MusicTrackMediaMetadata` (title · artist · albumName · albumArtist · images) instead of the
   `GenericMediaMetadata` at `:199`. `canDisplayType` means nothing on a speaker: capabilities are a fixed audio set
   (MP3 · AAC · FLAC · Opus · Vorbis direct; WMA converted — 279's music negotiation already sets
   `directPlay = !needsTranscode`, `PlaybackService.kt:641`).
3. **The queue (FR-286-4) is the episode list, for songs.** The receiver already carries `CastLoadData.episodes` and
   self-issues a LOAD per item (`nextEpisode()`/`loadNext()`, `Receiver.kt:339–380`). `CastLoadData` gains
   `tracks: List<CastTrackItem>` (id · title · artist · album · cover URL · duration), `repeat`, `shuffle` — additive
   (R319). Each song is negotiated through `POST /tv/music/play` with the receiver's own token (it is a `cast` device
   carrying the phone user's token — `CastService.redeem`) and reported per song through the generic
   `/tv/playback/progress` and `/tv/playback/stop` (279 routes music there). **Open question 1 is answered:** the LOAD
   carries ids and titles, ~120 B a song — 30 songs ≈ 4 KB, far under the Cast message ceiling (64 KB). No paging.
4. **Google's own next/previous** (the Home app, the Assistant, a display's remote) arrive as `QUEUE_UPDATE`
   (jump ±1) / `QUEUE_NEXT` / `QUEUE_PREV` messages: the receiver intercepts them (`PlayerManager.setMessageInterceptor`)
   onto its own list and advertises `supportedMediaCommands` with `QUEUE_NEXT | QUEUE_PREV`, or the Home app hides the
   buttons. Verify on a speaker (acceptance 1).
5. **The display's remote (FR-286-5).** Media keys (play/pause, next, previous, stop) reach a web receiver as media
   commands — the same interceptor; the D-pad reaches the page as DOM `keydown` (ArrowLeft/Right/Down, Enter); a tap
   on the hub is `click`. **Back on a Chromecast with Google TV is the platform's** — it may leave the app rather
   than reach the page; verify on the stue TV, and if Back cannot be kept, the transport row auto-hides after 5 s
   instead (the music never stops either way). Amends FR-R245-15 for music.
6. **Lyrics on a display (FR-286-6).** The receiver fetches `GET /tv/music/track/{id}/lyrics` (279) itself, synced
   only; the per-display switch lives in the receiver's `localStorage` (where `receiverId` already lives) and is set
   by a `CastCommand(type = "lyrics", on)` from R324 or by ▼.
7. **The ceiling (FR-286-8).** `CastService.checkCeiling` counts every playing `cast` device; the tracker's
   `TrackedPlayback` needs `directPlay` (the ticket knows it) recorded at `/tv/playback/start`, and the count skips
   `cast` devices whose session is direct — so a direct-played MP3 on a speaker costs nothing.
8. **Users & devices (FR-286-7).** `RaviloUsers.kt:91` maps `"cast" → "Chromecast"`; the *audio only* badge and the
   capability line key on `platform == "cast-audio"`. A group's name is the sender's `friendlyName` (the group),
   already passed as `CastLoadData.deviceName` ✓.
9. **Dashboard (FR-286-9)** rides 285's Services domain — 285 first, else the line lands in today's Services card.
10. **Wire:** `CastLoadData.tracks/repeat/shuffle`, new `CastCommand` types, `CastReceiverMessage.queue` (the snapshot
    R324 mirrors), `ChromecastStatus.speakers_confirmed_at` — all additive. The receiver is served by the backend
    (218), so it moves with the release; the phone must not offer music casting against a server without it —
    `RaviloConfig.cast.music = true` (additive, default false) gates R324's music-mode sheet.
11. **Build order:** 279 (built) → this phase → R324; the two ship in one release because the receiver and the phone's
    music sender share the shapes in item 10.

## Build notes (2026-09-29)

Built from the dev review (commit `86addb87`, together with R324):

1. **FR-286-1/2** — step **5a** on the Chromecast card and the registered state's speaker line (*reachable · {speaker}
   played music …* / *not confirmed — tick step 5a…*); `ChromecastStatus.speakers_confirmed_at` = the newest `last_seen`
   of a receiver whose platform is `cast-audio` (`CastService.kt`). No backend probe.
2. **FR-286-3** — the receiver asks `getDeviceCapabilities().display_supported` once; on a headless device the body is
   emptied and every element lookup lands on a detached node, so nothing is ever drawn; its `TvApiClient` says
   `cast-audio`; the audio capability set is fixed (MP3 · AAC · FLAC · Opus · Vorbis direct); the metadata block is
   `MusicTrackMediaMetadata` with the cover.
3. **FR-286-4** — `CastLoadData.tracks/repeat/shuffle` (additive); each song is negotiated through `playMusic` with the
   receiver's own token and reported per song; repeat and shuffle live on the receiver; `QUEUE_NEXT` / `QUEUE_PREV` /
   `QUEUE_UPDATE` are intercepted onto its list and `supportedMediaCommands` advertises next/previous.
4. **FR-286-5/6** — the Now playing screen (`cast-receiver/index.html`, `vw`-scaled so the hub and a TV share one
   drawing), the key handling (OK/Enter · Play/Pause · Stop · ◀ ▶ · held seek · ▼ · Back hides only; a click on the
   hub toggles), the transport row for 5 s, synced lyrics as five lines with the per-display switch in `localStorage`.
   The queue's end **is** the idle view.
5. **FR-286-7/8/9** — the *audio only* badge and capability line in Users & devices; `TrackedPlayback.directPlay` and
   `activeDirectDeviceIds()`: a receiver counts against the ceiling only while it converts, and a music start checks
   the ceiling only when it transcodes; the Dashboard's *speakers not confirmed — step 5a* line (285's Services).
6. **Wire** — `CastReceiverMessage.queue/queue_index/repeat/shuffle/lyrics_on/headless`, the new `CastCommand` fields,
   `CastCapability.music = true` — all additive (R319).

**Unverified on hardware, and named here rather than assumed:** whether CAF plays audio with no `<cast-media-player>`
in an emptied body (the framework's own documentation says an audio receiver needs no UI); whether the Nest Wifi
points accept the queue LOAD and whether `QUEUE_UPDATE` carries `jump`; whether Back on a Chromecast with Google TV
reaches the page (dev review 5's fallback — the transport row auto-hides after 5 s either way). **Step 5a is the
owner's to tick on the console** before any speaker appears; nothing here can be exercised until it is.

Not deployed and not device-tested: the owner withdrew backend-restart and device permission on 2026-09-29, mid-round. Verified by compile (`compileKotlinLinuxX64` · `compileKotlinWasmJs` · `:ravilo-ui:compileDebugKotlinAndroid` · `:ravilo-web:compileKotlinWasmJs` · `:ravilo-cast:compileKotlinJs`), the unit tests named below, and the six fences.

### Verified locally (2026-09-29, no hardware)

The real `ravilo-cast.js` bundle in headless Chromium behind a fake CAF (the e2e helper's shape, extended with
`getDeviceCapabilities`, `MusicTrackMediaMetadata`, the `QUEUE_*` message types and `setSupportedMediaCommands`),
against the backend binary on a scratch config with three seeded albums and the e2e Jellyfin mock streaming the
files: **display 24/24, headless 17/17.** A music LOAD answers an `audio/*` content type and a `MusicTrackMediaMetadata`
block; the status carries the queue snapshot; `QUEUE_NEXT|QUEUE_PREV` are advertised (one fix on the way: `or` on a
`dynamic` is a JS method call, so the flags are now combined in JS); Now playing paints and follows `TIME_UPDATE`;
Enter toggles, ▼ turns lyrics on (status says so), Back hides the row and never stops, ▶ tap self-loads the next song;
Google's `QUEUE_NEXT` is swallowed and lands on the list; `queue_add`/`repeat`/`play_at` round-trip; the queue's end
is `ended` and the idle view; the headless receiver builds no DOM, enrols with `platform = cast-audio`,
`speakers_confirmed_at` flips and names it; the admin shows step 5a, the speaker line, the *audio only* device row,
and the Dashboard's Services line appears before the first speaker cast and is gone after it.

## The receiver did not start (found and fixed 2026-09-30)

From the hour this phase was deployed (2026-09-29 11:26) until 2026-09-30 12:02, **no cast worked from any sender**,
films included. The queue interceptors this phase added call `setMessageInterceptor(QUEUE_NEXT)`, which the live
framework (CAF 3.0.0156) refuses by throwing *"Unknown message type - QUEUE_NEXT"*; the exception left
`Receiver.start()` before `context.start()`. The page showed its idle screen, the device waited 60 s for the app to
reach RUNNING and aborted it (`APP_ERROR_TIMEOUT` in the TV's log). Each interceptor is now registered on its own and
may fail with a warning; `QUEUE_UPDATE`, which carries next and previous from every sender SDK, is accepted.

The round that verified this phase ran against a stand-in framework that accepted the call — the same shape as R245's
amendment (`PLAYER_STATE_CHANGED`). **Owed:** a check that loads the built `/cast/` page against the real framework in
a headless browser and fails on *"failed to start"*; both of these would have been caught by it.

**Step 5a is still not done on Google's side:** the app's published configuration carries no audio-only support, and
both of the household's screenless speakers answer `APP_UNAVAILABLE` for it. Until *Supports casting to audio only
devices* is ticked (and saved) on the Cast console, no Ravilo client can list or reach a speaker.


## 2026-09-30, afternoon — step 5a is live, and the speakers still cannot start the app: they cannot reach the server

The owner ticked the box; within about two hours both speakers answered `APP_AVAILABLE` and the Mac's *Play on…*
lists them (*Speaker · Ready*). Choosing one does nothing for a minute, then the speaker gives a short tone.

Watched from a second, passive Cast connection: the speaker opens a playback session for the app, no application
ever appears in its receiver status, and after 60 s the session is gone — the same 60-second wait as a receiver page
that never starts. The page is not the cause this time:

| The speaker (volume zero, Google's default receiver) is asked to play a file from | Result |
|---|---|
| the server's **LAN address**, plain HTTP | `PLAYING` after 0.6 s |
| the server's **public name** (the name the receiver page and every stream use) | stays `IDLE`; nothing is fetched |

More probes the same afternoon pinned it down (all silent, Google's default receiver):

| The speaker is asked to play a file from | Result | What it shows |
|---|---|---|
| a name **only the router's DNS knows** | `LOAD_FAILED` at once | the speaker does not use the network's DNS |
| a public DNS name that answers with the LAN address, plain HTTP | plays | it resolves through a public resolver and reaches LAN addresses |
| the server's public name on the **Jellyfin port**, plain HTTP | plays | the public address loops back on that port |
| the server's public name on **443** | never loads | it does not on 443 |

And from two machines on the LAN, addressing the house's public address directly: the Jellyfin port answers in
20 ms; **443 and 80 time out**. So:

1. These two speakers (Nest Wifi points) resolve names through a public resolver, whatever the network hands out.
   The router's own record for the server (the LAN address) is never seen by them. **Not every Google device does
   this:** the kitchen's Nest Hub, given the same router-only name, resolved it and played — it uses the network's
   DNS, as the Android TVs do, which is why casting to those works.
2. It therefore goes to the house's public address, and this router loops every forwarded port back inside **except
   80 and 443** (its own management interface sits on those). The receiver page, the API and every stream are on 443.
3. A receiver page loaded over HTTPS may not call a plain-HTTP address: tried on the bedroom TV with the LOAD's
   `server_url` pointed at a plain-HTTP listener on the LAN — the page reported *noserver* at once and the listener
   saw no request. (A media element may: the default receiver plays plain-HTTP files.) So "give the receiver the
   LAN address" is not available for the API as the receiver is built.

Spotify plays on the same speaker because its receiver page and its audio come from Spotify's servers on the
internet; nothing in that path is inside the house.

**For this house** one rule fixes it: destination NAT for LAN → the public address on 443 → the server. **For any
house**, the choices are in *What the product should do* below.

**What the product should do (proposed, not built):**
- *Say it.* The Chromecast card's reachability check (218 FR-218-7) fetches `/cast/` from the server itself, where
  the name resolves locally — it passes while a speaker cannot get in. It should resolve the public name through a
  public resolver and fetch through that answer, and say in one sentence when that fails: *"Some Google speakers
  cannot reach this address from inside your network."* The failure is otherwise silent on every
  screen: the app says *Ready*, then nothing.
- *Not depend on it* (a phase of its own, the owner's call): a speaker that cannot start the Ravilo receiver is
  played through Google's own receiver page with the queue handed to the device (`QUEUE_LOAD`) and the media
  addressed on the LAN over plain HTTP. It needs a LAN address the media answers on, and gives up what the Ravilo
  receiver does itself (its own session, progress while no app is connected, lyrics on a display).
