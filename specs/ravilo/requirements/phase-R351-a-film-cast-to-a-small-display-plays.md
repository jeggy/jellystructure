# Phase R351 — A film cast to a small display plays

> Found 2026-10-02 testing the Mac app against backend `v1.48-34-g0a5a7e59`: every film or episode cast to the
> kitchen's Google Nest Hub (a 1024 × 600 display) fails about two seconds in. Music cast to the same hub plays.

## Status

`Planned` — written 2026-10-02 (dev-authored) from the Mac test and the code. Number given by the coordinator.
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
| 1080p | 1920 × 1080 | 4.1 | `avc1.640029` |
| 720p | 1280 × 720 | 3.1 | `avc1.64001F` |
| 480p | 854 × 480 | 3.0 | `avc1.64001E` |

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
