# Phase R382 — An audio rendition plays the track the viewer picked

> Found by 314's dev review (2026-10-08). Owner, 2026-10-08: *"let's start implementing all our phases/specs"*.

## Status

`⚠ Partial` — written 2026-10-08 (dev-authored), confirmed and built the same day (see *Build notes*); deployed to the
dev stack; the device check of acceptance 1 is in the build notes. Backend (`tv/AudioRenditions.kt`,
`AudioRenditionJobs.kt`), touching R291.

## What happens today (suspected, to be confirmed first)

- R291's own audio renditions run ffmpeg with `-map 0:${src.streamIndex}` (`AudioRenditionJobs.kt:84`), where
  `streamIndex` is the **Jellyfin** `MediaStreams[].Index` of the audio track (`AudioRenditions.Rendition`).
- Jellyfin numbers **external** streams first: external subtitles, then external audio, then the file's own streams,
  renumbering all of them. ffmpeg's `0:<n>` is the stream's number **inside the file**. With one or more external
  subtitle files (common: Bazarr sidecars, R209/R239), Jellyfin's index of an embedded audio track is shifted by the
  number of external streams, so `-map 0:<jellyfin index>` maps a **different stream**: another audio track, a video
  stream (ffmpeg error) or a subtitle (ffmpeg error).
- A title with an external subtitle and two or more audio tracks would then play the wrong language when the viewer
  switches audio, or fail to switch.

## Requirements

### FR-R382-1 — Confirm on a real title

Pick a title with ≥ 1 external subtitle and ≥ 2 embedded audio tracks; compare Jellyfin's `MediaStreams` indexes with
`ffprobe` stream indexes and with what a rendition job actually encodes (language of its output). Record the finding in
the build notes.

### FR-R382-2 — Map by the file's own stream number

Each rendition carries the **file's own** stream index, taken from jellystructure's scan of the file (the `tracks` in
`MediaItem`, which come from ffprobe) matched to Jellyfin's stream by codec, language, channels and order among audio
streams — never by Jellyfin's index. `-map 0:a:<n>` (the n-th audio stream) is used when the match is by audio order.
One mapping function, shared with 314 (whose `.mka` sidecars shift Jellyfin's numbers the same way).

### FR-R382-3 — Refuse a mismatch rather than play the wrong track

If no confident match exists, the rendition is not offered (R291's restream path is used for that switch) and the
reason is logged.

### FR-R382-4 — Tests

1. A fixture with 2 external subtitles and 3 embedded audio tracks: each Jellyfin audio index maps to the right
   `0:a:<n>`.
2. A sidecar `.mka` (314) shifts Jellyfin's numbers; the mapping still picks the embedded track.
3. No confident match ⇒ no rendition, restream used.

## Acceptance

1. On the FR-R382-1 title, switching to each audio track on Android plays that language.

## Build notes (2026-10-08)

- **FR-R382-1, confirmed on a real film** (read-only, Jellyfin's `MediaStreamInfos` vs `ffprobe`): three external SRT
  subtitles are Jellyfin's streams 0–2, so the embedded video is 3 and the two audio tracks (TrueHD 7.1, AC3 5.1) are
  **4 and 5**; in the file they are **1 and 2** (0 is the video). `-map 0:4` / `-map 0:5` therefore picked two PGS
  subtitles: a rendition of either audio track could not be made on this title.
- **FR-R382-2/3:** `fileAudioOrder(jellyfin audio, our scan's tracks)` in `AudioRenditions.kt`: the n-th Jellyfin audio
  track is the file's n-th audio stream when both lists have the same length and agree track by track on codec
  (`dca`/`dts`, `ec-3`/`eac3` aliases) and language (ISO 639-2 via `LanguageResolver`); otherwise null and **no
  renditions are registered** (the switch restreams). `Rendition.audioOrder` carries the place; `RenditionSource.audioOrder`
  → `-map 0:a:<n>`. `PlaybackService.localFileOf` now returns our scan's tracks with the path.
- Tests (`AudioRenditionsTest`): the confirmed film's numbers map to `0:a:0`/`0:a:1`; a sidecar audio track (314), a
  language swap or a codec mismatch gives null; `register` offers nothing on a mismatch; the command uses `0:a:<n>`.

### Live (2026-10-08)

- On the FR-R382-1 film, the rendition command with the old mapping (`-map 0:5`, Jellyfin's number for the AC3 track)
  fails (*Error opening output files*: stream 5 is a PGS subtitle); with the new `-map 0:a:1` it encodes the file's
  stream 2, AC3 5.1 English: the track the viewer picked. Not yet switched on a device (acceptance 1).

### Still owed (2026-10-08 evening)

The live check planned after the v1.50-54 deploy was not made: Stue TV was in use by a viewer, and the owner then
paused all device use except the Pixel. Owed: a cast to Stue TV's built-in Chromecast confirming the receiver plays
with Shaka and reports its start time and sample count (309a0), and an audio switch on Android on R382's confirmed
film.

## Live check (2026-10-09, v1.50-76, Pixel debug app)

On the confirmed film, under our own encoder (313), the encoder maps the file's own streams (`-map 0:a:0`,
`-map 0:a:1`); switching in the picker from *English · 1/2* (TrueHD 7.1) to *English · 2/2* (AC-3 5.1) loaded the
`a1 AC-3 5.1-EX` rendition at once (its first segment in 18 ms) with no restart. **Verified on Android.** (Not
re-checked on Jellyfin's own transcode path, where R291's renditions use the same mapping.)

## Live results after the integration deploy (2026-10-09, v1.50-105 → v1.50-106)

**A second mapping bug, found live:** a track Jellyfin names `fra` (639-2/T) and the file names `fre` (639-2/B) did not
match, so the film got no mapping at all (our encoder fell back to Jellyfin with `audio tracks don't match the file
(R382)`). Fixed in `73d15191`: languages are compared with `sameLanguage`, not by code (`AudioRenditionsTest`). After
the fix the same film's seven audio tracks map and our encoder serves it.

## Amendment, 2026-10-12 — acceptance 1 fails on the phone: the row and the stream are off by one

> Reported by QA on the Pixel 9 Pro (`dev.jellystructure.ravilo.debug`, `1.51-18-g171a151b-debug`, against prod) while
> running acceptance 1. **The backend half of R382 is not implicated**: both switches took effect, decoded, and
> continued without a restream, and the old `-map 0:5` breakage is gone. The defect is on the **client**, at the point
> the viewer picks — so the Kotlin for this amendment is in `ravilo-ui`, not in `tv/AudioRenditions.kt`.

### What was seen

On a title with 6 embedded audio tracks and 7 external SRTs, the player's own list is
`English | Danish | Finnish | Norwegian | Swedish | English` and the picker shows five rows
(`English (2 versions) | Dansk | Suomi | Norsk | Svenska`). Tapping **Norsk** decoded `'Swedish'`; tapping **Dansk**
decoded `'Finnish'` — each one track later. The sheet showed Norsk ticked and the Norwegian flag on the Audio chip
while Swedish was decoding, so the tap landed on the intended row and **the UI and the player disagree**.

On a second title (the FR-R382-1 film, two `eng` tracks) the two rows are simply swapped: the row labelled
`Default · Surround 7.1` plays AC-3 5.1, the row labelled `Surround 5.1` plays TrueHD Atmos 7.1, and the
`Now showing` badge names the wrong one of the two in both directions.

### What the numbers say

Flattening the five groups in group order gives `[0, 5, 1, 2, 3, 4]`. The track each tap actually played is the one
at the tapped version's **position in that flattened order**, not the one at its `flatIndex`:
Dansk is flattened position 2 → track 2 (Finnish); Norsk is flattened position 4 → track 4 (Swedish). A language with
two versions therefore shifts every row below it. On the two-`eng` title, where one group holds both tracks, the same
confusion presents as a straight swap. **First place to look:** `PlayerScreen.kt` `foldedAudioGroups`
(`:4288`–`:4291`) and the contract of the `build` lambda it is given — whether a `PickerVersion.flatIndex` coming back
from R195's grouping is a position in the list that was passed in, a position in the flattened group order, or the
track's own `PlayerAudioTrack.index`. The remap `visible[v.flatIndex].index` is only correct for the first of those
three. `shownAudio` and `playableAudio` (314b) read the same index and would inherit the error.

### FR-R382-5 — A pick addresses the track, not a place in a list

Choosing a version in the audio picker plays **that version's own track** in the list the player reported, on every
shape: one language with several versions, several languages, a 314b copy folded onto its source's row, and a
single-audio session where the ticket's list stands in for the player's (`sessionAudioTracks`, R284). The index the
player is given is derived from the version's identity, not from its position among the rows or among the flattened
groups. A language group with two or more versions must not shift the rows below it.

### FR-R382-6 — `Now showing` names what is decoding

The picker's selected tick, the summary row's badges, the Audio chip's flag and the `Now showing` badge are all
derived from the **same** index as the stream the player is decoding, so no two of them can disagree. This is R238's
rule (`activeVersionOf`) applied to audio: with several versions in a group the badges belong to the one that is
playing.

### FR-R382-7 — Tests

1. The reported shape — 6 tracks `en, da, fi, no, sv, en` grouped into five rows: every version's chosen index is its
   own track, and in particular the `Norsk` row selects the Norwegian track, not the Swedish one.
2. Two same-language tracks in one group: each version selects its own track, and `Now showing` marks the one that is
   decoding, in both directions.
3. A 314b copy folded onto its source's row, in a list where the copy is **not** last: the fold does not shift any
   other row's chosen index.
4. A regression test over the whole list: for every version of every group, the index handed to the player round-trips
   back to that same version through the display derivation (`shownAudio` ∘ pick = identity on the row).

### Acceptance (amended)

1. On the reported 6-track title, on the phone: tapping each of the five rows (and both versions of the two-version
   row) decodes that row's language — `Norsk` plays Norwegian — and the sheet, the chip and `Now showing` agree with
   the stream in the log, each way and back.

### Out of scope, filed separately

The same QA session found a playback session left `state='playing'` with `ended_at = NULL` on prod, which makes the
app offer *"Already playing on Ravilo Web"* on every later play. That is the 310/312 writer's business, not R382's.
