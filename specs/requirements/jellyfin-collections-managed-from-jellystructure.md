# Jellyfin collections are managed from jellystructure, and Ravilo filters by them

Task: DEB-4032

> Owner, 2026-11-08 (DEB-4032): *"Lets add support for collections (jellyfin collections). We need to be able to
> manage them and create/edit/delete them directly within Jellystructure, but the database for it is happening
> directly within jellyfin. As a first run we need all the jellyfin part and add filters for it, so we can filter by
> a collection within the movies tab in ravilo."*

**Owner's answers** (DEB-4032 card `9a0b52f3`, 2026-11-09):
- **Where the filter shows:** every browse page whose titles are in at least one collection. That means Movies,
  Series, *See all* and the phone's Library.
- **One chip for both kinds.** The browse page's existing *Channel* chip lists Ravilo collections. It is renamed
  **Collection** and lists Jellyfin collections as well. *"Let's add some icons on the options there, so when it's a
  big jellystructure collection, then it has one small icon beside it and when it's a normal collection stored in
  jellyfin etc, then it has another small icon beside it."*
- **Franchises:** collections are *"hand-made only. But add support in jellystructure to create a collection from a
  suggestion, and this suggestion is based on this franchise setup from tmdb."*

## Scope

Backend, admin and Ravilo. It touches the backend's Jellyfin client, a new admin page, the admin title page, the
seeded-browse wire (two additive fields), and the Ravilo browse page's facet bar (R187, R325).

Three things in this product are called *collection*. This spec keeps them apart:

| Name in this spec | What it is | Where it lives |
|---|---|---|
| **Ravilo collection** | an operator-built `ChannelConfig` (the *Collections* / *Samlinger* section) | jellystructure's Ravilo config |
| **franchise** | TMDB's `belongs_to_collection`: `MediaItem.collectionId` / `collectionName` (phase 269, DEB-2837) | derived from TMDB, read-only |
| **Jellyfin collection** | a Jellyfin `BoxSet`: a hand-made list of titles | **Jellyfin's database only** |

- **Jellyfin is the only store of Jellyfin collections.** jellystructure keeps no table of them. It keeps an
  in-memory cache that it can rebuild at any time from Jellyfin. Every change it makes is written to Jellyfin, and
  the cache is then re-read.
- **No design round for the admin.** The admin pieces are built from controls it already has: the Tags page's
  list/rename/delete pattern, `cfg-row`, `btn`, `input`, and the workbench's title search.
- **A small design round for Ravilo:** two glyphs and the facet popover row that carries them (FR-9).

**Not in this spec:**
- a workbench condition on a Jellyfin collection, so that a Ravilo row is built from one;
- a collection's poster or overview;
- a collection page in Ravilo;
- adding a title to a collection from inside Ravilo;
- making a collection automatically from a franchise (the owner answered *hand-made only*).

## Requirements

### FR-1 — The Jellyfin client knows collections

`JellyfinClient` gains collection calls. They all use the server key (`ApiKeys.jellyfinToken`), never a viewer's
token:

- **list:** `GET /Items?IncludeItemTypes=BoxSet&Recursive=true`, giving id and `Name`.
- **members:** `GET /Items?ParentId={boxSetId}`, giving each member's id and `Type`.
- **create:** `POST /Collections?Name=…&Ids=…`, answering the new id.
- **add:** `POST /Collections/{id}/Items?Ids=…`, batched as `addToPlaylist` is.
- **remove:** `DELETE /Collections/{id}/Items?Ids=…`.
- **rename:** read the collection's full item, change `Name`, and send the whole item back to `POST /Items/{id}`. A
  partial body is never sent, because Jellyfin replaces the item with whatever it is given.
- **delete:** see FR-2.

Each call carries a verification note in code (`verified against Jellyfin 12.x, <date>`) written when it is built,
per the constitution's *Supported Jellyfin* rule.

### FR-2 — Deleting a collection can never delete a title

`DELETE /Items/{id}` deletes a film's or series' **files** when the id is a title's. So:

- the delete call is its own function, `deleteCollection`. It first reads the item and **refuses unless its `Type`
  is `BoxSet`**, and in that case it never sends the `DELETE`;
- no other code path deletes a Jellyfin collection, and the existing `deleteItem` is never used for one;
- a test against a fake Jellyfin proves that a film's id, a series' id and an unknown id each send no `DELETE`, and
  that a `BoxSet` id sends exactly one;
- deleting a collection leaves every member title, its files and its NFO untouched. Only the collection goes.

### FR-3 — The collections cache

A `JellyfinCollections` service holds, in memory, every Jellyfin collection as `(id, name, member title ids)`.

- **Mapping members:** a member is mapped to a jellystructure title by `MediaItem.jellyfinId`. A member that is no
  title jellystructure holds (an episode, a season, music, a title in a skipped library, an unknown id) is ignored.
  A collection with no mapped members is still listed in the admin (FR-6), and is never offered to a viewer (FR-8).
- **Refreshing:** at start-up, every 5 minutes, after a library scan, and straight after each of jellystructure's
  own writes (FR-5). A change made in Jellyfin's own dashboard therefore shows within 5 minutes.
- **Reads never wait on Jellyfin** (phase 205). A reader gets the last good copy. Before the first refresh lands it
  gets an empty one, and a failed refresh keeps the previous copy and logs one line.

### FR-4 — The wire: which collections a browse card is in

Two fields are added. Both are additive and have defaults, so an installed app ignores them:

- `BrowseCard` gains `@SerialName("box_sets") val boxSets: List<String> = emptyList()`: the ids of the Jellyfin
  collections that hold this title.
- `SeededBrowseResponse` gains `@SerialName("box_sets") val boxSets: List<BoxSetFacet> = emptyList()`, with
  `BoxSetFacet(id: String, name: String)`. It names each Jellyfin collection that appears on at least one of the
  response's `items`, and only those.

`POST /api/tv/browse/seeded` fills both from the cache (FR-3). Because `items` are already what this viewer may see
(DEB-2518), a collection whose only members are hidden from the viewer is not named to them.

### FR-5 — Admin routes

All of these are behind the admin's authentication. Titles are named by jellystructure media id. Every write
answers with the collection's fresh state, **re-read from Jellyfin after the write**, never echoed from the
request:

- `GET /api/jellyfin-collections`: every collection with its name, its mapped members and its unmapped-member count.
- `POST /api/jellyfin-collections` with `{name, items}`: create.
- `PATCH /api/jellyfin-collections/{id}` with `{name}`: rename.
- `POST /api/jellyfin-collections/{id}/items` with `{items}`, and `DELETE /api/jellyfin-collections/{id}/items/{mediaId}`:
  add and remove.
- `DELETE /api/jellyfin-collections/{id}`: delete, through FR-2.
- `GET /api/jellyfin-collections/suggestions`: FR-7.

**Validation:**
- A name is trimmed, cannot be blank, and is at most 200 characters.
- A name that an existing collection already has (case-insensitive) answers 409 with *A collection called "…"
  already exists.*
- An unknown media id answers 400 naming it.
- A Jellyfin failure answers 502 with a sentence naming the step that failed, and nothing is assumed to have
  happened.

### FR-6 — The admin page and the title page

**A *Jellyfin collections* page**, linked in the admin's Library section:
- **The list:** one row per collection with its name, *N films · M series*, *Edit* and *Delete*. *New collection*
  sits at the top.
- **The editor:** the name field (rename on blur or Enter), the members with ✕ to remove each, and a title search to
  add films and series. It uses the workbench's existing title search.
- **Delete** asks *Delete "…"? The collection goes; its films and series stay.*
- **Unmapped members:** a collection with unmapped members says *Also holds N items jellystructure doesn't manage.*
  It never removes them.
- **Suggestions:** the page's second section, FR-7.

**The title page** (`MediaDetail`) gets a *Collections* line. It shows a chip for each Jellyfin collection the title
is in, with ✕ to remove it, and *Add to collection…*, which lists the collections it is not in plus *New collection*.

**Rendering:** both pages render what the routes answer, with no optimistic state (CLAUDE.md). A failed write shows
the route's sentence and leaves the page as the server last described it.

### FR-7 — A suggestion made from a franchise

`GET /api/jellyfin-collections/suggestions` answers one suggestion per franchise that:

- has at least two films jellystructure holds, which is the rule `franchisesOf` already applies (DEB-2837 FR-6.2);
- has a display name, per `franchiseDisplayName` (FR-6.3);
- is not already covered. A franchise is covered when a Jellyfin collection has its display name
  (case-insensitive), or holds every one of its films.

A suggestion carries the franchise's display name and its films in release order. They are sorted the way
`franchisesOf` sorts: the franchise with the most recently added film first.

**The admin's *Suggestions* section** lists them as *Name — N films* with a **Create collection** button. The
button opens the *New collection* editor already filled in: the name, with every film ticked. The operator may
rename or untick films before *Create*, and nothing is made until they press it. There is no dismiss: a suggestion
disappears once it is covered, and the owner can ask for a dismiss later.

### FR-8 — Ravilo: one *Collection* chip, two kinds of value

The browse page's `BrowseFacetKey.CHANNEL` chip becomes the **Collection** chip:

- **The label:** `browse.facet.channel` is renamed in place, to en **Collection** and da **Samling**. fo keeps
  **Savn**, which already means *collection*. The key stays the same, so an installed app shows the new word once
  its strings update and nothing breaks before then.
- **Values:** the chip's values are the Ravilo collections the cards carry (`BrowseCard.channels`, as today) plus the
  Jellyfin collections they carry (`BrowseCard.boxSets`, FR-4). The client keeps the two apart inside its own state,
  for example by prefixing a Jellyfin id with `box:`. Nothing new goes over the wire.
- **Labels:** a Ravilo collection's label is its configured name (`channelNames`), as today. A Jellyfin collection's
  label is its `BoxSetFacet.name`.
- **Selection** works as every facet does: OR inside the chip (a title in any chosen collection), AND with the other
  chips. Counts come from `valuesFor()`, as today.
- **Order:** by count, as every facet. The glyph (FR-9) is what tells the two kinds apart.
- **When the chip shows:** when at least one card carries either kind. This covers Movies, Series, *See all* and the
  phone's Library, which all use `SeededBrowseScreen`.
- **The chip's summary** (*two names, then +N*) uses the same labels.
- **Older pairings keep working.** An installed app on a new server shows the chip as it does today, under its old
  word until its strings update, listing Ravilo collections only. A new app on an older server gets no `box_sets` and
  lists Ravilo collections only.

### FR-9 — Ravilo: each value carries a small glyph saying which kind it is

Every row in the Collection popover, on the TV, phone, browser and desktop, carries a small glyph before its name.
There is one glyph for a **Ravilo collection** and another for a **Jellyfin collection**.

- The glyphs are drawn in a design round (Rannvá). They join the hand-drawn glyph set the app already has
  (`DeskIcons.kt`, `MusicGlyphs.kt`).
- The round fixes the glyphs' size, ink, focus-state ink and position. The build follows the round.
- A glyph changes nothing about focus. The popover's d-pad rules (R187 FR-RV-BROWSE1-5/-8, R362) are unchanged, and
  the row is still one focus target.
- The glyph is decoration. A screen reader reads the name followed by the kind, with one new string each:
  `browse.facet.collection.kind.ravilo` (en draft *Ravilo collection*) and `browse.facet.collection.kind.jellyfin`
  (en draft *Hand-picked collection*). da and fo are drafted through the lexicon. A viewer never reads the word
  *Jellyfin*.

## Acceptance

1. **Delete safety.**
   - FR-2's test passes. A film, a series or an unknown id sends no `DELETE`.
   - In the admin, deleting a collection leaves its films playable and their files present.
2. **Jellyfin is the store.**
   - A collection made in the admin appears in Jellyfin's dashboard with the same name and members.
   - A collection made in Jellyfin's dashboard appears in the admin, and in Ravilo's chip, within 5 minutes.
   - Restarting the backend loses nothing.
3. **Writes answer the truth.**
   - Rename, add and remove each answer the state re-read from Jellyfin.
   - A duplicate name answers 409.
   - A Jellyfin failure answers 502 and the admin page still shows the old state.
4. **The chip.**
   - On Movies, a title in Jellyfin collection *A* and Ravilo collection *R* counts under both.
   - Choosing *A* shows exactly the films in *A*. Choosing *A* and *R* shows their union.
   - Each value carries its kind's glyph.
   - A page whose titles are in no collection of either kind shows no chip.
5. **Visibility.** A viewer whose tag filter hides every member of a collection is not shown that collection's
   name. The test checks the response's `box_sets`, not only the UI.
6. **Suggestions.**
   - A franchise of three held films with no matching collection is suggested.
   - Creating it from the suggestion makes the collection with those films, and the suggestion disappears.
   - A franchise of one film is never suggested.
7. **Back-compat.**
   - The R319 wire baseline differs only by the two added fields.
   - An installed-app decode of the new response succeeds, and its chip lists Ravilo collections as before.
8. **No title in the repository.** Tests and fixtures use stand-in names (*Collection A*, *Franchise B*).

## Build order

Three build slices for one engineer, each `blocked` by the one before. The design round runs alongside slices 1
and 2:

1. **Backend: client, cache and wire** (FR-1 to FR-4). No viewer-visible change: an installed app ignores the
   fields, and nothing reads them yet.
2. **Admin: management and suggestions** (FR-5 to FR-7). With this slice the owner can make collections.
3. **Ravilo: the Collection chip** (FR-8, FR-9). It is blocked by slice 2 and by the design round. With it, viewers
   filter by collection.
