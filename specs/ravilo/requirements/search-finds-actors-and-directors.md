# Search finds actors and directors, and remembers what you searched

Task: DEB-5842

> Owner, on DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful
> in our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."* He picked this piece on the card *"Ravilo polish: what to build"* on 2026-11-26.

## Scope

Touches the backend's viewer search (`tv/BrowseService.search`, `GET /api/tv/search`), the shared `SearchResults` DTO,
and the Search page in `:ravilo-ui`.

- **In:** Android TV, phone, Ravilo Web, desktop Mac and Linux. They all draw `SearchScreen.kt`.
- **Out:** Ravilo Samsung (`ravilo-screen`) draws no people row and no recent searches. Its spec lists *people in
  Search* as not in DEB-5621's amendment (`ravilo-on-a-samsung-tv-is-a-full-app.md`, FR-8), and the constitution fences
  that client to its own list. It still gets FR-3 with no client change, because FR-3 lives in `items`. Chromecast has
  no search.
- **Extends, does not amend:** R325's genre group (FR-R325-5, a group above the titles), R190's person page
  (FR-RV-PPL1-1/2, `openPersonBrowse` with a `cast_crew` seed), R295's and R361's Back to the opened result, R350's
  D-pad order on Search, R365's *Clear*, and R337's desktop sidebar field. None of their promises change, so no
  amendment is written.
- Recent searches stay on the device, per profile, like R212's Home snapshot. They are words the viewer typed, not
  library data, so they do not cut across the constitution's *jellystructure decides* rule. Nothing is sent to the
  server.

## Why

What the code does today (checked 2026-11-27 against `921645877`):

- `BrowseService.search` (`src/linuxX64Main/kotlin/dev/jellystructure/tv/BrowseService.kt:252-286`) matches `title`,
  `originalTitle` and `titlesByLang`. An actor's or director's name finds nothing.
- The store already holds the people. Every `MediaItem` carries `cast` and `crew` as `Person(tmdbId, name, profilePath,
  character, job, department, type, …)` (`src/commonMain/kotlin/dev/jellystructure/model/Media.kt:126`). The scanner
  sets `type` to `"Actor"`, `"Director"` (department Directing) or `"Writer"` (`media/Scanner.kt:1590-1647`). Crew is
  TMDB's full crew list, so it holds every department.
- The person page already exists: a face on a detail page opens `Dest.SeededBrowse` with `cast_crew is_any_of [id]`
  (`ravilo-ui/…/RaviloApp.kt:1097-1111`). The detail page's `Person` wire type (`shared/…/tv/Models.kt:669`) carries
  `id` (the TMDB id as a string), `name`, `role` and `image_url` (TMDB w185, `tv/DetailService.kt:407-423`).
- `SearchScreen.kt` waits 250 ms after a keystroke (`:146-147`), shows *Suggestions* when the field is empty, and has
  no recent searches.
- The DEB-5687 audit rates this *Standard* (every large streaming app has it) and S–M in size.

## Requirements

### FR-1 — The server matches people on the viewer's own titles

- `search(device, q)` also matches people when `q` is 3 or more characters after trimming. Titles keep their 2.
- A person is matched on their `name` with `SearchQuery`'s rules: substring, word order, punctuation-blind, each word a
  prefix of a name word.
- Only these credits count: `cast` (every billed actor), and `crew` whose `type` is `"Director"`. Writers and other crew
  are not matched.
- A person counts only through titles in `mediaStore.liveItems(device)`. A person whose credited titles are all hidden
  from this viewer (library access or tag policy) is never returned, and their count never includes a hidden title.
- Hits are ranked: the query equal to the whole name first, then more of the viewer's titles first, then the name A–Z.
  At most 12 are sent.

### FR-2 — The hits travel as a new, additive field

- `SearchResults` gains `people: List<SearchPersonHit> = emptyList()`.
- `SearchPersonHit` has `id: String` (the TMDB id as a string, the same as `Person.id`), `name: String`,
  `@SerialName("image_url") imageUrl: String?` (TMDB w185, as `DetailService.castFrom` builds it),
  `@SerialName("known_for") knownFor: String?` and `count: Int`.
- `known_for` is the plain string `"acting"` or `"directing"`: the credit kind on most of this viewer's titles, with
  acting on a tie. It is a `String`, not an enum, because `RaviloWireJson` decodes enums strictly. An app that does not
  know a value shows no role line.
- An older installed app ignores `people` (`ignoreUnknownKeys`) and draws the Search page as it does today.
- A blank or short query sends `people = []`.

### FR-3 — A full name brings that person's titles into the grid

- When the query equals one matched person's whole name (normalised by `searchWords`), or is 2 or more words that each
  equal one of that name's words, and exactly one person matches like this, the titles they act in or directed follow
  the title matches in `items`.
- Title matches stay first, in today's order. The person's titles come next, newest first. A title is never listed
  twice, and `items` stays capped at 100.
- Two people with the same name add nothing. The people row (FR-4) tells them apart.
- An older app and Ravilo Samsung get this through `items` with no change.

### FR-4 — The Search page shows a People group

- Every client in scope draws a **People** group (`search.people`) when `people` is not empty. It sits under the Genres
  group (R325) and above the titles.
- Each hit is a `CastCircle` (the detail page's face). Under the name: the role line `search.person_actor` or
  `search.person_director` from `known_for`, then the count, `search.person_titles` (or `search.person_title_one`).
- Opening a face (OK on a TV, a tap on a phone, a click on web and desktop) calls `openPersonBrowse` with
  `Person(id, name, role = the role line, imageUrl)`. The breadcrumb is `nav.search`. The person page is R190's, unchanged.
- Back from the person page returns to Search with the same query, the same results and focus on the same face (R295).
  If the face is gone, focus goes to the face now at its index (R361).
- If there are no title matches but there are people, the page shows no *No results for "…"* line. That line shows only
  when titles, genres and people are all empty.
- The field's hint reads `search.placeholder_people` on these clients. `search.placeholder` stays as it is for Ravilo
  Samsung.

### FR-5 — On a TV the D-pad reaches the People group in page order

- Down from the field goes to the first group that is drawn: genres, then people, then the titles. Down from the people
  row goes to the titles. Up goes back the same way. Left and Right move along the row.
- Back from the people row leaves Search in one press, as from the results (R350, FR-R350-4).
- The row uses `CastCircle`'s draw-phase focus (R89) and adds no animation, no second bring-into-view (R108) and no
  image prewarm (R106).

### FR-6 — Recent searches are kept on the device, per profile

- A query is recorded when the viewer opens a result from it (a title, a person, a genre) or presses the keyboard's
  Search action while it has results. Partial queries the debounce sent are not recorded. Suggestions (the empty
  field) are never recorded.
- Up to 8 are kept, newest first, trimmed, and compared without case. Repeating a query moves it to the top.
- They are stored per profile (the Jellyfin user id) in a new `RecentSearchStore` (`expect` in common, `actual` for
  Android, desktop and Wasm, as `HomeSnapshotCache` does). They live apart from the session store.
- Signing a profile out, removing it, or a `sign_in_removed` that ends it (DEB-5781) clears that profile's list. This
  happens wherever `HomeSnapshotCache.clear(userId)` runs today. Another profile on the same TV never sees the list.
- Nothing about recent searches goes to the server.

### FR-7 — The empty field shows recent searches first

- With the field empty and a non-empty list, the page draws **Recent searches** (`search.recent`) above *Suggestions*.
  Each entry is a chip in R325's genre-chip style. Opening one fills the field and searches at once, with no 250 ms wait.
- A **Clear** button (`search.recent_clear`) at the end of the group empties the list for this profile.
- On a TV, Down from the field goes to the recent chips, then to the suggestions. On the desktop, the sidebar field
  (R337) drives the same page.
- With an empty list, the page is what it is today.

### FR-8 — Search does not get slower

- The people match reads an index of names. The index is built once when the media store changes (a scan, an edit), and
  never on a request. A request filters the index's matches by the viewer's visible items.
- On a synthetic library of 5,000 titles with 40 credits each, `search` with people takes no more than 20 ms more at the
  median than without them, measured in the test.
- No change touches the playback start path, the detail page or TV focus motion. The client adds no request: people
  come back in the same `GET /api/tv/search` answer.

### FR-9 — Strings

New keys in `i18n/en.json`, with Danish and Faroese in `da.json` and `fo.json`. Those are drafts in the
`{"text": …, "note": "DEB-5842 — draft, review before release."}` form `search.genres` uses.

| Key | English |
|---|---|
| `search.placeholder_people` | `Titles, actors, directors…` |
| `search.people` | `People` |
| `search.person_actor` | `Actor` |
| `search.person_director` | `Director` |
| `search.person_titles` | `{count} titles` |
| `search.person_title_one` | `1 title` |
| `search.recent` | `Recent searches` |
| `search.recent_clear` | `Clear` |

## Design

No design round. The people row reuses `CastCircle` and the Genres group's heading. Recent searches reuse
`SearchGenreChip` and R365's *Clear*. Nothing needs new layout. If the owner wants a different look for the People
group, that comes as a round on its own task.

## What this does not do yet

- Accent-blind matching (a name with an accent typed without it). Kotlin/Native has no normaliser, and `SearchQuery`
  does not fold accents for titles either.
- Writers, creators and other crew; a biography on the person page; people the library does not hold (TMDB or Seerr).
- Voice search, the Android TV system search, and recent searches that follow the viewer to another device.
- The People group and recent searches on Ravilo Samsung. That comes as an amendment in its own spec.
- A failed search that looks like *0 results*, and results blinking while typing (DEB-5687 evidence, polish item 11 and performance item 7). They
  are another piece's.

## Acceptance

1. On a TV, typing an actor's full name shows a People group with their face, *Actor · N titles*, and their films in
   the grid below. OK on the face opens their person page. Back lands on the same face.
2. Typing a director's surname shows them under People with *Director*.
3. A viewer with no access to a library never sees a person whose only titles are in it.
4. An APK built before this change, pointed at the new server, draws Search as before, and a full name brings that
   person's titles into the grid.
5. Open a title from a search, clear the field: the query is the first chip under *Recent searches*. Switch profile: it
   is not there. Sign that profile out and back in: the list is empty.
6. `GET /api/tv/search?q=ab` answers `people: []`.

## Build order

1. **Backend people match and the wire field** (FR-1, FR-2, FR-8). The index in `MediaStore` or a small
   `tv/PeopleIndex.kt`, `BrowseService.search`, and `SearchResults` plus `SearchPersonHit` in `shared/…/tv/Models.kt`.
   Proof: a new `SearchPeopleTest` (visibility, ranking, cap, 3-char floor, `known_for`, timing on a synthetic library),
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew linuxX64Test --tests '*SearchPeopleTest*' --console=plain`. About 180 lines.
2. **Full name brings their titles** (FR-3). `BrowseService.search` only. Proof: cases in `SearchPeopleTest` (one match
   appends, two same-named add nothing, no duplicates, cap 100), same Gradle line. About 60 lines. **Junior-ready**,
   after slice 1.
3. **People group on the Search page** (FR-4, FR-9). `SearchScreen.kt`, `RaviloApp.kt` (the `Dest.Search` branch calls
   `openPersonBrowse`), `SearchReturnTarget` for a face, and the three `i18n/*.json`. Proof: `SearchReturnTargetTest` cases for
   a face, a commonTest for the empty-line rule, `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests
   '*SearchReturnTarget*' --console=plain`. About 170 lines.
4. **TV D-pad order** (FR-5). `SearchScreen.kt` focus requesters. Proof: cases in `SearchFocusTest` (field → genres →
   people → grid and back; Back leaves in one press), `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew
   :ravilo-ui:testDebugUnitTest --tests '*SearchFocusTest*' --console=plain`. About 80 lines. **Junior-ready**, after
   slice 3.
5. **Recent searches store** (FR-6). `screens/RecentSearchStore.kt` (`expect`), its Android, desktop and Wasm `actual`s,
   the record calls in `SearchStore`, and the clear beside each `HomeSnapshotCache.clear(userId)` (`SignedOut.kt`,
   `SettingsScreen.kt`, `ProfilePickerScreen.kt`). Proof: a `RecentSearchesTest` on the pure list logic (cap, dedupe,
   order, per profile) and a desktop `actual` round trip, `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest
   --tests '*RecentSearches*' --console=plain`. About 150 lines.
6. **Recent searches on the empty field** (FR-7). `SearchScreen.kt` and `SearchStore.runNow(query)` with no debounce.
   Proof: `SearchFocusTest` cases (chips drawn, OK searches at once, Clear empties, Down order), the slice 4 Gradle line.
   About 120 lines. **Junior-ready**, after slice 5.
