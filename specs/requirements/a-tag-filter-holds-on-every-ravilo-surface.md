# A tag filter holds on every Ravilo surface

Task: DEB-2518

> Owner, DEB-799, 2026-10-26: *"for new users from sso, i don't want them to be admins and they should have access
> to everything, but with a tags filter, so everything that has ‹the household's tag› on it."*

That ask has two halves. This file holds the half that does not wait on how a new account gets its filter: once an
account has an allowed-tag or blocked-tag list in Jellyfin, every Ravilo surface must show that viewer what Jellyfin
lets them play, and nothing else. Ten of the server's twelve accounts already have an allowed-tag list. For two of
them the household's tag is the only entry. So these gaps are live today, single sign-on or not. How a new single-sign-on account gets the filter is decided on
DEB-2518's card, and lands in this file as its own section once it is answered.

## Scope

- **Builds on** Phase 142's tag follow-up (`MediaItem.visibleTo(device)`, `MediaStore.liveItems(device)`,
  `DevicePolicyReconciler`). That covers films and series.
- **Amends** Phase 275 FR-275-4 (music), Phase 280 FR-280-5 (audiobooks) and R188 FR-RV-UP1-2 (Coming Soon). Each
  file carries a dated pointer to this one.
- **Restores, with no amendment, filed as its own build piece:** Live TV. R177's acceptance already says *restricted
  viewers see only permitted channels*. The 2026-08-02 security review's H2 already says a device is never handed the
  server's Jellyfin key. Live TV breaks both today (below).
- **Clients:** none. Ravilo renders what the server serves (R175), so every FR here is server-side. Lists get shorter,
  and no field is added, removed or retyped (FR-9).

## What is true today (measured 2026-10-26, counts only)

**In Jellyfin:**
- The household's tag is on 441 items: 250 films, 189 series and 2 music videos. It is also on 23 seasons (each one's
  series carries it too) and 4 collections.
- No episode carries the tag; episodes inherit it from their series. No music, no audiobook and no live channel
  carries it.
- An account whose only allowed tag is the household's, with all libraries, sees in Jellyfin:
  - 250 films, 189 series and their episodes, and 4 collections;
  - **no music, no audiobooks and no live TV.**

**In jellystructure:**
- The tag is one of jellystructure's own tags (`js-tags.json`), so constitution invariant #6 keeps it through every
  scan and re-pull. Only a manual removal in the admin drops it. The admin is where it is assigned. It reaches Jellyfin
  through the NFO's `<tag>` when the title is saved to Jellyfin.
- jellystructure's catalogue has the tag on 448 titles. Seven of them were tagged in the admin and never saved to
  Jellyfin. Their NFOs and Jellyfin do not have the tag, so Ravilo shows them to a filtered viewer, and Jellyfin
  refuses that viewer the stream.
- **Already filtered by tag:**
  - Home rows, heroes, channels, Continue, Next up and Recommended;
  - Browse, My List, Search and the facets;
  - detail, related, playstate;
  - every playback route and stream URL;
  - cast hand-off and screen pairing;
  - video sessions;
  - the support AI.
- **Not filtered by tag:**

| Surface | Today |
|---|---|
| Music: home, albums, artists, tracks, genres, search, album and artist pages, lyrics, playlist entries, play, favourite | Library grant only (`musicVisible`), so a tag-filtered viewer sees the whole music library |
| Audiobooks: shelf, detail, author, progress, play | Library grant only |
| Sessions list and control, for music and audiobooks | Library grant only |
| Coming Soon, the not-yet-held half | Hidden only for a library-restricted or kids device, despite `UpcomingService`'s own KDoc |
| Discover rows, search, item | A held title the viewer cannot see still reads *Available* and carries its id. Its detail then 404s |
| `GET /tv/config` | Curated hero entries go out with their display title, meta and backdrop even when the viewer cannot see the title. Home itself drops them |
| *Play on…* (`POST /tv/playback/sessions`) | No check that the caller can see the items. `describe()` writes the title and still into the session for any id |
| Live TV list, guide and tune | No per-viewer filter, and tune can return the server key (restored separately, see Scope). The household-tag-only account above sees 0 of the 5 channels in Jellyfin |

## Requirements

**FR-1 — One rule.**
- A viewer sees an item on a Ravilo surface only if Jellyfin would let that viewer's account play it.
- Phase 142's `visibleTo(device)` is that rule for films and series. FR-2 to FR-7 bring the remaining surfaces under
  it.
- An account with empty allowed and blocked lists sees exactly what it sees today. So does an admin.

**FR-2 — Music and audiobooks follow the tag rule** (amends FR-275-4 and FR-280-5).
- **The rule.** jellystructure's music and audiobook rows carry no tags, and in Jellyfin none carries one (measured).
  So, after the library grant:
  - a viewer whose **allowed list is non-empty** sees no music and no audiobooks;
  - a **blocked list alone** hides none of them, because there is nothing for it to match.
- **What it hides.** FR-275-4 already says what *no music* means: no switch, no bar, no row, no search hit. Here that
  covers:
  - every music and audiobook route above;
  - the audio half of the sessions list and control;
  - `/tv/image` for music and audiobook art.
- **One predicate.** `musicVisible` gains the device's tag lists, and every caller passes them. A second predicate
  beside it would drift.
- **The access line.** FR-275-7's line on Users & devices reads *Music: no* for such an account. The tags themselves
  are already on that line.
- **Later.** If music or audiobooks ever carry tags, that later spec reads them here.

**FR-3 — Coming Soon treats a tag-filtered device as restricted** (amends R188 FR-RV-UP1-2).
- A device is restricted for the not-yet-held half when any of these hold:
  - `allowedLibraries != null`;
  - `isKids`;
  - its allowed list is non-empty;
  - its blocked list is non-empty.
- A not-yet-held item has no tags to test. It may arrive carrying a blocked tag, so the conservative default from
  R188 extends to both lists.
- `getDetail` follows the list (FR-RV-UP1-3 unchanged).

**FR-4 — Discover never vouches for a title the viewer cannot see.**
- A title the library holds but `visibleTo(device)` rejects is **dropped** from:
  - Discover's rows and search;
  - the person overflow, which already drops them.
- Its Discover item by id answers 404, as detail does.
- Showing it as *not held* instead would invite a request Seerr then refuses as already available.

**FR-5 — `GET /tv/config` carries only heroes the device can see.**
- A curated hero entry whose item `visibleTo(device)` rejects is removed from that device's copy of the config.
- That covers its id and its display fields. It is the same rule Home already applies to the heroes it renders.

**FR-6 — *Play on…* checks the caller.**
- `POST /tv/playback/sessions` answers 404 for any item the **calling** device cannot see, before `describe()` runs.
  It uses the same `requireVisible` playback uses.
- Its target must be one that `targetsFor(caller)` lists.
- The target device's own policy still gates what it plays, as today.

**FR-7 — The admin says when a filter tag and Jellyfin disagree.**
- **When it fires.** For each tag that any account's allowed or blocked list names, the backend compares:
  - the titles it holds with that tag;
  - the titles Jellyfin answers to `GET /Items?Tags=<tag>&Recursive=true`.

  The comparison runs at most hourly and after each full scan.
- **The finding.** When the two differ, the Dashboard shows one row per tag: *‹n› titles have the ‹tag› filter tag
  here but not in Jellyfin. Viewers filtered by it see them and can't play them.* An inverse count gets its own
  sentence.
- **The action.** One action, *Save to Jellyfin*, queues `write_nfo` and the Jellyfin refresh for exactly those
  titles.
- **What it does not change.** Phase 74's rule that a tag edit saves to the database only stands. This makes the
  disagreement visible instead of silent. It does not write behind the operator's back.

**FR-8 — A policy change still reaches Ravilo within five minutes.**
- `DevicePolicyReconciler`'s five-minute pass and its events-socket refresh already rewrite the device rows and push
  `home_changed`. Every surface in FR-2 to FR-6 reads the device's lists from those rows, never from a cache keyed
  without them.
- The music and audiobook caches' keys gain the policy hash that `HomeFeedService` and `BrowseService` already use.
- `PlaystateCache`'s merged entries are dropped for a user whose policy hash changes. Today an entry cached before a
  policy narrowed stays answerable by id.

**FR-9 — The wire is unchanged.** No field is added, removed or retyped. Installed clients see shorter lists and 404s
where they already handle 404s.

## Acceptance

Each FR has a `linuxX64Test` that builds a device with `allowedTags = [t]` and asserts the surface:

- **FR-2:** music home, album, artist, search, lyrics, playlist entries, play, favourite, audiobook shelf and play,
  and the audio sessions list are all empty or 404. Library-grant-only behaviour is unchanged for a device with empty
  lists. `blockedTags = [t]` alone hides no music.
- **FR-3:** not-yet-held items are absent for allowed-only and for blocked-only devices, and present for an
  unrestricted one.
- **FR-4:** a held title the device cannot see is absent from Discover's rows, search and person overflow, and its
  item 404s.
- **FR-5:** a curated hero for an invisible title is absent from `/tv/config`.
- **FR-6:** `POST /tv/playback/sessions` with an invisible item answers 404 and writes no session.
- **FR-7:** a stored tag set and a stubbed Jellyfin set differing by one title raise the row with *1*. The action
  queues exactly that title.
- **FR-8:** a narrowed policy empties the music caches' answer for that user on the next request.

**On a device, by QA** (a per-run throwaway local Jellyfin account whose only allowed tag is the household's):
- Ravilo phone and Ravilo Web show no music, no audiobooks, no live TV and no not-yet-held Coming Soon.
- No Discover card reads *Available* for a title that account cannot open.
- Every tile it does see plays.
- The admin account, unchanged, sees all of it.
