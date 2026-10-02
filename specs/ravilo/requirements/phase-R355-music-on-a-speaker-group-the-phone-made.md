# Phase R355 — Music on a speaker group the phone made: the phone names the group, and its speakers are this session

> Owner, 2026-10-02 14:35: Ravilo (debug build) on the Pixel 9 was casting a song to the Gæsteværelse speaker; in
> Android's media output panel (*Højttalere og skærme*) they pressed ⊕ next to **Stue** to add it. "It worked for a
> short bit", then stopped. Make sure casting music to several speakers at once works properly.

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed, **device-tested on the Pixel 9 against the Stue and Gæsteværelse speakers**. Written 2026-10-02 (dev-authored) from a device investigation the same afternoon, against `main`
`f98f6c52`. Number checked free on `main` and in every worktree (Ravilo specs top at R354). **Amends** R324
(FR-R324-1/2: the sheet's rows; FR-R324-4: the device name the phone shows) and R353 (FR-R353-5's *Play* after a stop
on the device). Android only; common decisions in `:ravilo-ui` commonMain. No wire change, no new string, no receiver
or backend change.

## What Google Cast does with a speaker added to a music session (seen 2026-10-02)

Read on the devices themselves over the Cast protocol (the speakers' own endpoints, their multizone status, and the
Ravilo receiver's log channel), with the backend log and the phone's logcat beside it, both directions (start on
Gæsteværelse and add Stue; start on Stue and add Gæsteværelse):

1. **Stream expansion keeps the same receiver.** Pressing ⊕ makes a *dynamic group* — a virtual Cast device named
   *{first speaker} + 1*, hosted by the speaker that was playing (the leader) on a port of its own (`10.10.x.y:32xxx`).
   The running Ravilo receiver page is **moved** onto that endpoint, alive: no relaunch, no `LOAD`, no
   `RESUME_SESSION`, no new enrolment, no new ticket. Its `current`, queue, events socket (R354), Jellyfin bridge (299),
   progress reports and the song it is playing all carry on. The leader's own endpoint then runs Google's *multizone
   leader* app (`531A4F84`), each added speaker the *multizone follower* app (`705D30C6`); both show Ravilo's name and
   the receiver's status text. The group's multizone status reads `appAllowsGrouping: true,
   streamTransferSupported: true`.
2. **Nothing is launched on the added speaker**, so there is never a second Ravilo receiver for one playback: no
   second events socket and no second Jellyfin session to fight over the item. The backend sees nothing at all when a
   speaker is added or removed.
3. **The sender's Cast session does not change** either: the same session id, the same `RemoteMediaClient`; only the
   session's device name changes (*Gæsteværelse* → *Gæsteværelse + 1*, `Cast.Listener.onDeviceNameChanged`).
4. **Removing a speaker** (its ✓ in the panel) moves the receiver back to the leader's own endpoint, again alive,
   playing on. The leader itself cannot be removed from the panel (it has no ✓), and tapping a speaker's *name* to move
   the session there is greyed out (*i*): Ravilo does not declare `STREAM_TRANSFER`, so Cast offers expansion only,
   never a transfer (see Open questions).
5. **Verified working with Ravilo as it is** (production receiver `v1.48-88`, debug app `1.48-82`): both directions,
   with the app on screen and in the background; the next song loads by itself at a song's end while grouped; the
   phone's *next* reaches the group; the queue playing out hands the song back (R353 FR-R353-5) and *Play* sends it
   to the group again; the Jellyfin dashboard's pause/unpause reaches the grouped receiver (299/R354); removing the
   added speaker keeps the music on the first.

**The owner's 14:35 session**, read back from the backend log: the group played the song to its end (14:38:21);
the receiver then reported two stops and closed its events socket as *idle* — the receiver's own end-of-queue path
(`musicEnded`), not a failure and not the group. Nothing in any log shows the group breaking; *it stopped* matches the
queue ending on that song. Logs from the 22 s after the ⊕ press were no longer in the phone's buffer.

## What is wrong (and fixed here)

**(a) The phone names one speaker while two play.** The mini bar reads *{artist} · Gæsteværelse*, the Playing page
*Playing on Gæsteværelse*, while the group plays on both. `CastSenderAndroid` read the device name once, at the
session's start, and never listened for its change; and Cast's own new name (*Gæsteværelse + 1*) would not say which
speaker joined.

**(b) *Play on…* treats the group's speakers as strangers.** With the group playing, the sheet read the leader as
*Speaker · Ready* or *Playing {title}* depending on which name the session carried, and the added speaker as
*Speaker · Busy · Caster: {song}* (the follower's status line, *Caster* is Danish for *Casting*) or *Playing Ravilo*.
Tapping it asked *Stop … and play here?* and, confirmed, ended the group and started a new session on that one
speaker — the opposite of what the row's own music was doing.

**(c) *Play* after a queue played out could send the queue twice.** In the owner's session two hand-offs left the phone
22 ms apart at 14:38:31 (two codes redeemed, two tickets for the same song). `MusicCast.resumeOnDevice` launches the
hand-off and returns at once; until the receiver's first report arrives the phone is not *linked*, so a second press
(or a second caller) sent the queue again.

## Requirements

**FR-R355-1 — The session's speakers, from the platform.** The Android sender reads, from MediaRouter, the routes the
session's (dynamic group) route holds, and exposes their names as `CastSender.members` (empty when the session plays on
one device or on a group made in Google Home, which is one route; empty on every other platform). It is refreshed on
every route change and on `Cast.Listener.onDeviceNameChanged`. Listening uses MediaRouter's unfiltered events with
**no** discovery request (R293: off screen, nothing scans).

**FR-R355-2 — The phone names the group.** The device name every screen shows (mini bar, Playing page, ⋯ block, the
cast remote, the sheet's *Stop casting* context) is `castSessionName(sessionName, members)` (commonMain): one or no
member ⇒ the session's own name, unchanged; two or three ⇒ the speaker that was playing first, then the others in the
platform's order, joined with ` + ` (*Gæsteværelse + Stue*); four or more ⇒ the first speaker and how many more
(*Gæsteværelse + 3*, Cast's own form). The first speaker is the member whose name the session's name is or starts
with (*Gæsteværelse + 1* → *Gæsteværelse*); when none matches, the platform's first. No new string: speaker names are
the household's own, and ` + ` is Cast's.

**FR-R355-3 — The group's speakers are this session in *Play on…*.** A route is *connected* when it is selected, when
its name is the session's name, or when it is one of the session's members (`castRouteInSession`). A connected speaker
reads *Speaker · Playing {title}* like the session's own row, asks nothing, and a tap only closes the sheet (R324's
connected rule). *Stop casting* stops the whole group (it ends the one session).

**FR-R355-4 — One hand-off per press.** After `resumeOnDevice` sends the phone's queue to the device, a second
`resumeOnDevice` within 5 s, while no live report has arrived, sends nothing and answers *handled* (the engine does not
start the song on the phone either). A live report, the link dropping or 5 s passing clears it
(`castResumeInFlight`, commonMain).

## Out of scope

Moving the session to another speaker from Android's panel or Google Home (*stream transfer*) and removing the
first speaker from a group: both need the receiver to declare `STREAM_TRANSFER` and to survive `RESUME_SESSION` on a
device that may hold no token (Open question 1). Video: Cast groups audio only. The mini bar's and the notification's
Google-drawn *Caster til {device}* line (the Cast SDK's own).

## Open questions

1. **Stream transfer.** Declaring `STREAM_TRANSFER` (receiver) and `setSessionTransferEnabled(true)` (sender) would let
   a viewer move the music to another speaker from the panel or Google Home, and drop the first speaker from a group.
   The destination runs a *new* receiver page on another device, so the moving receiver must hand it a way in: lean —
   the `SESSION_STATE` interceptor mints a fresh hand-off code with the receiver's own device token
   (`POST /api/tv/cast/handoff` already accepts any device session) and puts it, with the queue and place, in
   `loadRequestData.customData`; `RESUME_SESSION`'s `LOAD` then enrols as a sender's would (300). A receiver deploy;
   untestable without one.
2. A Google Home speaker group (a static group) appears in *Play on…* as a group route only when the platform lists
   it; none was listed on 2026-10-02 though both speakers are in one. Unverified why (it may be listed only to apps
   that declare audio-only groups, or not on this network).

## Acceptance

1. Pixel 9, a song cast to Gæsteværelse; the panel's ⊕ on Stue: both play; within a few seconds the mini bar reads
   *{artist} · Gæsteværelse + Stue* and the Playing page *Playing on Gæsteværelse + Stue*.
2. *Play on…* reads both speakers as *Speaker · Playing {title}*; a tap on either closes the sheet, nothing restarts.
3. The panel's ✓ on Stue: the music stays on Gæsteværelse and the names go back to *Gæsteværelse*.
4. The same starting on Stue and adding Gæsteværelse (*Stue + Gæsteværelse*).
5. A queue that plays out on the group, then two quick presses of Play: one `re-enrolled` line in the backend log.

## Build notes (2026-10-02)

Built on `main` `f98f6c52` (rebased onto `64a0073b`):

1. **FR-R355-1** — `CastSender.members` (commonMain, default empty; `ActiveCastSender` passes the Chromecast side's).
   `CastSenderAndroid` registers a MediaRouter callback with `MediaRouteSelector.EMPTY` and
   `CALLBACK_FLAG_UNFILTERED_EVENTS` (no discovery), and on every route event and `Cast.Listener.onDeviceNameChanged`
   reads the selected route: when it is a group, its `routesInGroup` whose `getSelectionState` is `SELECTED`. Seen on
   the device: Play services' dynamic group route lists **every** Cast device as a candidate (the TVs, the hub, the
   other speaker, even another session's *Stue + 1*), only the playing ones `SELECTED` — without the filter every
   device in the house was a "member". Logged once per change on tag `RaviloCast` (*R355: session 'X' plays on […]*).
2. **FR-R355-2** — `castSessionName` (`CastSender.kt`); the Android sender's `deviceName` is that name, so every screen
   that showed the device (mini bar, Playing page, ⋯ block, remote, sheet) follows without a change of its own.
3. **FR-R355-3** — `castRouteInSession` (`CastSender.kt`), used by `ScreensSheet`'s `connectedTo`; it also covers the
   group's own route (*{member} + {n}*, Cast's name), which the sheet lists as a *Speaker group* row.
4. **FR-R355-4** — `castResumeInFlight` + `CAST_RESUME_WINDOW_MS` (`MusicCast.kt`); `resumeOnDevice` keeps when it
   sent, cleared by a live report or the link leaving CONNECTED.

**Tests:** `CastGroupTest` (9 cases: one device keeps its name, two named first-speaker-first either order, three
named and four counted, no member matching, blank/repeated names, every member and the group route are this session,
another group is not, one hand-off per press) — `:ravilo-ui:testDebugUnitTest` and `:ravilo-ui:desktopTest`.
`:shared:desktopTest`, `:ravilo-cast:jsBrowserProductionWebpack`, `:ravilo-android:assembleRelease`,
`:ravilo-web:compileKotlinWasmJs` and the desktop compile pass; the fences pass.

**Device (Pixel 9, debug build from this branch, phone media volume 0, Stue at 6–8 %, Gæsteværelse at 4 %, both read
over the Cast protocol from the host; the receiver is production `v1.48-88`, unchanged):**
- *Before* (installed debug `1.48-82`): both directions of ⊕ worked on the speakers (group endpoint, same receiver,
  next song, phone *next*, the dashboard's pause/unpause, a queue played out and handed back, *Play* again, removing the
  added speaker); the mini bar and Playing page read only the first speaker; *Play on…* read the added speaker
  *Busy · Caster: {song}* (and once the leader *Ready*).
- *After*: Gæsteværelse playing, ⊕ Stue from the panel with the app in the background → the mini bar
  *{artist} · Gæsteværelse + Stue*, the Playing page *Playing on Gæsteværelse + Stue*, *Play on…* both rows *Speaker ·
  Playing {song}*; a tap on Stue closed the sheet and nothing reached the server. The panel's ✓ on Stue → back to
  *Gæsteværelse*, the music on. Stue playing, ⊕ Gæsteværelse with the app on screen → *Stue + Gæsteværelse*, both rows
  playing, a tap on Gæsteværelse changes nothing. The Jellyfin dashboard's *Stop* → the song handed back paused; two
  taps on Play 0 ms apart → **one** `re-enrolled` line in the backend log and the song playing on the speaker from its
  place.

**Seen, not fixed (recorded for follow-ups):**
- A reinstall (or update) of the app while a group plays ends the phone's Cast session (Play services releases the
  app's routing session: `onSessionEnded … SUCCESS`) and the group plays on. Afterwards *Play on…* lists the group as
  *Stue + 1 · Speaker group · Busy · Caster: {song}*; picking a member speaker instead joined a session that showed
  *Playing on Stue* but never got a status, and a load sent through it reached nothing. Rejoin through the group row.
- R353's open question 1 is unchanged: a speaker playing Ravilo for another sender reads *Busy · Caster: {song}* (the
  receiver framework's Danish status text) and asks *Stop Caster: {song} and play here?*.
- No Google Home speaker group appeared in *Play on…* (Open question 2).

**Nothing to deploy** for this phase beyond the app itself.
