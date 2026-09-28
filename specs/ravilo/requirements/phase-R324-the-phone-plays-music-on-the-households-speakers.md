# Phase R324 — The phone plays music on the household's speakers

> Owner, 2026-09-28: Ravilo music plays on the Cast speakers, keeps playing when the phone is closed, and is picked
> up again on reopen.

## Status

`Planned` — written 2026-09-28 from `research-reports/music-cast-to-speakers-2026-09-28.md` (§6.6–§6.11, §7, §8)
and the mockups `design/ravilo/Speakers - Directions.html` (round 1) and the build in `design/ravilo/Ravilo
Mobile.html` → `mobile/ravilo-speakers.js` (+ the `spk-*` rules in `mobile/ravilo-music.css`). **Not dev-reviewed.**
Number verified free on `main` 2026-09-28. The receiver side is admin **286**; both ship together.

**Builds on** R265 (the sheet) · R270 (a busy TV names its viewer) · R245 (hand-off, reconnect) · R321/R322 (the
listening mode, the Playing tab, the mini bar). Android only: no browser has a Cast sender.

## Decisions (owner, 2026-09-28)

| # | Question | Answer |
|---|---|---|
| Q1 | Starting a cast from an album | **Replaces the speaker's queue** with that album; *Add to queue* still appends |
| Q2 | Swipe-down on the mini bar while casting | **Only hides the bar** — the room plays on; a toast says so with *Stop* |
| Q3 | Volume | **The phone's keys + a slider in ⋯** |
| Q7 | The iPhone | **One footnote in the sheet** (*best experience*) |
| — | A speaker busy with another app | **Tappable**, and it asks *Stop Spotify and play here?* first (owner) |
| — | The sheet's title in music mode | ***Play on…*** (a speaker is not a TV); video mode keeps *Play on a TV* |

## Requirements

**FR-R324-1 — Speakers in R265's sheet, music mode only.** The sheet is R265's, unchanged in shape — *All your TVs
(n)*, *Add a TV* and the AirPlay footnote stay. In music mode it is titled **Play on…** and *On this network* lists
the Cast audio routes first: a speaker (speaker glyph · *Speaker · Ready*), a group (group glyph · *Speaker group ·
Ready*), a display (the hub), then the TVs as before. **In video mode no audio-only route is listed, and nothing
says so** (Google's rule; R265's absent-not-empty).

**FR-R324-2 — Row states.** Ready · **Playing · {song}** (a speaker already playing Ravilo — tapping it **joins**:
this phone becomes a second remote for the same queue, nothing restarts) · **Busy · {app}** (the Cast status's
running-app name). A busy row is **tappable**: it opens a small sheet — ***Stop {app} and play here?*** · *{device} is
playing {app}. Playing here stops it for whoever started it.* · **Play on {device}** · *Cancel*.

**FR-R324-3 — Hand-off both ways.** Casting a song that is playing on the phone hands **position and queue** over
(R245 FR-R245-4 for music); the bar says *Sending to Stue…* → *Playing on Stue* (~2 s) and retires. *Play on this
phone* pulls position and queue back and stops the speaker. Q1: starting an album while casting replaces the
speaker's queue.

**FR-R324-4 — The Playing tab is the remote.** Under the artist, a device chip *Playing on Stue* (tap → the sheet);
the cast glyph lit; transport acts on the receiver; the position is the receiver's report; the Queue tab mirrors
the receiver's queue (drag and remove are sent to it) with *on Stue* by its label. **Lyrics stay on the phone** and
scroll by the receiver's position.

**FR-R324-5 — ⋯ while casting** gains, above the song's own entries, a block titled with the device: a **volume
slider** in 5 % steps · **Lyrics on {device}** (displays only; a switch, *Off* at first, remembered per display;
disabled with *This song has no timed lyrics* when it has none — 286 FR-286-6) · **Play on this phone** · **Stop
casting** (in the warning colour).

**FR-R324-6 — The phone's volume keys drive the speaker** (the cast session hands them over); Android's own panel,
named for the speaker.

**FR-R324-7 — The mini bar** reads *{song} · {artist} · {speaker glyph} Stue*, the device never truncated before the
artist. **Swipe-down hides the bar only** (Q2): the room keeps playing and a toast says ***Still playing on Stue*** ·
**Stop** (5 s). Opening Playing, or starting a song, brings the bar back. With a film casting at the same time, the
two bars stack, the film's on top (R322's stacked frame).

**FR-R324-8 — Reconnect** is FR-R245-5 unchanged: on reopen, either the bar shows **the song the room is on now**
at its position (Playing and Queue rebuilt from the receiver), or — the queue ended while the phone was away —
nothing at all, and Playing shows the last-played song paused (R322 FR-R322-3). No *Reconnecting…*, no *the speaker
finished* line.

**FR-R324-9 — The lock screen** while a cast runs is the Cast SDK's notification: cover · title · artist with
play/pause · previous · next · stop casting (music actions, not ±30 s), replacing R322's own media session.

**FR-R324-10 — The iPhone** lists no speakers, groups or hub, and says once, under the rows: ***Speakers need the
Android app for now***.

**FR-R324-11 — Strings** × en · da · fo (da/fo drafts in `ravilo-i18n.js`; the shipped `i18n/*.json` wins):
`cast_sheet_music` *Play on…* · `cast_speaker` *Speaker* · `cast_group` *Speaker group* · `cast_busy_with` *Busy ·
{app}* · `cast_take_over` *Stop {app} and play here?* · `cast_take_over_sub` · `cast_play_on` *Play on {device}* ·
`cast_speakers_ios` · `cast_still_playing` *Still playing on {device}* · `cast_stop_room` *Stop* · `cast_lyrics_on`
*Lyrics on {device}*. `cast_play_here` (*Play on this phone*) is R299's, reused.

## Out of scope

The iPhone reaching a speaker (needs 286's road C) · AirPlay speakers · casting a film to a speaker.

## Acceptance

1. Pixel 9, music mode: the sheet is titled *Play on…* and lists Stue, Gæsteværelse (*Busy · Spotify*), the group,
   the hub and the TVs; in video mode only the hub and the TVs.
2. Tapping Gæsteværelse asks first; confirming plays there and Spotify stops.
3. A song playing on the phone moves to Stue at the same position; *Play on this phone* brings it back.
4. The volume keys move Stue's volume; the ⋯ slider agrees.
5. Swiping the bar down leaves Stue playing and shows the toast; *Stop* stops it.
6. Locked for an hour and reopened: the bar shows the song Stue is playing now.
7. iPhone: no speaker rows, one footnote.
