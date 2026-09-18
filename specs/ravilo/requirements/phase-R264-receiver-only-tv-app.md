# Phase R264 — A receiver-only TV app: one more media player, driven from the phone

> Owner decision 2026-09-18: for Samsung Tizen, a **receiver-only** app — no navigation on the TV,
> "basically just one more media player with all functionality, but fully controllable remotely as
> well". The viewer opens it with the TV remote; from then on the phone (R265) picks the title, and
> pause, seek, audio, subtitles, subtitle size, next episode and stop all arrive from the backend
> (236). The TV is its own Ravilo device: the phone can be closed or die and the picture continues.
> The repo already contains this app twice over — the Chromecast receiver (`ravilo-cast`) is exactly a
> phone-driven, no-navigation Ravilo device, and `ravilo-tizen` has the Kotlin/JS + AVPlay player layer
> old Samsung sets need. This phase joins them.

## Status

`Planned` — written 2026-09-18 from the owner's decisions, the research report
`ravilo-web-pwa-player-cast-2026-09-18.md` (§12) and a read of `ravilo-cast/…/Receiver.kt` (419 lines,
11 CAF touchpoints) and `ravilo-tizen/` (`config.xml` `required_version="2.4"`, AVPlay in
`PlayerScreen.kt`/`TizenPlatform.kt`/`LiveTvPlayerScreen.kt`). **Dev-reviewed 2026-09-18 against `main`
`05195d1f`** (see §Dev review at the bottom: 218's code is minted by a phone, so this app pairs through
236's new `screen/code` + `screen/claim`; it stores one device token per paired user; the core
extraction is its own no-behaviour-change commit; R269 amends FR-R264-2/6). Not built. Client:
a new `ravilo-screen` module (Kotlin/JS, DOM, no WebAssembly) packaged for **Tizen first**; webOS is the
same bundle in an `.ipk` and is listed as a follow-on package, not a second app. Depends on **236**.
Sibling **R265**. **Supersedes R189** (the full Samsung Tizen client): owner decision 2026-09-18 — "we
already have a Tizen app which has never been tested; these specs scratch that and this receiver app
takes over instead." `ravilo-tizen` (2 405 lines, M1+M2 build-verified, never run on a TV) is **removed**
by this phase (FR-R264-10); its AVPlay wrapper is the one thing carried over.

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — Ravilo taken through
**R263**, admin through **235**. Admin pair: **236**.

## The target device, concretely

The first user's TV is a **Samsung UE55RU7440 (2019, RU7400, Tizen 5.0)**: Chromium far below WasmGC, so
the Compose/wasm build cannot run there (R189's finding stands) — Kotlin/JS DOM it is. Its `<video>`
has no usable HLS; `webapis.avplay` does. It is **not in Samsung's store-eligible list for a Public
Seller outside the US** (report §12.5), so the install is a sideload for now: Developer Mode on the TV +
`sdb`/Tizen Studio or the Apps2Samsung installer on the same LAN. The app must therefore be **tiny, never
need updating for the server to change behaviour** (the backend pushes; the app renders), and survive
years without a store update.

## Requirements

**FR-R264-1 · It is a Ravilo device, not a screen mirror.** The app enrols with 218's hand-off code
(`/api/tv/cast/redeem` with `platform=tizen-screen`), stores its device token on the TV, heartbeats,
opens `/api/tv/events` on start and keeps it open, starts playback with `/api/tv/playback/start`
(negotiated exactly like the Chromecast receiver: `hls_only=true`, `containers` empty, real
`canDisplayType`/AVPlay probes for HEVC/HDR where the set answers honestly), reports progress every 10 s,
posts `ScreenStatus` (236 FR-236-5) on change and at least every 5 s, and reports QoE and start samples
like any TV. The Jellyfin identity it plays under is whatever `play_item` named (236 FR-236-4); the app
never holds a user credential beyond the sessions the backend keyed to its device.

**FR-R264-2 · Two screens, no navigation.** *Idle*: the lit mark, the TV's name, and — always, in a
corner — its **pairing code** with one line *"Enter this code in Ravilo on your phone"* (en/da/fo,
following the TV's UI language then the server's default). The code is 218's: minted server-side,
6 characters, shown as long as it is valid and re-minted when it expires; a paired phone never needs it
again. *Playing*: the picture, and the player chrome. There is no browse, no search, no settings, no
profile — the D-pad on the TV remote does only what a TV remote does on a player (FR-R264-4).

**FR-R264-3 · A whole player, not a stub.** Everything R245's receiver does (FR-R245-13…17) plus what a
TV player has: transport (play/pause, −10 s / +30 s, scrub with times), the R180/R195 **two-level
audio & subtitles picker** rendered on the TV when the TV remote asks and mirrored to the phone's sheet
when the phone asks, subtitle size S/M/L, **Skip Intro / Skip Credits** from 150/R182 segments, next-up
countdown and auto-advance (R184's position rule), R218's three waiting moments, R237's per-cause failure
copy, R220's black-picture recovery where AVPlay allows, phase 182's *Server busy* wait with elapsed
time, and R222's slow-to-start note is **not** shown (the phone shows it before pressing play).
Subtitles: VTT sideloads as text tracks drawn by the app (AVPlay's own subtitle rendering is used only
where a set cannot layer HTML over video — verify on the RU7440), PGS burned in by the server as today,
the R110 caption look.

**FR-R264-4 · Both remotes work, the phone wins ties.** The TV remote's OK/play/pause/seek keys act on
the player (a TV in the living room must never need a phone to pause). Every action, from either
remote, results in a status report, so the phone's remote reflects a TV-remote pause within one push.
`Back` on the TV remote while playing = stop and return to idle (R180 teardown); on idle it exits the app.

**FR-R264-5 · Commands come from the backend and only the backend.** `play_item`, `playstate_command`
and the new `player_command` (236 FR-236-3) are the entire input surface besides the TV remote. No LAN
listener, no local HTTP, no Cast namespace, no QR camera. A `play_item` while something plays replaces
it (the phone already confirmed; 236's 409 protects other users).

**FR-R264-6 · Independent of the phone, honest about the server.** The events socket dropping does
not stop playback; the app reconnects with backoff and re-posts status on reconnect. A backend
unreachable for > 90 s during playback shows R245's *no server* screen after the current buffer, and
Continue Watching is whatever the last accepted progress said. Idle with no server: the code screen
says *"Can't reach the server"* and keeps trying.

**FR-R264-7 · Light enough for a 2013 stick and a 2019 Samsung.** No artwork on idle beyond the mark;
no fonts beyond the bundled one; no animations that are not opacity; the status poster/backdrop only
while playing (FR-R245-17's rule). Bundle target < 1 MB gzip. Cold start to the code screen < 3 s on the
RU7440.

**FR-R264-8 · Packaging.** `ravilo-screen/` module: Kotlin/JS (IR) DOM, sharing `shared` DTOs and the
receiver's state machine with `ravilo-cast` (extract the CAF-free core into `ravilo-cast`'s commonJs or
a `ravilo-receiver-core` set — one player state machine, two launch shells: CAF, and the events socket).
Tizen: `config.xml` with `tv` profile, `required_version="2.4"`, privileges `internet`, `tv.inputdevice`,
`avplay`; a signed `.wgt` published as a GitHub release asset per version (the sideload artefact), with
the exact `sdb`/Tizen Studio steps and the Apps2Samsung path in `README`. webOS: an `.ipk` from the same
bundle with `appinfo.json`, submitted to the LG Content Store as a follow-on once the Tizen one is on a
real TV. No Play Store, no App Store.

**FR-R264-10 · `ravilo-tizen` is removed, not kept beside this.** The module directory, its
`include(":ravilo-tizen")` in `settings.gradle.kts`, the `COPY ravilo-tizen` lines in **both** Dockerfiles
(the graph refuses to configure without an included module's directory — the R245 lesson), the
`README.md` lines describing it, and the "same shape as :ravilo-tizen" comment in `ravilo-cast`'s build
file all go in the same commit that adds `ravilo-screen`. The AVPlay wrapper (`TizenPlatform.kt`'s
media calls, the R189 findings on `required_version`, privileges and the `.wgt` signing) is moved into
`ravilo-screen`, not copied. R189's spec file and its `STATUS.md` row are **kept**, marked *Removed →
R264* (the 162 → 217 precedent: the record of a built phase is worth more than a tidy directory). R190's
"deliberate Tizen omission" note becomes moot and is left as history. CI drops the `ravilo-tizen`
compile step and gains the `ravilo-screen` bundle + `.wgt` packaging.

**FR-R264-9 · Strings.** The idle screen's two lines and the *no server* variant × en/da/fo; everything
else reuses R245's `cast.*`/`srv.*` keys and the player's existing keys.

## Acceptance

- Sideloaded on the **UE55RU7440**: opens to the code screen in < 3 s; a phone pairs with the code; a
  play from the phone starts a 1080p HEVC/E-AC-3 episode as HLS with Danish subtitles rendered in the R110
  look; the phone pauses, seeks, switches audio, changes subtitle size; the TV remote pauses and the
  phone's remote shows paused within a second; **the phone app is swiped away and the episode keeps
  playing**; the next episode auto-advances; Skip Intro appears and works from both remotes; the phone is
  reopened and the mini bar shows the live position.
- Backend unplugged for two minutes mid-episode: playback continues to the end of the buffer; the *no
  server* screen; plugging it back in resumes reporting.
- Same on the stue TV's Chromecast receiver path (`ravilo-cast`), byte-identical player behaviour: the
  shared core is the acceptance of the refactor.
- `scripts/check-phases.sh` green; the `.wgt` builds in CI from the release tag.

## Non-goals

- No navigation, browse, search, Discover, Live TV guide, settings or profile on the TV. (Live TV *playback*
  via `play_item kind=livetv` is allowed if the backend sends it; the guide is not.)
- No launch/wake from the phone (236's non-goal). No store submission in this phase (LG follows; Samsung
  needs a Partner contract).
- No Compose, no WebAssembly, no Cast SDK in this module.

## Open questions

1. **HTML over AVPlay on Tizen 5.0** — can the app layer its chrome and subtitle text over the video
   surface, or must it use AVPlay's own subtitle/OSD calls? R189 built a player on this layer; its
   findings answer most of this — verify on the RU7440 before the chrome is drawn.
2. **Should idle time out** into a screensaver/black after N minutes so a TV left on the code screen
   does not burn a static mark? Lean: dim to 20 % after 10 minutes, wake on any key or `play_item`.
3. **The pairing code's exposure**: it is visible to anyone in the room, by design (218). Fine for a
   household; revisit if a guest-mode TV is ever wanted.

## Dev notes

- `ravilo-cast/…/Receiver.kt`'s CAF touchpoints are: context/options, the `LOAD` interceptor, the
  custom-namespace message listener, the sender-connected/disconnected events, and `stop`. Everything
  else (ticket, HLS attach, tracks, status object, next-up, segments, the ten screens) is the core to
  share.
- `ravilo-tizen`'s AVPlay wrapper (`TizenPlatform.kt`) becomes the media backend for the Tizen shell of
  `ravilo-screen` (moved, then the old module deleted — FR-R264-10); the webOS shell uses the plain
  `<video>` + hls.js path the Chromecast receiver already uses.
- R252: send `platform=tizen-screen` and the app version so the Jellyfin dashboard names it *Ravilo on
  Samsung TV*.

## Dev review (2026-09-18, against `main` `05195d1f`)

The inventory is right: `ravilo-cast/…/Receiver.kt` is 419 lines of Kotlin/JS (IR) with its CAF surface
confined to a handful of calls; `ravilo-tizen` is 2 405 lines across 22 files with the AVPlay layer in
`TizenPlatform.kt`, `config.xml` at the module root; `PlaybackStartRequest.hlsOnly` exists
(`shared/…/tv/Models.kt:67`); both Dockerfiles `COPY ravilo-tizen` (`Dockerfile:33`,
`ravilo-web/Dockerfile:36`) and `settings.gradle.kts:8` includes it. What does not hold is how the app
gets its identity.

1. **FR-R264-1 and FR-R264-2 rest on a code that runs the other way.** `/api/tv/cast/redeem` redeems a
   code that a **signed-in phone** minted (`/api/tv/cast/handoff`) and that reached the Chromecast inside
   the Cast launch. A Tizen TV that *shows* a code has no phone behind it and nothing to redeem. **236's
   dev review (item 1) replaces the flow;** for this app it means:
   - first run: generate and store `device_id` (`screen-…`), call `POST /api/tv/screen/code`, show the
     code, keep the returned `claim_secret` in memory only, poll `POST /api/tv/screen/claim` until it
     returns a `PairResult`; re-mint on expiry;
   - **the app stores one device token per paired user** (`{user_id → token}` in `localStorage`), because
     a device token *is* a `(device, user)` pair (236 dev review, item 2). `play_item.session_user_id`
     picks the token for `/playback/start` and everything after it. It opens **one** events socket with
     any token it holds (236 FR-236-4a) and re-opens with another if that user is un-paired;
   - while paired and idle the code stays on screen (a second household member pairs the same way) — the
     mint-and-poll loop simply keeps running on idle.
2. **Two design-authored drafts already amend this spec** and should be read with it: **R269** (the app
   needs a server address before it can ask for a code — it is a local `.wgt` with no origin; supersedes
   FR-R264-2's "always shows its code" and FR-R264-6's single no-server sentence) and the owner's decision
   recorded there that **idle is never dimmed — open question 2 is closed, answer: no.** R269 FR-R269-4
   inherits item 1 above: "asks the backend to mint a code" is `screen/code`, not 218's mint.
   `design/ravilo/Ravilo Receiver App.html` is the maintained mockup (18 states); `Ravilo Receiver.html`
   remains the Chromecast's screen.
3. **The shared core is the risk, and the acceptance already says how to fence it.** Extracting the
   CAF-free state machine out of `Receiver.kt` changes a shipped, only-just-working receiver (first real
   cast: 2026-09-18). Do the extraction as its **own commit with no behaviour change**, verified on the
   Chromecast path, *before* `ravilo-screen` exists. `ravilo-cast` is a single-target `js(IR)` module, so
   the core is a second Kotlin/JS module both depend on (`ravilo-receiver-core`), not a source set.
4. **FR-R264-10 has one more site and one inconsistency.** `README.md:124` lists `ravilo-tizen`; the
   main `Dockerfile:24` comment names it too. There is **no** CI step that compiles `ravilo-tizen` to
   drop. And `STATUS.md` already reads *R189 · Removed → R264* while the module is still in the tree and
   in the build graph — the row was flipped ahead of the code. Left as is (it is true of intent), but
   whoever builds this phase should not take the row as evidence the deletion happened.
5. **The `.wgt` signing is a release-process item, not a code one.** A Samsung author + distributor
   certificate is needed to sign for a TV in Developer Mode, and the distributor certificate is bound to
   the TV's DUID. CI can build an **unsigned** `.wgt`; the signed artefact for the first user's RU7440 is
   produced locally until a Partner account exists. FR-R264-8's "signed `.wgt` published as a release
   asset" is therefore: unsigned asset in CI, signing steps in the README.
6. **Open question 1 decides who draws the picker, and no code can answer it.** Tizen 5.0's AVPlay
   renders on a plane *behind* the web layer; a transparent hole in the DOM is the documented pattern and
   R189's player was written to it — but R189 never ran on a TV. **First task of the build is a
   fifty-line probe on the RU7440** (AVPlay + one absolutely-positioned `<div>` + one text cue), before
   any chrome is written.

**Build order:** 236 (with its new pairing flow) → the core extraction → this app → R265.
