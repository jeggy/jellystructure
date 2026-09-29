# Phase R330 — Ravilo on the Mac casts: music to the household's speakers, films to the TVs

> Owner, 2026-09-29: *"I would like to be able to stream music from this app to the wifi speakers etc as well."*

## Status

`Planned` — written 2026-09-29 (dev-authored) from `research-reports/ravilo-macos-desktop-app-2026-09-29.md` §4. Not
dev-reviewed. Number verified free. **Third of four** (R328 → R329 → **R330** → R331).

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
