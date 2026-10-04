# Phase R373 — The official album, its extras and B-sides, and one copy of every song in Ravilo

> Owner, 2026-10-03/04 (see 305). In Ravilo: *Play album* plays the official album; a menu beside it offers *Play album
> + extras*, which **also plays the B-sides** (owner, Q3); Shuffle has the same menu (Q2); extras are unnumbered (Q4);
> lists show one copy of every song, the better file (Q5); a **Bonus** chip after the version chips (Q6).

## Status

`✓ Built 2026-10-05` · **dev-reviewed 2026-10-04** (against `main` `5210045a`; see the end) · built (see Build notes) · pending export. Written
2026-10-04 (design-authored) beside **admin 305**, from
`specs/design-brief-music-editions-and-duplicates-2026-10-03.md` and the mockups:
- `design/ravilo/mobile/ravilo-editions.js` (`window.RaviloEditions`), used by `Ravilo Mobile.html` (`re-*` in
  `mobile/ravilo-music.css`) and `Ravilo Desktop.html` (`dx-ed*` in `desktop/ravilo-desktop.css`); both load
  `../app/editions.js` for the stand-in data
- the round-1 canvas `design/app/Music Editions - Directions.html` (D1 **A** picked)

Number checked free on `main` tree `fdbc126` (Ravilo tops at R367; our sessions specs hold R368–R372).

**Builds on** R321/R326 (the listening mode), R322 (the player, queue, Now playing), R337 (the desktop's layout), R344
(read-only version chips) and **305** (the server-side model and rules). **Not on the TV** (no music mode).

## Requirements

**FR-R373-1 — The wire.** `/api/tv/music/**` (279) gains, from 305's tables:
- on an album: `official` (ordered track ids), `extras` (ordered track ids), `edition` (*Japanese edition*),
  `singles` (release-group ids, year order) and `bsides` (track ids, single order, A-sides excluded) — all absent for
  an unmatched album;
- on every track: `extra: true` when it is one (drives *Bonus*), and `copies` (the other track ids of the same song);
- on a single: `home_album` when homed;
- every list endpoint that spans albums (songs, an artist's songs and top songs, search, *Shuffle all*) returns **one
  track per song** — 305's FR-305-10 shown copy.
The app computes nothing itself.

**FR-R373-2 — The album page**, phone and desktop, top to bottom:
1. header *{n} songs · {length}* of the official album, then **+ {k} extra(s)** in the dimmer ink;
2. on a single's own page: *Single from {album}* (tappable);
3. **Play album ▾** (primary) and **Shuffle ▾** as split buttons. The ▾ menu: **Play album** (*{n} songs · the
   official order*) · **Play album + extras** (*{n} songs · extras, then the B-sides*), the current one ticked. The
   pick is remembered **per album, per device**, relabels the Play button (*Play album + extras*), and both buttons
   follow it; picking from the menu also starts playback;
4. the official songs, numbered in the official order;
5. a thin divider **Extras · {edition}**, then the extras **unnumbered** — no Bonus chip here (the divider says it);
6. **Singles & B-sides**: the single cards (cover, title, *{year} · Single*), each opening its single; then one fold
   row **{n} B-sides · Show** (*Hide* when open), listing the B-sides with *from {single} (single)* as a second line;
7. *More from {artist}* — without the singles that live on this album.
A tap on any row plays from it inside the current pick's order (*Play album + extras* = official → extras →
B-sides). An unmatched album is today's page.

**FR-R373-3 — One copy in lists.** Songs, search, an artist's top songs and the desktop's Songs page use the server's
folded lists (FR-R373-1); the counts follow (*385 songs*). An album page always lists its own tracks.

**FR-R373-4 — Also on.** The phone's song ⋯ sheet ends with **Also on {n} releases** (*Also on 1 release*) and one
row per other copy (cover · title · *{kind} · {year}*), each opening that release. The desktop's song context menu gets
the same list (not drawn; same rows).

**FR-R373-5 — The Bonus chip.** R344's chip family, no hue (ink on a neutral fill), after the version chips, **never
folded into *+N*** (it is another axis). Shown on song rows in lists, queue rows and Now playing's line; **not** on
an album's own extras section, the mini bar, the lock screen / media session or the TV.

**FR-R373-6 — Chips sit at the row's right** (owner, 2026-10-04): on Ravilo song and track rows the version chips and
Bonus sit just left of the length (desktop: their own column before *Length*, a fixed width on the Songs table so the
columns line up; phone: the right end of the title area, beside the length / ⋯). The title keeps the space and
ellipsises first. Now playing's line keeps them before the artist · album text. (The admin keeps them after the title.)

**FR-R373-7 — Artist page** (Q1): homed singles and EPs leave *Singles & EPs*; under the section a line *{n} singles
live under their album — {album} · …*, each album tappable.

## Strings

`ed.play_album · ed.play_extras · ed.extras · ed.n_extra · ed.n_extras · ed.bonus · ed.singles · ed.n_bsides ·
ed.one_bside · ed.show · ed.hide · ed.from_single · ed.single_from · ed.also_on · ed.also_on_one · ed.sub_album ·
ed.sub_extras · ed.under · ed.album · ed.more_play` × en/da/fo in the mockup's `ravilo-i18n.js` (**da/fo are drafts**;
the shipped `i18n/*.json` wins). The desktop mockup is English like the rest of it.

## Open questions (with leans)

1. **The ▾ pick across devices** — lean per device (it is a listening habit, not a library fact).
2. **Casting with *Play album + extras*** — lean: the receiver gets the expanded queue (R359's long-queue rules apply).
3. **Desktop context menu for *Also on*** — lean the same rows as the phone's sheet; not drawn.

## Acceptance

- *Kite Weather*: *11 songs · 48 min + 1 extra*, *Extras · Japanese edition*, *Lantern Swing* unnumbered, five single
  cards, *13 B-sides · Show*.
- *Play album + extras* queues 11 + 1 + 13 = 25 tracks in that order; the button relabels and is remembered.
- Songs lists each song once; *Lantern Swing* carries *Bonus* there and in the queue, not on the mini bar.
- *Northern Line*'s ⋯ sheet: *Also on 3 releases*.
- Harbour Lights' *Singles & EPs* no longer shows the five *Kite Weather* singles.

In CI, on stand-in data (see **Tests**):
- line 1 → `MusicAlbumEditionsTest.kite_weather_on_a_phone` and `.the_same_page_on_the_desktop`. With 305's owner
  decision 3 the divider reads MusicBrainz's title or disambiguation, else *Extras · Japan*.
- line 2 → `AlbumPlayOrderTest.play_album_plus_extras_is_official_then_extras_then_b_sides` and
  `MusicAlbumEditionsTest.the_menu_picks_relabels_and_starts_playback`.
- line 3 → `MusicTvEditionsTest.lists_fold_per_viewer` and `TrackRowChipsTest`.
- line 4 → `SongSheetAlsoOnTest.northern_line_is_also_on_3_releases`.
- line 5 → `MusicArtistEditionsTest.homed_singles_leave_singles_and_eps`.

## Tests

The stand-ins are Harbour Lights, *Kite Weather* (11 + *Lantern Swing*), its five singles (*Northern Line*, *Fog
Bank*, *Salt on the Window*, *Low Tide* and *Tidewater*) and 13 B-sides, *Signal Found*, and a *Lighthouse Keepers*
soundtrack single. No real library names appear. The server's rules (the vote, the copy ranking, the fold, the bonus
predicate, the edition name, the pair selection) are tested once, in 305's **Tests**. Here we test only what crosses
the wire and what the app does with it.

**The wire (shared, `shared/src/commonTest/kotlin/dev/jellystructure/shared/tv/`)**

1. `MusicEditionsWireTest`, after `RaviloWireJsonTest` (FR-R373-1, review item 1):
   - `todays_album_payload_decodes_as_an_unmatched_page`: a `MusicAlbumDetail` without the new fields decodes with
     `official_ids = null`, empty `extra_ids`, `singles` and `bside_tracks`, and `single_from = null`. A
     `MusicTrackItem` decodes with `extra = false` and `also_on = 0`.
   - `a_matched_album_decodes_and_tracks_keep_file_order`.
   - `unknown_fields_from_a_newer_server_are_skipped`.
   - `the_copies_answer_round_trips`: a list of `{track, album card}`.
2. `WireCompatTest` (`shared/src/linuxX64Test/.../wire/`) stays green against every recorded release baseline. It is
   the proof that installed apps keep today's album page: fields are only added, and only as optional ones. The copies
   response joins `WireRoots.kt` (through `scripts/wire_roots.py`), so the next `record-wire-baseline.sh` records it.

**The server's answers (`src/linuxX64Test/kotlin/dev/jellystructure/music/`)**

3. `MusicTvEditionsTest`: a real `MusicTvService` on a temp store, with 305's tables filled in directly. Viewers are
   built with `DeviceData(…, allowedLibraries = …)` as in `MusicTvServiceTest`.
   - `album_detail_carries_official_extras_singles_and_b_sides_in_order`:
     - 11 `official_ids`;
     - `extra_ids = [Lantern Swing]`;
     - `edition_country = "JP"`, `edition_title = null`;
     - 5 held single cards, in year order;
     - 13 full `bside_tracks` whose `album` / `album_id` name their single.
   - `tracks_keep_file_order_so_an_installed_app_shows_todays_page`.
   - `an_unmatched_album_has_no_official_ids`.
   - `a_singles_page_carries_single_from` (FR-R373-2 point 2).
   - `more_from_artist_leaves_out_the_singles_homed_here` (review item 7).
   - `artist_detail_moves_homed_singles_to_singles_under_whichever_group_held_them`: a live single leaves *Live*
     (FR-R373-7).
   - `lists_fold_per_viewer` (FR-R373-3, review item 2). Browse → Songs, search's songs, an artist's top songs, Listen's
     *Recently played* (deduplicated, order kept), the empty search's recent songs and `artistCard.track_count` fold.
     An album's tracks, a playlist and `last-played` do not.
   - `the_shown_copy_is_the_highest_bitrate_this_viewer_may_see`. A 192 kbps WMA beats a 160 kbps MP3, because
     re-encoding does not demote a copy (305 owner decision 2). A FLAC in a library this viewer cannot open is neither
     shown nor counted.
   - `extra_on_a_folded_row_only_when_the_song_is_official_nowhere` (owner decision; FR-R373-5); on an album's own
     `tracks` it is per copy.
   - `also_on_counts_the_other_visible_copies`.
   - `copies_lists_the_other_visible_copies_and_a_hidden_track_is_not_found` (`GET /tv/music/track/{id}/copies` goes
     through `visible()`).
   - There is no *Shuffle all* route (review item 1). Nothing to test.

**The app, pure (`ravilo-ui/src/commonTest/kotlin/dev/jellystructure/ravilo/ui/music/`)**

4. `AlbumPlayOrderTest` tests `albumQueue(detail, pick)` and `queueForTap(detail, pick, trackId)`, which resolve the
   wire's ids against `tracks` and `bside_tracks` (FR-R373-2, review item 3):
   - `play_album_is_the_official_order` (11).
   - `play_album_plus_extras_is_official_then_extras_then_b_sides`: 11 + 1 + 13 = 25, in that order.
   - `a_tap_inside_the_pick_plays_from_that_row`.
   - `a_tap_on_an_extra_or_b_side_under_the_plain_pick_plays_the_extended_order_and_keeps_the_pick`.
   - `an_unmatched_album_plays_tracks_in_file_order_whatever_was_stored`.
   - `the_header_length_sums_the_official_ids`.
   - `an_id_missing_from_the_payload_is_skipped`: this pins the build's choice; the app never crashes on a short list.
5. `EditionLabelTest` tests `editionLabel(title, country, lang)` (305 owner decision 3, review item 8):
   - `musicbrainz_title_or_disambiguation_as_it_is`: *20th Anniversary*, *super deluxe*.
   - `else_the_country_in_the_viewers_language`: `JP` gives *Extras · Japan*; `DE` gives *Germany* / *Tyskland* /
     *Týskland*. The country names are a new table; none exists in `i18n/*.json` today.
   - `an_unknown_code_or_nothing_is_extras_alone`.
6. Extend `VersionChipsTest`:
   - `bonus_is_never_folded_into_more`: four version keys plus `extra` on a phone give two chips, *+2* and Bonus.
   - `bonus_has_no_hue_and_meets_contrast_in_every_theme`, using the file's `worstContrast` helper.

**The app, Robolectric (`ravilo-ui/src/androidUnitTest/kotlin/dev/jellystructure/ravilo/ui/music/`)**

The screen tests use `createComposeRule()` with `@Config(sdk = [34])`. The phone runs on the default qualifiers; the
desktop branch runs under `LocalLayoutFamily provides DESKTOP`, as `SeriesDetailFocusTest`'s `Platform` helper does.
Data comes from a fixed `MusicLoader { … }`, or from `fakeTvApiClient` for routes. Playback is caught through a new
`onPlayQueue` parameter on `MusicAlbumScreen` that defaults to `MusicPlayback::playQueue`, so no engine runs.

7. `AlbumPickStoreTest` (review item 4) runs against the real Android `MusicDeviceStore`:
   - `the_pick_is_per_album_and_per_device`.
   - `forget_listening_clears_it`.
   - `a_garbled_value_reads_as_the_plain_album`.
8. `MusicAlbumEditionsTest` (FR-R373-2):
   - `kite_weather_on_a_phone`:
     - the header reads *11 songs · 48 min* + *1 extra*;
     - the rows are numbered 1–11;
     - the divider reads *Extras · Japan*;
     - *Lantern Swing* has no number and no Bonus chip;
     - there are 5 single cards;
     - *13 B-sides · Show* opens 13 rows with *from Northern Line (single)*, and *Hide* closes them.
   - `the_same_page_on_the_desktop`.
   - `the_menu_picks_relabels_and_starts_playback`. The ▾ menu lists both items with their sub-lines. Picking *Play
     album + extras* sends the 25-track list (and Shuffle the same list with `shuffle = true`) and relabels the button.
     A fresh composition reads the remembered pick.
   - `an_unmatched_album_is_todays_page`: there is no ▾, no divider and no *Singles & B-sides*; Play gets `tracks` in
     file order.
   - `a_singles_page_says_single_from_and_opens_the_album`.
9. `TrackRowChipsTest` (FR-R373-5, FR-R373-6):
   - `chips_sit_between_the_title_and_the_length`: checked on the nodes' bounds.
   - `at_phone_width_the_title_ellipsises_the_versions_fold_and_bonus_stays`.
   - `the_queue_panel_shows_bonus`.
   - `the_mini_bar_shows_no_bonus`.
   - `now_playing_keeps_its_chips_before_artist_and_album`.
10. `SongSheetAlsoOnTest` (FR-R373-4), with `fakeTvApiClient` answering `/api/tv/music/track/{id}/copies` and counting
    the calls:
    - `northern_line_is_also_on_3_releases`: the sheet ends with *Also on 3 releases* and three rows (cover · title ·
      *Single · 2006*); a tap opens that release.
    - `one_copy_reads_also_on_1_release`.
    - `also_on_0_draws_no_section_and_asks_nothing`.
    - `a_failed_copies_call_leaves_the_rest_of_the_sheet`.
11. `MusicArtistEditionsTest`:
    - `homed_singles_leave_singles_and_eps` (FR-R373-7): the line *5 singles live under their album — Kite Weather*
      opens the album.
    - `the_songs_list_renders_what_the_server_sent`: two same-titled rows both render. The app folds nothing.
12. The new `ed.*` keys are in en, da and fo. `scripts/check-ravilo-strings.sh` and `scripts/check-i18n-spelling.sh`
    stay green, and the Faroese follows R288's lexicon.

**Only real devices on the dev stack can confirm** these, once 305a–c have run on the real library and with the owner's
go-ahead for devices and deploys:
- the Pixel and the Mac app show the household's folded counts (*385 songs*) and the real albums' extras and B-sides;
- the previous release's installed app shows today's album page against the new server;
- *Play album + extras* cast to a test speaker the owner allows queues the expanded order and splits per R359;
- the chips line up on the Mac's Songs table at Large and Medium window sizes.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `shared/…/tv/Music.kt`, `MusicTvService`, `MusicTvRoutes`, `MusicDetailScreens` (album and artist pages),
`MusicCommon.TrackRow`, `MusicPlayerScreens.QueuePanelRow`, `MusicCast.playQueue`, `MusicDeviceStore` and the
`i18n/*.json` tables, and alongside 305's review. The design holds. FR-R373-1's wire changes shape (item 1). Ten items.
The owner questions are 305's Q-A and Q-C (they decide what this page says), plus open question 1 here.

1. **FR-R373-1 changes shape. The app cannot use release-group ids or bare track ids.** The phone opens an album by its
   Jellyfin id, and the B-sides are tracks of *other* albums, which are not in `MusicAlbumDetail.tracks`. Every addition
   is optional with a default, per the file's own rule (`shared/…/tv/Music.kt:6-15`). No field is removed or changes
   meaning.
   - **`MusicAlbumDetail`** gains `official_ids: List<String>?` (null means unmatched: today's page), `extra_ids`,
     `edition_title`/`edition_country` (305 Q-C), `singles: List<MusicAlbumCard>` (the **held** singles, year order),
     `bside_tracks: List<MusicTrackItem>` (full items, single order; the existing `album`/`album_id` give *from
     {single}*), and `single_from: MusicAlbumCard?` (a single's own page, FR-R373-2 point 2). `tracks` keeps today's
     file order, so an installed app shows today's page.
   - **`MusicTrackItem`** gains `extra: Boolean = false` (305 Q-A decides what it means on a folded row) and
     `also_on: Int = 0`.
   - **Not `copies` on every track.** Up to ten ids on each of 60 rows per page is weight the sheet only needs on a tap.
     Add **`GET /tv/music/track/{id}/copies`** instead, answering the other visible copies as `{track, album card}`
     (cover, title, kind, year). It is checked with `visible()` like the other routes.
   - **`MusicArtistDetail`** gains `singles_under: [{album: MusicAlbumCard, count}]` (FR-R373-7). Homed singles leave
     `groups`, whichever group holds them (305 item 2e: a live single sits under *Live*).
   - **There is no *Shuffle all*.** `MusicTvRoutes.kt:41-91` has no such route, and the app has none. Drop it from the
     list.

2. **What folds, where** (`MusicTvService`). Browse → Songs (`:184`), search's songs (`:292`), an artist's top songs
   (`:268`), Listen's *Recently played* (`:155`, deduplicated by song, order kept) and the empty search's recent songs
   (`:287`). `artistCard`'s `track_count` (`:127`) folds. Never folded: an album's own tracks, a playlist (the viewer's
   own list), and `last-played` (it is the copy that was played). The copy shown is chosen among the copies **this
   viewer** may see (305 item 8). Installed apps get the folded lists, and fewer singles on an artist page without the
   new line. That changes content, not fields, so it is acceptable.

3. **The queue.** *Play album + extras* is `official_ids` → `extra_ids` → `bside_tracks`, in that order. The order is the
   wire's contract, so no rule lives in the app. Both buttons pass that list to `MusicPlayback.playQueue` (Shuffle with
   `shuffle = true`), as today (`MusicDetailScreens.kt:130-131`, `:153-154`). **A tap on a row outside the current pick**
   (an extra or a B-side while the pick is *Play album*) plays the extended order from that row and leaves the
   remembered pick alone. FR-R373-2's "inside the current pick's order" cannot reach those rows otherwise.

4. **The pick (open question 1).** Kept in `MusicDeviceStore` under one key (`album_pick` → a map of album id →
   `extras`), per device, as leaned. `forgetListening()` (`MusicDeviceStore.kt:42`) clears it with the mode and the
   queue, so the next viewer on the phone starts with the plain album. An unmatched album ignores the stored pick.

5. **Casting (open question 2, answered).** The phone builds the queue and `MusicCast.playQueue`
   (`MusicCast.kt:282-291`) sends it. R359 splits a long queue. Nothing to add.

6. **FR-R373-6 amends R344 FR-R344-3 and R352 FR-R352-1.** Say so in the requirement. The chips move from after the
   title to just before the length, in `TrackRow` (`MusicCommon.kt:216`) and `QueuePanelRow`
   (`MusicPlayerScreens.kt:662`). R352's fold rule stays. **Bonus is a fixed chip outside the fold**: the version chips
   fold first (down to *+N*), then the title truncates, and Bonus is never cut. On the desktop's Songs table the chip
   column has a fixed width. Now playing keeps them before the artist · album line (R344 unchanged there).

7. **The album page** is one composable with two branches (`MusicAlbumScreen`, `deskWide` and phone,
   `MusicDetailScreens.kt:109-158`). Both get the split buttons. The header's length is the sum over `official_ids`, as
   today's header sums `tracks` (`:115`). *More from* (`moreFromArtist`, `MusicTvService.kt:246`) leaves out the
   singles homed on this album on the server.

8. **Strings.** `ed.*` is a new namespace in `i18n/*.json`; none exist yet. Reuse `music.songs_n` / `music.songs_one`
   for *{n} songs*. `music.singles` (*Singles & EPs*) is a different phrase from `ed.singles` (*Singles & B-sides*), so
   both stay. The Faroese follows R288's lexicon (`i18n/lexicon`). `ed.edition` replaces any built *Japanese edition*
   text (305 Q-C).

9. **Desktop context menu (open question 3, answered).** The same rows as the phone's sheet, from the same route.

10. **Out of reach, as intended.** The TV never calls these routes (no music mode). The mini bar, the lock screen and
    MPRIS draw no chips today, so FR-R373-5's "not there" needs no code.


## Owner decisions (2026-10-04, after the dev review)

Follows 305's decisions: *Bonus* only when the song is official nowhere; the copy shown is the highest bitrate
(re-encoding doesn't lower it); the extras section is named from MusicBrainz, else the country in the viewer's
language, else *Extras*. The ▾ pick stays per device.


## Build notes

**Built 2026-10-05**, on 305's server side (same branch). Not deployed; no device run.

**The wire (FR-R373-1 as the dev review reshaped it).** Optional fields only: `MusicAlbumDetail.official_ids`
(null = unmatched, today's page) · `extra_ids` · `edition_title` / `edition_country` · `singles` (held album cards,
year order) · `bside_tracks` (full items; `album`/`album_id` name the single) · `single_from`; `MusicTrackItem.extra`
and `also_on`; `MusicArtistDetail.singles_under`; new `MusicTrackCopies` answered by `GET /tv/music/track/{id}/copies`
(checked with the viewer's libraries) and added to `WireRoots` via `scripts/wire_roots.py` — the next
`record-wire-baseline.sh` records it. `tracks` keeps the files' order, so an installed app shows today's page.
`MusicTvService` folds Browse → Songs, search's songs, an artist's top songs, Listen's *Recently played* and the
empty search's recent songs per viewer (the best copy this viewer may open; the hidden library's copy is neither shown
nor counted), sums play counts, and treats a song as a favourite when any copy is; un-favouriting fans out to every
favourited copy (`MusicTvService.setFavorite`, used by `/tv/music/favorite`). An album's tracks, a playlist and
`last-played` never fold. *More from* leaves out the singles homed on the album.

**The app.** `music/AlbumEditions.kt` (pure: `albumQueue`, `queueForTap`, `headerLengthMs`, `effectivePick`,
`editionLabel`); `AlbumPickStore` in `MusicDeviceStore` (key `album_pick`, per device, cleared by
`forgetListening()`); `MusicAlbumScreen` (phone and desktop branches) with the split *Play album ▾* / *Shuffle ▾*
(the menu picks, remembers, relabels and starts playback; Shuffle follows the pick), the header's *+ N extra(s)*, the
*Extras · {edition}* divider, unnumbered extras without Bonus, *Singles & B-sides* cards and the *{n} B-sides · Show*
fold, *Single from …* on a single's page; a new `onPlayQueue` parameter (defaults to `MusicPlayback.playQueue`).
FR-R373-6 (amends R344 FR-R344-3 and R352 FR-R352-1): `TitleThenChips` puts the version chips and Bonus at the
row's right on a phone (chips fold first, then the title ellipsizes; Bonus is never cut); the desktop's `TrackRow` has a
fixed 188 dp chip column before the length; the desktop queue panel uses `TitleThenChips`; Now playing shows Bonus
after its chips. `BonusChip` has no hue (the theme's secondary text on a neutral fill). *Also on* ends the song's ⋯
sheet (phone sheet and the desktop's dialog — the same component) through `TvApiClient.getMusicCopies`; nothing is
asked when `also_on` is 0. The artist page draws the *{n} singles live under their album — …* line, each album
tappable. Strings `ed.*` and a new `country.*` table (19 codes) × en/da/fo in `i18n/*.json` (da/fo drafts;
`check-ravilo-strings.sh` and `check-i18n-spelling.sh` green).

**Tests.** Shared: `MusicEditionsWireTest`, `WireCompatTest` green. Server: `MusicTvEditionsTest` (11) and
`MusicTvSongCopyTest` (3). App, pure: `AlbumPlayOrderTest`, `EditionLabelTest`, `VersionChipsTest` (+2). App,
Robolectric: `AlbumPickStoreTest`, `MusicAlbumEditionsTest` (phone and desktop), `TrackRowChipsTest`,
`SongSheetAlsoOnTest`, `MusicArtistEditionsTest`. Not written: the mini bar / Now playing chip tests (both draw from
private composables; the mini bar draws no chips, dev review 10). `SongSheetAlsoOnTest` opens a release through the
row's click semantics — an injected touch on a row inside the Robolectric sheet did not land; a device should confirm
the tap.

**Only devices can confirm:** the Pixel and the Mac showing the folded counts and the real albums' extras and
B-sides; an installed older app showing today's album page against the new server; *Play album + extras* cast to a
speaker the owner allows (R359's long-queue split); the chips lining up on the Mac's Songs table at Large and Medium.
