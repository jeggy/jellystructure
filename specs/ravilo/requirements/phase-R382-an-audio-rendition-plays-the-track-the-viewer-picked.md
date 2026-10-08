# Phase R382 — An audio rendition plays the track the viewer picked

> Found by 314's dev review (2026-10-08). Owner, 2026-10-08: *"let's start implementing all our phases/specs"*.

## Status

`Planned` — written 2026-10-08 (dev-authored). Not dev-reviewed, not built. Backend (`tv/AudioRenditions.kt`,
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
