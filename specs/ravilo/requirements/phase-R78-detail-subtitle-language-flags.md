# Phase R78 — Detail hero: subtitle-track language flags

> Show the viewer, on a title's detail screen, which languages they can **read** it in — a second
> flag row (`SUBTITLES: {flags}`) directly under the R75 `AUDIO: {flags}` row, one flag per subtitle
> track that has a language.

## Problem
R75 added an audio-language flag strip to the Movie/Series detail hero, answering "what can I *hear*
this in?". The matching question — "what can I *read* this in?" — is still invisible until the viewer
starts playback and opens the subtitle picker. On a Nordic/Faroese-leaning service the subtitle
languages are often the deciding signal (e.g. an English film with Faroese or Danish subs). The
metadata already exists on the server: Jellyfin's `MediaStreams` carry `Type = "Subtitle"` streams
with a `language` each, and the scanner stores per-episode subtitle tracks (`TrackKind.SUBTITLE`).

## Goal
Add a **subtitle-language flag strip** to the detail hero, rendered as a second row immediately below
the existing audio strip, using the **same component, mapping, dedup, cap and styling** as R75 — only
the label ("SUBTITLES") and the source list differ.

## Current state (as-is)
- **Audio strip only.** `ravilo-ui/.../components/AudioFlagStrip.kt` renders the audio row:
  `AudioFlagStrip(audioLanguages: List<String>, modifier)`. It maps each code via a file-private
  `LANG_CC` (ISO-639-1 **and** ISO-639-2 B/T → flag `DrawableResource`), `.distinct()`s, caps at
  `FLAG_MAX = 5` with a `+N` pill, hides itself when nothing maps, and draws a hard-coded "AUDIO"
  label (`Color.White α0.55`, 9.sp, SemiBold, Sora). `LANG_CC`, `FLAG_MAX` and the styling are all
  `private` to this file.
- **Where it's inserted.** Both detail screens render it right after the meta row, guarded by
  `if (detail.audioLanguages.isNotEmpty())` with a leading `Spacer(8.dp)`:
  - `screens/MovieDetailScreen.kt` — between the `meta` Text and the synopsis (≈ lines 157–160).
  - `screens/SeriesDetailScreen.kt` — between the `meta` Text and the episode-progress block
    (≈ lines 209–212, before `val p = detail.progress`).
- **DTOs.** `shared/.../tv/Models.kt`: `MovieDetail` and `SeriesDetail` each carry
  `@SerialName("audio_languages") val audioLanguages: List<String> = emptyList()`. **No subtitle
  field exists.** (`SubTrack` in the same file is the unrelated playback-ticket subtitle DTO.)
- **Backend.** `src/linuxX64Main/.../tv/DetailService.kt`:
  - Movie: `audioLanguages = audioLanguagesFrom(jfDetail)`, where the helper filters
    `jfDetail.mediaStreams` by `type.equals("Audio", ignoreCase = true)` and maps non-blank lowercased
    `language`s.
  - Series: `seriesAudioLangs` from `item.episodes.firstOrNull()?.tracks` filtered to
    `TrackKind.AUDIO` (no extra Jellyfin round-trip — `JellyfinEpisodeItem` has no `MediaStreams`).
- **Subtitle source is confirmed available.** `JellyfinMediaStream` (`auth/Models.kt`) includes
  subtitle streams as `Type = "Subtitle"` with a populated `Language` (plus `isTextSubtitleStream`,
  `isForced`, `isExternal`). `TrackKind.SUBTITLE` exists in `commonMain/.../model/Media.kt` and
  episode `Track`s carry `language`.

## Requirements

### A. Detail API — expose ordered subtitle languages
1. Add a subtitle-language list to both detail DTOs in `shared/.../tv/Models.kt`, mirroring R75:
   ```kotlin
   @SerialName("subtitle_languages") val subtitleLanguages: List<String> = emptyList()
   ```
   The `= emptyList()` default keeps older payloads/clients compatible.
2. Populate it in `DetailService.kt`, parallel to the audio path:
   - **Movie:** filter `jfDetail.mediaStreams` by `type.equals("Subtitle", ignoreCase = true)`, map
     non-blank lowercased `language`s in physical stream order. Factor the audio/subtitle filter into
     one helper taking the stream-type string rather than duplicating it.
   - **Series:** reuse the first-episode track extraction with `TrackKind.SUBTITLE`:
     ```kotlin
     val seriesSubLangs = item.episodes.firstOrNull()?.tracks
         ?.filter { it.kind == dev.jellystructure.model.TrackKind.SUBTITLE }
         ?.mapNotNull { it.language?.lowercase()?.takeIf { l -> l.isNotBlank() } }
         ?: emptyList()
     ```
   - This is control-plane metadata only; no new Jellyfin round-trip (movie reuses the existing
     `jfDetail`; series reuses already-scanned tracks).

### B. UI — render the subtitle strip
3. **Parameterize the existing component, don't fork it.** Add a `label: String = "AUDIO"` parameter
   to `AudioFlagStrip` (or rename to a neutral `LanguageFlagStrip` with a `label` param and keep
   `AudioFlagStrip` as a thin wrapper). Reusing the same component guarantees the subtitle row gets
   the identical map, `.distinct()`, `FLAG_MAX` cap, `+N` pill, hide-when-empty rule, and styling for
   free. Do **not** duplicate `LANG_CC`/`FLAG_MAX`.
4. In **both** detail screens, render the subtitle row immediately after the audio block, guarded
   independently:
   ```kotlin
   if (detail.subtitleLanguages.isNotEmpty()) {
       Spacer(Modifier.height(6.dp))   // tighter than the 8.dp above AUDIO — same visual group
       SubtitleFlagStrip(detail.subtitleLanguages)   // label = "SUBTITLES"
   }
   ```
   placed right after the `audioLanguages` block (Movie: after ≈ line 160; Series: after ≈ line 212,
   before `val p = detail.progress`).
5. Same rules as R75: physical track order; skip untagged tracks; **`.distinct()`** so one flag per
   language even when several subtitle tracks share it; hide the whole row when nothing maps; max 5 +
   `+N`. An unmapped language is skipped, not drawn broken.

## Invariants
- DTOs defined once in `:shared`, reused by backend + android + wasmJs.
- Renders **server-pushed state only** — the subtitle-language list is server-derived (Jellyfin
  `MediaStreams` for movies, scanned episode tracks for series), never client-guessed.
- Bytes still stream straight from Jellyfin; this is detail-payload metadata only.
- One component, one `LANG_CC` map — audio and subtitle rows must never drift apart visually.

## Out of scope
- Distinguishing forced / SDH / external subtitle tracks in the strip — language only (the R75
  "display only, non-interactive" stance holds).
- Per-episode subtitle accuracy for series — like R75 audio, this reads the **first episode** as
  representative of the whole series; a future phase could reconcile mixed-subtitle series.
- Showing flags on poster tiles / rows — detail screen only.
- Any change to the playback subtitle picker (R55/R56 own that).

## Source references
- `ravilo-ui/.../components/AudioFlagStrip.kt` (component to parameterize),
  `ravilo-ui/.../screens/MovieDetailScreen.kt` + `SeriesDetailScreen.kt` (insertion points),
  `shared/.../tv/Models.kt` (`MovieDetail`/`SeriesDetail` DTOs),
  `src/linuxX64Main/.../tv/DetailService.kt` (`audioLanguagesFrom`, `seriesAudioLangs`),
  `src/linuxX64Main/.../auth/Models.kt` (`JellyfinMediaStream`),
  `src/commonMain/.../model/Media.kt` (`TrackKind.SUBTITLE`, `Track`).
- Related: **R75** (audio flags — canonical component, map, asset set, rules), **R41** (Jellyfin
  MediaStreams subtitle tracks), **R55/R56** (subtitle rendering / image subs), Phase 87 (admin
  pagebar flags — shared mapping origin).
