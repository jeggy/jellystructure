# Songs and albums carry jellystructure tags, and a tag can be scoped to some kinds of media

Task: DEB-3701

> Owner, DEB-799, 2026-11-05 (comment `80db8be0`): *"I would like to be able to tag songs and albums in
> jellystructure. And I would also like to update our current metadata tags page, so it's possible to create a
> jellystructure tag that is being used only for specific media types. So it should be default to all, but can be
> configured one or multiple groups. And these groups are the ones that we already have. So: Movies · TV · Music
> videos · Music · Audiobooks. And they should have the same logic, when a song get's a tag from the jellystructure
> tags, then that over wins anything else that happens automatically (coming from musicbrainz, jellyfin or whatever
> automation we might build later on etc) a manually set jellystructure tag, should never be removed unless it's
> manually removed."*

## Scope

Backend and admin. This spec does three things:

1. it gives every jellystructure tag a **scope**: *All*, or one or more of the five groups (FR-1 to FR-4);
2. it lets **albums and songs** carry jellystructure tags (FR-5 to FR-9);
3. it holds constitution invariant #6, *JS tags always survive*, for music by construction rather than by guard
   (FR-5, FR-7).

- **The constitution does not change.** Invariant #6 already says jellystructure tags are preserved on every path.
  Music joins that rule and does not become an exception to it. Phase 199's audit is the precedent: every writer is
  named, and the proof is a test per writer.
- **Builds on** Phase 278 (the album page and Library → Music), Phase 292 (version ticks, the owner's own per-song
  labels, kept in a table that a scan never writes, and its carry across a *Convert*), and Phase 74 (a tag edit saves
  to the database only).
- **Delivers what Phase 278 already promised.** FR-278's album page has *"Jellystructure tags underneath as for
  films"*, and its build notes deferred it (*"albums carry no JS tags yet"*). The section it drew
  (`design/app/album.js`, the Genres tab) is built here. 278's file is not amended, because its promise does not
  change.
- **Leaves alone, and says why:** `a-tag-filter-holds-on-every-ravilo-surface.md` FR-2 rests on *"in Jellyfin no
  music or audiobook carries a tag"*. FR-10 keeps that true, so `musicVisible` and every viewer's library are
  unchanged.
- **Clients:** none. Nothing on `/api/tv` changes (FR-11). The work's label is `jellystructure-admin` only.
- **Design:** no round (Bárður, 2026-11-05). The album's tag row is already drawn (`design/app/album.js`, the Genres
  tab's *Jellystructure tags* section). Everything else is made of controls the admin already has, each named in its
  FR: the tag card, the tag modal, `mu-gc` tick chips, `chip` with a `tag-dot`, a Library → Music facet, and the
  selection bar's bulk actions.
- **Not in this build:**
  - a rating;
  - tags on artists;
  - tags shown or filtered in Ravilo;
  - tags written into the music files, or sent to Jellyfin (FR-10).

  Each of those would be its own ask. *"Tag music like this"* was said over a screenshot of Lidarr's singles list. The
  stars in it are MusicBrainz's community rating, which Lidarr shows and does not let anyone set. The owner's own
  words then describe tags only.

## What is true today (2026-11-05, `main` `8ec86617`)

- **Tag definitions** live in `js-tags.json` (`JsTagStore`): a name, a colour and a description. A tag has no scope.
  `/api/tags` lists, creates, edits and deletes them. A delete also strips the tag from every item, under FR-199-7.
- **Who carries tags:** films, series and music videos, the three `MediaKind`s. Their tags sit inside the item's
  row as `item.tags`, mixed with Jellyfin's and TMDB's. A name counts as jellystructure's when it is in
  `JsTagStore.nameSet()`. Invariant #6's guards (`JsTagLock.preserveJsTags`, `mergeRepullTags`) sit on each of
  `MediaStore`'s write choke points. Episodes carry no tags and inherit their series'. A tag reaches Jellyfin only
  through an NFO `<tag>`, on an explicit save.
- **Music:** `music_album` and `music_track` are keyed by the **Jellyfin item id**. Jellyfin derives that id from the
  file's path. So a *Convert* (278) or a Lidarr upgrade to another format gives the song a **new id**, and the old row
  stays behind with `missing_since` set. Music rows are never deleted. Neither table has any tags.
- **Music already has one owner-set label** that a scan never writes: the version ticks
  (`music_version_choice`, Phase 292). They are keyed `rec:<recording mbid>` or `trk:<track id>`. A new song that has
  the same folder and base name as a song that just went missing takes over its ticks
  (`MusicScanner.carryVersionChoices`). 292 accepted that a song moved or renamed by hand loses its ticks. For tags,
  this spec does not accept that (FR-7).
- **Library → Music** has facets (`MusicBrowse`). An album carries a value set per facet, and **a song inherits its
  album's** values. The page has a selection with bulk actions.
- **Audiobooks** live in their own tables (`audiobook`, `audiobook_part`) and carry no tags.

## Decisions

**Settled here** (Bárður, 2026-11-05: each one follows from a rule already in force, or changes nothing a viewer
sees):

| # | Question | Answer | Because |
|---|---|---|---|
| D1 | *"Like this"*: tags or a rating? | **Tags only** | The owner's words describe tags. Lidarr's stars are MusicBrainz's community rating, and nobody sets them |
| D2 | Where does a music tag live? | **jellystructure's database only**. Not in the file, and not in Jellyfin (FR-10) | FR-284-1 already says a file never gets anything that is not a fact about the song. A Lidarr upgrade replaces the file, so a copy in the file could never be the record |
| D3 | What a narrowed scope does to items already outside it | **They keep the tag.** The page says how many, and offers a removal that is a deliberate act (FR-4) | Invariant #6: only a manual removal drops a tag |
| D4 | Ravilo | **Nothing** in this build (FR-11) | No ask names Ravilo. Keeping music tags out of Jellyfin leaves every viewer's library exactly as it is |

**For the owner** (card on DEB-3701, 2026-11-05):

| # | Question | Recommended | Alternative |
|---|---|---|---|
| Q1 | Does an album's tag carry down to its songs? | **Yes.** The song shows the album's tag marked *from the album*, the Songs list's filter finds it, and it is removed on the album | Album and song tags stay independent: a tag on the album says nothing about its songs |
| Q2 | Can audiobooks carry tags too? | **Yes, in the same build.** The Book page gets the album's tag row, so the *Audiobooks* scope has something to apply to | Not yet: the *Audiobooks* scope can be chosen but applies to nothing, and the scope chip says so |

FR-6 and FR-12 are written as the recommendations. A different answer rewrites them before their slice is built.

## Requirements

### A tag has a scope

**FR-1 — The scope.**
- Each jellystructure tag gains `scopes`: a set of the Library's existing kind keys, `MOVIE`, `TV_SHOW`,
  `MUSIC_VIDEO`, `music` and `audiobooks` (`libraryKindSeg()`, `ui/AudiobookLibrary.kt`, the picker Library, Music and
  Audiobooks already share). There is no new enumeration.
- An empty or absent set means **All**. Every tag that exists today loads as *All* and behaves exactly as it does
  now. The file needs no migration: an older `js-tags.json` has no `scopes`, and that reads as *All*.
- The groups map onto items as follows:

  | Group | Items |
  |---|---|
  | Movies | `MOVIE` |
  | TV | `TV_SHOW`: the series. Its episodes carry no tags of their own and inherit the series' tags, as today |
  | Music videos | `MUSIC_VIDEO` |
  | Music | albums and songs |
  | Audiobooks | books (FR-12) |

- One function decides the question *does this tag apply to this item's group?*, and every caller in FR-3 uses it.

**FR-2 — The tags page shows and edits the scope** (Metadata → Tags).
- **The card.** Each tag card shows its scope on a line under the description: *All*, or the groups joined by ` · `
  (*Music · Audiobooks*). The count badge still counts every item that carries the tag, in every group.
- **The modal.** *New tag* and *Edit tag* gain a **Used for** row:
  - an *All* chip and five group chips, in the order Movies · TV · Music videos · Music · Audiobooks, drawn as
    `mu-gc` tick chips;
  - ticking *All* clears the others, and ticking any group clears *All*;
  - unticking the last group ticks *All* again. A tag never has a scope of nothing.
- **The API.** `POST /api/tags` and `PATCH /api/tags/{name}` accept an optional `scopes`. `GET /api/tags` and
  `GET /api/metadata/tags` (`TagsResponse.jsTags`) return it.
- **The default.** A new tag starts as *All*.

**FR-3 — A tag is offered only where its scope applies.**
- Every place that adds a jellystructure tag lists only tags whose scope covers that item's group. That includes:
  - the film, series and music-video detail page;
  - every bulk *add tag* action;
  - the album and song controls in FR-9;
  - the book control in FR-12.
- **The server enforces it,** not only the picker. Adding an out-of-scope tag answers `409` with *‹tag› is not used
  for ‹group›*. The film detail page saves its whole tag list through `PATCH /api/media/{id}/metadata`, so the check
  there applies only to jellystructure tags that are **new** in the list. A tag the item already had is never
  refused, and neither is a removal.
- **A tag the item already carries is always shown and always removable**, whatever its scope. When it is outside
  its tag's scope, its chip has a dashed border and the title *outside this tag's scope*.

**FR-4 — Narrowing a scope never removes a tag** (D3).
- **Before the save.** When an edit narrows a scope and items outside the new scope carry the tag, the modal says so
  before the save, as one line: *‹n› items outside the new scope keep this tag: ‹3 films · 1 series›.* The save goes
  ahead. Nothing is removed.
- **After the save.** The card carries *‹n› outside its scope*. A click opens the Library filtered to those items.
- **The removal.** The modal of a tag that has such items offers one action: **Remove from the ‹n› outside its
  scope**, behind a confirm that names the count. That action is the owner removing the tag by hand, so invariant
  #6 allows it. Nothing else removes it.
- **Widening** a scope changes no item.

### Albums and songs carry tags

**FR-5 — Where a music tag lives.**
- **The table.** A new table, `music_js_tag(subject_id, subject_kind, tag, set_at)`, with primary key
  `(subject_id, tag)`. `subject_kind` is `album` or `track`, and `book` under FR-12.
- **It is not part of the row.** It is not inside `music_album.json` or `music_track.json`. A scan replaces those rows
  wholesale (`INSERT OR REPLACE`), and the tags must not ride on them.
- **Invariant #6 for music by construction: the table's writers are a closed list.**
  1. the admin's add-tag and remove-tag routes (FR-9);
  2. a tag's deletion on the tags page, which strips the tag here too, as FR-199-7 does for films;
  3. FR-4's *Remove from the ‹n› outside its scope*;
  4. FR-7's carry, which only ever **adds**.

  No scan, ingest, match, MusicBrainz re-pull, Jellyfin refresh, `write_tags`, *Convert*, edition or version
  decision, Lidarr import, or later automation writes the table. A test asserts the list (Acceptance 5).
- **A tag name that is not defined** in `js-tags.json` is never stored here. Tags have no rename today, and this spec
  adds none.

**FR-6 — A song carries its album's tags** (Q1, recommended answer).
- **The song's tags.** They are its own tags plus its album's.
- **On the song,** an album tag shows marked *from the album* and has no remove control there. It is removed on the
  album.
- **Adding twice.** Adding a tag to a song that already has it from the album is refused as a no-op. When the album's
  tag is later removed, a song that was also tagged by hand keeps it.
- **The Tag facet** (FR-9) follows `MusicBrowse`'s existing rule: *a song inherits its album's*.

**FR-7 — A tag follows its song or album to a new id.**
- **When it runs.** A music scan finds a new song or album at the same moment a tagged one goes missing. That
  happens on a *Convert*, on a Lidarr upgrade, on a re-import, and when a folder is renamed. The new row then takes
  over the old one's tags. The first rung that matches wins:

  | | Songs | Albums |
  |---|---|---|
  | 1 | the same folder and the same base name (292's rule) | the same folder |
  | 2 | the same album, or that album's FR-7 successor, and the same recording MBID | the same release MBID, else the same release-group MBID |
  | 3 | the same album or successor, the same disc and position, and the same title (case-folded) | the same album artist and the same title (case-folded) |

- **The carry copies, never moves.** The missing row keeps its tags. If the song comes back, it still has them.
- **Ambiguity.** When two new rows match one missing row on the same rung, nothing is carried. That case belongs to
  FR-8.
- **History.** A carry writes one line on the album: *tags carried from the replaced file: ‹tags›*.

**FR-8 — A tagged song or album that is gone is shown, never dropped.**
- **The card.** A tag whose music rows are all missing and that no carry took over gets a line on its card on the
  tags page: *‹n› tagged songs or albums are no longer in the library*.
- **The list.** A click lists them with title, artist and album, each with *Remove*. Removing one there is a manual
  removal. Nothing else removes them.

**FR-9 — Where the admin sets and sees music tags.**
- **Album page, Genres tab.** The *Jellystructure tags* section as drawn: `chip` with a `tag-dot` per tag, then
  *＋ Add tag*. It opens the same tag dropdown the film detail page uses, with FR-3 applied.
- **Album page, Tracks tab.** Each row shows its tags as `tag-dot`s after the title, own tags first, then the album's
  (FR-6). A click on the dots, or on an empty row's *＋*, opens the dropdown for that song.
- **Library → Music:**
  - a **Tag** facet in Albums and Songs, in `MusicBrowse.FACETS` after *Genre*. A song inherits its album's values
    (FR-6). Only tags whose scope covers Music, plus any tag a music row still carries, are listed;
  - the selection bar gains **Tag…** and **Untag…**, on albums and on songs, in the version ticks' bulk shape (292).
    *Select all shown* resolves on the server, as 292 FR-292-16 does.
- **Metadata → Tags.** Each card's count includes albums and songs. *View in library* opens Library → Music filtered
  by the tag when the tag's scope is Music only; otherwise it opens the Library as today.

**FR-10 — Not into the files, and not into Jellyfin** (D2).
- **The files.** `write_tags` never writes a jellystructure tag. FR-284-1 already says so; this FR names the case.
- **Jellyfin.** `album.nfo` gets no `<tag>` for them. Nothing calls Jellyfin's tag API for music.
- **Why.** `a-tag-filter-holds-on-every-ravilo-surface.md` FR-2 rests on *"in Jellyfin no music or audiobook carries a
  tag"*. That stays true, so `musicVisible` and every viewer's library are unchanged.
- **A later spec** that sends music tags to Jellyfin must amend that FR-2 in the same change, because a tag in Jellyfin
  starts to act on allowed-tag and blocked-tag accounts.

**FR-11 — The wire is unchanged.**
- `/api/tv` gains nothing and loses nothing.
- The admin DTOs gain optional fields only: `scopes` on a tag, and `jsTags` on the album, track and book payloads the
  admin reads.
- `WireCompatTest` passes unchanged.

**FR-12 — Books carry tags** (Q2, recommended answer).
- **Storage.** A book carries tags exactly as an album does: `music_js_tag` with `subject_kind = book`, and the same
  closed list of writers (FR-5).
- **The carry.** FR-7's album rungs, applied to the book's folder and its title and author.
- **The Book page** shows the album's tag row (FR-9) above its Files tab.
- **Audiobooks Library** gets the Tag facet.
- **Parts.** A book's parts carry no tags of their own.

## Acceptance

`linuxX64Test` unless named otherwise.

1. **Old file.** A `js-tags.json` with no `scopes` loads every tag as *All*. Writing it back keeps every tag's name,
   colour and description byte-for-byte.
2. **Scope enforced.** A tag scoped to Music:
   - is listed for an album and refused (`409`) for a film;
   - a film that already carries it keeps it, shows it outside its scope, and can drop it.
3. **Narrowing.** Narrowing *All* to *Music* while 3 films carry the tag:
   - removes nothing, and reports 3 outside the scope;
   - *Remove from the 3 outside its scope* removes exactly those 3.
4. **Album and song tags.** An album tag and a song tag are set, and survive each of these, run in turn:
   - a full music scan;
   - a re-match;
   - a MusicBrainz re-pull;
   - `write_tags`;
   - a Jellyfin refresh.
5. **Closed writers.** A test enumerates every function that writes `music_js_tag` and fails if one is added outside
   FR-5's list. This is Phase 199's proof, made structural.
6. **Carry.** A tagged MP3 replaced by a FLAC of the same base name, as a Lidarr upgrade does, gives the new track id the
   tag, and the missing row keeps it. The same holds for:
   - a renamed file with the same recording MBID;
   - the same disc, position and title.

   Two candidates on one rung carry nothing, and FR-8 lists the song.
7. **Inheritance.** A song of a tagged album shows the tag as *from the album*, cannot remove it there, and is found by
   the Songs view's Tag facet. Removing the album's tag leaves a song's own copy.
8. **Delete.** Deleting a tag on the tags page strips it from films and from `music_js_tag` (and books).
9. **Nothing reaches viewers.** `album.nfo`, a written file's frames, and every `/api/tv` response are identical before
   and after tagging. An allowed-tag account still sees no music, and a blocked-tag-only account still sees all of it.
10. **Admin.** `:wasmJsTest` or a DOM check covers the modal's *All* / group chip rules (FR-2).

## Build order

Three slices for one engineer. Each is blocked by the one before it.

1. **The scope:** FR-1 to FR-4, and FR-11's `scopes`. Backend and admin. It depends on nothing open, so it is ready
   now.
2. **Albums and songs:** FR-5 to FR-10, and FR-11's `jsTags`. It waits for Q1's answer only if the answer is *no*,
   which removes FR-6.
3. **Books:** FR-12. Built only if Q2 is answered *yes*.

QA: one admin pass on prod after slice 2. Tag an album and a song, run a music scan, then upgrade or convert one
tagged song and check the tag followed it. No device is needed.
