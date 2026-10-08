# Phase R379 — The device's own audio decoder first, like Jellyfin's Android app

> Owner, 2026-10-07, relaying a remote household member: Ravilo crashes on his phone — an episode *"crashed at 0.35"*,
> just as its intro finished. Then: *"We want the same approach jellyfin android has."*

## Status

`⚠ Partial` — written and built 2026-10-07 (dev-authored) from the owner's ask; re-dev-reviewed 2026-10-08; the
owner's decisions built 2026-10-08 (*Build notes (2026-10-08)*), unit-tested; device acceptance partly owed. Android only (`:ravilo-player`, `:ravilo-ui`
androidMain, `:ravilo-android`); no server, wire, string or config change. Number verified free on `origin/main`
`938b92b5`'s tree and the local `main` (Ravilo tops at R378).

## What happened

Prod, 2026-10-07 17:46–17:50 UTC: a Galaxy S23 (SM-S911B, Ravilo 1.50 from Play) direct-played an episode five times
(H.264 1080p, **AC3 5.1**, an HDTV capture). Four plays ended with the events socket closing without a close frame
(`1006`) and the stop watchdog stopping the play — the app process died, with no error the app could catch. The fifth
played on for ten minutes.

The file's audio changes channel count for three frames at the end of the intro:

```
0.000 s   AC3 6 ch (5.1)
36.032 s  AC3 2 ch (stereo)
36.128 s  AC3 6 ch (5.1)
```

Ravilo decodes all audio with jellyfin's FFmpeg extension first (`RaviloRenderers`: `EXTENSION_RENDERER_MODE_PREFER`).
Media3's `ffmpeg_jni.cc` (`decodePacket`, 1.8.0, unchanged on `main` 2026-10) builds its `SwrContext` once, from the
first frame's channel layout, and never rebuilds it. When the stereo frames arrive the resampler still converts six
channels: it reads input planes that don't exist and writes past the output buffer it sized for two. That is a native
crash, so the whole app goes with it. Any AC3/E-AC3 stream that changes channel count (common in TV captures:
5.1 programme, stereo adverts or idents) does this on every Android client.

`RaviloRenderers`' own doc says PREFER gives *"the same automatic DTS / TrueHD / AC3 / E-AC3 handling as
jellyfin-androidtv"*. It doesn't: neither Jellyfin app prefers FFmpeg by default.

## What Jellyfin's Android apps do (2026-10-07, `master`)

- **jellyfin-android** (`PlayerViewModel.setupPlayer`): `DefaultRenderersFactory` with
  `setEnableDecoderFallback(true)` and **`EXTENSION_RENDERER_MODE_ON`** — the platform's MediaCodec decoders first,
  FFmpeg only for a format no platform decoder takes. When playback fails with a `MediaCodecDecoderException`
  (`onPlayerError`), it releases the player once, rebuilds it with **`EXTENSION_RENDERER_MODE_PREFER`**, and restarts
  the item; the preference stays for the rest of that player's life.
- **jellyfin-androidtv** (`ExoPlayerBackend`): the same `ON` by default; `PREFER` only behind the user setting
  *Prefer FFmpeg* (`exoplayer_prefer_ffmpeg`, default off).

## Requirements

**FR-R379-1 — Platform first.** The video player's and the music player's renderers factory uses
`EXTENSION_RENDERER_MODE_ON` with decoder fallback on. A format the device decodes itself (AC3/E-AC3 on most phones
and TVs with Dolby decoders, AAC, MP3, FLAC, Opus) uses the device's decoder — or passthrough, where the audio output
takes the format — and FFmpeg only takes what nothing on the device can (DTS and TrueHD on most devices, WMA).
Video stays MediaCodec-only, as today (the cover-track fix in `RaviloRenderers`).

**FR-R379-2 — Retry once with FFmpeg preferred.** When a video play fails because a platform decoder failed
(`PlaybackException` whose cause is a `MediaCodecDecoderException`), the player does not report the failure. It
releases its engine, builds a new one with `EXTENSION_RENDERER_MODE_PREFER`, and starts the same item where it was,
in the same play/pause state and with the same track choices. It does this once: a failure on the preferring engine
is a failure (R306 as today). The preference holds for the rest of that player's life (the player screen, including
the next episodes), as in jellyfin-android.

**FR-R379-3 — Nothing else changes.** The audio codecs Ravilo declares to the server (`supportedAudioCodecs`) stay as
they are; direct play vs transcode is decided exactly as today.

## Out of scope / open

- A device with **no** platform AC3 decoder still decodes AC3 with FFmpeg and still crashes on a channel change — as
  Jellyfin's apps do. Fixing the native decoder (rebuild the `SwrContext` when the layout changes) is a separate,
  larger phase: our own build of the GPL decoder, or an upstream fix.
- No user setting (jellyfin-androidtv's *Prefer FFmpeg*); the automatic retry covers what it was for.

## Acceptance

1. The reported episode's file (AC3 5.1 → 2.0 → 5.1 at 36.03 s) plays past 0:37 on a phone with a Dolby decoder.
2. A debug build logs which audio decoder each play used (`onAudioDecoderInitialized`): an AC3 file on the Pixel 9
   and on Stue TV names a platform decoder (or no decoder, for passthrough), a DTS file names FFmpeg.
3. Forcing a `MediaCodecDecoderException` restarts the item once at the same position with FFmpeg preferred; a second
   failure shows R306's failure as today.

## Build notes (2026-10-07)

- `RaviloRenderers.create(context, preferExtensions = false)`: `EXTENSION_RENDERER_MODE_ON`, or `PREFER` when asked;
  video still forced to MediaCodec only. `RaviloPlayerEngine.renderersFactoryProvider` is `(Context, Boolean)`; both
  `MainActivity`s pass the flag through; the music engine always asks for `false` (FR-R379-1).
- `RaviloPlayer` (androidMain): `preferExtensions` per player; `onPlayerError` with a `MediaCodecDecoderException`
  cause sets it, posts `restartPreferringExtensions()` on the engine's looper and does not set `playbackFailed`; the
  restart releases the failed engine (unless it was already released or replaced), builds a new one, and re-prepares
  the same `MediaItem` at the same position with the same `playWhenReady` and `trackSelectionParameters`.
- Debug builds: `DebugLoadLogger` (tag `R291`) logs `audio decoder <name>` and an error's cause class (acceptance 2).
- Check: `:ravilo-android:compileDebugKotlin` green. Device acceptance 1–3 owed.

## Re-dev review (2026-10-08, against `main` `4222ac4c`)

Read against `RaviloRenderers.kt` (`create(context, preferExtensions)`), `RaviloPlayerAndroid.kt`
(`preferExtensions`, the `onPlayerError` path, `restartPreferringExtensions`), `RaviloPlayerEngine.kt`, the
dependency `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`, and 308/309/R291 for interactions. Five items, one for
the owner.

1. **Built as specified.** `EXTENSION_RENDERER_MODE_ON` by default, `PREFER` on the one retry after a
   `MediaCodecDecoderException`, video still MediaCodec-only, the music engine always `false`. The `preferExtensions`
   flag lives as long as the player, as jellyfin-android does. Device acceptance 1–3 is still owed.
2. **The crash stays on devices with no platform AC3/E-AC3 decoder, possibly including the household's Pixel.**
   Platform-first only helps where a Dolby decoder exists (the reporting Galaxy S23 has one). Google's Pixel phones
   have historically shipped without platform AC3/E-AC3 decoding; if the Pixel 9 Pro is one of them, every AC3 file
   still goes through the FFmpeg extension and still crashes on a channel-count change. Verify first, as acceptance 2's
   decoder log (or the phone's `media_codecs*.xml`) shows. **For the owner:**
   - (a) fix the native decoder: rebuild `SwrContext` in `ffmpeg_jni.cc` when the channel layout changes, in our own
     build of the jellyfin decoder 1.8.0+1, and offer it upstream **(lean: a small, contained native change that fixes
     every device)**;
   - (b) server-side: the app reports which audio codecs its platform decodes, and AC3 files with a channel change
     (found at scan by ffprobe) get an audio-only transcode on devices without one. This costs a transcode, though a
     video-copy one;
   - (c) accept the gap, as Jellyfin's apps do.
3. **The retry is a cold start on a transcode.** The rebuild re-prepares the same `MediaItem` at the position. On a
   direct play that is cheap. On an HLS transcode it is a new Jellyfin request at that position: 8–38 s cold today, and
   on a ladder it starts from the first listed variant. It is acceptable for a rare failure, but 309's re-review item 7
   applies: refresh the ladder's first listed variant from the device's record.
4. **Interaction with R291: an improvement.** Renditions are made in the variant's audio codec (AC3 when Jellyfin
   copies AC3). Under platform-first those AC3 renditions now reach the device's own decoder, or passthrough on a TV,
   instead of FFmpeg, which removes the crash path for transcoded multi-audio films on Dolby-capable devices. No
   conflict with 308/309: the audio decoder is independent of the video rung.
5. **Make the decoder visible in production.** Acceptance 2's decoder name is logged only in debug builds. Add it to
   the QoE report as an additive `audio_decoder` field (like `video_decoder`), so the next crash report says which
   decoder was in use without a device in hand. Lean: yes, in the same app release as 309b.

## Decided by the owner (2026-10-08, after the streaming re-review)

1. **Phones with no platform AC3 decoder: the server re-encodes just the audio** for them (against the lean of
   patching our FFmpeg decoder). A device whose decoder list has no AC3/E-AC3 decoder declares so, and the backend asks
   Jellyfin for an audio-only transcode (video copied) for AC3/E-AC3 sources on it. Verify first whether the household
   Pixel is one of them.
2. QoE gains an `audio_decoder` field, so a crash report names the decoder. See `specs/research-reports/ravilo-streaming-plan-2026-10-08.md` for the whole order.

## Build notes (2026-10-08) — the owner's decisions

- **The Pixel 9 Pro has platform Dolby decoders** (read-only `dumpsys media.player`: `c2.dolby.eac3.decoder` takes
  `audio/ac3` and `audio/eac3`, vendor `media_codecs_dolby_c2.xml`). So on the household phone R379's platform-first
  already keeps AC-3 away from FFmpeg, and the rule below changes nothing there; it is for phones without Dolby.
- **The app reports what its platform decodes** (additive wire fields on `ClientCapabilities`):
  `platform_audio_decoders` — the AC-3 family (`ac3`, `eac3`) a `MediaCodecList` decoder takes for `audio/ac3`,
  `audio/eac3` or `audio/eac3-joc`, read once per process (`platformAudioDecoders()`, Android; null on the desktop
  and the web, which never decode AC-3 with Media3's FFmpeg extension). HDMI passthrough is deliberately not counted:
  it depends on what is plugged in at the moment, and without it Media3 falls back to the crashing FFmpeg path.
  `hls_hevc_capable` — the player takes HEVC in fMP4 HLS (`supportsHevcOverHls()`), stated without opting every
  transcode into it (Android's `hls_hevc` stays false, as today).
- **The server re-encodes just the audio** (`tv/PlatformAudio.kt`, one pure rule `forPlatformAudio`): on a device that
  reports the list, every AC-3 family codec it lacks leaves the declared audio codecs, so Jellyfin neither direct-plays
  nor copies it (R297's transcode audio is the declared list's intersection) and converts it instead. When the play's
  own audio track (the picked one, else the default, else the first — `playingAudioCodec`) is such a codec and the app
  is `hls_hevc_capable`, the negotiation also offers fMP4 with HEVC first, so an HEVC picture is copied, not re-encoded
  (an H.264 picture is copied in either container). Applied at every negotiation: start, R381's prepare, both restreams
  (the burn-in one narrows before `burnInLimits`) and Live TV's tune (list only — a channel's codec isn't known before
  tuning). A start that narrows logs `no platform … decoder — those are re-encoded[, HEVC copied in fMP4] (R379)`.
  Older apps (no list) are negotiated byte for byte as before.
- **QoE `audio_decoder`** (additive): Media3's `onAudioDecoderInitialized` name on Android (the engine's, kept across
  items like the video decoder's; a FR-R379-2 rebuild reports its new one), null for passthrough and elsewhere; stored in
  `playback_qoe.audio_decoder` (migration 76, after 314a's 75) and returned in the QoE
  summaries.
- **With 314's added copies:** the list is narrowed first, so 314's `copyForDevice` chooses against it: an AC-3 film
  that 314 gave an AAC *Stereo* copy plays that copy directly on a phone without Dolby (no encode at all); only then
  does the playing track decide the HEVC-copy path.
- **Tests:** `PlatformAudioTest` (9: an older app unchanged, both decoders unchanged, none ⇒ both out of the direct-play
  and transcode profiles, only the missing one leaves, AC-3 play + HEVC-capable ⇒ fMP4 profile, not capable ⇒ TS, the
  declared-nothing guard, the playing-track choice, 314's AAC copy chosen on a phone without Dolby); `MusicEditionsStoreTest`'s schema rewind drops the new column.

