# Phase R269 — The receiver needs a server before it can pair

> Found by the owner, 2026-09-18, reading the round-1 receiver frames: *"The Ravilo TV receiver app
> probably needs to enter a server before it can start all of this authentication. I think this part was
> missing."* It was. **R264** has the receiver enrol with 218's hand-off code (FR-R264-1) and show a
> pairing code on idle *always* (FR-R264-2) — but a code is **minted server-side**, so neither can happen
> until the app can reach a server, and nothing in the phase says where that address comes from. This
> phase adds the one screen that answers it, and the ordering rule it implies: **server → code → pair**.

## Status


**2026-09-25 — the CORS question is answered, on the Tizen 10.0 TV emulator.** Installed and driven over
DevTools: the widget's origin is `file://`; with the `<access origin="*">` element it lacked (without it,
*every* request failed with `net::ERR_UNKNOWN_URL_SCHEME` — the setup probe could never have succeeded on
a TV), `GET /api/health` on the production server returns 200 with a readable body and no
`Access-Control-Allow-Origin` header, and a preflighted JSON POST reaches the server directly. A packaged
Tizen widget does not enforce CORS; no backend exception is needed. The RU7440's Tizen 5.0 is older and
unverified. Also fixed: with no server stored, setup was drawn over the idle screen.
`✓ Built` — written 2026-09-18. **Dev-reviewed and built 2026-09-19 against `main`
(post-R264/R272/R265 build)** — see §Dev review below: FR-R269-3's premise (the TV client already probes
the server) is wrong, but the fix is good news — `GET /api/health` already exists, unauthenticated, and
answers Open Question 1 outright with zero backend change; FR-R269-2's LAN-http-fallback claim describes
behavior R226 never actually shipped, corrected to match what R226 really does. Client only
(`ravilo-screen`); no backend, route, DTO or config change, confirming the spec's own claim. Extends
**R264** and supersedes two of its clauses (FR-R264-2's *always shows its code*, FR-R264-6's single
no-server sentence).

**Built**: `Screen.kt`'s `main()` now checks `localStorage` for a stored address before anything else;
absent, it shows the setup screen (`index.html`'s new `#setup` section — mark, title, a plain `<input>`
relying on Tizen's own system IME rather than R175's Compose keyboard, a Connect button, a hint/error
line), probes `GET {url}/api/health` for `"status":"ok"`, and only stores the address on success — a
failed probe keeps the typed text and shows the *not found* sentence (FR-R269-3). Idle now shows the
configured address (FR-R269-6) and a stored-but-unreachable server reuses the existing `noserver` screen
with a retry loop (FR-R269-5) instead of silently doing nothing. Holding Back on idle for three seconds
(FR-R269-7) reloads the page with the current address prefilled via a `localStorage` handoff key — no
live teardown of the running instance, the simplest correct implementation of a rare action. Three new
strings × en/da/fo added to `ravilo-receiver-core`'s `ReceiverStrings` (shared with `ravilo-cast`, which
never reads the new keys). `TizenPlatform`'s `KeyboardEvent`-based key handling was restructured so the
hold-detection runs independent of the existing `!loaded` guard.

**Verified**: `:ravilo-receiver-core:compileKotlinJs`/`:ravilo-screen:compileKotlinJs`/
`:ravilo-cast:compileKotlinJs` all clean; the full production webpack bundle
(`:ravilo-screen:syncScreenReceiver`) builds. **Real browser verification performed** (Playwright/Chromium
against the actual built bundle, a tiny local mock `/api/health`): fresh load shows setup; a bad/unreachable
host shows the *not found* sentence and keeps the typed text; a reachable mock succeeds. This is
real, but limited — see finding 6 below for what it does NOT prove.

**Not done / found, tracked here rather than guessed:**
- **A real, previously-unconsidered risk found by this verification**: the backend's CORS policy is
  deliberately restrictive (an allowlist, no wildcard — see finding 6), and a sideloaded `.wgt` has no
  origin `allowHost()` can represent. Whether a packaged Tizen widget's networking bypasses browser CORS
  the way other privileged app runtimes do is **unconfirmed** — this can only be resolved by installing
  the actual `.wgt` on the Tizen emulator/hardware and watching whether its calls succeed, which is also
  R264's own still-open verification step.
- No hardware verification at all — same standing caveat as R264/R265/R272 tonight.

### Dev review (2026-09-19, against `main` post-R264/R272/R265)

1. **FR-R269-3's premise is false — read the real code, not the description.** `ServerSetupScreen.kt`
   (`ravilo-ui`) does **no reachability or identity probing at all**: `onUrlSaved(fullUrl)` fires the
   instant "Go" is pressed, straight from inferring the scheme — no fetch, no check. `RaviloRoot.kt`
   confirms it: `saveBaseUrl(url); baseUrl = url` with nothing in between. "The same probe the TV client's
   setup screen already performs" describes a probe that doesn't exist anywhere in this codebase today.
2. **Open question 1 is answered, and the news is good: no backend change needed.** `GET /api/health`
   already exists (`Server.kt`), is explicitly exempted from auth (`AuthPlugin.kt`'s `path == "/api/health"`
   check — built for "orchestration health checks that can't carry a session cookie"), and returns
   `{"status":"ok","version":"…", …}` — a shape no non-Ravilo web server would produce by accident. Use it
   as FR-R269-3's probe verbatim: fetch it, treat a response containing `"status":"ok"` as "reachable and
   Ravilo", anything else (non-JSON, wrong shape, network failure, timeout) as the one *not found* sentence
   FR-R269-3 already specifies. This is a *better* answer than the spec assumed — it's an endpoint that
   already exists for an unrelated reason, not a new one to add.
3. **FR-R269-2 misdescribes what R226 shipped.** There is no automatic "falls back to `http://` for a
   private/LAN address" anywhere in `ServerSetupScreen.kt`, `RaviloRoot.kt`, or the actuals — the real rule
   is simpler: `https://` is inferred unless the user types a scheme themselves (verified: `hasScheme`
   check, then `if (hasScheme) host else "https://$host"`, no IP-range logic at all). A household on a
   plain-HTTP LAN box must type `http://` explicitly today, same as everywhere else in this codebase.
   Build `ravilo-screen`'s version to match **actual** R226 behavior (correct the FR's wording rather than
   inventing new LAN-detection logic no other client has) — consistency across clients matters more here
   than the (unbuilt) smarter behavior the FR assumed existed.
4. **Storage fits the pattern R264 already established.** `Screen.kt` already persists `deviceId` and the
   per-user token map under `localStorage` keys `ravilo.screen.deviceId`/`ravilo.screen.tokens` — a third
   key (`ravilo.screen.serverUrl`) for FR-R269-1's stored address is the same mechanism, not a new one.
5. **FR-R269-1's on-screen-keyboard reference needs no action, and the spec's own dev note already says
   so** — confirmed correct on inspection: R175's keyboard is a Compose composable and cannot be reused
   from `ravilo-screen`'s DOM code. A focused `<input>` on a real Tizen TV should raise Samsung's own
   system IME automatically (standard Tizen web-app behavior); nothing here needs building beyond a plain
   `<input>` element — noted as an assumption to confirm on real hardware alongside R264's own unverified
   acceptance criteria, not a new open question.

**Build order:** straightforward given the above — `Screen.kt`'s `main()` currently reads
`window.RAVILO_SERVER_URL` (a package-time stopgap this phase replaces) and returns early if blank; this
becomes "check `localStorage`, else render the setup screen, probe `/api/health` on submit, store on
success, then proceed to the existing `Screen(serverUrl).start()`". Everything else in `Screen.kt` is
unaffected.

6. **A real risk this review found empirically, affecting R264 as a whole, not just this phase's setup
   screen.** Browser-testing the built bundle (Playwright/Chromium, a real backend-shaped mock at a
   different origin) surfaced that the server's own `install(CORS) { … }` (`Server.kt`) is deliberately
   restrictive since a 2026-08-02 security fix: cross-origin requests are allowed only from
   `CORS_ALLOWED_ORIGINS` (an admin-set allowlist of `host:port` pairs meant for a local webpack/vite dev
   server), never a wildcard. `ravilo-cast` never hits this because it's served *from* the backend
   (same-origin, per this phase's own "why the Chromecast receiver never needed this" section) — but a
   sideloaded `ravilo-screen` `.wgt` is, by this phase's own diagnosis, "a local file with no origin at
   all", meaning **every one of its calls to the backend — this phase's `/api/health` probe, and R264's
   own pairing/status/events calls — is cross-origin**, and `allowHost()` has no way to express a Tizen
   widget's actual origin (`file://`, or whatever scheme Tizen's WebKit assigns a packaged `.wgt`) even if
   an admin wanted to allowlist it. **Unconfirmed which way this resolves**: a packaged Tizen widget
   running with the `http://tizen.org/privilege/internet` privilege may bypass browser-tab CORS
   enforcement entirely (the standard behavior for privileged/hybrid app runtimes — Cordova, Electron,
   etc. — which is *why* that privilege exists), in which case nothing here needs to change; or it may
   not, in which case every receiver call fails silently against a real, correctly-configured production
   server, and the backend's CORS policy needs a real answer for this device class (a Tizen-widget-shaped
   exception is not obviously safe to add given the finding M1 security history documented right there in
   `Server.kt`). This can only be resolved by installing the actual signed `.wgt` on the Tizen emulator or
   real hardware and watching whether its network calls succeed — the one verification step still open
   from R264 itself (its own open question 1, plus this one, are both blocked on the same missing step).

**Numbering:** verified against `main` and `STATUS.md` on 2026-09-18 — Ravilo taken through **R265**
(with **R266 · R267 · R268** ours, written the same day), admin through **236** (with **237** ours).
Ravilo-only, no admin pair. Next free: **238 / R270**.

Design: `design/ravilo/Play on a TV - Directions.html` §C0 (four TV frames: first boot, typing with the
scheme inferred, trying, wrong address) and §C1c (idle naming its server). **Built into
`design/ravilo/Ravilo Receiver App.html`** — the maintained mockup for this app, started 2026-09-18 and
kept separate from `Ravilo Receiver.html` (the Chromecast's screen, which is served by the backend and
therefore never asks for one). Its SETUP picker carries all five setup states including the
hold-*Back* return.

## Why the Chromecast receiver never needed this

`ravilo-cast` is **served by the backend** at `/cast/` (218), so its own origin *is* the server: it can
ask before it can be configured wrong, and a wrong address is not a reachable state. A sideloaded Tizen
`.wgt` (R264 FR-R264-8) is a local file with **no origin at all**. It boots knowing nothing — no host, no
token, no code, no language default. Every later screen in R264 assumes a server that this one has to
produce first.

## Requirements

**FR-R269-1 · One setup screen, reached only when there is no server.** On start, if the TV holds no
server address, the app draws `ServerSetupScreen`: the mark, *"Hvor er Ravilo?"* (en/da/fo), one address
field, R175's on-screen keyboard, and a single action. It is drawn **only** in this condition — never from
the idle screen, never mid-playback, never as a menu, and never as a navigable destination. This is the
one exception to R264 FR-R264-2's *no navigation*, and it exists because without it the app can do
nothing whatsoever.

**FR-R269-2 · The scheme is not a question.** R226's rule, unchanged: the household types a host
(`ravilo.mit-hus.dk`, `192.168.1.40:8096`), the client infers `https://` and falls back to `http://` for
a private/LAN address. No toggle, no dropdown, no protocol word in any label. The field's placeholder is
an example address, not a URL template.

**FR-R269-3 · The action probes, and says which of three things happened.** *Forbind* fetches the
server's own identity endpoint (the same probe the TV client's setup screen already performs) with a
short timeout:
- **reachable and it is a Ravilo backend** → store the address, go to FR-R269-4;
- **reachable but not Ravilo** → *"Der er ingen Ravilo-server på den adresse"*;
- **unreachable** → the same sentence. The two are one string on purpose: the household's fix is
  identical, and naming the difference would mean naming a status code (FR-R245-16).
The typed text is **kept and focused** on failure — a field that clears itself on a 40-character address
typed with a D-pad is the cruelty R234 FR-R234-5 already called out.

**FR-R269-4 · Then, and only then, the code.** With an address stored, the app asks the backend to mint
its enrolment code (218) and draws R264's idle screen. The ordering is the requirement: **no code exists
before a server answers**, so the code screen never shows a code it cannot have minted.

**FR-R269-5 · Waiting for the server is not the same as having none.** R264 FR-R264-6 gives both states
one sentence; they are different situations with different actions and must read differently:
| State | Screen | What the household should do |
|---|---|---|
| No address stored | FR-R269-1's setup screen | Type an address |
| Address stored, server not answering | Idle, mark + TV name + *"Kan ikke få forbindelse til serveren"* in place of the code line, retrying with backoff | Nothing — it comes back |
| Address stored, server answering | Idle with the code | Type the code into the phone |
A stored address is **never** discarded because the server is down; the app retries indefinitely.

**FR-R269-6 · Idle names its server.** Under the TV's name, one quiet monospace line with the configured
address. It is the only way a household can see that a TV is pointed at a server that has since moved,
and it is the first thing to ask about when a receiver behaves oddly. Not shown while playing.

**FR-R269-7 · A way back, without a menu.** Holding *Back* on the idle screen for three seconds reopens
the setup screen with the current address in the field. Nothing advertises it — same class of affordance
as a factory reset — and it is the **only** route back. Rejected alternatives, recorded: a visible
*Change server* item (it is navigation, which this app does not have) and doing it from the phone
(impossible — the phone reaches the TV *through* the server being replaced).

**FR-R269-8 · No discovery.** No mDNS/Bonjour, no LAN sweep, no "found a server" shortcut. Tizen 5.0's
web runtime has no usable mDNS; the phone side already refused LAN discovery (R265 non-goals); and a
receiver that *sometimes* finds the server is worse than one that always asks once. The same reasoning as
"on this network" being the server's judgement rather than the phone's (236 FR-236-6).

**FR-R269-9 · Language before there is a server.** The setup screen cannot follow the server's default
(there is no server), so it follows the **TV's own UI language** where Tizen reports one, else English.
Every later screen keeps R264 FR-R264-2's order (TV language, then the server's default).

**FR-R269-10 · Strings × en/da/fo.** `srv.setup_title` *Hvor er Ravilo?* · `srv.setup_hint` *Skriv
adressen på jeres Ravilo-server* · `srv.setup_placeholder` (an example address) · `srv.setup_connect`
*Forbind* · `srv.setup_trying` *Prøver at få forbindelse…* · `srv.setup_not_found` *Der er ingen
Ravilo-server på den adresse* · `srv.scheme_hint` *https:// tilføjes*. The shipped table wins wherever a
key exists; `receiver.no_server` (R264 FR-R264-9) stays for the configured-but-unreachable case.

## Acceptance

- A freshly sideloaded `.wgt` on the **UE55RU7440**, never configured: boots to the setup screen in
  < 3 s (R264 FR-R264-7's budget still holds — this screen carries no artwork either); a host typed with
  the TV remote connects; the pairing code appears; a phone pairs; playback works as R264 describes.
- The same TV after a reboot: **no setup screen** — straight to idle with the code.
- Backend stopped, TV rebooted: idle with *"Kan ikke få forbindelse til serveren"*, no code, retrying;
  backend started; the code appears with no interaction.
- A deliberately wrong host: the *not found* sentence, the typed text still there, one edit fixes it.
- A host that is a web server but not Ravilo: the same sentence, no protocol words, no stack detail.
- Hold *Back* three seconds on idle: the setup screen with the current address prefilled; *Forbind* on an
  unchanged address is a no-op that returns to idle.
- `scripts/check-phases.sh` green.

## Non-goals

- No navigation of any other kind on the receiver. This phase adds exactly one screen and one hold.
- No server *list* on the TV, no switching between two saved servers, no per-user server.
- No QR code, no camera, no phone-to-TV address hand-off (see FR-R269-7).
- No change to how the phone pairs (R265 FR-R265-5), to 218's code minting, or to the Chromecast
  receiver, which is served from its origin and never sees this screen.

## Open questions

1. **Does the probe need its own endpoint?** The TV client's setup screen already validates a server;
   confirm the same call is available to a Kotlin/JS DOM build with no shared client stack, or name the
   plain `GET` it should use instead.
2. **Three seconds, or a key combination, for the way back?** The *hold* is the owner's decision
   (below); only its exact gesture is a dev detail. A hold is discoverable by accident on some remotes;
   if that turns out to be a problem in the house, a two-key combination is the fallback.
3. **Should the setup screen show the TV's own IP** as a courtesy (*"this TV is 192.168.1.66"*)? It helps
   an admin who is guessing at the server's address on the same subnet. Lean: no — it is one more line on
   a screen seen once, and the address is in the router.

## Owner decisions, 2026-09-18

Both questions this phase opened on the design canvas are **answered**, and the FRs above are written to
match — they are recorded here so a dev review can see they were chosen, not assumed:

- **The way back is a three-second hold on *Back* from the idle screen** (FR-R269-7). Not a visible
   menu item — this app has no navigation — and not "from the phone", which is impossible: the phone
   reaches the TV *through* the server being replaced.
- **When the server is unreachable, the screen waits and says so** (FR-R269-5). It never shows the last
   code: a code that cannot be redeemed fails on the *phone*, which points the household at the wrong
   device entirely.
- **The idle screen is not dimmed after ten minutes** (R264's open question 2, scratched — see the dev
   note below).

## Dev notes

- `ServerSetupScreen` (R225/R226) is Compose and lives in `ravilo-ui`; `ravilo-screen` is Kotlin/JS DOM,
  so this is a **re-draw of the same screen in DOM**, not a reuse of the composable. Keep the copy, the
  inference rule and the failure behaviour identical — the household has met this screen before.
- Store the address in the same place the device token lives (R264 FR-R264-1's TV-side store), written
  only after a successful probe, so a half-finished setup cannot brick the boot path.
- The owner also **scratched R264's open question 2** (dimming idle to 20 % after ten minutes) the same
  day: the idle screen stays at full brightness. It holds no artwork, and a code you have to wake with a
  remote you may not be holding is worse than a lit mark. Recorded here because R264 is dev-authored.
