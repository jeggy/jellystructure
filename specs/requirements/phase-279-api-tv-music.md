# Phase 279 — `/api/tv/music/**`: what the phone reads and plays

## Status

`Planned` — written 2026-09-28 from the research report §2.4, §4.1, §5.1–§5.3 and §6.5, and the phone mockup's
data needs (`design/ravilo/mobile/ravilo-music.js`). **Not dev-reviewed.** Builds on **275–277**,
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
