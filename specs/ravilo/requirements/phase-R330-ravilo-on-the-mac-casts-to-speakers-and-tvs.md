# Phase R330 — Ravilo on the Mac casts: music to the household's speakers, films to the TVs

> Owner, 2026-09-29: *"I would like to be able to stream music from this app to the wifi speakers etc as well."*

## Scope

Written 2026-09-29 (dev-authored) from `research-reports/ravilo-macos-desktop-app-2026-09-29.md` §4, and
dev-reviewed 2026-09-29 against `main` `4c67e49f` (§Dev review). **Third of four** (R328 → R329 → **R330** → R331).

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

## Triage (2026-10-09, against `main` `12bffb29`)

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

**Mac re-test (2026-10-09 evening, test build `v1.50-136-ge5b4ec68`, the installed app not running; the speaker's
volume was 0 before and stayed 0): PASS.** Music handed to Gæsteværelse (`hand-off of 60 songs … to Gæsteværelse`);
the app was quit while the speaker played — the speaker played on — and started again: `cast: rejoin Gæsteværelse
[Nest Wifi point] → joined`, link CONNECTED, the bar back on the cast, **no hand-back** in the log, the song kept its
place. **The 5–50 s drops did not happen with one Ravilo:** the link held for 3 min 25 s before the quit and 4 min 20 s
after the relaunch with no `RECONNECTING`, `closed (send failed)` or hand-back line — consistent with the earlier
drops coming from two Ravilos on one Mac fighting over one speaker session.
Small finding: the remote for the speaker cast says *No phone or computer nearby can reach the speakers* under *Add a
speaker…* while the Mac itself is the sender holding that speaker — the line should not show on a sender that can.

### Amendment 2026-10-12 — the Swift half, the two known bugs, and acceptance as a checklist

**1. The Swift half compiles, and has already run against real devices.** The 2026-09-29 build note's *"the Swift
Bonjour has not been compiled"* is stale from 2026-09-30 onward, and nothing in R330 waits on a Mac compile:

- the Mac test build runs `:ravilo-desktop:buildMacNative` by name, which is `xcrun swiftc -emit-library -O
  -whole-module-optimization` over **all** of `native/*.swift` as one module (R330's `Network` framework among the
  frameworks listed in `ravilo-desktop/build.gradle.kts`). A compile error there fails the build — so a Mac build that
  exists is a compile that passed;
- `Bonjour.swift` was last changed in `cc6a4bec` (2026-10-05, IPv4 first). The Mac test build
  `v1.50-136-ge5b4ec68` (2026-10-09 evening) **contains** `cc6a4bec`, and that run read the browser's own snapshot:
  `cast: rejoin Gæsteværelse [Nest Wifi point] → joined` — the `[model]` in that line is the `md` TXT key only
  `ravilo_bonjour_snapshot` carries on a Mac.

So FR-R330-2's Swift half is compiled **and** exercised. The only native source not known-compiled is R329's
`Player.swift` at `73e9b9db` (2026-10-09 21:55, later than that build) — R329's to prove, not R330's.

**2. ⌘W during a speaker cast — covered.** `DesktopBackground.keepsRunningOnClose(localPlaying, castLinked)` is the
rule and `CloseWhileCastingTest` is the test (R328's fix of 2026-10-09; the Mac re-test of the same evening passed:
the window hides, the music plays on, a reopen brings it back). **One gap, not changed here:** the window's close
reads `MusicCast.linked`, which is music only — ⌘W while a **film** casts to a TV still quits Ravilo, and the viewer
loses the remote mid-film (the TV plays on, and FR-R330-5 rejoins it on the next start). Whether the window should
also stay for a film cast is R328 D7's policy, so it is the owner's call, not this phase's.

**3. A retried drop must never hang — one unbounded step found and bounded.** *Defect:* `openCastTransport` (JVM)
bounded the TCP connect with `timeoutMs` but then set `soTimeout = 0` **before** `startHandshake()`, so a device that
accepts TCP on 8009 and never answers the TLS handshake — an overloaded or half-asleep Chromecast — blocked that open
for as long as the device held the socket. Every other step of a rejoin is bounded (connect 5 s, each request 10 s,
`launchOrJoin`'s poll 10 × 2 s), so this was the one place where a try could stall: the start-up ladder
(FR-R330-5, three tries) and the drop ladder (`onClosed`, three tries) both wait for `open` to come back, and the link
sits at `RECONNECTING` meanwhile — the bar reads *reconnecting* for ever and no later try runs. A second cost:
`CastAvailability.ask` wraps its open in `withTimeoutOrNull(8 s)`, which cannot cancel a blocking handshake, so a
stalled device leaked a socket and an IO thread per ask.

*Fix:* the handshake is bounded by the same `timeoutMs` (`soTimeout` set for the handshake, then back to 0 so reads
after it stay unbounded, as the protocol needs); a failure closes the socket instead of dropping it; and an open whose
caller has already given up closes what it built rather than returning an orphan. Every rejoin ladder is now bounded:
a worst case of 3 × (5 s connect + 5 s handshake + 10 s join + 2 s gap) ≈ 66 s to reach `NONE`, and no step waits on a
device for ever. Test: `CastTransportTimeoutTest` in `:ravilo-castv2`'s `jvmTest` — a loopback `ServerSocket` that
accepts and answers nothing; the open fails inside its timeout rather than hanging, and the stalled device sees the
socket closed (its read reaches EOF). The test runs on Linux; no Mac and no device.

**4. R383 (*Ravilo on the Mac stays light*).** Nothing here contradicts it, and the leak above was of R383's own kind
(a thread and a socket per stalled ask), so closing it makes R383's measurement a little cleaner. R383 will also meet
R330's one timer — `CastDiscovery` polls `ravilo_bonjour_snapshot` once a second — which is already reference-counted
to the sheet being on screen and the start-up rejoin (FR-R330-2), not a standing wakeup.

**5. Acceptance 1–7, as the Mac claim's checklist.** The protocol work is complete against the fake device
(`:ravilo-castv2:jvmTest` 33 tests, `:ravilo-ui:desktopTest` 648, all green on 2026-10-12 at `b9ab513c`); what is
left needs real devices and is written out below, to be run in one Mac claim.

### Acceptance checklist for the Mac claim (written 2026-10-12, not attempted from here)

**Devices needed in the one claim:** the owner's MacBook (`jogols@10.10.11.211`), the **Stue** and **Gæsteværelse**
speakers and their group, the **Køkken Hub**, and **one TV** (Stue TV; Soveværelse TV only if its board tickbox says
so that day). The Pixel 9 is needed for step 8 alone.

**Before anything:** build and sign on the Mac as `reference-mac-test-build-and-driver` says
(`:ravilo-desktop:buildMacNative :ravilo-desktop:createDistributable`, then the temporary-keychain signing — unsigned
it is a different app to macOS and TCC's Local Network grant does not follow it). **Only one Ravilo may run**: kill
every `Ravilo.app/Contents/MacOS/Ravilo` first — two senders fighting over one speaker session is what produced the
5–50 s drops of 2026-10-09. Start it with `RAVILO_TESTDRIVER=/tmp/ravilo-td2`, send `cmd VOLUME_DOWN` ×11 at once (the
app's volume is not remembered), and take a `shot` before every click batch — rows move between builds, and a wrong
row in *Play on…* woke a TV once. Keep the Mac's log: `~/Library/Logs/Ravilo/` (the cast path writes `cast:` lines on
connect, load and close).

| # | Acceptance | What to do, and what passes |
|---|---|---|
| 1 | The sheet's rows (FR-R330-2, D2) | In music mode open *Play on…*: Stue · Gæsteværelse · their group · the hub · the TVs, and nothing else. Switch to a film and open it again: only the hub and the TVs, no speaker, and no line saying why (FR-R324-1). **Also record the TXT keys** of each of the five devices — still owed from the 2026-09-29 build note: the driver's `cast` command now prints the browser's raw snapshot lines, `id · fn · md · ca · rs · host · port` (2026-10-12). Of dev review 1's six keys, `st` is **not** carried: nothing in the app reads it, `rs` carries the running app's name the rules need, and adding it is a Swift change nobody can compile off a Mac — so five keys and this note are the record, unless the owner wants the sixth. Record too whether each *speaker* answered `GET_APP_AVAILABILITY` the way the displays did (open question 1). |
| 2 | An album moves to a speaker and back | Start an album on the Mac, then *Play on…* → Stue: it keeps its position; the Queue tab edits **the speaker's** queue (add and remove one song, and see the speaker follow); *Play on this Mac* brings it back where it was. |
| 3 | A busy device (FR-R330-4) | With Spotify playing on Gæsteværelse, its row reads *Busy · Spotify*, a tap asks *Stop Spotify and play here?* first, and taking over replaces it. (If no Spotify is to hand, any other sender's app does; record which.) |
| 4 | Volume and the media keys (D3) | The ⋯ slider moves **Stue's** own volume (check the speaker, not the Mac's, and leave it where it was). The keyboard's play/pause and next keys go to the speaker (FR-R330-7, Now Playing). The Mac's own volume keys stay the Mac's. |
| 5 | Quit and reopen while it plays (FR-R330-5) | With Stue playing, quit Ravilo, wait, start it again: the mini bar comes back on the speaker's current song, the log says `rejoin … → joined`, and **no hand-back line** appears (R353). Then do it once more with the speaker **unplugged at the moment of the start** — the three tries must end at *no connection* and the bar must go quiet, not sit at *reconnecting* (the 2026-10-12 bound, item 3). |
| 6 | A film to a TV (FR-R330-6) | Cast a film to Stue TV; R245's remote drives it (play, pause, seek, a subtitle and an audio pick), and the mini bar shows it. |
| 7 | Local Network refused (FR-R330-8) | In System Settings → Privacy & Security → Local Network, turn **Ravilo** off; open the sheet: one line *Ravilo needs Local Network access to find speakers and TVs* · *Open Settings*, and the button opens that pane. **Turn it back on afterwards.** |
| 8 | The speaker remote's two lines (2026-10-09 evening fix) | While the Mac holds a speaker cast, open the remote: under *Add a speaker…* it must read *Adding a speaker needs Ravilo open on an Android phone nearby* — **never** *No phone or computer nearby can reach the speakers*. With the Pixel 9's Ravilo open nearby, *Add a speaker…* is offered instead. |

Two more worth a line each if they appear: the 2026-09-30 open question — a song handed over at 2:40 that ended at
once on a TV, the server logging the stop at the song's full length (not known to be the Mac's) — and whether the link
holds for a quarter of an hour with one Ravilo and no `closed (send failed)`.

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

## Amendment 2026-10-25 — the bar's place comes from the receiver's own status (DEB-2252)

**Why.** After the receiver loads the next song itself (a natural song change, a direct file), CAF's media channel
goes quiet for that song: on DEB-2310 and DEB-2314 the Mac got 0–1 `MEDIA_STATUS` after the change and none later,
a `GET_STATUS` every 3 s (DEB-2294) and a second sender's `GET_STATUS` went unanswered, and the receiver's own notes
said `PLAYING` throughout, so DEB-2312's press under BUFFERING never fired. The bar sat at 0:00 with the equaliser for
the whole song, 3 of 3 changes on DEB-2314, until a press of play/pause. Our own channel kept working the whole time:
the song's title and length, and the receiver's notes, all arrived. FR-R330-7's Now Playing is therefore fed from our
channel too, and no longer depends on CAF answering.

**FR-R330-7a — the receiver says where a song is.** On music, the receiver's `status` (`CastReceiverMessage`) carries
two new optional fields: `position_ms` (the song's place when the status was built, in ms) and `playing` (true while
it plays, false while paused or buffering). It sends a `status` (a) on every one it sends today, (b) once at the
first `TIME_UPDATE` of each load whose time is at least 1 s past where the load started, and (c) every 15 s while a
song plays. A status sent for (b) or (c) carries no queue (FR-R356-8 is unchanged). Films leave both fields null.
This is additive: an older sender ignores them, and an older receiver sends neither.

**FR-R330-7b — the Mac uses whichever report is newer.** For the song our channel names, the desktop sender takes
the place and the play state from the newer of the receiver's last `status` with `position_ms` and CAF's last
`MEDIA_STATUS` for that song, measured by when each arrived. The receiver's place is moved on by the time since it
arrived while `playing` is true, as `CastMediaStatus.positionMs()` does for CAF's. The equaliser glyph (buffering)
shows only while the newer report says the song is not yet playing. With no `position_ms` from the receiver (an older
receiver), behaviour is as before. The Mac's media keys and the macOS Now Playing card read the same place.

**FR-R330-7c — the log can tell the two channels apart.** The sender's `cast: position` line also says which
report it showed (`from=caf|ravilo`). Every media-channel frame that is not a `MEDIA_STATUS` with a media session (an
empty `status`, `INVALID_REQUEST`, `LOAD_FAILED`, any other type) is noted once per song as `cast: media frame <type>
…`, so the next report says whether CAF answered at all.

**Unchanged.** The phone (it uses the Cast SDK and keeps time correctly, DEB-2253), the receiver's own screen, and
DEB-2294's re-ask and DEB-2312's heal, which stay as they are: harmless, and still logged.

**Acceptance.** The DEB-2314 recipe: a 1000-song queue with FLAC and MP3 from the Mac to a speaker, the Mac left
alone for ~10 min, at least 3 natural song changes. After each change the bar's place moves within ~4 s and stays
within ~2 s of the speaker's; a pause holds; the log shows `from=ravilo` on the stuck-shaped changes.

**Built as `5bf9c8d1` (DEB-2456). Calls the text above was silent on, accepted 2026-10-25:**

- *Starting or paused.* A receiver report with `playing=false` means the song is starting (the equaliser shows) only
  while its `position_ms` is under 1 s. Past that, it means paused: the play glyph shows and the place holds.
- *For that song.* A CAF status counts for the song our channel names only when its title matches. Without that, the
  old song's last status, arriving late, would win over the receiver's report for the new song.
- *What drops the receiver's report.* Any receiver message that is not a `status` with a place (`ended`, `failed`,
  `busy`, `noserver`, or a status without one) drops the held report; `tracks` does not. So do a load, a new device and
  a close.
- *A seek.* A seek sends no receiver status, so while CAF is silent the bar can trail a seek by up to 15 s. QA records
  it on DEB-2459 as data, not as a failure. If it shows, the fix is a status at the first `TIME_UPDATE` after a seek.

