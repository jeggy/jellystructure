# Phase 253 — On a single-audio stream, every track choice is a server-side choice

> Follows [[phase-252-a-burn-in-ticket-says-what-it-burned]]. Backend half of
> [[phase-R284-audio-and-subtitles-work-in-every-player]] and
> [[phase-R285-audio-and-subtitles-work-on-every-receiver]]. Owner, 2026-09-20: *"Do not leave
> anything alone. We want this to be fully supported across all platforms."*

**Status:** Planned (written 2026-09-20, not dev-reviewed).

## Investigation — what each platform can do today (read from the code, 2026-09-20)
| | text subtitles | PGS subtitles | audio switch |
|---|---|---|---|
| Android, direct play | ✓ | ✓ in-container | ✓ in-container |
| Android, transcode | ✓ | burn-in (252/R282) | ✗ picker shows the one HLS track, mislabelled with the first ticket track's name |
| Web (`RaviloPlayerWasm`) | ✗ `selectSubtitleTrack()` is an empty stub — only `<track default>` ever shows | ✗ | ✗ `selectAudioTrack()` is an empty stub |
| Samsung / webOS receiver (`ravilo-screen`) | ✗ never rendered: `setSelectTrack("TEXT", i)` addresses in-stream tracks and an HLS stream has none; the ticket's VTT URLs are never fetched | ✗ filtered out of the list (`subtitleTracksOf`) | ✗ `setSelectTrack("AUDIO", i)` on a one-track stream |
| Chromecast (`ravilo-cast`) | ✓ CAF sideload | ✗ filtered out | ✗ no command exists |

Every ✗ in the two right-hand columns has one cause: **an HLS session carries one audio track and no
picture subtitles, and only `restream()` can change either — but it can only change the subtitle, it
silently resets the audio to Jellyfin's default (252's own out-of-scope note), and nothing tells a
client which audio it is hearing.**

## Requirements

### FR-253-1 — `restream` takes the audio track too
`PlaybackRestreamRequest` gains `audio_stream_index: Int? = null` (Jellyfin's stream index). Both
branches of `restream()` (burn-in and 252's un-burn) pass it to `PlaybackInfo` as `AudioStreamIndex`.
Null ⇒ Jellyfin's default, exactly as today. The two indices compose: a subtitle pick keeps the
audio, an audio pick keeps the burn-in.

### FR-253-2 — The ticket names the audio it carries
`StreamTicket` gains `audio_stream_index: Int? = null`: on a **transcode**, the Jellyfin index of the
one audio track in the stream — read from the `AudioStreamIndex` parameter of Jellyfin's own
`TranscodingUrl` (what it *did*, not what was asked), else the requested index, else null. Always
null on direct play, where the container carries every track and the player selects. Set by
`startPlayback()` and both `restream()` branches. Additive with a default (252's wire rules).

### FR-253-3 — A client that can take HEVC over HLS says so, and is not re-encoded to h264
`ClientCapabilities` gains `hls_hevc: Boolean = false`. The single TranscodingProfile is
`h264`/`ts` today, so an `hls_only` client that declares `hevc` (both receivers do) still has **every
HEVC title re-encoded to h264**. When `hls_hevc` is true the profile becomes
`Container: mp4` (fMP4 segments — Jellyfin only emits HEVC in fMP4) with `VideoCodec: hevc,h264`, so
Jellyfin may copy the video and transcode only what does not fit. Default false: **no client's
negotiation changes until that client opts in**, because a wrong guess is a black screen on a title
that plays today. Who opts in is R284/R285's call.

### FR-253-4 — Live TV reports the same audio codecs as VOD
Not a backend change; recorded here because it is the last hard-coded list on the Android path. R284.

## Invariants
- A pre-253 client or server sees no difference (additive fields, defaults, `encodeDefaults=false`).
- `audio_stream_index` on a ticket is a fact about the stream, never an echo of the request.
- 252's invariant stands: a ticket with `burned_subtitle_index` is never `direct_play`.

## Considered and not done
- **Audio-aware first negotiation** (retry `PlaybackInfo` with a playable `AudioStreamIndex` before
  accepting a transcode). After R283 no shipping client benefits: Android decodes everything the
  library holds, and every other client is `hls_only`, where Jellyfin already copies a fitting audio
  track. FR-253-1 gives the viewer the same outcome by choice. Revisit with a new direct-play client.

## Verification
- Unit: wire contract of the three new fields, both directions; `AudioStreamIndex` extraction from a
  real `TranscodingUrl`; the profile string with and without `hls_hevc`.
- Live (read-only, done while writing): `PlaybackInfo` with `AudioStreamIndex=5` on Honeyman returns
  `DefaultAudioStreamIndex=5` and a URL carrying `AudioStreamIndex=5`.
