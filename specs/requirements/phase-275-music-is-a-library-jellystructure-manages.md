# Phase 275 — Music is a library jellystructure manages

> Owner, 2026-09-27: music and audiobooks in jellystructure and a music player mode in Ravilo on the phone
> (the two design briefs of that day). This phase is the floor everything else stands on: the music library is
> read, stored and gated like the film libraries, without becoming a fourth `MediaKind`.

## Status

`✓ Built` 2026-09-28 (build notes at the end; not deployed — the backend was not restarted). `Planned` when
written 2026-09-28 from `specs/design-brief-music-in-the-admin-2026-09-27.md` (§A, §E1, §E3, §F),
`specs/research-reports/music-library-and-player-2026-09-27.md` (§1.2, §2.1, §4.1–§4.3) and the round-1 mockups
(`design/app/music-data.js`, `library.html?kind=music`, `settings.html#mu-libcard`, `ravilo-users.html`).
**Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). Numbering verified against `main` `f8bdaab4` on 2026-09-27: admin taken through **273**,
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

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Ten items; no blocker. Two things the spec asks for already exist (1, 2).

1. ***Refresh from Jellyfin* already adds the row.** `ui/Settings.kt:1356-1366` merges `/api/jellyfin/libraries`
   into `libraryMappings`, new libraries with `skip = false` and `localPath = jellyfinPath`. FR-275-3 is
   therefore a **card** change (no fallback language; the *not the Music videos library* line), not a new
   mechanism. Consequence: a fresh `music` row is scanned by the film scanner at once — harmless
   (`Audio` → `unsupported-type`, `media/Scanner.kt:1525`) — but `scan_music` must key on
   `collection_type == "music"` explicitly, never on "not skipped".
2. **The advisor finding exists.** `JellyfinAdvisorService.metadataOwnershipFindings(lib)`
   (`advisor/JellyfinAdvisorService.kt:404-411`) flags `MetadataSavers` containing `Nfo` for every managed
   library (`managedJellyfinIds`: `!skip && jellyfinId.isNotBlank()`, `:394`). Once the music row is mapped,
   the `--bad` finding appears with no new code; FR-275-5's *You lose: nothing* is a per-collection wording
   tweak. The three silent ✓ rows need nothing.
3. **Tables:** migration **59** (`58.sqm` is 273's) creates `music_artist`, `music_album`, `music_track`,
   `music_track_artist` + `.sq` files, the `media` shape (JSON blob + index columns, `library_id`). Add
   `nfo_written_at` / `nfo_hash` to the album and artist index columns — 277's foreign-NFO and drift rules
   need them (see 277's review, item 2).
4. **Visibility is a `MediaItem` extension today.** `MediaItem.visibleTo(allowed)` / `visibleTo(device)`
   (`media/MediaStore.kt:36-60`) — write the same two-line rule for the music rows (a shared
   `libraryVisible(libraryId, device)` helper); `DeviceData.allowedLibraries` (`auth/Models.kt:36`, null =
   unrestricted) is the input.
5. **Health** is a hand-built JSON string (`server/Server.kt:443`) — the `music` block is additive. The webhook
   counter stays film/series-only (168 FR-168-5) ✓.
6. **New Jellyfin reads:** `getMusicArtists` (`/Artists`, `/Artists/AlbumArtists`), `getMusicAlbums`,
   `getAudioTracks` (`/Items?IncludeItemTypes=MusicAlbum|Audio&Recursive=true&Fields=…`), on 12.1's header
   auth. The existing `IncludeItemTypes=Movie,Series,MusicVideo` queries are untouched.
7. **Lane (open question 1):** `MediaJobQueue.QUEUE_NAMES = listOf("media", "segments", "subtitles")`
   (`media/MediaJobQueue.kt:1270`), `lane TEXT DEFAULT 'media'` (`db/MediaJob.sq:23`, no migration), the
   scheduler iterates the list (`:470-485`), `emptyQueues` filters on it (`:498-499`). Adding `"music"` is a
   code change plus Activity's lane card. Lean: **own lane** — MusicBrainz is 1 request/second and must never
   hold a film's re-order (213's *slow by design*).
8. **Search:** a `search_text` column per music table; `BrowseService.search` (`tv/BrowseService.kt:232`)
   keeps its response shape — music groups travel only on 279's path.
9. **Open question 2 (credit roles):** Jellyfin gives `ArtistItems` (per track) and `AlbumArtists` (per album)
   separately (measured) → three roles: *album_artist*, *artist*, *feat* — *feat* only when MusicBrainz's
   artist-credit join phrase says so, never from a title parse alone.
10. **Wire:** nothing an installed app decodes changes (FR-275-9) ✓.

## Build notes (2026-09-28)

Built on `main` after the dev review. Compiles (`compileKotlinLinuxX64`, `compileKotlinWasmJs`), the migration
verifies, and the music, config, plan and advisor tests pass. **Not deployed; acceptance 1–4 are checked on the
server once it runs this build.**

1. **Tables (FR-275-1).** `Music.sq` + migration **59**: `music_artist`, `music_album`, `music_track`,
   `music_credit` (roles `album_artist` · `artist`, `feat` reserved for 276), the `media` shape — a JSON blob plus
   the columns a query filters on (`library_id`, names, MusicBrainz ids, `match_state`, `match_locked`,
   `cover_state`, `search_text`, `missing_since`). The models are in `commonMain` (`model/Music.kt`), so the admin
   decodes the same classes. `MediaKind` and `MediaItem` are untouched.
2. **`scan_music` is a real pipeline step (FR-275-2), not a sub-step.** It runs in the same run, directly after
   `scan_files`, so it shows in the pre-run dialog and Activity's strip and can be skipped for a run. It is seeded
   once into an existing pipeline (`scan.music_steps_seeded` records *which* music steps were added, so 276/277's
   steps each join once and an operator's removal sticks) and is in the built-in default. Two engine rules are new:
   **the music steps run even when no film or series changed** (the empty-working-set early return now keeps them),
   and **a single-item run never runs them** (a webhook for one film does not rescan music).
3. **One read per kind, all or nothing.** `JellyfinClient.getMusicLibrary` pages `/Artists?ParentId=`, `MusicAlbum`
   and `Audio` items; any failed page returns null and nothing is marked missing. No ffprobe per track.
4. **Artists are the union of folders and credits.** `/Artists?ParentId=` lists folder-backed artists only; an artist
   Jellyfin knows only from a track's `ArtistItems` is kept too, with no folder (and not counted as a missing
   picture). **On the household library the scan stores 20 artists (19 + 1 credit-only), 30 albums, 60 songs, 38 of
   them `reencodes`** (checked read-only against Jellyfin on 2026-09-28). Acceptance 1's "23 artists" counted every
   `MusicArtist` across Jellyfin's libraries, including the music-video library's.
5. **What a rescan may change.** The scan overwrites only Jellyfin's fields on top of the previous row (names,
   paths, formats, gains, credits, tag genres, the cover state); match state, MusicBrainz ids, locks and anything a
   later phase adds are carried untouched. A row Jellyfin no longer reports keeps its data and gets `missing_since`;
   reappearing clears it. Another library's rows are never touched.
6. **Covers and pictures.** `cover_state`: a file in the album folder (`cover`/`folder`/`front` · jpg/png) →
   `file`; else Jellyfin's `Primary` image tag → `jellyfin`; else `none`. Artists the same with `folder`/`artist`/
   `thumb`/`poster`.
7. **FR-275-4:** `musicVisible(libraryId, allowed)` — the films' grant rule, fail-closed. Its readers are 279's routes.
8. **FR-275-5:** the existing NFO-saver finding fires for the music library as soon as it is mapped; for a
   `music` library its trade-off reads *You lose: nothing — Jellyfin keeps reading the album.nfo and artist.nfo
   files*. **FR-275-6:** `/api/health` carries `music {artists, albums, tracks, matched, needs_you, unmatched,
   covers_missing, artist_images_missing, reencodes}`, null when no music library is mapped. `music_unmatched` in
   the webhook waits for 276 (nothing is matched before it). **FR-275-7:** Users & devices' access line adds
   *Music: yes* / *Music: no library access*.
9. **FR-275-3:** a `music` library's card shows the provider line instead of *Fallback lang*.
10. **Lane (open question 1):** no job-queue lane here — the scan is three reads inline in the run. 276 decides
    matching's.
11. **FR-275-8:** `search_text` is written on every row; its readers are 278 (admin) and 279 (phone).
