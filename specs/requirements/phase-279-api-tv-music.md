# Phase 279 — `/api/tv/music/**`: what the phone reads and plays

## Status

`Planned` — written 2026-09-28 from the research report §2.4, §4.1, §5.1–§5.3 and §6.5, and the phone mockup's
data needs (`design/ravilo/mobile/ravilo-music.js`). **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). Builds on **275–277**,
`PlaybackService`/`StreamTicket` (180), **218** (the session ceiling) and **R319** (wire discipline). It serves
**R321** and **R322**. **281** extends it for audiobooks.

## Requirements

**FR-279-1 — New paths, new DTOs, nothing changed.** Everything below is on new `/api/tv/music/**` paths with
new DTOs (`MusicAlbumCard`, `MusicArtistCard`, `MusicTrack`, `MusicAlbumDetail`, `MusicArtistDetail`,
`MusicHome`, `MusicSearch`). No existing DTO gains a field. Every endpoint is scoped to the device's viewer
(275 FR-275-4). An installed app without music never calls them.

**FR-279-2 — `GET /music/home`.** The **Listen** tab's rows, server-composed, fixed and localised (no admin
editor in v1):

1. *Recently added* — albums.
2. *Recently played* — five tracks, from Jellyfin's `LastPlayedDate` for this viewer.
3. *Artists* — album artists with a picture first.
4. *A mix from your library* — **only when the library is large enough to mix** (Jellyfin's InstantMix of this
   household returns the whole library; lean: at least 300 tracks, dev tunes).
5. One row per genre with ≥ 3 albums.

An empty library returns no rows (the phone shows one sentence). Owner, 2026-09-27: Listen has **no
Now-playing card** — the mini bar already shows it.

**FR-279-3 — Browse.** `GET /music/albums|artists|tracks?sort=&page=`, with sort `added` (default) · `title`
· `year` · `played` (most played). `GET /music/genres` returns names with album counts, and `?genre=` filters
albums. `GET /music/playlists` returns this viewer's Jellyfin playlists (name, count, four covers).
Creating and editing playlists is phase 2 (R321 FR-R321-10).

**FR-279-4 — Detail.** `GET /music/album/{id}` returns the cover, title, album artist(s) with ids, year, type,
the track list (position, title, credits incl. *feat.*, duration, `has_lyrics`) and *more from this artist*.
`GET /music/artist/{id}` returns the image, background, name, type, span, the biography (plain text; the
source name **stripped** — a viewer never reads a provider's name), albums grouped by type, songs by play count,
and `videos[]` (277 FR-277-9: title, kind, year, length and the `media` id the video player opens), empty when
none.

**FR-279-5 — Search.** `GET /music/search?q=` returns **Songs · Albums · Artists**, each group with a total, and
the phone caps each at three with *See all*. An empty query returns *Recently played* and *Artists* (R321
FR-R321-8).

**FR-279-6 — Play.** `POST /music/play {track_id, capabilities}` → `PlaybackService.startPlayback` with an
**audio** device profile built from what the phone declares. It returns the existing `StreamTicket`:
`direct_play` for MP3/FLAC/Ogg/Opus/AAC/ALAC/WAV, or an `hls_url` (AAC 256 kbps stereo) for what the phone
cannot demux (WMA). Progress and stop use the existing endpoints, so Jellyfin's `PlayCount` and
`LastPlayedDate` move. A queue advance is a stop and a start, so every play is one session. **Audio encodes do
not count against 218's session ceiling** (research §7-8 lean — an audio encode is a fraction of a core).

**FR-279-7 — Loudness.** `MusicTrack` carries `track_gain_db` and `album_gain_db` from 275. The phone chooses
(R322 FR-R322-9).

**FR-279-8 — Lyrics.** `GET /music/track/{id}/lyrics` returns `{ synced: [{ t_ms, line }] }` or
`{ plain: "…" }`, or 404 when there are none. The phone's lyrics glyph is absent on 404 — never greyed.

**FR-279-9 — Favourites.** The existing favourite endpoint accepts track and album ids, and My List on the
phone shows them (Jellyfin's `IsFavorite`, as films).

**FR-279-10 — Nothing a viewer reads names a provider or a delivery.** No *MusicBrainz*, *Jellyfin*, codec,
bitrate or *transcode* in any string these endpoints return for display. A viewer never learns that 38 songs
re-encode.

**FR-279-11 — The last-played track** (for R322 FR-R322-3's *Playing tab with nothing playing*):
`GET /music/last-played` returns the viewer's most recent track, its album context and the position left off,
or 204.

## Acceptance

1. A WMA track returns an HLS ticket, an MP3 track returns direct play, and both report progress to Jellyfin.
2. `home` for the household library has no Mix row. A synthetic 400-track library has one.
3. A viewer without the music grant gets empty responses on every path.
4. `WireCompatTest` passes: no existing DTO changed.
5. An artist with a music video returns `videos[]` whose id plays in the video player.

## Open questions

1. The Mix threshold (FR-279-2). Lean ≥ 300 tracks, tuned on real libraries.
2. Should `last-played` read Jellyfin's `LastPlayedDate` (cross-device) or the phone's own store (per device,
   like the mode)? Lean: Jellyfin — it is the viewer's.

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Ten items; three corrections (2, 3, 5).

1. **An audio device profile is new.** `deviceProfile(capabilities)` (`auth/JellyfinClient.kt:55-100`) emits
   only `Type: "Video"` direct-play profiles and a video HLS transcoding profile → an
   `audioDeviceProfile(capabilities)` sibling: `DirectPlayProfiles` of `Type: "Audio"` from the phone's
   `containers` (`mp3, flac, ogg, oga, opus, m4a, mp4, wav, mka, webm`) with its `audioCodecs`, and one
   `TranscodingProfile {Container ts, Type Audio, AudioCodec aac, Protocol hls, MaxAudioChannels 2}` — the
   shape measured in the research (§2.4: MP3 direct, WMA → `/audio/{id}/master.m3u8`).
   `startPlayback(device, jellyfinId, capabilities, …)` (`tv/PlaybackService.kt:461`) gets a sibling or a
   `mediaType` parameter.
2. **Visibility and favourites are `MediaStore`-bound.** `requireVisible` (`PlaybackService.kt:442-450`)
   resolves the id through `mediaStore` (movies, then episodes) and throws for anything else — a track id
   would be refused; `setFavorite` (`:820-827`) calls it first. **Correction:** the music start and favourite
   paths use 275's `libraryVisible(libraryId, device)` on the `music_track` row (or a `/music/favorite`
   route); Jellyfin's `markFavorite` itself accepts any item id ✓.
3. **218's ceiling is cast-only by construction.** It filters on `DeviceData.kind == "cast"`
   (`PlaybackService.kt:249, 314, 475`), so a phone's audio encode never counted. FR-279-6's last sentence
   is true as written — drop the "lean", it is a fact.
4. **Recently played needs a new query.** `getRecentlyPlayed` hardcodes `IncludeItemTypes=Movie,Episode`
   (`JellyfinClient.kt:747`) → `getRecentlyPlayedAudio(userId)` with `IncludeItemTypes=Audio&SortBy=DatePlayed`,
   the per-user token via `tvToken` as today.
5. **`last-played` cannot return "the position left off" from Jellyfin.** Jellyfin keeps **no** position for
   `Audio` (`PlaybackPositionTicks` is 0 on every track — research §1.1). **Correction:** the server answers
   *which* track (from `LastPlayedDate`) and its album context; *where* lives on the phone with the queue
   (R322's per-device queue store, which `onPlaybackResumption` needs anyway). Open question 2 is answered
   by that split.
6. **Lyrics:** `GET /Audio/{id}/Lyrics` → `LyricDto{Metadata, Lyrics[{Text, Start, Cues}]}` (12.1 OpenAPI);
   `Start` is in ticks → `t_ms = Start / 10_000`. New `JellyfinClient.getLyrics`.
7. **DTOs and R319.** The new DTOs are additive; add them to `WIRE_ROOTS`
   (`shared/src/linuxX64Test/…/wire/WireRoots.kt:6`) so later changes are guarded, and re-record the baseline
   at the next release (`scripts/record-wire-baseline.sh`).
8. **`TvApiClient`** gains `getMusicHome`, `browseMusic`, `getMusicAlbum`, `getMusicArtist`, `searchMusic`,
   `playMusic`, `getLyrics`, `lastPlayed`; the platform header is unchanged.
9. **The Mix threshold:** InstantMix on the 60-track library returns the whole library (measured) — ≥ 300
   tracks as the lean, tuned later; the server decides ✓.
10. **Wire:** no existing DTO changes ✓.
