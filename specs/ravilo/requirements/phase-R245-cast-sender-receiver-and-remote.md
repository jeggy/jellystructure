# Phase R245 — Casting: the receiver, the sender, and the remote in your hand

> The viewer half of **Phase 218**. A Ravilo web receiver runs on the Chromecast as **its own Ravilo
> device**, so the TV keeps playing if the phone dies. The phone is a remote in the literal sense: a cast
> button on every app bar, a mini bar while you browse, and a full-screen remote with pause/play, −10 s /
> +30 s, a draggable seek bar and **subtitles one tap away** — applied on the TV, using the same picker
> component the local player uses.

## Status

`Planned` — written 2026-09-16, **dev-reviewed 2026-09-16 against `main`** (see §Dev review at the
bottom). Built into the mockups 2026-09-16. No viewer-facing change; the receiver's reuse story and
its enrolment dependency on 218 were both made explicit.

**Numbering:** verified against `main` on 2026-09-16 — Ravilo taken through **R243**, admin through
**217**. No `phase-R245-*` file and no `STATUS.md` row for it. Pairs with **218**; follows **R244**, whose
picker sheet and safe-area work this phase reuses. Next free: **219 / R246.**

Design / reference implementation: `design/ravilo/Casting - Directions.html` (+ its 8-page print copy) —
the §B1 flow as nine frames, both remote directions, the seven remote states plus the subtitles sheet, the
mini bar, the notification, the receiver's ten screens and the admin card. Built:
`design/ravilo/Ravilo Mobile.html` + `design/ravilo/mobile/ravilo-mobile-player.css` (`.rc-*`) for the
sender, remote and mini bar; `design/ravilo/Ravilo Receiver.html` for the receiver; strings in
`design/ravilo/ravilo-i18n.js`.

Reuses without change: **R218** (the receiver's loading and buffering moments), **R237** (failure copy),
**R180** + **R195** (the picker), **R222** (the slow-to-start note applies to a cast too), **R110** (the
TV caption style), **phase 182** (the busy state), **R243**'s wordmark-tile fallback rule.

## Current state

No Cast code of any kind. `design/ravilo/Ravilo Mobile.html` had no player and no remote before
2026-09-16. The receiver does not exist.

## The architectural fact that shapes every screen

**The receiver is its own Ravilo device** (phase 218 FR-218-9). It holds its own device token, runs its
own heartbeats, advances to the next episode and honours Skip Intro by itself. Three consequences the UI
must obey rather than work around:

1. The remote is **never** the source of truth. On app start it rebuilds from the receiver — item,
   position, tracks, next-up — never from anything the phone remembered before it died.
2. **Re-connect has two outcomes, and one of them is silence.**
3. Every cast is a Jellyfin encode (a Cast receiver never direct-plays MKV), so R218's waiting states,
   R222's slow-to-start note and phase 182's busy state all apply to casting — and the viewer is still
   never told why.

## Decisions taken (owner, 2026-09-16)

1. **Remote direction 2 · "Now playing"** — a 16:9 art card on a solid ground, with the device chip as the
   header *and* the control. Direction 1 ("Cinema", full-bleed scrimmed backdrop) was rejected: ink on a
   scrimmed photo cannot be guaranteed to clear 4.5:1, and most of this library has no backdrop, so its
   best case is its rarest case.
2. The mini bar **never dismisses while a cast runs**.
3. *Next episode* **skips straight to it**.
4. Casting from inside the local player **swaps straight to the remote**.
5. The receiver's idle screen is the **lit mark and one sentence** — no clock, no artwork, no carousel.
6. *Ended* offers **two actions**.

## Functional requirements — the sender

**FR-R245-1 · A cast button on every app bar.** Home, Detail, Player. The glyph is the **platform's own
Cast mark** — the screen outline open at the bottom-left with a dot and two arcs — in its two standard
forms: the outline while disconnected, and the **filled-screen** form once connected, accent-tinted. A
bespoke glyph is not acceptable: this is the one control in Ravilo whose meaning comes from being
recognised from other apps. Connecting animates the mark rather than substituting a third shape.
**Present only when the server says Chromecast is set up; absent otherwise, never greyed** (218
FR-218-3). No AirPlay affordance, ever.

**FR-R245-2 · The device picker is the platform's.** `MediaRouteButton` on Android and
`GCKUICastButton` on iOS open the *system* dialog. Ravilo does not draw its own discovery UI. Output
Switcher discovery via `MediaTransferReceiver`.

**FR-R245-3 · Connecting is a bar, not a screen.** A thin bar under the app bar reads *"Connecting to
{device}…"*, becomes *"Casting to {device}"*, and retires itself after ~2 s. The app stays fully usable
throughout. No modal, no full-screen interstitial.

**FR-R245-4 · Play casts, and the position goes with it.** While connected, a detail screen's primary
action reads *"Play on {device}"* and hands the resume position over. Pressing the cast button **inside**
the local player hands the *current* position over, stops local playback and swaps straight to the
remote — one act, not two.

**FR-R245-5 · Re-connect on app start — a requirement, not a nicety.** When the app starts or returns
while a cast is running, the Cast SDK's session resumption re-attaches, the cast glyph animates and one
bar reads *"Reconnecting to {device}…"*. Then **exactly one of two things happens**: the session is alive
⇒ the mini bar appears with the **live** position read from the receiver; or the receiver has finished or
is gone ⇒ **nothing is shown at all** — no bar, no toast, no error — and the cast glyph returns to idle.
Telling the viewer their cast ended is a notification about something they watched happen.

**FR-R245-6 · The mini bar (64 px).** Above the bottom safe area on every screen while casting:
thumbnail, title, ▶/❚❚, the device name, and a hairline progress bar. Tap opens the remote. **Swipe down
does nothing** — while a cast is running the mini bar is the only route back to the remote, so dismissing
it would dismiss the session.

**FR-R245-7 · The remote.** Portrait; landscape centres the same layout. Device chip in the header (tap to
switch device or stop) · 16:9 art card · kicker + title · *"Playing on {device}"* · draggable seek bar
with times · **−10 s · ▶/❚❚ · +30 s** large and centred · a footer of *Subtitles & audio*, *Next episode*
(series only) and *Stop casting*. Volume is the phone's hardware keys with a transient pill; **no
on-screen slider**, because on a cast volume belongs to the TV and a slider invites a fight with the TV's
own remote. The art card falls back to a **wordmark tile** with no redraw (R243's rule) — there is no
state in which ink sits on a picture.

**FR-R245-8 · Subtitles and audio from the remote.** *Subtitles & audio* opens **the same sheet component
the local player opens** (R244 FR-R244-10) — R180/R195's two levels, the same flags, the same badges, and
the same S · M · L **Subtitle size** row. The only difference is one line: *"Applies on {device}"*. The
choice is sent to the receiver and applied on the TV. One component, two destinations; a second
implementation is forbidden.

**FR-R245-9 · The remote's seven states.** *Playing* (position ticks from the receiver's reports, never a
local clock) · *Paused* (art dims, so the state reads without reading a button) · *Buffering on the TV*
(R218 moment C borrowed: the spinner stands in for the play button; no explanation, because the viewer
cannot act on one) · *Next-up mirrored* (the **receiver** owns the countdown, so cancelling must be sent,
not assumed) · *Receiver unreachable* (*"Lost contact with {device}"* + *"It may still be playing. Ravilo
cannot reach it to check."*, one action *Try again*, and the transport **greyed** because pressing it
would be a lie) · *Server busy* (phase 182's sentence plus the **elapsed wait** — the one number in the
whole viewer half) · *Ended* (two actions; nothing auto-plays after a season's last episode).

**FR-R245-10 · Leaving the remote never stops the cast.** System back / edge swipe returns to the app with
the mini bar up. Stopping is only ever explicit, from the footer or the device chip, and needs **no
confirmation dialog** — it is one tap to start again.

**FR-R245-11 · Lock screen and notification.** The Cast SDK's own notification
(`CastOptions.NotificationOptions`): artwork, title, ▶/❚❚, ±10/30 s, stop. The layout is the platform's;
ours is the artwork crop — a **landscape still, not a poster**, which crops to nothing at 64 px — and the
second line, which carries the device name. **Local** playback still has no notification at all (R193).

**FR-R245-12 · Strings.** Fourteen tabled strings × en/da/fo plus `cast_applies_on` found during the
build: `cast_ready`, `cast_connecting`, `cast_connected`, `cast_reconnecting`, `cast_playing_on`,
`cast_paused_on`, `cast_play_on`, `cast_stop`, `cast_applies_on`, `cast_lost`, `cast_lost_sub`,
`cast_no_server`, `cast_no_server_sub`, `srv_busy`, `srv_busy_sub`, `cast_waiting`. `{device}` is the
Chromecast's own name as the viewer named it. Danish and Faroese are drafts; the shipped table wins
wherever a string already exists.

## Functional requirements — the receiver

**FR-R245-13 · It is a Ravilo device, not a screen.** CAF web receiver; probes `canDisplayType` at runtime
for HEVC / VP9 / HDR and sends that as its capabilities to `/api/tv/playback/start`; plays the returned
`hlsUrl` with the ticket's VTT sideloads as text tracks. It assumes **no codec** — an old stick simply
gets an H.264 1080p encode. It reports progress and QoE like any device and honours phase 180 teardown.

**Dev review — the Kotlin side of this is genuinely reusable; the token story needs stating once.**
`shared` compiles to plain JS via `js(IR) { browser() }` (shipped since R189 for Tizen), so the receiver
uses `TvApiClient` rather than a hand-written fetch layer, and `R216`'s capability reporting and `R222`'s
note resolution come with it. Two things to make explicit, because the receiver is the first Ravilo
device that is a web page rather than an app:

- **It authenticates with a Ravilo `device_token`, never a Jellyfin token.** Its `ravilo_device` row
  holds `jellyfin_user_token` server-side, exactly as a TV's does, and every Jellyfin call is made by
  jellystructure on its behalf. This is what keeps the design consistent with 141/175, and it is the same
  point 218's review asks that phase to state.
- **`StreamTicket.hlsUrl` already carries `api_key=` in the query**, so the receiver fetches media with
  no headers — which is what makes a plain `<video>`/MediaPlayer receiver viable at all. That is a
  property of the existing ticket, not something this phase adds, and it must not be "tidied up" later
  without breaking the receiver.

**FR-R245-14 · It advances and skips by itself.** Next-episode auto-advance and Skip Intro run **on the
receiver**, because it reads the same detail payload and segment markers the TV app reads. The phone
mirrors; it does not drive.

**FR-R245-15 · Ten screens, none interactive.** Idle (lit mark + one sentence) · loading (**R218 moment B
verbatim**) · playing (video only) · paused-or-seeked (a low overlay for ~3 s: kicker + title, progress,
time, ❚❚ glyph, then gone) · buffering (**R218 moment C**) · captions (**R110**: white, black outline, size
S/M/L from the phone) · next-up (the card as on the TV) · can't-reach-the-server · server-busy (with the
elapsed wait) · ended (**straight back to idle** — there is no "thanks for watching" screen). There is no
chrome to *show*, because nothing can focus it.

**FR-R245-16 · It never talks about settings**, and never names a product, protocol, codec or status code
— the same rule the TV app follows (R216/R234), easier here because there is nothing to press.

**FR-R245-17 · Light enough for a 2013 stick.** No artwork on idle, no heavy effects, no fonts beyond the
three the app already loads. A Chromecast idles for hours; a 1st-gen stick has very little memory.

## Non-goals

- **Cast Connect** on the Android TV activity — the end state, a later phase.
- **Our own device picker.** Redesigning the platform dialog would be a lie about what ships.
- **An on-screen volume slider.**
- **A receiver UI of any kind.** Nothing on that screen is pressable, because nothing is pointed at it.
- **AirPlay**, ever.
- **"Play on Stue TV"** (phase 111's remote-play routes under device-token auth). Genuinely useful in a
  house where every screen runs Ravilo, and arguably the better "cast" here — but it is its own phase, and
  it is drawn beside the cast button in the second design round so the two affordances that compete for
  the same corner are decided together.
- **Background audio, PiP, offline downloads, tablets** (see R244).

## Acceptance

1. Force-stop the phone mid-cast: **the TV keeps playing.** Reopen the app: the reconnecting bar appears,
   then the mini bar with the *live* position, and the remote opens on the receiver's state — including a
   subtitle track changed from the TV side.
2. Let the cast finish, then reopen the app: **nothing is shown.** No bar, no toast, no error.
3. Subtitles are reachable in **one tap** from the remote, the sheet is the same component as the local
   player's, and the choice visibly changes the captions on the TV.
4. Pressing cast inside the local player resumes on the TV **at the same second** and the phone is on the
   remote, not the player.
5. The art card renders correctly for a title with **no backdrop** — a wordmark tile, not a gradient with
   white text on it.
6. With the receiver unreachable, the transport is greyed and the copy claims only that Ravilo cannot
   reach it — not that playback stopped.
7. A third concurrent cast shows the busy sentence with an elapsed wait on both the TV and the remote.
8. No viewer-facing string in this phase names a product, protocol, codec, bitrate or status code. Google
   appears only on the admin card (218 FR-218-6).
9. The acceptance device is **the parents' old Chromecast stick on an LG TV, cast to from an iPhone**.

## Open questions

1. **The stick's generation** (see 218 open question 1). If a CAF v3 receiver will not launch on it, the
   acceptance device changes — the design does not.
2. **Whether the remote should show the slow-to-start note** (R222) for a cast. It is a per-device
   expectation, and the Chromecast is now a device with its own history, so the note *could* be resolved
   for it. Not drawn, because the first cast has no history to resolve from.
3. **Position reconciliation.** The remote is optimistic on drag and resolves disagreements to the
   receiver's number. What "resolves" means visually — snap, or ease — has not been decided, and a snap
   after a long drag will read as a bug.
4. **Re-connect while a *different* item is playing** than the one the phone last saw. The rule is "rebuild
   from the receiver", so it should just work; it is called out because it is the case most likely to be
   implemented from the phone's memory by accident.
5. **What the mini bar shows during a receiver-side stall.** It has a play/pause button and a progress bar,
   and neither is true mid-stall. Probably the bar stops and the button stays; not drawn.
6. **Whether the cast glyph belongs on the Live TV player.** Casting live TV works architecturally, but the
   receiver's overlay has no seek bar to show and the design round did not cover it.

## Dev review (2026-09-16)

Reviewed against `main` at `080364b4`, alongside its server half **218**. **Nothing the viewer sees
changed.** The sender flow, the mini bar, the remote's seven states, the shared picker component and the
receiver's ten screens all survive unchanged. Three notes.

**The reuse claim is real.** `shared`'s `js(IR)` target has shipped since R189, so the receiver is a
Kotlin client rather than a JavaScript rewrite, and `StreamTicket.hlsUrl` already carries its own
`api_key=`, so media fetches need no header plumbing on a platform that makes headers awkward.

**The dependency on 218 is heavier than the cross-reference suggests.** FR-R245-13's "it is a Ravilo
device" rests entirely on 218 FR-218-9's enrolment, and that review found FR-218-9 has **no existing
mechanism to build on** — phase 141 retired the code-based pairing flow. Worse, if a Chromecast does not
preserve the receiver page's storage between sessions, enrolment becomes a per-cast round trip rather
than one-time setup. That does not change any frame drawn here, but it means **FR-R245-5's re-connect
path may have to run an enrolment, not just a reconnect**, and the "one of the two outcomes is silence"
rule has to hold even when the silent case is reached via a failed enrolment. Settle it on the real stick
with 218's open question 1.

**Open question 2 can be narrowed now.** Whether the remote should show R222's slow-to-start note for a
cast is answerable in principle: `PlaybackNoteResolver` is per `(device, file)`, and the receiver is a
device with its own row, so a note *would* resolve for it once it has history. The spec's own reason for
not drawing it — the first cast has no history — is exactly `basis: "expected"`, which R222 already
defines a sentence for. So the real question is narrower than written: not "can it", but whether an
expectation about the TV's startup belongs on a phone screen the viewer is holding. Recommend leaving it
undrawn for round 1 and revisiting once a receiver has real `playback_start_sample` rows.

**Unchanged:** open questions 1, 3, 4 and 5 stand exactly as written. Question 3's position-reconciliation
concern is the one most likely to produce a bug that reads as a bug, and it has no server-side answer —
it is a motion decision.
