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

`Planned` — written 2026-09-18 from the owner's decisions and the research report
`ravilo-web-pwa-player-cast-2026-09-18.md` (§5, §12). Not dev-reviewed, not built. Client
(`ravilo-ui` commonMain: the sheet, the tiers, a backend-transport `CastSender`; wasmJs actuals: the
AirPlay seam; Android: nothing new), plus **design work** for the three-tier sheet and the AirPlay
notice. Depends on **236**; needs **R264** on a TV to be tested; **R263** for the installed-app
experience; **R264**'s honest-capabilities work for AirPlay's native HLS is folded into FR-R265-8.
Sibling of **R245**, which it extends and partly supersedes (FR-R245-2's "the picker is the platform's").

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — Ravilo taken through
**R264**, admin through **236**.

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

**FR-R265-2 · Tier 1 — on this network.** `GET /api/tv/screens`'s `nearby` list: name, platform mark,
and one line of state — *Ready* · *Playing {title}* (yours, tappable to the remote) · *Busy · {user} is
watching* (236's 409 shape, not tappable) · *Offline · last seen {when}*. Empty tier ⇒ the section is
absent, not an empty box.

**FR-R265-3 · Tier 2 — all your TVs, collapsed.** A collapsible row *"All your TVs ({n})"*, closed by
default, remembering its open state per device. Same rows as tier 1 for `others`. On Android, Google's
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

**FR-R265-6 · Play, and everything after, over the backend.** A new `CastSender` implementation in
commonMain (`ScreenSender`) backed by 236's routes and the phone's events socket: `load` → `POST
/screens/{id}/play`; `play/pause/seekTo/stop/selectAudio/selectSubtitle/setSubtitleSize/next/cancelNextUp`
→ `POST /screens/{id}/command`; `status` ← `screen_status` pushes (`subscribe_screen` on open,
`unsubscribe_screen` on leaving the app, re-subscribe on reconnect). The remote, mini bar, subtitles sheet
and states are **unchanged** — they already read `CastRemoteStatus`, which 236's `ScreenStatus` mirrors
field for field. Positions tick only from pushes (never a local clock). *Play on {TV}* on Detail and the
in-player hand-over (FR-R245-4) work exactly as for a Chromecast.

**FR-R265-7 · Reconnect is a list, not a session.** On app start / return, `GET /api/tv/screens` decides:
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
