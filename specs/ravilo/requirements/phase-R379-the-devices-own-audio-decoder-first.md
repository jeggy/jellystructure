# Phase R379 — The device's own audio decoder first, like Jellyfin's Android app

> Owner, 2026-10-07, relaying a remote household member: Ravilo crashes on his phone — an episode *"crashed at 0.35"*,
> just as its intro finished. Then: *"We want the same approach jellyfin android has."*

## Status

`⚠ Partial` — written and built 2026-10-07 (dev-authored) from the owner's ask; compiles, **not device-tested**, not
dev-reviewed. Android only (`:ravilo-player`, `:ravilo-ui`
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
