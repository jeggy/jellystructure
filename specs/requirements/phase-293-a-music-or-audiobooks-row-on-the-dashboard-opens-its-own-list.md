# Phase 293 — a music or audiobooks row on the Dashboard opens its own list

**Status:** `✓ Built` 2026-10-01 (see *Build notes*), not deployed. Dev-authored 2026-10-01, found in the dev review
of 292 (its item 10).
**Depends on:** 285 (the Dashboard as one overview, one row grammar), 278 (Library → Music), 280/281 (Library →
Audiobooks), 283 (the folder flags), 284 (tags in the files).

## 1. What happened

Reading the code for 292 on `main` `44e26871`: every music and audiobooks row on the Dashboard links to the Library
with the row's triage key, and the Library throws the key away.

- The row's link is `/library?kind=music&filter=<key>` or `/library?kind=audiobooks&filter=<key>`
  (`DashboardRoutes.kt:108–112`).
- `Library.kt` hands a music or audiobooks link to its own page before it reads `filter` (`Library.kt:175`, `:177`;
  the films' `libFilter` is read at `:127`, for films only).
- `renderMusicLibrary` reads only `mview`, `q`, `sort` and `f.*` (`MusicLibrary.kt:44–49`).
  `renderAudiobookLibrary` reads only `f.*` and its own view keys (`AudiobookLibrary.kt:58–63`).
- Neither browse route takes a triage key (`MusicRoutes.kt:323–330`, `AudiobooksRoutes.kt:67`).

So *Albums need a match*, *Albums without a cover*, *Artists without a picture*, *Songs a phone plays only by
re-encoding*, *Songs whose files don't say what they are*, the two folder flags, and the four audiobooks rows all open
the plain list: Artists for music, every book for audiobooks. The row says *12 albums*; the page shows all of them.

The same page carries a stale sentence. It says what the page maintains is written to `album.nfo` / `artist.nfo`,
*never into the files* (`MusicLibrary.kt:112`). Since 284 that is false whenever *Write tags into music files* is on.

## 2. Why

285 moved these rows from the old attention dock to the Dashboard and kept their triage keys as the link. The films'
Library understands `filter=<key>` (`Library.kt:127`, `:410`); the music and audiobooks pages, written later (278,
281), only ever had facets. Nobody clicked through a music row since, or the count and the list were never compared.

Mapping each key onto today's facets is not enough. The facets are close but not equal to the triage predicates:

| Key (`TriageRoutes.kt`) | What the count counts | Nearest facet | Differs by |
|---|---|---|---|
| `music_needs_match` | unlocked albums not matched | `match = needs_you, unmatched` | an unmatched album that is locked is in the facet, not the count |
| `music_no_cover` | **matched** albums with no cover | `cover = missing` | unmatched albums without a cover |
| `music_no_picture` | artists with a folder and no picture | `artimg = missing` | artists without a folder |
| `music_reencodes` | songs a phone re-encodes | `format = WMA` | other containers a phone does not play |
| `music_files_no_ids` | songs of matched albums whose files carry no MusicBrainz id | none | — |
| `music_shared_album`, `music_folder_disagrees` | albums with that flag | `check = …` | none |
| `audiobooks_missing_part`, `audiobooks_two_in_one` | books with that flag | `needs = …` | none |
| `audiobooks_no_cover`, `audiobooks_no_narrator` | books without one | `cover`, `narrator = missing` | none |

## 3. Requirements

- **FR-293-1 — The count and the list use one predicate.** Each music and audiobooks triage key gets one function
  that says whether an album, artist, song or book has the problem. `musicTriageCounts` (`TriageRoutes.kt:522–562`)
  and `audiobookTriageCounts` (`:589–606`) count with it, and the browse routes filter with it. Nothing restates a
  predicate in a second place.
- **FR-293-2 — The music and audiobooks pages read `filter=`.** `renderMusicLibrary` and `renderAudiobookLibrary`
  read `filter`, keep it in their own URL (`muUrl()`, `MusicLibrary.kt:36–42`, and the audiobooks equivalent), and send
  it to their browse route. The route applies FR-293-1's predicate before the facets, so the facets narrow within it
  and their counts stay honest (the *other active facets* rule, `MusicBrowse.kt:227–245`).
- **FR-293-3 — The key chooses the view.** An album key opens Albums, an artist key Artists, a song key Songs
  (`unitForMusic`, `DashboardRoutes.kt:207`, already knows which). Audiobooks have one view.
- **FR-293-4 — The page says what it shows, and it can be removed.** A chip above the list reads the row's label
  (*Issue: Albums need a match*), with ✕ to clear it, as the films' Library does (`Library.kt:410`). An unknown key is
  ignored and shows no chip.
- **FR-293-5 — The stale sentence.** `MusicLibrary.kt:112` says where the page's work goes as it is: `album.nfo` and
  `artist.nfo`, and the music files' tags when *Write tags into music files* is on (`MusicBrowseDto` gains `write_tags`,
  as `MusicAlbumPageDto` already has).
- **FR-293-6 — A test per key.** For every music and audiobooks triage key, a test builds a small library and checks
  that the Dashboard's count equals the length of the browse answer for `filter=<key>`.

## 4. Acceptance

1. With *Albums need a match* at N on the Dashboard, clicking the row opens Albums with N albums and the chip *Issue:
   Albums need a match*. A locked, unmatched album is not among them.
2. *Songs a phone plays only by re-encoding* opens Songs, and the list's length is the row's count.
3. *Audiobooks with a missing part* opens the books with that flag, and only those.
4. Ticking a facet on such a page narrows the list further; ✕ on the chip returns the whole library with the facet
   kept.
5. With *Write tags into music files* off, the Library sentence says `album.nfo` / `artist.nfo`; with it on, it also
   names the files' tags. It never says *never into the files* again.
6. 292's *Lyrics on an instrumental* row (`filter=music_instrumental_lyrics`, 292 item 10) works through the same path
   when 292 is built.

## 5. Open questions

1. **A song whose length disagrees with its release gets that release's recording id written into the file**
   (`MusicTagWriter.kt:129`, from `applyMatch`, `MusicMatchService.kt:250`). `DISAGREES` means *often another version
   — a single's, a live cut* (`Music.kt:211`), so the id may name another recording. Picard does the same when it tags
   a release, so it may be the right standard behaviour; but it spreads a doubtful id to every other player. Should a
   `DISAGREES` track's recording id be left out of the file until *Match this track…* confirms it? Not part of this
   fix; recorded here because 292's review found it.

## Build notes (2026-10-01)

**Built.**
- `src/linuxX64Main/kotlin/dev/jellystructure/music/MusicTriage.kt` holds every music and audiobooks key: its unit
  (album · artist · song · book), the Dashboard row's label, and one predicate (FR-293-1). `musicTriageCounts` and
  `audiobookTriageCounts` (`TriageRoutes.kt`) now count with it; the Dashboard's `unitForMusic` reads the unit from
  the same table.
- `GET /api/music/browse` and `GET /api/audiobooks/browse` read `filter=<key>` (FR-293-2). `MusicBrowse.browse` and
  `AudiobooksBrowse.browse` take a `triage` key and drop rows that do not have the problem **before** the facets, so a
  facet narrows inside the key and its counts only count rows inside it. An unknown key narrows nothing and is not
  echoed back.
- The key chooses the view (FR-293-3): the music route answers with the key's view whatever `view=` said; the
  audiobooks route answers with the books view. Choosing another view on the page leaves the key.
- `MusicBrowseDto` and `AudiobooksBrowseDto` gain `filter` and `filter_label` (additive, admin-only). Both pages keep
  `filter=` in their own URL and draw *Issue: {label}* with ✕ first in the active-filter bar (FR-293-4); ✕ keeps the
  facets, *clear all* clears both.
- FR-293-5: `MusicBrowseDto.write_tags`; the sentence names `album.nfo` / `artist.nfo`, and adds *and into the music
  files’ tags* when *Write tags into music files* is on.
- FR-293-6: `src/linuxX64Test/kotlin/dev/jellystructure/music/MusicTriageTest.kt` — for every key, the predicate's
  count equals the browse list's length; the cases the old facet mapping got wrong (a locked unmatched album, an
  unmatched album without a cover, a credit-only artist, a container other than WMA that a phone cannot play); facets
  inside a key; an unknown key.

**Deviation.** The test checks `MusicTriage.Music.count(key)` against the browse rather than calling
`musicTriageCounts` itself (that needs a running `MusicPipeline` and a `ConfigStore`). `musicTriageCounts` returns
exactly `count(key)` for each key, so it is the same number.

**Verified.** `compileKotlinLinuxX64`, `compileKotlinWasmJs`, `linuxX64Test` for `dev.jellystructure.music.*` and
`dev.jellystructure.audiobooks.*` — green. Not deployed; acceptance 1–5 need the admin web UI on a real library.
Acceptance 6 arrives with 292's row (`music_instrumental_lyrics` is a key of this table).

Open question 1 (a `DISAGREES` track's recording id written into the file) is untouched.
