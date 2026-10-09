# Phase R330 — Ravilo on the Mac casts: music to the household's speakers, films to the TVs

> Owner, 2026-09-29: *"I would like to be able to stream music from this app to the wifi speakers etc as well."*

## Status

`⚠ Partial` — **built 2026-09-29; the protocol is tested against a fake device, but nothing has talked to a real Cast device, and the Swift half has not been compiled** (§Build notes). Written 2026-09-29 (dev-authored) from `research-reports/ravilo-macos-desktop-app-2026-09-29.md` §4.
**Dev-reviewed 2026-09-29** against `main` `4c67e49f` (§Dev review) — build from it. Number verified free. **Third of four** (R328 → R329 → **R330** → R331).

**Builds on:**

- 286 — the receiver owns the queue and plays on audio-only devices;
- R324 — the phone as the speaker's remote: the *Play on…* sheet, take-over, hand-off, the ⋯ block;
- R245/R265 — the sender seams and the sheet;
- R327 — one row per Chromecast.

**Needs** 286 step 5a ticked on the Cast console for the speakers.

## Why this is possible without Google

There is no Google Cast SDK for macOS or the JVM. The Cast protocol it speaks is small and public:

- mDNS discovery;
- TLS to port 8009;
- length-prefixed protobuf messages on named channels.

What stopped the *backend* from speaking it (286's road C) was opening that TLS socket from Kotlin/Native. The JVM has
TLS built in. Everything R324 built above the sender seams is common code, so a Mac sender makes all of it work
unchanged — against the same receiver the phone uses.

## Decisions (leans)

| # | Question | Lean |
|---|---|---|
| D1 | Library or our own | **Our own**, in a new module `:ravilo-castv2`: the protocol in `commonMain`, the TLS transport behind one `expect`. It gets a JVM actual now, and later a Kotlin/Native actual so the backend becomes a sender for the iPhone (286 road C). [chromecast-java-api-v2](https://github.com/vitalidze/chromecast-java-api-v2) exists (Apache 2.0) but calls itself "not stable … a lot of bugs" |
| D2 | Which devices are listed | **Only those that can run Ravilo**, as the SDK does: asked once per device with `GET_APP_AVAILABILITY` for our application id |
| D3 | The Mac's volume keys | **Stay the Mac's.** The ⋯ slider drives the speaker (R324's FR-R324-6 is phone-only) |
| D4 | The cast's system card | the Mac's **Now Playing**, the equivalent of Android's Cast notification (FR-R324-9) |

## Requirements

**FR-R330-1 — The protocol, as a module.** `:ravilo-castv2` with `jvm()` (and room for `linuxX64()` later).

In `commonMain`:

- **framing** — a 4-byte big-endian length, then a protobuf `CastMessage`, hand-encoded: protocol version, source,
  destination, namespace, payload type, UTF-8 payload;
- **the connection channel** — `CONNECT` / `CLOSE`;
- **the heartbeat channel** — `PING` / `PONG` every 5 s; three missed answers close the session;
- **the receiver channel** — `LAUNCH`, `STOP`, `GET_STATUS`, `GET_APP_AVAILABILITY`, `SET_VOLUME`;
- **the media channel** — `LOAD`, `PLAY`, `PAUSE`, `SEEK`, `STOP`, `GET_STATUS`, `QUEUE_NEXT`, `QUEUE_PREV`;
- **our own channel** — `urn:x-cast:dev.jellystructure.ravilo`, carrying `CastCommand` / `CastReceiverMessage`
  unchanged;
- one session state machine over all of the above.

In `jvmMain`: an `SSLSocket` that accepts the device's self-signed certificate. Every sender does this; checking the
device's certificate chain is not done.

Unit tests: round-trips of every message against golden bytes, and the state machine against a fake transport.

**FR-R330-2 — Discovery.** JmDNS browses `_googlecast._tcp.local.`, and only while the app is on screen (R293's rule).
Each service becomes a `CastRoute`:

- `id` — the TXT record's device id;
- `name` — its friendly name;
- `kind` — `group` for a group, `speaker` when the capability mask has no video output, else `display`;
- `busyWith` — the running app's name, from the TXT status or FR-R330-4's `GET_STATUS`.

A device appears only after D2's availability answer says our application id can run there. The answer is cached per
device for the app's session. *The exact TXT keys are verified on the household's five devices and recorded in the
build notes.*

**FR-R330-3 — The sender.** `CastSenderDesktop : CastSender`, handed to `ActiveCastSender` beside `ScreenSender`.
`hasCastSdk = true`.

- `load` — `LAUNCH` our app if it is not running, `CONNECT` to it, then `LOAD` on the media channel with
  `CastLoadData` as `customData` — exactly the message the phone's SDK sends. A music load carries 286's tracks, a film
  R245's episodes.
- `play`, `pause`, `seekTo` — the media channel.
- `send(json)` — our channel.
- `setVolume` — `SET_VOLUME`.
- `stop` — `STOP` the app; only ever explicit (FR-R245-10).

`CastRemoteStatus` is rebuilt from the media channel's status plus the receiver's own messages, by the same merge as
`CastSenderAndroid.rebuildStatus`. That function moves to `commonMain` as a pure function, tested once and used by both
senders.

**FR-R330-4 — A busy device** (FR-R324-2):

- `GET_STATUS` names the app another sender left running (*Busy · Spotify*).
- R324's confirmation — *Stop {app} and play here?* — is the only gate. `LAUNCH` replaces the other app.
- A device already running **Ravilo** is joined, not relaunched: `CONNECT` to its session, ask our channel for
  `status`, and the Queue tab and Playing rebuild from the receiver's snapshot.

**FR-R330-5 — Reconnect** (FR-R245-5, FR-R324-8). When the app starts, a device this Mac last cast to that is still
running Ravilo with media loaded is rejoined silently. Otherwise nothing happens: no bar, no toast.

**FR-R330-6 — Films too.** The same sender casts films to the TVs and the hub (the displays). As on the phone, video
mode lists no speaker and says nothing (FR-R324-1), and R245's remote and mini bar work unchanged.

**FR-R330-7 — Now Playing while casting.** While a speaker plays, the Mac's Now Playing (R329 FR-R329-10) shows the
speaker's song. Its play, pause, next and previous go to the receiver.

**FR-R330-8 — Local Network permission.**

- The app bundle declares `NSLocalNetworkUsageDescription` (*Ravilo looks for your speakers and TVs on this network.*)
  and `NSBonjourServices` = `_googlecast._tcp`. Without them macOS 15 blocks discovery silently.
- The first scan triggers macOS's prompt.
- If access is denied, the sheet says once, under its rows, **Ravilo needs Local Network access to find speakers and
  TVs** · **Open Settings** (the Privacy & Security → Local Network pane).
- Strings × en · da · fo: `mac.local_network`, `mac.open_settings`.

**FR-R330-9 — Gating is R324's.** The cast glyph appears only with `RaviloConfig.cast` present; the music-mode sheet
only with `cast.music = true`. Against a server without either, the Mac shows exactly what the phone would.

## Out of scope

The backend as a sender (286 road C — this module is written for it; the transport and the routes are a later
phase) · AirPlay speakers · casting from the web app · casting away from the home network (mDNS does not cross the
VPN).

## Acceptance (286 step 5a ticked)

1. In music mode the sheet is *Play on…* and lists Stue, Gæsteværelse, their group, the hub and the two TVs; in video
   mode only the hub and the TVs.
2. An album started on the Mac moves to Stue at the same position. The Queue tab edits the speaker's queue.
   *Play on this phone* (*Play on this Mac*) brings it back.
3. With Spotify playing on Gæsteværelse, the row reads *Busy · Spotify*, asks first, and takes over.
4. The ⋯ slider changes Stue's volume. The keyboard's media keys pause and skip the speaker.
5. Quit and reopen the app while Stue plays: the mini bar shows Stue's current song.
6. A film casts to a TV and the remote drives it.
7. With Local Network access denied, the sheet's one line appears and *Open Settings* opens the right pane.

## Open questions

1. Is `GET_APP_AVAILABILITY` answered by audio-only devices the same way as by displays? Verify in the cast spike
   (research report §4) before FR-R330-2 is built.
2. Should *Play on this phone* read *Play on this Mac* on the Mac? Lean yes — one more string (`cast.play_here_mac`).

## Dev review (2026-09-29, against `main` `4c67e49f`)

Buildable; the protocol facts below are from the public Cast v2 documentation and open senders, to be confirmed in
the spike on the household's five devices. Thirteen items.

1. **Discovery: lean macOS's own Bonjour, not JmDNS, on the Mac.** `NWBrowser` for `_googlecast._tcp` in the Swift
   library (R329's) sees every interface the system does and is what macOS 15's Local Network prompt is designed
   around; JmDNS stays for the Linux dev build. The TXT keys a Cast device publishes: `id` (device id), `fn` (friendly
   name), `md` (model), `ca` (capability bits — the SDK's `CastDevice` constants: 1 video out, 2 video in, 4 audio
   out, 8 audio in, 32 multizone group), `rs` (receiver status — the **running app's display name**, which is what the
   SDK shows as R324's route description), `st`. So `kind` and `busyWith` come from the record alone, before any
   connection. *Verify all six keys on Stue, Gæsteværelse, their group, the hub and a TV.*
2. **Protocol facts to pin (FR-R330-1):** sender id `sender-0`, the platform receiver `receiver-0`; `LAUNCH` answers
   with a `RECEIVER_STATUS` carrying the app's `transportId` and `sessionId`; a **second** `CONNECT` goes to that
   transport id, and every media and custom-namespace message is addressed to it; media commands carry the
   `mediaSessionId` from the last `MEDIA_STATUS`; `requestId` correlates answers. `GET_APP_AVAILABILITY` answers
   `availability[appId] ∈ {APP_AVAILABLE, APP_UNAVAILABLE}`; `SET_VOLUME {volume: {level}}` on the receiver
   namespace sets the device (or group) volume; `LAUNCH` replaces whatever runs, with no confirmation from the device
   — R324's sheet is the only gate, as FR-R330-4 says. Our custom namespace works because the receiver registers it
   (`addCustomMessageListener(CAST_NAMESPACE)`) and CAF advertises it in the app's status.
3. **`CastMessage` by hand is small:** seven fields — `protocol_version` (enum, 0), `source_id`, `destination_id`,
   `namespace`, `payload_type` (0 string / 1 binary), `payload_utf8`, `payload_binary` — behind a 4-byte big-endian
   length. No protobuf library; golden-byte tests as the FR says.
4. **Heartbeat:** `PING` every 5 s on `receiver-0`; CAF drops a sender that goes quiet for ~10 s, so a missed
   `PONG` window of three (15 s) is the right close rule. The heartbeat runs on the platform connection, not the app's.
5. **`rebuildStatus` moves to common code with a small neutral input.** `CastSenderAndroid.rebuildStatus` touches
   the SDK at 16 lines (`MediaStatus`, `RemoteMediaClient`, `MediaMetadata`); extract `mergeStatus(prev, said, media:
   MediaSnapshot, event)` into `commonMain` with `MediaSnapshot(playerState, idleReason, positionMs, durationMs,
   activeTrackIds, title, subtitle, imageUrl, metadataType)`, Android mapping the SDK into it, the Mac mapping
   `MEDIA_STATUS` JSON. `MusicCast` (R324) reads only `CastRemoteStatus`, so it is untouched.
6. **The controller is untouched.** `rememberCastSender`'s desktop actual returns
   `ActiveCastSender(CastSenderDesktop(...), ScreenSender(api))`; `castOnChromecast(route, music)` calls
   `route.select()`, which on the Mac opens the session; `pendingMusicHandoff` and the hand-off code
   (`POST /api/tv/cast/handoff`, the Mac's own session — `TvRoutes.kt:662`) work exactly as on the phone.
7. **Reconnect (FR-R330-5) without an SDK:** `ScreensSheetPrefs.lastDevice` already remembers the device; on start,
   once discovery finds it, `GET_STATUS` on the receiver namespace says whether our application id is running; if so
   `CONNECT` to its transport id and send our `status` command — the receiver answers with 286's queue snapshot and
   `MusicCast` rebuilds. Both of FR-R245-5's outcomes fall out; nothing is remembered about the media itself.
8. **Local Network (FR-R330-8) and development.** TCC binds the prompt to the **bundle**; a bare `java` process from
   `:ravilo-desktop:run` gets no prompt on macOS 15 and multicast fails silently. Develop discovery against the
   packaged app (`createDistributable`, then run `Ravilo.app`), and say so in the module's README. Ad-hoc signing
   (owner, no Apple bills) does not affect the prompt.
9. **`hasCastSdk = true` on the Mac** hides R324's *Speakers need the Android app for now* line — correct, the Mac has
   its own sender.
10. **Away from home:** nothing to build — mDNS does not cross the VPN, the sheet stays absent-not-empty (R265).
11. **Films (FR-R330-6):** `CastLoadData.episodes` and R245's remote already ride the same `load`; the receiver cannot
    tell the Mac from the phone. Verified by reading `Receiver.kt`'s `intercept`: it reads `customData` only.
12. **Module and later reuse (D1):** `:ravilo-castv2` with `jvm()` now; a `linuxX64()` target later needs only the TLS
    transport actual (the Kotlin/Native gap the 2026-09-18 report named) — nothing in the protocol layer changes.
    That is 286's road C, and the iPhone's route to the speakers.
13. **Wire:** none — the phone, the receiver and the backend see nothing new. **Size:** about two weeks including the
    spike, on top of R328/R329.

## Build notes (2026-09-29)

Built from the dev review. **Nothing here has spoken to a real Cast device** — device control was off-limits for
this round, and the Linux box would have found and queried the household's own Chromecasts — so the protocol is
tested against a fake device only, and the six TXT keys (dev review 1) and open question 1 are still owed to the
spike on the five devices. `⚠ Partial`.

1. **FR-R330-1 — `:ravilo-castv2`** (a `jvm()` target; `linuxX64()` later needs only the transport actual).
   `CastMessage` encodes and decodes the seven protobuf fields by hand, framed by a 4-byte big-endian length;
   `CastFrameReader` reassembles frames from any split (tested at every cut point, and one byte at a time). The
   golden bytes in `CastMessageTest` come from an independent encoder, not this one. `CastSession` is the state
   machine over any `CastTransport`: an ordered outbox, `CONNECT` to `receiver-0`, `PING` every 5 s with three
   unanswered pings closing it (any message counts as an answer; a device's `PING` gets `PONG`), `requestId` pairing
   with a 10 s timeout, `GET_STATUS`, `GET_APP_AVAILABILITY`, `LAUNCH` or join, a second `CONNECT` to the app's
   `transportId`, media commands carrying the last `mediaSessionId`, our channel both ways, `SET_VOLUME`, `STOP`.
   CAF sends `media` in a `MEDIA_STATUS` only when it changed, so a report without it keeps the last one's for the
   same media session. The app closing, or another sender's app replacing ours, ends the session. The JVM transport
   is an `SSLSocket` that accepts the device's own certificate. Tests: `CastMessageTest`, `CastDeviceTest`,
   `CastMediaStatusTest`, `CastSessionTest` (23, virtual time).
2. **FR-R330-2 — discovery.** On a Mac with the library, `NWBrowser` in `Bonjour.swift` (the address resolved once
   with a short TCP connection); Kotlin polls a snapshot once a second — nothing calls into the JVM. Elsewhere,
   JmDNS. Reference-counted: browsing runs while the sheet is open on screen (R293) or the start-up reconnect needs
   it. `kind` comes from `ca` (32 → group, no video out → speaker), `busyWith` from `rs`, except for the name our
   own app runs under (learned from any status). A device is listed only after `GET_APP_AVAILABILITY` says yes
   (D2); only a definite answer is remembered, so a device that was asleep is asked again.
3. **FR-R330-3 — the sender** (`CastSenderDesktop`, handed to `ActiveCastSender` beside `ScreenSender`;
   `hasCastSdk = true`). The row's `select` opens a session (launch or join); `load` sends the `LOAD` the phone's SDK
   sends — `contentId` `ravilo://{id}`, the same metadata types and fields, `CastLoadData` as `customData` on both the
   request and the media — waiting for the session when it is not open yet, as Android's pending load does. **The
   status merge moved to common code** (`mergeCastStatus`, `foldReceiverMessage`, `isCastBurnIn` over a neutral
   `CastMediaSnapshot`, `CastStatusMergeTest`); `CastSenderAndroid.rebuildStatus` now maps the SDK into the snapshot
   and calls it, rule for rule as before. Subtitle and audio picks follow R285 exactly as on Android. A dropped
   connection is rejoined up to three times; a stop, the app ending or being replaced is silence (FR-R245-5).
4. **FR-R330-5 — reconnect.** The device last cast to is remembered (`cast.last_device`, cleared by *Stop
   casting*); once the server's app id is known at start-up, the Mac looks for it for up to 15 s, joins only if our
   app runs there, and keeps the session only if the media channel reports something loaded — otherwise nothing
   shows. Then it asks the receiver for its `status`, which rebuilds the queue (`MusicCast`, unchanged).
5. **FR-R330-7 — Now Playing while casting**: the speaker's song (or the TV's film), its position and play state,
   with play, pause, seek, and next/previous sent to the receiver as `CastCommand`s. `MacNowPlaying` is now
   synchronized, since the sender updates it off Compose's thread.
6. **FR-R330-8.** `NSLocalNetworkUsageDescription` and `NSBonjourServices = _googlecast._tcp` in the bundle's
   Info.plist. A refusal (`kDNSServiceErr_PolicyDenied`) puts one line under the sheet's rows —
   *Ravilo needs Local Network access to find speakers and TVs* · *Open Settings* (the Local Network pane) — through a
   small common `CastPlatform` the Mac sets. **Open question 2:** yes — *Play on this Mac* (`cast.play_here_mac`).
7. **Owed to real devices:** acceptance 1–7 — the TXT keys on Stue, Gæsteværelse, their group, the hub and a TV;
   whether audio-only devices answer `GET_APP_AVAILABILITY` like displays (open question 1); a take-over from
   Spotify; the volume slider; the Local Network prompt on macOS 15 (only the packaged app gets it, dev review 8).

## First real devices (2026-09-30)

From the owner's Mac, on the household's network: discovery (the Swift Bonjour browser) finds the three displays and
the two screenless speakers, and Local Network access was already granted.

- **A TV asleep answers the launch late.** `LAUNCH` came back as a `LAUNCH_STATUS` with no app, the sender gave up,
  and the receiver came up on the TV with nobody connected. `launchOrJoin` now asks the receiver's status every 2 s
  for up to 20 s after a launch that was not answered with the app, and leaves at once on `LAUNCH_ERROR`.
- **No answer is not "no".** `GET_APP_AVAILABILITY` timing out was remembered as *cannot run our app* for the rest of
  the session; only a definite answer is remembered now.
- **Music to the bedroom TV works:** joined, the song on its Now playing screen, *Stop casting* ends it and the Mac's
  own state returns. (It could not have worked before 286's receiver fix of the same day — see 286.)
- **The two speakers say `APP_UNAVAILABLE` for our app** while answering `APP_AVAILABLE` for the default media
  receiver: Google's published configuration for the app has no audio-only support (286's step 5a, the owner's to
  tick on the Cast console). Nothing in the app can change that answer; the sheet rightly does not list them (D2).
- The cast path now writes a line to the log on connect, load and close.
- Open: a song handed over at 2:40 ended at once on the TV and the queue moved on (the server logged the stop at the
  song's full length); not yet understood, and not the Mac's alone if it is the receiver's seek.

## Triage (2026-10-09, against `main` `9ea5da3c`)

- **Code: nothing found missing.** **Owed on devices:** acceptance 1–7 from the Mac (the TXT keys on Stue,
  Gæsteværelse, their group, the hub and a TV).

### Found live 2026-10-09 — fixed

- **Start-up did not rejoin the speaker once.** *Defect:* the Mac's log shows the installed 1.50 app's start-up rejoin
  ending `→ no connection` (the TCP/TLS connection to the speaker failed — the reason was swallowed), and FR-R330-5 tried
  only once, while a dropped connection is tried three times. The same log shows retries of a dropped connection that
  failed setting the link to NONE between tries, so the music bridge handed the speaker's queue back to the Mac (R353)
  while the next try was about to rejoin. *Fix:* start-up tries again on *no connection* (three tries in all, 2 s apart,
  the device read again from discovery each time; a device that answers with nothing loaded ends it at once); a failed
  try that will be retried keeps the link RECONNECTING, and only the last ends it; a failed connection now logs its
  exception and the address tried. **Open:** the same log shows the speaker closing the Mac's connection every 5–50 s
  (`closed (send failed)`); the test build and the installed app may both have been running — re-test with one Ravilo.
  **Mac re-test owed:** quit during a speaker cast and start again (rejoins, no hand-back in the log).

**Mac re-test (2026-10-09 evening, test build `v1.50-136-gfcec8bf2`, the installed app not running; the speaker's
volume was 0 before and stayed 0): PASS.** Music handed to Gæsteværelse (`hand-off of 60 songs … to Gæsteværelse`);
the app was quit while the speaker played — the speaker played on — and started again: `cast: rejoin Gæsteværelse
[Nest Wifi point] → joined`, link CONNECTED, the bar back on the cast, **no hand-back** in the log, the song kept its
place. **The 5–50 s drops did not happen with one Ravilo:** the link held for 3 min 25 s before the quit and 4 min 20 s
after the relaunch with no `RECONNECTING`, `closed (send failed)` or hand-back line — consistent with the earlier
drops coming from two Ravilos on one Mac fighting over one speaker session.
Small finding: the remote for the speaker cast says *No phone or computer nearby can reach the speakers* under *Add a
speaker…* while the Mac itself is the sender holding that speaker — the line should not show on a sender that can.

### Found live 2026-10-09 (evening) — fixed

- **The speaker remote on the Mac said *No phone or computer nearby can reach the speakers* while the Mac itself was
  playing on that speaker.** The line is shown when the server offers no `add_room`: neither the app holding the speaker
  can make a group (`group_control`, only Android's Cast SDK has it — the Mac's Cast v2 client has no group controller)
  nor is a grouping app nearby to relay it. On the Mac the first half is always false, so the old sentence blamed the
  network for what is the Mac's own limit. *Fix:* on a device that cannot make groups itself the line says what would
  work — *Adding a speaker needs Ravilo open on an Android phone nearby* (`group.needs_phone`, en/da/fo); the old line
  stays for a device that can group but finds no road to the speakers. Test: the reason chosen for each case
  (`addSpeakerBlockedReason`). **Mac re-test owed:** the remote of a speaker cast from the Mac shows the new line; with
  the Pixel's Ravilo open nearby, *Add a speaker…* is offered instead.
