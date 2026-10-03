# Editions, extras and duplicate songs — research, 2026-10-03

**Asked by the owner:** check one artist's albums and folder structure, and support *the official album* without
losing any songs — deluxe and Japanese editions being the example — with an indicator on a song that is extra.
Then suggest changes to jellystructure and to the collection. Research only: nothing was built and nothing in the
library was changed. Measured read-only on the music folder on disk, a backup copy of the production database, a
copy of Lidarr's database (which stores MusicBrainz's releases and tracklists for every album it knows) and
MusicBrainz itself. The design brief that follows from it is `../design-brief-music-editions-and-duplicates-2026-10-03.md`.

Album and song names are left out on purpose; each is described by what it is.

## 1. The artist's folder

The household's biggest artist: **64 folders, 443 FLAC files and 1 MP3, 20 GB**, all brought in by Lidarr
(`{Album Title} ({Release Year})/{track:00} - {Track Title}`, multi-disc `{disc:00}-{track:00} - …`). Every FLAC
carries the full Picard id set (release, release group, recording, release track, artist). Every folder has
`cover.jpg` and `album.nfo`, and nearly every song has a `.lrc` or `.txt`. No macOS `._*` files are left, and the
library's Jellyfin NFO saver is now off (`MetadataSavers` empty). jellystructure has matched all 63 real folders.

What the 63 are: **10 studio albums, 2 live albums, 2 compilations** (a soundtrack-plus-live set and a nine-disc,
114-track box set), **3 EPs and 46 singles**.

**Housekeeping found:**

- **One stray folder** holding one MP3 with no tags, left over from an older MP3 collection. Both Lidarr and
  jellystructure filed it under a B-sides compilation the household does not own. The same song is in FLAC twice
  (as a Japanese bonus track on a studio album, and on a single).
- **15 MP3s from the older collection** were moved out of the library on 2026-09-30. Each was checked by title
  against the FLAC library: all 15 songs are there. jellystructure marked the 10 rows of the moved folders as
  missing, as it should.
- **The year in the tags** is often the year of the reissue that was downloaded, not the year the music came out:
  a 1998 EP says 2026, most singles say 2009. Phase 290 writes the original date into `DATE` the next time tags are
  written; it is built but not deployed.
- **A handful of releases** are tagged as a CD pressing while the audio is a 24-bit/96 kHz download. The tracklist
  is the same, so nothing depends on it today.

## 2. Editions — what *the official album* is

MusicBrainz has no "standard edition" flag. An album (a *release group*) has many releases: the UK CD, the
Japanese CD with a bonus track, a 24/96 download, an anniversary box. Lidarr picked one per album when it
downloaded, and five of the ten studio albums came in as a bigger edition than the standard one.

**Method tried: the tracklist most official releases share.** Of each album's official releases, take the set of
recordings each one carries and pick the set the most releases agree on. It named the standard edition correctly
for all 12 studio and live albums, by a clear margin. The two compilations are a special case: the box set has one
release, and the soundtrack-plus-live set's releases disagree on recording ids (see below).

| Album (by kind) | Official releases | Standard tracklist | On how many | What we hold | Extras | Where the extras first appeared |
|---|---|---|---|---|---|---|
| 1999 debut | 22 | 12 songs | 16 | 13 | 1 | the Japanese CD, 2000 |
| 2001 second album | 27 | 11 | 18 | 12 | 1 | the Japanese CD, 2001 |
| 2003 third album | 26 | 14 | 16 | 26 (20th-anniversary edition) | 12 | 1 on the Japanese CD, 2003; 11 live/demo cuts on the anniversary edition |
| 2006 fourth album | 20 | 11 | 14 | 12 | 1 | the Japanese CD, 2006 |
| 2018 eighth album | 18 | 11 | 9 | 21 (super deluxe) | 10 | the super deluxe |
| 2008 live album | 12 | 14 | 10 | 15 | 1 | a 15-track release out the same day |
| the other 5 studio albums, the second live album | — | — | — | the standard | 0 | — |

Things learned on the way:

- **"The earliest release" is the wrong rule.** The Japanese editions often came out first: the fourth album's
  first release date (2006-06-28) *is* its Japanese CD with the bonus track.
- **Compare recordings, not titles — with a fallback.** Edition titles differ (*(live from …)* added or not, `&` vs
  `and`), so titles alone mismatch. Recording ids worked for every studio and live album. On the soundtrack-plus-live set, one
  release links a song to a different recording id than the rest, so a fallback is needed
  (the same audio check as §4).
- **Singles have no standard tracklist.** A 1999 single has 14 releases of 2 to 6 tracks (CD1, CD2, 7″, the
  Japanese one…), and "the most common" there is the 3-track CD. Releases with the single or EP type should stay a
  plain list.
- **Where an extra comes from is derivable.** Take the earliest official release in the group that carries the
  recording. Four of the five single-song extras first appeared on the Japanese CD. That gives *Japanese edition*.
  When the earliest such release has its own title (*… 20th Anniversary*) or a disambiguation (*super deluxe*),
  that wording names it instead.
- **jellystructure today reads at most 25 releases per album** (`MusicBrainzClient.kt:214–215`, one browse page;
  290's build notes already say a big group can lose pressings). Three albums here have 26, 27 and 30, so the
  count needs paging.

**What the code has today** (built, 276/283/290/292): `MusicAlbum` stores both `releaseGroupMbid` and
`releaseMbid` plus the chosen pressing (`Music.kt:139–179`); `bestRelease()` picks the pressing that agrees with the
files (`MusicScoring.kt:54–61`); tracks carry `recordingMbid` and `releaseTrackMbid` (`Music.kt:250–281`). There is
**no notion of an official tracklist, an edition or a bonus track** anywhere, and the album page lists the held
edition in file order. Song versions (292: Live, Demo, Remix …) describe a recording, not which edition it came
from, and its title finder deliberately ignores *(Deluxe Edition)*.

## 3. Songs that are nowhere in the library

Against every official release MusicBrainz lists for the artist, by base title. Variants of songs we have are left out:

| What | Songs |
|---|---|
| the 1995 demo EP | 4 |
| a 1999 EP's B-side | 1 |
| B-sides on two 2009–2010 singles | 2 |
| a cover on a streaming-service live single | 1 |
| live bonus tracks on the Japanese CD of the newest album | 2 |

Everything else on every official release is in the library at least once. Holding the biggest edition loses
nothing; the standard edition would have lost 26 songs across the five albums in §2.

## 4. Duplicates

**487 songs in the music library** (production database), 444 of them this artist's. The library holds a lot of
copies because singles repeat album songs and the box set repeats two whole albums. The worst songs appear 10, 8
and 8 times; this artist's 444 tracks are only **158 different songs** by base title.

**Name and length don't work; tried and dropped (owner, 2026-10-03).** The owner first proposed *same name, same
length ±3 s*. It joins 102 copies, but it gets both directions wrong:

- **It joins different takes.** The box set has discs named *Demos*, *Instrumental Demos* and *Live at …*, so a
  name and a length can hide a whole different take. Measured: a live EP's copy beside the studio one, an
  instrumental demo, a festival recording, and six copies MusicBrainz calls an *EP version*, a *stand-alone single
  version* or *with extra opening chord*.
- **It splits one take.** 9 songs are the same MusicBrainz recording but 3–8 s apart: a single's earlier fade, a
  remaster's longer tail, a few seconds of leading silence. Titles split them too (*… (Piste 8)*, `&` vs `and`).

**What identifies a song: its recording.** In MusicBrainz a *recording* is one performance in one mix and one edit.
A remaster or a different fade of the same recording is still that recording; a live take, a demo, a remix or a
radio edit is a different one. That is "the exact same edition" in the owner's sense, and the files already carry it
(`MUSICBRAINZ_TRACKID`, written by Lidarr; 276 checks it against the release).

The audio was checked against it. Full-length Chromaprint fingerprints (`fpcalc -raw -length 0`, already in the
runtime image) were taken of the 387 tracks that share a base title or a recording with another track (7.5 min, 4
at a time) and compared pairwise: 700 pairs, aligned by the fingerprint, scored by bit errors and by how much of
both songs lines up.

- **All 122 pairs that 276 trusts as the same recording are the same audio**: at least 94 % of the longer song lines
  up, and the differences sit only at the very start or end (≤ 6 s of fade or silence). Recording ids are
  reliable here.
- **Audio alone cannot replace the id.** A remaster beside the vinyl-era original scores up to 0.25 bit error.
  A *vocals and keyboard only* mix, or a remix that keeps the backing track, scores 0.24–0.29 over the whole song.
  The audio is decisive only at the clear end: near-identical (≤ 0.15 bit error, ≥ 95 % lined up, almost no
  mismatched stretches) means the same take.
- **13 tracks have no trusted recording**: 8 where 276 found the file's id disagreeing with its release, and 5
  unmatched. For 4 of them the audio is near-identical to an album song. Three are singles whose tags name a
  different recording ("with extra opening chord", "stand-alone single version") but whose audio is the album's
  (0.02–0.12 bit error), and one is a box-set copy. The audio settles exactly the cases where the id is in doubt.
- **3 pairs are two trusted but different recordings with near-identical audio** (0.01–0.13): a single and its
  album at the same length, which MusicBrainz never merged. Nothing proves they are one take, so they are a
  question for the owner, not a rule.

**Recommended rule:**

1. Two copies with the same **trusted MusicBrainz recording** are one song (276's `agrees` or `manual`).
2. A copy **without a trusted recording** is compared by **audio** with the artist's other songs. A near-identical
   match makes it that song.
3. Two trusted but different recordings whose audio is near-identical are **suggested** to the owner (*these sound
   the same: one song?*), never joined on their own.
4. The owner's *same song* / *not the same song* always wins and is kept across runs.

Title and length are never part of the decision. They are only a cheap way to pick which pairs to fingerprint.

**Result: 78 groups, 102 copies folded, 487 → 385 songs** (98 by recording, 4 by audio). Accepting the 3
suggestions makes it 382. The copy shown comes from the album 50 times, the single's 26 times and the compilation's
twice. Hidden: 37 copies on singles and EPs, 65 on the box set and the soundtrack set. Nothing the owner calls a
different take is folded: every live, demo, instrumental, remix and *EP version* copy stays its own song.

This is a display rule; it is not 292's link. Phase 292 Q7 (owner, 2026-10-01) says a song's *version* spreads only
along the same MusicBrainz recording. It still does, and the two now agree, except that a copy joined by audio
(rule 2) is shown once without inheriting the other copy's versions.

## 5. Singles belong to albums

To show an album's singles and B-sides under it (owner, Q1), each single needs a home album. Of the 49 singles and
EPs:

| How the album is found | Count |
|---|---|
| MusicBrainz's *single from* relationship between release groups | 36 |
| via *remix of* → the original single → *single from* | 2 |
| the A-side's base title is a song on one of the artist's albums (singles only, never EPs) | 5 |
| none — stays a stand-alone single or EP | 6 (2 singles on no album, 1 single from another artist's soundtrack, 3 EPs) |

One request per single (`release-group/{id}?inc=release-group-rels`, 1 req/s). **B-side songs per album, after
taking out songs the album already shows (the rule in §4):** 25, 19, 13, 9, 9, 6, 2, 0 and 0; 83 in all. The two
oldest albums carry the most, because their singles came on two or three CDs each.

## 6. Recommendations

**For the collection (the owner's hands, outside the repo):** delete the stray MP3 folder and the folder of moved
MP3s; keep letting Lidarr fetch the **biggest** edition (with §2 in jellystructure nothing extra is shown as part of
the album, and nothing is lost); fetch the newest album's Japanese CD and the two B-side singles if the missing
songs matter.

**For jellystructure** (designs first; see the brief): an official tracklist per album, extras labelled by where
they came from, an album page in the order *album · divider · extras · its singles and B-sides*, *Play album* with a
*Play album + extras* choice, a small *Bonus* marker, and one copy per song in every list that spans albums, by §4's
recording rule. All of it lives in jellystructure's own tables. No tag standard has a field for "bonus track" or "the
official tracklist", and no file is moved, renamed or deleted.
