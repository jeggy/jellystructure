# Phase R292 — A player that is not on screen does not exist

## Status

`Planned` — written 2026-09-24 from a household report and a same-day investigation on the stue TV.
**Dev-reviewed 2026-09-24 against `main` `9d2636bb`** (see §Dev review at the bottom: every finding
holds in the code; the recorded position cannot reach a transcode without a `start_position_ms` on the
start request; FR-R292-6's saved state is smaller than it reads and must be; the resume machinery may
not live in `PlayerScreen`'s body; rung 2 is already built). Not built beyond FR-R292-11's rung 2. Spec
first. **Supersedes R220 FR-R220-4** and R192's *"resume without a
full player rebuild"* design note; keeps R220's detector and ladder for mid-playback loss (FR-R292-11).

> *"This has been reported before, but seems like it's still not fixed. When watching something on
> Ravilo TV app and then going out of the app (without a proper close) and then coming back, then the
> video starts streaming again, but only audio can be heard, while only seeing a black screen."*

> Owner, 2026-09-24: *"Yes, let's write a spec for your suggested fix. This even sounds like a pretty
> serious bug, so let's write a proper spec including as much information as possible as well, so we
> can fully fix this."*

Scope: the **Android** app (`:ravilo-android` TV and phone activities, both through
`ravilo-ui/src/androidMain`). `ravilo-web` and the Tizen receiver (`ravilo-screen`) are out of scope
(Non-goals).

## History

- **2026-08-31** — first report (R220): *"…the video continues (which is correct). But while audio works
  perfectly, the video is just black."* R220 built a frame-count detector, a four-rung recovery ladder
  (re-attach surface → seek flush → recreate the `SurfaceView` → re-arm the session), a deterministic
  surface detach on `ON_STOP` / re-attach on `ON_START` (FR-R220-4), and a telemetry counter
  (FR-R220-6). **It was built without ever reproducing the bug** (no device access at the time), as
  *"a reasoned bet across all three cases, not a confirmed-necessary one"* (R220 Status).
- **2026-09-24** — reported again on the stue TV, which runs `1.36-dirty` (installed 2026-09-21, contains
  all of R220). **R220 did not fix it.**

## What was measured on the stue TV, 2026-09-24

Sony BRAVIA 4K VH21 (`BRAVIA_VH21`, Android 12, MediaTek, `dalvik.vm.heapgrowthlimit` 192 MB), Ravilo
`1.36-dirty` release build, logcat captured throughout, `dumpsys` after every step.

| # | Played | Away | Meanwhile | Came back to |
|---|---|---|---|---|
| 1 | SDR HEVC 1080p, direct play (*Starhaul* S02E19) | HOME, 30 s | — | picture + sound, same position |
| 2 | same | HOME + **standby** 60 s | — | picture + sound |
| 3 | same | HOME | **Wholphin** played an HEVC/Opus file | picture + sound |
| 4 | AVC **transcode** (*Cooking Trouble* S01E02) | HOME, 45 s | — | picture + sound, **but a duplicate transcode** (below) |
| 5 | same | HOME | **DR TV** played a live AVC stream | picture + sound |
| 6 | **Dolby Vision** 4K, direct play (*Late Train Home*) | HOME, 30 s | — | **Ravilo's Home screen — the player was gone** |
| 7 | same | HOME, 10 s | — | Home screen again |
| 8 | same | HOME, **2 s** | — | picture + sound |

The black screen itself **did not reproduce** in eight tries. R220's detector logged nothing in any of
them (`PlayerVideoSurface` has three `Log.w` lines; none appeared; the release build does not strip
logs). What the trials **did** show, each with its evidence:

**1. A backgrounded Ravilo keeps its whole player.** On `ON_STOP` the player is paused, the session is
stopped and the video surface is detached, but the `ExoPlayer` is **not released**
(`PlayerLifecycleEffect.kt`). Thirty seconds after HOME, `dumpsys audio` still listed Ravilo's
`AudioTrack` (`state:paused`), and `dumpsys media.resource_manager` still listed Ravilo's pid holding
`OMX.MTK.VIDEO.DECODER.HEVC` (`non-secure-codec/video-codec`). The same held with the AVC and Dolby
Vision decoders. Buffers go with it: `am_pss` for Ravilo was 80–120 MB during SDR playback and
**236 MB during Dolby Vision** (`am_pss: [13083,…,236049408,…]` at HOME).

**2. With Dolby Vision, Android destroys the activity for memory, and the viewer loses their place.**
Both DV trials: `wm_destroy_activity: [0,1280982,26317,dev.jellystructure.ravilo/.android.MainActivity,low-mem]`,
3.4 s and 5.5 s after HOME, same process (no `am_kill`). The composition went with it (`ExoPlayerImpl:
Release` at the same moment), and because the navigation stack is plain `remember` state
(`RaviloApp.kt`: `var stack by remember { mutableStateOf(listOf(initialDest)) }`), the relaunch opened
**Home**. Jellyfin had the right resume position (the session stop at HOME reported it), so the film is
still in Continue Watching, but the viewer who pressed HOME for a moment has to find it again. Trial 8
(2 s away) came back fine: the destroy happens only after a few seconds in the background.

**3. With a transcode, every return starts a second, orphaned transcode.** On return, `ON_RESUME` calls
`player.play()` on the **old** `ExoPlayer`, still prepared with the **old** ticket's HLS URL, whose
transcode Phase 180 had already stopped at HOME. Jellyfin's log, trial 4:

```
10:52:38.311  started playback … ("Ravilo" "1.36-dirty")        ← the re-armed session
10:52:38.320  TranscodeManager: ffmpeg … -ss 00:01:12.072 … -start_number 24 … 654c7b32….ts
10:52:38.339  MediaInfoHelper: User policy …                    ← the NEW ticket's negotiation
10:52:38.728  TranscodeManager: ffmpeg … -start_number 0 … 04a57a6b….ts
```

The first ffmpeg is the old player asking for segment 24 of a stream nobody owns any more; Jellyfin
restarts the encode for it. The new ticket then loads, and that encode is orphaned. It ran until
Jellyfin's own idle timeout, **67 s after** the second one was stopped at the next HOME. One
background/foreground round trip = two 4K→AVC encodes on the server, one of them for nobody. This is
R220's open question 5 (*"re-prepare on foreground when the delivery was a transcode"*), now measured.

**4. Live TV is not paused at all.** `LiveTvPlayerScreen` has no `PlayerLifecycleEffect`; its player is
released only when the screen leaves composition. By the code, pressing HOME during Live TV leaves
the stream and its audio running behind the launcher until the activity is destroyed. (From code,
not a device trial: this session lost TV access before a Live TV trial could run.)

**5. R220's telemetry never reaches anyone.** `PlayerQoeSnapshot.videoOutputRecoveries` (FR-R220-6)
is counted on the device, but it is not in the shared QoE DTO, `playback_qoe` has no column for it, and
no backend code reads it. There is no way to know from the database whether R220's ladder has ever run
in the field. **The next occurrence of this bug must be diagnosable from the database** (FR-R292-11).

**6. Ravilo never takes audio focus.** The `ExoPlayer.Builder` has no `setAudioAttributes(…,
handleAudioFocus = true)`. Ravilo never asks Android for focus and is never told to yield it, so
nothing arbitrates Ravilo's audio against another app's (see 4).

**7. A long-lived process brought back into an old player can hang the app (phone, release build).**
Found the same day by the session investigating tracks-at-end MKVs: bringing a long-backgrounded
process back into its old player screen flipped the activity landscape→portrait, tore down a decoder
buffer pool, and never regained a focused window: *"Input dispatching timed out — application does not
have a focused window"* (an ANR, main thread idle in its looper). The same class as findings 1–2: a player
that outlived its screen, re-bound on return.

### What that means for the black screen

The exact mechanism is **not proven**. Two candidates fit the symptom (audio continues, picture black,
after the app was away) and both are consequences of finding 1:

- **The hardware video plane.** On this TV the MediaTek decoders render through a dedicated hardware
  plane (`MtkACodecPlugin: add usage GRALLOC_USAGE_PATH_VDP0`), not the GPU compositor; Wholphin's mpv
  output used `OUTPUT_GRAPHIC` instead. While Ravilo is away, something else can drive that plane: the
  Google TV launcher's autoplaying previews, a DRM app, the TV's own tuner input. Ravilo's decoder,
  still configured, is then re-bound to a new `Surface` on return. If that re-bind leaves the decoder
  writing to a plane it no longer owns, the picture stays black while audio, which never used the
  plane, carries on. That matches the report's *"sometimes"*: it depends on what ran in between.
- **Codec reclaim.** Android's `ResourceManagerService` reclaims codecs from background processes when a
  foreground app needs one (the MTK decoders log `keep callback message for reclaim`). A reclaimed
  hardware video decoder with an untouched software audio decoder (`c2.android.opus.decoder`) gives
  exactly *"only audio"*. No reclaim happened in these eight trials, but the pool is finite and 4K/DV
  sessions take more of it.

What both candidates share, and what R220 FR-R220-4 kept, is **a decoder that lives through the
background and is re-bound to a new surface afterwards**. The recurrence on a build that has FR-R220-4
is evidence that re-binding more carefully is not enough. The fix is to never re-bind: **no decoder
survives a background.** Android's own guidance for Media3 on API 24+ is the same: release the player
in `onStop`, create it in `onStart`.

## Requirements

### The rule

- **FR-R292-1 — The engine does not survive `ON_STOP`.** When the activity stops (HOME, another app,
  standby, a TV input switch), the player's `ExoPlayer` is **released**: its decoders, `AudioTrack`,
  buffers, network source and (TV) `MediaSession`. `ON_PAUSE` still only pauses (an overlay such as the
  assistant or a quick-settings panel pauses; it does not tear down). This replaces FR-R220-4's
  detach/re-attach and R192's *"resume without a full player rebuild"*.
- **FR-R292-2 — What survives is a small, explicit resume record.** Captured from the live player
  **before** release, held by the player screen (and saved state, FR-R292-6):
  - the item id, and for an episode the episode context the binge needs (`currentItemId`,
    `currentEpIndex`, the next-episode id);
  - the position, from the player at `ON_STOP` (the same value `onBackground` already reports to
    `stopSession`), so the session stop and the resume point can never disagree;
  - the play intent (was it playing);
  - the viewer's track choice: audio and subtitle, the burn-in state (R282), a single-audio session's
    server-side audio choice (R284), the handset subtitle size (R244).

  Nothing else carries over. In particular no `ExoPlayer`, `MediaItem`, `Surface`, ticket or URL.

### Coming back

- **FR-R292-3 — Coming back is a start, not a re-attach.** On `ON_START` after a background: a **new**
  engine, `armSession` for the recorded item, a fresh ticket, `load` at the recorded position, and
  `play()` only if the play intent says so. It is presented as **R290's one start moment**: R218's
  cold-start treatment with title context, no chrome at 0:00, no stale frame, nothing from the previous
  engine. The viewer sees *"loading"* for a moment, then the picture at their position.
- **FR-R292-4 — The previous ticket is never touched again.** No code path may call `play()`, `prepare()`
  or `seekTo()` on an engine loaded with a ticket whose session was stopped. With FR-R292-1 that is
  structural (the engine is gone), and a return during a transcode causes **exactly one** transcode
  (acceptance 4).
- **FR-R292-5 — The viewer's choices survive.** A viewer who switched to Danish audio or turned subtitles
  off mid-episode gets exactly that back, applied from the resume record on the new engine, not
  re-resolved from the server's remembered-track rules (R181/R195), which may disagree with a choice made
  seconds ago.

### Surviving the activity being destroyed

- **FR-R292-6 — The player survives an activity recreation.** The navigation stack (at minimum the
  current player destination) and the resume record are **saved state**: `rememberSaveable` /
  `onSaveInstanceState`, with the destinations made saveable. A low-memory destroy (finding 2), a
  configuration recreation, or a process death that Android restores from the recents task brings the
  viewer back **into the player at their position**, through FR-R292-3's start. Never Home.
- **FR-R292-7 — `colorMode` is in `configChanges`.** An HDR/wide-gamut mode change on the way out of or
  back into a DV/HDR title must not recreate the activity (R220 open question 3). Nothing in Ravilo reads
  `colorMode` from resources, so the activity has nothing to rebuild. FR-R292-6 makes a recreation
  survivable anyway; this makes it rarer.

### The cases the lifecycle does not announce

- **FR-R292-8 — Standby is a background.** R220 §2.4: display standby on an Android TV may deliver no
  `ON_STOP`. A screen-off broadcast (`Intent.ACTION_SCREEN_OFF`, registered while the player is up) is
  handled exactly like `ON_STOP`, and the matching `ACTION_SCREEN_ON` + resumed state like `ON_START`,
  each at most once per transition (a real `ON_STOP` after `SCREEN_OFF` is a no-op). Which events the
  BRAVIA actually sends for standby, HDMI-CEC power-off and an input switch is **recorded in this spec
  from a device trace** before the phase is marked built.
- **FR-R292-9 — Live TV follows the same rule.** `LiveTvPlayerScreen` gets the same lifecycle: pause on
  `ON_PAUSE`, release on `ON_STOP`/screen-off, and on return re-tune the channel it was on (the channel
  is its resume record). Pressing HOME during Live TV stops the audio.

### The seam

- **FR-R292-10 — The engine is rebuildable behind the same `RaviloPlayer`.** Today `exo` is `by lazy`
  and `release()` is final. The Android actual gains an explicit engine lifecycle (e.g.
  `releaseEngine()` / an engine rebuilt on the next `load`), and **everything bound to the engine is
  re-bound to each new one from a single place**: the renderers factory (R31 FFmpeg audio), the
  `LoadControl` (R216), the load-error policy (phase 179), the **extractors factory** (the vendored
  `MatroskaExtractor` that follows `SeekHead` to a `Tracks` element at the end of the file, being wired into
  `DefaultMediaSourceFactory` by the tracks-at-end work in parallel), the QoE analytics listener, the video-size
  listener (R77), the cue listener that feeds the `SubtitleView` (R55/R110/R244), the current
  `SurfaceView` (tracked since R220), and (TV only) the `MediaSession` (R192/R193 metadata re-fed from
  the resume record). A new engine that misses one of these is this phase's most likely regression, so
  the single re-bind function is unit-visible and every binding is listed there. The web actual may
  treat these calls as no-ops. The common `PlayerScreen` code sees one `RaviloPlayer` for the screen's
  lifetime, as today.
- **FR-R292-11 — Delete what this replaces; keep what still earns its place; make the telemetry real.**
  - Delete FR-R220-4's `detachVideoSurfaceForBackground`/`reattachVideoSurfaceForForeground` and the
    background half of `PlayerVideoSurface`'s re-attach hooks (`SurfaceHolder.Callback` /
    `onWindowVisibilityChanged`). There is no surviving decoder to re-attach.
  - **Keep** R220's detector and ladder: a picture can still be lost mid-playback, in the foreground.
    **Fix rung 2 while here.** Rung 2 (`PlayerVideoSurface.kt`, `player.seekTo(player.positionMs)`) is a
    seek to the current position. Across ~12 ladder runs on a TV and the Pixel 9 (2026-09-24, the
    tracks-at-end investigation) it never recovered a stalled picture, while a seek to a *different*
    position recovered in 1.5 s. Rung 2 must be a real flush: a seek that **moves**, or an explicit
    decoder flush, verified against a stall on a device. "Back one second" is not enough on its own: at
    0:00, where the tracks-at-end stall sits, it clamps to the same position. Move forward by a
    millisecond below one second, back otherwise (being built in parallel by the tracks-at-end session).
  - **Make FR-R220-6 real.** `videoOutputRecoveries` goes into the shared QoE DTO, a `playback_qoe`
    column (additive migration) and the backend's QoE write, and gains the breakdown R220 asked for and
    never built: the rung that recovered, and time to first frame after. Two new counters beside it:
    `background_returns` (FR-R292-3 starts) and `restored_after_recreate` (FR-R292-6 restores).
    If the black screen ever happens again, the row for that session says so.
- **FR-R292-12 — Take audio focus.** The engine is built with `AudioAttributes(USAGE_MEDIA,
  CONTENT_TYPE_MOVIE)` and `handleAudioFocus = true`, so Ravilo pauses when another app takes focus and
  never plays over it. Transient losses (the assistant) pause and resume.

### Unchanged

- The session contract: `ON_STOP` → `stopSession(position, duration)` exactly as today (Phase 180
  teardown releases a transcode, the ≥90% mark-played rule applies); `ON_START` → a new session.
- The phone: same code path, same rule. Casting is untouched: a casting phone is not playing locally.
- Everything R218/R290 say about how a start looks. This phase only makes a return one of them.

## Non-goals

- **A grace period before release.** A viewer who presses HOME and comes straight back pays a start
  (trial 8's 2-second return would now show R218's loader for a moment). Accepted: the DV activity was
  destroyed 3.4 s after HOME, so any grace long enough to matter loses that race, and holding a decoder
  "just in case" is the design that failed. Open question 1 keeps the door open for a measured value.
- Background playback, picture-in-picture, audio-only continuation.
- `ravilo-web` (a browser tab has no decoder to leak) and the Tizen receiver (`ravilo-screen`, AVPlay,
  its own suspend/resume model). A Tizen equivalent, if needed, is its own phase.
- Changing what R218's start moment looks like.

## Adjacent findings (not this phase)

- `MainActivity` sets `FLAG_KEEP_SCREEN_ON` for the whole activity, so a TV left on Ravilo's Home never
  sleeps or starts its screensaver. It belongs on the player only, and is worth its own small phase.

## Open questions

1. **Grace period.** Lean: none (Non-goals). If device data after this phase shows returns within a
   few seconds are common and the DV destroy does not recur with a released engine, a short grace could
   be revisited with numbers.
2. **Coming back after a long absence.** A viewer who returns after hours to a restored player
   (FR-R292-6): auto-play (the R220 report calls *"the video continues"* correct) or land paused?
   Lean: play if away under 30 minutes, paused at the position otherwise.
   **Decided 2026-09-24 (owner): the lean.** Under 30 minutes away ⇒ playing; longer ⇒ paused at the
   position, presented as the same start.
3. **Which lifecycle events the BRAVIA sends** for standby, CEC power-off, HDMI input switch and the
   launcher's own video previews. FR-R292-8 requires a device trace; this spec cannot answer it.
   *Dev review:* trial 2 does not answer it either — it went through HOME first, so its `ON_STOP` came
   from HOME. The trace must be standby **from the player**, with no HOME press before it.
4. **Is releasing on `ON_STOP` enough on the phone,** where the OS may deliver `ON_STOP` for a
   notification shade pull on some OEMs? Lean: yes. The shade delivers `ON_PAUSE` only on stock
   Android; verify on the Pixel 9.

## Acceptance

Device trials need the owner's go-ahead for the TV. The phone rows run on the Pixel 9.

1. **Nothing survives a background.** Play SDR, AVC-transcode and DV titles; press HOME; within 2 s,
   `dumpsys media.resource_manager` lists **no codec** for Ravilo's pid and `dumpsys audio` lists **no
   `AudioTrack`** for Ravilo's uid; Ravilo's `am_pss` returns to its not-playing level.
2. **Coming back is a start at the right place.** For each: return after 30 s and after 5 min of
   standby; the picture appears at the recorded position (±2 s) with the same audio/subtitle choice,
   presented as R290's single start moment. Repeat trial 3 and 5 (Wholphin / DR TV played meanwhile).
3. **DV no longer loses the viewer.** Trial 6: no `wm_destroy_activity … low-mem` within 5 minutes of
   HOME with a DV title; and when a destroy is forced (`adb shell am kill` of the backgrounded app, or
   *Don't keep activities*), the relaunch opens the player at the position, not Home.
4. **One transcode per return.** Trial 4: Jellyfin's log shows exactly one `TranscodeManager` start per
   return and no transcode outliving its session by more than Phase 180's teardown.
5. **Live TV stops on HOME.** `dumpsys audio` shows no started Ravilo `AudioTrack` 2 s after HOME from
   Live TV; returning re-tunes the same channel.
6. **Telemetry lands.** After a session with a forced ladder run and a background return, that session's
   `playback_qoe` row carries `video_output_recoveries ≥ 1` and `background_returns ≥ 1`.
7. **Unit:** the resume record round-trips through saved state; the engine re-bind function attaches every
   binding listed in FR-R292-10 to a fresh engine (a fake engine records each call).

## Dev review (2026-09-24, against `main` `9d2636bb`)

The seven findings are all as the code reads. `PlayerLifecycleEffect.kt:33-65` pauses on `ON_PAUSE`,
stops the session + deactivates the media session + detaches the surface on `ON_STOP`, re-arms on
`ON_START` and calls `player.play()` on `ON_RESUME` — never `release()`, which only runs when the screen
leaves composition (`PlayerScreen.kt:1351-1353`). `LiveTvPlayerScreen.kt:93/156-159` has no lifecycle
effect at all. `videoOutputRecoveries` exists only in `PlayerQoeSnapshot` (`RaviloPlayer.kt:148`) — not in
`Models.kt`'s QoE DTO (`:143` ends at `subtitle_load_errors`), not in `PlaybackQoe.sq`, not in
`PlaybackQoeStore.kt`. No `AudioAttributes`, no `handleAudioFocus`, no `ACTION_SCREEN_OFF` anywhere in
`ravilo-ui`/`ravilo-android`. `android:configChanges` lists no `colorMode` on either activity
(`AndroidManifest.xml:46`, `:59`). The navigation stack is `remember`, not `rememberSaveable`
(`RaviloApp.kt:447`) — and `rememberSaveable` is used **nowhere** in `ravilo-ui`. Nine items.

1. **Finding 3's double transcode is structural, and the `ON_RESUME → play()` branch has to go with
   FR-R292-1.** The order on a return is: `ON_START` → `armSession` → `store.startSession` (a network
   round trip) → `ON_RESUME` → `player.play()` on the engine still holding the **old** HLS URL
   (`PlayerLifecycleEffect.kt:63-65`) → the new ticket arrives → `LaunchedEffect(sessionState)` calls
   `player.load(newUrl, ticket.startPositionMs, …)` on the same engine (`PlayerScreen.kt:909-933`). The
   old URL's segment request is the first `TranscodeManager` line in the Jellyfin log; the `load` is the
   second. FR-R292-1 removes the engine, but the spec keeps "`ON_PAUSE` still only pauses" and says
   nothing about `ON_RESUME`: state explicitly that the resume branch is deleted and the play intent
   lives in the record (FR-R292-2), or a builder keeps it and calls `play()` on a released engine.
2. **"Load at the recorded position" cannot reach a transcode as the request stands — add
   `start_position_ms` to the start.** The backend resolves the start position from Jellyfin's user data
   at negotiation (`PlaybackService.kt:446-448`, `itemDetail.userData.playbackPositionTicks`), and
   `PlaybackStartRequest` carries only `item_id` + `capabilities` (`Models.kt:1140-1142`). Two
   consequences. (a) **The race the spec wants to close is real today:** `stopSession` posts the final
   position fire-and-forget with retries on `exitScope` (`PlayerStore.kt:257-270`) and `armSession`
   negotiates immediately, so the start can read Jellyfin *before* the stop has landed — up to a
   heartbeat behind. (b) **A client-side `load(url, recordedPosition)` only works for direct play.** A
   transcode's HLS is cut server-side at the ticket's position; loading it elsewhere is a seek into an
   unencoded region — a restream, which is exactly what R290 FR-R290-4 rations. The fix is the shape the
   restream paths already have: `PlaybackRestreamRequest` carries `positionMs` and the service uses it as
   `startPositionMs` (`PlaybackService.kt:962`, `:1021`). Give `PlaybackStartRequest` an optional
   `start_position_ms`; when present it wins over Jellyfin's user data. Additive (never remove a field —
   installed clients deserialise it), one line in the service, and it makes FR-R292-2's "can never
   disagree" true rather than hoped. This is also the mechanism R290 FR-R290-3 needs.
3. **The record's position must come from the engine, under R184's guard.** The spec says "from the
   player at `ON_STOP`", but today's `onBackground` reports the *polled* `positionMs`, gated by
   `bk.positionKnownForItemId == currentItemId` (`PlayerScreen.kt:1302-1305`, R184). Read
   `player.positionMs` directly before the release — it is the exact value, not a tick stale — but keep
   the guard: during a binge `replaceTop` the engine may already be loading the next episode, and a
   record of (episode 2, position from episode 1) is the R184 bug in a new place. Capture `(currentItemId,
   position)` as one unit, or record nothing.
4. **FR-R292-6 is smaller than it reads, and must be.** `Dest` is a private sealed class in
   `RaviloApp.kt` (`:202-292`); `Player` carries `episodes: List<PlayerEpisodeEntry>?` and `segments:
   TvSegmentMarkers` (`:257-277`), `ChannelView` carries a whole `Channel`. Making "the destinations
   saveable" means a `Saver` for every one of them, in a codebase with no `rememberSaveable` at all. Take
   the FR's own minimum literally: save **one string** — the resume record, serialised with
   kotlinx.serialization (already a dependency; `TvSegmentMarkers` is already `@Serializable`) — through
   `rememberSaveable`, and on restore rebuild the stack as `[Home, Player]` from it. A `String` needs no
   platform `Saver`, survives a low-mem destroy, a recreation and a recents restore alike, and nothing
   about the other destinations changes. Everything above `Player` on the stack that a viewer might
   have had is lost on a low-mem destroy, and that is fine: the viewer pressed HOME from the player.
5. **None of this may go into `PlayerScreen`'s body.** `PlayerScreen.kt` is 4,175 lines; the release
   dex guard (`scripts/check-player-dex.sh`, limit 250 of a 256-register cliff) last measured it at 239.
   Capture, restore, re-apply (FR-R292-5) and the saved-state plumbing are exactly the kind of state
   logic that has tipped this class into a release-only `VerifyError` twice. The resume record and its
   capture/restore belong in a state-holder class (`PlayerResume` or on `PlayerStore`), called from the
   screen in one line each. Write it as an invariant, beside the two already there.
6. **FR-R292-10 is R192's own pattern applied to the engine — say so, and fix two bindings' homes.**
   R192 hit the same wall for the session: "`lazy` can't be reset, hence the manual ref"
   (`RaviloPlayerAndroid.kt:192-195`); `exo` is still `by lazy` (`:45`), and `release()` (`:367-371`)
   *creates* an engine if none exists just to release it. The engine becomes a nullable ref like
   `mediaSessionRef`. Two bindings the list names are registered from the wrong place for a rebuild:
   `setSubtitleView` adds its cue listener to `exo` **per call** (`:309-319`) from `PlayerVideoSurface`'s
   `AndroidView` factory (`:172-176`), which runs once per surface generation, not per engine; and the
   `onVideoSizeChanged` listener + `qoeListener` live inside the lazy (`:82-87`). All three move into the
   single re-bind function, with the cue listener registered once per engine against the *current*
   `subtitleViewRef` — which also ends today's one-listener-per-`setSubtitleView` accumulation.
7. **FR-R292-11's rung 2 is built and verified; the telemetry bullet needs one more line.** Rung 2 shipped
   as `f6ae399e`: `recoverySeekTargetMs` (`seams/PlayerRecovery.kt:10`, forward below one second, back
   otherwise, unit-tested), `SEEK_RUNG_SETTLE_MS = 4_000` (`PlayerVideoSurface.kt:49`), verified on the
   stue TV against a forced stall at 0:00. The bullet's "being built in parallel" is history. For the
   counters: `PlaybackQoeStore.hasIssue` (`PlaybackQoeStore.kt:35`) is the one consumer that decides
   whether a session is worth a second look — add `video_output_recoveries > 0` and `background_returns`
   to it, or the column is written and never read, which is the state FR-R220-6 is in now. Migration:
   the next numbered `.sqm`, on the model of `35.sqm` (`subtitle_load_errors`).
8. **FR-R292-12: Media3 ducks on `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`, it does not pause.** With
   `handleAudioFocus = true` ExoPlayer pauses on permanent loss and plain transient loss, and lowers the
   volume (to 0.2) on may-duck, restoring afterwards. The FR's "transient losses (the assistant) pause and
   resume" is right for the assistant's usual request and wrong for a may-duck one; let the FR say
   "pause or duck, as the other app asked". Also worth noting: audio focus is the one line in this phase
   that reaches Live TV *before* FR-R292-9 does — another app playing would at least pause it.
9. **Acceptance 3's first half is the system's decision, not ours.** Releasing ~150 MB of decoder buffers
   makes the low-mem destroy less likely; it cannot make it impossible, and a run that sees one is not a
   failure of this phase. Keep the measurement, make the second half — a forced destroy (`am kill` and
   *Don't keep activities*, both) returns to the player at the position — the pass/fail line.

**Small corrections.** "R192's *resume without a full player rebuild* design note" is not in R192's spec;
it is two code comments (`RaviloPlayer.kt:63-66`, `RaviloPlayerAndroid.kt:372-374`) — both get rewritten
by FR-R292-10, so cite them as such. FR-R292-7 says "the activity": there are two (`.android.MainActivity`
and `.phone.MainActivity`), and `colorMode` goes on both. R218's shutter already gates on
`Loading || (Ready && !hasRenderedFirstFrame)` (`PlayerScreen.kt:1584`), so FR-R292-3's "presented as one
start" follows once the return goes through `Loading` — which `startSession` does; nothing new to draw.
The `wasmJs` `PlayerLifecycleEffect` (a 15-line no-op) and `RaviloPlayer` actuals must still compile
against any expect-signature change; the spec's "may treat these calls as no-ops" covers it.

**Net effect.** The phase is right about what to delete and what to keep. Before building: one additive
request field (item 2), the record captured from the engine under R184's guard (item 3), saved state as
one serialised string (item 4), all of it outside `PlayerScreen`'s body (item 5), and the engine as a
nullable ref with one re-bind function that owns every listener (item 6).
