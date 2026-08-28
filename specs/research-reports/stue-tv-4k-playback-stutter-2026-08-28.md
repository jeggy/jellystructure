# 4K playback stutter on stue TV — what actually causes it

**Date:** 2026-08-28

> Owner report: *"issues streaming some of the highest quality files from the server to stue TV"*, most
> recently the evening of **2026-08-27 while watching Offboarding season 2**. A third-party technical
> briefing was supplied alongside it, attributing the stutter to (1) an ExoPlayer Dolby Vision Profile 8
> MKV demux bottleneck, (2) lossless-audio (TrueHD / DTS-HD MA) demuxer overload, and (3) missing 24p
> refresh-rate matching. This report checks all three against the real files, the real client and the
> real server, and records what the evidence actually supports.

**Verdict: all three supplied root causes are wrong or inapplicable for the reported title.** One of
them (lossless audio) describes a real mechanism that applies to a *different* part of the library. The
measurable causes are **delivery** (Wi-Fi retries, 2.4 GHz association, shared-spindle I/O) and a
**client decode ceiling** the server currently never asks about. Adopted as phases **177**, **178** and
**R216**.

---

## 1. The two files involved

Both were probed directly on disk, not inferred from names.

| | `Offboarding.S02E03…FLUX.mkv` | `Spindlelegs.2024…CiNEPHiLES.mkv` |
|---|---|---|
| Size / duration | 9.5 GB / 3230 s | 65 GB / 6041 s |
| Video | HEVC Main 10, 3840×1606, 23.976 fps, `smpte2084` | HEVC Main 10 |
| Dolby Vision | `dv_profile=8`, `dv_bl_signal_compatibility_id=1`, `el_present_flag=0` → **Profile 8.1**, single-layer, HDR10+ base | HDR10 (no DV) |
| Audio | **E-AC3 (DDP5.1 + Atmos), 6ch, 768 kbps** | **DTS-HD MA 5.1** + FLAC 2.0 |
| Avg bitrate | **24.0 Mbps** | **93 Mbps** |

Offboarding peak video bitrate, sliding windows over the real packet stream:

| window | 1 s | 2 s | 5 s | 10 s | 30 s |
|---|---|---|---|---|---|
| peak | **88.9 Mbps** | 58.1 | 39.0 | 34.0 | 29.7 |

So the stream is a ~24 Mbps average that demands **~35–40 Mbps sustained** through dense scenes, with
brief ~89 Mbps spikes. Spindlelegs is a different animal entirely: 93 Mbps *sustained for two hours*.

**This distinction matters.** Offboarding is a mid-tier WEB-DL. The owner's phrase "highest quality files"
much better describes the UHD remuxes — and those are the ones that break the ceilings in §3.

## 2. The client: Sony BRAVIA 4K VH21

Read live over ADB (Android 12 / SDK 31, MediaTek **MT5895**).

### 2.1 The display has exactly one mode

```
supportedModes [{id=1, width=3840, height=2160, fps=60.000004, alternativeRefreshRates=[]}]
mRefreshRateChangeable: false
mDefaultRefreshRate: 60.0
```

There is **no 24 Hz mode for anything to switch to**. `Surface.setFrameRate()` and the `Display.Mode`
API are both dead ends on this panel — whatever 24p handling happens, happens inside Sony's own picture
processing, invisible to Android. Any engineering effort aimed at app-side refresh-rate matching is
wasted here.

### 2.2 Both video decoders are rated to 60 Mbps

```
OMX.dolby.vision.dvhe.st.decoder    profile/levels: [256/256]   ← DolbyVisionProfileDvheSt (profile 8)
                                    bitrate-range = "1-60000000"
                                    max-concurrent-instances = "1"
                                    feature-adaptive-playback = 0
                                    performance-point-3840x2160-range = "60-60"
OMX.MTK.VIDEO.DECODER.HEVC          bitrate-range = "1-60000000"
                                    max-concurrent-instances = "2"
```

**60 Mbps is this SoC's declared decode budget, on both the DV and the plain-HEVC path.** Switching
between them buys no headroom. Offboarding briefly overshoots it; **Spindlelegs exceeds it for its entire
runtime**, which explains that case far better than the Wi-Fi finding recorded at the time.

The TV also has genuine Dolby Vision hardware (`dvhe.st` = single-track, i.e. profile 8) and reports
`mSupportedHdrTypes=[1, 2, 3]` (DV, HDR10, HLG).

### 2.3 Heap headroom is tight

`dalvik.vm.heapgrowthlimit = 192m` (heapsize 512m). Media3's `DefaultLoadControl` defaults to a
**128 MB** video buffer (`DEFAULT_VIDEO_BUFFER_SIZE = 2000 × 64 KB`), allocated as Java-heap `byte[]`.
At 24 Mbps that's ~42 s of buffer but it sits uncomfortably close to the growth limit alongside Compose
and Coil's caches. Any buffer enlargement must be measured, not assumed safe — and GC pressure here is
itself a candidate stutter source that has never been ruled out.

### 2.4 Ethernet is a 10/100 port, currently unplugged

`eth0` is `DOWN / NO-CARRIER`; driver is **`star-eth`**, MediaTek's Fast Ethernet MAC for TV SoCs. The
negotiated speed can't be read without a cable, but this family is 10/100 — so a wired link would give
~94 Mbps of rock-solid, jitter-free throughput. That comfortably covers Offboarding and everything like
it, sits *right at the wall* for a 93 Mbps Spindlelegs, and eliminates the entire retry/band-steering class
of failure. **Recommended regardless** — it makes delivery deterministic, which is what §4 is about — but
it does not remove the need for the 60 Mbps decode ceiling work, and it narrows rather than widens the
margin for the very largest remuxes.

## 3. The three supplied claims, checked

### Claim 1 — "ExoPlayer Profile 8 MKV parser bottleneck" → **not supported**

The file identification is correct (Profile 8.1). The mechanism is not.

The DV RPU rides inside HEVC NAL units. Media3's `MatroskaExtractor` never parses it per frame — it
reads the `dvcC` block **once at init** (`MatroskaExtractor.java:1429`, `BLOCK_ADD_ID_TYPE_DVCC`) and
uses it only to rewrite the sample MIME type to `video/dolby-vision` and emit an RFC 6381 codecs string
(`dvhe.08.06`, via `DolbyVisionConfig.parse`). There is no per-frame demux cost and therefore no
demux-driven "clock synchronization drift" path. The claimed symptom — drift between frames and display
refresh — is a *description of judder*, i.e. claim 3, conflated with a parser bug.

The TV additionally has native DV profile-8 decoding (§2.2), so "lacks full hardware pipeline support"
is false here. Media3 also carries a documented soft-match fallback from `video/dolby-vision` to HEVC
(`MediaCodecUtil.getDecoderInfosSoftMatch` / `getAlternativeCodecMimeType`) for devices that don't.

### Claim 2 — "TrueHD / DTS-HD MA demuxer overload" → **false for Offboarding, real elsewhere**

Offboarding carries **E-AC3 at 768 kbps** (§1). There is no lossless track in the file. The premise is
simply wrong for the reported title.

But the mechanism is real for the rest of the library: **417 of 6798** library MKVs carry TrueHD or
DTS-HD MA by filename, and Ravilo decodes audio in **software** via the FFmpeg extension renderer
(`RaviloRenderers.kt` sets `EXTENSION_RENDERER_MODE_PREFER`, keeping hardware MediaCodec for video
only). Spindlelegs is `DTS-HD MA 5.1`. So the briefing accidentally describes a genuine issue affecting
~6% of the library — including, notably, the very remuxes that also breach the 60 Mbps ceiling.

### Claim 3 — "no refresh-rate matching" → **true, inert, and cannot explain the symptom**

Content is 23.976 fps and Ravilo has no frame-rate code (no `setFrameRate` / `Display.Mode` usage
anywhere in `ravilo-*`). But:

1. ExoPlayer **already** calls `Surface.setFrameRate()` itself (`VideoFrameReleaseHelper.java:436`),
   gated by the default `VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS` (`ExoPlayer.java:450`).
2. The panel exposes **one 60 Hz mode** and `mRefreshRateChangeable: false` (§2.1). Nothing to switch to.

And decisively: 3:2 pulldown judder is **constant, rhythmic and identical on every play**. The reported
symptom is intermittent — fine most evenings, bad on some. Judder cannot produce that pattern.

## 4. What the evidence actually shows

### 4.1 Delivery, on the night in question

Everything direct-played — the backend's own decision log shows `directPlay=true transcode=false` for
every recent session, and no Jellyfin transcode ran. So the server was serving bytes and nothing more.

From the TV's own Wi-Fi log, the session ran **~22:52 → 23:44 local** (screen off at 23:44 — one
episode). UniFi hourly stats for the TV across that evening (`tx` = delivered *to* the TV):

| local hour | delivered | signal | **retry %** |
|---|---|---|---|
| 21:00 | 99.9 MB | −52 | 4.6 |
| 22:00 | 6333 MB | −53 | 12.1 |
| **23:00** | **8596 MB** | **−59** | **18.6** ← the episode |

**18.6 % of downstream frames needed retransmission during the viewing hour**, with signal degrading to
−59 dBm. That matches the Spindlelegs signature recorded 2026-08-19 (~1–4 % baseline → ~21 % during
playback).

Immediately before that session the TV associated to the **2.4 GHz radio** and was ejected 34 s later:

```
22:51:32  assoc  BSSID …fe:bb  f=2462  link=130   isFirstConnectionAfterBoot=true
22:52:06  NETWORK_DISCONNECTION_EVENT  reason=34:DISASSOC_LOW_ACK  lastFreq=2462
22:52:10  assoc  BSSID …fe:bc  f=5240  link=585
```

One SSID ("JoJo") across both radios on the same UDR7, so band steering can and does land this TV on
2.4 GHz. A stream needing ~40 Mbps sustained on a 130 Mbps 2.4 GHz link will stutter outright. On this
occasion the AP moved it after `DISASSOC_LOW_ACK`; there is nothing guaranteeing it always will.

### 4.2 Shared-spindle I/O — armed, not implicated on the night

The Offboarding file has **link count 2** — hardlinked into `/mnt/series/cross-seed-links/HD-Space/` and
actively seeded. Playback reads, seeding reads and download writes all share one 23.6 TB spindle
(`sda`). Measured live during this investigation:

```
idle:                sda  r_await   8.17 ms   %util  4.09
under 154 MB/s write: sda  r_await  73.43 ms   %util 56.32
/proc/pressure/io    avg300 = 23.29
```

**Read latency ×9 under a concurrent write burst.** That is the mechanism behind the 2026-08-20/21
Offboarding case (qBittorrent writing to `/mnt/series` while streaming from it).

For 2026-08-27 specifically it was *not* the trigger: no qBittorrent writes to `/mnt/series` between
17:00 and 02:00, and nothing written during 22:52–23:44. Jellyfin did run a 65-minute tone-mapped 4K
trickplay job over a 10.4 GB file on that same spindle (16:00:22→17:05:30 UTC = **18:00→19:05 local**),
but that is hours before the session. Seeding *reads* could not be time-sliced to the window and remain
unverified.

### 4.3 The server is not the constraint

Wired at **2500 Mb/s** (`enp7s0`). Never a bottleneck for a 24 Mbps stream.

## 5. Gaps in our own code that this exposes

Not causes of the 2026-08-27 event, but the reason the system has no defence against any of the above:

1. **`deviceProfile()` never asks what the device can actually decode.** It is a hardcoded string
   (`JellyfinClient.kt:42-43`); only `allowedVideoRangeTypes()` and `h264TargetConditions()` vary. The
   TV's real 60 Mbps ceiling is invisible to the server, so a 93 Mbps remux is handed straight to a
   decoder rated for 60.
2. **The client's declared audio support is discarded.** `ClientCapabilities.audioCodecs` and
   `maxAudioChannels` (`Models.kt:65-66`) are populated by the client (`PlayerStore.kt:93-94`) and then
   **never read** — `DirectPlayProfiles` hardcodes `truehd,dts`, so lossless always direct-plays into a
   software decoder no matter what the client said.
3. **Nothing knows the link is bad.** No client-side link telemetry exists, so a 2.4 GHz association at
   130 Mbps is negotiated identically to a 585 Mbps 5 GHz one.
4. **Nothing measures playback quality.** Three stutter investigations (2026-08-19, 2026-08-20/21, this
   one) have all been forensic reconstructions after the fact. ExoPlayer already tracks dropped frames
   and rebuffers; none of it is captured or shipped anywhere.
5. **Background work is playback-blind.** `PlaybackTracker` (`PlaybackService.kt`) knows exactly when a
   device is streaming, and nothing consults it before starting a trickplay pass or letting a download
   saturate the spindle the stream is being read from.

Note also the deliberate absence of a `VideoBitrate` condition (`JellyfinClient.kt:92-93`, *"verified
live that one blocks direct play of any source above it"*). That comment is correct about a **blanket**
condition; it is the reason a per-codec, capability-derived ceiling is the right shape in Phase 177,
not a reason to avoid the idea.

## 6. Adopted direction

| | |
|---|---|
| **Phase 177** | Delivery-aware playback negotiation — report and honour the real decoder bitrate ceiling per codec, honour the client's audio-codec list so lossless can be audio-only transcoded, and cap bitrate when the link says to. Plus QoE ingest. |
| **Phase 178** | Playback-aware background I/O — never let a trickplay pass or a download storm the spindle a TV is streaming from. |
| **R216** | Client half — report decoder ceilings + link state, harden `LoadControl` against dips, and capture playback QoE from ExoPlayer's own analytics. |
| **Infrastructure** (no code) | Move the TV to Ethernet (§2.4); move qBittorrent's active-download path off `sda` and hardlink onto it only on completion; split the SSID or pin the TV to 5 GHz. |

## 7. What was not established

- **No stutter was reproduced live.** The 2026-08-27 session is reconstructed from Wi-Fi, UniFi and
  Jellyfin logs after the fact. The 18.6 % retry ratio is strong circumstantial evidence, not proof that
  it is what the owner saw.
- **Retry ratio is partly self-inflicted.** A heavy stream raises airtime utilisation, which raises
  retries. Cause and effect are entangled; 18.6 % is high enough to matter either way, but the split is
  unquantified.
- **Seeding reads during the session are unverified** (§4.2) — only writes could be time-sliced.
- **The GC/heap hypothesis (§2.3) is untested.** It is a plausible contributor with no measurement
  behind it; R216's telemetry is what would settle it.
- **The Ethernet port's negotiated speed is inferred** from the `star-eth` driver family, not measured —
  plugging a cable in and reading `/sys/class/net/eth0/speed` settles it in seconds.
