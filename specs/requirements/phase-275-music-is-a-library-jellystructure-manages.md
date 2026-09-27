# Phase 275 — Music is a library jellystructure manages

> Owner, 2026-09-27: music and audiobooks in jellystructure and a music player mode in Ravilo on the phone
> (the two design briefs of that day). This phase is the floor everything else stands on: the music library is
> read, stored and gated like the film libraries, without becoming a fourth `MediaKind`.

## Status

`Planned` — written 2026-09-28 from `specs/design-brief-music-in-the-admin-2026-09-27.md` (§A, §E1, §E3, §F),
`specs/research-reports/music-library-and-player-2026-09-27.md` (§1.2, §2.1, §4.1–§4.3) and the round-1 mockups
(`design/app/music-data.js`, `library.html?kind=music`, `settings.html#mu-libcard`, `ravilo-users.html`).
**Not dev-reviewed.** Numbering verified against `main` `f8bdaab4` on 2026-09-27: admin taken through **273**,
and **274** is ours (pending export), so the research report's ladder (274–280 / R320–R324) moves up by one:
this is its "274".

The music ladder: **275** (this) · **276** MusicBrainz · **277** covers, NFOs, lyrics · **278** the admin UI ·
**279** `/api/tv/music/**` · Ravilo **R321** the listening mode · **R322** the player. Audiobooks: **280**,
**281**, **R323**.

## Decisions

| # | Question | Answer |
|---|---|---|
| — | Music videos and concert films | **Stay video-mode content** (`MUSIC_VIDEO` in `media`). The link is the artist (277 FR-277-9, R321 FR-R321-11). Owner, 2026-09-27 |
| H1 | The 38 WMA files | **Lean: a *Convert…* action** (278 FR-278-7). Not yet answered |
| — | Kids profiles | **Lean: the Jellyfin library grant only** — no rating source exists for audio. Research §7-6 |

## Requirements

**FR-275-1 — New tables, not a new kind.** `music_artist`, `music_album`, `music_track`, each the `media`
pattern: `id = jellyfinId`, a JSON blob plus index columns (`name`/`title`, `sort_name`, `year`,
`album_artist_ids`, `release_group_mbid`, `release_mbid`, `recording_mbid`, `position`, `disc`, `duration_ms`,
`codec`, `bitrate`, `sample_rate`, `path`, `match_state`, `match_locked`, `cover_state`, `image_state`,
`has_lyrics`, `library_id`, `updated_at`). `MediaKind`, `MediaItem` and every `when` over them are untouched.
Credits are many-to-many (`music_track_artist(track_id, artist_id, role)` with roles *main* and *feat*).

**FR-275-2 — `scan_music`.** A step inside `scan_files`' run, in its own lane (or the media lane — 278
FR-278-10's dev decision). Reads `/Artists`, albums and tracks with the fields listed in the research §2.1.
**No ffprobe per track**: Jellyfin's `MediaStreams` already carry codec, bitrate and sample rate, and an audio file
has one stream. `NormalizationGain` and `AlbumNormalizationGain` are stored (the phone's *Even out volume*,
R322 FR-R322-9). A track gone from Jellyfin is kept and flagged, as films (the *no longer in Jellyfin* rule).

**FR-275-3 — The library row.** *Refresh from Jellyfin* adds a `[[libraries]]` row with
`collection_type = "music"`. Its Settings card (278 FR-278-9) has **no fallback language** (music has no
language cascade). The *Music videos* library keeps its own card and its `MUSIC_VIDEO` items (phase 168).

**FR-275-4 — Visibility.** The same `libraryId ∈ EnabledFolders` rule as films. A viewer whose Jellyfin policy
does not grant the music library sees no music anywhere: no switch, no bar, no row, no search hit (R321 FR-R321-2).
Kids profiles follow the grant only.

**FR-275-5 — Advisor findings on the music library** (212/242's shape):

| Finding | When | Severity |
|---|---|---|
| *Jellyfin writes its own NFO files for this library* | `MetadataSavers` contains `Nfo` | `--bad`, with the path *Dashboard → Libraries → {name} → Manage library → Metadata savers → untick Nfo* and *You lose: nothing — Jellyfin keeps reading them* |
| online fetchers off | always wanted | silent ✓ |
| NFO reader on | always wanted | silent ✓ |
| LUFS scan on | the phone evens out volume from it | silent ✓ |

The first finding links the album that already drifted, when one has (277 FR-277-7).

**FR-275-6 — Health.** `/api/health` gains `music { artists, albums, tracks, matched, needs_you, unmatched,
covers_missing, artist_images_missing, reencodes }`. `reencodes` counts tracks whose container a phone cannot
direct-play (`asf`/`wma` today). The webhook's `unmatched` and `notifyOnNoMatch` stay film/series-only
(FR-168-5); music gets `music_unmatched`.

**FR-275-7 — Users & devices says who has music.** Each user row carries one chip: *Music: yes* or
*Music: no library access*, from the Jellyfin policy — so the admin can see why a phone shows no switch.

**FR-275-8 — Search index.** Tracks, albums and artists feed a `search_text` index of their own; the admin
Library search and 279's grouped search read it. Films' search is unchanged.

**FR-275-9 — Nothing on the wire changes.** No field is added to any DTO an installed app decodes; music
travels only on 279's new paths. `WireCompatTest` (R319) passes unchanged.

## Acceptance

1. After *Refresh from Jellyfin* and a run on the household server: 23 artists, 30 albums, 60 tracks, 38 of them
   `reencodes`, with codec/bitrate/sample rate from Jellyfin and no ffprobe invoked.
2. The Musik card in Settings shows the `--bad` NFO-saver finding while Jellyfin's saver is on, and nothing once it
   is off.
3. A user without the music library's grant: Users & devices says *Music: no library access*; 279's endpoints
   return nothing for that user.
4. `/api/health` carries the `music` block; an app built before this phase decodes every existing response.

## Mockup

`design/app/music-data.js` (the stand-in library, "after the first run"), `design/app/library.html?kind=music`
(the fence's *Mapped, not yet scanned* and *Empty library* states), `design/app/settings.html?tab=libraries#mu-libcard`,
`design/app/ravilo-users.html`.

## Open questions

1. Does music matching run in its own lane or ride the media lane? Both are drawn (Activity's fence, 278 FR-278-10).
2. `music_track_artist` role set — is *main/feat* enough, or does Jellyfin's `ArtistItems` vs `AlbumArtists`
   split need a third (*album artist only*)?
