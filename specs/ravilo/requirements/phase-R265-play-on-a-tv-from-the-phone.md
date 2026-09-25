# Phase R265 — Play on a TV from the phone: one glyph, three tiers

> Owner decisions 2026-09-18: on the phone app, everything "send this to the TV" lives behind **the
> same Cast glyph** — even though the TV may not be a Chromecast. It opens Ravilo's own sheet with
> **three tiers**: the user's TVs **on this network** first; a **collapsible list of all their TVs**
> beneath; and, one layer further down, **AirPlay**, kept but with a notice that the phone must stay on
> and in Ravilo, so it reads as supported-with-caveats rather than the recommended route. Starting a
> title, and everything after it — pause, seek, audio, subtitles, subtitle size, next episode, stop —
> goes through the backend (236) to a receiver-only TV app (R264); the phone can be closed and the
> picture continues. R245's remote, mini bar, subtitles sheet and seven states are reused as drawn; what
> changes is *where the device list comes from* and *what carries the commands*.

## Status

`✓ Built` — every FR built by 2026-09-25 (see the two *Built 2026-09-25* sections); on-device: the
Android/Chromecast half verified on the Pixel 9 + soveværelse TV, the iPhone/AirPlay half not (no
iPhone), the screens half pending a TV that runs R264. Written 2026-09-18 from the owner's decisions and the research report
`ravilo-web-pwa-player-cast-2026-09-18.md` (§5, §12). **Dev-reviewed 2026-09-18 against `main`
`05195d1f`** (see §Dev review at the bottom: pairing is 236's new `remote/pair`; self-hosting the player
libraries moved to 235; the glyph's presence is server-pushed so a first TV can be added; two senders
need a one-linked-at-a-time rule; R270 supersedes FR-R265-4's row).

**Built 2026-09-19** (client-side; the backend half — `/api/remote/**`, `RemoteDevice`,
`RaviloConfig.screens` — turned out to already exist from 236's own build): `TvApiClient` gained
`remoteDevices`/`remotePlay`/`remoteCommand`/`remotePair`/`connectRemoteEvents` (FR-R265-6); a new
commonMain `ScreenSender` implements `CastSender` over those routes, mapping `ScreenStatus` onto the
same `CastRemoteStatus` the shared remote UI already reads (dev review item 2's "field for field" holds
in effect via a mapping function, not a typealias — `ravilo-ui` doesn't depend on `shared`'s `ScreenTrack`
shape being identical to Cast's `CastTrack`); a new `ActiveCastSender` composes it with the platform's
Chromecast sender and applies dev review item 4's "at most one linked at a time" rule; `CastController`
gained `castOnScreen`/`joinScreen`/`pairScreen`/`screenDevices`, and its existing `cast()` now checks
which side is actually connected before ever minting a Chromecast hand-off code, so every existing
Detail/Player call site keeps working unchanged whether the connected device turns out to be a screen or
a Chromecast; a new `ScreensSheet` composable draws tiers 1/2 + Add-a-TV (code entry → `remotePair`) +
R270's AirPlay footnote row (visual only — see below); `RaviloApp.kt`'s `castActive` now reads
`RaviloConfig.screens?.enabled` alongside the existing `cast?.appId`; three languages of strings added.
Compiles clean across every target (backend, `shared` ×4, `ravilo-ui` wasmJs+Android, `ravilo-android`,
`ravilo-web`); existing `ravilo-ui` unit tests pass unchanged. **No hardware verification performed** — no
Tizen TV, no live Chromecast session, no device install — everything above is proven by compilation and
the pre-existing test suite only.

**Not done, tracked here rather than guessed:**
- **A true single unified glyph** (Chromecast rows living inside the same sheet as screens, FR-R265-3)
  needs enumerating Cast SDK routes outside the SDK's own picker dialog — untested Android `MediaRouter`
  surface, deliberately not risked in this pass. Tonight's honest middle ground: Chromecast keeps its
  exact existing glyph/dialog when configured; the new sheet is the entry point for screens specifically,
  shown instead of (never alongside) the Chromecast glyph — see `CastButton`'s own doc comment.
- **FR-R265-8 (AirPlay)** — the footnote row is drawn but wired to an always-`false` `airplayAvailable`
  parameter; the wasmJs seam (`webkitShowPlaybackTargetPicker`, capability probing, HLS subtitle
  delivery) is unbuilt, exactly as the spec's own text allows ("if the team prefers, this FR is its own
  small phase") — it also depends on the still-unconfirmed Jellyfin 10.11.11 HLS-subtitle-delivery probe.
- **Detail/Player don't yet pass a play context into the sheet** — `ScreensSheet` accepts a
  `ScreenPlayContext` (itemId + start position) precisely so a not-yet-connected screen row can start a
  title immediately, but only `CastButton`'s context-free path (join an existing session, or just look at
  what's on) is wired into the app tonight. Wiring `MovieDetailScreen`/`SeriesDetailScreen`'s "Play on TV"
  action through it is the natural next step.
- Tier 2's "remember which TVs list is expanded" (open question 3) is session-only, not persisted.
- Every acceptance-criteria line in this spec needs a real iPhone + a real screen or Chromecast, which
  wasn't available this session (R264 itself is also unverified on real hardware) — none of the criteria
  in the Acceptance section below have been checked against real devices.

Depends on **236** (built); needs **R264** on a TV to be tested (also not yet done); **R263** for the
installed-app experience; **R264**'s honest-capabilities work for AirPlay's native HLS is folded into
FR-R265-8. Sibling of **R245**, which it extends and partly supersedes (FR-R245-2's "the picker is the
platform's").

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — Ravilo taken through
**R264**, admin through **236**.

### Built 2026-09-25 — one glyph on Android, and the first device test

The owner asked for every ⚠ Partial phase to be finished, with the Pixel 9 and the soveværelse TV. Built
and verified on the Pixel 9 Pro (debug) against the soveværelse TV's built-in Chromecast:

- **FR-R265-1/-3 — one glyph, Ravilo's sheet, Chromecasts inside it.** `CastButton` always draws the Cast
  mark (`CastMarkGlyph`: idle · connected, waves pulsing while connecting) and always opens Ravilo's
  sheet; the SDK's `MediaRouteButton` and its dialog are gone (`PlatformCastButton` deleted). The SDK's
  Chromecasts are tier-2 rows with the Cast mark, read from the same `MediaRouter` the SDK's dialog read
  (`rememberCastRoutes`), and a row is selected the way that dialog selected it — `route.select()`, which
  `CastContext` turns into a session through the unchanged sender. A linked screen is unlinked first (dev
  review item 4). Seen on the device: *All your TVs (3)* → Køkken hub · Stue TV · Soveværelse TV →
  *Connecting to Soveværelse TV…* → the connected mark → Detail's button reads *Play on Soveværelse TV* →
  the remote → Home with the mini bar.
- **The sheet never opened before today.** It was drawn *inside* the glyph — a full-height sheet laid
  out in a 40 dp box in a 60 dp app bar — so a tap showed nothing, and its full-width box also pushed the
  glyph against the brand. The sheet is now drawn once at the app's root (`CastSheetHost`, above the
  bottom bar and the mini bar), and every glyph asks for it (`CastController.openSheet`).
- **The in-player hand-off works for a screen too** (FR-R265-6's "exactly as for a Chromecast"). It was
  gated on the Chromecast app id; `cast()` already posts to a linked screen first, so the gate is now just
  "something can be cast to". Seen on the device: the phone's player → glyph → sheet over the video →
  Soveværelse TV → the TV continues at the position and the phone swaps to the remote.
- **Open question 3 — yes:** the TV used last leads its tier, and tier 2 remembers whether it was left
  open (`ScreensSheetPrefs`, per device; its own storage, so a sign-out keeps it). Seen: reopened, the
  sheet came back expanded with Soveværelse TV first.
- **FR-R265-7 — reconnect is a list:** on app start and on every return to the screen, `GET
  /api/remote/devices` decides; a screen playing something *this* viewer started (`session_user_id` equal
  to the viewer's id, online, loaded, not ended) is joined and the mini bar shows it; otherwise nothing.
  Never someone else's session, never over a link that already stands. (Not seen on a device: screens
  are switched off on this server, so the path is compile- and code-verified only.)
- **FR-R270-3 held, two rows were wrong.** R265's row marked *any* loaded session busy, so a screen
  playing this viewer's own title read *Busy · {me} is watching* — and every busy row was tappable,
  though 236's 409 refuses the play. Busy is now someone else's session only, dimmed and not tappable.
- **A connected Chromecast read "Ready".** A Cast session selects a group route (`…-groupRoute`), not the
  TV's own, so the row is matched by the SDK's device name; it now shows the filled mark and *Playing
  {title}*. *Stop casting* is a row while anything is linked (FR-R245-10), in the design's `#ff9b8a`.
- **Discovery:** passive while the app has a Cast app id, active while the app is on screen — never off
  screen (R293). The platform norm for a Cast app, and what a resume needs (below).

**Found on the way, R245's re-connect (FR-R245-5), and only partly closed.** After the app process died
with the TV still playing, the phone never rejoined it. Three causes, each fixed: the SDK started with a
placeholder receiver id and learned the real one only after the config loaded, so its start-up resume
looked for the wrong app (the id the server gave is now stored and handed to the SDK at start-up);
re-applying the id on every config load ends the session even when it is the same id (now applied only
on a change); and `onSessionResumed` decided "finished" from a client that had no media status yet, and
ended a session whose TV was still playing (it now asks the receiver first). Plus: the connecting bar
no longer shows *Casting to* with a hole where the name should be. **Result:** one kill-and-relaunch
rejoined the TV with the mini bar live; two later ones did not — the SDK logs *resuming session* and
never reaches its route wait. The saved route is a group route, which may be why. Left open here.

**R305 was found by this test:** the relaunch after a process death killed the app outright (see R305).

### Built 2026-09-25 — the screens capability the phone never received, and AirPlay on the web

- **`RaviloConfig.screens` was never sent.** 236's dev review (item 9) asked for it and this phase's dev
  review (item 5) built the phone against it, but no server code ever set it — every phone read `null`,
  so tiers 1–2, *Add a TV* and FR-R265-7 were unreachable in production (the Pixel 9's sheet showed no
  *Add a TV*). `RaviloConfigService` now resolves it per viewer on every read, like `cast`:
  `enabled = true` (the routes are on every installation; there is no switch to be absent behind),
  `paired` = this viewer has a paired screen. `ScreensCapabilityConfigTest`.
- **FR-R265-8 — the web player sends the truth.** Video codecs are asked of the browser
  (`supportedVideoCodecs()`: h264, plus HEVC/VP9/AV1 only where the browser says yes; the web used to
  declare all four everywhere). `navigator.connection.type` feeds `link_kind` where a browser has it
  (never a rate — `downlink` is capped at 10 Mb/s and would trip phase 177's link cap). And **Safari —
  HLS natively and WebKit's AirPlay — declares `hls_only`** (it cannot play an MKV, which is most of the
  library) **and the new `hls_subtitles`**: text subtitles come as renditions *in* the HLS manifest,
  because an AirPlay hand-over takes the stream to the TV and the page's `<track>`s stay behind. Chrome
  on Android plays HLS natively too, but has no AirPlay and plays the files as they are, so it keeps its
  negotiation. `hls_subtitles` is opt-in and honoured only with `hls_only`: the Chromecast receiver is
  `hls_only` and keeps its sideloaded VTT (`DeviceProfileTest`).
  **The dev note's probe, done first:** Jellyfin 12.1.0 with `{"Format":"vtt","Method":"Hls"}` lists
  every embedded SubRip track of a 32-track film in `master.m3u8` as an `EXT-X-MEDIA TYPE=SUBTITLES`
  rendition (Danish among them), and fetching the manifest starts no encode.
  The ticket marks such a track `delivery_method = "hls"` with no URL; the web player gives each one a
  picker slot after its own `<track>`s (the order the `TextTrackList` uses) and re-applies the chosen
  one as Safari adds the manifest's tracks — which arrive after the pick, and one may be flagged
  `DEFAULT` (a Croatian sidecar was, in the probe).
- **FR-R265-4 / R270 FR-R270-1 — the AirPlay row.** A common `AirPlay` seam (null on Android), fed by the
  web player's `<video>` (`x-webkit-airplay="allow"`, `webkitplaybacktargetavailabilitychanged`,
  `webkitcurrentplaybacktargetiswirelesschanged`): the sheet's footnote row appears only where WebKit
  reported a target and opens Apple's picker (`webkitShowPlaybackTargetPicker`) inside the tap; the glyph
  takes its connected form while the picture is on the TV, and exists at all when AirPlay is the only
  way to a TV (FR-R265-1). No mini bar and no remote: the phone's own player is the remote.
- **WebKit never names the TV.** It reports that the target is wireless, not which one, so the glyph's
  "naming the target" and R270's *"Playing on {TV} · keep Ravilo open"* cannot be drawn as written — see
  R270 FR-R270-2's build note for what the bar says instead.
- **Detail's play context:** a screen tapped in the sheet is linked, and Detail's button then reads *Play
  on {TV}* — the same two steps as a Chromecast (FR-R265-6's "exactly as for a Chromecast"); inside the
  player the tap is the hand-off. `ScreenPlayContext` stays available for a one-tap start.

**Verified:** `tests/e2e/ravilo-web-airplay-probe.spec.ts` against a local build of the web app —
Chromium's video list agrees with the browser's own answers and is not an AirPlay browser; with WebKit's
AirPlay event and native HLS simulated it declares HLS-only; native HLS alone (Chrome on Android) keeps
its negotiation. The R302 audio probe still passes. **Not verified, and it cannot be here:** anything on
an iPhone — the row appearing, Apple's picker, the picture on an AirPlay TV with Danish subtitles, the
bar. That needs an iPhone and an AirPlay-2 TV.

## Current state (traced against `main`, 2026-09-18)

- `seams/CastSender.kt` (commonMain) is the seam: `link`, `deviceName`, `status: CastRemoteStatus`,
  `setAppId`, `load`, `play/pause/seekTo/stop`, `selectSubtitle/selectAudio`, `send(json)`.
  `CastSenderAndroid.kt` implements it over Google Play services; `CastSenderWasm.kt` returns `null` (no
  sender ⇒ no button — the *absent, never greyed* rule). `PlatformCastButton` is the SDK's
  `MediaRouteButton` on Android and empty on web.
- The mini bar, the remote (FR-R245-6/7), the subtitles sheet (FR-R245-8), the seven states (FR-R245-9),
  reconnect (FR-R245-5) and *Play on {device}* (FR-R245-4) are common code reading `CastSender.status`.
- The web player (`RaviloPlayerWasm.kt`) is a `<video>` element with native HLS on Safari; it already
  sends the TV's capability list (report §1.3), which is what R264's sibling capabilities work fixes — the
  AirPlay tier needs a native HLS source to hand to the TV.

## Requirements

**FR-R265-1 · One glyph, Ravilo's sheet.** The Cast mark (FR-R245-1's two forms) stays on Home, Detail and
Player. It is **present when the user has at least one paired screen (236) or AirPlay is available here**,
absent otherwise — never greyed. Tapping it opens **Ravilo's own bottom sheet** (46 px targets, R234's
floors) instead of a platform dialog. On Android the native Cast SDK path (R245) remains available for
*Chromecast* devices and appears as rows in the same sheet (FR-R265-3), so there is one entry point on
every platform. The owner accepts that the glyph is a little misleading for a Tizen TV; the sheet's
first line makes it plain: *"Play on a TV"*.

**FR-R265-2 · Tier 1 — on this network.** `GET /api/remote/devices` filtered to `nearby=true`: name, platform mark,
and one line of state — *Ready* · *Playing {title}* (yours, tappable to the remote) · *Busy · {user} is
watching* (236's 409 shape, not tappable) · *Offline · last seen {when}*. Empty tier ⇒ the section is
absent, not an empty box.

**FR-R265-3 · Tier 2 — all your TVs, collapsed.** A collapsible row *"All your TVs ({n})"*, closed by
default, remembering its open state per device. Same rows as tier 1 for the devices with `nearby=false`. On Android, Google's
Cast-discovered Chromecasts appear here too (with the Cast mark), so the sheet is the *only* picker on
every platform; on web/iOS there are none to show.

**FR-R265-4 · Tier 3 — AirPlay, with the notice.** Shown only where the web player's `<video>` reported
AirPlay availability (`webkitplaybacktargetavailabilitychanged` → `available`, or the standard
`HTMLMediaElement.remote` where WebKit exposes it). One row *"AirPlay"* with a **persistent second line**:
*"Your phone has to stay on and in Ravilo — the TV stops when you close the app."* Tapping it opens
Apple's picker (`webkitShowPlaybackTargetPicker()` / `remote.prompt()`); the picture leaves the phone,
**the phone's player stays the remote** (its chrome, picker, Skip Intro, next-up all keep working — they
drive the `<video>` that AirPlay mirrors), progress keeps being reported by the phone, and the cast glyph
shows the connected form with the TV's name from `webkitCurrentPlaybackTargetIsWireless`. No mini bar and
no R245 remote for AirPlay: the local player *is* the remote. Never on Android, never on the TV.

**FR-R265-5 · Pair a TV from the sheet.** A last row *"Add a TV"* opens a 6-character code entry
(R175's keyboard on TV builds is irrelevant here — native input on phone/web) with one line *"The code
is on the TV's screen"*. Success adds the TV to the list in place. Errors: *"That code didn't work"*
(unknown/expired/used) — no protocol words (FR-R245-16).

**FR-R265-6 · Play, and everything after, over the backend — the same API an API key uses.** A new
`CastSender` implementation in commonMain (`ScreenSender`) backed by 236's **`/api/remote/**`** routes
under the phone's ordinary device token (owner decision: one device-control API for API users and Ravilo
clients alike): `load` → `POST /api/remote/play`; `play/pause/seekTo/stop/selectAudio/selectSubtitle/
setSubtitleSize/next/cancelNextUp` → `POST /api/remote/command`; `status` ← `device_status` pushes
(`subscribe_device` on the phone's own events socket on open, `unsubscribe_device` on leaving the app,
re-subscribe on reconnect). The TV list is `GET /api/remote/devices` with its `nearby` flag. The remote, mini bar, subtitles sheet
and states are **unchanged** — they already read `CastRemoteStatus`, which 236's `ScreenStatus` mirrors
field for field. Positions tick only from pushes (never a local clock). *Play on {TV}* on Detail and the
in-player hand-over (FR-R245-4) work exactly as for a Chromecast.

**FR-R265-7 · Reconnect is a list, not a session.** On app start / return, `GET /api/remote/devices` decides:
a screen with `now_playing` for this user ⇒ mini bar with the live position; none ⇒ nothing shown at all
(FR-R245-5's two outcomes, without an SDK). The connecting bar (FR-R245-3) reads *"Sending to {TV}…"* /
*"Playing on {TV}"* and retires itself.

**FR-R265-8 · The web player sends the truth, so AirPlay has something to hand over.** On the web the
capability list is probed, not copied: `hls_only=true` when the browser plays HLS natively (Safari), else
`containers`/`video_codecs`/`audio_codecs` from `canPlayType`/`MediaCapabilities` (report §4.3);
`link_kind` from `navigator.connection` where present. On Safari the source is therefore native HLS —
AirPlay hands that URL to the TV. Text subtitles for an AirPlay session are requested with Jellyfin's
**HLS subtitle delivery** (in-manifest WebVTT) so the TV renders them; sideloaded `<track>` cues do not
travel. hls.js and JASSUB are self-hosted in the bundle (the CSP of 235 blocks the CDN). *(If the team
prefers, this FR is its own small phase; it is here because AirPlay is unusable without it.)*

**FR-R265-9 · Strings ×3 (en/da/fo).** `screens.title` *Play on a TV* · `screens.nearby` *On this
network* · `screens.all` *All your TVs* · `screens.ready` · `screens.busy` · `screens.offline` ·
`screens.add` · `screens.code_hint` · `screens.code_failed` · `screens.airplay_notice` ·
`screens.sending` · `screens.playing_on`. No product, protocol or status code in any of them.

**FR-R265-10 · Design.** The three-tier sheet (phone, iPhone frame and Pixel frame, in
`design/ravilo/Ravilo Mobile.html`), its empty/busy/offline rows, the code entry, and the AirPlay row
with its notice; the connected glyph naming a Tizen TV. The remote and mini bar are R245's drawings.

## Acceptance

- **iPhone (Safari, installed per R263) + UE55RU7440 running R264:** the glyph is present; the sheet shows
  the TV under *On this network*; *Play on Stue TV* starts the episode on the TV; pause/seek/subtitles
  from the phone; **swipe the app away, TV keeps playing; reopen, mini bar shows the live position**;
  the TV remote's pause shows on the phone within a second.
- **Same iPhone on mobile data:** the TV is under *All your TVs*; everything else identical.
- **iPhone + AirPlay-2 TV:** the AirPlay row with its notice; picture on the TV with Danish subtitles;
  the phone's player is the remote; closing the app stops the TV (the notice was true).
- **Pixel 9, Chrome (PWA):** same as the iPhone minus the AirPlay row. **Pixel 9, Android app:** the sheet
  additionally lists the stue Chromecast under tier 2 via the SDK path; both work.
- **Two users, one TV:** each pairs with the code; B's play while A is watching is refused with A's name.
- `scripts/check-phases.sh`, `check-mobile-css.sh` green; UI tests for the sheet's four row states.

## Non-goals

- No LAN discovery on the phone, no location permission, no Bonjour. "On this network" is the server's
  judgement (236 FR-236-6) and may be wrong on CGNAT or VPN — the TV is still one tap away in tier 2.
- No AirPlay mini bar or remote (the local player is the remote), no AirPlay on Android, no AirPlay
  audio-only mode.
- No TV launch/wake from the phone. No change to the Android TV client.
- No player-chrome rebuild on web (R266, the transparent-canvas question) — the AirPlay tier works with
  today's DOM chrome because the hand-over is the browser's.

## Open questions

1. **Does a standalone iOS web app keep an AirPlay session alive when backgrounded (app switcher, not
   closed)?** WebKit keeps a playing media element alive; verify on the device — the notice's wording
   ("stay on and in Ravilo") is written for the worst case and can be softened if the test passes.
2. **Google's brand rules for the Cast icon** cover Cast-enabled apps; using the mark as the entry to a
   non-Cast sheet is the owner's call and may need a neutral glyph if Play Store review objects (Android
   app only; the web is unaffected).
3. **Should tier 2 remember "last used TV" and pin it to the top?** Lean: yes, cheap, per device.

## Dev notes

- `ScreenSender` is commonMain, so the Android *app* can use it too (a Tizen TV from an Android phone
  with no Cast SDK involved) — `rememberCastSender()` composes both senders into one status.
- The AirPlay seam is wasmJs-only: `airplayAvailable: StateFlow<Boolean>`, `showAirplayPicker()`,
  `airplayTarget: StateFlow<String?>`, wired to the `<video>` in `RaviloPlayerWasm.kt` with the
  `x-webkit-airplay="allow"` attribute set at creation.
- Jellyfin's `SubtitleDeliveryMethod.Hls` must be confirmed on 10.11.11 (163's and 187's lesson: probe
  before building) — the `SubtitleProfiles` in `deviceProfile()` gain `{"Format":"vtt","Method":"Hls"}`
  for `hls_only` clients only if the probe says the master playlist carries the rendition.

## Dev review (2026-09-18, against `main` `05195d1f`)

The seam is as described: `seams/CastSender.kt` (commonMain) exposes `link`, `deviceName`,
`status: StateFlow<CastRemoteStatus?>`, `setAppId`, `load(CastLoadData)` and the transport calls; the
mini bar, remote, sheet and states read only `status`. A commonMain `ScreenSender` is the right shape.

1. **FR-R265-5 pairs through a route that does not exist yet.** Entering the TV's code is `POST
   /api/remote/pair {code}` from 236's dev review (item 1) — the phone *claims* a code the TV minted; it
   is not 218's redeem, whose caller is the receiver. Device token only. One error sentence, as written.
2. **`CastRemoteStatus` is not field-for-field `ScreenStatus` today.** It lives in `ravilo-ui`, not
   `shared`, and has `busySinceMs`, `receiverId` and `subSize: Char`. 236's dev review (item 6) moves it
   to `shared` and makes `CastRemoteStatus` a typealias, so FR-R265-6's "unchanged" holds for the UI —
   but it is a rename that touches the Android sender and `ravilo-cast` in the same commit.
3. **FR-R265-8 is split.** Self-hosting hls.js and JASSUB moved to **235 FR-235-9** (235's CSP would
   otherwise break production web playback the day it ships, long before this phase). What stays here is
   the capability probing (`hls_only` on Safari, `canPlayType` / `MediaCapabilities` elsewhere) and the
   HLS subtitle delivery for AirPlay — still gated on the Jellyfin 10.11.11 probe the dev note asks for.
   The status block's "R264's honest-capabilities work" is the research report's numbering; there is no
   such phase, the work is this FR.
4. **Composing two senders needs a rule, not just a function.** `rememberCastSender()` returning "both
   senders as one status" must say which wins: at most one is *linked* at a time; starting a play on a
   screen while a Chromecast session is live stops the Cast session first (and the reverse). The mini bar
   reads the linked one. Without the rule, a phone that cast to the Chromecast yesterday and picks the
   Samsung today shows two remotes' worth of state.
5. **FR-R265-1's presence rule costs a request on every Home.** "Present when the user has at least one
   paired screen" needs `GET /api/remote/devices` before the glyph can be drawn — and with no screens and
   no AirPlay there is then **no entry to *Add a TV*** at all, since that row lives inside the sheet. Make
   it server-pushed like the Chromecast capability (`RaviloConfig.cast`): a `screens: { enabled, paired }`
   block on the config the client already holds; the glyph is present when `enabled`, so a household's
   first TV can be added from the sheet. *(Absent-never-greyed still holds: `enabled` is the admin's
   switch.)* This is a small addition to 236's payload.
6. **Design is ahead of this file.** The three-tier sheet, its four row states, *Add a TV*, the
   connecting bar and AirPlay-active are already built into `design/ravilo/Ravilo Mobile.html`
   (`sc-*` layer) — FR-R265-10 is delivered. The design-authored **R270** supersedes **FR-R265-4's row
   shape** (AirPlay is a footnote link, with the full sentence on the connecting bar) and confirms
   FR-R265-2's busy/offline wording; build from R270 where they differ.
7. **Open question 2 is real for the Play build.** Google's Cast icon guidelines tie the mark to Cast
   functionality. On Android the sheet *does* list Chromecasts, which is a defensible reading; on the web
   there is no reviewer. Keep the glyph; keep a neutral fallback drawable ready rather than designing for
   a rejection that may not come. Open question 3: yes.

**Build order:** last of the five — after 235 (self-hosted players), 236 (routes, pairing, status) and
with R264 on a real TV to test against.
