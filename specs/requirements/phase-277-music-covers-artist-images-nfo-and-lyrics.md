# Phase 277 — Covers, artist images, `album.nfo` / `artist.nfo`, and lyrics

## Status

`✓ Built` 2026-09-28, **not deployed** (build notes at the end). Written 2026-09-28 from the admin music brief (§B3 Artwork/Genres/NFO, §C2, §E2, §H6, §H7), the
research report §2.3, §3.4, §3.5 and §4.2, and the mockups `design/app/album.js` and `artist.js`.
**Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). Builds on **275**, **276**, **133/151** (per-asset locks), **175** (the foreign-NFO rule)
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

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Nine items; two need code the spec assumed existed (1, 2) and one Jellyfin call needs a flag (4).

1. **The artwork writer is `MediaItem`-typed.** `ArtworkDownloader.fetch(item)`, `check(item)` and `mediaDir()`
   dispatch on `MediaKind` (`media/ArtworkDownloader.kt:115, 162, 308`); a `MusicArtworkWriter` for
   `{album}/cover.jpg` and `{artist}/folder.jpg | backdrop.jpg | logo.png` is new code — but the per-asset
   lock is **path-level** (`isManual(imagePath)` / `markManual(imagePath)`, `:143-147`), so 133/151's locks
   work unchanged.
2. **The NFO writer dispatches on `MediaKind`.** `NfoWriter.buildXml` (`nfo/NfoWriter.kt:17-20`) → a separate
   `MusicNfoWriter` (album · artist), the same atomic `.tmp` + rename. 175's foreign rule is
   `PipelineStepOps.writeNfo` comparing the on-disk hash with the row's `nfoHash` (`media/PipelineStepOps.kt:217-235`),
   and `detectDrift` (`:471-479`) reads the same fields — hence 275's `nfo_written_at`/`nfo_hash` columns.
3. **Jellyfin reads it — confirmed in its source.** `AlbumNfoProvider` → `{album}/album.nfo`,
   `ArtistNfoProvider` → `{artist}/artist.nfo`; provider ids by element name (`musicbrainzalbumid`,
   `musicbrainzreleasegroupid`, `musicbrainzartistid`, `musicbrainzalbumartistid`). Jellyfin's own saver writes
   `<track><disc><position><title><duration>`, so `<track>` is in its vocabulary; whether its **parser** sets
   anything on `Audio` items from it is unconfirmed — open question 1 stands, test on the demo Jellyfin.
4. **The refresh call is deliberately non-recursive.** `refreshItem(base, token, id, full)` sends no
   `Recursive` (`auth/JellyfinClient.kt:685-697`). An album refresh must re-read `album.nfo` **and** the
   tracks' new `.lrc` sidecars → add `recursive: Boolean = false` (Jellyfin's `Recursive` query) and use it
   for albums only.
5. **Lyrics:** LRCLIB verified (research §3.5). The sidecar is `{track basename}.lrc`; the library's
   `SaveLyricsWithMedia` is on and Jellyfin's resolver picks it up on the refresh in item 4.
6. **The biography is a new outbound host.** Wikipedia's REST summary (`/api/rest_v1/page/summary/{title}`,
   the viewer's language, else `en`) and Wikidata's description, found through the artist's MusicBrainz URL
   relationships — `OutboundHttp` BACKGROUND, a descriptive User-Agent (Wikimedia asks for one), cached per
   artist per run.
7. **fanart.tv's response shape** (v3 `albums` as a map vs v3.2 as an array) — verify on the first call
   (research §9) and pin the version in the URL.
8. **Videos by artist (FR-277-9):** `MUSIC_VIDEO` rows carry the artist in `director` from 168's parse
   (`media/Scanner.kt:145`, `parseMusicVideoArtistTitle`) → match by normalised name or MusicBrainz alias ✓.
9. **Wire:** none.

## Build notes (2026-09-28)

Built on `main` after 276. Compiles (backend + admin); the music/config tests pass. **Not deployed**, so nothing has
been fetched or written against the household library yet, and open questions 1–2 stay open until the demo Jellyfin
run. The admin pages that show all of this are 278.

1. **Three run steps, seeded like 275's** (`MusicSteps.ARTWORK` `fetch_music_artwork`, `LYRICS` `fetch_lyrics`,
   `NFO` `write_music_nfo`), after `match_musicbrainz` and before the trailing wait/notify; all three run even when
   no film changed and never in a single-item run. `fetch_lyrics` is **filtered out of `effectivePipeline` while
   *Fetch lyrics* is off** (acceptance 4), so the pre-run dialog doesn't list it; a hand-started run with it off
   says *lyrics are off in Settings*.
2. **Covers (FR-277-1).** Matched albums only: the CAA's approved front at 1200 px by release-group
   (`/release-group/{mbid}/front-1200`; the 307 redirect is followed) → `{album}/cover.jpg` (open question 2 went
   with the lean). A locked path is never touched — the lock is 133/151's path-level one, so it works unchanged. A
   release-group with no front is flagged `coverMissingOnCaa` for triage, not an error. `missing` scope (the
   default) skips albums that already have a cover file; `all` re-fetches unlocked ones.
3. **Artist pictures.** With a fanart.tv key: `artistthumb` → `folder.jpg`, `artistbackground` → `backdrop.jpg`,
   `hdmusiclogo` → `logo.png` (v3 URL pinned; `albums` read as either shape, dev review 7). Without one:
   the Wikimedia Commons file from the artist's MusicBrainz `image` relationship, with its author · licence as
   `imageCredit`, which `artist.nfo` carries as a comment.
4. **Biographies (FR-277-6).** Wikidata sitelinks from the artist's MusicBrainz URL relationships → Wikipedia's REST
   summary lead in **en, da and fo** (all three stored; the page picks the viewer's language, else English), else
   Wikidata's description. Fetched once per artist (`all` refreshes), a descriptive User-Agent on every Wikimedia
   call. An admin-typed biography (`biographyEdited`) always wins and is never overwritten.
5. **NFOs (FR-277-4).** `album.nfo` for matched albums only: title, artists, year, type (`compilation` from the
   secondary types), the release-group and release ids, album-artist ids, label, the effective genres, one `<track>`
   per song **on disk** (a partial album lists what it holds). `artist.nfo`: name, sort name, id, type,
   `formed`/`disbanded` for a group and `born`/`died` for a person, disambiguation, biography, albums. Lowercase
   provider-id elements for Jellyfin plus Kodi's camel-case ones. Atomic `.tmp` + rename.
6. **175's foreign-NFO rule, and drift (FR-277-7).** A file whose hash is not the one written here is left alone
   unless `behavior.overwrite_nfo`, and the fields that differ are counted onto the album (`nfoDriftAt`,
   `nfoDriftFields`); *Save → NFO* / *Re-assert* always overwrites, because the admin asked. The page also asks on
   read (`albumDrift`), so Jellyfin's own saver rewriting a file between runs shows up without a run.
7. **Jellyfin refresh (FR-277-5, dev review 4).** `refreshItem` gained `recursive` (default false, so nothing else
   changes); each written or re-covered album is refreshed **recursively** so the tracks' `.lrc` sidecars are read,
   each changed artist non-recursively.
8. **Lyrics (FR-277-8, H6 → on).** LRCLIB `get` by artist · title · album · duration → `{track}.lrc` when synced,
   `{track}.txt` when only plain text exists, nothing when not found or instrumental. A song without lyrics is
   asked again after **30 days**, not every run. An existing sidecar is never overwritten; *Fetch missing lyrics*
   on an album (`POST /api/music/album/{id}/lyrics`) retries that album's songs now.
9. **H7 → no tag writing.** Nothing in this phase opens an audio file for writing.
10. **FR-277-9, the videos link:** `MusicVideoLinks.forArtist` matches `MUSIC_VIDEO` rows by 168's filename artist
    against the artist's name, sort names and aliases (letters and digits only); tested. The route that serves it
    is 278's artist page. **FR-277-10 → not shown** (the lean).
11. **Routes** (all under the admin session): album/artist artwork candidates · use · upload · clear · lock, the
    image files themselves (`/api/music/image/…`, private, short cache), the two NFO views with drift, album/artist
    save (with or without sync), album sync, and the biography editor.

