# Phase R359 — A long queue still casts: no message over the Cast limit

> Found 2026-10-02 21:07 testing R357/R358 on the MacBook: casting a 487-song queue to the Gæsteværelse speaker, the
> Mac logged `cast: load music, 487 in the queue on Gæsteværelse` and 25 ms later `session to Gæsteværelse closed (the
> device closed the connection)`. The receiver launched and never played. The same album-sized queue (26 songs) casts
> fine. Probably also behind the earlier "failed cast" on the Mac the same day, which was put down to a dead token.

## Status

`✓ Built` 2026-10-02 (build notes at the end); deployed 2026-10-02 (backend + receiver `v1.48-121-gaf679d64` (commit `53dbccc9` since the 2026-10-02 re-timing)), **device-tested on the Mac**: a 487-song queue to the Gæsteværelse speaker — 146 songs in the LOAD, the rest in parts, the receiver whole (487) about 1 s later, playing, *next* works, *Stop casting* hands back with the queue (before: the speaker closed the connection 25 ms after the LOAD). Android not device-tested (the Pixel was signed in to the demo server, which has no casting). Written 2026-10-02 (dev-authored) against `main` `575cc8d5`. Number checked free (Ravilo specs top at
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

## Build notes

Built 2026-10-02 on `main` `0fbfd677` (commit `1db3bc3e`). Not deployed, not device-tested.

- **One rule, in `shared`** (`tv/CastQueueParts.kt`, FR-R359-6): `CAST_MESSAGE_BUDGET_BYTES` = 48 KB;
  `castWireBytes` counts UTF-8 bytes plus one per `/` (Android's `JSONObject` writes `\/`, and cover URLs are full of
  them), so one count is an upper bound for both senders and the receiver. `castLoadPlan` returns the LOAD and the
  parts: a queue that fits (≤ 40 KB of `CastLoadData`, 8 KB left for the media envelope and the song card) is one
  LOAD exactly as before; a longer one gets a **window** — the current song, the one before it, as many after as fit,
  then as many before as still fit — and `queue_part` commands: the songs after the window first, in order, then the
  ones before it, last part first, so the receiver's run only ever grows at one end. `castQueueReply` splits a
  receiver status whose queue does not fit; `CastQueueAssembly` puts it back together on a sender.
- **Wire (additive).** `CastLoadData` gained `queue_id` (a fresh id per sender LOAD), `queue_total` (set only on a
  window) and `queue_start`; `CastCommand` gained `queue_id`, `offset`, `tracks` for `queue_part`;
  `CastReceiverMessage` gained `queue_parts` (on a status: this many parts follow) and `queue_offset` (on a
  `queue_part` message, which carries `queue`, `queue_rev`, `queue_size`).
- **FR-R359-2.** The receiver has read `request.media.customData ?: request.customData` since R245, so both senders
  now send `CastLoadData` only as the media's; the request carries none. Every receiver ever deployed reads it.
- **Senders.** Mac (`CastSenderDesktop.loadNow`, media built by `castLoadMedia`) and Android (`castLoadRequest`):
  plan, LOAD the window, send the parts at once on our namespace (the Mac after the LOAD is queued; on Android the two
  channels are not ordered, which the receiver allows for). The remote's own status holds the **whole** queue from the
  start; the receiver reports each song's place in the whole queue while parts arrive, so nothing jumps. A received
  `queue_part` is not a state report: it is fed to the assembly and, once whole, the queue is held as if a status had
  carried it (`castStatusWithQueue`). `castQueueGap` does not ask `get_queue` for a status that says parts follow.
  `CastSession.load` now returns the frame size; FR-R359-7's line: `cast: load music, 487 in the queue, 40 KB + 4
  parts on …` (Mac), `R359: load music, …` (Android, `RaviloCast`).
- **Receiver** (`Receiver.kt`): plays from the window at once; a part joins the run at either end
  (`castQueueAttachAll`), one that overtakes its LOAD (or the part before it) is held; parts of another queue id are
  dropped. *Next* past the run's end (or repeat-all's wrap while the first songs are still coming) and *previous* at
  the run's start wait and go on when the song arrives (`castNextIndex` → `Wait`); `play_at`, the queue edits and
  shuffle sent while arriving wait for the whole queue (their places are the whole queue's). If the rest has not come
  in 15 s, the songs held become the queue. The receiver's own next loads take the queue from `current` (found again
  by place, else by id), so a part that lands while the next song loads is not lost. While arriving, a status carries
  no queue and **no revision** (FR-R359-3); once whole, the revision moves once and the queue goes out — in parts when
  it does not fit (FR-R359-5, also for `get_queue`, a sender connecting, and `ended`, which leaves a long queue out).
- **Measured** (test fixtures with realistic ids, titles and cover URLs): 487 songs — the old LOAD frame was 313 KB
  (Mac) / 314 KB (Android), the whole queue twice; now **40 KB + 4 parts** (largest 47 KB) on the Mac, 41 KB + 3
  parts on Android. 1, 30, 487 and 5 000 songs, short and long titles, current song at either end and the middle: every
  frame ≤ 48 KB. 30 songs (and the 26-song album) still go as one LOAD with no window.
- **Ages.** New sender + receiver older than R359: the LOAD fits, the old receiver plays the window (next/previous
  within it) and ignores the parts; its status then names the window as the queue, which the remote takes — a long
  queue is shortened, never a failed cast. Old sender + new receiver: unchanged for a queue that fits; an old sender's
  long queue still fails at its own LOAD (nothing on the receiver can help). A receiver's queue too long for one
  message reaches an old sender as a status without it: an R356 sender asks `get_queue` once and keeps the queue it
  holds (it ignores the parts); the old Mac's 64 KB frame reader is no longer handed a bigger message.
- **Tests:** `CastQueuePartsTest` (shared: sizes on the real encoder, the 487 case, window/next edge, out-of-order and
  stale parts, the receiver's split reply and the sender's assembly), `CastLoadSizeTest` (desktop: the Mac's real
  LOAD and part frames), `CastLoadRequestTest` (Robolectric: the SDK's `MediaLoadRequestData.toJson()`),
  `CastSessionTest` (the LOAD's reported frame size), `CastStatusMergeTest` (no gap while parts follow; the assembled
  queue held at the place said). The receiver's Playwright spec (`tests/e2e/cast-receiver.spec.ts`) was not run (it
  needs the e2e stack) and gained no long-queue case.
- **Not done:** device verification (FR's *Live* list: the Mac and the Pixel 9 to the Gæsteværelse speaker, *next*,
  the Queue page listing all 487, *Stop casting* handing back).
