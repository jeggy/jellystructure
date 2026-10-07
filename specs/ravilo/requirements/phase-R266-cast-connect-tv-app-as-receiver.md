# Phase R266 — Cast Connect: the TV app takes the cast, the web receiver takes the rest

> R245 gave the phone a sender and the Chromecast a web receiver at `/cast/`. The household also has a
> TV that runs the Ravilo Android TV app, and a cast aimed at *that* TV must land in Ravilo proper —
> the skin, the two-level subtitle picker, R216 QoE, the playback tracker, kids gating — not in a web
> page that has none of it. Google's mechanism is Cast Connect. This phase makes the TV app a Cast
> Connect receiver, sets the one sender flag that permits it, and keeps R245's remote identical
> whichever receiver answered.

## Status

`Planned` — design-authored 2026-09-18, **dev-reviewed 2026-09-19 against `main` `b397f5e6`**, **not
built, deliberately.** Two of its three open questions are closed below from the review's code
citations; the build itself is the only remaining phase in this batch that was left alone, and the
reason is worth stating rather than leaving as a gap.

### Why this one was not built on 2026-09-20

Everything else in the 237/R264–R271 batch was buildable and checkable without hardware. This is not:

- It adds the **Cast Connect SDK** (`play-services-cast-tv`), a `com.google.android.gms.cast.tv.action.LAUNCH`
  intent filter and a `CastReceiverContext` to the **shipped Android TV build**. That is a manifest and
  dependency change to the artifact the household actually installs, and this project has already paid
  once for a release-only failure that no debug build could show
  (`PlayerScreen`'s `VerifyError` — see `scripts/verify-release-apk-on-art.sh`, which exists because of it).
- **Nothing about it can be verified without casting from a real phone to the stue TV.** A launch
  intent that never fires, a `customData` payload the receiver silently ignores, and a correct
  implementation look identical from here. Building it blind would produce a phase marked built whose
  only real acceptance criterion had not been attempted — the exact shape phase 246 was written to
  remove from the advisor.
- Review item 4 adds a real hazard on top: the TV **already has** a Media3 `MediaSession` (R44,
  `RaviloPlayerAndroid.kt`, TV-gated), so this is a reconciliation of two competing sessions, not a new
  one. That is precisely the kind of thing that behaves differently on the device than in a compile.

Nothing else waits on it: 237's admin card shipped without it (its own review carved FR-237-7's status
line into this phase), and 236 already gives the household a working phone → TV path that does not
involve Google at all.

### Closed from the dev review

- **OQ2 — `customData`.** None of the payload (jellystructure item id, position, the casting viewer's
  user id) is media data, so it does not belong in `MediaLoadRequestData`'s media fields. Review item 1.
- **OQ3 — no, and by citation rather than by lean.** `CastService.checkCeiling` returns immediately
  unless `kind == "cast"`, and its own comment records 236 FR-236-9's decision verbatim. The Android TV
  app is `kind = "tv"`, so a Cast Connect play cannot reach the ceiling. Review item 3.
- **OQ1 stays open** and is now sharper: its premise was wrong (the TV *does* have a media session), so
  the question is which session wins, not whether to create one.

### The shape it should take when it is built

Per review items 1 and 2, and unchanged by this decision: **Cast Connect carries the launch and the
transport; the play travels 236's road.** No hand-off redemption — `CastService.redeem` forces a
`cast-` id, the Chromecast display name and `kind = "cast"`, so redeeming from the TV app would mint a
second device row for one physical TV and put it under 218's ceiling. The TV is already an enrolled
device holding its own token; it accepts a phone-supplied user id **only if it already holds a token
for that user**, which is what makes trusting it safe. And a native launch is a **launch observation**,
not an enrolment, most cheaply a field on the status the TV already posts under 236 — which is what
237's carved-out status line would then read.

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
8. *(Moved from 237's acceptance 6, 2026-09-25.)* Settings → Connections → Chromecast's registered
   state shows the *Living room TV opens Ravilo itself when cast to* line only when at least one
   `android_tv` launch is on record (FR-R266-6), never on the strength of the console's settings.

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

## Dev review (2026-09-19, against `main` `b397f5e6`)

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

## Re-dev review (2026-10-08, against `main` `4222ac4c`)

Read against branch `r266-cast-connect` (5 commits, built 2026-10-05, **not merged, 42 commits behind `main`**),
`ravilo-cast/…/Receiver.kt` on `main`, `cast-receiver/index.html`, 308/309/310 as they now stand, Stue TV's codec list
(`/vendor/etc/media_codecs*.xml`, read over adb 2026-10-08) and 30 days of `playback_start_sample`/`playback_qoe`. The
question asked: is Cast Connect the lever that removes most Chromecast stalls? **Yes, for every TV that can run Ravilo,
and it is the largest single one available.** Nine items, three for the owner.

1. **The numbers.** Over 30 days the Ravilo TV app started **1 142** plays at **1.7 s on average**, 1 084 of them under
   3 s, and direct-played **753 of 812**. The Chromecast web receiver records **no start samples at all** (the gap is
   item 6). Its two QoE rows since 308 hold one 29.4 s stall. A cast to a TV that runs Ravilo would get the first
   number instead of the second.
2. **Why the web receiver must transcode what Ravilo on the same TV would not.** `Receiver.capabilities()` declares
   `hlsOnly = true` (every play is at least a remux, a Jellyfin job and its cold start), `supportsDolbyVision = false`,
   and no DTS. Its audio channels come from `canDisplayType`, which gave 2 on the 2026-10-05 cast. Stue TV's own
   decoders, read 2026-10-08:
   - HEVC Main 10 at 4096×2304 up to **60 Mbps**;
   - Dolby Vision `dvhe.dtr`/`dvhe.st`/`dvhe.stn` (single-layer profiles, not profile 7's enhancement layer);
   - AC3, EAC3, DTS, DTS-HD (MTK DSP decoders).

   Ravilo's Media3 path uses all of these and direct-plays the MKV. So on the TVs, R266 turns most casts from
   *re-encode, then adapt* (308/309) into *play the file*, with Ravilo's own playback tracker, QoE, picker and gating.
3. **Who benefits.** The household's two BRAVIAs, and any Google TV device (Chromecast with Google TV, Google TV
   Streamer) once Ravilo is installed there **from the Play Store**. The Play Store is a whitelisted installer, so the
   *Play-installed* release qualifies without test-device registration. Plain Chromecast dongles (3rd gen, Ultra) and
   meidam's receivers (models unknown) stay on the web receiver, which is what 309 is for. **R266 does not replace
   309: it shrinks the set of plays that need it.**
4. **The branch must be rebased before anything else.**
   - Its `69.sqm` (`cast_connect_launch`) collides with `main`'s `69.sqm` (307's publish queue), and `70.sqm` is 308's.
     It becomes the next free number, coordinated with 309's and 310's migrations (each also wants one).
   - `Receiver.kt`, `Cast.kt` and `CastSenderAndroid.kt` changed on `main` since (308's adaptive seed, R245's quiet
     end `b8c67fab`, R372/R378).
5. **A regression the branch would ship: music to a TV running Ravilo.** With `androidReceiverCompatible` on, a
   music cast to Stue TV launches the TV app, which refuses the queue. Today the web receiver plays it.
   `LaunchOptions` is set once per `CastContext`, so one Application ID can't mean "Cast Connect for films, web for
   music". **For the owner, Q1** below.
6. **The receiver itself, while it remains the path for everything else** (these belong in 309's build, noted here
   because they are cast-path findings):
   - **(a) `useShakaForHls` is never set.** CAF v3 plays HLS with its own MPL player unless
     `PlaybackConfig.useShakaForHls = true`, and `shakaConfig` (308's ABR targets, 0df9d193's `bufferingGoal 40`) only
     applies under Shaka.
     - Our signals can't tell the two apart: `BITRATE_CHANGED` and `getStats()` fire for both, so 308's
       "Shaka 4 → 12 → 40" device test did not prove Shaka was playing.
     - 2026-10-06's 29.4 s stall with **0 down-switches** on receiver v1.50-30, which already had the 40 s goal, is
       what MPL would do.
     - First build step: log `playerManager.getPlaybackConfig().useShakaForHls` on every load. Then set it explicitly
       (with `shakaVersion` pinned) and re-test the down-switch.
     - Shaka needs its TS transmuxer for our MPEG-TS segments; Shaka ≥ 4.3 has one built in. Verify on a Chromecast
       before relying on it.
   - **(b) No time to first frame from the receiver.** `playback_start_sample` has no `cast` rows. 309 FR-309-11 must
     cover the receiver, or "fast start on Chromecast" can't be measured.
   - **(c) The receiver's bandwidth estimate has the same default-guess problem as 309 FR-309-13.**
     `getStats().estimatedBandwidth` is the player's prior until segments arrive, so it needs the same sample count.
7. **The gaps the build notes name are real, and two block a release:**
   - The phone remote's subtitles & audio, and *Next episode*, do nothing against the TV app until it speaks
     `CAST_NAMESPACE`. For a Stue TV cast that is a step back from today's web-receiver remote.
   - `position_ms` is ignored (a hand-over starts at the server's resume point, a few seconds behind).

   Build both before release. Neither needs a new string.
8. **Device testing is now possible** (owner, 2026-10-08: Stue TV's debug build `dev.jellystructure.ravilo.debug`,
   the development Cast application). The acceptance can run end to end without touching the Play Store install.
   Verify the release APK on ART (`scripts/verify-release-apk-on-art.sh`) before any release that carries the
   manifest change.
9. **Order.** Rebase → device test on Stue TV (acceptance 1, 3–6) → Q1's music answer → the remote namespace → merge →
   release with 308's Android half (the TV app must declare `hls_adaptive` for its own HLS fallbacks).

**For the owner:**
- **Q1 — Music casts to a TV that runs Ravilo:**
  - (a) a second Cast Application ID used only for music, registered without an Android TV package, so music always
    opens the web receiver **(lean: no regression, one console entry)**;
  - (b) the TV app refuses music (as built: music to the BRAVIAs breaks);
  - (c) give the TV app a music mode first (big, its own phase).
- **Q2 — Priority:** (a) rebase and device-test R266 now, before 309 **(lean: it fixes the TVs outright, and 309 then
  only has to serve dongles and remote viewers)**; (b) after 309; (c) park it.
- **Q3 — Remote parity before release:** (a) the TV app implements `CAST_NAMESPACE` first **(lean)**; (b) release with
  basic transport only and add it later.

## Decided by the owner (2026-10-08, after the streaming re-review)

1. **Priority:** step 2 of the order, right after the hotfixes, 310 and 312. Rebase `r266-cast-connect` onto `main`
   and renumber its migration with 309's and 310's. See `specs/research-reports/ravilo-streaming-plan-2026-10-08.md` for the whole order.
2. **Music casts to a TV running Ravilo (Q1): build a TV music mode first** (against the lean of a separate music-only
   Cast app). R266 does not ship until a TV running Ravilo can play a music cast itself.
3. **The phone's remote must work fully against the TV app before release (Q3):** subtitles, audio and Next episode,
   the same remote whichever receiver plays.
