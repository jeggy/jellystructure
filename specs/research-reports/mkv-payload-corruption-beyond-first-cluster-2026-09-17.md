# MKV corruption beyond the first cluster — what phase 201's repair left behind

**Date:** 2026-09-17 · **Status:** research, no spec, **nothing on production was touched** (read-only
header walks and `ffmpeg -c copy -f null` demux passes at `nice 19`).

## Why this was looked at

A status pass asked whether phase 201's "164 files still unrepaired on production" was still true.

## Findings

1. **Phase 201's own defect is gone.** A header-only EBML walk of all **7 938** `.mkv` files the
   production catalog knows finds **0** with `Tracks` after the first `Cluster`. (One catalogued file
   no longer exists on disk: *Monster — The Lizzie Borden Story* S01E07.)
2. **The v1.13 element-size check (first `Cluster` only) flags 2 files**, both in
   *Hoppe Hares Byggebande* (Hoppy Hare Builders).
3. **A full demux pass finds far more, and it is payload damage, not layout.** On three of the five
   titles inside the 2026-09-13 concurrent-repair race's blast radius:

   | title | files | structurally corrupt | what ffmpeg says |
   |---|---|---|---|
   | Murer Ben (1999) | 72 | **42** (all in S13–S16) | element sizes overrunning their cluster **mid-file**, invalid EBML lengths, invalid track numbers, **H.264 decode errors** (`left block unavailable`, `cabac decode … failed`) |
   | Hoppe Hares Byggebande (2022) | 93 | **20** | same set; offsets from 0x16c81 to 0x970ac47 |
   | The Tidier (2021) | 18 | **4** (S03E02–E05) | same |
   | Chore Captain (72 files, 126 GB) · Ashworth (32 files, 44 GB) | — | **not checked** | too much disk I/O for an evening with viewers on the box |

   Hoppe Hare episodes: S00E13 S01E04 S01E06 S01E07 S01E09 S01E10 S01E13 S01E19 S01E21 S01E22 S01E26 S01E28 S01E30 S01E32 S01E33 S01E35 S01E39 S02E03 S02E33 S02E39 

   (Nine more Hoppe Hare files only report `non monotonically increasing dts` on subtitle stream 5 —
   a null-muxer complaint about the source's subtitle timestamps, not corruption. Excluded.)

## What it means

- The damage sits **anywhere in the file**, so the shipped detector — deliberately bounded to the first
  cluster so a library sweep stays cheap — cannot see it. "Fix now" reports these titles clean.
- It is **data loss, not a layout problem**: bytes inside clusters are wrong (video packets fail to
  decode). A `-c copy` remux makes the container valid again by *dropping* what it cannot parse; the
  picture glitches where the bytes were lost. The only real repair is **the original file again**.
- The viewer symptom differs from 201's: not "loads forever" at the start, but a stall, a skip or a
  macroblock smear part-way through — easy to mistake for the stue TV's Wi-Fi.
- The shape (kids' series, the exact seasons the 2026-09-13 race ran over) says this is that race's
  damage and **not an ongoing writer** — phase 201's temp-file fix shipped in `c10716ef`. That is an
  inference: nothing here proves no file has been damaged since.

## Options, with a lean

1. **Re-acquire the damaged files** (lean). 66 files across three kids' series; Sonarr can re-grab
   them. jellystructure's *arr integration is read-only by design, so this is a hand job in Sonarr
   (delete file → search), or a product decision to add one write action.
2. **Remux in place** with the existing repair route. Fast, and it stops ExoPlayer from stalling —
   but it makes the loss permanent and silent, and the v1.13 detector would not even select these files.
3. **A title-scoped deep check** as a segments-lane-style job (playback-deferred per 178/213): one
   demux pass per file of one title, results into Triage. Worth building only if Chore Captain and
   Ashworth also turn out damaged, or if the "not an ongoing writer" inference is ever contradicted.

## Addendum, same evening — the operator's own report on S01E05, checked

The household operator filed a bug report the same day on one file from this set (*Hoppe Hares Byggebande* S01E05, `DirectPlayError` on the Jellyfin Android TV app, repaired by a stream-copy remux
at 19:13). It is the same incident. Two of its points change this report, one of its claims does not
hold:

- **The clean originals are still on disk.** The seeding copies under
  `/mnt/series/qbittorrent/downloads/` exist for Ben the Bricklayer S13–S16 and Hoppy Hare Builders
  S01/S02, are separate files (link count 1) and demux with zero errors. **Option 1 needs no
  re-download: copy the seeding file over the library file, then re-apply the track flags.**
- **The gate gap is real.** `MkvpropeditRunner.verifyAndRepairLayout` returns success for anything but
  `TRACKS_AFTER_CLUSTER` (`:45`), so an `ELEMENT_SIZE_OVERFLOW` right after an edit is reported as fine.
- **"No quality loss" is not true of the remux.** Packet counts, clean source vs library copy:

  | file | video | audio 0 | subtitles |
  |---|---|---|---|
  | S01E05 source | 16 093 | 20 954 | 119 |
  | S01E05 after the remux | 15 847 (**−246**, ≈10 s) | 20 625 (−329) | 117 (−2) |
  | Ben the Bricklayer S13E12 source | 15 001 | | |
  | Ben the Bricklayer S13E12 library, still corrupt | 14 859 readable (**−142**) | | |

  The duration is unchanged because the gaps are inside the file. A remux makes the container valid by
  discarding what it cannot parse. S01E05 should be replaced from its seeding copy like the rest.

## Addendum 2 — sources verified, fix spec'd, replacement not yet run

- **62 of the 63** damaged Ben the Bricklayer and Hoppe Hare files (the 20 + 42 above, plus the remuxed
  S01E05) have a same-named seeding copy that demuxes with zero errors and has the same stream layout.
  The one without is Hoppe Hare **S00E13**. *The Tidier* S03E02–E05 have **no local source** and
  need a Sonarr re-grab.
- The code side became **phase 234** (`✓ Built`, not deployed): the post-edit gate now repairs both
  broken layouts, one file has one writer, and a lossy repair says so in the log.
- **The replacement itself has not been run.** It is an operator action on production media; the
  procedure is: stream-copy the *source* into the library folder carrying over the library copy's
  per-track language/title/default/forced flags, verify (zero demux errors, packet counts equal to the
  source, flags equal to the old copy), quarantine the corrupt file, rename into place.

## Still to do

- Check Chore Captain and Ashworth at a quiet hour (≈170 GB of sequential reads).
- Decide between options 1 and 2 — the owner's call; it concerns production media files.

## Reproduce

```bash
ffmpeg -nostdin -v error -xerror -i "$f" -map 0 -c copy -f null - </dev/null 2>&1 | head -1
```

`-nostdin` matters inside a `while read` loop: without it ffmpeg eats the file list and every second
file reports *No such file or directory* — which is how this pass first produced a wrong count of 59.
