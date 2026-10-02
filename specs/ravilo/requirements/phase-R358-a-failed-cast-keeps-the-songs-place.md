# Phase R358 — A cast that never started gives the song back where it was

> Found 2026-10-02 in the Mac cast test (R330/R353 sender on the MacBook, music to a speaker whose Jellyfin token
> was dead): a song playing at about 0:30 was cast; the speaker launched Ravilo's receiver but never got a stream
> (the backend logged the receiver's token as rejected, HTTP 401); the bar showed 0:00; after *Stop casting* the song
> came back on the Mac at **0:00**. Owner, 2026-10-02: *"Also this part — a failed cast resets the song to 0:00."*

## Status

`Planned` — written 2026-10-02 (dev-authored, owner-approved the same day) against `main` `91a12ee8`. Number checked
free (Ravilo specs top at R357). **Amends** R353 FR-R353-5 (the hand-back keeps the speaker's place — but only a
place the speaker actually reported) and R330 (the Mac's cast sender). Applies to every sender that hands music to a
speaker and back: the Mac and Linux desktop sender and the Android sender. No wire change, no string, no UI change.

## What is wrong

When a song moves to a speaker, the sender's bar follows the speaker. A speaker that never plays reports no media
status, so the bar sits at 0:00. Ending the cast hands back "where the speaker is", and with no report that is
read as 0:00, so the song restarts.

## Requirements

- **FR-R358-1 — the place handed back.** Ending a cast (Stop casting, *Play on this phone/this computer*, the cast
  dropping, a Cast error) hands the music back:
  - at the speaker's last **reported** song and position, if the speaker ever reported playing (R353 FR-R353-5,
    unchanged);
  - otherwise at the song and position the sender had **when it handed the music over**. A position of 0 that nobody
    reported is never a place.
- **FR-R358-2 — while waiting.** Until the speaker's first report, the sender's bar shows the hand-over song and
  position (not 0:00). It stays paused-looking until the speaker reports playing.
- **FR-R358-3 — the hand-back is paused** where it lands, as R353 already does; Play resumes from there.
- **FR-R358-4 — one rule for every sender.** The rule lives in common code (the shared hand-back/merge both senders
  use), with a unit test: hand over at 30 s, no report, stop ⇒ the same song at 30 s; hand over at 30 s, a report at
  45 s on the next song, stop ⇒ that song at 45 s.

## Verification

- Unit: the cases above, plus a report of position 0 while the receiver is still loading (no playing state) is not
  a place.
- Live, on the Mac: play a song, seek to ~0:30, cast to a speaker that cannot play (or kill the cast before the first
  report), Stop casting ⇒ the song is back at ~0:30, paused. Only the Gæsteværelse speaker or the Køkken hub; never
  the Stue speaker, the Stue TV or the Soveværelse TV.
