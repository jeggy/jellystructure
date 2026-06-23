# Phase R46 — Human-readable audio/subtitle labels in the player picker

**Status:** ✓ Done (2026-06-23) · The `StreamTicket` now carries an `audio: List<AudioTrack>`
populated by `PlaybackService` from Jellyfin's `MediaStreams` (label = `DisplayTitle ?: Title ?:
lang·codec`); `RaviloPlayer.load` takes the audio metadata and the Android `audioTracks` getter
prefers the server label (mapped by audio-stream order) over the container `Format.label`, then a
**humanized** language name, then the raw code. A shared `languageName()` util also upgrades the
bare-code fallback for subtitles, and the web actual surfaces the server audio labels (display-only).
The picker's secondary line shows the humanized language unless the primary label already conveys it.
Backend + `:ravilo-ui` (android + wasmJs) compile.

_Partial:_ embedded-subtitle titles still come from the container (R41 externals already use
`DisplayTitle`); sourcing embedded-sub `DisplayTitle` from the backend via the same mechanism is a
small follow-up, not the reported issue (audio).

## Problem
In the Ravilo player's **Audio** and **Subtitles** picker, options show only a code — typically an
uppercased language code ("DAN", "ENG") — instead of the human-readable track title. The library
metadata already has the proper text: some episodes of **"De bedste år"** have an audio track titled
**"Synstolkning"** (Danish audio-description), and Jellyfin exposes a `DisplayTitle` like
"Dansk - Synstolkning - Dolby Digital - 5.1" for it. The viewer should see that, not "DAN".

## Current state (as-is) — where the title is lost
- **Picker label = whatever the player reports.** `screens/PlayerScreen.kt` `TrackPicker`
  (lines ~951–959) renders `track.label` (audio) and `sub.label` (subs) via `PickerOption`
  (label primary, `language` secondary).
- **Android player labels from the container only.** `seams/RaviloPlayerAndroid.kt` builds tracks as
  `val label = format.label ?: format.language?.uppercase() ?: "Track N"` (audio line ~125, subs
  ~142). `Format.label` comes from the **container** track-`Name` element. When the MKV/MP4 track has
  no embedded name (Jellyfin computes the human title server-side instead), the player falls back to
  the uppercased language code — which is what the viewer sees.
- **The StreamTicket carries no audio metadata.** `shared/.../tv/Models.kt` `StreamTicket`
  (lines ~71–83) has `subtitles: List<SubTrack>` but **no audio list**. So Jellyfin's audio
  `DisplayTitle`/`Title` (which `PlaybackService` already fetches in `getItemDetail`'s `MediaStreams`)
  is **never sent to the client** — only language survives to the picker.
- **Subtitles already do the right thing on the backend** but only for externals: `PlaybackService`
  `buildSubtracks` (line ~113) sets `label = s.displayTitle ?: s.title` and the player sets it via
  `MediaItem.SubtitleConfiguration.setLabel(...)`. Embedded subs still rely on container labels and
  hit the same `format.label ?: language.uppercase()` fallback.
- **`JellyfinMediaStream` already has the fields:** `auth/Models.kt` carries `displayTitle`, `title`,
  `language`, `codec`, `isDefault`, `isForced`, plus (to add if missing) `channels`/`channelLayout`.

## Requirements

### A. Backend — send audio-track metadata in the StreamTicket
1. Add an **audio track list** to the `StreamTicket` (new `AudioTrack` DTO in `shared/.../tv/Models.kt`:
   `index`, `language`, `label` (Jellyfin `DisplayTitle ?: Title`), optional `codec`, `channels`,
   `isDefault`). Populate it in `PlaybackService.startPlayback` from
   `itemDetail.mediaStreams.filter { it.type == "Audio" }`, mirroring `buildSubtracks`.
2. Keep the subtitle `label = displayTitle ?: title` behaviour; ensure the audio `label` prefers
   `DisplayTitle` (the fullest human string), falling back to `Title`, then a composed
   language+codec string, then language.

### B. Player — prefer the backend label, map by track order
3. Pass the ticket's audio metadata into the player (`RaviloPlayer.load`, alongside `subtitles`), and
   when enumerating ExoPlayer audio tracks, **prefer the backend `label`** for the matching track,
   falling back to `format.label`, then a humanized language, then "Track N". Match backend audio
   streams to ExoPlayer audio groups by **audio-only order** (Jellyfin `MediaStreams` audio order ↔
   ExoPlayer audio `TrackGroup` order); document the assumption and degrade gracefully (use the
   container label) if counts differ.
4. Humanize the bare language fallback: show a language **name** ("Danish", "English") not the raw
   ISO code where a name is known, so even a label-less track reads better than "DAN". Keep the code
   as the secondary line if useful.

### C. Subtitles parity + web
5. Apply the same label preference to embedded subtitle tracks (backend can also send the embedded
   sub `DisplayTitle` list, or reuse the same mechanism) so subs match audio.
6. Web (`RaviloPlayerWasm`): use the backend audio list to label audio options (it currently returns
   `audioTracks = emptyList()`); `<video>` audio-track selection is limited, so at minimum show the
   correct labels for what the browser exposes. Subtitle labels already flow via `track.label`.

## Invariants
- Data plane stays Jellyfin-direct; this only enriches the **control-plane** ticket with track
  metadata. Bytes still stream straight from Jellyfin.
- DTOs defined once in `:shared`, reused by backend + both clients.
- Renders server-pushed state only — the label is server-derived metadata, not client-guessed.

## Out of scope
- Re-ordering/auto-selecting tracks by preference (e.g. auto-pick audio-description) — display only.
- Channel-layout/codec badge design polish beyond showing the label text.
- A device-profile `PlaybackInfo` negotiation (tracked under R14 TODO) — this reuses the existing
  `getItemDetail` `MediaStreams`.

## Design reference
`src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` (`startPlayback`,
`buildSubtracks`), `src/.../auth/Models.kt` (`JellyfinMediaStream`),
`shared/.../tv/Models.kt` (`StreamTicket`, `SubTrack`, new `AudioTrack`),
`ravilo-ui/.../seams/RaviloPlayer*.kt` (`PlayerAudioTrack`/`PlayerSubtitleTrack`, `load`,
`audioTracks`), `ravilo-ui/.../screens/PlayerScreen.kt` (`TrackPicker`/`PickerOption`). Related: R41
(subtitle tracks from Jellyfin MediaStreams), R14 (player).
