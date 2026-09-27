# Music: a new media type in jellystructure, and a music player mode in Ravilo on the phone — investigation

**Date:** 2026-09-27
**Scope:** what it takes for jellystructure to manage the household's Jellyfin **music** library (artists ·
albums · tracks) the way it manages films and series — metadata from a second provider instead of TMDB,
artwork, NFOs, triage — and for **Ravilo on the phone** to gain a **music player mode**: switched to from the
Profile page, with its own bottom bar, playing in the background, switched back from the same place.
**Trigger:** the owner's ask, 2026-09-27: *"support for music … a whole new media type … coming from our music
library in Jellyfin … maintain information about this music, just like … movies and series via TMDB, but for
music we need another provider, probably MusicBrainz … under the profile tab in Ravilo mobile one could click
'switch to music player' and back again … new options within the bottom navbar. So it's kinda a new app."*
**Status:** research only — no code, no spec, no design change. Next step: the two design briefs written with
this report (`specs/design-brief-music-in-the-admin-2026-09-27.md`, admin surfaces;
`specs/ravilo/design-brief-music-player-on-the-phone-2026-09-27.md`, the phone) go to the design project, its
mockups come back, specs are written from the mockups and dev-reviewed, and only then is anything built.

**Part 2 (audiobooks):** `audiobooks-library-and-player-2026-09-27.md` — written the same day at the owner's
request; it reuses this report's storage model, provider client, service and phone mode, and amends the
bar question (the fourth item becomes *Audiobooks*).

Every number in §1–§3 was **measured on 2026-09-27** against the household server (Jellyfin **12.1.0**) with
read-only calls, or read from the checkout at `c2a7a8ca`. Nothing here names a title, an artist or an album
from the library (the repo is public); files are described by their properties.

---

## 0. The findings that shape everything else

1. **The household already has a real music library on Jellyfin.** A `music`-type library (Jellyfin name
   *Musik*, `/media/music`, on the same `/mnt/media` volume as the films) was created on **2026-09-23**:
   **60 tracks · 30 albums · 23 artists** (17 album artists), 18 music genres. Someone has already played four
   tracks through a Jellyfin client. It is **not in jellystructure's `config.toml`** — the mapping there has
   6 of Jellyfin's 8 libraries (`music` and `books` are missing), and the music-**video** library it does map
   is named *Musik* in the config while Jellyfin now calls it *Musik Videoer*. The two libraries are different
   things and must not be confused in copy: today the phone's Library dropdown labels music **videos** as
   *Music* (`lib.type.music`), and the admin's Library page as *Music videos*.
2. **The library's files are old rips: 38 of 60 tracks are WMA** (`asf` container, `wmav2` 128 kbps), the
   other 22 are MP3 (128–320 kbps). No track, album or artist carries a MusicBrainz id; 0 tracks / 1 album /
   2 artists have any image; 21 tracks and 15 albums carry a genre; no lyrics anywhere; **no disc numbers**
   (`ParentIndexNumber` is null on every track); several albums are partial (one has tracks 2 and 18 only).
   Matching this library is the hard case, not the easy one — text search on messy 2004-era tags, with
   fingerprinting (AcoustID) as the tie-breaker.
3. **Jellyfin can stream every track to the phone today, but the WMA ones only by re-encoding.** Measured:
   `PlaybackInfo` with an audio-only device profile answers an MP3 with `SupportsDirectPlay: true` and a WMA
   with direct play **and** direct stream false → `TranscodingUrl /audio/{id}/master.m3u8` (HLS, AAC, `ts`
   segments); `/Audio/{id}/universal` returns an HLS master with one `mp4a.40.2` rendition for the WMA and
   206 `audio/mpeg` bytes for the MP3. On the phone **Media3 1.8.0 has no ASF extractor** (its extractor
   list: MP4, Matroska, MP3, Ogg, WAV, FLAC, ADTS, TS/PS, FLV, AVI, AMR…), so even though the bundled
   jellyfin FFmpeg decoder **does** contain `wmav1/wmav2/wmapro/wmalossless`, a WMA file cannot be demuxed
   client-side. 38 of 60 tracks will always be Jellyfin transcodes on Android — cheap ones (audio-only), but
   each is a Jellyfin play session and none is gapless.
4. **MusicBrainz is the right provider, and its shape is not TMDB's.** Artist → release-group (*the album*
   as a work) → release (one pressing) → recording (one performance) → track (a position on a release).
   Free, no key, but **1 request/second per IP with a mandatory identifying `User-Agent`**, 503 on violation.
   Covers come from the **Cover Art Archive** (no rate limit, `release-group/{mbid}/front-500` → 307 to
   archive.org). **MusicBrainz has no artist images** — those need fanart.tv (free project key), Wikimedia
   Commons via the artist's `image`/`wikidata` URL relationships (free, attribution), or TheAudioDB (MBID
   lookups are on its **$8/month** tier; the free key `123` returns one result per call at 30 rpm). Genres
   are community votes on MusicBrainz (2 205 genres in its list) — a different id space from 271's TMDB
   genre ids. Lyrics: **LRCLIB** (no key, `Lrclib-Client` header; measured: returns plain and synced LRC).
5. **jellystructure already has most of the machinery.** A paced provider client with AIMD (183's
   `TmdbRateLimiter` — MusicBrainz's 1/s fits it exactly), `fpcalc` in the runtime image (150's Chromaprint
   step; AcoustID wants the *compressed* fingerprint, one flag away from today's `-raw`), a pipeline with
   pluggable steps and per-item locks (174's *Clear match* + lock is the exact shape a music match needs),
   an NFO writer, an artwork downloader with per-asset locks (133/151), a per-library ACL (`visibleTo`,
   fail-closed on `libraryId`), and a Jellyfin client on 12.1's header auth. What it lacks is a **data model
   for a three-level hierarchy** — `media` is one row per title with episodes inside the JSON — and a second
   provider seam. Recommendation (§4): **new tables, not a fourth `MediaKind`**; 168's blast-radius list is
   the argument.
6. **Jellyfin will read what we write.** The music library already has `LocalMetadataReaderOrder: ["Nfo"]`
   and **every online fetcher switched off** (`MetadataFetchers: []` for MusicArtist/MusicAlbum/Audio) — the
   posture the constitution wants. Jellyfin's `AlbumNfoProvider` reads `album.nfo` in the album folder and
   `ArtistNfoProvider` reads `artist.nfo`; provider ids come in as `<musicbrainzalbumid>`,
   `<musicbrainzreleasegroupid>`, `<musicbrainzartistid>`, `<musicbrainzalbumartistid>` (the same generic
   `<…id>` path `<tmdbid>` takes). **One conflict to fix first:** the library's `MetadataSavers` is `["Nfo"]`
   — Jellyfin's own NFO *saver* is on, so it would overwrite our files. 242's metadata-ownership advisor
   finding applies to this library too.
7. **Ravilo's phone is ready for a second bar but not for background audio.** R267's bottom bar iterates a
   fixed enum of five (`BottomNavItem`), R304's Profile page exists and already holds the account rows, the
   phone player (R244), the handset sheet, the cast mini bar (R245, docks above the bar) and the three skins
   are all there. But **R292 deliberately kills the video engine on `ON_STOP`**, the OS `MediaSession` is
   created **TV-only** (R193), there is **no `Service`, no `Application` class, and no foreground-service
   permission** in the manifest. Music needs a **`MediaSessionService`** owning its own `ExoPlayer` (Media3
   `session` is already a dependency), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
   `foregroundServiceType="mediaPlayback"`, and a `MediaController` in Compose. The music player and the
   video player are **two engines** that must never both hold audio focus.
8. **Per-user state can ride Jellyfin, as My List does.** Favourites are Jellyfin `IsFavorite` brokered
   through jellystructure (`setFavorite`), and Jellyfin keeps `PlayCount`/`LastPlayedDate`/`IsFavorite` per
   track (measured — but `PlaybackPositionTicks` stays 0: **no resume position for music**). Jellyfin also
   has a full **Playlists API** and **InstantMix**/**Similar** (the ListenBrainz similarity plugin is
   installed and active). None of this needs a jellystructure table; a queue is client state.
9. **The web app is the second target, not the first.** `ravilo-web` already wires the Media Session API
   around a `<video>` element; music would be an `<audio>` element behind the same seam. iOS fixed background
   audio for standalone web apps in **17.5** (WebKit), but a documented lock-screen bug remains (audio paused
   for >30 s stops responding until the app is foregrounded). Verify on the owner's iPhone before promising.
10. **The Jellyfin server's own `/api-docs/openapi.json` answers 500** (*Error processing request*) — this
    report used the published `jellyfin-openapi-stable.json` (12.1.0, 294 paths) instead. Worth a line in
    `specs/jellyfin-upgrade-checklist.md`; a *Malfunctioned* third-party plugin is installed and may be why.

---

## 1. What exists today

### 1.1 The music library on the household server (Jellyfin 12.1.0)

| Fact | Measured |
|---|---|
| Virtual folders | 8: `movies`, `tvshows`, `musicvideos` (*Musik Videoer*), `music` (*Musik*), `homevideos`, `boxsets`, `books`, Live TV recordings |
| `music` library | `/media/music` in Jellyfin = `/mnt/media/jellyfin/music` on the host (`docker inspect`); type options MusicArtist · MusicAlbum · Audio · MusicVideo; created 2026-09-23 (`DateCreated` on every track) |
| Counts | `Audio` 60 · `MusicAlbum` 30 · `MusicArtist` 23 (`/Artists` 22, `/Artists/AlbumArtists` 17) · `Playlist` 0 · `/MusicGenres` 18 |
| Files | 38 × `asf`/`wmav2` 128 kbps · 22 × `mp3` 128–320 kbps; every track at folder depth artist/album/file |
| Ids and art | `ProviderIds` empty on all 60 tracks and 30 albums, present on 3 artists; `ImageTags` on 0 tracks, 1 album, 2 artists; `Overview` on 2 artists |
| Tags | genres on 21 tracks / 15 albums; `IndexNumber` set, `ParentIndexNumber` (disc) null everywhere; `ChildCount` came back 0 for every album, so track counts must be counted from the tracks; at least one album holds only tracks 2 and 18 |
| Lyrics | `HasLyrics: false` on every track; `GET /Audio/{id}/Lyrics` → 404; `GET /Providers/Lyrics` → empty (no lyric provider installed) |
| Loudness | `EnableLUFSScan: true`; every track carries `NormalizationGain` (−8.7 … −10.5 dB on the sample) and every album `AlbumNormalizationGain` |
| Per-user state | `UserData` per track: `PlayCount`, `Played`, `LastPlayedDate`, `IsFavorite`, `PlaybackPositionTicks` (always 0); 4 tracks already `IsPlayed` for one user |
| Library options | `LocalMetadataReaderOrder: ["Nfo"]` · **`MetadataSavers: ["Nfo"]`** (conflict, §0 #6) · `SaveLocalMetadata: false` · `SaveLyricsWithMedia: true` · `TypeOptions[*].MetadataFetchers: []` and `ImageFetchers: []` (all fetchers off; the *order* lists show the defaults MusicBrainz → TheAudioDB) · `SimilarItemProviderOrder: [ListenBrainz, Local Genre/Tag]` |
| Plugins | MusicBrainz 12.1.0.0 (config: `Server https://musicbrainz.org`, `RateLimit 1`), AudioDB, ListenBrainz Similarity Provider, TMDb, OMDb, Studio Images, Webhook — all *Active*; one third-party plugin *Malfunctioned* |
| Mix / similar | `GET /Items/{trackId}/InstantMix` → 60 items (the whole library — too small to mix); `/Items/{id}/Similar` → 3 |

### 1.2 What jellystructure knows about music

- **Nothing about audio.** `MediaKind` is `{ MOVIE, TV_SHOW, MUSIC_VIDEO }` (backend) / `{ MOVIE, SERIES,
  MUSIC_VIDEO }` (wire). Every Jellyfin query is hard-coded `IncludeItemTypes=Movie,Series,MusicVideo` (or
  `Movie,Episode`), `Scanner.scanItem` dispatches on `"Movie" | "Series" | "MusicVideo"` and answers
  `unsupported-type` for anything else, so an `Audio` item is silently skipped today — 168's report showed
  the same three-layer exclusion for music videos.
- **The music library is not mapped.** `config.toml` has six `[[libraries]]`; `scanItem` only considers
  `!skip && localPath.isNotBlank()` mappings and matches by path prefix, so even with `Audio` in the query
  every track would log *No matching library*. Settings → Libraries' *Refresh from Jellyfin* is where the two
  missing libraries appear.
- **Storage is one row per title.** `media(id, json, kind, title, year, studio, network, …)` with the whole
  `MediaItem` (episodes, tracks, people) in `json`. 168 put a music video in as a `MediaItem` because it *is*
  a single video file; an album is not.
- **Provider plumbing is TMDB-shaped but reusable:** `TmdbClient` (183's AIMD token bucket: 4/s seed,
  0.5–20/s, burst 10, `TmdbPacingStats` for Activity's *Outbound pacing* card), `PipelineStepOps.pullTmdb`,
  174's `tmdbMatchLocked` + *Clear TMDB match*, 184's per-title `metadataLanguage`, 133/151's
  `lockedArtwork`, `ArtworkDownloader`, `NfoWriter` (atomic `.tmp` + rename), 175's one ingest engine and
  `effectivePipeline` (default steps `scan_files · pull_tmdb · fetch_artwork · file checks · subtitles ·
  recommendations`), 213's three job lanes, 182's INTERACTIVE/BACKGROUND gates.
- **Fingerprinting is in the image:** `libchromaprint-tools` (fpcalc) since 167; `FfmpegRunner.computeFingerprint`
  runs `fpcalc -raw -length 120` for 150/159's intro/credits matching. AcoustID wants the **compressed**
  fingerprint (plain `fpcalc`, or `-json`) plus the duration — a second, smaller helper.
- **ACL:** `MediaItem.visibleTo(allowed)` — `libraryId ∈ EnabledFolders`, fail-closed when `libraryId` is
  null. A restricted Jellyfin user who is not granted the music library must see no music at all, including
  the mode switch.
- **The webhook counter and `pull_tmdb`'s missing-scope filter** are gated by *kind* since 168 (FR-168-5);
  music must not re-enter them.
- **Health:** `/api/health` reports libraries, the two gates and the job queues; music adds counts.

### 1.3 What Ravilo knows

- **Phone shell.** `RaviloApp.kt` holds a `Dest` stack (Home, Browse, SeededBrowse, Search, Discover,
  MovieDetail, SeriesDetail, Player, Settings, **Profile**, AppLanguage, YourProfile, ChangePassword, LiveTv,
  CastRemote…). `RaviloBottomNav(selected, onSelect, userInitials)` draws `BottomNavItem.entries` — a fixed
  `HOME · LIBRARY · SEARCH · DISCOVER · PROFILE`; the pill slides on the selected index (R267/R274: 74 dp bar,
  36 dp pill, 11.5 sp labels); `bottomBarShows(dest)` hides it on the player, the remote, a title's detail,
  Login and the profile picker (R278). `AppBar(handsetTopSlot)` draws brand · the page's own control · cast.
- **Profile is a page (R304):** photo + name + *Admin*, **My List** row, Account (App language · Change
  password · Settings), **Sign out** behind a `HandsetSheet`, the `Ravilo {version} · signed in to {host}`
  line. `openProfile()` sits behind nine avatar sites.
- **Playback.** `RaviloPlayer` (expect/actual) is a *video* seam: `load(streamUrl, …, title, subtitle,
  artworkUrl)`, `releaseEngine()` (R292). The Android actual builds `ExoPlayer` with `setAudioAttributes`,
  routes audio through the GPL-contained FFmpeg renderers (`RaviloRenderers`, audio only), and creates a
  `MediaSession` **only when `RaviloAppContext.isTelevision`** (R192/R193 scoped it to the TV on purpose).
  **R292 FR-R292-1: the engine does not survive `ON_STOP`** — exactly what a music player must not do.
- **Manifest:** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`; two activities (leanback + phone);
  the Cast options provider and `MediaTransferReceiver`; **no `<service>`, no `Application` class, no
  `FOREGROUND_SERVICE*`, no `POST_NOTIFICATIONS`, no `WAKE_LOCK`.** `targetSdk 36`, `compileSdk 36`.
- **Dependencies already present:** `media3-exoplayer`, `media3-exoplayer-hls`, **`media3-session`** (R44),
  `media3-ui`, `play-services-cast-framework`, `org.jellyfin.media3:media3-ffmpeg-decoder 1.8.0+1`.
- **Web (`ravilo-web`):** `RaviloPlayerWasm` owns a `<video>` element, lazy-loads hls.js, and already calls
  `wireMediaSession(video)` (title/artwork/handlers for the browser's media controls). PWA manifest:
  `display: standalone`, icons, `#0d0d1a`.
- **Cast receiver (`ravilo-cast`):** loads `t.hlsUrl` as `application/x-mpegURL` — CAF plays audio HLS too,
  so music casting is a later, small phase.
- **Strings:** 596 keys in `i18n/{en,da,fo}.json` (R279); `lib.type.music` = *Music* is the phone Library
  dropdown's label for music **videos**; `browse.type.musicvideo` = *Music video*.
- **Wire discipline:** R319's `WireCompatTest` checks every `shared/` change against installed releases
  from v1.23; every music DTO is **new** (new paths, new types), so nothing existing changes shape.

---

## 2. Jellyfin as the source — what it gives us (12.1.0, measured)

### 2.1 Items and fields

`GET /Items?IncludeItemTypes=Audio|MusicAlbum|MusicArtist&Recursive=true&Fields=…` plus `GET /Artists` and
`GET /Artists/AlbumArtists`. Useful `BaseItemDto` fields: `Artists`, `ArtistItems{Name,Id}`, `AlbumArtist`,
`AlbumArtists`, `Album`, `AlbumId`, `AlbumPrimaryImageTag`, `IndexNumber` (track), `ParentIndexNumber` (disc),
`ProductionYear`, `PremiereDate`, `RunTimeTicks`, `Container`, `Genres`/`GenreItems`, `ProviderIds`,
`MediaStreams[]{Codec, BitRate, Channels, SampleRate, BitDepth}`, `HasLyrics`, `NormalizationGain`,
`AlbumNormalizationGain`, `ImageTags`, `DateCreated`, `UserData{PlayCount, Played, LastPlayedDate,
IsFavorite}`. An album's tracks: `GET /Items?ParentId={albumId}&SortBy=ParentIndexNumber,IndexNumber`.

### 2.2 Provider ids Jellyfin understands (`MetadataProvider`)

`MusicBrainzAlbum` (8, a *release*), `MusicBrainzAlbumArtist` (9), `MusicBrainzArtist` (10),
`MusicBrainzReleaseGroup` (11), `AudioDbArtist` (16), `AudioDbAlbum` (17), `MusicBrainzTrack` (18, a *track*
on a release), `MusicBrainzRecording` (20). Jellyfin's tag prober (ATL) fills them from embedded tags
`MUSICBRAINZ_ARTISTID`, `MUSICBRAINZ_ALBUMARTISTID`, `MUSICBRAINZ_ALBUMID`, `MUSICBRAINZ_RELEASEGROUPID`,
`MUSICBRAINZ_RELEASETRACKID`, `MUSICBRAINZ_TRACKID`/`UFID` (first value only), and reads
`REPLAYGAIN_TRACK_GAIN`/`REPLAYGAIN_ALBUM_GAIN` and embedded lyrics (LRC preferred over plain). A Picard-tagged
library would arrive with ids; this one has none.

### 2.3 NFO: what Jellyfin reads and writes for music

- Readers: `AlbumNfoProvider` → `{album folder}/album.nfo`; `ArtistNfoProvider` → `{artist folder}/artist.nfo`.
  `BaseNfoParser` handles `sortname`, `biography`, `review` (→ overview), `year`, `formed`, `releasedate`,
  `genre`, `style`, `thumb`, `lockdata`, `uniqueid`, and provider ids by element name (the known list:
  `musicbrainzartistid`, `musicbrainzalbumartistid`, `musicbrainzalbumid`, `musicbrainzreleasegroupid`,
  `audiodbartistid`, `audiodbalbumid`).
- Jellyfin's own savers (to be switched **off** on this library): `AlbumNfoSaver` writes `<artist>`,
  `<albumartist>`, `<track><disc><position><title><duration>`; `ArtistNfoSaver` writes `<disbanded>`,
  `<title>`, `<year>` on top of the base fields.
- Kodi's fuller schema (what our writer should emit, so Kodi users keep everything): **artist.nfo** —
  `name`, `musicBrainzArtistID`, `sortname`, `type`, `gender`, `disambiguation`, `genre`, `style`, `mood`,
  `yearsactive`, `instruments`, `born`, `formed`, `biography`, `died`, `disbanded`, `thumb`, `fanart`, and an
  `<album>` discography; **album.nfo** — `title`, `musicbrainzalbumid`, `musicbrainzreleasegroupid`,
  `artistdesc`, `genre`, `style`, `mood`, `theme`, `compilation`, `boxset`, `review`, `type`/`releasetype`,
  `releasestatus`, `releasedate`, `originalreleasedate`, `label`, `rating`, `userrating`, `votes`, `thumb`,
  `albumArtistCredits{artist, musicBrainzArtistID}`, `track{position, title, duration, musicBrainzTrackID}`.
- After a write, the same `POST /Items/{id}/Refresh` the film path uses (the `sync_jellyfin` step).

### 2.4 Streaming (the data plane)

- **Negotiation:** `POST /Items/{id}/PlaybackInfo` with a `DeviceProfile` whose `DirectPlayProfiles` are
  `Type: "Audio"` (measured with `mp3`, `flac`, `m4a/mp4 aac,alac`, `ogg/oga/opus vorbis,opus`, `wav`) and one
  `TranscodingProfile {Container ts, Type Audio, AudioCodec aac, Protocol hls}` plus an `http`/`mp3` fallback.
  MP3 → `SupportsDirectPlay true`; WMA → transcode with `TranscodingUrl
  /audio/{id}/master.m3u8?…AudioCodec=aac&AudioBitrate=128000&SegmentContainer=ts&MinSegments=1&PlaySessionId=…`
  and `TranscodingSubProtocol hls`. `PlaySessionId` is present on both, so 180's teardown applies unchanged.
- **The shortcut:** `GET /Audio/{itemId}/universal` (params: `container`, `audioCodec`, `maxAudioChannels`,
  `transcodingAudioChannels`, `maxStreamingBitrate`, `audioBitRate`, `startTimeTicks`, `transcodingContainer`,
  `transcodingProtocol`, `maxAudioSampleRate`, `maxAudioBitDepth`, `enableRemoteMedia`,
  `enableAudioVbrEncoding`, `enableRedirection`, `deviceId`, `userId`, `mediaSourceId`). Measured: WMA → 200
  `application/vnd.apple.mpegurl`, one rendition `BANDWIDTH=256000 CODECS="mp4a.40.2"`; MP3 with
  `enableRedirection=false` → 206 `audio/mpeg`. It bypasses the ticket/teardown discipline — **the PlaybackInfo
  path is the one to use**, as for video (constitution: one brain).
- **Direct bytes:** `GET /Audio/{id}/stream?static=true` → 206 `audio/mpeg` (range requests work — what a
  direct-play `StreamTicket` points at).
- **Progress:** the existing `PlaybackService.started/heartbeat/stopped` write `PlayCount`/`LastPlayedDate`;
  Jellyfin keeps no resume position for `Audio` (audiobooks only), so "continue listening" is a client-side
  queue concern, not a server one.

### 2.5 The rest of the music surface

`/Items/{id}|/Albums/{id}|/Artists/{id}|/Songs/{id}|/MusicGenres/{name}|/Playlists/{id}/InstantMix`;
`/Items|/Albums|/Artists/{id}/Similar` (the ListenBrainz plugin answers for artists); **Playlists**: `POST
/Playlists`, `GET|POST /Playlists/{id}`, `GET|POST|DELETE /Playlists/{id}/Items`,
`/Items/{itemId}/Move/{newIndex}`, `/Playlists/{id}/Users[/{userId}]` (sharing); **Lyrics**: `GET|POST|DELETE
/Audio/{id}/Lyrics` (`LyricDto{Metadata{Artist, Album, Title, Author, Length, By, Offset, Creator, Version,
IsSynced}, Lyrics[{Text, Start, Cues}]}`), `GET /Audio/{id}/RemoteSearch/Lyrics`, `/Providers/Lyrics/{id}`;
`/Items/{id}/ThemeSongs`. Auth on 12.1 is the `Authorization: MediaBrowser … Token=` header (`api_key=` is
dead — see the 12.1 reference note), which `JellyfinClient` already does.

---

## 3. The second provider: MusicBrainz, and what it cannot do alone

### 3.1 The model (why an album is a release-group)

| MusicBrainz | What it is | Jellyfin id key | Our use |
|---|---|---|---|
| **artist** | a person or group; `sort-name`, `type`, `country`/`area`, `life-span`, `disambiguation`, aliases, genres/tags with vote counts, URL relationships | `MusicBrainzArtist` / `MusicBrainzAlbumArtist` | the artist page; the link to images (fanart.tv, Commons) |
| **release-group** | *the album as a work*: one id for every pressing; `primary-type` Album/Single/EP/Broadcast/Other, `secondary-types` Compilation/Live/Soundtrack/Remix…, `first-release-date` | `MusicBrainzReleaseGroup` | the album's identity, cover (CAA `release-group/{id}/front`), type badges |
| **release** | one pressing: date, country, label, barcode, `media[]{format, track-count, tracks[]}` | `MusicBrainzAlbum` | tracklist to match the folder against; disc numbers |
| **recording** | one performance; `length`, ISRCs | `MusicBrainzRecording` | the track's identity across releases; AcoustID answers with recordings |
| **track** | a recording at a position on a release | `MusicBrainzTrack` | `<musicBrainzTrackID>` in album.nfo |

### 3.2 API facts (measured against `musicbrainz.org/ws/2`, `fmt=json`)

- **Rate:** ~1 request/s per IP, **503** when exceeded; a `User-Agent` of the form
  `jellystructure/<version> ( <contact url or email> )` is required (anonymous agents are throttled hard).
  183's `TmdbRateLimiter` seeded at 1/s with burst 1 is this exactly; the *Outbound pacing* card gains a row.
- **Lookup** `/artist/{mbid}?inc=genres+tags+url-rels+aliases` returned `name`, `sort-name`, `type`,
  `country`, `area`, `begin-area`, `life-span`, `disambiguation`, `genres[]{name,count}`, `tags`, `aliases`,
  `isnis`/`ipis`, and `relations[]` whose `type`s included `wikidata`, `image` (a Wikimedia Commons file),
  `official homepage`, `discogs`, `last.fm`, `lyrics`, `allmusic`, `bandcamp`, `youtube`, `streaming`.
- **Browse** `/release-group?artist={mbid}&type=album&limit=100&offset=…` → `release-group-count` plus
  `title`, `first-release-date`, `primary-type`, `secondary-types` (max 100 per page).
- **Search** is Lucene: `/recording?query=artist:"…" AND recording:"…" AND dur:[290000 TO 310000]` scored
  the right recording 100 with its releases and release-group ids; `/release-group?query=artist:"…" AND
  releasegroup:"…"`; `/artist?query=artist:"…"`. **Tracklists:** a release lookup
  `/release/{mbid}?inc=recordings+media` (`media[].tracks[]{position, title, length, recording}`).
- **Genres:** `/genre/all?fmt=txt` → 2 205 names; per-entity `inc=genres` gives votes. Jellyfin's *MusicGenre*
  is a free string, so the genre we write into `album.nfo` becomes the genre Jellyfin shows.
- **Other id paths:** `/isrc/{isrc}`, `/discid/{discid}`, `/url?resource=…`.

### 3.3 Matching this library — a ladder, not a lookup

1. **Ids already there** — embedded `MUSICBRAINZ_*` tags surface as Jellyfin `ProviderIds` (none here; a
   Picard-tagged library is done at this rung).
2. **Album text search** — album artist + album title (+ year) against `release-group`; score the candidates
   by MusicBrainz's own score, the year, and **track agreement**: for each candidate pick the release whose
   tracklist best covers the folder's `(position, title, duration)` set. A partial rip (tracks 2 and 18) still
   agrees on two positions and two durations.
3. **Recording search per track** — title + artist + `dur:` window when an album match is weak or the folder
   is a mixed compilation.
4. **AcoustID** — `POST https://api.acoustid.org/v2/lookup` with `client` (free key, non-commercial),
   `duration`, `fingerprint` (fpcalc's **compressed** output — today's helper asks for `-raw`, so a second
   small helper), `meta=recordings+releasegroups`; ≤ 3 requests/s; answers recordings with a score. Decisive
   for mis-titled or untitled files, and for telling a live version from the studio one.
5. **The admin decides the rest** — a match picker with candidates and their scores, then a **lock** (174's
   shape: an explicit *Clear match* that stops any run from re-attempting it).

Every rung is paced, cached (an album's release-group and release JSON kept, so a re-run costs nothing), and
never blanks a good match on a failed lookup (131/183's rule).

### 3.4 Artwork

| Need | Source | Terms | Notes |
|---|---|---|---|
| Album cover | **Cover Art Archive** `coverartarchive.org/release-group/{mbid}/front-500` (also `-250`, `-1200`, `/release/{mbid}/front`), JSON index at `/release-group/{mbid}/` (`images[]{types, front, approved, thumbnails{250,500,1200}}`) | free, **no rate limit**, 307 → archive.org | measured; the release the art came from is in the index |
| Artist thumb / background / logo | **fanart.tv** `webservice.fanart.tv/v3/music/{artist-mbid}?api_key=…` → `artistthumb`, `artistbackground`, `hdmusiclogo`/`musiclogo`, `musicbanner`, `albums{release-group}{albumcover, cdart}` | free project API key (registration); optional personal `client_key` | *verify the response shape on first use* — v3.2 moved `albums` to an array |
| Artist image, free | **Wikimedia Commons** via the MusicBrainz artist's `image` URL relationship (measured present) or `wikidata` → P18 | free, attribution per file licence | fewer hits than fanart.tv, no key |
| Everything, paid | **TheAudioDB** `theaudiodb.com/api/v1/json/{key}/artist-mb.php?i=…`, `album-mb.php?i=…` | MBID lookups need the **$8/month** key (100 rpm); the free key `123` returns one result at 30 rpm and no MBID lookups (measured: the old test key `2` → 404) | Jellyfin's own AudioDB plugin runs on Jellyfin's key, which does not transfer |
| Not recommended | Discogs (60 rpm authenticated, images need auth), Last.fm (artist images removed), Spotify/Deezer (terms forbid caching) | | |

No cover ⇒ the same rule as R243's walls: a tile sets the title as a wordmark on a generated gradient; never a
placeholder image, never a "no logo" badge on the viewer side (on the admin side a missing cover *is* a work item).

### 3.5 Genres, lyrics, similarity, scrobbling

- **Genres:** MusicBrainz votes → the top *n* above a threshold become the album's/artist's genres. They are a
  different id space from 271's TMDB ids (`GenreCatalog` keys TMDB ids with labels per language); MusicBrainz
  genre names are English and have their own MBIDs. Decision: a separate `music_genre` list, or `GenreCatalog`
  gains a namespace. Lean: separate — a film's *Comedy* and an album's *comedy rock* should not share a chip.
- **Lyrics:** **LRCLIB** `GET https://lrclib.net/api/get?artist_name&track_name&album_name&duration` (exact) or
  `/api/search?track_name&artist_name` (measured: 200, fields `id`, `trackName`, `artistName`, `albumName`,
  `duration`, `instrumental`, `plainLyrics`, `syncedLyrics` as LRC `[mm:ss.xx]`), no key, an identifying
  `Lrclib-Client` header. Delivery: write a `.lrc` beside the track (Jellyfin's `SaveLyricsWithMedia` is on and
  its resolver picks sidecars up) **or** `POST /Audio/{id}/Lyrics`. Lean: the sidecar — it is a file jellystructure
  owns, like an NFO. Ravilo reads `GET /Audio/{id}/Lyrics` through a jellystructure route.
- **Similar / mixes:** Jellyfin's InstantMix and the ListenBrainz similarity plugin exist, but a 60-track
  library mixes into itself. Draw *Mix* rows, gate them on library size.
- **ListenBrainz scrobbling** (optional, later): `POST /1/submit-listens` with a per-user token, the
  half-track-or-4-minutes rule; `lb-radio` for recommendations. A per-viewer token is a new setting.

### 3.6 Alternatives to MusicBrainz, considered

Discogs (physical-release-centric, images behind auth, 60 rpm), TheAudioDB alone (paid for the lookups that
matter, thin coverage of a Nordic library), Spotify/Deezer (rich, but their terms forbid storing the metadata
and images we write to disk), Last.fm (tags and play counts, no canonical ids). **MusicBrainz + CAA is the only
free, open, id-stable spine; the others are image or enrichment sources hung off its ids.** A self-hosted
MusicBrainz mirror removes the 1/s limit but is a heavy service for a household library — not now.

---

## 4. Where music lives in jellystructure

### 4.1 Model: new tables, not a fourth `MediaKind`

168 documented what a new `MediaKind` costs: nine exhaustive `when`s that fail to compile (good) and ~25
binary `if`s that silently treat the new kind as a movie (bad). An album has **three levels** and
**many-to-many artist credits**; jamming it into `MediaItem` (album as the item, tracks as `episodes`, artist
as `director`) would put *Seasons & episodes*, `tvshow.nfo` logic and Continue Watching semantics one `else`
away from every album. Recommendation:

- `music_artist(id = jellyfinId, json, name, sort_name, mbid, library_id, image_state, updated_at, …)`
- `music_album(id = jellyfinId, json, title, year, album_artist_ids, release_group_mbid, release_mbid,
  match_locked, cover_state, library_id, …)`
- `music_track(id = jellyfinId, album_id, json, position, disc, title, duration_ms, codec, bitrate,
  recording_mbid, has_lyrics, path, …)`
- the same JSON-blob-plus-index-columns pattern as `media`, the same `libraryId` ACL, the same
  `search_text` index feeding `/tv/search` (grouped results: tracks · albums · artists).
- `MediaKind` and `MediaItem` untouched; the wire gains **new** DTOs (`MusicArtistCard`, `MusicAlbumCard`,
  `MusicTrack`, `MusicAlbumDetail`, `MusicArtistDetail`, `MusicHome`) on **new** `/api/tv/music/**` paths —
  nothing an installed app decodes changes (R319 stays green by construction).

### 4.2 Ingest and steps

- **`scan_music`** (inside `scan_files`' run, its own lane): `/Artists`, albums, tracks with `Fields=…`; no
  ffprobe per track in v1 — Jellyfin's `MediaStreams` already carry codec/bitrate/sample rate, and an audio
  file has one stream. `fpcalc` runs **only** when the match ladder reaches rung 4.
- **`match_musicbrainz`** per album (paced 1/s, cached, locked-aware) → release-group + release + recordings;
  per artist → artist lookup with `inc=genres+url-rels+aliases`. Scope `missing` re-attempts only unmatched,
  unlocked albums (168's kind gate keeps them out of `pull_tmdb`).
- **`fetch_music_artwork`**: CAA front → `{album}/cover.jpg` (Jellyfin's first name in its list; `folder.jpg`
  also read); artist → `{artist}/folder.jpg` + `backdrop.jpg` + `logo.png`; per-asset lock as 133/151;
  manual upload as today.
- **`write_music_nfo`**: `album.nfo` / `artist.nfo` in Kodi's schema (§2.3), atomic, foreign-NFO rule as 175.
- **`fetch_lyrics`** (optional step, off by default): LRCLIB → `.lrc` sidecar.
- **`sync_jellyfin`**: refresh the album and artist items; `detect_drift` extended to the two NFO shapes.
- **Embedded tags are not written.** Writing `MUSICBRAINZ_*`/`REPLAYGAIN_*` into the files (Picard's way)
  would make every other player see the ids too, but it edits the media file itself — a class of write this
  product does only on explicit admin action (invariant 1's spirit; 254's "files are fixed by jellystructure,
  never by hand" is about repairs the admin asked for). Offer it, if at all, as a per-album *Write tags…*
  action behind a confirmation — **owner decision** (§7).

### 4.3 Config and health

- `[api_keys]` gains `acoustid_client_key`, `fanart_tv_key` (both optional; absent ⇒ rung 4 and artist images
  are skipped and the Settings card says so). `[musicbrainz]` gains `contact` (the User-Agent's contact —
  required by MusicBrainz) and `rate_per_sec = 1.0`; `enabled` per the usual shape.
- The music library appears as a `[[libraries]]` row (`collection_type = "music"`) via *Refresh from Jellyfin*;
  its card carries *Metadata: MusicBrainz* instead of TMDB's language cascade.
- **Advisor findings** for the library (212/242's shape): *Jellyfin's NFO saver is on* (must be off);
  *online fetchers off* (✓); *LUFS scan on* (✓, the phone uses it); *lyric fetchers: none* (✓ if we own lyrics).
- `/api/health`: `music { artists, albums, tracks, matched, unmatched, covers_missing }`.
- The webhook's `unmatched` counter and `notifyOnNoMatch` stay film/series-only (168's FR-168-5); music gets
  its own `music_unmatched` field if wanted.

### 4.4 Admin surfaces (the design brief covers the visuals)

Library page (a **Music** kind: Albums · Artists · Songs views, filters incl. *unmatched*, *no cover*,
*re-encodes on the phone* for WMA), an **Album** page and an **Artist** page (new mockups; the `media.html`
pagebar/tabs idiom), Metadata → a music genre view, Settings → Libraries (the mapping card) and a **Music
providers** card, Activity (steps, the pacing card's MusicBrainz/AcoustID rows, run summary), Dashboard
counters and attention entries, triage types (*album unmatched*, *cover missing*, *artist image missing*,
*track has no recording match*, *plays only by re-encoding*).

---

## 5. Streaming music to the phone

### 5.1 Ticket flow (reuse)

`POST /api/tv/music/play {track_id, capabilities}` → `PlaybackService.startPlayback` with an **audio**
profile built from the phone's declared containers/codecs → the existing `StreamTicket` (`hls_url` null and
`direct_play true` for MP3/FLAC/…; `hls_url` for WMA) → `progress`/`stop` as today (Jellyfin's `PlayCount`
increments, `LastPlayedDate` moves; 180's teardown releases the encode when the queue advances or the app
dies). The client also sends the **queue advance** as a normal stop + start so every play is one session.

### 5.2 The Android profile

Direct play: `mp3`, `flac`, `ogg/oga/opus` (vorbis, opus), `m4a/mp4` (aac, alac), `wav`, `mka/webm` audio;
**not `asf`/`wma`** (no extractor). Transcode target: HLS/`ts`/AAC 256 kbps stereo (what Jellyfin chose for
the WMA in the probe) — or `mp3`/`http` progressive as a fallback for the web. Because 38 of 60 tracks will
re-encode: (a) they are not gapless (Media3 does gapless only on direct-play MP3/AAC/FLAC/Ogg with the right
headers); (b) each is a Jellyfin transcode session — decide whether audio counts against 218's session
ceiling (lean **no**: an audio encode is a fraction of a core). A one-time **convert WMA → Opus 160 k** repair
job in jellystructure would remove the re-encode forever at some quality cost on already-lossy files; a
FLAC of a lossy source gains nothing. **Owner decision**, drawn as a triage action in the brief.

### 5.3 Loudness

Pass `NormalizationGain` and `AlbumNormalizationGain` through the track DTO; the phone applies one of them as
a volume scale (jellyfin-web does; Jellyfin issue #14346 notes the field is always the *track* gain, so the
album/track choice is the client's). A viewer setting *Even out volume* — on by default.

### 5.4 Web (second) and Chromecast (later)

Web: `<audio>` behind a `RaviloAudioPlayer` seam with the existing `wireMediaSession`; standalone iOS ≥ 17.5
keeps playing in the background, with the documented >30 s-paused lock-screen bug to test on the household
iPhone. Chromecast: the receiver already plays HLS; a music LOAD is the same hand-off with `contentType`
`audio/mpeg`/HLS and a cover instead of a backdrop.

---

## 6. The phone app: a second mode

### 6.1 The switch and the mode

- **Where:** the Profile page (R304) — the owner's own placement. The row/card is **absent** when the viewer's
  Jellyfin policy does not grant the music library (`visibleTo`), when the library is empty, or on a TV
  (R256's `isHandset` seam) — absent, never greyed (R267 FR-R267-4's idiom).
- **What "mode" is:** a **per-device** flag (like `LastLanguage`/`PlaybackPrefsStore`), not a per-viewer
  server setting — a phone is personal, the TV never has it, and a household member switching modes must not
  switch anyone else's phone. The server still composes the *content* of music Home (invariant 4); the mode
  itself is navigation state. Remembered across launches; sign-out clears it.
- **Kids profiles:** MusicBrainz carries no age ratings, Jellyfin no parental rating on `Audio`, so a kids
  profile either sees all of the library the policy grants or none. Lean: **follow the Jellyfin library
  grant only**; note it in the admin's Users & devices copy.

### 6.2 The bar

`BottomNavItem` becomes a parameter (`RaviloBottomNav(items = …)`), two declared lists, **Profile last in
both** — it is the way back. Music mode's four other items are a round-1 design question (brief §B); lean:
**Home · Library · Search · Playlists · Profile** — the same shape as the video bar, so the pill, the reselect
rule (R267 FR-R267-9), R275's Back ladder, the docking rule for the mini bar and the insets all carry over
unchanged. `Dest` gains `MusicHome`, `MusicLibrary(tab)`, `MusicSearch`, `MusicPlaylists`, `AlbumDetail`,
`ArtistDetail`, `NowPlaying`, `Queue`; `bottomItemOf` maps them.

### 6.3 Background playback (the one genuinely new platform piece)

- A **`MediaSessionService`** (`RaviloMusicService`) owns its own `ExoPlayer` + `MediaSession`; Compose gets a
  `MediaController` via `SessionToken`. Manifest: `<service android:foregroundServiceType="mediaPlayback"
  android:exported="true">` with the `androidx.media3.session.MediaSessionService` intent filter,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (13+, prompted),
  `WAKE_LOCK`; Media3 draws the `MediaStyle` notification from `MediaMetadata` (title · artist · cover) and
  drops out of foreground after 10 min paused. `onTaskRemoved` → `pauseAllPlayersAndStopSelf()`. Android 15
  forbids starting it from `BOOT_COMPLETED` (fine — resumption goes through `MediaButtonReceiver` +
  `onPlaybackResumption` restoring the last queue).
- `RaviloAppContext.init(applicationContext)` must be safe from the service (today only activities call it).
- **Two engines, one focus:** the music engine sets `setAudioAttributes(usage MEDIA, content MUSIC,
  handleAudioFocus = true)` and `setHandleAudioBecomingNoisy(true)`; starting the video player takes focus
  and pauses music by the platform's rule — nothing custom. The video engine keeps R292's lifecycle
  untouched; the music engine explicitly does **not** follow it (its whole point is surviving `ON_STOP`),
  which R292's spec should say in one sentence so the two rules are never read as a contradiction.
- Gapless: on direct-play items Media3 handles it; on HLS re-encodes it does not — nothing to draw, one
  note in the spec.
- QoE: the `postPlaybackQoe` shape can carry `media: "audio"`; cheap to add, not required.

### 6.4 Queue, now playing, mini bar

The queue is the Media3 playlist (client state, like the seek position); the *Now playing* screen and a
**mini bar** that docks above the bottom bar exactly as the cast mini bar does (R245 FR-R245-6 / R267
FR-R267-8) — and stays when the viewer switches back to video mode, until a video takes focus. Lyrics:
`GET /api/tv/music/track/{id}/lyrics` → synced lines highlighted on the clock, plain text otherwise.

### 6.5 What the server composes

`GET /api/tv/music/home` — system rows only in v1 (*Recently added albums*, *Recently played*, *Artists*,
*Albums by genre*, and a *Mix* row gated on library size); `GET /api/tv/music/albums|artists|tracks` with
sort and paging; `GET /api/tv/music/album/{id}`, `/artist/{id}`; `GET /api/tv/music/search?q=` (grouped);
`POST /api/tv/music/play`; the existing `progress`/`stop`/`favorite`. Playlists (phase 2) brokered to
Jellyfin's Playlists API — consistent with My List riding `IsFavorite`, visible in Jellyfin's own clients, no
new table. **No admin editor for music rows in v1**; the row set is fixed and localised.

### 6.6 Not in this round

TV (the owner scoped it to the phone; a TV music UI is a different design), the web app (second brief),
casting music, offline/downloads, ListenBrainz scrobbling, playlist *editing* (phase 2), an audiobook mode
(`books` library — different rules, out of scope), tag writing (§7).

### 6.7 Music videos and concert films (owner-confirmed 2026-09-27)

The `music_*` tables are a storage decision, not a decision about what the music player shows. **Audio**
live recordings are albums (MusicBrainz's secondary type *Live* — the brief draws the badge) and belong in
music mode. **Music videos and concert films are video files**: they already live in the household's
music-video library as `MUSIC_VIDEO` items (22 today) and play in the video player; they **stay video-mode
content** — a music player that suddenly needs a picture and R244's chrome is the wrong seam. The link
between the two is the **artist**: 168 already parses `artist – title` from a music video's filename, and a
`music_artist` row carries a name and a MusicBrainz id, so an artist page in music mode can carry a
**Videos** row (16:9 tiles) that opens the *video* player and comes back — cheap precisely because music has
its own tables: a `music_artist` may reference `media` rows of kind `MUSIC_VIDEO` without the two models
merging. Phase 2, only shown when the artist has any; the same row on the admin's Artist page links to the
music video's `media.html`.

---

## 7. Open questions for the owner (with leans)

1. **The WMA files** — keep them and let Jellyfin re-encode every play (lean, zero risk), or a one-time
   jellystructure job that converts them to Opus/AAC (gapless and direct play, small further loss)?
2. **Write MusicBrainz ids into the files' tags** (Picard's way; every player benefits; edits the media file)
   or **NFO + sidecar only** (lean — nothing touches a media file without an explicit action)?
3. **Artist images:** fanart.tv with a free project key (lean), Commons only (fewer hits, no key), or pay
   TheAudioDB?
4. **Lyrics:** on by default (LRCLIB, `.lrc` sidecars; lean) or off until asked?
5. **Music mode is per device** (lean) or a per-viewer setting synced across a viewer's phones?
6. **Kids profiles and music:** library grant only (lean), or hide music mode for `is_kids` viewers?
7. **The bar in music mode:** Home · Library · Search · Playlists · Profile (lean, brief §B), or Albums and
   Artists as their own tabs?
8. **Do audio transcodes count against 218's session ceiling?** Lean no.
9. **Playlists via Jellyfin's API** (lean; shows in every Jellyfin client) or jellystructure-owned?
10. **The web app** — same round as the phone, or after the phone has been used for a while (lean)?

---

## 8. Prospective phases (numbers to verify against `main` when the specs are written)

At the time of writing `main` tops at **273 / R319**, so the next free numbers are **274 / R320**. Suggested
ladder — each a spec written from the mockups, dev-reviewed, then built:

| # | Side | One line |
|---|---|---|
| 274 | admin | **Music is a library jellystructure manages** — `music_*` tables, `scan_music`, the library mapping row, the ACL, health counts, the Jellyfin NFO-saver advisor finding |
| 275 | admin | **MusicBrainz is the second provider** — paced client, the match ladder incl. AcoustID, release-group model, genres, per-album lock/clear, `match_musicbrainz` step, the *Music providers* card |
| 276 | admin | **Covers, artist images, `album.nfo` / `artist.nfo`, lyrics** — CAA, fanart.tv/Commons, the two NFO shapes, `.lrc` sidecars, `sync_jellyfin`/drift for music |
| 277 | admin | **Music in the admin UI** — Library's Music kind, the Album and Artist pages, Metadata's music genres, Activity/Dashboard/triage |
| 278 | admin | **`/api/tv/music/**`** — home, browse, detail, search, play with an audio profile, lyrics, favourites; visibility-scoped |
| R320 | Ravilo | **A music mode on the phone** — the switch on Profile, the second bar, mode persistence, the way back |
| R321 | Ravilo | **The music player** — `MediaSessionService`, notification/lock screen, queue, *Now playing*, the mini bar, the audio device profile, loudness, lyrics |
| R322 | Ravilo | **Music on the web app** — `<audio>` seam, Media Session, the iOS standalone caveats |
| R323 | Ravilo | **Playlists and mixes** — Jellyfin playlists brokered, InstantMix rows gated on library size; casting music |

---

## 9. Not established here — verify before or during the specs

- The **iPhone**: whether the installed web app keeps playing on the lock screen across a long pause (iOS
  version on the household phone; the WebKit bug fixed in 17.5, the forum-reported >30 s regression).
- **fanart.tv's current response shape** (v3 vs v3.2 `albums`) and key issuance time; **AcoustID key**
  issuance (instant on registration, but confirm the non-commercial terms cover a household server).
- Whether **MusicBrainz's text search** finds this library's albums at all before AcoustID — the probe used
  MusicBrainz's own documentation example; the real folders' tag quality decides rung 2's hit rate. A
  dry run of the ladder over the 30 albums is the first thing 275's build should do, before any UI.
- **Jellyfin's handling of an `album.nfo` `<track>` list** — its own saver writes one, its parser reads
  album-level fields; whether `<track>` entries set anything on `Audio` items is unconfirmed (they are for
  Kodi). Test on the demo Jellyfin.
- **HLS AAC bitrate**: PlaybackInfo's URL said `AudioBitrate=128000` while the master advertised 256 000 —
  which one the encode actually uses matters for the phone's data use; measure one segment.
- **`ChildCount = 0` on every album** — a 12.1 field change or a scan artefact; either way, count tracks.
- Whether the 60-track library grows: the design should assume a few hundred albums (2-up grids, paging),
  not 30.

## Method

Read-only: `config.toml` and the production DB for what jellystructure maps and stores; the household
Jellyfin server via `GET`s and two `POST /Items/{id}/PlaybackInfo` negotiations (no state changed, no
playback started, no encode left running — the `universal` probes fetched only a manifest and one byte);
Jellyfin's published OpenAPI (12.1.0) for route shapes; Jellyfin's source for `MetadataProvider`,
`AudioFileProber`, the NFO providers/savers; the checkout for the seams; the Gradle cache for Media3's
extractor list and the FFmpeg decoder's codec table; MusicBrainz, the Cover Art Archive and LRCLIB with an
identifying User-Agent at ≤ 1 request/s; TheAudioDB's free key; the Kodi wiki for the NFO schemas.

## Sources

- MusicBrainz API and rate limiting — https://musicbrainz.org/doc/MusicBrainz_API ·
  https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
- Cover Art Archive API — https://musicbrainz.org/doc/Cover_Art_Archive/API
- AcoustID web service — https://acoustid.org/webservice
- Jellyfin: music libraries — https://jellyfin.org/docs/general/server/media/music/ · OpenAPI (stable) —
  https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json · `MetadataProvider.cs`, `AudioFileProber.cs`,
  `MediaBrowser.XbmcMetadata/{Providers,Savers,Parsers}` on github.com/jellyfin/jellyfin ·
  audio normalisation: jellyfin-web PR #4318, jellyfin issue #14346
- Kodi music NFO — https://kodi.wiki/view/NFO_files/Artists · https://kodi.wiki/view/NFO_files/Albums
- Media3 — background playback https://developer.android.com/media/media3/session/background-playback ·
  supported formats https://developer.android.com/media/media3/exoplayer/supported-formats · foreground
  service types https://developer.android.com/develop/background-work/services/fgs/service-types
- LRCLIB — https://lrclib.net (API probed live) · ListenBrainz API —
  https://listenbrainz.readthedocs.io/en/latest/users/api/core.html
- TheAudioDB tiers — https://www.theaudiodb.com/free_music_api · fanart.tv — https://api.fanart.tv/
- iOS standalone web-app audio — WebKit bug 261858; https://dbushell.com/2023/03/20/ios-pwa-media-session-api/ ;
  Apple developer forums thread 762582
