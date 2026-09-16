# Phase 218 — Chromecast: serve the receiver, and make registering it part of the product

> Chromecast is optional and set up by the admin. Jellystructure **serves the receiver itself** at
> `/cast/`, and **each installation registers its own Cast application with Google** — the one paid,
> external step in the whole feature (US$5, once, Google only). This phase is the server half: the
> receiver bundle, the guided registration in Settings, the enrolment of the Chromecast as its own Ravilo
> device, and the config snapshot that tells a phone whether a cast button exists at all.

## Status

`Planned` — written 2026-09-16, **not dev-reviewed**. Admin card built into the mockups 2026-09-16.

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

**FR-218-2 · A `chromecast` config block.** `enabled` (bool, default **false**), `app_id` (string, empty
by default), `max_sessions` (int 1–5, default **2**). Per installation, in `config.toml` like every other
key. Junk in `max_sessions` reads back as 2 rather than failing the load.

**FR-218-3 · Off means absent, not greyed.** When `enabled = false` the config snapshot pushed to clients
says there is nothing to cast to, and **Ravilo renders no cast button anywhere**. The client never
guesses from the presence of a Cast route, a network device or a cached value. A disabled cast button is
a promise the server is not keeping, and is forbidden.

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

**FR-218-10 · Named identity in Jellyfin.** `JellyfinDeviceIdentity.forDevice` produces
`Device = "Chromecast via Ravilo · <name>"` (or `Client = "Ravilo Cast"`) so the Jellyfin dashboard says
what the client is rather than "Ravilo" twice. Phase 110 then gives dashboard pause/stop/seek for free —
the owner's *"control the pause from the Jellyfin dashboard"* is an existing feature once the receiver is
a device, and this phase must not re-implement it.

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
