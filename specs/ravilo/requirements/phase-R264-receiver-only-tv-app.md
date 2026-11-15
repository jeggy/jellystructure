# Phase R264 — A receiver-only TV app: one more media player, driven from the phone

> Owner decision 2026-09-18: for Samsung Tizen, a **receiver-only** app — no navigation on the TV,
> "basically just one more media player with all functionality, but fully controllable remotely as
> well". The viewer opens it with the TV remote; from then on the phone (R265) picks the title, and
> pause, seek, audio, subtitles, subtitle size, next episode and stop all arrive from the backend
> (236). The TV is its own Ravilo device: the phone can be closed or die and the picture continues.
> The repo already contains this app twice over — the Chromecast receiver (`ravilo-cast`) is exactly a
> phone-driven, no-navigation Ravilo device, and `ravilo-tizen` has the Kotlin/JS + AVPlay player layer
> old Samsung sets need. This phase joins them.

## Scope

Client only: a new `ravilo-screen` module (Kotlin/JS, DOM, no WebAssembly), packaged for **Tizen first**;
webOS is the same bundle in an `.ipk`, listed as a follow-on package, not a second app. Depends on
**236**; sibling **R265**. Written 2026-09-18 from the owner's decisions, the research report
`ravilo-web-pwa-player-cast-2026-09-18.md` (§12) and a read of `ravilo-cast/…/Receiver.kt` and
`ravilo-tizen/`; dev-reviewed against `main` `140f6aba` (§Dev review at the bottom). R269 amends
FR-R264-2/6.

**Supersedes R189** (the full Samsung Tizen client) — owner decision 2026-09-18: *"we already have a Tizen
app which has never been tested; these specs scratch that and this receiver app takes over instead."*
`ravilo-tizen` (2 405 lines, never run on a TV) was removed by this phase (FR-R264-10); its AVPlay wrapper is
the one thing carried over. **This phase is in turn amended by DEB-4654** — see *Amendment 2026-11-15* below.

**What was built** (2026-09-19 and 2026-09-25):
- The core extraction landed first as its own no-behaviour-change commit: `ravilo-receiver-core` carries
  `ReceiverStrings`/`nowMs`/`hms`/`subtitleTracksOf`/`audioTracksOf`, out of `ravilo-cast/Receiver.kt`, which
  depends on it instead of duplicating them.
- `ravilo-screen`: `MediaBackend`/`AvPlayBackend`/`HtmlVideoBackend`/`detectMediaBackend()` (FR-R264-8's
  backend choice), `TizenPlatform.kt` (moved from `ravilo-tizen`) and `Screen.kt` — the pairing loop
  (`screenCode`/`screenClaim`, one device token per paired user, minting kept running while idle), the events
  socket with reconnect/backoff, `play_item` negotiation via `startPlayback`, `playstate_command`/
  `player_command`/`navigate`, both-remotes key handling, `ScreenStatus` on every change and at least every 5 s.
  Unlike `ravilo-cast` it owns no local episode queue: it plays exactly what the latest `play_item` names.
  `config.xml`/`index.html`/`icon.png` live at `ravilo-screen/wgt/`; `package="RaviloScrn"` is exactly 10
  characters (the Samsung installer constraint the dev notes describe).
- **FR-R264-3 as a whole player:** **← / →** seek −10 s / +30 s, **OK** plays/pauses, **↓** opens R180/R195's
  two-level picker on the TV (Subtitles · Audio, `:shared`'s `groupVersions`, the Subtitle size row; a pick
  closes it, R197); **Skip Intro** inside the intro window; the **next-up card** at the credits (or the last
  20 s with no marker) with R245's countdown; the phone's `next` and `cancel_next_up`; Back as R112's two
  steps; R237's *Still trying…* at 5 s.
- **Segments and "next" come from the play push.** The receiver fetches nothing: `play_item` carries the
  title's intro/credits markers (`DetailService.segmentsFor`, so the TV and the phone cannot disagree) and the
  next episode (season then episode order, never a special, never the same multi-episode file twice).
  Auto-advance asks `POST /api/remote/play` to play the next episode on itself, so the push that comes back is
  complete. `PlayPushResolverTest` covers the ordering and the lookup key.
- **One sentence per cause (R237):** 401, 403, 404/410 and no answer each say why; anything else *"That didn't
  start · Try again from your phone."*
- **FR-R264-8's asset** is R272's: every GitHub Release builds, signs and attaches the `.wgt`.

**Findings that hold** (installed on the Tizen 10.0 TV emulator, 2026-09-25):
- **Back on a Samsung remote** arrives as `XF86Back` (key code 10009), not `Backspace`/`Exit`. All three are
  accepted.
- **`config.xml` needs `<access origin="*" subdomains="true">`.** Without it Tizen's web runtime refuses every
  request to another origin (`net::ERR_UNKNOWN_URL_SCHEME`). With it, the packaged widget does not enforce CORS
  on HTTP.
- **The events WebSocket sends `Origin: file://`** on the handshake, and the server's CORS answered 403, so a
  paired TV could never receive `play_item`. Fixed by phase 247's FR-247-3.
- Subtitle-overlay rendering of a sideloaded VTT track over AVPlay is unbuilt pending open question 1;
  container-track *selection* is wired (`MediaBackend.selectAudioTrack`/`selectSubtitleTrack`).

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

## Dev review (2026-09-18, against `main` `140f6aba`)

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

## Amendment 2026-11-15 (DEB-4654) — the receiver becomes a full app

The owner resumed Tizen on DEB-799, 2026-11-15, and asked for a full app driven by the TV remote, with no
casting and no Discover tab. `specs/ravilo/requirements/ravilo-on-a-samsung-tv-is-a-full-app.md` is that
app. It is built inside this module, under the same package. Against this phase:

- **FR-R264-2 and the first non-goal (*no navigation*) are superseded.** The app has Home, Movies, Series,
  Search, a title's page, My List, profiles and Settings (that spec's FR-4 to FR-8 and FR-11).
- **FR-R264-1's enrolment is superseded.** The TV signs in as a viewer (DEB-2233 FR-5's code, or a password)
  instead of pairing to a phone through `screen/code` + `screen/claim`, and the idle code screen goes.
  `screen`-kind tokens left on an upgraded set are dropped. The rest of FR-R264-1 stands: the events socket,
  the negotiation, the progress and status reports.
- **FR-R264-3 to FR-R264-7 stand** as the full app's player requirements. Back from the player returns to the
  page it was started from, and a refused start started from the TV does not say *from your phone*.
- **FR-R264-5 narrows to commands for a signed-in profile.** The input surface is the TV remote plus
  `play_item`/`playstate_command`/`player_command`, which still act as written here (subject to the owner's
  answer on the full app's FR-10).
- **FR-R264-9's strings move to `ravilo-i18n`** (the full app's FR-12). `ReceiverStrings` keeps only what the
  Chromecast receiver shows.
- **Open question 1 is unanswered and is carried to the owner's real-TV test** (the full app's FR-16). Its
  answer still decides who draws the subtitles. The Tizen 10.0 emulator cannot answer it for Tizen 5.0.
- **The target set** is now the owner's UE55RU7445 (2019, RU7400, Tizen 5.0), the same family as the
  UE55RU7440 above.

