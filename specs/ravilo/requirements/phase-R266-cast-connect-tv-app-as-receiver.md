# Phase R266 — Cast Connect: the TV app takes the cast, the web receiver takes the rest

> R245 gave the phone a sender and the Chromecast a web receiver at `/cast/`. The household also has a
> TV that runs the Ravilo Android TV app, and a cast aimed at *that* TV must land in Ravilo proper —
> the skin, the two-level subtitle picker, R216 QoE, the playback tracker, kids gating — not in a web
> page that has none of it. Google's mechanism is Cast Connect. This phase makes the TV app a Cast
> Connect receiver, sets the one sender flag that permits it, and keeps R245's remote identical
> whichever receiver answered.

## Status

`Planned` — written 2026-09-18, **dev-reviewed 2026-09-19 against `main` `dcb97f2c`** (see §Dev review
at the foot: 236 has landed, so the shrink this spec anticipates is now the situation — the play travels
236's `play_item`, not a hand-off redemption). Pairs with admin **237** (the console steps
that associate the TV app's package name and the sideload/test-device rule). Depends on **R245**
(sender, remote, web receiver) and **218** (the `/cast/` bundle and Application ID).

**Numbering:** written 2026-09-18 as R254, renumbered **R254 → R260** the same day when `main`'s own
R254 (row-open-is-a-tv-thing) landed, and renumbered again **R260 → R266** hours later when `main`
took R260 · R261 · R262 (the player's Back on the phone · fullscreen only while playing · Discover is
one page with five tabs) before this draft was ever pushed, and a **third** time **R263 → R266** when
`main` took R263 · R264 · R265 the same afternoon. The dev tracker's number wins, three times.
Verified against `main` on 2026-09-18 — Ravilo taken through **R265**, admin through **236**. Next
free: **238 / R269**.

**⚠ Read together with `main`'s R264 + 236 before implementing.** Those specs answer the same owner
wish — *a TV that runs Ravilo plays the title itself, and keeps playing when the phone is closed* — by a
different road: the phone tells the **backend**, and the TV app (receiver-only, Tizen first) takes
`play_item` off its events socket. This spec's road is Google's: a *cast* to a TV with the Ravilo
package installed launches Ravilo natively instead of the web receiver. They are complementary (Cast
Connect covers the Chromecast-built-in Android TV the household already owns; 236/R264 covers the
Samsung set, and any phone with no Cast SDK), but the **hand-off payload and the enrolment path should
be one mechanism, not two** — FR-R266-4's *play request by hand-off code, never a Jellyfin URL* is
already 236's `play_item` in all but name. If the team builds 236 first, this phase shrinks to the
sender flag, the launch intent and `receiver_kind`.

## Functional requirements

**FR-R266-1 — The phone sender opts in, once.** `CastOptions.androidReceiverCompatible = true` (Web
sender: `castOptions.androidReceiverCompatible`; Android sender: `LaunchOptions.setAndroidReceiverCompatible(true)`).
No UI. The Application ID is the one from 218; the phone never learns which receiver launched until
the session reports it, and R245's remote does not care.

**FR-R266-2 — The TV app declares itself a receiver.** The Android TV build adds the Cast Connect
launch intent filter (`com.google.android.gms.cast.tv.action.LAUNCH`) on the player activity, a
`ReceiverOptionsProvider`, and a `CastReceiverContext` whose lifecycle is bound to the app's — started
with the app, stopped on exit, never held across a backgrounded player (Google's own rule for video
apps: release the session when the viewer leaves playback).

**FR-R266-3 — A load request is a Ravilo play request.** The incoming `MediaLoadRequestData` carries
R245's payload (jellystructure item id + position + the casting viewer's hand-off code), **not** a
Jellyfin stream URL — the TV app resolves it exactly as it would a Select on its own Home, through
jellystructure, so 180 teardown, R216, `requireVisible()`, the per-user ACL and kids gating all see a
normal play. A load request whose hand-off code does not resolve is refused with R237's *couldn't
start* copy, on the TV and in the remote — never a silent web-receiver-style blank.

**FR-R266-4 — Whose profile plays?** The cast carries the *casting viewer's* identity (R245's hand-off
code). The TV app plays under that viewer, as it does for a phase 111 hand-off, and the profile shown
on the TV's Home afterwards is the TV's own last-selected profile, unchanged. If the TV is mid-playback
for another viewer, the incoming cast **replaces** it (Google's behaviour; the TV is a shared surface)
and the interrupted viewer's position is saved first.

**FR-R266-5 — One remote, two receivers.** R245's remote (play/pause, −10 s / +30 s, seek, *Next
episode*, *Stop casting*, subtitles & audio) drives the TV app through the Cast media session with no
new commands. The subtitle sheet's choice is applied by the TV app's own R180/R195 picker logic, so
the R237-era signs-only rule and R241's language-granularity rule hold on the TV exactly as they would
for a D-pad selection. The mini bar and the re-connect rules (R245: rebuild from the receiver; two
outcomes, one silent) are unchanged. *(Note: "R237" here is Ravilo's own signs-only-subtitle phase,
unrelated to admin phase 237 above — the two numbering tracks collided in name only.)*

**FR-R266-6 — Enrolment names the receiver kind.** When a receiver enrols by hand-off code (218), it
reports `receiver_kind: web | android_tv`. The admin's Users & devices row shows the TV's existing
device (Ravilo on the TV *is* that device — a cast does not create a second one), and 237 FR-237-7's
status line renders only once at least one `android_tv` launch is on record.

**FR-R266-7 — Falling back is not an error.** A TV with Ravilo installed but not Play-installed and
not registered as a test device will open the web receiver instead (Google's whitelisted-installer
rule). Nothing on the phone or TV says so — the cast works, in the web receiver — and the admin card
(237 step 6's note) is the only place the reason is written. No new string in any language.

**FR-R266-8 — No new viewer-facing strings.** Every state the TV app can reach from a cast already
has its copy (R218 waiting, R237 failure, R180/R195 picker). The web receiver's ten screens (R245)
are unchanged.

## Non-goals

- **Cast Connect from the Wasm/web build of Ravilo as receiver.** Only the Android TV app can be a
  native receiver.
- **Redesigning the web receiver** to look like the TV app. It is the fallback; it stays the lit mark
  and one sentence.
- **A Ravilo-drawn device picker.** The picker is the platform's (R245).
- **Intent to Join** (the console's *Sender Details → Intent to Join URI*). It lets the phone app join a
  cast session that *something else* started — a voice command, another phone, the Google Home app — by
  tapping Android's remote-control notification; it needs a `VIEW` intent filter on a unique URI and a
  *published, listed* receiver. R245's re-connect already rebuilds the remote from the receiver on app
  start, which is the household case. Owner confirmed 2026-09-18: leave the field empty. If it is ever
  wanted, the URI is `public_url + /cast/join` and 237 gains one Copy row — nothing else on the card moves.
- **Replacing phase 111.** *Play on Stue TV* is jellystructure's own hand-off and works with no
  Google account at all; Cast Connect is what makes the *Cast button* land in Ravilo when the viewer
  reaches for that instead.

## Acceptance

1. Casting from the phone to the stue TV (registered test device, Ravilo sideloaded) launches the
   Ravilo TV app and starts playback under the casting viewer within R218's cold-start budget; the
   web receiver does not appear.
2. Casting the same title to a Chromecast stick launches the web receiver at `/cast/`; the phone UI
   is identical in both cases.
3. With the TV **un**registered as a test device, the same cast launches the web receiver on the TV
   and plays; no error is shown on either screen.
4. The remote's subtitle sheet applied on the TV app selects the same track the TV's own picker
   would for that language (R237/R241 hold).
5. `receiver_kind` is recorded per enrolment; Users & devices shows one device for the TV, not two.
6. Exiting the player on the TV (Back, or a phase 180 teardown) ends the Cast session; the phone's
   mini bar goes away per R245's silent outcome.
7. Zero new keys in `ravilo-i18n.js`.

## Source references

- `ravilo-android/build.gradle.kts:15` — one `applicationId` for phone and TV.
- R245 (`phase-R245-cast-sender-receiver-and-remote.md`) — sender, remote, mini bar, re-connect, web
  receiver; 218 — Application ID, hand-off enrolment, session ceiling; 111 — the Google-free hand-off.
- Google: *Add Core Features to Your Android TV Receiver* (`androidReceiverCompatible`, launch intent,
  `CastReceiverContext` lifecycle), *Android TV receiver troubleshooting* (whitelisted installer).

## Open questions — for the dev team

1. **Can `CastReceiverContext` coexist with the current `PlayerScreen` media session** (R216/QoE
   hooks, R193's no-lock-screen-card rule)? Cast Connect needs a `MediaSessionCompat`; R193 chose not
   to expose one for local playback. Either the session is created only while a cast is live, or R193
   is revisited — design leans **cast-only session**.
2. **Does the R245 payload fit `MediaLoadRequestData` as-is**, or does the hand-off code ride in
   `customData`? Either is fine; it must not become a Jellyfin URL.
3. **Session ceiling accounting (218):** does a Cast Connect play count against the concurrent-cast
   ceiling (it is not a server transcode; the TV direct-plays)? Design leans **no** — the ceiling
   exists because each *web* cast is a transcode.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

**The conditional in this spec's own header has become unconditional.** 236 is `✓ Built` (2026-09-18) and
R264/R265 landed 2026-09-19, so "if the team builds 236 first, this phase shrinks to the sender flag, the
launch intent and `receiver_kind`" is now the situation, not a scenario. Three requirements should be
rewritten around what shipped, and two open questions close against the code.

1. **FR-R266-3 must not go through the hand-off code, and no longer needs to.** `CastService.redeem`
   (`:102`) rejects any `receiverId` not starting `cast-` (`:109`), forces the display name to
   *Chromecast via Ravilo · …* (`:110`) and passes `kind = "cast"` (`:126`) — it *mints a device row*. But
   a Cast Connect launch lands in the **Android TV Ravilo app, which is already an enrolled device holding
   its own token**. Redeeming there would create a second `ravilo_device` row for one physical TV, name it
   a Chromecast, and count it under 218's ceiling. Meanwhile 236 shipped the mechanism this phase wants:
   `play_item` carries `session_user_id` (`TvEventBus.kt:160-162`, `shared/…/Models.kt:657`) and
   `POST /api/remote/play` resolves an item through `mediaStore.resolvePlayTarget`
   (`RemoteRoutes.kt:100-124`).
   **Recommended shape: Cast Connect carries the launch and the transport; the play travels 236's road.**
   The phone's `MediaLoadRequestData` puts the jellystructure item id, the position and the casting
   viewer's user id in `customData` (OQ2 answered: `customData`, because none of it is media data); the TV
   accepts that user id **only if it already holds a token for it**, which is 236's rewritten FR-236-4 and
   is exactly what makes trusting a phone-supplied identity safe. No redemption, no new payload format,
   and FR-R266-3's real requirement — *never a Jellyfin stream URL* — is satisfied by construction.
2. **FR-R266-6 describes an enrolment that will never happen.** "When a receiver enrols by hand-off code
   (218), it reports `receiver_kind`" holds for the web receiver and cannot hold for the TV app, which
   does not enrol on a cast — per item 1, it is already a device. Two consequences. `receiver_kind` is not
   a new field: 236 shipped `kind` on `ravilo_device`, read by `CastService.isCastDevice` (`:69`). And a
   native launch is **not an enrolment event**, so nothing records it today. What this phase needs is a
   *launch observation* — the TV reporting, when `CastReceiverContext` delivers a launch, that it took
   one — most cheaply as a field on the status it already posts under 236. **Phase 237's dev review moved
   FR-237-7's admin status line into this phase**, so R266 now owns both halves: the signal and the
   sentence.
3. **Open question 3 is already answered in code: no.** `checkCeiling` returns immediately unless
   `kind == "cast"` (`CastService.kt:130-137`), and its comment records 236 FR-236-9's decision verbatim —
   a Tizen/webOS screen counts like a TV, one transcode per playing device, never against this ceiling.
   The Android TV app is `kind = "tv"`. The design lean was right and the question can be closed with the
   citation rather than left open.
4. **Open question 1's premise is wrong, and its lean is probably backwards.** It says "R193 chose not to
   expose [a media session] for local playback". On the phone, yes. **On the TV there already is one:**
   `RaviloPlayerAndroid.kt:183` binds a Media3 `MediaSession` to the player (R44, so the OS routes
   hardware transport keys), `RaviloAppContext.kt:41` gates it to TV only, and R192 toggles its visibility
   through a nullable ref rather than `isActive`. Cast Connect runs on exactly the platform that already
   has the session. So the hazard is not "R193 must be revisited" — it is **two sessions competing for the
   same player**, which a cast-only second session would create. Flip the lean: reuse R44's session, hand
   its token to `CastReceiverContext`'s `MediaManager`, and verify that R192's visibility toggle cannot
   deactivate the session a live cast is driving. That last check is the real risk in this phase and is
   worth its own acceptance line.
5. **Acceptance 5 becomes true by construction, and should say why.** "Users & devices shows one device
   for the TV, not two" is currently a hope; under item 1 it is a consequence of never enrolling. Restate
   it as such, because the version that would produce two devices is the one a builder reaches for first
   (it is the only documented path today).
6. **FR-R266-1, -2, -5, -7 and -8 stand as written.** The sender flag is one line, the launch intent and
   `ReceiverOptionsProvider` are Google's own shape, the remote genuinely needs no new commands under
   item 1, the silent fallback is right (and 237's step 6 note is the only place the reason belongs), and
   the no-new-strings claim holds — every state reachable from a cast already has copy. The inline note
   distinguishing Ravilo's R237 from admin phase 237 should stay; it earned its place.

**Net effect.** This phase is now roughly: `androidReceiverCompatible` on the sender, the launch intent
and `CastReceiverContext` on the TV build, `customData` → 236's play, a launch observation, and the
media-session reconciliation in item 4. Everything else it described is either already shipped by 236 or
must not be built the way it is written.
