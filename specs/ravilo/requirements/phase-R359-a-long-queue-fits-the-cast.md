# Phase R359 — A long queue still casts: no message over the Cast limit

> Found 2026-10-02 21:07 testing R357/R358 on the MacBook: casting a 487-song queue to the Gæsteværelse speaker, the
> Mac logged `cast: load music, 487 in the queue on Gæsteværelse` and 25 ms later `session to Gæsteværelse closed (the
> device closed the connection)`. The receiver launched and never played. The same album-sized queue (26 songs) casts
> fine. Probably also behind the earlier "failed cast" on the Mac the same day, which was put down to a dead token.

## Status

`Planned` — written 2026-10-02 (dev-authored) against `main` `575cc8d5`. Number checked free (Ravilo specs top at
R358). **Amends** 286 (FR-286-4 and its dev review 3: "30 songs ≈ 4 KB, so the whole queue rides one LOAD") and R324.
Both senders (the Mac/Linux Cast v2 sender, the Android Cast SDK sender) and the Cast receiver (`ravilo-cast`).
Additive wire change only (a receiver ignores what it does not know); no string; no UI change.

## What is wrong

A Cast v2 message has a hard ceiling of 64 KB (`CastFrameReader(maxFrame = 64 * 1024)` on our side; the device closes
the connection on a bigger frame). The music LOAD carries the **whole queue** as `CastTrackItem`s (title, artist,
album, cover URL…, ~150–250 bytes each), and both senders put `CastLoadData` **twice** in the same LOAD — once as the
media's `customData`, once as the request's (`CastSenderDesktop.castMusic`; `CastSenderAndroid` lines ~483 and ~489).
So a queue of roughly 150–200 songs and up is over the limit; *Shuffle* on an artist or *Play* on Songs makes one in a
tap. 286 assumed queues of about 30.

## Requirements

- **FR-R359-1 — every sender message fits.** No message either sender sends to the receiver exceeds **48 KB** of
  encoded payload (a margin under the 64 KB frame), whatever the queue length. Unit-tested on the real encoders with
  1, 30, 487 and 5 000 songs, including long titles.
- **FR-R359-2 — the LOAD carries `CastLoadData` once.** Whichever of the two places the receiver reads stays; the other
  carries nothing (or only what the receiver needs from it). Verify in `Receiver.kt` which it reads before removing.
- **FR-R359-3 — a long queue is sent in parts.** The LOAD carries a window of the queue that fits (the current song
  and as many following it as fit, and the ones before it if room remains — the receiver must be able to start, go
  to *next* and *previous* immediately), plus the queue's total length and a queue revision. The rest follows at once
  in further messages, in order, each one under the budget, on the existing music channel (the same one *add to
  queue* uses), until the receiver holds the whole queue in the sender's order. A receiver that already reports the
  queue revision (R356) reports it only when the whole queue is in.
- **FR-R359-4 — the receiver.** Plays from the window at once; appends/places the later parts without interrupting
  playback, shuffle order kept as the sender sent it; *next* past the window's end while parts are still arriving waits
  for them rather than ending the queue. A part for an older queue revision (the viewer started something else) is
  ignored.
- **FR-R359-5 — joining and taking back.** A sender joining a running cast, `get_queue`, and the hand-back of R353/R358
  work with a queue of any length (the receiver's replies obey the same budget — chunk them too if a full queue reply
  can exceed it).
- **FR-R359-6 — one rule in common code** for both senders (the window and chunking), with the receiver's side
  unit-testable where it can be.
- **FR-R359-7 — logged.** Each sender logs the LOAD size and the number of parts (`load music, 487 in the queue, 31 KB
  + 4 parts`), so a future limit is visible in the log.

## Verification

- Unit: FR-R359-1's sizes; a 487-song queue reaches the receiver model whole and in order; next across the window edge.
- Live: on the Mac and the Pixel 9, a 487-song queue (*Songs* → Play, or the Listen page's recently played) to the
  Gæsteværelse speaker plays, *next* works, the Queue page lists all 487 on the speaker, *Stop casting* hands back.
  Never the Stue speaker, the Stue TV or the Soveværelse TV; speaker volume ≤ 10 %.
