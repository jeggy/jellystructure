# Phase R284 — Audio and subtitles work in every player (Android transcode, web)

> Client half of [[phase-253-every-track-choice-is-a-server-side-choice-on-a-single-audio-stream]] for
> the two `RaviloPlayer` actuals. Read 253's table first.

**Status:** ✓ Built 2026-09-20 (same day as written), not dev-reviewed, **not verified on a device or in a browser.** Android + wasmJs compile clean; `SingleAudioSessionTest` 6/6, `BurnedInSubtitleTest` 12/12, `PlayerScreenTrackResolutionTest` 20/20, whole `:ravilo-ui` suite 172/172. ⚠ **Release dex guard: 239 registers (limit 250, cliff 256)** — up from 227; the next change to `PlayerScreen` should move `choosePick`/`resolveTrackSelection` out of the composable first. Found and fixed on the way: the web player listed every PGS track **twice** (its own list kept URL-less entries that `PlayerScreen` also lists as burn-in candidates), and its `<track default>` attribute was a second, browser-run chooser — removed now that the resolver really selects. ⚠ FR-R284-6 (Android `hlsHevc = true`) rests on Media3's documented fMP4-HLS support, not on a test here.

## Requirements

### FR-R284-1 — On a single-audio session the picker lists the ticket's audio, not the player's
When the ticket is not direct play and carries audio metadata, the audio list is `ticket.audio`
(every real track, correctly labelled) on every platform, and the selected one is the track whose
index is `ticket.audioStreamIndex`. On direct play nothing changes (the player's own list).

### FR-R284-2 — Picking audio on a single-audio session restreams
…with that track's Jellyfin index, **keeping the current burn-in** (253 FR-253-1). Re-picking the
playing track is a no-op. A subtitle restream (R282) likewise keeps the current audio.

### FR-R284-3 — A remembered audio language is honoured on a transcode, once
After R181's resolver runs on a single-audio session, if the resolved track is not the one the
stream carries, restream to it — **at most one automatic attempt per item**, so a server that does
not honour the request can never loop the player.

### FR-R284-4 — The web player really switches subtitles
`RaviloPlayerWasm.selectSubtitleTrack(i)` sets `video.textTracks[k].mode` (`showing` for the chosen
`<track>`, `disabled` for the rest; `-1` disables all) and mounts/unmounts JASSUB for an ASS track.
Index = position in the ticket's subtitle list, which is already how `subtitleTracks` numbers them;
a list entry without a `<track>` (no URL) is skipped consistently in both.

### FR-R284-5 — `selectAudioTrack` on the web stays a no-op, deliberately
A browser HLS session has one audio track; FR-R284-2 is the mechanism. Documented, not stubbed.

### FR-R284-6 — Android opts into HEVC-over-HLS; the web does not
`hlsHevc = true` on Android (Media3 plays HEVC fMP4 HLS), so a transcode forced by something other
than the video (a burn-in aside, which always re-encodes) stops re-encoding HEVC. Web: false.

### FR-R284-7 — Live TV uses `supportedAudioCodecs()`
Replaces `LiveTvPlayerStore`'s literal. Android gains TrueHD/DTS there too (harmless for broadcast,
and one list is the point); web unchanged in effect.

## Invariants
- R282's: one subtitle on screen; the picker names what is on screen — now for audio as well.
- No new `remember` locals in `PlayerScreen` (`check-player-dex.sh`).
- No new strings.

## Verification
Unit tests for the pure helpers; Android + wasmJs compile; release dex guard. ⚠ Device/browser
verification is owed and listed in the build note.
