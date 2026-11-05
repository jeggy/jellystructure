# A damaged file still plays, or says why, and never leaves a viewer waiting

Task: DEB-3642

> Owner, 2026-11-05 00:02Z (on DEB-3627): *"please make jellystructure fail-proof to this type of errors
> somehow."*

## Scope

Backend only. Ravilo is not changed, and no field is added, removed or retyped on the wire. A file the checks
know is damaged gets a different **plan**: fewer audio tracks in a list, a different route, a different track
picked by default. An installed client reads all of it through fields it already has.

The work rides the file checks that already run on every file (phase 254's deep check, phase 255's tail probe,
the timeline of `a-file-with-holes-in-its-picture-and-sound-is-found.md`), and makes the playback plan read
them. Today nothing in `tv/` reads any of them. It answers phase 255's open question 3 (avoid a track that is
known to be short). It amends the holes spec's FR-3: `file_timeline` gains one column, with a dated note in
that file. The bounds already
specified on DEB-3627 stay as they are: FR-313-12 (a first encoder job that writes nothing goes to Jellyfin
within 5 s) and FR-R218-7 (a start with no picture at 10 s moves once, then shows the error). They are the net
for a file nobody has checked yet. This spec is about making a checked file play right the first time.

## What happened, measured 2026-11-05

- **The file** (DEB-3627). It is a web capture of one episode. Its Matroska header lists every track twice:
  ffprobe and Jellyfin both see 2 video, 4 audio and 4 subtitle streams. A read of the whole file finds
  packets only in the first set. The second set has **zero packets**.
- **The deep check had already found it.** `file_integrity` stored it as damaged **525 s after the file
  landed**, ten days before the failed starts: 68 damage lines, the first *"0x00 at pos 72900 … invalid as
  first byte of an EBML number"*. Byte 72 900 is inside the header, before the first Cluster. Phase 255's
  track coverage stored `[]` for it, because a track with no packets has no end to compare.
- **What each route does with it:**

  | Route | Maps | Result |
  |---|---|---|
  | Our encoder, today | all 4 audio tracks (`hlsAudioRenditions`) | prod's ffmpeg writes **0 segments in 30 s**. It waits for packets that never come. |
  | Our encoder, real tracks only | video 0, audio 1 and 2 | first segment at once, 8 segments for 30 s of input |
  | Jellyfin's transcode | the selected video and one audio track, `-map 0:0 -map 0:1` | plays. The default audio is stream 1, a real one. |
  | Direct play on the TV | the TV's own demuxer | read 72 901 bytes, stopped at the damage, sat on the start screen for 34 s |

  So the file is playable. It just has to be read without the ghost tracks and without asking a TV to parse
  the broken header.
- **The class on prod.** Of 10 157 files with a current deep check, 14 are damaged. One of them, this file, is
  damaged before its first Cluster. Of 10 156 files with track coverage, 15 have findings: kind A 2, B 4, C 2,
  E 7. The timeline check is not built yet (DEB-3637), and one release alone has holes in 8 files.

## What each kind of damage does to a viewer, and what this spec changes

| Damage | Today | After this spec | Where |
|---|---|---|---|
| **Ghost tracks**: the header lists streams that carry no packets | our encoder hangs, the TV stalls on the header | plays on our encoder at the first try, and the ghosts are not in the track menu | FR-1–FR-3 |
| **Header damage** before the first Cluster | the TV's player stalls on it | not offered for direct play. It goes to our encoder, else to Jellyfin's transcode. | FR-4 |
| **A short track, with a complete track in the same language** (255 kind A) | the server may pick the short one: silence after a few minutes | the server picks the complete one unless the viewer chose otherwise | FR-6 |
| **The header claims more than the file holds** (255 kind E) | the player shows the wrong length. A seek past the real end can wait with no picture, and the episode never reaches 90 % so it never leaves Continue Watching. | the play ends at the real end as finished, and counts as watched | FR-7 |
| **Holes in picture and sound** (DEB-3633) | the player jumps forward over each hole | unchanged: flagged in Triage and explained on the title page (DEB-3637). Nothing restores the missing seconds. Replace the file. | QA leg 5 checks that it never stalls on our encoder |
| **The only audio track is short** (kind C), or **a short track that is the only one in its language** (kind B) | silence from that point | unchanged: flagged, explained (phase 255). The viewer's language choice is not overridden. | — |
| **Damage after the first Cluster** (254) | players skip the bad cluster | unchanged: flagged, and repaired from a source (phase 254) | QA leg 6 checks that direct play does not stall |
| **Sound out of sync** (`file_av_sync`) | plays out of sync | unchanged: flagged | — |
| **A file nobody has checked yet** (the first ~10 minutes after import) | as above | FR-313-12 and FR-R218-7 bound the start. FR-5 checks the file at once, so the next start is right. | FR-5 |

## Requirements

### Knowing which tracks are real

- **FR-1 — the deep check records the streams that carry no packets.** The single read of the holes spec's
  FR-1 already prints one line per packet with its stream. It also counts the packets **per stream**. A video
  or audio stream with **zero** packets over the whole read is **empty**. Subtitle streams are counted too, but
  they never make a file *damaged*, because a subtitle track can rightly be empty. `file_timeline` gains
  `empty_streams TEXT` (a JSON list of absolute stream indexes, `[]` for none), written in the same row and in
  the same migration as the rest of that table if DEB-3637 has not shipped yet. It is current under that row's
  size and mtime rule. A pass that did not finish stores nothing, so the file stays *unchecked*. The empty
  streams are also listed on the title page's integrity block: *"The file lists 2 audio tracks that hold no
  sound. Players that pick them play nothing."*

### Playing it

- **FR-2 — our encoder maps only tracks that carry packets.** When the file has a current `file_timeline` row,
  `Encoder.planFor` leaves every empty stream out of the plan: out of the `-map` list, out of the audio
  renditions, and out of the audio list in the ticket the client gets. Nothing else in the ticket changes
  shape. `fileAudioOrder` (R382) matches the two lists after the empty streams are removed from both.
  - The track the viewer chose, or the one the negotiation carries, may be an empty stream. Then the plan
    carries its **twin**: the first non-empty audio stream with the same codec, language and channel layout.
    With no twin, it carries the default non-empty audio track (else the first). That swap is logged once per
    play: `audio N holds no packets — carrying M`.
  - The same applies to an empty video stream. The plan's video is always a stream with packets.
- **FR-3 — Jellyfin's route is never asked for an empty track.** Every `PlaybackInfo` the server sends
  (start, restream, burn-in, the FR-313-12 fallback) passes an `AudioStreamIndex` that is not empty, using FR-2's
  twin rule. The track menu a client builds from the server's audio list does not show the empty streams.
- **FR-4 — a file whose header is broken is not direct-played.** A file is **header-damaged** when its current
  checks show either of these:
  - an empty video or audio stream (FR-1), or
  - a `file_integrity` damage line whose byte position lies **before the first Cluster**. The position comes
    from the damage line's `pos N`, and the first Cluster from the layout walk the scanner already does
    (`scanMkvLayout`).

  For such a file, every negotiation asks Jellyfin with direct play **and** direct stream off. The start goes
  to our encoder (FR-2), and where our encoder declines, to Jellyfin's transcode (FR-3). This is decided per
  file, never per device, and it is never stored on the device's record. A file that is unchecked or clean is
  negotiated as today, so a new import is never forced to transcode on a guess.
- **FR-5 — a start the bounds had to rescue gets the file checked at once.** When FR-313-12 refuses a first
  job, or a client reports FR-R218-7's move (`start_failure_status=-1`), and the file has no current deep
  check, its `verify_files` job goes to the head of the queue. It is still deferred while that same file is
  playing, and still niced, so the check never competes with the play it is rescuing. The next start of that
  file then gets FR-2 to FR-4.

### Playing it well

- **FR-6 — the server never picks a short track when a complete one says the same thing** (phase 255 open
  question 3, kind A). When the server picks the audio itself (no choice by this viewer on record for this
  item or series, phase 253), and the default-ranked track is **short** in a current `file_track_coverage`
  row, it picks the complete track of the same language instead. The row's `alternativeStreamIndex` names
  it. A choice the viewer made is always honoured, even a short track. Kind B and C are unchanged, because
  there is no complete track of that language to pick.
- **FR-7 — a file whose header claims more than it holds ends where its content ends** (phase 255 kind E).
  The content end is the `file_track_coverage` row's `content_end_ms`.
  - On our encoder, the playlist ends at the content end, and a seek to or past it answers with the end of
    the stream at once: `#EXT-X-ENDLIST` after the last real segment. It never starts a job that cannot write
    a segment.
  - Whatever the route, a play whose position passes 90 % of the content end is reported to Jellyfin as
    played when it stops, as a play past 90 % of a correct header already is. The episode leaves Continue
    Watching. The duration shown to the viewer is unchanged, because it is Jellyfin's `RunTimeTicks`, and
    changing it is a metadata write, not a playback one.

## Non-goals

- **Rewriting any media file.** A remux that drops the ghost tracks (DEB-3640) or a replacement release is an
  operator's decision under the media rules, never a job this spec starts.
- **Any write to Sonarr or Radarr.** Preventing such a grab is a recommendation to the owner (below), not code.
- **A warning in Ravilo.** The viewer gets a file that plays. The explanation is for the admin.
- **Filling holes** with frozen frames or silence (the holes spec's non-goal).
- Changing phase 254's damage rules, phase 255's classifier, or the bounds of FR-313-12 and FR-R218-7.

## Upstream prevention (a recommendation, the owner's call)

The deep check found this file 525 s after it landed, and holes are found by the same read. That is soon
enough to act before anyone presses Play. The arr stack cannot inspect a file before it downloads it, so the
practical gate is after import. A release group whose captures repeatedly fail these checks can be scored
down in Sonarr, and a damaged fresh import can be sent back for a different release. Both are outside this
repository and are asked of the estate on the task, as a recommendation only.

## Acceptance

1. Unit, FR-1: a fixture whose header lists a second audio stream with no packets ⇒ `empty_streams=[2]`, and
   the file counts as damaged. An empty subtitle stream ⇒ listed, and the file is not damaged by it. A pass
   killed half-way ⇒ no row.
2. Unit, FR-2: a file with video 0, audio 1–2, video 5 and audio 6–7, where 5–7 are empty, on a client with
   `hlsAudioRenditions` ⇒
   the encoder command maps `0:a:0` and `0:a:1` only, and the ticket lists 2 audio tracks. A carried index
   of an empty stream ⇒ its twin is carried, and the log line appears once.
3. Unit, FR-3: every `getPlaybackInfo` call site passes a non-empty `AudioStreamIndex` for that fixture.
4. Unit, FR-4: an empty stream ⇒ direct play and direct stream off. Damage at `pos 72900` with the first
   Cluster at 80 000 ⇒ off. Damage at a position past the first Cluster ⇒ as today. Unchecked ⇒ as today.
   The device record is unchanged after such a negotiation.
5. Unit, FR-5: a FR-313-12 refusal on an unchecked file puts its `verify_files` job first in the queue. On a
   checked file, it changes nothing.
6. Unit, FR-6: kind A with no viewer choice ⇒ the alternative stream. With a viewer's choice of the short
   track ⇒ the short track.
7. Unit, FR-7: kind E with `content_end_ms` of 2 873 000 ⇒ the playlist ends at the last segment before it,
   and a seek to 3 000 000 answers with an end-of-stream playlist without starting ffmpeg. A stop at 2 600 000
   ⇒ reported as played.
8. On a device, QA (plan on the task): the original damaged file from DEB-3627 reaches a picture **on our
   encoder** within 3 s with no 5 s refusal in the log, and with its two real audio tracks in the menu. On a
   link fast enough for direct play, it still plays, from our encoder.
