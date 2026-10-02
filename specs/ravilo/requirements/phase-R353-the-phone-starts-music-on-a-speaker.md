# Phase R353 — The phone starts music on a speaker: idle devices read Ready, the session starts, the hand-off survives

> Owner, 2026-10-02: casting music from the Android app to a Google Cast speaker does not work. Seen on the Pixel 9
> (Android 16, debug build `1.48-66`) against the Stue speaker (a Nest Wifi point), the first time R324 met a real
> speaker from a phone.

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed, **device-tested on the Pixel 9 against the Stue
speaker**. Written 2026-10-02 (dev-authored) from the device test; number given by the coordinator. **Amends** R324
(FR-R324-1/2's busy row, FR-R324-3's hand-off, FR-R324-5's slider) and R265 FR-R265-3 (how a route is selected).
Android only. No wire change, no new string, no receiver or backend change. **Amended 2026-10-02 (FR-R353-5, with R354):**
the hand-back after a cast ends from outside the app — common code, so Android and the Mac.

## What was seen, and why

**(a) Every device in *Play on…* read *Busy*, idle ones included.** Tapping the idle Stue speaker asked *Stop Nest
Wifi point and play here? Stue is playing Nest Wifi point.* R324 took the route's description as the running receiver
app's name (`CastRoutesAndroid`), as its build notes asked to be verified. Seen on the device: the description is the
app's status text when one runs (`Ravilo`, or the receiver's own *Casting: {song}* while it plays) and the device's
**model name** when nothing runs (*Nest Wifi point*, *Google Nest Hub*, the TVs' model names).

**(b) Choosing the speaker sometimes did nothing.** No Cast session, no app on the speaker. On Android 14+ a route
selection is a MediaRouter2 transfer: Play services creates the routing session, then androidx adopts it by reading
the session's selected routes back against the app's route list. While Play services connects to the device (a
speaker in a speaker group goes through a "dynamic group" controller first) it republishes its whole route list —
the log shows the routes removed and re-added within the same 100 ms as the session. When the session reaches the app
just after the removal, its selected routes resolve to nothing: androidx logs *Selected routes are empty. This
shouldn't happen.* and drops the transfer, no route is ever selected, and the Cast SDK, which starts its session from
`onRouteSelected`, never hears of it. A race: thirteen later tries on the same phone and speaker all went through, and
the very next try after the failed one did too (Play services' controller is then already connected).

**(c) Casting a song that was playing on the phone killed the app.** `MusicCast` hands the engine's queue and
position over when the session connects; it ran on its `Dispatchers.Default` collector and read the position from
ExoPlayer, which throws off the main thread (*Player is accessed on the wrong thread*). The Mac's player has no such
rule, which is why the Mac's casts never showed it.

**(d) The ⋯ volume slider opened at 50 %** whatever the speaker was at (the Android sender reported no volume), so
the first touch could take a quiet room to half volume.

## Requirements

**FR-R353-1 — An idle device reads Ready.** A route's status line is an app only when it is not the device's model
name (or its own name). `castRouteBusyWith(description, modelName, friendlyName)` in `:ravilo-ui` commonMain decides
it; the model name comes from `CastDevice` in the route's extras. Busy and *Playing Ravilo* keep R324's meaning.

**FR-R353-2 — A selection the Cast SDK never started is selected again.** `selectRoute` selects the route and, 3 s
later, selects it once more when the SDK has not begun a session since (`onSessionStarting`/`onSessionResuming`), the
route still exists, and nothing is selected — at most twice. A try the SDK did start is never repeated (a session that
starts and then fails is not retried), and a route that has gone or that something else selected is left alone.

**FR-R353-3 — The hand-off runs on the main thread.** `MusicCast`'s hand-off (engine position, stop, queue) hops to
`Dispatchers.Main`; the collector stays where it is.

**FR-R353-4 — The slider shows the device's volume.** The Android sender reports the session's volume (`CastSession`
`volume`, updated by `Cast.Listener.onVolumeChanged`, null while unlinked); the ⋯ slider starts there and follows it
(the volume keys move it) unless a finger is on it.

**FR-R353-5 — A cast that ends from outside hands back the speaker's song** (amended 2026-10-02 with R354/299). Seen
on the Pixel 9: a song changed on the speaker by another controller (Google Home's *next*: song A → song B), then
Google Home's *Stop cast* — the phone came back to song A, the song from before the cast.

*Why:* the phone followed the speaker's song all along (the receiver's `status` carries `queue` and `queueIndex` to
every sender, and R352's `follow()` saved it), but only into the last-played record, never into the running engine.
`stop()`, `moveAway()` and *Play on this phone* take the speaker's queue back (`takeBack`); a session that ends any
other way — Google Home, the Cast notification's *Stop casting*, the device dropping the app, a network loss — only
cleared `_linked`, and the engine kept what the hand-off had parked: the pre-cast song and position. By the time the
link change was seen, the last status was already overwritten with `null`, so even `takeBack` had nothing to read.

*Rule:* `MusicCast` keeps the last music status it saw while linked, and when it was seen. When the link drops and the
app did not end it itself (`playHere`, `stop`, `moveAway` mark their own ends), the speaker's queue, its song and its
place (the last position, plus the time since if it was playing, capped at the song's length) are loaded into the
engine **paused**, on the main thread. The decision is `castHandBack` (commonMain), tested. Android and the Mac use
the same `MusicCast`, so both are fixed.

**FR-R353-5, second amendment (2026-10-02 afternoon) — a Stop on the device is a hand-back too.** Seen on the Pixel 9
against production `v1.48-79`: music cast to Stue, the Jellyfin dashboard's *Next* (song A → song B, the phone
followed), then the dashboard's *Stop*. The speaker went to its idle view with the Ravilo app still running, the phone
stayed connected (cast glyph lit), and the phone's player went back to **song A at the hand-over's place**.

*Why:* two gaps. (1) The dashboard's Stop does not end the Cast session: the receiver's `musicEnded()` stops the
player, says `ended` and shows its idle view (R354 FR-R354-7). The link stays CONNECTED, so the hand-back above — which
only fired when the link dropped — never ran; `MusicCast.linked` went false and every screen fell back to the engine,
which still held what the hand-off had parked. (2) Even on a link drop the kept "last music status" was not the last
*live* one: after `ended` the media goes idle and the next report (music, queue intact, not loaded, not ended,
position 0) replaced it, so the place handed back would have been 0:00.

*Decision — what a dashboard Stop on a cast means (owner's lean, taken):* the **cast session stays connected**, the
speaker shows its idle view, and the **phone shows the speaker's last song paused at its last place**, ready to play on
the speaker again: Play (the Playing page, the mini bar) sends the queue back to the same speaker from that place, with
no new pick in *Play on…*. The same holds for the TV remote's Stop key on a display and for a queue that plays out
(that one at 0:00 of its last song, FR-R322-5). A session that **ends** — Google Home's *Stop cast*, the Cast
notification, the device closing the app, the network — hands back the same way (the speaker's song and place,
paused), and there is no session left, so Play then plays on the phone. Ravilo's own *Stop casting* and *Play on this
phone* are unchanged. This supersedes R354's device-check line "*stop* → the speaker goes idle, the phone reads ended".

*Rule:* only a live report (`castMusicLive`: connected, music, loaded, not ended, not failed) is kept as the place to
hand back. When the session stays connected but the music stopped there and the app did not ask for it
(`castStoppedOnDevice`: was live, now ended / not loaded / no status; not a failure; not a film in its place), the
hand-back runs after a 1.5 s settle — a song finishing reports IDLE for a moment before the receiver loads the next one,
and a new live report cancels it. A song that had reached its end (within 2 s) comes back at its start. While still
connected after such a stop, `MusicPlayback.play()` / `togglePlay()` hand the phone's (handed-back) queue to the device
again (`MusicCast.resumeOnDevice`). No wire change, no receiver or backend change, no new string.

## Out of scope

The Output Switcher path (`MediaTransferReceiver` stays: the TVs' casts and R265's resume were verified with it, and
they could not be re-tried here) · the speaker's row while **another** sender plays Ravilo on it (see Open questions) ·
the receiver.

## Open questions

1. **A speaker playing Ravilo from another phone or the Mac reads *Busy · Casting: {song}*** (in the receiver's
   language), because the receiver framework writes its own status text while media plays, and tapping it asks
   *Stop … and play here?* before it joins. The route line carries nothing else that names the app. Lean: the receiver
   sets its own status text (`CastReceiverContext.setApplicationState`) to start with *Ravilo*, and the phone treats a
   line that starts with *Ravilo* as ours — a receiver deploy, and a check that the framework does not overwrite it.
2. A session's own row reads *Speaker · Ready* while it is connected with nothing loaded (a join): R324 shows
   *Playing {title}* only once something plays. Unchanged here.

## Acceptance

1. Pixel 9, music mode, nothing casting: *Play on…* lists Stue and Gæsteværelse as *Speaker · Ready*, the hub and both
   TVs as *Ready*; a tap on idle Stue starts the cast with no question.
2. With a song playing on the phone, Now playing's cast glyph → Stue: the app stays up, the song continues on Stue
   where it was, and the mini bar reads *{artist} · Stue*.
3. Pause, play and next act on the speaker; the volume keys move its volume and the ⋯ slider shows where it is.
4. *Play on this phone* brings the song back at its position and the speaker goes idle; *Stop casting* leaves the
   speaker idle and the song paused on the phone.
5. A failed transfer (log *Selected routes are empty*) is followed within 3 s by *R353: no Cast session started from
   {device}; selecting it again* and a session.

## Build notes (2026-10-02)

Built on `main` `58159a53`:

1. **FR-R353-1** — `castRouteBusyWith` (`CastSender.kt`), used by `CastRoutesAndroid`; `CastRouteBusyTest` covers the
   model name, the device name, nothing said, a running app and an unknown model.
2. **FR-R353-2** — `selectRoute` + `CastStartWatch` (`CastRoutesAndroid.kt`); `CastSenderAndroid` marks the watch on
   `onSessionStarting` and `onSessionResuming`. Logged on tag `RaviloCast`.
3. **FR-R353-3** — `MusicCast.bind` launches the hand-off on `Dispatchers.Main`.
4. **FR-R353-4** — `CastSenderAndroid.volume` (session volume, `Cast.Listener`), the slider in `MusicCommon.kt`'s
   `CastBlock` starts from `MusicCast.deviceVolume` and follows it while not dragged.

**Verified on the Pixel 9 against the Stue speaker** (phone media volume 0 throughout, speaker at ≤ 10 %, its state
read over the Cast protocol from the host): the sheet read *Speaker · Ready* for both speakers and *Ready* for the hub
and the TVs (looked at, not cast to); a tap on idle Stue launched the receiver with no question; a song played on Stue
(receiver media *PLAYING*), the mini bar read *{artist} · Stue*; pause, play and next
acted on the speaker; one volume-down key took the speaker from 6 % to 4 % and later 4 % to 2 % with the ⋯ slider
following it, and a touch on the slider set 5 %; *Play on this phone* continued the song on the phone at its position
with the speaker idle; casting from Now playing's glyph while the song played on the phone continued it on Stue at the
phone's position (the build before this phase died here); *Stop casting* (from ⋯ and from the sheet) left the speaker
idle and the song paused on the phone. Thirteen tries after that morning's failure (seven of them stop-and-cast cycles, three with the app force-stopped
first) all started a session; the race in (b) did not recur, so FR-R353-2's retry is verified by reading only — its trigger is the log line
above, last seen at 09:25 the same morning. `:ravilo-ui:testDebugUnitTest`, `:ravilo-android:assembleDebug` and
`assembleRelease`, the desktop and web compiles, and the fences pass.

**Not done:** a cast to the TVs or the hub after this change (not allowed this round; the selection path is the same
with one extra re-select that only fires when no session started) · open question 1.

## Build notes — FR-R353-5 (2026-10-02, with R354), not deployed, not device-tested

`MusicCast.bind` keeps the last music status seen while connected (`lastMusic`, with when it arrived) and forgets it
when a film takes the device. On a CONNECTED → not-CONNECTED change it asks `castHandBack(last, elapsed, endedByApp)`
(commonMain, `MusicCast.kt`) and, when that answers, loads the speaker's queue into the engine **paused** at that song
and place, on `Dispatchers.Main`. `playHere`, `stop` and `moveAway` set `endedByApp` (they bring the music back
themselves); every other end — Google Home's *Stop cast*, the Cast notification's *Stop casting*, the device closing
the app, a lost network — now hands back. A playing song is advanced by the time since the last report, capped at its
length; a queue that played out comes back at 0:00 of its last song. Common code, so the Mac's Cast v2 sender
(`CastSenderDesktop` → the same `MusicCast`) is fixed too.

**Tested:** `CastHandBackTest` (5 cases: another controller's *next* then an outside stop resumes the speaker's song
at its place; paused; capped at the song's end; a played-out queue; nothing when the app ended it, no status, a failed
item, a film, a bad index) — `:ravilo-ui:testDebugUnitTest` and `:ravilo-ui:desktopTest` in CI.

**Device check (owed):** Pixel 9, music to Stue: Google Home *next* (song A → song B), wait 20 s, Google Home *Stop
cast* → the phone's mini bar shows song B, paused about 20 s further on than when *next* landed; Play resumes song B
there. The same from the Mac.

## Build notes — FR-R353-5 second amendment (2026-10-02), not deployed, device-tested on the Pixel 9 against Stue

**Built** (common code, `MusicCast.kt`): `castMusicLive(link, status)` decides both `linked` and which report is kept
as the place to hand back; `castStoppedOnDevice(wasLinked, link, status, endedByApp)` decides a stop on the device while
the session stays; the hand-back itself is one `handBack()` used by both paths (the session's end, and the stop on the
device after a 1.5 s settle that a new live report or the link dropping cancels). `castHandBack` now brings a song that
reached its end (within 2 s) back at 0:00 rather than at its length. `MusicPlayback.play()` / `togglePlay()` call
`MusicCast.resumeOnDevice()` first: still connected, nothing live there, the engine holds a paused music queue ⇒ that
queue goes to the device from its place (the existing hand-off). Books never cast, as before.

**Deviation:** the lock screen's / notification's Play still reaches the phone's own engine after a dashboard Stop (the
media session calls `MusicEngine` directly); only the app's own controls send it back to the speaker.

**Tested:** `CastHandBackTest` (8 cases; new: the dashboard's Stop hands back song B at its place; only a live report
counts; a stop on the device while connected is a hand-back, and the six cases that are not) —
`:ravilo-ui:testDebugUnitTest` green; `:ravilo-android:assembleRelease` green.

**Device (Pixel 9, debug build, the Stue speaker at 3 %, phone media volume 0):**
1. Cast *song A* from the phone, dashboard *Next* → song B on the speaker, dashboard *Stop* → the speaker idle (Ravilo
   app still running), the phone's cast glyph lit, its Playing page shows **song B at 0:23, paused** (the speaker was at
   ~0:25). Play → song B plays **on Stue** from 0:23 (*Playing on Stue*). Before the fix (production, and a first build of this
   change that still kept the idle report as the place) the phone showed song A at the hand-over's place.
2. Dashboard *Next* (→ song C), Google Home's Stue page → *Stop cast* → the speaker's app closed, the glyph unlit, the
   phone shows **song C at 0:32, paused** (the speaker was at ~0:31).
3. Cast again, ⋯ → *Stop casting* → the speaker's app closed, the phone shows the song paused at the speaker's place.
4. Cast again, ⋯ → *Play on this phone* → the speaker's app closed, the song plays on the phone from the speaker's place.

5. The final build (without the debug logging) once more: cast, dashboard *Next* → the last song in the queue, dashboard
   *Stop* → the phone shows that song at 0:14, paused, glyph lit; *Play on…* → *Stop casting* closes the speaker's app.

**Seen, not changed:** while connected after a dashboard Stop, the Playing page's ⋯ has no *Stop casting* / *Play on
this phone* block (it follows `linked`); *Play on…* still offers *Stop casting*. Stue in that sheet reads *Ready*, not
*Playing Ravilo*.

**Not tested:** a queue that plays out on the speaker (the same path as the dashboard's Stop, landing at 0:00 of its last
song), the Mac's sender (same common code).
