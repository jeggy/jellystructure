# A song that waits says so

Task: DEB-3032

> Owner, 2026-10-30: *"I was steaming music today at the work office via ravilo on mac and then at around 14.57 it
> started lagging a lot. Can you check why and maybe create some bug tasks on it"*

## Why

The server sees a song start and stop. It does not see a song wait. On 2026-10-30 one song took 61 s to make its
first sound, and that was only recovered afterwards, by extrapolating `position_at − position_ms` on the one song
the session still held. A wait on an earlier song leaves nothing to extrapolate from, and a wait that ends in a skip
looks exactly like a skip. Without this, the next report of music lagging cannot be answered from the server: nothing
separates a slow path from a stalled player, and the phone and the desktop apps are no better off than the Mac.

The session timeline (`playback_session_event`, Phase 304) is what told a pause from a freeze on that report. It is
durable, per session, and already shown in the admin's *Playing now*. A wait belongs there.

**Not in `playback_qoe`.** That table has no row for any song, and it must stay that way. `qoeMeasurementOf` reads a
device's newest 20 rows as the film ladder's throughput samples (FR-309-13), `recentForDevice(…, 1)` is the admin
device row's *recent quality*, and `recent(30)` is the admin's quality list. A listening session posts a row every
few minutes, so songs would push a device's film measurements out of the window and change where its next film starts.

## Requirements

**FR-1 — The engine reports a wait.** While the listener wants a song playing and the player has no data to play for
**2 s or more**, the music engine sends one progress report at once with `waiting = true`. Every later report during
that wait also says `waiting = true`. When the position moves again it sends one report at once with `waiting = false`.

- "No data to play" is the state the engine already publishes as buffering: `DesktopEngine` state `buffering` on the
  Mac and Linux, `Player.STATE_BUFFERING` on Android.
- A wait under 2 s reports nothing. An ordinary song start takes about that long.
- A pause ends a wait, because the listener no longer wants the song playing.
- Songs on the local engine only. A song on a speaker is the receiver's. An audiobook part reports through
  `audiobookProgress` and is out of scope here.

**FR-2 — What it says.** Each report with `waiting = true` carries `wait_detail`, at most 120 characters:
`phase=<start|seek|mid> pos=<ms> buffered=<ms> observed=<bps>`.

- `start` means this song has made no sound yet, `seek` means the wait began within 1 s of a seek, `mid` is the rest.
- `observed` is left out where the player has no figure.
- The Mac adds `why=<reasonForWaitingToPlay>`.

**FR-3 — The wire.** `PlaybackProgressRequest` gains two optional fields, `waiting: Boolean? = null` and
`wait_detail: String? = null`. An older app omits them and behaves as today. An older server ignores them, because its
ContentNegotiation decodes with unknown keys ignored. Nothing is removed or retyped.

**FR-4 — The timeline.** `PlaybackSessions.onProgress` writes to the session's timeline, with the device as the source:

- `waiting`, on the change from not waiting to waiting, with `wait_detail` as its detail;
- `waited`, on the change back, with detail `<ms> ms`;
- `waited`, with detail `<ms> ms abandoned`, when the song is stopped or replaced while still waiting.

The server reduces `wait_detail` to `[A-Za-z0-9=._:() -]` and 120 characters before writing it, as `sanitizeEventsPrev`
does for its header. The waiting state lives in memory on the session: no column, no migration, no `revision` bump and
no `SessionChange`, so no remote or receiver sees a wait. A backend restart during a wait writes a second `waiting`
when the next report arrives. That is acceptable.

**FR-5 — The admin reads it.** `PlayingNow.kt`'s timeline names the two events *Waiting for data* and *Waited*, and
shows the detail beside them as it does for other events.

**FR-6 — Films are untouched.** No song posts to `/api/tv/playback/qoe` because of this spec, and the film player's
reports do not change. A test holds that a `waiting` report writes no `playback_qoe` row and leaves `takeOf` unchanged.

**FR-7 — A pause says what sent it (added 2026-10-30).** The owner did not pause the music on that report, yet the Mac
reported four pauses that day. On the Mac a reported pause means a pause command reached the player, and several
things can send one. The timeline cannot say which.

- `PlaybackProgressRequest` gains an optional `pause_by: String? = null`, sent on the report that first says
  `is_paused = true`. Absent from an older app, ignored by an older server, as in FR-3.
- Values: `screen` (an on-screen control), `keyboard` (the space bar), `dock` (the Dock menu), `now_playing_pause`,
  `now_playing_toggle` and `now_playing_stop` (macOS's three Now Playing commands, which are how media keys, Control
  Center and headphones reach the app), `remote` (a session remote or the Jellyfin dashboard), `engine` (the engine's
  own stop, such as the end of the queue). Android adds `media_session` and `audio_focus`, the latter from Media3's
  play-when-ready change reason. Anything else omits the field.
- `PlaybackSessions.onProgress` writes it as the detail of the `paused` event, sanitised as in FR-4, and the admin shows
  it as it shows other details.
- The desktop engine also prints one line to its log: `music: paused by <value> at <ms>`. The log stays on the machine.

## Acceptance

1. `linuxX64Test --tests '*PlaybackSessionsTest*'`: the three transitions of FR-4, the sanitising, no revision bump,
   FR-6, and FR-7's detail on the `paused` event.
2. `:ravilo-ui:desktopTest`: the decision in FR-1 and FR-2 as a pure function with a test clock. A 1.9 s wait sends
   nothing, a 2 s wait sends once, the end sends once, a pause ends it, and `phase` comes out right.
3. On a device: with the network cut under a playing song, the session's timeline shows `waiting`, then `waited` once
   the network returns, with a wait matching the time it was cut. Mac and Android phone.
4. On a Mac: a pause from the bar, from the space bar and from a media key each show their own `pause_by` on the
   timeline.

## Scope

One build piece across `shared`, the backend, `wasmJsMain` and the three engines. It is a wire change, so it stays
with the engineer and is not a junior piece. It changes nothing a viewer sees or hears.
