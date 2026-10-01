# Lyrics that do not belong to the song — research, 2026-10-01

**Asked by the owner:** an instrumental demo in a box set shows lyrics. *Investigate it properly and come with ideas
for issues like this one.* Research only: nothing is built and nothing in the library was changed. Measured on a
read-only copy of the production database, the music folder on disk, MusicBrainz and LRCLIB.

## 1. The case

A nine-disc box set holds the same song three times: an **instrumental demo** (3:15), the studio recording (3:18)
and a live recording (3:35). All three are matched to the right MusicBrainz recording, and all three got synced
lyrics. MusicBrainz says plainly that the demo has none: its disambiguation is *instrumental demo*, and its
performance relationship to the song's work carries the attribute `instrumental`. LRCLIB, asked for that artist,
title, album and duration, answers with an entry for that box set at 3:16, `instrumental: false`, with the
studio recording's words and timing. The demo and the studio recording got byte-identical `.lrc` files.

The album was matched (from the files' own ids) 18 seconds before the lyrics were fetched, so this is not an
ordering problem: **the lyrics step never looks at the recording.** 277 FR-277-8 asks LRCLIB by text and duration
and writes whatever comes back.

## 2. How lyrics reach a listener today

- `fetch_lyrics` (277): LRCLIB `get` by artist · title · album · duration (LRCLIB's tolerance is ±2 s), then a
  `search` by artist · title within ±3 s. Synced → `{track}.lrc`, plain → `{track}.txt`, LRCLIB's instrumental flag →
  state `instrumental`, nothing → `none` (asked again after 30 days). An existing sidecar is never overwritten.
- Jellyfin reads the sidecar on its next refresh. Ravilo's lyrics route asks **Jellyfin first** and falls back to
  our sidecar, so a removed sidecar keeps showing until Jellyfin refreshes the song.
- Nothing records which sidecar jellystructure wrote or where it came from.

## 3. What the library looks like

487 songs. 439 have a sidecar (401 synced, 38 plain); 16 are `instrumental`, 32 `none`.

**Where the sidecars came from.** Replaying LRCLIB's exact `get` for all 439: **414 are byte-identical to today's
exact answer**, 19 differ from it (LRCLIB changed since), 6 came from the `search` fallback. The fallback is not the
problem; LRCLIB's exact answers are.

**Class 1 — lyrics on an instrumental: 10 songs.** Nine instrumental demos in the box set, and one B-side whose
title ends in *(instrumental)*. MusicBrainz knew for all ten. The rule that finds them and nothing else:

> a recording is instrumental when it has performance relationships and **every** one is either marked
> `instrumental` or points at a work whose language is `zxx` (MusicBrainz's *no linguistic content*); with no
> performance relationship, when its title or disambiguation says *instrumental* or *karaoke*.

*Every*, not *any*: four live recordings open with an instrumental intro piece (a `partial` performance of a `zxx`
work) and then a sung song; *any* flags them. The other way round, LRCLIB marks 4 songs instrumental where
MusicBrainz links a sung work (one is an *instrumental remix* whose MusicBrainz entry lacks the attribute, one is a
taped intro). Two songs on a `zxx` work sit at `none` — right in effect, wrong in name.

**Class 2 — the words are right, the timing belongs to another version.** LRCLIB holds a separate entry for most
versions in the box set (durations to the microsecond — a library tool publishing in bulk), each carrying the
**studio recording's timing**. In our library:

- **61 synced songs share one timing with a different recording whose length differs by more than 3 s** (22
  groups). One timing can fit at most one length, so at least 39 of them are out of sync. Examples: a 2:46 demo
  whose last line is at 3:04; a 5:29 live version on the 4:12 studio timing.
- **5 synced files have a last line after the song's end.**

Two ways to pick the right owner of a shared timing were tried and **rejected**:

- *The earliest LRCLIB entry with this timing is the original*: 94 flags across the library, visibly noise — LRCLIB
  ids do not order originals, and copies run in both directions.
- *Keep the timing on a recording without a MusicBrainz version note*: 30 keep, 31 drop, but 5 of the 22 groups
  are undecidable (notes such as *album version*, or one naming the album, mark the canonical recording; many live
  titles carry no note at all).

## 4. Ideas

**A — Instrumental is a fact about the recording (fixes class 1; lean: yes).** Fetch the chosen release with
`inc=recordings+recording-level-rels+work-rels` (same request count as today) and keep a per-track verdict from §3's
rule. Instrumental from **any** source wins — MusicBrainz, the title, or LRCLIB's flag — because wrong words on an
instrumental are worse than no words (the owner's rule from 273: *rather nothing than something wrong*). Then: no
lookup, our own sidecar removed, Jellyfin refreshed, Ravilo's lyrics route answers nothing for that song even while
Jellyfin still holds a copy, and the Tracks tab says *Instrumental · MusicBrainz*. Fixes the 10 at once.

**B — Know which sidecars are ours (needed by A, C, D; lean: yes).** Record on the track what was written: LRCLIB's
id, its duration, and a hash of the file. Only a file we wrote is ever changed or removed. Backfill: a sidecar
written during a fetch run (its time ≥ the track's `lyricsCheckedAt`) is ours — 439 of 439 here.

**C — A timing must fit the song (class 2; lean: the certain half now).**
- *Certain:* a last line after the song's end → keep the words, drop the timing (`.txt`).
- *Certain that something is wrong:* one timing on recordings of different lengths in the library → the words stay,
  the timing is dropped on all of them until the admin says which one it fits (D). This costs sync on up to 22
  songs whose timing was right; it removes it from at least 39 whose timing was wrong.

**D — The admin decides, and the decision holds (lean: yes).** Per song on the Tracks tab: *Instrumental* ·
*Words only* · *Remove lyrics* · *Find again*, and *Timing fits this one* on a shared timing. Each is a lock that
`fetch_lyrics` respects.

**E — Lyrics belong to a recording, not a file (lean: yes, small).** The same recording on several albums (a song
on its album, a best-of and a box set) shares one verdict, so a decision on one copy holds for all.

**F — Check the timing against the audio (research, not promised).** 273 found a speech detector for subtitles;
singing over centred bass and drums is harder. An experiment at most, and only for groups C cannot decide.

**G — Tell LRCLIB (outward; owner's call only).** LRCLIB's publish API can mark a track instrumental. Never
automatic; at most an admin action, and only if the owner wants jellystructure writing to a public database.

**Not proposed:** a second lyrics provider (the polluted entries are the crowd's, not LRCLIB's code; another crowd
source has the same failure), or trusting LRCLIB's `search` fallback less (6 of 439).
