# Phase 201 — mkvpropedit moves the track list to the end of the file, and Ravilo can never start it

> Live report, 2026-09-08: *"it seems like special seasons dont work in ravilo? when playing snurre
> snup byggevenner season 0 episodes it just loads for ever"*
>
> It is not season 0. It is **164 files** across 7 titles, including 35 of the 39 Season 1 episodes of
> that same series. The viewer landed on a working Season 1 episode first and a broken Specials one
> second, and the coincidence named the wrong axis.

## Status
✓ Built 2026-09-10 (FR-201-1/2/3/4/6/8). Root cause corrected 2026-09-10 after a byte-exact
reproduction — see *Correction* below. Audit-authored, not dev-reviewed, not deployed. **FR-201-5 (repair
the 164 known-broken files) is NOT run** — the repair route exists and is tested, but was deliberately
not invoked against production media this session. Backend/media-file only — **no Ravilo counterpart
and no client change**: the client is behaving correctly given a file whose track list it cannot reach.
See STATUS.md for the build summary.

### Correction (2026-09-10)
The first draft of this spec blamed `-cues_to_front 1` in `TrackCommandBuilder.ffmpegReorder:102`.
**That was wrong and `-cues_to_front` must stay.** Running the exact production remux under the
container's own ffmpeg 5.1 produces a *correct* file — `Tracks` at offset 293, `Cues` at 2832, first
`Cluster` at 5674, matching the broken file's layout in every respect except the one that matters.
The eviction happens in the `mkvpropedit` call that runs **after** it. Evidence below.

## The finding

`MkvpropeditRunner.setDefault` — via `TrackCommandBuilder.mkvDefault` — edits the MKV **in place**. It
is fast and lossless, which is exactly why it was chosen over an ffmpeg remux for MKV flag and
language edits. But setting `flag-default=0` on tracks that carry no `FlagDefault` element (the
element's default value *is* 1, so writing 0 must add it), or adding `language-ietf` alongside
`language`, makes the `Tracks` element **grow**. When the grown element no longer fits its original
slot and there is no adjacent `Void` to absorb it, mkvpropedit overwrites the old slot with a `Void`
of the identical size and **appends `Tracks` to the very end of the file**, reachable only through
`SeekHead`.

`mkvpropedit` is called from four places, all of which can do this:

| site | call |
|---|---|
| `TrackRoutes.kt:587` | bulk reorder → `setDefault` **after** `FfmpegRunner.reorderTracks` |
| `TrackRoutes.kt:626` | bulk reorder, `toFlagFix` loop → standalone `setDefault` |
| `TrackRoutes.kt:254` / `:364` | single-title set-language / set-default |

### Reproduced byte-for-byte, 2026-09-10

Starting from a clean remux of `Bugs.Bunny.Builders.S00E02`, run inside the production container:

| step | offset 52 | 121/124 | 213 | 293 | 937 | 2832 | 5674 | EOF |
|---|---|---|---|---|---|---|---|---|
| after `ffmpeg … -cues_to_front 1` | SeekHead 64 | Void 83 | Info 75 | **Tracks 638** | Tags 1889 | Cues 2836 | Cluster | — |
| after `mkvpropedit --set language…` | SeekHead 67 | Void 80 | Info 75 | **Void 635** | Tags 1889 | Cues 2836 | Cluster | **Tracks @92539415** |
| **the live broken file** | SeekHead 67 | Void 80 | Info 75 | **Void 635** | Tags 1889 | Cues 2836 | Cluster | **Tracks @92539415** |

The last two rows are identical. Its `SeekHead` says so outright:
`Info → 213 · Tags → 937 · Cues → 2832 · Tracks → 92539415`.

The `writing_application` on every broken file is `Lavf59.27.100` — ffmpeg 5.1, the version inside our
own container, not the host's 7.1. So our remux wrote the file and our mkvpropedit call then moved its
track list to the end.

### Why only Ravilo breaks

Everything that opens the file **as a local seekable file** follows `SeekHead` and is fine — `ffprobe`
reports all 7 streams, our scanner records them, Jellyfin's `MediaSources` are complete and correct,
and Jellyfin will happily direct-play it.

ExoPlayer's `MatroskaExtractor` reads the HTTP body **linearly**. It reaches the first Cluster having
never seen a `Tracks` element, so it never builds a single renderer format. It does not error — there
is nothing to error about — it simply never leaves the buffering state.

The telemetry matches that precisely:

| | working S01E01 | broken S00E02 |
|---|---|---|
| `POST /api/tv/playback/start` | 200, `direct_play=true` | 200, `direct_play=true` |
| Jellyfin `GET /Videos/…/stream` | 200, full body | 200, full body |
| `playback_qoe.video_decoder` | `c2.exynos.hevc.decoder` | **empty** |
| `playback_qoe.rebuffer_count` | 1 | **0** |
| `playback_qoe.dropped_frames` | 1 | **0** |

Zero rebuffers, zero dropped frames and no decoder name is not a slow start — it is a player that
never had a track to decode. The client closes the stream ~30 ms after the response headers (Caddy:
`aborting with incomplete response … broken pipe`, 0.02–0.03 s) and never reopens it, because there is
nothing left to ask for. `/api/tv/playback/start` returns in 0.18 s with a correct ticket throughout.

## Blast radius (measured 2026-09-08, full sweep of 7 885 `.mkv` files)

**164 files** have `Tracks` after the first `Cluster`. Every one is unplayable in Ravilo on every
platform, and plays normally in Jellyfin's own clients.

| title | files |
|---|---|
| Bugs Bunny Builders (Snurre Snups Byggevenner) | 85 of 93 — S01 35/39, S02 36/40, Specials **14/14** |
| Bob the Builder | 52 |
| Bridgerton | 8 of 8 |
| The Cleaner (2021) | 6 |
| Secrets We Keep | 6 |
| Taskmaster | 5 |
| movies — *Adventures of the Road Runner (1962)*, *The Wedding (2026)* | 2 |

`media_history` ties the two largest runs to this path: `bulk_reorder_tracks` on
`bugs-bunny-builders-2022` (93 episodes, 2026-09-07 14:34) and `bridgerton-2020` (8 episodes,
2026-09-07 10:34). Bridgerton is 8-for-8.

**Whether a file breaks is data-dependent** — it turns on whether the grown `Tracks` still fits. That
is why 8 Bugs Bunny files survived and 85 did not, and why this cannot be caught by testing one file.

## Requirements

**FR-201-1 — no write path may leave a file whose `Tracks` element follows the first `Cluster`.**
This is the invariant. Everything below is a means to it.

**FR-201-2 — check the layout after every mkvpropedit edit.** A top-level EBML walk of the edited
file, confirming `Tracks` precedes the first `Cluster`. It reads a few hundred bytes and needs no
subprocess. It belongs in `MkvpropeditRunner`, once — not at each of the four call sites. **It must
not use ffprobe**, which seeks and therefore always passes.

**FR-201-3 — a file that fails the check is repaired in place, immediately, before the operation
reports success.** `ffmpeg -i <file> -map 0 -c copy -cues_to_front 1 <tmp> && mv` restores `Tracks` to
the front *and* keeps Cues at the front. Measured: **0.59 s for a 92 MB file**, with every stream,
language tag and default/forced disposition byte-identical before and after. Same `tmpPath` +
ownership-preservation wrapper `FfmpegRunner.runRemux` already uses. The repair is not optional or
deferred — an unrepaired file is a title that silently cannot be played.

**FR-201-4 — keep `-cues_to_front 1`.** It is innocent here and it is load-bearing: without it ffmpeg
writes Cues at the end and ExoPlayer spends ~7.6 s reading most of the file before playback starts
(the 2026-08-16 report). Note that **`mkvmerge` is not a substitute** — it preserves `Tracks` at the
front but writes Cues at EOF (verified 2026-09-10), which trades this bug for that one.

**FR-201-5 — repair the 164 existing files** with the same one-line remux as FR-201-3. Lossless: no
re-encode, no metadata loss, no track-order change. ~0.6 s each plus I/O. This is an operator action
on the media library, not something a scan may do on its own initiative. The file list is reproducible
from a sweep (FR-201-6) rather than stored.

**FR-201-6 — a detector the operator can run and read.** The same EBML walk over a library,
classifying each `.mkv` as `ok` / `tracks-after-clusters`, surfaced on the Activity page's existing
health reporting. Cheap — the full 7 885-file sweep ran in well under a minute. It must not
auto-repair.

**FR-201-7 — Ravilo says nothing new.** A file this broken should be repaired, not explained. No new
copy, no new error state, no "this file can't be played" screen — R237's classification covers real
start failures and this is not one (the start *succeeds*). A client-side guard, if ever wanted,
belongs in its own phase with its own thinking about what the viewer is meant to do about it.

**FR-201-8 — in the bulk-reorder path, make the ffmpeg remux the last writer.** `TrackRoutes.kt:587`
runs `setDefault` *after* `FfmpegRunner.reorderTracks`; swapping them lets the remux lay `Tracks` out
correctly for free, so the common path never reaches FR-201-3's repair at all. This is a complement to
FR-201-2/3 and **never a replacement** — it does nothing for the `toFlagFix` loop at `:626` or the
single-title edits at `:254`/`:364`, which have no remux to be last.

## Out of scope

- Re-encoding anything.
- Replacing `mkvpropedit` with an ffmpeg remux for flag/language edits. In-place editing is the whole
  point of that path; a post-condition check plus a rare repair is far cheaper than remuxing every
  edit.
- Changing ExoPlayer/`MatroskaExtractor` behaviour or making a player seek to a trailing `Tracks`
  element. Reading the tail of an HTTP stream before the head is not something to build into a player.
- The Jellyfin side. Jellyfin is correct throughout.

## Open questions

1. **Bob the Builder (52), The Cleaner (6), Secrets We Keep (6), Taskmaster (5)** have no
   `reorder_tracks` / `bulk_reorder_tracks` row in `media_history` — but they may well have gone
   through the single-title set-language/set-default paths, which record different actions. Confirm
   before FR-201-2 is called complete; if some other writer can do this, the check is in the wrong
   place.
2. **Repair ordering.** Nothing stops the next flag edit from re-breaking a repaired file until
   FR-201-2/3 ship, so the repair should follow the code fix rather than precede it.
