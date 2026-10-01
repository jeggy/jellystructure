# Phase 290 — an album's year is the year it first came out

**Status:** Planned. Dev-authored 2026-10-01 from an owner request.
**Depends on:** 276 (MusicBrainz match, *Find match…*), 277 (`album.nfo`), 284 (tags in the files).

## 1. What happened

The owner opened an album page that read *(2015)* for an album that came out in 1999, and asked for it to be
matched to *the album from 1999* while keeping all 13 songs in the folder.

What was found (prod, read-only, 2026-09-30 → 10-01):

- **The folder holds a reissue.** 13 FLAC files at 24-bit / 96 kHz, each tagged `DATE=2015`: the 2015 hi-res
  digital reissue, which adds a bonus song as track 13.
- **The match was already the right album.** It had been chosen by hand in *Find match…*: the release group is the
  album (first released 1999-09-06), and the pressing is a 2010 digital release where 13 of 13 tracks agree. The
  2010 pressing was preselected because pressings that agree equally are listed oldest first. The 2015
  *24bit/96kHz* pressing — the one the files come from, also 13 of 13 — sat further down, and could be told apart
  only by its date: MusicBrainz's disambiguation (*24bit/96kHz*) is not shown.
- **No 1999 pressing holds these 13 songs.** Of the release group's 26 releases, the 1999 ones have 11 or 12 tracks;
  a 1999 promo has 13 but opens with a radio edit and has no bonus song; a 2000 pressing has the bonus song at 10,
  not 13. So *a pressing from 1999* cannot be the answer. **The year is.**
- **The year on the page is Jellyfin's, which is the file's `DATE`.** `MusicAlbum.year` is Jellyfin's
  `ProductionYear`, and Jellyfin takes it from the `DATE` tag. **Jellyfin ignores `ORIGINALDATE`:** 284 had written
  `ORIGINALDATE=1999-09-06` into all 13 files, Jellyfin re-read them, and every song and the album still say 2015
  (Jellyfin's database, 2026-10-01).
- **284 writes the files' year back into the files.** `MusicTagWriter.wanted()` sets `date = album.year`, so the
  year MusicBrainz knows can never reach the one tag every player reads. A loop.
- `album.nfo` already says `<year>` from MusicBrainz's first release, but this library's NFO saver rewrites it
  (277 / 242's finding), so Jellyfin never reads ours.

Across the library: of 81 matched albums, **25** carry a different year in their files than MusicBrainz's first
release. In **21** the files are later (reissues, remasters, digital re-releases of old singles). In **4** the files
are earlier — and in at least three of those the match itself looks wrong (an album from the 1970s matched to a
release group first released in the 1990s).

## 2. Decisions

- **D1 — The year an album shows is the year it first came out.** On every surface: the admin, Ravilo, the NFO and
  the files. The year of the copy in the folder is not what a listener means by *the album's year*, and it is not
  what MusicBrainz, Discogs or a record shop list.
- **D2 — The earliest evidence wins: a year only moves earlier.** The year is the earlier of MusicBrainz's first
  release of the matched release group and the year the files carry. A copy dated X proves the album existed by X,
  so a later MusicBrainz date points at a wrong match more often than at a wrong file (the four cases in §1). The
  cost, recorded: a file whose year is wrongly early keeps it.
- **D3 — The file says it: `DATE` is the album's original date.** Picard's meaning of `DATE` is *this pressing's
  date*, but `DATE` is the only date Jellyfin reads, and Picard users who want the original year in their players set
  `date` from `originaldate` with a one-line script for the same reason. `ORIGINALDATE` carries the same value. The
  pressing stays named by `MUSICBRAINZ_ALBUMID`, and its date stays on the album page.
- **D4 — Nothing is written by a scan.** The year reaches the files through 284's moments (on match, *Save → files*,
  bulk *Write tags…*); until then every surface already shows the original year, and the Files tab shows the
  difference.

## 3. Requirements

**FR-290-1 — One resolver.** `MusicAlbum.originalDate(): String?` and `originalYear(): Int?` in the shared model,
beside `effectiveGenres()`. Inputs: `firstReleaseDate` while the album has a release group (a cleared match does
not count), and `year` (what the files say, through Jellyfin). The earlier year wins; on the same year the more
precise value wins (`1999-09-06` over `1999`). Null when neither is known.

**FR-290-2 — Every surface reads it.** The admin album page (title and meta line), Library → Music (album cards,
sort by year, the decade facet), the artist page's album list, triage, Ravilo's album cards, its *by year* sorts
(albums, and songs by their album) and the artist page's groups and *More by*, `album.nfo` (`<year>`,
`<originalreleasedate>`) and `artist.nfo`'s album list. The stored `year` stays Jellyfin's — it is an input.

**FR-290-3 — The tags.** `date` and `originaldate` are both the resolved original date. ASF's `WM/Year` holds a year,
so a WMA file gets the year alone.

**FR-290-4 — The Files tab shows it.** A **Year** column in the *Identity* group, comparing what the file says with
the resolved date (ASF compared on the year). A difference marks the row as differing, so *Save → files* is offered
for a year alone.

**FR-290-5 — Find match… lists the pressing in use first.** Among pressings that agree equally: the album's current
pressing, then the one the files already name (`MUSICBRAINZ_ALBUMID`, as Jellyfin reads it), then oldest first. Each
pressing row shows MusicBrainz's disambiguation (*24bit/96kHz*, *reissue*) when there is one.

**FR-290-6 — Old and new together.** Additive fields only (`MusicReleaseOption.disambiguation`, the page's resolved
year); no Ravilo client change — `MusicAlbumCard.year` already exists and now carries the original year.

## 4. What this phase does not do

- It does not add a year field to type into. A wrongly early year is fixed in the files (a foreign edit, 284 FR-284-4).
- It does not rewrite the library's 25 albums on its own (D4).
- It does not touch films, series, music videos or audiobooks.
- It does not change how Jellyfin decides an **album's** year while this library's NFO saver is on (open question 2).

## 5. Acceptance

1. The album in §1 reads *(1999)* on its admin page, in Library → Music and on Ravilo before any file is written.
2. Its Files tab shows *Year* `2015 → 1999-09-06` on all 13 rows; *Save → files* writes it; afterwards Jellyfin's
   songs read 1999.
3. *Find match…* on it lists the 2015 *24bit/96kHz* pressing with its disambiguation, and after choosing it,
   re-opening the panel preselects it.
4. A matched album whose files say 1977 while MusicBrainz says 1991 keeps 1977.
5. An unmatched album, and an album whose match was cleared, show the files' year.
6. Unit tests: the resolver (earlier wins either way, same year keeps the precise date, cleared match, nothing
   known), the tag map's `date`/`originaldate`, the NFO's year, the pressing order.

## 6. Open questions

1. **A typed year.** For the wrongly-early case (D2's cost) — lean: not until it happens; the file is the place.
2. **Jellyfin's album-level year with the NFO saver on.** Jellyfin may read its own rewritten `album.nfo` (`<year>2015`)
   for the album while the songs say 1999. jellystructure and Ravilo are unaffected (FR-290-1 takes the earlier);
   Jellyfin's own apps may show 2015 until 242's finding (clear the library's savers) is acted on. Check on the §1
   album after acceptance 2.
