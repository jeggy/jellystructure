# Phase 218 — Chromecast: serve the receiver, and make registering it part of the product

> Chromecast is optional and set up by the admin. Jellystructure **serves the receiver itself** at
> `/cast/`, and **each installation registers its own Cast application with Google** — the one paid,
> external step in the whole feature (US$5, once, Google only). This phase is the server half: the
> receiver bundle, the guided registration in Settings, the enrolment of the Chromecast as its own Ravilo
> device, and the config snapshot that tells a phone whether a cast button exists at all.

## Status

`✓ Built` — written 2026-09-16, **dev-reviewed 2026-09-16 against `main`** (see §Dev review at the
bottom), **implemented 2026-09-16** (see §Implementation notes). Admin card built into the mockups
2026-09-16. **One finding materially resized the phase:** FR-218-9's hand-off code had no existing
mechanism to build on — phase 141 retired the only one — so it is new machinery (`cast_handoff`,
migration `44.sqm`, two endpoints). Compiled on linuxX64 and wasmJs, unit-tested (`CastServiceTest`);
not deployed; the receiver page itself is R245's.

**Numbering:** verified against `main` on 2026-09-16 — admin taken through **217** (Towo removal), Ravilo
through **R243**. No `phase-218-*` file and no `STATUS.md` row for it. Pairs with **R245**. The source
research report proposed 217 + R247 for Chromecast; 217 was taken the same day, so the pair moved and the
report's ladder shifts by one. Next free: **219 / R246.**

Design / reference implementation: `design/app/settings.html` → Connections → **Chromecast** (`.cc-*`,
three states behind a fenced preview control); the receiver's own screens in
`design/ravilo/Ravilo Receiver.html`; the whole round in `design/ravilo/Casting - Directions.html`.

Depends on / reuses: **phase 110** (a device that opens `/api/tv/events` gets its own named Jellyfin
session via `JellyfinDeviceIdentity`, which is where the Jellyfin dashboard's pause/seek comes from for
free), **phase 141/175** (device tokens; the phone never holds a durable Jellyfin token), **phase 177**
(`MaxStreamingBitrate` negotiation), **phase 180** (session teardown), **phase 182** (the 503 +
`Retry-After` that becomes the receiver's busy state), **phase 183** (outbound pacing), **R216** (QoE).

## Current state

There is **no Cast or `MediaRouter` code anywhere** in the repo. The pieces that do exist:

- `StreamTicket.hlsUrl` is already a direct Jellyfin URL with `api_key=` in the query, so a receiver can
  fetch it with no headers; the ticket also carries `jellyfinBaseUrl`, per-track `SubTrack` VTT URLs and
  `AudioTrack` metadata.
- The backend already serves the admin frontend's static files (`Server.kt` `serveFrontendFile`), so one
  more static bundle is a small change.
- `shared` already compiles to plain JS (`js(IR)`, for the Tizen client), so a web receiver can reuse the
  Kotlin API client instead of re-implementing it.
- Jellyfin ships its own Cast receivers, and **using one is the architecture this phase rejects**: the
  receiver would talk to Jellyfin directly, so the playback tracker, phase 180 teardown, R216 QoE,
  `requireVisible()` gating, the per-user library ACL, kids gating and R183 pacing would all see nothing.
  That is the class of session this project already documented as architecturally unreachable (the
  Wholphin finding). It also requires the phone to hold a durable Jellyfin token, which phases 141/175
  deliberately prevent.

## Decisions taken (owner, 2026-09-16)

1. **Jellystructure hosts the receiver**; no GitHub Pages, no project-hosted shared receiver. Each
   installation registers its own Cast application with Google, configured inside Jellystructure — *"this
   is exactly the part I meant when I said if we need to pay (Google only) or configure anything, then it
   should be configurable within jellystructure."*
2. **The receiver is its own Ravilo device**, with its own device token, and the Jellyfin dashboard shows
   it as **Chromecast via Ravilo**.
3. **Every generation of Chromecast must work** — the project is going open source. The receiver assumes
   no codec and probes at runtime.
4. The card lives in **Settings → Connections**, and the concurrent-session ceiling is pre-filled **2**.

## Functional requirements

**FR-218-1 · Serve the receiver at `/cast/`.** One more static bundle behind `serveFrontendFile`, built
from `shared` compiled to JS plus thin CAF glue (R245 owns its behaviour). It must be reachable over
**public `https://`** for a Chromecast to load it at all.

**Dev review — the pattern is already shipping twice, so copy it rather than invent it.** `Server.kt`
already serves a second static bundle exactly this way: `raviloWebDir` is an optional directory served
under its own path prefix via `call.serveFrontendFile(raviloWebDir, path)` (`:683-686`), falling through
to the admin frontend otherwise (`:692`). `/cast/` is a third instance of that same three-line shape,
with the same "absent directory ⇒ route simply does not answer" behaviour, which pairs well with
FR-218-3. `shared` genuinely does compile to plain JS for this — `js(IR) { browser() }` has shipped since
R189 for Tizen, alongside the `wasmJs` target.

**FR-218-2 · A `chromecast` config block.** `enabled` (bool, default **false**), `app_id` (string, empty
by default), `max_sessions` (int 1–5, default **2**). Per installation, in `config.toml` like every other
key. Junk in `max_sessions` reads back as 2 rather than failing the load.

**Dev review — `val chromecast: ChromecastConfig? = null` on `AppConfig`, matching `seerr`/`bazarr`.**
That is the established shape for an optional integration block (`AppConfig.kt:13-20`), and a null block
is a second, cheaper expression of "off" that FR-218-3 can read without a nested boolean.

**FR-218-3 · Off means absent, not greyed.** When `enabled = false` the config snapshot pushed to clients
says there is nothing to cast to, and **Ravilo renders no cast button anywhere**. The client never
guesses from the presence of a Cast route, a network device or a cached value. A disabled cast button is
a promise the server is not keeping, and is forbidden.

**Dev review — the snapshot is `RaviloConfig`, and phase 202 is the precedent to follow.** `RaviloConfig`
(shared DTO, `Models.kt`) is the per-user resolved config already pushed to every client. 202 put
`focusDetail` on it as a **server-resolved enum** rather than the two booleans the admin card holds,
precisely so no client re-implements a resolution rule. Cast capability takes the same shape: one
resolved field that is either absent or carries the app id (FR-218-11), never a set of flags the client
has to combine. Anything derived — "is there a Chromecast on the network" — stays out of it entirely;
that is the platform's question, asked by the platform's own button.

**FR-218-4 · Settings → Connections → Chromecast, in three states.** Off (the switch and one paragraph,
nothing else rendered); on-but-unregistered (the receiver address, the three numbered steps, an empty
Application ID, the session ceiling, and a status list); registered-and-verified (steps collapsed to a
link, the ID shown with its use count, and a status list including the last cast). Write-through editing
and wf.css tokens, as phases 71/74 established.

**FR-218-5 · The receiver address, with a real self-check.** Read-only, copyable, and accompanied by a
check the **backend performs by fetching its own `/cast/` through the configured public URL** — not by
inspecting local config. Two outcomes only: *reachable over https* or *not reachable — a Chromecast needs
a public https address*. No third "unknown" state that leaves the admin guessing.

**FR-218-6 · The three steps are the registration.** Rendered inline as numbered steps, not as a help
link: *Register an application at the Google Cast Developer Console (a one-time US$5 fee)* · *Choose
"Custom Receiver" and paste the address above* · *Add your Chromecast as a test device, or publish the
application so any Chromecast can use it.* Then the **Application ID** field (8 hex characters).
**Google is named, and is the only product named anywhere in this phase or R245**, because the admin pays
Google. Nothing else — not Jellyfin's transcoder, not a codec, not a protocol — appears in any
viewer-facing string.

**FR-218-7 · The honest verification.** Jellystructure **cannot** verify an application ID with Google;
there is no API for it. Until a real cast has happened the status list says so in words — *"Cast from your
phone once to confirm it works."* — and the card must not imply a check it did not perform. After the
first session it reports the last cast and the devices that have cast, linking to Users & devices.

**FR-218-8 · Concurrent cast sessions.** A stepper (1–5, default 2) with one plain sentence: *"Each cast
is a transcode on your Jellyfin server."* Exceeding it is not an error dialog — it is phase 182's 503 +
`Retry-After`, which the receiver renders as its busy state and the phone's remote mirrors with the
elapsed wait.

**FR-218-9 · Receiver enrolment by hand-off code.** The phone's LOAD carries a short-lived code minted by
the phone's own session; the receiver redeems it for **its own device token and `ravilo_device` row**, and
then behaves like any other Ravilo device: heartbeats, `/api/tv/playback/start`, progress, stop, QoE. The
code is single-use and expires in minutes. **Nothing about the session depends on the phone staying
alive.**

⚠ **Dev review — there is nothing to build this on. Phase 141 deleted the only code-based enrolment
flow this project ever had.** `ravilo_pairing` — the code + poll + admin-approve challenge table — was
**retired** when 141 replaced it with proxied username/password login; `RaviloDeviceService.kt:50` records
that the current path "creates the `(deviceId, jellyfinUserId)` row directly, bypassing the retired
`ravilo_pairing` challenge table entirely." So the hand-off code is **entirely new machinery**: a new
short-lived store, a mint endpoint, a redeem endpoint and an expiry sweep. The spec reads as though it
reuses something; it does not, and that is a real addition to this phase's size.

**Two constraints the new mechanism inherits from `ravilo_device`'s schema.** A row requires a
`jellyfin_user_token` — so redeeming the code copies the *phone user's* Jellyfin token into the
receiver's row, server-side. This is consistent with 141/175 rather than in tension with them, and the
spec should say so plainly: the **receiver never holds a Jellyfin token**, it holds a Ravilo
`device_token`, exactly like a TV, and jellystructure does every Jellyfin call on its behalf. That
distinction is the whole reason this design survives the argument §2 makes against Jellyfin's own
receiver, so leaving it implicit invites a reviewer to think the argument is self-defeating.

Second: a Chromecast receiver is a web page whose storage the platform may clear between sessions. If the
`device_token` does not survive, the receiver re-enrols on **every** cast, and the "single-use, expires in
minutes" code becomes a per-session round trip rather than a one-time setup step. Which of the two it is
must be settled on the real stick before build — it changes the mint endpoint's rate profile, and it
interacts directly with open question 5 (what happens to the row when Chromecast is switched off).

**FR-218-10 · Named identity in Jellyfin.** `JellyfinDeviceIdentity.forDevice` produces
`Device = "Chromecast via Ravilo · <name>"` so the Jellyfin dashboard says what the client is rather than
"Ravilo" twice. Phase 110 then gives dashboard pause/stop/seek for free — the owner's *"control the pause
from the Jellyfin dashboard"* is an existing feature once the receiver is a device, and this phase must
not re-implement it.

**Dev review — this costs nothing, and the `Client = "Ravilo Cast"` alternative is not available.**
`forDevice` already reads `device.displayName.ifBlank { "Ravilo TV" }` (`JellyfinClient.kt:1171-1172`),
so naming the receiver's `ravilo_device` row is the entire implementation — no code change at all. But
`Client` is **hardcoded** to `"Ravilo"` in `jellyfinAuth` (`:1182`), shared by every device; changing it
per-device would mean threading a second field through that one canonical header builder, which Phase 50
deliberately centralised. So the dashboard will read `Client = Ravilo`, `Device = Chromecast via Ravilo ·
<name>`, and the parenthetical alternative in this requirement is withdrawn.

**FR-218-11 · The app ID reaches clients at runtime, not in a manifest.** The receiver app ID is per
installation, so it rides the config snapshot. On Android `CastContext.setReceiverApplicationId(String)`
changes it at runtime, so the sender initialises with a placeholder and applies the real ID when the
snapshot loads. On iOS `GCKCastContext` options are set once per launch, so the ID must be read **before**
Cast is initialised and a changed ID takes effect on the next app start — to be verified against the iOS
SDK when that phase comes.

**FR-218-12 · A cast session is a first-class playback session.** It goes through
`/api/tv/playback/start` with the Chromecast's own probed capabilities, so phase 177's negotiation, the
per-user library ACL, kids gating, `requireVisible()`, R183 pacing, phase 180 teardown and R216 QoE all
apply unchanged. **No new path to Jellyfin is opened**, and no client is given a durable Jellyfin token.

**FR-218-13 · Page-local CSS naming.** The card's classes are prefixed `cc-`, following the `.paced` /
`.mkvh-` precedent, and explicitly **not** `adv-*` or any other name an ad blocker's cosmetic filter list
matches — that cost a mysteriously blank element on 2026-09-15, and the shipped `advisorFindingHtml`
(phase 212 §8) has the same exposure.

## Non-goals

- **Using Jellyfin's own Cast receiver as the product.** Worth a one-day spike to validate the SDK
  wiring; never the shipped path, for the reasons in *Current state*.
- **A project-hosted shared receiver** that spares every admin the Google registration. Could be added
  later as a convenience; explicitly out of scope by owner decision.
- **Cast Connect** (the phone's Cast button launching Ravilo TV natively on an Android TV). The end
  state, and a later phase.
- **AirPlay**, ever.
- **Verifying the application ID with Google.** There is no API. See FR-218-7.
- **Any UI for choosing a Cast receiver *version*** (Jellyfin's `CastReceiverId` setting has no analogue
  here — there is exactly one receiver, ours).

## Acceptance

1. With `enabled = false`, a phone shows **no** cast button — verified by grep on the client, not just by
   eye — and the config snapshot carries no app ID.
2. With `enabled = true` and no app ID, the card shows the three steps and says in words that only a real
   cast can confirm the ID.
3. The reachability chip is wrong when it should be: temporarily break the public URL and it reports *not
   reachable* rather than staying green off local config.
4. A completed cast makes the Chromecast appear as its own row in **Users & devices**, and the Jellyfin
   dashboard lists it as *Chromecast via Ravilo · <name>* and can pause it.
5. Killing the phone mid-cast (force-stop, or airplane mode) does not stop the TV.
6. A third concurrent cast against `max_sessions = 2` produces phase 182's 503 + `Retry-After`, and the
   receiver shows the busy sentence — not an error code, not a spinner forever.
7. `scripts/check-phases.sh` and `scripts/check-mobile-css.sh` stay green.

## Open questions

1. **The generation of the parents' Chromecast stick.** 1st gen (2013) had its last firmware in November
   2022 and lost support in April 2023. Whether a CAF v3 receiver still *launches* on one is not confirmed
   by any source we fetched and **must be tested on the real device** before this phase is committed to.
   The benchmark is Jellyfin's own receiver on the same stick: if that runs, ours will. Identify the
   generation first — 1st gen is the flat dongle with a rounded end; 2nd/3rd gen are round discs.
2. **The concurrent-encode number.** 2 is a guess informed by nothing but caution. The honest input is how
   many NVENC sessions the house's server sustains next to the TVs' own fallbacks, which nobody has
   measured.
3. **Whether `max_sessions` should be per-user or per-server.** It is a property of the server's encoder,
   which argues per-server; but a household might want to stop one profile monopolising it.
4. **Hand-off code lifetime.** Minutes is stated, not chosen. It wants to be long enough for a slow stick
   to boot the receiver and short enough that a stale LOAD cannot enrol a device later.
5. **What happens to the receiver's `ravilo_device` row** when the admin turns Chromecast off. Revoke the
   token, or leave the row so history survives? The Towo removal (phase 217) argues for an explicit
   answer rather than an orphan.

## Dev review (2026-09-16)

Reviewed against `main` at `080364b4`. **The architecture holds and the §2 argument against Jellyfin's own
receiver survives scrutiny** — every invariant it names is real and really would be bypassed. Most
integration points are cheaper than the spec assumes. One is considerably more expensive.

### Cheaper than assumed

- **FR-218-1** — a third static bundle is a three-line copy of the `raviloWebDir` shape already in
  `Server.kt`, and `shared`'s JS target has shipped since R189.
- **FR-218-2** — `ChromecastConfig? = null` on `AppConfig` matches four existing optional blocks.
- **FR-218-10** — free. `forDevice` already names the device from `displayName`, so setting the
  receiver's row name is the whole implementation. The `Client = "Ravilo Cast"` variant is withdrawn:
  `Client` is hardcoded in the one canonical header builder Phase 50 centralised, and per-device
  variation would undo that centralisation for a cosmetic gain.

### More expensive than assumed

**FR-218-9 has no foundation.** `ravilo_pairing` — code, poll, admin-approve — was retired by phase 141,
which is recorded in `RaviloDeviceService.kt`'s own comment. The hand-off code is therefore a new store,
two endpoints and an expiry sweep, not a reuse. Worse, whether it runs **once per receiver** or **once per
cast** depends on whether a Chromecast preserves the receiver page's storage between sessions, which
nobody has tested. That question sits alongside open question 1 as something to answer on the real stick
before committing to the phase, and it bears directly on open questions 4 and 5.

### One clarification that protects the spec's own argument

The receiver enrolling as a Ravilo device means its `ravilo_device` row holds a `jellyfin_user_token`
**server-side**. The receiver itself holds only a `device_token`, exactly like a TV. Stating this
explicitly matters, because §2 rejects Jellyfin's receiver partly on the grounds that it "needs the phone
to hold a durable Jellyfin token" — a reader who does not know the schema could reasonably think this
design has the same problem, and it does not.

### Inherited from 217

165 cited Towo's "where runners connect" field as the precedent for an operator-editable "where should
this be reached from" address. **Phase 217 deletes it.** So FR-218-5's receiver address is now the first
shipped instance of that pattern rather than the second, and whatever shape it takes is what 165 will
have to copy later.

### Unchanged

FR-218-4, 6, 7, 8, 12 and 13 are unaffected. The honest-verification rule in FR-218-7 is the right call
and has no cheaper alternative — nothing in the Cast SDK lets a server confirm an application ID without
a real cast. Open questions 1, 2 and 3 stand as written; 4 and 5 gain the storage-durability question
above.

## Implementation notes (2026-09-16)

- **FR-218-1** — `Server.kt` serves `/cast/**` from `castDir` exactly like `/tv/**` from `raviloWebDir`
  (three lines), adding an `X-Ravilo-Cast: receiver` response header so FR-218-5's check can tell *our*
  receiver from any other 200. `Main.kt` resolves `CAST_DIR` (Docker: `/app/cast`, `COPY cast-receiver/`
  in the Dockerfile; dev: the repo's `cast-receiver/`) and passes null when the directory is absent, so
  the route simply does not answer. `cast-receiver/index.html` is the mount point; **R245 owns and
  replaces its contents.**
- **FR-218-2** — `ChromecastConfig? = null` on `AppConfig` (`enabled`, `app_id`, `max_sessions`,
  **plus `public_url`**, see FR-218-5 below). `effectiveMaxSessions()` returns 2 for anything outside
  1–5; `hasAppId()` is the 8-hex rule.
- **FR-218-3/11** — `RaviloConfig.cast: CastCapability?` (`app_id`, informational `receiver_url`),
  resolved by `RaviloConfigService` on every read path exactly the way `focus_detail` is (202's
  precedent), **never persisted** (`normalize()` nulls it) and null unless enabled *and* an app id is
  set. `PUT /api/config` publishes the global-config event when the block changed, so open clients
  re-fetch. Nothing derived from the network ever enters it.
- **FR-218-4/6/7/13** — the card in `Settings.kt` under Connections (`sect-cast`, `cc-*` classes),
  three states driven by the form + `GET /api/config/chromecast/status`: off (switch + paragraph),
  on-but-unregistered (address, the three steps naming Google and the US$5 fee, empty ID, the 1–5
  stepper, status list), and registered-and-verified (steps collapse behind *Show the three steps
  again*, hint says confirmed by a real cast, last cast + device count + the Jellyfin name). `verified`
  is **only** "a receiver has enrolled" — the card never claims a check it did not perform. The `cc-*`
  rules moved from the design's inline `<style>` into the served `wf.css` and are fenced by
  `check-mobile-css.sh`.
- **FR-218-5** — `POST /api/config/chromecast/check {url}`: the backend fetches `<url>/cast/` itself
  through `OutboundHttp` (8 s), requires `https://` and the `X-Ravilo-Cast` header, and answers one of
  two outcomes. **Deviation, stated:** the spec's "configured public URL" did not exist in config, so the
  block gained `public_url`; the card pre-fills it from the page's origin (as a default the admin
  saves, not as evidence) and the check never trusts the browser. This is now the first shipped
  instance of the "explicit reach address" pattern 165 will copy.
- **FR-218-8** — `CastService.checkCeiling()` runs at the top of `PlaybackService.startPlayback` for a
  device whose name carries the receiver prefix: `max_sessions` *other* receivers already playing ⇒
  `CastCeilingException` ⇒ phase 182's shape, 503 + `Retry-After: 30`, via `StatusPages`. A receiver
  starting its next episode never counts against itself; a TV or phone is never gated.
- **FR-218-9** — new `cast_handoff` table (`CastHandoff.sq`, **`44.sqm`**): `POST /api/tv/cast/handoff`
  (device-auth) mints a 6-character code (no 0/O/1/I) valid **5 minutes**, single-use; `POST
  /api/tv/cast/redeem` (in `OPEN_API_PATHS`, rate-limited by the `/tv/login` limiter) exchanges it
  for a `PairResult` — a `ravilo_device` row `cast-…` created through `loginDevice` with the **phone
  user's** id, Jellyfin token, kids flag and library/tag policy copied server-side. The receiver holds
  only a `device_token`, like a TV. An optional `receiver_id` reuses the row when the stick's storage
  survived (open question on durability handled both ways: same row + same token if it did, a fresh
  row if not). Expired and redeemed rows are swept on every mint/redeem.
- **FR-218-10** — free, as the review said: the row's name is `Chromecast via Ravilo · <name>`.
- **FR-218-12** — nothing new: a receiver calls `/api/tv/playback/start` as any device.
- **Test** `CastServiceTest`: capability absent unless enabled + 8-hex; mint → redeem once (policy,
  name, real token), second redeem and unknown code refused, `receiver_id` reuse; ceiling refuses only a
  receiver past the limit and never a TV.
- **Not done / open:** acceptance 3–6 need a real stick and a deploy; the stick's generation (OQ1) and
  the concurrent-encode number (OQ2) are untouched guesses; what happens to a receiver row when the admin
  switches Chromecast off (OQ5) is unchanged — the row stays, the button disappears, and a redeem while
  disabled is refused because `handoff` 404s without a capability.

## Amendment (2026-09-18) — the first real cast: the receiver page could not load its own framework

First end-to-end attempt (Pixel 9 → Soveværelse TV, app published, the TV answering `APP_AVAILABLE`):
the TV accepted the launch (`LAUNCH_STATUS: USER_ALLOWED`) and then ran **no application**; the phone
sat on *Connecting…*. `/cast/` was served with the site-wide CSP — `script-src 'self'` — which blocks
`https://www.gstatic.com/cast/sdk/libs/caf_receiver/v3/cast_receiver_framework.js`, the one script a
receiver cannot self-host. No framework ⇒ `context.start()` never runs ⇒ the device discards the page.
FR-218-7's "only a real cast can confirm this" was right, and this is what the first one found.

- **FR-218-1a** — `/cast/**` gets its own policy (`CAST_RECEIVER_CSP`): Google's Cast origin (`www.gstatic.com`) and `ajax.googleapis.com` — CAF fetches the Shaka HLS
  player from there at runtime, protocol-relative, so the sources carry no scheme — for script, style and font; blob workers; no `X-Frame-Options`/`frame-ancestors` (some cast shells embed
  the page; it is non-interactive). Every other path keeps the strict policy unchanged.
- **FR-218-4a (open)** — the reachability check should also fail when the served policy would block
  an external script the page references; today it only proves the page is fetchable.
- e2e: every external script origin in `/cast/` must appear in its `script-src`; the admin page still
  carries `X-Frame-Options: DENY` and no Google origin.

## Amendment 2 (2026-09-18) — the receiver's capabilities were declared and never read

Second real cast (Pixel 9 → Stue TV, v1.24): the receiver enrolled, negotiated, and the TV's player
process was killed 12 s after the load (`Render process for main frame exited unexpectedly`,
`error_code=-9`). The backend had logged `directPlay=true`. FR-R245-13's receiver sends
`hls_only=true, containers=[mp4,ts]`; `deviceProfile()` read neither and declared
`Container: mkv,…` direct-playable for every client, so the receiver got a raw MKV `/stream?Static=true`
URL and fed it to a player that expects HLS.

- **FR-218-10** — a client with `hls_only` gets **no** `DirectPlayProfiles`: Jellyfin then always
  answers with a `TranscodingUrl` (a codec-copy remux when the codecs fit the transcoding profile, a
  transcode otherwise). A client that names `containers` direct-plays only those. A client that names
  nothing keeps today's list — no TV or phone changes behaviour.
- Open: the transcoding profile is still H.264 in TS with AAC/AC-3; an HEVC file is re-encoded for
  the receiver rather than remuxed. A fragmented-MP4 HLS profile carrying HEVC when the receiver's
  `video_codecs` include it is the next step, once one cast plays end to end.

