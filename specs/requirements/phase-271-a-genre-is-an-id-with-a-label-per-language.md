# Phase 271 — A genre is an id, with a label per language

> Owner, 2026-09-26: *"Genres should be coming from tmdb, how come they have different language?"* And then:
> *"Lets make a spec so we include id when fetching from tmdb and then just store multiple labels when we get
> different languages. Then we can just use the label in the language the ravilo app is currently in (if
> available), then fallback logic ravilo app -> movie/series resolved language fallback list -> english ->
> just whatever label is left now etc."*

## Status

`✓ Built` 2026-09-26, **not deployed** (see *Build notes* at the end; acceptance on production waits
for a release). Written 2026-09-26. Backend (storage, labels, resolution) and every surface
that shows or filters by a genre. Ravilo's apps need no change: the server hands them labels already
resolved. **Built together with Phase 269** (owner, 2026-09-26: *"lets make this part of this"*), and
before it, because 269's recommendations score on genre ids. **Numbering:** verified against
`STATUS.md` the same day — admin taken through **270**.

## What is wrong, measured on production 2026-09-26

**The genres are TMDB's, but only the name is kept, in whatever language the title was fetched in.**

- The scanner fetches a title's details in that title's resolved metadata language (the language-priority
  chain, Phases 128/184/191) and stores `details.genres.map { it.name }` into both `genres` and
  `tmdbGenres` (`Scanner.kt:410-411`, `:502-503`, `:805-806`, `:871-872`, `:950`). TMDB's genre **id**
  is thrown away.
- TMDB translates genre names into the requested language. So a Danish title stores *Komedie*, an English
  title *Comedy*, an Italian one *Commedia*, and all three are genre 35. Of the 99 titles carrying
  Danish genre names, 94 are Danish-resolved titles.
- **The library holds 41 genre names for 24 genres.** Duplicates: *Comedy / Komedie / Commedia*,
  *Family / Familie*, *Kids / Børn*, *Crime / Kriminalitet / Krimi / Kriminal*, *Reality / Virkelighed*,
  *Mystery / Mysterium / Mystik*, *Talk / Snakke*, *Horror / Gyser* and more. Every name maps back to a
  TMDB genre id through TMDB's own genre lists: 41 names, 24 ids, none unmapped.
- **What a viewer sees:** Ravilo's *Genres* wall (R243) has **41 tiles**: *Comedy* and *Komedie* side by
  side, each counting only its own half. The detail page's genre row (R221), the browse page's Genre
  filter, and the row filters in the layout editor all split the same way.
- **TMDB has no Faroese genre names.** `GET /genre/movie/list?language=fo` answers 19 genres with no names.
  Danish and English are complete.

## Requirements

**FR-271-1 — Keep the id.** Wherever the scanner stores genres from a TMDB details response, it also
stores their ids: `MediaItem.genreIds` alongside `genres`, and `tmdbGenreIds` alongside the Phase 94
baseline `tmdbGenres`. A genre the admin added by hand (Phase 94's user edits) has no id and stays a
plain label on that title.

**FR-271-2 — Keep every label TMDB gives us, per language.** A table `genre_label (genre_id, language,
label)`, one row per id and language. It is filled from two sources:

- every details response: its genres' `{id, name}` in the language that response was requested in;
- TMDB's genre lists (`/genre/movie/list`, `/genre/tv/list`), fetched weekly for each language in use:
  English, every configured Ravilo UI language, and every language a title resolved to. That is two
  requests per language, and it makes the table complete for every genre, not only the ones a title
  happened to bring.

**FR-271-3 — One label per genre, chosen by one rule.** `GenreLabels.label(genreId, appLanguage,
titleLanguages)`, in this order:

1. **the viewer's app language**: the per-viewer Ravilo `ui_language`, else the household default;
2. **the title's own languages**: its resolved metadata language, then its original language;
3. **English**;
4. **any label the table has.**

A surface that shows a genre without one title (the Genres wall, the browse page's Genre filter, the admin
workbench) skips step 2. The admin shows English, the language of its own interface, with the same
fallback.

A consequence, kept deliberately because it is the rule asked for: a Faroese viewer (TMDB has no
Faroese labels) sees *Komedie* on a Danish title's detail page and *Comedy* on an English one, and
*Comedy* on the Genres wall.

**FR-271-4 — Grouping and filtering use the id, everywhere.** The same genre is one thing everywhere:

- the **Genres wall and facet counts** (`BrowseService.FacetsAcc`) count by id, so the wall shows one
  tile per genre (24, not 41), labelled by FR-271-3. `FacetItem` gains an additive `id`;
- the **browse page's genre filter and R243's genre seed** filter by id. A request that sends a genre
  **name** (every installed app does) is resolved to its id through the label table, in any language, so
  old apps keep working unchanged;
- **row and channel filters** (the workbench's Genre condition, `ConditionEvaluator`) match by id when
  the condition's value is a known label in any language, and by name only for a hand-added genre. No
  saved config is rewritten: a filter saved as *Komedie* starts matching *Comedy* titles too, which is
  what it always meant;
- **Phase 269's recommendations** use ids.

**FR-271-5 — What the apps receive.** `MovieDetail.genres` / `SeriesDetail.genres`, `BrowseCard.genres`
and facet names carry labels resolved for the requesting viewer (FR-271-3), in TMDB's order, with the ids
alongside as an additive field. The apps render what they receive, so no app change is needed. The
per-viewer label choice is applied **after** any cache read (like R314's platform projection), so a cache
is never keyed by language.

**FR-271-6 — Existing titles need no re-fetch.** After the first genre-list fetch (FR-271-2), a one-time
pass maps every stored genre name to its id through the label table and writes `genreIds` /
`tmdbGenreIds`. It is one batched write, for the reason in Phase 268's dev review (a write per title
would discard every viewer's Home feed each time). Names that map to no id stay hand-added labels. On
production that is 41 names → 24 ids, none left over.

**FR-271-7 — What Jellyfin sees is unchanged.** The NFO keeps writing the title's own `genres` names, as
today. Changing the genres Jellyfin's own clients show is a separate decision.

**FR-271-8 — The admin's Metadata → Genres page shows every label a genre has.** Owner: *"the existing
metadata genres jellystructure page should also show all the different labels we currently have for a
specific genre."* Today the tab draws one chip per stored **name** with its count
(`Metadata.kt:192-198`, from `GET /api/metadata/genres`), so *Comedy* and *Komedie* are two chips.
It becomes **one entry per genre id**:

- the English label as its name, and the total count across every title with that id;
- beneath it, every label the table holds, each with its language (*da Komedie · it Commedia ·
  en Comedy*);
- the entry's link opens the Library filtered by the **id**, so it lists every title in that genre
  whatever language its metadata came in.

Hand-added genres (FR-271-1) are listed after the TMDB genres, as their own entries, marked as
added by hand. The route answers ids, labels per language and counts. The page does not group names
itself (render-never-compute).

**FR-271-9 — Tests.**

- the label rule, each step falling to the next;
- a Faroese viewer on a Danish title, and on an English one;
- the wall counting *Comedy* and *Komedie* titles under one id;
- a filter saved as *Komedie* matching an English-labelled comedy;
- a hand-added genre surviving a re-scan (Phase 94);
- the backfill mapping names to ids.

## Non-goals

- Merging TMDB's own separate ids. Films have *Action* (28) and series *Action & Adventure* (10759);
  they stay two genres, as TMDB defines them.
- Faroese genre labels. TMDB has none, and the fallback chain covers it. If the household wants Faroese
  genre names, a later phase can add rows to `genre_label` for `fo`: the table and the rule already take
  them.
- Recommendations. Phase 269 is built separately and reads ids as soon as they exist.

## Acceptance

1. The Genres wall on the TV shows one *Comedy* tile (or *Komedie* for a Danish-language app) whose count
   is the sum of today's *Comedy*, *Komedie* and *Commedia* tiles. There are 24 tiles, not 41.
2. With the app in Danish, a comedy's detail page says *Komedie*, whichever language the title's metadata
   came in. With the app in Faroese: *Komedie* on a Danish title, *Comedy* on an English one.
3. Selecting the *Comedy* tile opens a grid with every comedy, Danish and English titles together.
4. A row filter saved as *Komedie* now includes English-labelled comedies.
5. The admin shows English genre names everywhere.
6. An app installed before this phase shows the corrected labels and counts, with no update.
7. Metadata → Genres in the admin: one *Comedy* entry with its total count and *da Komedie · it
   Commedia* listed beneath it. Its link opens the Library with every comedy.

## Build notes (2026-09-26)

Built on `main` after `39e6e8af`, the same day, before Phase 269. Every requirement is in; where the build
differs from the text above, the build is recorded here.

1. **Labels come from TMDB's genre lists only, never from a details response** (changes FR-271-2's first
   source). Measured 2026-09-26: `/movie/{id}?language=fo` answers *Comedy*, *Family*: TMDB falls back to
   English where it has no translation. `/genre/movie/list?language=fo` answers `name: null`. Recording from
   details would file English names as Faroese, and a Faroese viewer would see *Comedy* on a Danish title,
   which is the opposite of FR-271-3. A details response still records which ids exist and which list
   (`movie`, `tv`) they belong to (`genre_kind`), used only when one label names two ids.
2. **Storage.** Migration **54**: `genre_label (genre_id, language, label)`, `genre_kind (genre_id, kind)`,
   `genre_list_fetch (language, fetched_at)`. So 269 takes 55 and 270 takes 56 (both specs updated).
   `MediaItem.genreIds` / `tmdbGenreIds` are `List<Int?>`, **aligned by index** with `genres` /
   `tmdbGenres`, null for a genre added by hand.
3. **`genreIds` is never written by hand.** `MediaStore` derives it on every write
   (`GenreCatalog.normalize`) **before** the content comparison (Phase 204). Otherwise a scan's `copy`
   carrying stale ids would read as a change and rebuild every viewer's Home. The title's own TMDB pairing
   (`tmdbGenres[i]` ↔ `tmdbGenreIds[i]`, one details response) wins over the label table, so a genre whose
   language list has not been fetched yet still gets its id. The scanner stores `tmdbGenreIds` at all nine
   sites that store `tmdbGenres`. The clear-match (174) and history-revert paths carry them too.
4. **The weekly refresh** (`GenreListRefresher`, started from `Main.kt`, BACKGROUND class): wakes every
   6 h, fetches both lists for each language whose last fetch is over 7 days old. The languages are:
   - English, the three app languages;
   - the global and per-library fallback languages;
   - every title's resolved, original and hand-set metadata language;
   - every audio-track language, which is exactly the set the scanner's language-priority chains can have
     asked TMDB in.

   A new language waits at most 6 h. After a pass that learnt anything, `MediaStore.normalizeGenres` maps
   stored names to ids in **one transaction with one feed bump** (FR-271-6). It does not stamp `updatedAt`,
   since ids derived from names already stored are not an edit.
5. **Matching** (FR-271-4) goes through one identity, `GenreCatalog.keys(item)` / `keyOf(value)`: `#35`
   for a TMDB genre, the Phase 216 `TaxonomyKey` of the name for a hand-added one. Used by:
   `ConditionEvaluator` (saved row and channel filters); `BrowseService.browse` (installed apps' `genres=`
   names, in any language); `MediaStore.list` (the admin Library); `metaFacets` (admin, English names);
   `relatedByGenre`; and the legacy `filterGenre` channel field. The legacy substring `GENRE` row matches
   every label of a title's genres in any language. `#35` is accepted as a value too.
6. **Labels per viewer** (FR-271-5). One detail per surface:
   - **Detail pages**: the full rule. The title's languages are its hand-set metadata language, then its
     resolved language, then its original language. The detail card's `genre` matches.
   - **Browse cards, facets, search cards**: the rule without the title step, from the viewer's resolved
     `uiLanguage`. Facets are cached in English and relabelled **after** the cache read, then re-sorted.
   - **Home, channel pages, *See all* for Continue Watching**: built and cached in English and relabelled
     per request by `GenreLabels.kt` (English label → id → the viewer's label, one map lookup per card).
     This also covers cards built outside any viewer's context (the shared Continue list) and R240's
     focus-detail facts.
   - **Coming Soon details and Seerr's Request details** show the viewer's labels by TMDB id.
     `SeerrGenre` gains its `id`.
7. **The admin's Metadata → Genres** (FR-271-8): one card per genre id, named in English, with its total.
   Beneath it, every label the catalog holds, grouped by text, with the languages that use it and how many
   titles store that exact name. A label no title stores is dimmed. A stored name the catalog has no
   language for yet still shows. Hand-added genres follow, marked *Added by hand*. The link filters the
   Library by the English name, which resolves to the id. The page's search matches every label, so typing
   *Komedie* finds *Comedy*.
8. **Provenance merge by identity** (Phase 94): a genre the admin removed stays removed, and one added
   survives, even when a re-sync fetched the title in another language and the same genres came back under
   other names (`GenreCatalog.mergeUserGenres`).
9. **Backwards compatibility** (owner, 2026-09-26: *"we need to be backwards compatible for a while with
   our apis"*). Every wire change is additive with a default: `FacetItem.id`, `BrowseCard.genre_ids`,
   `MovieDetail.genre_ids`, `SeriesDetail.genre_ids`. No field changed meaning. An installed app keeps
   sending genre names and gets id matching through them. An app built with these models reads an older
   server's payloads unchanged. The admin route `GET /api/metadata/genres` keeps its list shape, with two
   fields added.
10. **Tests.** `GenreCatalogTest` (9) covers: the four label steps; the Faroese viewer on a Danish and an
    English title; any-language name resolution; a *Komedie* filter matching an English comedy; the merge
    across a language switch; the title's own pairing; `normalize` idempotence; `relabel`; and the backfill
    as one batch and one bump, with no bump on a stale-ids rewrite. `GenreIdBrowseTest` (3) covers: one
    tile per genre with grid counts that agree; old-app names in Danish and Italian; a Danish viewer's wall
    and cards with the cache not keyed by language; Home's relabel. Full `linuxX64Test` green;
    `compileKotlinWasmJs` and `:ravilo-ui:compileKotlinWasmJs` green.

**Not done here:** deploying, and the acceptance checks on production (they need a release and a restart,
which need the owner's go-ahead).
