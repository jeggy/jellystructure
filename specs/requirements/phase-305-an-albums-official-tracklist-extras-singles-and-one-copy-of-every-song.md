# Phase 305 — An album's official tracklist, its extras, its singles, and one copy of every song

> Owner, 2026-10-03: *"Collect the biggest edition and lose nothing"* — the official album first, a thin divider, the
> extras, then the singles and B-sides; every song shown once. Owner, 2026-10-04, on the round-1 directions:
> D1 **A**; Q1 yes · Q2 yes · **Q3 yes, the B-sides play with *Play album + extras*** · Q4 no number ·
> **Q5 the better file first, wherever it is from** · Q6 after the version chips · Q7 folded.

## Status

`✓ Built 2026-10-05` · **dev-reviewed 2026-10-04** (against `main` `5210045a`; see the end) · built (see Build notes) · pending export. Written
2026-10-04 (design-authored) from
`specs/design-brief-music-editions-and-duplicates-2026-10-03.md` (owner decided §A on 10-03 and §G on 10-04) and the
mockups built from it:
- `design/app/editions.js` + `editions.css` (`ed-*`, `window.Editions`), loaded by `album.html`, `library.html`,
  `artist.html`, `metadata.html` and `index.html`
- the round-1 canvas `design/app/Music Editions - Directions.html` (+ `editions-directions.js`), where D1 = **A** is
  picked and B/C are kept as declined

The number was checked free on `main` (tree `fdbc126`, admin tops at 304 with our playback-sessions spec) on
2026-10-04. The brief's own "303 / R361" were taken the same day. **Ravilo's side is R373.**

**Builds on** 275 (the music library), 276 (MusicBrainz: the release match, the recording match, `agrees`/`manual`),
278 (the admin UI: album, artist, Library → Music), 285 (the Dashboard's row grammar) and 292 (song versions — a
separate axis: a live cut on an anniversary edition carries *Live* **and** *Bonus*).

## The household (numbers to build against)

- 5 of 10 studio albums are held as a bigger edition; their extras are **1 · 1 · 12 · 1 · 10**. One live album has 1.
- 487 songs → **385** shown; 102 copies in 78 groups folded away (98 by recording, 4 by audio); **3 suggestions**.
- 43 of 49 singles and EPs find their album. B-sides per album: **25 · 19 · 13 · 9 · 9 · 6 · 2 · 0 · 0**.
- Stand-ins in the mockup: Harbour Lights — *Kite Weather* (2006, 11 + *Lantern Swing*, Japanese edition, 24/96) and
  *Signal Found* (2003, 14 + 12 extras from *Signal Found 20th Anniversary*). Singles from *Kite Weather*:
  *Northern Line*, *Fog Bank*, *Salt on the Window*, *Low Tide*, *Tidewater* — 13 B-sides.

## Requirements

### The model

**FR-305-1 — Our own tables; no file is touched.** No tag standard carries an official tracklist or a bonus flag
(research §C), so this lives in jellystructure's database. Nothing here moves, renames, retags or deletes a file.
- `music_album_official` — per album: the official tracklist (ordered MusicBrainz recording ids), the release it was
  read from, `k` of `m` releases that share it, `source` = `auto` | `user`, `user_release_mbid` when picked.
- `music_track_extra` — per track: `extra` (bool), `first_release` (title + year of the earliest official release
  that carries the recording; *the Japanese CD, 2006*).
- `music_single_home` — per single/EP release-group: `album_id` or null, `how` = `mb_single_from` | `via_remix` |
  `by_title` | `user`.
- `music_fingerprint` — per track, only where the audio check is needed (FR-305-9): the Chromaprint, kept until the
  file's size or mtime changes.
- `music_same_song` — the owner's decisions: pair (track a, track b) → `same` | `not_same`; plus answered
  suggestions (`yes` | `no`), so a pair is never asked about twice.
- Duplicate **groups are computed, never stored** — they follow the files, the matches and the decisions.

### The official album

**FR-305-2 — Found automatically.** For an album, a live album or a soundtrack with a trusted release match (276),
the official tracklist is **the tracklist most of the release group's official releases share** (by recording, in
order; status *Official*, not *Bootleg* / *Promotion*). Ties go to the earliest release. The line records *k of m*.
Singles, EPs, compilations and box sets never get one (a single here has 14 releases of 2–6 tracks).

**FR-305-3 — Extras.** A held track whose recording is not on the official tracklist is an **extra**. Extras stay in
the library, in every count of files, and are shown — never hidden. An extra in the middle of the held edition
(track 12 of 26) moves down to the extras section. `first_release` is filled from MusicBrainz.

**FR-305-4 — The owner's pick.** *Change…* lists the album's pressings (Find match…'s list: country · date · label ·
format · tracks · *against the official*) with **Use as the official album** on each pressing whose extra songs are in
the library. A pick is `source = user`, kept across every scan and re-match, and reads *chosen by you · Back to
automatic*. Songs the pick has that the held files lack become gap rows; songs the files have that the pick lacks
become extras.

**FR-305-5 — An unmatched album** has no official tracklist: a plain list in the files' own order, no divider, no
singles section, no *+ N extras* — exactly today.

### Singles under their album

**FR-305-6 — A single finds its album**, in this order, first hit wins:
1. MusicBrainz's *single from* relationship on the single's release group → `mb_single_from`;
2. a track on the single is a remix / edit of a recording on the album (292's *remix of*) → `via_remix`;
3. the single's A-side title equals an official album song's title, same artist, single released within two years of
   the album → `by_title`;
4. the owner's **Move to…** (any album of the same artist, or **No album**) → `user`, kept across runs; **Back to
   automatic** clears it.
A single from another artist's release (a soundtrack single) is never homed. EPs follow the same rules.

**FR-305-7 — B-sides** are a homed single's tracks that are not already one of the album's songs (official or extra)
after FR-305-8's folding. The A-side is never repeated.

### One copy of every song

**FR-305-8 — A song is its recording** (brief §A6). Two tracks are one song when:
1. they have the **same trusted MusicBrainz recording** (276 `agrees`/`manual`); or
2. one has **no trusted recording** and its fingerprint is near-identical to one of the same artist's songs
   (FR-305-9); or
3. the owner said **same song** (`music_same_song`).
The owner's **not the same song** beats 1 and 2. Title and length never decide.

**FR-305-9 — The audio check.** Fingerprint only the tracks that need it: those without a trusted recording, and the
same artist's songs they are compared with. Compare over the overlapping 30 s from the same second; a match score at
or above the threshold the dev side picks (lean: Chromaprint's `≥ 0.9` similarity) joins (rule 2). Two **trusted but
different** recordings that match are never joined — they become a **suggestion** (FR-305-14).

**FR-305-10 — The copy shown in lists = the better file, wherever it is from** (owner, Q5, against the brief's lean).
Better = lossless over lossy; then higher bit depth; then higher sample rate; then higher bitrate. On a tie: the album's
official copy, then the album's extra, a single's, a compilation's or box set's, a live album's; then the earliest
added. The shown copy's play count, last played, rating and favourites are the **song's** — summed / unioned over the
copies, written back to the copy that was played.

**FR-305-11 — Facts are not spread.** 292 Q7 holds: versions, lyrics and every other fact are shared only between
copies of the same MusicBrainz recording. A copy joined by sound or by the owner keeps its own.

### The admin

**FR-305-12 — Album → Tracks** (`album.html`), top to bottom:
- header count *11 songs + 1 extra*; the length is the official album's;
- under the release line: **Official album: {n} songs, as on {k} of {m} releases · Change…** (or *as on {release} ·
  chosen by you · Back to automatic · Change…*) and *held: {country · year · label · format · tracks}*;
- the official songs, numbered in the official order; a gap row (*not in library*) where the held edition lacks one,
  above the divider;
- a thin divider **Extras · {edition} · {n}**; extra rows **unnumbered** (Q4), each with *first on {release}, {year}*;
  version chips as 292 (no Bonus chip here — the divider says it);
- **Singles & B-sides** below the table: one row per single (cover, title, year, *B-sides here*, a **Linked** chip
  *MusicBrainz · via remix · by title · you*, **Move to…**), then a fold *{n} B-sides · Show* listing them with *from
  {single} (single, {year})*;
- a single's own page: *Single from {album}* with its Linked chip and Move to…, or *No album*.

**FR-305-13 — Library → Music → Songs is folded** (Q7). One row per song (FR-305-10's copy), *also on {n} releases*
under the title; the header says *{songs} songs · {n} copies folded*. A **Show every copy** switch (off by default,
remembered per admin) lists every file: the other copies indented under their song, each with its reason (*same
recording · MusicBrainz* · *sounds the same* · *you said so*). Clicking *also on …* opens the **copies panel**: every
copy (album, kind, year, track, format), *shown in lists* on the kept one, the reason chip, **Not the same song** on
each other copy, and **Same song as…** (a search over the same artist's songs) at the foot. Albums and artists count
songs the folded way. Every copy that is an extra carries **Bonus** after its version chips (292's chip family, no
hue).

**FR-305-14 — Dashboard** (285's grammar): *Songs that may be the same* · severity **info** · counted in **pairs** ·
fix *here* · action **Listen and decide**. The modal shows one pair at a time (*{i} of {n}*): two players that start
from the same second, each with album · kind · length and its MusicBrainz recording name; **Yes, one song** · **No, two
songs** · **Later**. Yes stores `same` (*you said so*); No stores `not_same` and the pair never returns; the count
follows; at zero the row is gone.

**FR-305-15 — Artist** (`artist.html`, Q1): a homed single or EP leaves **Singles & EPs**; the section keeps the
stand-alone ones, with one line *{n} singles live under their albums — {album}'s {k} · … in Singles & B-sides*.

### Out of scope

Fetching missing editions or songs (Lidarr's job) · deleting or retagging duplicate files · telling MusicBrainz to merge
recordings · an official tracklist for singles, EPs or box sets · the TV (no music mode).

## Open questions (with leans)

1. **The fingerprint threshold** (FR-305-9) — lean Chromaprint similarity ≥ 0.9 over 30 s; the dev side measures on
   the household's 4 audio joins and 3 suggestions.
2. **Summed play counts** (FR-305-10) — lean yes, the song's numbers are the union of its copies; Jellyfin's per-item
   numbers are untouched.
3. **A pick whose extras aren't held** (FR-305-4) — lean: the row is shown but cannot be picked, with *+ 13 not in
   the library*.

## Strings (English; da/fo are drafts — the shipped `i18n/*.json` wins)

*Official album: {n} songs, as on {k} of {m} releases* · *as on {release}* · *Change…* · *Use as the official album* ·
*Use automatic* · *chosen by you* · *Back to automatic* · *held:* · *Extras · {edition} · {n}* · *first on {release},
{year}* · *{n} songs + {k} extras* · *Bonus* · *Singles & B-sides* · *Linked* · *MusicBrainz* · *via remix* ·
*by title* · *you* · *Move to…* · *No album* · *Single from {album}* · *{n} B-sides* · *Show* · *Hide* · *from {single}
(single, {year})* · *also on {n} releases* · *Show every copy* · *{n} songs · {k} copies folded* · *shown in lists* ·
*same recording · MusicBrainz* · *sounds the same* · *you said so* · *Not the same song* · *Same song as…* · *Songs
that may be the same* · *Listen and decide* · *These sound the same: one song?* · *Yes, one song* · *No, two songs* ·
*Later* · *{n} singles live under their albums*.

## Acceptance

- *Kite Weather* shows 11 numbered songs, *Extras · Japanese edition · 1*, *Lantern Swing* unnumbered with *first on
  the Japanese CD, 2006*, then 5 singles and *13 B-sides · Show*.
- Picking the Japanese CD makes *Lantern Swing* song 12; no divider; *Back to automatic* restores it.
- Library → Songs lists 385 songs; *Show every copy* lists 487.
- *Northern Line*'s panel lists 4 copies with their reasons; *Not the same song* on the compilation's splits it off.
- The Dashboard row reads *3 pairs*; answering all three removes it.
- Harbour Lights' *Singles & EPs* shows 6.

Each line above is also a CI test on stand-in data (see **Tests**): line 1 → `MusicAlbumPageTest.kite_weather_reads_official_then_extras_then_singles`
(with owner decision 3 the divider reads MusicBrainz's title or disambiguation, else *Extras · Japan · 1*); line 2 →
`MusicOfficialPickTest.picking_the_japanese_cd_and_back_to_automatic`; line 3 →
`MusicSongFoldTest.songs_fold_and_show_every_copy_lists_every_file` (scaled down; the household's 385/487 is a real-scan
check); line 4 → `MusicSongIndexTest.not_the_same_song_splits_the_compilations_copy_off`; line 5 →
`MusicSameSongDashboardTest.three_pairs_then_none`; line 6 → `MusicSingleHomeStoreTest.the_artist_page_keeps_six_singles_and_eps`.

## Tests

All backend tests live in `src/linuxX64Test/kotlin/dev/jellystructure/music/`, use the music tests' style
(`snake_case` names, a temp SQLite store from `createDatabase("/tmp/jellystructure-test-<name>-${getpid()}.db")`,
`MusicStore.replaceLibrary`), and only the design's stand-ins (Harbour Lights, *Kite Weather*, *Signal Found*,
*Tide Tables 1999–2012*, *Northern Line*, *Fog Bank*, *Salt on the Window*, *Low Tide*, *Tidewater*, *Lantern Swing*;
another artist = *Lighthouse Keepers*). **No test calls MusicBrainz, AcoustID or `fpcalc`**: MusicBrainz is either a
`FakeMb` subclass of `MusicBrainzClient` (as `MusicMatchTest` does) or a loopback fake server; fingerprints are
synthetic `List<Int>`s. By build (item 10): **305a** = 1–3, 9–12, 15–17 (official/pick parts); **305b** = 6, 21; **305c** = 4, 5, 7, 8, 13,
14, 18–20, 22.

**Pure functions first**

1. `MusicOfficialTest` — the vote, `MusicOfficial.vote(group, releases)` (FR-305-2, item 4). Releases are built in code
   like `MusicMatchTest.release()`:
   - `the_most_shared_recording_set_wins_and_says_k_of_m`: 9 *Kite Weather* pressings, 7 with the same 11 recordings,
     the Japanese CD with 12 → 11 songs, `k = 7`, `m = 9`.
   - `order_discs_and_titles_come_from_the_earliest_release_with_that_set`: the same set in two orders → the earliest one's.
   - `a_tie_goes_to_the_smaller_set_then_the_earliest`: *Signal Found*'s 26-song anniversary pressing is the
     earliest and ties 14-song pressings 2–2 → the 14-song set; two equal small sets → the earlier one.
   - `videos_and_dvd_media_are_dropped_before_the_vote`: a CD+DVD deluxe majority does not add its video recordings
     or a `DVD-Video`/`Blu-ray` medium as gap rows.
   - `only_official_releases_vote`: Bootleg and Promotion pressings in the input change nothing.
   - `only_an_album_without_compilation_gets_one`: tested on `primaryType` (item 2e). Album, Album + Live and Album +
     Soundtrack get one. Single, EP, Album + Compilation and a box set get `null`. A Single + Live is still not an album.
   - `one_medium_numbers_1_to_n_more_media_number_disc_and_track`.
2. `MusicExtrasTest` — `MusicOfficial.extras(album, tracks)` and gaps (FR-305-3, FR-305-5, item 2a, item 3):
   - `a_held_recording_off_the_official_list_is_an_extra_and_moves_down`: track 12 of 26 sorts into the extras.
   - `a_disagreeing_tracks_recording_still_decides_extra`: `disagrees` counts for the extra test but not for rule 1.
   - `no_recording_id_on_a_matched_album_is_an_extra`.
   - `an_official_song_the_files_lack_is_a_gap_row_above_the_divider`.
   - `an_unmatched_album_has_no_official_list_no_extras_no_gaps`: files' order, exactly today.
3. `MusicEditionNameTest` — `MusicOfficial.edition(group, firstExtraRelease)` (owner decision 3):
   - `the_releases_own_title_when_it_differs_from_the_groups` → *Signal Found 20th Anniversary*.
   - `else_its_disambiguation_as_it_is` → *super deluxe*.
   - `else_the_country_code` → `JP`; the admin renders *Extras · Japan*.
   - `else_nothing_and_the_divider_reads_extras`.
   - `the_first_release_is_the_earliest_dated_pressing_that_carries_the_recording` (*the Japanese CD, 2006*).
4. `MusicCopyRankTest` — `MusicCopyRank.compare` (FR-305-10, owner decision 2, item 8):
   - `bitrate_decides_first`: a 320 kbps MP3 beats a 256 kbps AAC.
   - `a_copy_a_phone_re_encodes_is_not_ranked_lower`: a 192 kbps WMA beats a 160 kbps MP3. This is the declined lean
     Q-B, pinned.
   - `equal_bitrate_goes_to_the_higher_bit_depth`.
   - `a_full_tie_goes_official_extra_single_compilation_live_then_earliest_added`.
   - `an_unknown_bitrate_sorts_below_every_known_one`: pins the build's choice.
   - `lossless_codecs_are_the_listed_six`: `flac alac wav pcm_* ape wavpack tta`. Shown on the copies panel only; the
     list is not a rank key.
5. `MusicSongIndexTest` — the fold, `MusicSongIndex(snapshot)` (FR-305-8, FR-305-11, items 1 and 3). This is the lazy
   index on the snapshot, over 292's `MusicVersions.keyOf` / `MusicVersionIndex.copies`:
   - `rule_one_is_keyof`: `agrees`/`manual` on the same recording is one song; `disagrees` stays `trk:`.
   - `a_sound_pair_joins_only_when_one_side_is_untrusted`.
   - `two_trusted_different_recordings_that_sound_alike_are_a_suggestion_not_a_join`.
   - `the_owners_same_joins_anything`.
   - `not_the_same_song_splits_the_compilations_copy_off`: *Northern Line*'s 4 copies. A `not_same` on the
     compilation's copy takes it out of the 3-clique entirely, leaving 3 + 1. Only an explicit `same` brings it back.
   - `title_and_length_never_decide`: two same-titled, same-length tracks with no recording, no pair and no decision
     stay two songs.
   - `versions_and_lyrics_still_follow_the_recording`: a copy joined by sound keeps its own 292 answer.
   - `groups_are_recomputed_after_any_store_write`: nothing stored.
   - `bonus_on_a_folded_row_only_when_the_song_is_official_nowhere` (owner decision 1). A live cut that is an extra on
     the anniversary edition and official on the live album is not bonus, even when the extra copy is the one shown.
     An extra on every copy is bonus.
6. `MusicSingleHomeTest` — `MusicSingleHome.resolve(single, albums, facts, overrides)` (FR-305-6, FR-305-7, item 4):
   - `musicbrainz_single_from_wins_first` (`mb_single_from`): it wins even when rule 3 would pick another album.
   - `a_remix_of_an_album_recording_is_via_remix`.
   - `a_remix_of_the_singles_own_recording_follows_the_hop`: target recording → the held single → its `singleFrom`.
   - `same_base_title_same_artist_within_two_years_is_by_title`; at three years it is not homed.
   - `an_ep_is_never_homed_by_title`.
   - `another_artists_single_is_never_homed`: a soundtrack single credited to *Lighthouse Keepers*.
   - `the_owners_move_and_no_album_win_and_back_to_automatic_clears`.
   - `b_sides_are_the_singles_songs_that_are_not_already_the_albums`: *Kite Weather*'s 5 singles give 13 B-sides, in
     single (year) order. The A-sides are never repeated, nor is a B-side that folds into an extra.
7. `MusicSoundMatchTest` — `MusicSoundMatch.score(a, b)` and `joins(score)` (FR-305-9, item 6). Fingerprints are
   seeded-random 32-bit frames, so the cases are exact. `SegmentDetection`'s popcount becomes `internal` and is shared;
   `SegmentPositionDetectionTest` must stay green:
   - `identical_is_bit_error_0_coverage_1_offset_0`.
   - `a_3_second_lead_in_is_found_as_the_offset_in_ms` (frames × `FRAME_SEC`).
   - `ten_percent_bit_flips_join_twenty_do_not` (≤ 0.15).
   - `a_remix_that_keeps_only_the_first_40_percent_does_not_join` (coverage < 95 % of the longer song).
   - `an_offset_beyond_10_seconds_is_not_searched`.
8. `MusicPairCandidatesTest` — `MusicCompareSongs.candidates(snapshot, decided)` (item 5):
   - `same_artist_same_base_title_not_already_one_song`. The artist is the MBID, else the Jellyfin artist id. The base
     title uses 292's title-finder (`MusicVersions.segments`), so *Northern Line (radio edit)* pairs with *Northern
     Line*.
   - `untrusted_side_is_rule_2_both_trusted_and_different_is_a_suggestion`.
   - `a_decided_pair_is_never_offered_again`.
   - `pairs_are_ordered_a_less_than_b`.
   - `title_only_picks_never_joins`: a candidate whose score fails stays two songs.

**MusicBrainz (no network)**

9. `MusicBrainzEditionsClientTest` — a loopback fake MusicBrainz (`embeddedServer(CIO, port = 0, host = "127.0.0.1")`,
   as `ReceiverTokenRefreshIntegrationTest`) behind a real `MusicBrainzClient(config, base = "http://127.0.0.1:$port/ws/2")`.
   It answers from **`MbEditionsFixtures.kt`** (same folder): inline JSON in exactly the shape musicbrainz.org sends,
   renamed to stand-ins, like `MusicVersionFactsTest.release`. The fixtures:
   - *Signal Found*'s 30 pressings as `release-count: 30` pages of 25 + 5;
   - *Kite Weather*'s 9, the Japanese CD with *Lantern Swing*;
   - a CD+DVD deluxe with `"video": true` recordings and a `DVD-Video` medium;
   - *Northern Line*'s release group with `release-group-rels` (`single from`, `direction: forward`, a `release_group`
     target);
   - a *Lighthouse Keepers* soundtrack single;
   - an EP.

   Tests:
   - `releases_of_pages_past_25_until_release_count` (item 2d/4): the query has `status=official`,
     `inc=recordings+media+labels`, `limit=100`, `offset=0` then `offset=25`; 30 releases; it stops at `release-count`.
   - `an_empty_page_stops_paging`: no endless loop on a server that under-counts.
   - `a_failed_later_page_answers_null_not_a_short_list`: a partial set would vote wrong. Null keeps what was known.
   - `find_match_lists_the_26th_pressing`: `MusicMatchService.releases` on the paged call. This is the shipped
     25-cap bug, item 12.
   - `release_group_asks_for_release_group_rels_and_reads_single_from_forward`: `MbRelation.releaseGroup` is decoded.
   - `a_recording_reads_video`: `MbRecording.video`.
10. `MusicEditionsCatchUpTest` — `match_musicbrainz`'s catch-up pass on a store with a counting `FakeMb` (items 3 and 4):
    - `a_matched_album_gets_official_extras_and_origins_in_one_or_two_requests`.
    - `a_locked_album_is_caught_up_too`.
    - `a_second_run_asks_nothing_unless_scope_all_or_the_group_changed`.
    - `an_extra_on_no_release_of_the_group_costs_one_recording_call`.
    - `singles_and_eps_without_relationships_are_caught_up_once`.
    - `an_unreachable_musicbrainz_leaves_the_official_list_as_it_was`.

**Store, migration, ingest**

11. `MusicEditionsStoreTest` — the owner's rows and the JSON fields (FR-305-1 as replaced by item 3):
    - `a_database_one_version_old_upgrades_and_keeps_its_music`. Create the DB, drop `music_official_pick`,
      `music_single_home`, `music_same_song` and `music_sound_pair`, and set `PRAGMA user_version` back by one.
      Reopening with `createDatabase` creates all four, and the existing albums, tracks and 292's
      `music_version_choice` rows are untouched.
    - `same_song_is_stored_with_a_less_than_b_whatever_order_it_is_given`.
    - `an_album_json_written_before_305_decodes_with_no_official_list`.
    - `the_owners_pick_survives_a_rescan_and_a_rematch_to_the_same_group`.
    - `a_rematch_to_another_group_or_clear_match_drops_the_pick_with_a_history_line`.
12. Extend `MusicIngestTest` with `a_rescan_keeps_official_and_single_from_and_takes_bit_depth_from_jellyfin`.
    `BitDepth` is read on `JellyfinAudioStream` and is a Jellyfin-owned field in `MusicIngest.carry`; the 305 fields
    are not (item 2b).
13. `MusicFingerprintCacheTest` — `fingerprints/music/<track id>-<size>-<mtime>.json` under a temp dir, with a counting
    fake `computeFingerprint`. Tests: `an_unchanged_file_is_never_fingerprinted_twice`;
    `a_changed_size_or_mtime_misses_by_name`.
14. `MusicCompareSongsTest` — the step on a store, with a fake fingerprinter (item 7):
    - `compare_songs_writes_ber_coverage_and_offset_per_candidate_pair`.
    - `a_second_run_only_touches_new_or_changed_files`.
    - `it_asks_for_the_background_process_class`: a recording fake `ProcessGate`.
    - Extend `MusicMatchTest.the_match_step_joins_before_the_trailing_notify`: `compare_songs` follows
      `match_musicbrainz` in `MusicSteps.seed` and in the default pipeline, and a pipeline that already has the
      music steps gains only `compare_songs`. `MusicSteps.isMusic("compare_songs")`, and, once 303 has landed, it is
      kept off a title's Checks card.

**The admin's answers (routes and DTOs)**

15. `MusicAlbumPageTest`: the album page DTO is built by a pure `MusicAlbumPage.build(snapshot, albumId)`, lifted out
    of `MusicRoutes` (FR-305-12, item 9).
    - `kite_weather_reads_official_then_extras_then_singles`: 11 numbered rows; one extra row with `number = null`,
      `extra = true` and `first_on` set; *k of m*; 5 singles with their `how`; 13 B-sides.
    - `the_header_length_is_the_official_albums`.
    - `a_gap_row_carries_disc_position_title_length`.
    - `a_singles_own_page_says_single_from`.
    - `an_unmatched_album_is_todays_page`: no official line, singles or gaps.
16. `MusicOfficialPickTest` (FR-305-4). Tests: `picking_the_japanese_cd_and_back_to_automatic` (*Lantern Swing* becomes song 12,
    no divider; `DELETE` restores it); `a_pressing_whose_extras_are_not_held_is_listed_not_pickable` (*+ 13 not in the
    library*, open question 3).
17. `MusicEditionsRoutesTest` (`testApplication`, as `GzipCompressionIntegrationTest`): the admin routes' contracts.
    - `releases_official_lists_every_pressing_with_against_the_official`.
    - `put_official_rejects_a_release_not_of_this_album`.
    - `put_home_takes_an_album_of_the_same_artist_or_no_album`.
    - `same_song_rejects_a_equal_b_and_unknown_tracks`.
    - `copies_lists_every_copy_with_its_reason_and_shown_in_lists`.
18. `MusicSongFoldTest` — `MusicBrowse.browse` SONGS, folded vs `copies=all` (FR-305-13, item 9):
    - `songs_fold_and_show_every_copy_lists_every_file`: 9 files → 6 songs, header *6 songs · 3 copies folded*.
      `copies=all` gives 9, the other copies under their song with *same recording · MusicBrainz* / *sounds the same*
      / *you said so*.
    - `facets_test_the_copy_shown_when_folded_and_each_file_with_every_copy`.
    - `artist_songs_fold_album_rows_keep_their_own_tracks_and_health_counts_files`.
    - `bonus_after_the_version_chips_per_copy_with_every_copy`.
19. Extend `MusicTriageTest.every_music_key_count_equals_its_list`: the library gains a WMA copy folded behind a FLAC
    song, and the `music_reencodes` count still equals its list, because `filter=` forces `copies=all` (293's promise).
20. `MusicSameSongDashboardTest` (FR-305-14):
    - `three_pairs_then_none`: the `music_same_songs` row is info, unit *pair* and fix here, with an *opens* action,
      not a post. Each Yes or No lowers the count; at zero the row is absent.
    - `no_never_returns_the_pair`.
    - `a_pair_carries_the_offset_so_both_players_start_together`.
    - `a_file_a_browser_cannot_play_has_its_player_disabled_with_the_reason`.
21. `MusicSingleHomeStoreTest` (FR-305-15). Tests: `the_artist_page_keeps_six_singles_and_eps` (two on no album, the
    soundtrack single, three EPs); `singles_under_names_the_album_and_count`; `a_homed_live_single_leaves_the_live_group`.
22. `MusicTvSongCopyTest`: the viewer side of FR-305-10 / item 8, which R373's tests build on.
    - `the_copy_shown_is_the_best_this_viewer_may_see`: the FLAC is in a library the viewer cannot open, so the
      viewer gets the MP3, and the count leaves out the hidden copy.
    - `play_count_sums_last_played_is_latest_favourite_is_any`.
    - `unfavouriting_a_folded_row_clears_every_favourited_copy`: a loopback fake Jellyfin records one `DELETE` per
      copy, as `JellyfinSessionBridgeIntegrationTest`'s fake does.

**Only a real library scan can confirm** these. Run them once on the dev stack, with the owner's go-ahead for that
deploy. That run is the only time MusicBrainz is called, through the client's 1 request/s. Read the results from the
admin pages and the `/api/music` answers, and keep the album names out of commits and reports:
- the household's numbers: 487 → 385 songs, 102 copies in 78 groups (98 by recording, 4 by audio), and 3 suggestions;
- the extras 1 · 1 · 12 · 1 · 10, and the live album's 1;
- 43 of 49 singles homed, with B-sides 25 · 19 · 13 · 9 · 9 · 6 · 2 · 0 · 0;
- the three albums with 26, 27 and 30 pressings fully listed in Find match…;
- that the measured threshold makes exactly the 4 audio joins and the 3 suggestions, with no false join. Spot-check
  by ear, in the Dashboard modal;
- that the first `compare_songs` run takes minutes, not hours (the research measured 7.5 min for 387 tracks), and a
  second run with no changes is near-instant.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `MusicBrainzClient`, `MusicMatchService` (the ladder, `applyMatch`, `catchUpVersionFacts`), `MusicScoring`,
`MusicIngest.carry`, `MusicStore.Snapshot`, `MusicVersions.keyOf` and `MusicVersionIndex`, `MusicBrowse`, `MusicTriage`,
`DashboardRoutes`, `MusicRoutes`, `MusicTvService`, the Jellyfin music fetch, `FfmpegRunner.computeFingerprint`,
`FingerprintService` and `SegmentDetection`. The design holds, and much of it reuses what 276 and 292 built. Twelve
items; item 11 is for the owner.

1. **Rule 1 is already built.** FR-305-8 rule 1 is exactly `MusicVersions.keyOf` (`model/MusicVersions.kt:105-108`:
   `rec:<mbid>` when the track `agrees` or is `manual`, else `trk:<id>`), and `MusicVersionIndex.copies(t)`
   (`music/MusicVersionIndex.kt:14-27`) already groups the live tracks by it. So a song is: keyOf groups, joined by
   sound (rule 2) and by the owner's `same`, minus the owner's `not_same`. FR-305-11 then holds by itself, because 292's
   versions keep riding keyOf.

2. **Corrections about the current code.**
   - **a. A track's recording is the release's, not the file's tag.** `applyMatch` (`MusicMatchService.kt:286-297`)
     writes the recording that the matched release has at the same disc and position. The track `agrees` when its
     length is within ±3 s (`MusicScoring.kt:34-50`). A `disagrees` track still carries that release's recording id.
     Rule 1 must trust only `agrees` and `manual`, as written. The extra test (item 3) can use the id whatever its
     state, because it is that release's own track.
   - **b. There is no bit depth.** `JellyfinAudioStream` (`auth/Models.kt:518-524`) reads codec, bitrate, sample rate
     and channels, and `MusicTrack` (`model/Music.kt:263-267`) keeps the same four. FR-305-10's second key needs
     `BitDepth` on the stream, a `bitDepth` field on `MusicTrack`, and the field added to `MusicIngest.carry`'s list of
     Jellyfin-owned fields (`MusicIngest.kt:174-181`). The next scan fills it. No migration is needed (JSON blob).
   - **c. There are no ratings.** Neither jellystructure nor Ravilo has a song rating. FR-305-10's numbers are play
     count, last played and favourite: Jellyfin's, per viewer (`MusicTvService.UserMusic`, `:97-116`). Nothing is
     "written back". Ravilo already reports a play on the track id it played, so Jellyfin records it on that copy.
   - **d. Pressings are capped at 25.** `releasesOf` (`MusicBrainzClient.kt:214-215`) is one browse page, `limit=25`,
     no offset, and `MbReleaseBrowse` (`:129`) reads no `release-count`. Find match…'s pressing list
     (`MusicMatchService.releases`, `:350-357`) and `bestRelease` already lose pressings beyond 25 (290's build notes;
     three household albums have 26, 27 and 30). FR-305-2's vote needs every official release, so paging comes first
     (item 4).
   - **e. A live single is typed `live`.** `MusicBrowse.albumType` (`MusicBrowse.kt:63-72`) checks the secondary types
     before the primary one, so a single with *Live* is `live` and a box set is `compilation`. FR-305-2 and FR-305-6
     must test `primaryType`, not `albumType`: Album without *Compilation* gets an official tracklist; Single or EP gets
     homed. FR-305-15's "leaves *Singles & EPs*" must also cover a homed live single, which today sits under *Live*.
   - **f. "Computed, never stored" holds for the groups, not for the sound.** Comparing fingerprints takes minutes on a
     first run, and the snapshot is replaced on every write (`MusicStore.kt:84-120`). The comparison results must be
     stored (item 3), and the groups computed from them on read.

3. **Where it is kept.** This replaces FR-305-1's five tables:
   - **On `MusicAlbum`'s JSON** (no migration; `MusicIngest.carry` lists only Jellyfin's fields, so a scan keeps
     anything a phase adds, `MusicIngest.kt:168-171`): `official` (ordered recording ids, each with title, length and
     disc/position for gap rows; the release it was read from; *k*; *m*; when it was read), `extraOrigins` (recording
     id → first release's title, date, country, disambiguation), `singleFrom` (the release group the single is *single
     from*) and when the relationships were read.
   - **Extras are computed on read.** A held track is an extra when its recording id is not in `official`. A track
     with no recording id on a matched album is an extra too (it is not shown to be on the official album).
   - **One migration** (`66.sqm` at HEAD; take the next free number at build time) for the owner's rows only, 292's
     precedent (`music_version_choice`, which no scan writes): `music_official_pick(album_id PK, release_mbid,
     release_group_mbid, set_at)`, `music_single_home(album_id PK, home_album_id NULL = No album, set_at)`,
     `music_same_song(track_a, track_b, state same|not_same, set_at, PK(track_a, track_b), a < b)`. Answered
     suggestions are the same rows. Add one measurement table: `music_sound_pair(track_a, track_b, ber, coverage,
     offset_ms, measured_at)`.
   - **Fingerprints go on disk, not in SQLite**, like `FingerprintService` (`media/FingerprintService.kt:12-20`: a raw
     fingerprint is tens of KB). Use `fingerprints/music/<track id>-<size>-<mtime>.json`. A changed file misses the
     cache by its name.
   - **A song index on the snapshot**, lazy like `versions` (`MusicStore.kt:47`).
   - **What *not the same* does.** Rule-1 groups are cliques, so removing one pair would leave the copy joined through a
     third. A `not_same` takes the named copy out of every automatic group it is in. Only an explicit `same` brings it
     back. That is what "splits it off" in the acceptance needs. Versions still follow the recording (292 Q7).
   - **The owner's pick** survives scans and re-matches to the **same** release group. A re-match to another group, or
     *Clear match* (`MusicMatchService.clear`, `:393-404`), drops it with a History line, because its pressing is no
     longer one of the album's.
   - Owner rows are keyed by Jellyfin track and album ids, like 292's `trk:` keys. A file that Jellyfin re-creates
     under a new id loses its decision. 292 has the same limit.

4. **MusicBrainz: feasible and cheap.**
   - **Paging.** `releasesOf` becomes `/release?release-group=X&status=official&inc=recordings+media+labels&limit=100&offset=N`,
     advancing by what came back until `release-count` (add `release-count`/`release-offset` to `MbReleaseBrowse`;
     MusicBrainz may return fewer releases than the limit when recordings are included). `status=official` is a browse
     filter, so bootlegs and promos cost nothing. Find match… uses the same paged call, which fixes item 2d.
   - **Cost.** One or two requests per album: about 15–30 for the household's albums, once. Read it in a catch-up pass
     at the end of `match_musicbrainz`, like `catchUpVersionFacts` (`:219-225`), because that pass also covers locked
     albums (`matchAlbums` skips them, `:152`). Read again on an `all`-scope run and when the group changes.
   - **The vote.** Flatten every medium. Drop video recordings and DVD/Blu-ray media first (add `video` to
     `MbRecording`; `format` is already on `MbMedium`). Otherwise a CD+DVD deluxe majority would put videos in the
     official list as gap rows. Vote on the **set** of recording ids (the research's measured method). Take the order,
     discs and titles from the earliest release with the winning set. **Ties go to the smaller set, then the
     earliest**, because the bonus pressing is the bigger one and is often the earliest (research §2). FR-305-2's "ties
     go to the earliest release" should say so.
   - **Numbering.** One medium: 1…n. More than one: disc · track, as on the official release.
   - **`first_release`** comes from the same pages: the earliest dated release that carries the recording. An extra on
     no release of the group costs one `recording(mbid)` (`:243`, already `inc=releases`). That is rare.
   - **The edition's name** (admin, English): the earliest such release's title when it differs from the group's, else
     its disambiguation (*super deluxe*), else its country (*Japanese edition*).
   - **Singles.** Add `release-group-rels` to `releaseGroup()`'s `inc` (`:210-211`). `applyMatch` already calls it on every
     match and refresh, so there is no new call path: one extra request per group, once (the URL changes), then free.
     `MbRelation` needs a `release_group` target (`@SerialName("release_group")`). Check the direction of *single from*
     on a live answer, as 292 did; the single should be the first entity (`forward`). Matched singles and EPs without
     the relationships are caught up in the same pass: about 49 requests, once.
   - **Rule 2 (`via_remix`)** reads 292's `MusicRecordingFacts.remixOf`, already fetched for every matched album, so it
     costs no request. The research's two cases went *remix of → the original single → single from*. When the remix
     points at the single's own recording rather than the album's, rule 2 as written misses. Follow that hop too (target
     recording → the held single it is on → that single's `singleFrom`). It costs nothing and matches the research.
   - **Rule 3** compares base titles (292's title-finder normalisation) and applies to **singles only**. Research §5
     never applies it to EPs, and the acceptance's *Singles & EPs shows 6* (two singles on no album, one soundtrack
     single, three EPs) depends on that. FR-305-6's last sentence should read "EPs follow rules 1, 2 and 4".
   - **Another artist's single** is detected by comparing the release groups' artist-credit MBIDs (`mbArtists`).

5. **Which pairs get fingerprinted.** As written, FR-305-9 fingerprints only untrusted tracks and their partners, so
   FR-305-14's suggestions (two trusted, different recordings) could never be found. Use the research's selection:
   candidate pairs are the same artist (MBID, else Jellyfin artist id) with the same base title, not already one song
   by rule 1. If either side is untrusted, the pair is rule 2. If both are trusted with different recordings, it is a
   suggestion. Title only picks the pairs and never decides (FR-305-8 holds).

6. **The audio check (answers open question 1).** Chromaprint has no "similarity" score, and a 30 s window can sit
   entirely in a backing track that a remix kept. Use the research's measured rule over **full-length** raw fingerprints
   (`FfmpegRunner.computeFingerprint(path, windowSec = 0)` runs `fpcalc -raw -length 0`): best alignment within ±10 s,
   bit error ≤ 0.15 over the overlap, and ≥ 95 % of the longer song lined up. Reuse `SegmentDetection`'s popcount (private
   today, `SegmentDetection.kt:175`) and the offset search of `findIntroMatch` (`:221`) with a whole-song scorer. Store the bit error, the coverage and the
   offset; the offset is what makes FR-305-14's two players start "from the same second". Run it through
   `ProcessGate`'s background class, as 276's AcoustID fingerprints are. Cost, from the research: 387 tracks in 7.5 min
   at four at a time on the first run; after that only new or changed files.

7. **The pipeline.** Add a music step `compare_songs` (fingerprints and comparisons, no network) after
   `match_musicbrainz`: in `MusicSteps.ALL` (`config/AppConfig.kt:178-193`), seeded by `MusicSteps.seed`, and in the
   built-in default pipeline. Keeping it separate lets 154's pre-run dialog untick the heavy half. 303's
   `WholeLibrarySteps` keeps it off a title's Checks card by itself (`MusicSteps.isMusic`). Official tracklists and
   homes ride `match_musicbrainz`'s catch-up (item 4).

8. **The copy shown (FR-305-10).**
   - Lossless means `flac`, `alac`, `wav`/`pcm_*`, `ape`, `wavpack` or `tta`.
   - The copy is chosen **per viewer** among present, visible copies. `MusicTvService.View` filters by allowed
     libraries (`:80-92`), so a copy in a library the viewer cannot open is never shown or counted for them. The admin
     chooses over every library.
   - Favourite: the song is a favourite when any visible copy is. Un-favouriting a folded row must clear **every**
     favourited copy (the server fans out from `/tv/music/favorite`, `MusicTvRoutes.kt:91-101`), or the union puts it
     straight back. Play count is the sum; last played is the latest.
   - Open question 2: fine as leaned. It is a sum at read time, and Jellyfin is untouched.

9. **The admin.**
   - `MusicAlbumPageDto` (`model/MusicApi.kt:308-333`) gains the official line (*k*, *m*, the pick), `gaps`,
     `singles` (with how each is linked), `bsides` (as `MusicTrackRow`s with their single) and `single_from`.
     `MusicTrackRow` gains `extra`, `first_on` and `number` (the official number). The server orders the rows; the page
     renders what it gets.
   - Routes: `GET /api/music/album/{id}/releases?official=1` (the paged list with *against the official*, `pickable`
     and *+ 13 not in the library*), `PUT|DELETE /album/{id}/official`, `PUT|DELETE /album/{id}/home`,
     `GET /track/{id}/copies`, `PUT /same-song {a, b, state}`, `GET /same-song/suggestions`.
   - **Library → Songs.** The server folds (`MusicBrowse.browse`, SONGS, `MusicBrowse.kt:119-124`). *Show every copy*
     is `copies=all` on the request, remembered in localStorage (`js-music-copies`, like `js-theme`). The client never
     folds (constitution: the frontend renders server-pushed state only).
   - **A Dashboard key opens every copy.** `MusicTriage`'s song keys (`music_reencodes`, `music_files_no_ids`,
     `music_instrumental_lyrics`, `MusicTriage.kt:28-38`) count files, and 293 promises the Dashboard's count equals the
     list it opens. A folded list would hide a WMA copy behind its FLAC song. So `filter=` forces `copies=all`.
   - Facets on the folded list test the copy shown; with every copy, each file.
   - **Counts.** `MusicArtistRow.songs` and the artist page's `songs` fold. Album rows keep their own tracks (*{n} songs
     + {k} extras* in the cell). `MusicStore.health()` and every file count stay files (FR-305-3).
   - **The Dashboard row** `music_same_songs`: music, info, unit *pair*, fix here. Today's music rows are triage keys
     that open the Library (`DashboardRoutes.kt:111-117`). This one opens a modal, so `DashboardRow` needs an action
     the page opens rather than posts. The count is the open suggestions (pairs with no decision). The players use
     `/api/music/track/{id}/stream`. A file a browser cannot play (`MusicBrowse.browserPlays`) shows its player
     disabled, with the reason, as the Tracks tab does.
   - **FR-305-15.** `MusicArtistPageDto` gains `singles_under` (album id, title, count). A homed single leaves
     whichever group holds it (item 2e).

10. **Split it.** It is large. Three builds, each shippable on its own:
    - **305a** — paged pressings, the official tracklist, extras, the owner's pick, the album page, bit depth;
    - **305b** — single homes, B-sides, *Move to…*, the artist page's line;
    - **305c** — fingerprints, `compare_songs`, the song index, folded Songs, the copies panel, the Dashboard row.

    R373 follows: its album page needs a and b, its lists need c.

11. **For the owner.**
    - **Q-A — Bonus on a folded row.** The shown copy can be an extra on one album while the same recording is official
      on another (a live cut on an anniversary edition and on the live album), and the better file decides which copy
      is shown. **Lean: a folded row says *Bonus* only when no copy of the song is on an official tracklist**, so a song
      that is official somewhere is never called bonus. *Show every copy* and the album page stay per copy, as drawn.
    - **Q-B — A file a phone has to re-encode.** A 192 kbps WMA beats a 160 kbps MP3 on bitrate, but Ravilo plays it
      only by re-encoding (`MusicFormats.reencodesOnPhone`). **Lean: a copy that re-encodes ranks below every copy that
      plays as it is**, before bitrate.
    - **Q-C — The edition's name in Ravilo's languages.** *Japanese edition* is built from a country, and the viewer
      may read Danish or Faroese. **Lean: MusicBrainz's own title or disambiguation as it is (*super deluxe*, *20th
      Anniversary*), like an album title; else the country's name in the viewer's language (*Extras · Japan*); else
      *Extras* alone.**
    - Open question 3 stands as leaned.

12. **Shipped issues found, not fixed.** Only the 25-pressing cap (item 2d), which was already known from 290. It hides
    pressings from Find match… today, before any of this is built.


## Owner decisions (2026-10-04, after the dev review)

1. **Bonus on a folded row only when no copy of the song is on any official tracklist.** *Show every copy* and the album
   page stay per copy.
2. **The shown/played copy is chosen by bitrate first** (then bit depth, then the existing tie-breaks); whether a phone
   must re-encode it does **not** rank it lower. (The dev review's lean is declined.)
3. **The edition's name:** MusicBrainz's own title or disambiguation as it is (*super deluxe*, *20th Anniversary*);
   else the country in the viewer's language (*Extras · Japan*); else *Extras*.


## Build notes

**Built 2026-10-05** as one build covering 305a, 305b and 305c (they share the song index and the album page, so they
landed together). Not deployed; no real-library run yet.

**305a — official tracklists.** `MusicBrainzClient.releasesOf` now pages `status=official` browses (`limit=100`,
offset by what came back, until `release-count`; an empty page stops; a failed later page answers null), which also
fixes Find match…'s 25-pressing cap (item 12). A group with no official pressing falls back to the old single
unfiltered page, so a bootleg-only match still works. `MbRelease`/`MbRecording.video`/`MbRelation.releaseGroup`/
`release-count` decoded; `releaseGroup()` asks for `release-group-rels`. Pure `MusicOfficial` (`music/MusicOfficial.kt`):
the vote on the recording-id **set** (video media and video recordings dropped, ties → smaller set → earliest), extras,
gaps, numbering (`n` or `disc·track`), the edition name (owner decision 3: title ≠ group's → disambiguation → country
code → nothing), first pressings, *against the official*. The lists ride the album JSON (`official`, `userOfficial`,
`extraOrigins`, `singleFrom`, `relsReadAt`); read in `match_musicbrainz`'s catch-up (`readEditions`, also for locked
albums; an `all` run re-reads locked ones, unlocked ones are re-read by their match) and after every `applyMatch`.
The owner's pick: `GET /album/{id}/releases?official=1` (every pressing, `pickable`, *+ N not in the library*),
`PUT|DELETE /album/{id}/official`; a re-match to another group or *Clear match* drops it with a History line.
`BitDepth` is read from Jellyfin and carried as a Jellyfin-owned field (next scan fills it). The album page DTO is
built by the pure `MusicAlbumPage.build`.

**305b — single homes.** `MusicSingleHome.resolve`: *single from* → a remix (with the hop through the held single the
remix points at) → by title (singles only, ≤ 2 years, same artist by MBID else Jellyfin id) → the owner's *Move to…*
(`PUT|DELETE /album/{id}/home`, `null` = *No album*). B-sides exclude any track folding into one of the album's songs
and the A-side when its base title is an album song's. The artist page drops homed singles from whichever group held
them and gains `singles_under`.

**305c — one copy of every song.** `compare_songs` (`MusicCompareSongsStep`, `music/MusicCompareStep.kt`): whole-song fingerprints (`fpcalc -raw -length 0` through
`ProcessGate`, the step runs under the background class) cached at `fingerprints/music/<track>-<size>-<mtime>.json`;
candidate pairs = same artist + same base title, not already one recording, not decided; scored by
`MusicSoundMatch` (±10 s, BER ≤ 0.15, coverage ≥ 0.95 — the dev review's measured rule; the threshold is a constant
to re-check on the household's 4 joins and 3 suggestions). `compare_songs` is seeded straight after
`match_musicbrainz` and is in the built-in pipeline; the wasm pipeline dialog and Activity name it. `MusicSongIndex`
(lazy on the snapshot): keyOf groups ∪ sound joins (one side untrusted) ∪ the owner's *same*, minus *not the same*
(the lower-ranked copy of the pair leaves every automatic group — what the panel's button names); trusted-and-different
pairs that sound alike are suggestions. `MusicCopyRank`: bitrate, bit depth, sample rate, then album · extra · single ·
compilation/box · live, then earliest added (owner decision 2; re-encoding does not demote). Bonus on a folded row =
some copy is an extra and no copy is official (owner decision 1). Library → Songs folds (`copies=all` lists every file;
a Dashboard key always does, so 293's counts hold); `GET /track/{id}/copies`, `PUT /same-song`,
`GET /same-song/suggestions`; Dashboard row `music_same_songs` (info, unit *pair*, fix here, `opens = same_songs`).

**Migration 66** (`66.sqm` + `MusicEditions.sq`): `music_official_pick`, `music_single_home`, `music_same_song`,
`music_sound_pair`. ⚠ Another branch may also take 66 — renumber on merge if so.

**Admin (wasm):** `ui/MusicEditionsUi.kt` + edits to `MusicAlbum.kt`, `MusicLibrary.kt`, `MusicArtist.kt`,
`Dashboard.kt`, `api/MusicApi.kt`; `design/app/editions.css` is now served (`build.gradle.kts`, `index.html`).
*Show every copy* is remembered in `js-music-copies`.

**Tests** (`src/linuxX64Test/kotlin/dev/jellystructure/music/`, all green): `MusicOfficialTest`, `MusicExtrasTest`,
`MusicEditionNameTest`, `MusicCopyRankTest`, `MusicSongIndexTest`, `MusicSingleHomeTest`, `MusicSoundMatchTest`,
`MusicPairCandidatesTest`, `MusicBrainzEditionsClientTest` (loopback MusicBrainz, `MbEditionsFixtures.kt`),
`MusicEditionsCatchUpTest`, `MusicEditionsStoreTest` (incl. the one-version-old upgrade), `MusicIngestTest` (bit depth),
`MusicFingerprintCacheTest`, `MusicCompareSongsTest` (background class asserted), `MusicAlbumPageTest`,
`MusicOfficialPickTest`, `MusicEditionsRoutesTest` (`testApplication`), `MusicSongFoldTest`, `MusicTriageTest`
(folded WMA still counted), `MusicSameSongDashboardTest`, `MusicSingleHomeStoreTest`, `MusicTvSongCopyTest` (loopback
Jellyfin: one `DELETE` per favourited copy), plus the existing music tests (`MusicMatchTest`'s seed test now includes
`compare_songs`). Stand-in data in `EditionsFixture.kt`. Leans taken: Chromaprint rule as the dev review measured;
an unknown bitrate ranks below every known one; a pressing whose extras aren't held is listed, not pickable.

**Only the real library can confirm** (needs the owner's go-ahead for a dev-stack run): the household's numbers
(487 → 385 songs, 102 copies, 3 suggestions; extras 1·1·12·1·10; 43 of 49 singles homed; B-sides), that the three
26/27/30-pressing albums list fully, that the threshold makes exactly the 4 audio joins and 3 suggestions, and that the
first `compare_songs` run takes minutes and the second is near-instant. The *Listen and decide* players start at the
same second only as far as Jellyfin's direct stream allows a `currentTime` seek.
