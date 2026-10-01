# Phase R351 — A film cast to a small display plays

> Found 2026-10-02 testing the Mac app against backend `v1.48-34-g0a5a7e59`: every film or episode cast to the
> kitchen's Google Nest Hub (a 1024 × 600 display) fails about two seconds in. Music cast to the same hub plays.

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed. **Amended 2026-10-02** (FR-R351-10–12, the channel limit). Written 2026-10-02 (dev-authored) from the Mac test and the code. Number given by the coordinator.
**Amends** R245 (FR-R245-13, the receiver's capabilities; FR-R245-4, the page's button while connected; FR-R245-8,
the remote). Reuses R343 (FR-R343-8, a shuffle carries over). No wire change: every field it sets already exists on
`ClientCapabilities`. No new string.

## What happens

1. Series page → the cast button → *Køkken hub* → *Play on Køkken hub* (or *Shuffle*).
2. The hub shows its loading screen, then idle. The Mac's remote says *Køkken hub couldn't play this*.
3. The receiver's log channel (`urn:x-cast:dev.jellystructure.ravilo.log`, in the Mac's `ravilo.log` as
   `cast: Køkken hub · …`) shows Shaka error **4032** (`CONTENT_UNSUPPORTED_BY_BROWSER`: none of the stream's
   variants is playable on this device). The server answered with a transcoding URL.

The same test found three smaller things on the Mac:

- **(a)** The remote has no volume control for a film. Music's player bar sets the device's volume (R337); the film
  remote on a computer has nothing, and a computer has no hardware volume keys that reach a Cast device.
- **(b)** After the failed cast, Back showed the series page **without its progress lines** (*0 of 20 episodes
  watched*, the *S01E01 · …* kicker) until *Stop casting*.
- **(c)** While connected, the page's primary button reads *Play on Køkken hub* — it no longer names the episode it
  will play (R346 gave every Play label its code).

## Why it happens

**The receiver tells the server it can take more than a Nest Hub can play.** `Receiver.capabilities()`
(`ravilo-cast/.../Receiver.kt:325`) asks the device about HEVC, VP9, AC-3, E-AC-3, Opus and 4K, but never about
H.264 below 4K: it always declares H.264 at **1920 × 1080**, sends no level (so the server declares **High 5.1**,
`h264TargetConditions`, `JellyfinClient.kt:224`), no bitrate ceiling and **six** audio channels. The server builds the
Jellyfin profile from exactly that (`deviceProfile`, `JellyfinClient.kt:69`):

- A 1080p H.264 file fits the declared 1920 × 1080, so Jellyfin **copies** the video into the HLS stream and only
  converts the audio. The episode in the test is 1920 × 872 H.264 at 10 Mbps with E-AC-3 5.1: the stream offered is
  1920 × 872 H.264 + AAC.
- A file that needs re-encoding is encoded at level 5.1 and 1080p.

On a Cast device, Shaka checks every HLS variant with the platform's own `canDisplayType`, including the variant's
resolution, frame rate and bitrate (its *extended MIME type*). Google lists the Nest Hub's H.264 as a 720p-class
decoder, so the only variant fails the check and Shaka gives up with 4032. A Chromecast built into a TV (the stue TV)
and a Chromecast dongle pass, which is why this never showed before.

Music works because a speaker's songs use a fixed audio-only profile (`audioCapabilities()`, 286), never these
numbers.

(b): leaving the remote re-enters the series page, which re-reads its playstate (`SeriesDetailStore.refreshSilent`).
That function **empties the overlay first** and fills it again only if both reads succeed; if either fails (it did
while the hub's failed session was being torn down), the lines stay hidden until the next re-entry. The same
function in `MovieDetailStore` has the same shape.

(c): `SeriesDetailScreen`'s label puts `cast.play_on` in place of the whole label.

## Requirements

**FR-R351-1 — The receiver asks the device what H.264 it plays.** The receiver probes H.264 High on a ladder, largest
first, and declares the first rung the device answers yes to:

| Rung | Size | Level | Codec string |
|---|---|---|---|
| 4K | 3840 × 2160 | 5.1 | `avc1.640033` |
| 1080p, 60 fps class | 1920 × 1080 | 4.2 | `avc1.64002A` |
| 1080p | 1920 × 1080 | 4.1 | `avc1.640029` |
| 720p, 60 fps class | 1280 × 720 | 4.1 | `avc1.640029` |
| 720p | 1280 × 720 | 3.1 | `avc1.64001F` |
| 480p | 854 × 480 | 3.0 | `avc1.64001E` |

Each size is asked at its higher level first, so a device that takes a 60 fps source at that size keeps it as a copy.

It sends that rung's `max_h264_width`, `max_h264_height` and `max_h264_level`. A device that answers no to every rung
keeps today's declaration (1920 × 1080, no level), so a receiver in an environment that cannot answer behaves as
before. The probe asks at 30 frames per second.

**FR-R351-2 — It asks the same way Shaka will.** Where the platform exposes `cast.__platform__.canDisplayType` (every
Cast device does; Shaka uses it), the receiver asks with the extended MIME type Shaka builds from a variant
(`video/mp4; codecs="…"; width=…; height=…; framerate=…`), so the answer is the one Shaka will get. Elsewhere it asks
`CastReceiverContext.canDisplayType(mime, codecs, width, height, framerate)`.

**FR-R351-3 — A bitrate ceiling where the device has one.** With `cast.__platform__`, the receiver also asks for the
chosen rung at 120, 60, 40, 25, 20, 15, 10 and 8 Mbps (`bitrate=`). If 120 Mbps passes it sends no ceiling (today's
behaviour). Otherwise it sends the highest bitrate that passes as `max_video_bitrate` and `max_h264_bitrate`, so the
server's `MaxStreamingBitrate` and phase 177's per-codec condition keep a stream under it. No answer at all ⇒ no
ceiling.

**FR-R351-4 — Audio channels the device plays.** With `cast.__platform__`, the receiver asks for AAC with
`channels=6`; a no sends `max_audio_channels = 2` (the server then down-mixes). AC-3 and E-AC-3 are asked with
`channels=6` too, as Shaka will. No answer ⇒ today's six.

**FR-R351-5 — The receiver says what it declared and what it got.** Once per receiver start the log channel carries
the declared capabilities (sizes, level, ceiling, channels, codecs). After each film negotiation it carries the
ticket's shape: direct or converted, and the stream URL's `VideoCodec`, `AudioCodec`, `MaxWidth`, `MaxHeight`,
`VideoBitrate`, `AudioChannels`, `SegmentContainer` and `TranscodeReasons` parameters — never a token or an
`ApiKey`. A failed cast can then be read from the Mac's `ravilo.log` without a second test.

**FR-R351-6 — The desktop's remote has the device's volume.** On a computer (macOS, Linux) the film remote shows a
volume line under the transport: the device's own level as it reports it (`CastSender.volume`), dragged or clicked
to set it (`CastSender.setVolume`). It is the same control as the music bar's volume while casting (R337). A device
that reports no volume shows the line at its last set value. The phone keeps its hardware keys and draws nothing.

**FR-R351-7 — A detail page keeps what it knows while it reads again.** `SeriesDetailStore` and `MovieDetailStore`
re-read on re-entry without emptying the playstate overlay: the old values stay until the new ones arrive, and a
failed read keeps them (and the old detail). The same stores read their playstate again when the server pushes
`home_changed` while their page is in the navigation stack, so a page under the remote follows what the TV did.

**FR-R351-8 — The button names the episode while connected.** *Play on {device} · S01E01*: `cast.play_on` followed by
the same code the label carries when not connected (R346). A film keeps *Play on {device}*.

**FR-R351-9 — A shuffle carries over (R343, unchanged).** Checked, not changed: *Shuffle* while connected sends the
shuffled order with `episodes_shuffled`, the receiver asks every start with `shuffle`, and its next-up card says *UP
NEXT · SHUFFLED*. The capabilities of FR-R351-1–4 apply to every start of the order and to a restream.

## Non-goals

- HEVC is still probed at 1080p only, and the server has no size condition for HEVC; a device that decodes HEVC 1080p
  but not 4K could be sent 4K HEVC. Not seen on any household device; named here, not changed.
- No retry with a smaller declaration after a 4032. FR-R351-5 makes the next failure readable instead.
- The phone's remote is unchanged (hardware keys set the Cast volume through the SDK).

## Acceptance

1. Mac → a series → cast to the Nest Hub → *Play on Køkken hub · S01E01*: the episode plays on the hub. The Mac's
   `ravilo.log` shows `cast: Køkken hub · caps h264≤…` with a size of 720p or 1080p and the ticket line with
   `MaxWidth`/`MaxHeight` at or under it.
2. The same with *Shuffle*: the hub plays a shuffled episode and its card says *UP NEXT · SHUFFLED* near the end.
3. The stue TV (Chromecast built in) and the bedroom TV still play the same episode, and their `caps` line shows 4K
   or 1080p as before.
4. On the Mac remote the volume line moves the hub's volume, and the hub's own volume change moves the line.
5. Cast fails (any reason) → Back: the series page keeps *N of 20 episodes watched* and the up-next kicker.
6. While connected the button reads *Play on {device} · S01E01*.

## Build notes (2026-10-02)

Built 2026-10-02 on `main` `145fc0f9`. Not deployed, not device-tested (no cast was made from this build).

- **Receiver (FR-R351-1–5):** `CastDecodeProbe` in `:shared` (pure, unit-tested) holds the ladder, the bitrate steps,
  the extended-MIME builder and the stream-URL summary. `Receiver.capabilities()` declares the chosen rung's
  `max_h264_width/height/level`, `max_video_bitrate` + `max_h264_bitrate` (only below 120 Mbps), and
  `max_audio_channels`; AC-3 / E-AC-3 also need a yes with `channels=6`. Asked once per receiver start (`lazy`), through
  `cast.__platform__.canDisplayType` when present, else `CastReceiverContext.canDisplayType(…, 30)`. The log channel
  carries `caps h264≤WxH L… ceiling=… ch=… platform=…` once and `ticket <id> direct=… caps=… master.m3u8 VideoCodec=…
  MaxWidth=… …` on every film negotiation (named parameters only).
- **Deviation:** the ladder asks each size at its higher level first (1080p at 4.2 then 4.1, 720p at 4.1 then 3.1), six
  rungs instead of four, so a 60 fps source at a size the device takes is not re-encoded for its level alone.
- **Desktop remote volume (FR-R351-6):** `RemoteVolume` under the transport on macOS/Linux — the sender's reported
  volume, else the last set level; click or drag sets it.
- **Detail stores (FR-R351-7):** `refreshSilent` no longer empties the overlay; an empty (failed) playstate answer never
  replaces the shown one; both stores read their playstate again on `home_changed` while their page is in the stack.
- **Button (FR-R351-8):** *Play on {device} · S01E01* on the series page.
- **Shuffle (FR-R351-9):** checked in code, unchanged — the sender sends `episodes_shuffled`, the receiver keeps it on
  every own load and asks every start with `shuffle`.

**The cause is inferred from the code and Google's published decoder limits, not from a capture of the hub's
answers.** The first cast with this receiver will say what the hub declares (`caps …`) and what it was sent
(`ticket …`).

**Verified:** `CastDecodeProbeTest` (8), `:ravilo-cast` production bundle, `:ravilo-ui` unit tests, backend
`linuxX64Test`, desktop compile, Android release build. **Needs:** a deploy of the receiver (it is served by the
backend) and a cast to the Nest Hub, the stue TV and the bedroom TV.

## Amendment (2026-10-02) — the hub now fails on audio

**Seen in the Mac + Nest Hub re-test (backend `v1.48-54-gfefa9049`, this phase deployed).** The receiver now reports
what the hub takes (`cast: Køkken hub · caps h264≤1280x720 L41 ceiling=none ch=2 platform=true`) and the ticket limits
the picture (`VideoCodec=h264 AudioCodec=aac,mp3 MaxWidth=1280 MaxHeight=720 SegmentContainer=ts h264-level=40`), but
the stream URL carries **no channel limit**, and Jellyfin's session shows aac/h264/ts 1280 × 581 with **six** audio
channels (source E-AC-3 5.1, `AudioCodecNotSupported`). The hub's renderer refuses it: Shaka **3016**
(`PipelineStatus::AUDIO_RENDERER_ERROR`). Music to the same hub plays (its songs use the speaker profile, stereo).

**Why.** Phase 177 (FR-177-3) put the client's channel limit into the device profile as a codec profile of
`"Type":"Audio"`. Jellyfin reads an `Audio` codec profile only for an audio item (a song); a film's audio stream is
checked against `VideoAudio` profiles. So no film negotiation has ever seen `max_audio_channels`, for any client. The
video transcoding profile also had no `MaxAudioChannels`, and the PlaybackInfo request did not send one, so when
Jellyfin converted E-AC-3 to AAC it kept the source's six channels (its AAC encoder allows six). The burn-in restream
(a picture subtitle) negotiated with no capabilities at all, so it would have sent the hub 1080p and six channels too.

**FR-R351-10 — The device's channel count reaches every place a film's stream is made.** When a client reports fewer
than 8 channels (a receiver: 2 or 6; the Samsung screen app: 6):

- the device profile carries a `VideoAudio` codec profile `AudioChannels ≤ N` (required), and the song profile an
  `Audio` one;
- the video transcoding profile (TS and fMP4 alike) carries `"MaxAudioChannels":"N"`;
- the PlaybackInfo request carries `"MaxAudioChannels":N`;
- a conversion URL that still has no `MaxAudioChannels` / `TranscodingMaxAudioChannels` gets
  `TranscodingMaxAudioChannels=N` appended (a direct-play `Static=true` URL is never touched; nothing else in
  Jellyfin's URL changes — FR-239-5).

A stereo device gets stereo AAC; a device that reports 6 keeps 6. A client that reports 8 (the default: the phone, the
TV app, the desktop, the web) negotiates exactly as before. Every start, the next episode, a shuffled entry and a
restream go through these, because the receiver sends its capabilities on each of them; a seek on the receiver stays
inside the HLS stream it has.

**FR-R351-11 — A burn-in restream keeps the device's limits.** The picture-subtitle restream still negotiates as a
plain SDR h264/TS conversion, but with the client's channel count, H.264 size and level, bitrate ceilings and audio
codecs; its hand-built fallback URL uses the same size and channel limit.

**FR-R351-12 — The log says it.** The receiver's `ticket` line adds the channel count it declared (`caps …/ch2`) and
keeps the URL's `MaxAudioChannels`, `TranscodingMaxAudioChannels` and `aac-audiochannels`; the server's
`PlaybackInfo:` line adds `maxAudioChannels=N` when a limit applies.

**Checked, not changed.** The R216 / 177 bitrate ceilings already reach Jellyfin as required `VideoBitrate`
conditions and `MaxStreamingBitrate`, so they were not affected. Phase 289 / 286's speaker path uses the song profile,
whose transcoding profile was already stereo; it now also has the `Audio` channel profile, so a multichannel file to a
stereo speaker converts rather than direct-plays.

### Build notes (2026-10-02, amendment)

Built 2026-10-02, not deployed, not device-tested. `deviceProfile` / `audioDeviceProfile` / `getPlaybackInfo` in
`JellyfinClient.kt` (`channelLimit`, `audioChannelCondition(type)`, `withChannelLimit`); `PlaybackService` applies
`withChannelLimit` in `streamUrlFor` (start and un-burn restream), the song conversion URL and the burn-in path, which
now negotiates with `burnInLimits(capabilities)`; the receiver's `ticket` line and `CastDecodeProbe.LOGGED_PARAMS`.

**Deviation:** none. One wider effect, deliberate: the Samsung screen app reports 6 channels, so a 7.1 file to it now
converts its audio to 6 channels instead of direct-playing (Tizen work is paused; nothing else reports fewer than 8).

**Verified:** `AudioChannelLimitTest` (8: ch=2 and ch=6 profiles, the fMP4 profile, the unchanged 8-channel
profile, the song profile, the URL guard both ways, the burn-in limits), `CastDecodeProbeTest` (the summary keeps the
channel parameters, never a token), backend `linuxX64Test`, `:shared:desktopTest`, the receiver bundle. **Not
checked against a live Jellyfin** (no prod access in this build): the `VideoAudio` reading is from Jellyfin's
`StreamBuilder` (audio conditions for a video item come from `CodecType.VideoAudio`) and is the reason the appended
`TranscodingMaxAudioChannels` exists as a second guard. **Needs:** a deploy, then a film cast to the Nest Hub — the
Mac's `ravilo.log` `ticket` line should carry `MaxAudioChannels=2` (or `TranscodingMaxAudioChannels=2`) and Jellyfin's
session should show 2 audio channels; the stue TV (ch=6) should show 6 for the same file.

## Amendment (2026-10-02, final re-test) — the next-up card is up for two seconds

**Seen in the final Mac + Nest Hub re-test (production `v1.48-62-g23c34ae4`).** A *Shuffle* cast of a series to the
hub (the episode's E-AC-3 5.1 converted to 720p stereo HLS), seeked to about 50 s before the end. The receiver's first
`nextup` message (`nextup_secs` 6) arrived about two seconds before `finished END_OF_STREAM`; the end of the stream
then loaded the next entry after two of the six ticks. The Mac's remote never showed *Play in N*.

**Why.** Not the converted stream. The episode's credits marker sits **1.8 s before the end of the file** (a
`heuristic` marker: the ffmpeg pass looks for black + silence in the last 180 s and found the final fade to black; the
season's other episodes have the same shape, 1.3–1.8 s before the end). The receiver puts its card up at the credits
marker whenever the marker is in the back half of the title, else 20 s before the end — so a marker in the last
seconds put the card up later than the 20 s fallback would have, with no room for its countdown. A direct-play cast of
the same episode (the stue TV, the bedroom TV) does exactly the same; the file's own length matched the stream's
(probed: format, video and audio all 35:28, so the converted stream's duration was not the cause). The sender's pills
mirror the receiver's `nextup` messages; there were two, so the pills were up for two seconds.

**FR-R351-13 — The receiver's card always has its whole countdown before the end.** The card goes up at the credits
marker when it is a trusted one (the shared `trustedCreditsStartMs`: positive, inside the duration, in the back half),
else 20 s before the end — and **never later than the countdown (`skip_secs`) plus 2 s before the end**. A marker with
room for the countdown is unchanged. The rule is one pure function in `:shared` (`nextUpStartMs`), unit-tested. The
receiver's log channel says when the card went up (`nextup at <pos>ms of <dur>ms (credits <ms>)`).

**Not changed.** The TV app's own player: its card waits on the last frame until the countdown ends (it does not
advance on the end of the file while the card is up), so a late marker costs it nothing. The credits heuristic itself
(a marker on the final fade is the detection's own problem; a later phase can drop a heuristic marker in the last few
seconds at scan time).

### Build notes (2026-10-02, final re-test amendment)

Built 2026-10-02, not deployed, not device-tested. `shared/.../tv/NextUpTrigger.kt` (`nextUpStartMs`,
`NEXT_UP_FALLBACK_MS`, `NEXT_UP_END_MARGIN_MS`); `Receiver.onTime` uses it and notes the moment on the log channel.

**Verified:** `NextUpTriggerTest` (6: a marker in the last seconds leaves room for 6 and 8 s countdowns, a marker with
room is kept, exactly at the cap, no or untrusted marker → 20 s, a countdown longer than the fallback, unknown
duration, a clip shorter than the countdown); `:shared:desktopTest`; `:ravilo-cast:jsBrowserProductionWebpack`. The
cause was read from the production database (the episode's `credits` row, source `heuristic`) and an ffprobe of the
file.

**Re-test (needs the receiver deployed — it is served by the backend):** Mac → a series → cast to the Nest Hub →
*Shuffle* → seek to about 50 s before the end. The hub's card shows *Starts in 6 s* about 8 s before the end and counts
6 → 1 before the next entry loads; the Mac's remote shows *Play in 6s … 1s* with *Watch credits* the whole time; the
Mac's `ravilo.log` carries `cast: Køkken hub · nextup at …ms of …ms (credits …)`. The same on the bedroom TV (direct
play, `ch6`). An episode whose credits marker is a minute before the end still gets its card at the marker.
