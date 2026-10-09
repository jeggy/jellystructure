# Phase R266 — Cast Connect: the TV app takes the cast, the web receiver takes the rest

> R245 gave the phone a sender and the Chromecast a web receiver at `/cast/`. The household also has a
> TV that runs the Ravilo Android TV app, and a cast aimed at *that* TV must land in Ravilo proper —
> the skin, the two-level subtitle picker, R216 QoE, the playback tracker, kids gating — not in a web
> page that has none of it. Google's mechanism is Cast Connect. This phase makes the TV app a Cast
> Connect receiver, sets the one sender flag that permits it, and keeps R245's remote identical
> whichever receiver answered.

## Status

`⚠ Partial` — **built 2026-10-05 on `r266-cast-connect`, rebased onto `main` and completed with R380 on 2026-10-08
(branch `worktree-agent-a97c82f5673ccb803`, not merged)**: the phone's full remote against the TV app (subtitles, audio,
Next episode, the queue) and the TV's music mode are in; **a film and a music cast were played live on Stue TV** from a
host-side Cast Connect sender, not yet from a real phone (see *Build notes (2026-10-08)*). Released only with R380.

*The status as first written:* `Planned` — design-authored 2026-09-18, **dev-reviewed 2026-09-19 against `main` `b397f5e6`**, **not
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
## Build notes (2026-10-05)

**Built on branch `r266-cast-connect` (not merged to `main`), compile- and unit-tested only. Nothing here has run on a
device.** The build follows the dev review's shape, not the original FRs: Cast Connect carries the launch and the
transport; the play travels 236's road; no hand-off is redeemed; a launch is an *observation*, not an enrolment; R44's
media session is the only session.

### What was built

- **Sender flag (FR-R266-1).** `RaviloCastOptionsProvider` sets
  `LaunchOptions.Builder().setAndroidReceiverCompatible(true)` (`ravilo-ui/…/seams/CastSenderAndroid.kt:95`). The web
  sender is unchanged (the Wasm build has no Cast sender of its own).
- **The casting viewer on every LOAD (review item 1).** `CastLoadData.userId` (`user_id`, `shared/…/tv/CastMessages.kt`),
  filled in by `CastController.load()` and `moveLoaded()` from the controller's `userId`
  (`ravilo-ui/…/components/Cast.kt:331`). It rides where the rest of `CastLoadData` already rides — the media's
  `customData` (R359). A relay of someone else's session (R370) carries none and the TV refuses it. The web receiver
  ignores the field and still enrols by `code`.
- **The receiver (FR-R266-2).** `ravilo-android/…/android/castconnect/CastConnectReceiver.kt`: `CastReceiverContext.initInstance`
  in the new `RaviloApplication` (TV only — a `UiModeManager` check; the phone in the same APK never becomes a
  receiver), `RaviloReceiverOptionsProvider` named by the manifest's
  `com.google.android.gms.cast.tv.RECEIVER_OPTIONS_PROVIDER_CLASS_NAME`, `start()`/`stop()` on the TV activity's
  `onStart`/`onStop`, `mediaManager.onNewIntent(intent)` from `onCreate` and `onNewIntent`. The TV `MainActivity`
  gains the `com.google.android.gms.cast.tv.action.LAUNCH` and `…action.LOAD` filters and becomes
  `launchMode="singleTask"`, so a cast to a TV already running Ravilo arrives through `onNewIntent` instead of
  stacking a second activity. Dependency `com.google.android.gms:play-services-cast-tv:21.1.1` (the newest published;
  its `play-services-cast 21.5.0` is superseded by the framework's 22.1.0 without conflict). One R8 keep rule for
  `ReceiverOptionsProvider` implementations (named only in a `<meta-data>` value).
- **A LOAD is a Ravilo play (FR-R266-3, review item 1).** The load callback parses `CastLoadData` from the media's
  `customData` (`castConnectPlayFromJson`, `ravilo-ui/…/seams/CastConnect.kt`) and hands it to
  `CastConnectInbox`; the app's root (`RaviloApp.kt:922`) decides and answers. A LOAD with no viewer, no item, a music
  queue (the TV app has no music mode) or no parseable payload is refused with `MediaError.ERROR_REASON_INVALID_REQUEST`
  — never a Jellyfin URL, never a silent blank.
- **Whose profile (FR-R266-4, review item 1).** `castConnectVerdict`: the phone-supplied user id is trusted **only if
  `MultiTokenStore` already holds a token for it** — `PLAY` when it is the active profile, `SWITCH_THEN_PLAY` when it
  is another held profile, `REFUSE_NO_TOKEN` otherwise, `REFUSE_NOT_READY` while someone is signing in on the TV. A
  switch remembers the TV's own profile and switches back once the player closes (`RaviloApp.kt:961`). A cast that
  arrives while a player is open replaces it (the open player is popped first, so it saves its position as on Back).
- **The play itself** is a plain `Dest.Player(itemId, title, kicker, …)` — the same destination 236's `play_item`
  pushes — so 180 teardown, R216 QoE, `requireVisible()`, the per-user ACL and kids gating all see an ordinary play.
  As with `play_item`, the start position is the server's own resume point for that viewer; the phone's `position_ms`
  is parsed but not yet used (see *Not built*).
- **One media session (review item 4).** `RaviloPlayerAndroid.mediaSessionRef` now reports every create/release
  through `TvPlayerSessionHooks` (`ravilo-ui/…/seams/TvPlayerSessionHooks.kt`; setter at
  `RaviloPlayerAndroid.kt:285`), and the receiver hands that session's token to `MediaManager.setSessionCompatToken`
  (`MediaSessionCompat.Token.fromToken(session.platformToken)`) — no second, cast-only session. A release that is not
  of the current session (the previous player's, disposed after the next built its own) changes nothing.
  **R192's toggle:** `setSessionActive(false)` returns early while `TvPlayerSessionHooks.castDriving`
  (`RaviloPlayerAndroid.kt:473`) — set when the session of the player a cast opened appears, cleared when that session
  is released or the receiver stops. Leaving playback (Back, a 180 teardown, `releaseEngine` on ON_STOP) still releases
  the session, and the receiver then broadcasts a media status with no session behind it (acceptance 6).
- **The launch observation (review item 2, acceptance 8).** The TV did *not* already post a status under 236 (only the
  receiver-only screen app does), so it now posts one: `TvApiClient.reportCastConnectLaunch()` sends
  `ScreenStatus(cast_connect_launch = true)` to `POST /api/tv/playback/status` once a LOAD has been accepted. The route
  (`TvRoutes.kt:758`) records it via `CastService.recordCastConnectLaunch` — only for `kind == "tv"` devices — in the new
  `cast_connect_launch` table (`CastConnectLaunch.sq`, migration `69.sqm`, one row per TV), and a status that carries
  only the observation (`isLaunchObservationOnly`) never reaches the tracker or a subscribed remote.
  `ChromecastStatus` gains `tv_opens_ravilo_at`/`tv_opens_ravilo_name`, and Settings → Connections → Chromecast shows
  *"<TV> opens Ravilo itself when cast to · last …"* only when one is on record (`Settings.kt:3455`), never from the
  console's settings.
- **Acceptance 5 by construction.** Nothing redeems a hand-off on the TV, so no `cast-` device is ever minted for it;
  `CastServiceTest` asserts the TV stays one device after a recorded launch.
- **No new viewer-facing strings (FR-R266-8, acceptance 7).** Logs only. The admin line is the admin's (English) card.

### Debug builds cast with the development Cast application (owner, 2026-10-05)

The owner registered a second Cast application for development whose Android TV package is
`dev.jellystructure.ravilo.debug`. A **debug** build casts with it instead of the server's `chromecast.app_id`:

- `ravilo-android/build.gradle.kts` — `buildConfigField("String", "CAST_DEV_APP_ID", …)` on the debug build type, read
  by `castDevAppId()` from the Gradle property **`-PraviloCastDevAppId=…`**, else **`raviloCastDevAppId=…` in the
  root `local.properties`** (gitignored — the id is never committed), else empty. Anything but 8 hex characters reads as
  empty. The release build type sets it to `""` unconditionally.
- `RaviloApplication.onCreate` copies it into `CastAppIdOverride.devAppId`; `effectiveCastAppId(server, dev)` is applied
  in the one place the server's id enters (`CastController.appId`'s setter, `Cast.kt:106`), so the sender's
  `setReceiverApplicationId` and the route discovery (`rememberCastRoutes(cast.appId, …)` → `categoryForCast`) both
  use it; `RaviloCastOptionsProvider` also falls back to it before the first config load. A server with casting off
  (no id) still shows no cast button — the override never turns casting on.
- Empty ⇒ exactly today's behaviour. Verified: a debug build with `raviloCastDevAppId` set compiles
  `BuildConfig.CAST_DEV_APP_ID = "EA91BAE4"`.

### Tests

`CastConnectTest` (ravilo-ui commonTest: the LOAD → play parse, refusals incl. music and a Jellyfin-URL-shaped payload,
the held-token verdict, the inbox hand-over/replace/timeout, the debug app-id rule), `CastConnectWireTest` (shared:
`user_id` on the wire and an older LOAD without it; the observation-only rule), and a `CastServiceTest` case (only a
`kind = "tv"` launch is recorded; the status carries it; one device for the TV).

### Not verified — everything that matters happens on a device

- That the Cast SDK launches the **debug** TV app for the development application at all (test-device registration,
  sideload/whitelisted-installer rules), and that a LOAD actually reaches `MediaLoadCommandCallback.onLoad`.
- That `customData` arrives on `mediaInfo` as the phone puts it (the code also reads the request's own `customData`).
- That `MediaManager` mirrors Media3's session correctly (play/pause/seek/stop from the phone's remote, position and
  state back) once handed the token, and what the phone's remote shows when the session is released (acceptance 6).
- That R192's guard and the `singleTask` change behave on the BRAVIA (display standby, Back from the player, a second
  cast while one plays).
- Release builds: R8 compiled; ART verification of the release APK (`scripts/verify-release-apk-on-art.sh`) needs a
  device and was **not** run.

### Not built (known gaps)

- **The remote's richer half against the TV app.** R245's remote reads tracks, next-up and the episode list from the
  web receiver's own messages on `CAST_NAMESPACE`; the TV app does not speak that namespace yet, so against Cast
  Connect the phone gets the SDK's standard media status only (play state, position, title). Subtitles & audio from
  the remote and *Next episode* (FR-R266-5) therefore do nothing on the TV app until it implements the namespace
  (`CastReceiverOptions.setCustomNamespaces` + `CastReceiverContext.setMessageReceivedListener`).
- **Music to the TV.** With `androidReceiverCompatible` on, a music cast to a TV that runs the Ravilo app launches the
  app, which refuses the queue (it has no music mode) — where today the web receiver would play it. Worth an owner
  call before release: refuse (as built), or have the TV app hand a music LOAD back to the web receiver.
- The phone's `position_ms` (a hand-over mid-film may start a few seconds behind where the phone was).

## Build notes (2026-10-08)

**Rebased onto `main` `97b9e588`** (branch `worktree-agent-a97c82f5673ccb803`; the R266 commits cherry-picked). The
`cast_connect_launch` migration moved **69 → 72** (main had taken 69–71); **`main` has since taken 72 and 73** (R381 and
the backend batch), so it becomes **74** when this branch is merged. `MusicEditionsStoreTest`'s rewind drops the table.

### The known gaps of 2026-10-05, closed

- **The remote's richer half (FR-R266-5, owner decision 3).** The TV app speaks Ravilo's Cast channel through the
  shared module R380 introduced (`shared/…/tv/CastChannel.kt`, the same `castChannelStep` the web receiver now uses):
  `CastConnectReceiver` sets the custom namespace and a message listener, and `TvCastChannel` answers `status` with the
  playing film's title, kicker, audio and subtitle lists, selections, subtitle size and next episode, and acts on
  `audio`, `subtitle`, `subsize` and `episode_next` through the player's own pick path (`CastVideoSource` /
  `CastChannelVideoHost`, `PlayerScreen.applyPick`). The film's `MediaInfo` is rebuilt with **no Cast media tracks**, so
  the phone's picker sends its picks on the channel rather than as a standard track selection (which the TV maps too).
- **Music to the TV (owner decision 2):** R380 — a music LOAD plays in the TV app's own music mode.
- **`position_ms`:** a film LOAD now starts where the phone was (`Dest.Player(startAtMs)`).

### Verified live (Stue TV, 2026-10-08)

Debug build `dev.jellystructure.ravilo.debug` (over the R381 fork's debug build; the Play Store app untouched), the
development Cast application, a host-side Cast Connect sender (LAUNCH with `supportedAppTypes` WEB + ANDROID_TV, then
the phone's own LOAD shape and channel messages); the phone itself could not be driven.
- The Cast SDK **launches the debug TV app** for the development application, and a LOAD reaches
  `MediaLoadCommandCallback.onLoad` with `customData` intact (logcat: `Cast Connect intent …LOAD`, `load of … : PLAY`).
- The film started at **1:00, the phone's `position_ms`**; the receiver handed the player's Media3 session to
  `MediaManager` (`media session handed to Cast Connect`) and the standard media status followed it (BUFFERING →
  PLAYING with the position).
- The channel's `status` listed the film's real audio track and its Danish subtitle; `subtitle 0` turned it on
  (`selected_sub: 0`), `subtitle -1` off; quitting the app released the session (`media session released`).
- Music: see R380's build notes (launch from cold, queue status, next, stale refusal, lyrics, the TV remote's keys,
  Back → Home with the pill, Home stops).
- The test's resume point on the film (and the songs' plays) were reset in Jellyfin afterwards.

### Still not verified

- A cast from a real phone: the remote's screens following the TV (play state, tracks sheet, Next episode).
- Acceptance 6 on the phone (what its remote shows when the TV's session is released), a second cast while one plays,
  display standby, the admin card's *opens Ravilo itself* line (the observation is posted; the card was not opened).
- Release build on a device: R8 + `check-player-dex.sh` pass (245 registers), `verify-release-apk-on-art.sh` not run.
- Soveværelse TV (not tested).

## Triage (2026-10-09, against `main` `9ea5da3c`)

- **Code: nothing left that a desk can find** — merged (38ae8c6e), deployed. **Owed on devices:** a cast from a real
  phone (the remote following the TV), acceptance 6, a second cast while one plays, display standby, the admin
  card's line, `verify-release-apk-on-art.sh` on a device, Soveværelse TV.

## Live, 2026-10-09 (Soveværelse TV debug build 1.50-119 + Pixel 9 Pro debug, dev stack v1.50-118)

Stue TV was withdrawn by the owner mid-session; everything below ran on **Soveværelse TV** (BRAVIA 4K VH2, debug app
`dev.jellystructure.ravilo.debug`, development Cast app) with the Pixel as a real phone.

- **Acceptance 1 — passed, both roads.** (a) Ravilo app *not running* on the TV: the phone's *Play on a TV* →
  Soveværelse TV went through the Cast SDK; the server logged *Cast Connect: Soveværelse TV … took a cast in the
  Ravilo app*, the TV app launched and played under the casting viewer from the phone's own place (26.4 s), direct
  play. The phone opened R245's cast remote (10 s / 30 s, *Audio & Subs*, *Stop casting*). (b) Ravilo app *running*:
  the row is the merged TV-app row (R380 decision 2), so the start went through the server (R372 move, 2 s back:
  phone at ~42 s → TV at 40.1 s) and the phone shows the R369 session remote.
- **Acceptance 4 — passed.** *Audio & Subs* listed the TV player's tracks over the Cast channel (Off · Dansk ·
  English · Hrvatski · Srpski, *Sound described* lines, Subtitle size, *Applies on Soveværelse TV*); Dansk
  was applied on the TV within ~3 s (the sidecar VTT loaded, Danish lines on screen).
- **Play state follows both ways:** TV remote Play/Pause → the phone's mini bar read *Paused on Soveværelse TV*;
  the mini bar's play → TV state 3. Session remote: pause/play and a seek (to 27:13, our encoder restarted at
  segment 815, first segment 1.13 s) reached the TV; the phone's clock caught up within ~10 s.
- **A second film while one plays — passed:** *Play Again* on another film asked *Already playing on Soveværelse
  TV · Hypnotic* → *Play on Soveværelse TV instead* replaced it (stop written for the first, the second direct-played).
- **Acceptance 6 — passed:** Back on the TV ended the session; the phone's remote read *Stopped on Soveværelse TV*
  and the bar dropped the film.
- **Standby during a cast — passed (silent):** KEYCODE_SLEEP on the TV: the app wrote its stop at 103.8 s, closed its
  events socket, and the phone's cast mini bar went away with no error.
- **Findings (not fixed):** (1) the film remote reached through the *server* road (app already running) has no
  *Audio & Subs* — owner decision 1 of R380 asks for the same remote against anything; only the Cast road has it.
  (2) The app-bar cast glyph on a detail page opens the sheet join-only (FR-R265-7): tapping a free TV there does
  nothing at all, with no hint — a viewer reads it as broken. (3) TV sign-in: with the system keyboard up, *Cancel*
  draws over the *Sign in* button.
  **Fixed 2026-10-09:** (1) — see R369 *Found live 2026-10-09* (the TV app reports its film tracks to the server, and
  the session remote's *Audio & Subs* reaches the TV's player); (2) — R265 FR-R265-7a (the page's title is what the
  glyph starts; elsewhere the sheet says why nothing started); (3) — R349 *Found live 2026-10-09* (Cancel is part of
  the form, under Sign in).
- **Finding (not fixed):** after standby during a cast, opening Ravilo again (LEANBACK launcher) brought the player
  back and **played on** from the stop's place (1:43) with nobody asking — it should come back paused (or not at all).
  **Fixed 2026-10-09 in R292 (FR-R292-8a):** a return after the screen went off is a start **paused** at the place.
- **`scripts/verify-release-apk-on-art.sh` not run:** it uninstalls `dev.jellystructure.ravilo`, and every device here
  (both TVs, the Pixel) carries the Play Store build — run it on CI's emulator only.
