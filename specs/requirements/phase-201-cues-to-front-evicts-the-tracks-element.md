# Phase 201 — our own remux moves the track list to the end of the file, and Ravilo can never start it

> Live report, 2026-09-08: *"it seems like special seasons dont work in ravilo? when playing snurre
> snup byggevenner season 0 episodes it just loads for ever"*
>
> It is not season 0. It is **164 files** across 7 titles, including 35 of the 39 Season 1 episodes of
> that same series. The viewer landed on a working Season 1 episode first and a broken Specials one
> second, and the coincidence named the wrong axis.

## Status
`Planned`, 2026-09-08. Audit-authored, not dev-reviewed. Nothing built. Backend/media-file only —
**no Ravilo counterpart and no client change**: the client is behaving correctly given a file whose
track list it cannot reach.

## The finding

`TrackCommandBuilder.ffmpegReorder` (`src/commonMain/kotlin/dev/jellystructure/media/TrackCommandBuilder.kt:102`)
appends `-cues_to_front 1` to every `.mkv` remux. It was added by a 2026-08-16 live fix for a real
problem — ffmpeg's Matroska muxer writes Cues at the *end*, and ExoPlayer spent ~7.6 s reading almost
the whole file before it would start. Cues at the front fixed that.

ffmpeg's own help text for the flag says what it costs:

```
-cues_to_front  <boolean>  move Cues (the index) to the front by shifting data if necessary
```

The data it shifts is the **`Tracks` element** — the definition of every audio, video and subtitle
stream in the file. When the finished Cues do not fit the space the muxer reserved near the front,
ffmpeg overwrites the original `Tracks` slot with a `Void` of the identical size, writes the Cues
there instead, and appends `Tracks` to the very end of the file, reachable only through `SeekHead`.

Measured on `Hoppy.Hare.Builders.S00E02` (broken) against `…S01E01` (survived the same run):

| offset | broken file | working file |
|---|---|---|
| 52 | SeekHead (67) | SeekHead (64) |
| 213 | Info (75) | Info (75) |
| 293 | **Void (635)** | **Tracks (638)** |
| 937 | Tags | Tags |
| 2832 / 2845 | Cues | Cues |
| 5674 / 13596 | first Cluster | first Cluster |
| **92 539 415** | **Tracks (647 B, last bytes of a 92 MB file)** | — |

Its `SeekHead` says so outright: `Info → 213 · Tags → 937 · Cues → 2832 · Tracks → 92539415`.

### Why only Ravilo breaks

Everything that opens the file **as a local seekable file** follows `SeekHead` and is fine — `ffprobe`
reports all 7 streams, our scanner records them, Jellyfin's `MediaSources` are complete and correct,
and Jellyfin will happily direct-play or transcode it.

ExoPlayer's `MatroskaExtractor` reads the HTTP body **linearly**. It reaches the first Cluster having
never seen a `Tracks` element, so it never builds a single renderer format. It does not error — there
is nothing to error about — it simply never leaves the buffering state.

That is the exact shape of the report, and the telemetry matches it precisely:

| | working S01E01 | broken S00E02 |
|---|---|---|
| `POST /api/tv/playback/start` | 200, `direct_play=true` | 200, `direct_play=true` |
| Jellyfin `GET /Videos/…/stream` | 200, full body | 200, full body |
| `playback_qoe.video_decoder` | `c2.exynos.hevc.decoder` | **empty** |
| `playback_qoe.rebuffer_count` | 1 | **0** |
| `playback_qoe.dropped_frames` | 1 | **0** |

Zero rebuffers, zero dropped frames and no decoder name is not a slow start — it is a player that
never had a track to decode. The stream connection is closed by the client ~30 ms after the response
headers (Caddy: `aborting with incomplete response … broken pipe`, duration 0.02–0.03 s) and never
reopened, because there is nothing left to ask for.

The backend is entirely innocent at request time. `/api/tv/playback/start` for the broken item returns
in 0.18 s with a correct ticket, correct audio list and correct subtitle list.

## Blast radius (measured 2026-09-08, full library sweep of 7 885 `.mkv` files)

**164 files** have `Tracks` after the first `Cluster`. Every one of them is unplayable in Ravilo on
every platform, and plays normally in Jellyfin's own clients.

| title | files |
|---|---|
| Hoppy Hare Builders (Hoppe Hares Byggebande) | 85 of 93 — S01 35/39, S02 36/40, Specials **14/14** |
| Ben the Bricklayer | 52 |
| Ashworth | 8 of 8 |
| The Tidier (2021) | 6 |
| Things We Hide | 6 |
| Chore Captain | 5 |
| movies — *Adventures of the Road Runner (1962)*, *The Engagement (2026)* | 2 |

`media_history` ties the two largest same-day runs directly to this path:
`bulk_reorder_tracks` on `bugs-bunny-builders-2022` (93 episodes, 2026-09-07 14:34) and on
`ashworth-2020` (8 episodes, 2026-09-07 10:34). Ashworth is 8-for-8.

**Whether a given file breaks is data-dependent, not deterministic** — it turns on whether the finished
Cues fit the reserved space. A control remux of a Go Buster episode through the exact production
command kept `Tracks` at offset 291. That is why 8 Bugs Bunny files survived and 85 did not, and it is
why this cannot be caught by testing one file.

## Requirements

**FR-201-1 — a remux may never emit a file whose `Tracks` element follows the first `Cluster`.**
This is the invariant. How it is met is an implementation choice; what may not happen is shipping the
current unconditional `-cues_to_front 1` and hoping.

**FR-201-2 — verify the layout after the remux, before `mv`.** `ffmpegReorder`'s output goes to
`.jstmp_<name>` and is moved over the original on success. "Success" must include a top-level EBML
walk of the temp file confirming `Tracks` appears before the first `Cluster`. A temp file that fails
the check is not moved; the original is left untouched and the operation reports failure. Reading the
first few KB of the temp file is enough — no full parse, no ffprobe (which would pass, since ffprobe
seeks).

**FR-201-3 — keep the 2026-08-16 fix that `-cues_to_front` was buying.** Cues at the end cost ~7.6 s of
silent buffering before playback. Dropping the flag outright trades this bug for that one. The
recommended path is `mkvmerge` for `.mkv` remuxes — it is already installed in the runtime image
(`/usr/bin/mkvmerge`, confirmed in the running container), it is what most original releases are muxed
with, and it writes Cues near the front *without* relocating `Tracks`. Falling back to an
ffmpeg remux without `-cues_to_front` when the checked output fails FR-201-2 is the acceptable
minimum.

**FR-201-4 — the same guarantee applies to every writer, not just reorder.** `ffmpegLanguage` and
`ffmpegDefault` remux through the same builder and the same `runRemux`. The check belongs in
`FfmpegRunner.runRemux`, once, not at each call site. `mkvpropedit` edits (the MKV language/default/
forced paths) are in-place property edits and do not relocate `Tracks` — out of scope, but they must
not be assumed safe without the same check if they ever gain a `--add-track-statistics-tags`-style
rewrite.

**FR-201-5 — repair the 164 existing files.** Lossless: remux `-c copy` with a muxer that satisfies
FR-201-1, verify, replace. No re-encode, no metadata loss, no track-order change. This is an operator
action on the media library, not something a scan may do on its own initiative. The file list is
reproducible from a library sweep (below) rather than stored.

**FR-201-6 — a detector the operator can run and read.** A top-level EBML walk classifying each
`.mkv` as `ok` / `tracks-after-clusters`, surfaced somewhere an operator will see it — the Activity
page's existing health reporting is the natural home. Cheap: it reads a few hundred bytes per file
(the full 7 885-file sweep ran in well under a minute). It must not auto-repair.

**FR-201-7 — Ravilo says nothing new.** A file this broken should be repaired, not explained. No new
copy, no new error state, no "this file can't be played" screen — R237's existing classification
already covers real start failures, and this is not one (the start *succeeds*). If a client-side
guard is ever wanted, it belongs in a later phase with its own thinking about what the viewer is
supposed to do with the information.

## Out of scope

- Re-encoding anything.
- Changing `ExoPlayer`/`MatroskaExtractor` behaviour or forcing a seek to a trailing `Tracks`
  element. Reading the tail of an HTTP stream before the head is not something to build into a player.
- The Jellyfin side. Jellyfin is correct throughout; nothing there needs to change.
- Auditing whether `-cues_to_front` was the right answer to the 2026-08-16 buffering report. It was —
  it just cannot be applied blind.

## Open questions

1. **`mkvmerge` vs verify-and-retry.** mkvmerge is the better output but a second remux tool on the
   write path, with its own argument surface for track selection/order and its own failure modes.
   Verify-and-retry keeps one tool and pays a second full remux pass on the (data-dependent, possibly
   uncommon) failures. Decide before building — FR-201-3 states a recommendation, not a decision.
2. **Ben the Bricklayer (52), The Tidier (6), Things We Hide (6), Chore Captain (5)** have no
   `reorder_tracks` / `bulk_reorder_tracks` row in `media_history`. Either the history predates
   retention, or a second write path produces the same layout. Worth resolving before FR-201-4 is
   called complete — if a path other than `runRemux` can do this, the check is in the wrong place.
3. **Retention of the repair.** Nothing stops the next bulk reorder from re-breaking a repaired file
   until FR-201-1 ships. Repair should follow the code fix, not precede it.
