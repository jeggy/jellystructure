# Ravilo streaming — the evidence from real plays (2026-10-08)

Read-only. Sources: the backend database (`playback_qoe`, `playback_start_sample`, `ravilo_device`,
`playback_session`), Jellyfin's database (`MediaStreamInfos`, `BaseItems`), Jellyfin's logs (main logs and
`FFmpeg.Transcode-*.log`) and `encoding.xml`, `nvidia-smi`, and the Ravilo source. Windows differ by source and are
stated per table: QoE and start samples cover **30 days**. Jellyfin keeps **3 days** of logs (2026-10-05 → 10-08), and
those days are dominated by test plays of 308. Nothing was changed; no device was touched.

## 1. Direct play vs transcode

QoE rows (one per play), last 30 days:

| Device kind | Plays | Direct | Transcode | Rebuffers | Rebuffer time | Variant ↓ / ↑ |
|---|---|---|---|---|---|---|
| Android TV (Ravilo) | 776 | 733 (94 %) | 43 | 548 | 659 s | 0 / 0 |
| Phone (Android) | 50 | 43 | 7 | 16 | 95 s | 1 / 1 |
| Web | 21 | 14 | 7 | 0 | 0 | 0 / 0 |
| Mac | 12 | 0 | 12 | 0 | 0 | 0 / 0 |
| Linux | 2 | 0 | 2 | 0 | 0 | 0 / 0 |
| Chromecast receiver | 2 | 0 | 2 | 1 | 29 s | 0 / 3 |

The receiver reports QoE only since 308 (2026-10-05), so casts before that are missing. On an Android TV or a phone,
a play is almost always a direct play. The Mac transcodes every time.

**Why plays transcode** — the 57 video transcodes in Jellyfin's 3-day log window (7 distinct items, mostly 308 tests):
every one re-encoded the **picture** to H.264 (`h264_nvenc`); none copied the video. By source:

| Source video | Jobs | Notes |
|---|---|---|
| HEVC, Dolby Vision 7 with enhancement layer | 42 | always tone-mapped to SDR; TrueHD/DTS audio → AAC |
| HEVC, HDR10+ (with or without DV) | 6 | tone-mapped; E-AC-3 → AAC |
| HEVC, HDR10 | 2 | a phone; tone-mapped; audio copied |
| H.264 SDR | 3 | burned-in subtitle (PGS) |
| HEVC SDR | 1 | a cast receiver |

The reasons Jellyfin gives (from the backend's `no ladder` lines and the stream URLs) are, in order of how often they
appear: `VideoCodecNotSupported` / a range the target can't carry (DV7+EL, HDR10+), `AudioCodecNotSupported`
(TrueHD, DTS, and on the Chromecast also AC-3/E-AC-3), `ContainerBitrateExceedsLimit` (a cap, including one from a bogus
4.3 Mbps "measurement" — §6), and subtitle burn-in.

**Jellyfin's encoder settings** (`encoding.xml`): `AllowHevcEncoding = false` and `AllowAv1Encoding = false`, so
**every** transcode is H.264 SDR, even for TVs that decode HEVC HDR10. Tone-mapping (bt2390) is on.

## 2. Time to first frame

`playback_start_sample` (seconds, rounded), last 30 days:

| Device kind | Delivery | Plays | Median | p90 | Max | ≥ 5 s |
|---|---|---|---|---|---|---|
| Android TV | direct | 1 069 | 1 | 2 | 38 | 2 % |
| Android TV | transcode | 73 | 2 | 15 | 22 | 40 % |
| Phone | direct | 67 | 2 | 3 | 19 | 7 % |
| Phone | transcode | 15 | 13 | 24 | 25 | 100 % |
| Mac | transcode | 23 | 5 | 7 | 380 | 52 % |
| Web | direct / transcode | 14 / 7 | 1 | 1 | 1 | 0 % (the web timer looks wrong) |

**The cold start of a Jellyfin transcode** (FFmpeg logs, 55 video jobs): the first output frame appears **8.5 s
(median) after ffmpeg starts, p90 16 s, min 4.5 s, max 18.5 s**. Once running, the encoder makes 4–11× realtime
(median of per-job medians ≈ 6×). So the wait is the start-up, not the encoding speed. Every job runs with
`-analyzeduration 200M -probesize 1G`, which lets ffmpeg read up to 1 GB (200 s) of input before the first frame.
On an 80 Mbps remux that is about 100 s of file. This is a likely, **unproven**, cause of the start-up time and is worth
measuring. Each seek into a part not yet encoded pays the cost again: **25 of the 57 jobs** were restarts at a new
position (`-ss`).

## 3. Rebuffers

By play (QoE), last 30 days: **direct plays** 530 rebuffers in 791 plays (62 % of plays have at least one), but each
is short (**0.55 s average**). **Transcodes** 35 rebuffers in 73 plays (36 % with one), **4.4 s average**. Android
counts a stall only after the first frame and not after a seek (`RaviloPlayerAndroid.kt`), so the many short
direct-play stalls are real but brief. Their cause is not known from this data (episode changes, the start of a resumed
play, or audio switches are candidates).

By hour, since 2026-10-04 (session hours from `playback_session`, pauses included, so the rates are lower bounds):
Android TV 80 rebuffers / 34 s in 118 plays ≈ **3.9 per session-hour**; phone 6 / 88 s in 9 plays ≈ 1.3; web and Mac 0;
Chromecast 1 rebuffer of 29 s (a remote viewer on a 24 Mbps rung over a ~15 Mbps path). The cast hours (57 h) are
inflated by a paused session that never ended.

The worst single device was a third Android TV, from a remote household: **13 rebuffers, 349 s** in 11 plays (one play
with 10).

## 4. The library: what forces a transcode, per device kind

Jellyfin's `MediaStreamInfos`, all 332 films and 9 193 episodes:

| Fact | Films | Episodes |
|---|---|---|
| 2160p | 46 % | 1.5 % |
| HDR10 (PQ) | 36 % | 1.0 % |
| Dolby Vision, any | 18 % | 0.9 % |
| — DV profile 7 (enhancement layer) | **4.8 %** (16) | 0 |
| — DV profile 8 | 13 % | 0.9 % |
| HDR10+ | 11 % | 0.3 % |
| AV1 | 0.3 % | 3.0 % |
| Other video (MPEG-2, VC-1 …) | 4.8 % | 1.7 % |
| Video over 54 Mbps (0.9 × the TVs' 60 Mbps ceiling) | 6.6 % | 0 |
| Default audio TrueHD or DTS | 31 % | 5.2 % |
| Only TrueHD/DTS audio (nothing else) | 4.5 % | 4.7 % |
| Default audio AC-3 / E-AC-3 | 45 % | 48 % |
| **No AAC/MP3/Opus/FLAC track at all** | **75 %** | **53 %** |
| Has PGS subtitles | 26 % | 12 % |
| PGS but no text subtitle | 1.8 % | 2.9 % |

What that means per device kind:

| Device kind | Picture must be re-encoded | Audio must be re-encoded | Comment |
|---|---|---|---|
| Android TV (Ravilo; HEVC/H.264/AV1, HDR10/DV, 60 Mbps; TrueHD/DTS via the FFmpeg extension) | **12 % of films, 1.7 % of episodes** (DV7+EL, > 54 Mbps, MPEG-2/VC-1) | rarely | the 94 % direct-play rate matches |
| Phone (Pixel 9 Pro; 240 Mbps) | DV7+EL, MPEG-2/VC-1 (≈ 10 % of films) | rarely | direct play is the norm; transcodes start slowly (§2) |
| **Chromecast** (the living-room TV's built-in; probes say no AC-3/E-AC-3 (R297), no DV, no AV1) | ≈ 10 % of films, 5 % of episodes | **76 % of films, 54 % of episodes** | **80 % of films and 57 % of episodes need a transcode** |
| Web browser | depends on the browser (HEVC/HDR mostly no) | AC-3/E-AC-3/TrueHD/DTS mostly no | not measured here |

**The two household Android TVs' own decoders** (read 2026-10-08 from `media_codecs*.xml` and `dumpsys display` over adb,
read-only; both screens were off and nothing was pressed). Both models answer the same:

| | Hardware decoders |
|---|---|
| Video | HEVC, H.264, AV1, VP9, MPEG-2 (no VC-1) |
| Dolby Vision | `dvhe.dtr`, `dvhe.stn`, `dvhe.st`, `dvav.se`, `dvav1.10` (profiles 4, 5, 8, 9, 10); **no profile 7 (`dvhe.dtb`)** |
| Display HDR types | Dolby Vision, HDR10, HLG; **no HDR10+** (an HDR10+ file plays as its HDR10 base) |
| Audio (DSP) | AC-3, E-AC-3, DTS, DTS-HD (LBR too); **no TrueHD** (Ravilo decodes TrueHD and DTS with its FFmpeg extension) |

So on these TVs only DV7 with its enhancement layer, VC-1 and a bitrate above the 60 Mbps ceiling force a picture
re-encode. No test play was made (it would write watch history).

**Preparing files ahead of time would remove most transcodes on a Chromecast:** a stereo (or 5.1) AAC track added to
each file, or one made once by jellystructure (R291 already makes AAC renditions on the fly), turns 76 % of films into
an audio-only job or none. A DV8.1/HDR10 base layer made from the 16 DV7 films removes the largest picture re-encode
for Android TVs and phones. Neither helps a bitrate above a device's ceiling (6.6 % of films on the TVs).

## 5. GPU headroom

- Two GPUs: Quadro P4000 (no NVENC session limit) and GeForce RTX 2060 SUPER (consumer NVENC session cap; 8 on
  recent drivers, to be verified for driver 550.163). Jellyfin runs `-init_hw_device cuda=cu:0` with every GPU visible.
  CUDA's default order is fastest first, so `cu:0` is most likely the RTX 2060 SUPER. The earlier investigation saw the
  encode on it.
- Idle at sampling time (0 % encoder, 0 sessions). During the plays: encoders ran at 4–11× realtime. The 2026-10-05
  investigation saw 0 % utilisation on the RTX 2060 mid-play with one encode.
- **Most concurrent video encodes seen: 4** (overlapping FFmpeg logs, 3-day window, during 308's ladder tests: a
  variant switch keeps the old encode alive ~60 s). No gate saturation in the backend log.

## 6. Other findings

- **Bandwidth guesses stored as measurements.** 34 QoE rows hold exactly **4 300 000** and 11 hold exactly
  **3 200 000** (Media3's initial estimates); one of each is on an HLS play, which 308 then used as the device's
  "measured" throughput (the Pixel's 2.37 Mbps cap on 2026-10-07). See 309 FR-309-13.
- **Direct-play estimates read the file's pace, not the link:** median 5.7 Mbps, p10 2.3, p90 20 (938 rows) on links
  of hundreds of Mbps. HLS estimates range 3.2–485 Mbps.
- **Start at 0, then jump:** 6 times in 3 days a transcode started at 0:00 and a second job started at another position
  within 30 s (a resume or a skip, paying the cold start twice); 4 more were same-bitrate restarts at a new position.
- **Double stops:** 24 times in 4 days Jellyfin received a stop at **0 ms** within 5 s after a real stop of the same item,
  wiping the place (see 312).
- **The Mac** transcodes everything (12 of 12), median start 5 s, one start of 380 s.
- **The web start timer** reports 1 s for every play, transcodes included; it probably measures something else.
- A Chromecast session paused on 2026-10-05 never ended: each "offline" event rewrites `position_at`, so the 24 h rule
  never fires (noted earlier, still open).

## What the numbers say, in short

1. On Android TVs and phones the problem is **not** everyday plays: 94 % direct-play and start in 1–2 s. It is the
   **8.5–16 s cold start of every transcode** and of every seek inside one, plus short direct-play stalls.
2. On a **Chromecast** most plays transcode, mostly because of **audio** (no AC-3/E-AC-3/TrueHD/DTS); making a
   compatible audio track ahead of time would remove most of them.
3. Every transcode is H.264 SDR (`AllowHevcEncoding = false`), so HDR-capable TVs get SDR, at a higher bitrate than HEVC
   would need.
4. The adaptive ladder (308) has barely run on real plays yet (3 up, 1 down switches in total).
