# Phase 277 — Covers, artist images, `album.nfo` / `artist.nfo`, and lyrics

## Status

`Planned` — written 2026-09-28 from the admin music brief (§B3 Artwork/Genres/NFO, §C2, §E2, §H6, §H7), the
research report §2.3, §3.4, §3.5 and §4.2, and the mockups `design/app/album.js` and `artist.js`.
**Not dev-reviewed.** Builds on **275**, **276**, **133/151** (per-asset locks), **175** (the foreign-NFO rule)
and the drift detector.

## Decisions (round 1 — leans; not yet answered)

| # | Question | Lean |
|---|---|---|
| H6 | Fetch lyrics by default | **On** — `fetch_lyrics` is a run step and the Tracks tab fills in |
| H7 | Write MusicBrainz ids into the audio files | **Not drawn at all.** NFO + sidecars are enough for Jellyfin and Kodi, and nothing touches a media file without being asked. The alternative is a per-album action behind a confirmation |
| — | Artist images | fanart.tv with a free project key, Wikimedia Commons without one (research §7-3) |

## Requirements

**FR-277-1 — `fetch_music_artwork`.** A run step after `match_musicbrainz`:

- **Albums:** the Cover Art Archive's approved **front** for the release-group → `{album}/cover.jpg`.
- **Artists:** fanart.tv `artistthumb` → `{artist}/folder.jpg`, `artistbackground` → `backdrop.jpg` and
  `hdmusiclogo` → `logo.png` (with a key). Without a key, Wikimedia Commons (the licence line travels in
  `artist.nfo`).

It never overwrites a locked asset. **Manual upload** works as it does for films. A matched album with **no**
CAA front is a triage item, not an error (*No cover on the Cover Art Archive for this release-group · Upload…*).

**FR-277-2 — Artwork tabs.** The album's Artwork tab has these groups:

- **Currently in use** — the file name, the size, *lock* and *Clear*, plus an upload tile.
- **Cover Art Archive** — front · back · booklet thumbs, *approved* marked, *Use*.
- **fanart.tv** — `albumcover` and `cdart`.

The artist's tab has *in use* (thumb 1:1, backdrop 16:9), fanart.tv (thumb, background, a transparent logo on a
checkerboard) and Commons (with its licence). Unmatched albums say *A match fills this from the Cover Art
Archive*.

**FR-277-3 — The wordmark rule.** No cover anywhere a **viewer** looks ⇒ the title set as a wordmark on a
gradient, never a "no cover" badge. On the **admin** side a missing cover *is* a work item: the same wordmark
cell plus a small `--warn` dot, and the *Cover* facet.

**FR-277-4 — `write_music_nfo`.** `album.nfo` in the album folder and `artist.nfo` in the artist folder, in
Kodi's schema (research §2.3). The album NFO holds title, artist, album artist, year, type, the release-group and
release ids, the album-artist id, label, the ticked genres (276 FR-276-7), and one `<track>` per track on disk
(position, title, duration, `musicBrainzTrackID`). The artist NFO holds name, sort name, id, type, formed,
disambiguation, the biography, and its albums. Writes are atomic, and 175's foreign-NFO rule applies.
Unmatched albums get **no** NFO (*not written yet* on the NFO tab).

**FR-277-5 — Save / Sync as films.** The album and artist pages keep the split button (*Save & Sync* ·
*Save → NFO* · *Sync Jellyfin*). `sync_jellyfin` refreshes the album and artist items after a write.

**FR-277-6 — Biography.** From the artist's MusicBrainz URL relationships → Wikipedia (the viewer's language
if present, else English) → the lead section, or Wikidata's description. The source is named under the text
(*Source: Wikipedia (da) · via MusicBrainz's URL relationships*). With no relationship there is an inline
editor (*No biography found … Write one*) whose text is written into `artist.nfo`.

**FR-277-7 — Drift for music.** `detect_drift` covers the two new NFO shapes. When Jellyfin's own NFO saver
rewrites an `album.nfo` (275 FR-275-5), the album page shows 139's drift banner with the time, how many fields
differ, *Re-assert NFO → Jellyfin*, and a link to the Musik card's finding (*this will keep happening until…*).

**FR-277-8 — `fetch_lyrics`.** Present in the run only when *Fetch lyrics* is on (H6). LRCLIB by artist,
title, album and duration → a synced `.lrc` sidecar beside the track, or plain text when only that exists.
Nothing found ⇒ nothing written. The Tracks tab shows *synced ✓ / plain ✓ / none · Fetch*, and with the switch
off it shows *none* and no link. *Fetch missing lyrics (n)* acts on the album. The run summary counts songs
with lyrics.

**FR-277-9 — Music videos by artist.** A `music_artist` may reference `media` rows of kind `MUSIC_VIDEO`
whose filename artist (phase 168's `artist – title` parse) matches its name or alias. The admin Artist page
gets a **Videos** group (16:9 tiles, *Music video · 2003 · 3:58*) that links to each video's `media.html`,
**absent** when none. The storage models are not merged. This is phase 2 content, drawn once.

**FR-277-10 — Albums the library lacks (H4).** Lean: **not shown**; the Artist page lists what is on disk.
The drawn alternative greys out MusicBrainz's other release-groups (*not in library*) and requests nothing.

## Acceptance

1. A matched album gets `cover.jpg` from the CAA's approved front. A matched album with none is in triage with
   *Upload…*.
2. `album.nfo` for a partial album lists only the tracks on disk. Jellyfin re-reads it and shows the ids.
3. With Jellyfin's NFO saver on, a run followed by Jellyfin's own save shows the drift banner. With the saver
   off, it never appears.
4. *Fetch lyrics* off ⇒ no `fetch_lyrics` step in the run and no *Fetch* links. On ⇒ synced lyrics appear on
   the Tracks tab and on the phone (R322 FR-R322-7).
5. An artist with a music video in the music-video library has a Videos group linking to it. An artist without
   one has no group.

## Mockup

`design/app/album.html?a=salt-on-the-window` (full album, drift banner, fanart.tv candidates, lyrics),
`?a=foghorn-lullabies&tab=artwork` (no cover after a match), `design/app/artist.html?ar=harbour-lights`
(biography, Videos, artwork), `?ar=kvold` (no picture, no biography), `design/app/settings.html#mu-libcard`.

## Open questions

1. Does Jellyfin set anything on `Audio` items from `<track>` entries in `album.nfo` (research §9)? If not, the
   recording ids live in our table only, and the NFO keeps them for Kodi.
2. `cover.jpg` vs `folder.jpg` — Jellyfin reads both; lean `cover.jpg` (first in its list).
