# An admin names a season

Task: DEB-5321

> Owner, 2026-11-22 (on DEB-5285): *"Let's add support for custom season name overwrites."* The owner wants two
> seasons, one in each of two series, to say which language they are in, before a later task merges the two series.

This spec uses a stand-in series, *The Lighthouse Club*, with two seasons on disk: Season 1 and Season 7. The admin
names Season 7 *Danish dub 🇩🇰*. Season 1 keeps its name from Jellyfin.

## Scope

An admin can give any season of a TV series a name of their own: free text, emoji included. That name wins over
Jellyfin's season name everywhere Ravilo names a season. It survives every rescan and every Jellyfin refresh.
Clearing it brings back today's name.

The work spans both trees:
- the server stores and applies the name;
- the admin's title page edits it;
- the Ravilo player's episode rail and the phone's Downloads show it.

The season picker on every client already shows the server's name. So do the TV series page and the Tizen app. They
show the admin's name **with the backend change alone**, on every installed app.

**Amends**, each with a dated note in its own file:
- [R350](phase-R350-d-pad-first-class.md) FR-R350-6: when the admin has named the season, the rail's header shows that
  name (FR-7 below).
- [A phone downloads titles to watch offline](a-phone-downloads-titles-to-watch-offline.md), *Headings*: a series line
  and a season header show the admin's name (FR-8 below).

**Decided on the task** (Tróndur, 2026-11-22, DEB-5321): this is done now, at medium priority. The name is stored in our
DB, apart from the scanned name. An empty name clears it.

## What is true today (verified 2026-11-22, `main` `85957a71`)

- **Where a season name comes from.**
  - `MediaItem.seasonNames: Map<Int, String>` (R82, `model/Media.kt:284`) is filled only by a full scan, from
    Jellyfin's `SeasonName` (`media/Scanner.kt:612-615`, stored at `:867` and `:937`).
  - A season sync (`Scanner.kt:1190`) keeps the stored map.
  - A full scan rebuilds the map from Jellyfin. So a name edited into the map would be lost at the next scan.
  - The map is keyed by Jellyfin's `ParentIndexNumber`. Both readers look it up by jellystructure's own
    `Episode.seasonNumber ?: 0`. When the two numberings differ, the lookup misses and the reader falls back.
- **The wire.** Two fields carry a season name. Both are filled by `item.seasonNames[n] ?: "Season $n"`:
  - `Season.name` in `SeriesDetail.seasons` (`shared/.../tv/Models.kt:866`, filled at `tv/DetailService.kt:152`);
  - `SegmentSheetResponse.seasonName` (`server/routes/SegmentRoutes.kt:755`), shown by the admin's segments sheet.

  No other DTO carries a season name. Continue Watching, Upcoming, cast messages, the session and watch history carry
  a season number or an `S01E03` code only.
- **Clients that show `Season.name`.**
  - The season picker: `ravilo-ui/.../components/SeasonPicker.kt:244`, on TV, phone, desktop and web.
  - The Tizen app's season pills: `ravilo-screen/.../ScreenDetail.kt:327`.
- **Clients that build their own label.**
  - The player's episode rail (TV) and episode sheet (phone) are headed by `playerRailSeasonLabel`
    (`ravilo-ui/.../screens/PlayerEpisodeEntry.kt:38-45`). That header is `detail.season` + the number for every
    season above 0, by R350 FR-R350-6. `PlayerEpisodeEntry.seasonName` is set from `Season.name`
    (`SeriesDetailScreen.kt:291`) but is read only for Specials.
  - Phone Downloads: the series line and the per-season header read `detail.season` + the number
    (`downloads/DownloadsScreen.kt:558`, `:579`). The name is dropped before that point. `DownloadTarget`
    (`DownloadsController.kt:27`) and `DownloadRecord` (`DownloadLibrary.kt:19`) carry the season number only.
    `DownloadRecord` is stored as JSON with `ignoreUnknownKeys`, so a new nullable field needs no migration.
- **The Chromecast receivers** draw no season label.
- **The admin.** The title page's Episodes tab labels every season `"Season $n"` and never reads `seasonNames`. That
  covers the dropdown, the heading and each season block (`ui/MediaDetail.kt:1591`, `:1595`, `:1628`). Other admin
  spots name a season by its code (`S01`), as an operator needs: subtitle verdicts, checks, track coverage, AV sync,
  artwork, the NFO tree, bulk reorder and *Sync Season*.
- **Storage precedent.** The `media` table stores each `MediaItem` as JSON. A new field with a default needs no
  migration, as with `metadataLanguage`. A scan builds a fresh item, so an admin's field is carried over at the store's
  two write choke points: `preserveNewestFirst` (`media/NewestFirstLock.kt`, `MediaStore.kt:902` and `:985`), whose
  route writes with the guard off (`EpisodeOrderRoutes.kt`).
- **Writing to Jellyfin.** Nothing writes a Season item:
  - `NfoWriter` writes `tvshow.nfo` and the episode `.nfo` files, never a `season.nfo`;
  - `JellyfinClient` has no item-metadata write.

## Requirements

### Server

**FR-1 — The stored name.**
- `MediaItem` gains `seasonNameOverrides: Map<Int, String>`, empty by default. Its key is jellystructure's season
  number, `Episode.seasonNumber ?: 0`, the same number `DetailService` groups by. 0 is Specials, and it can be named
  too.
- It is stored in the item's JSON with no migration.
- It is the admin's alone. No scan, sync, Auto-fetch, re-pull or Jellyfin refresh writes it.
- Both store write choke points carry the stored map onto the fresh item, as `preserveNewestFirst` does, unless the
  write is FR-4's route.
- `seasonNames` (the scanned map) is unchanged and still filled by the scan.

**FR-2 — One function names a season.**
- One server function names a season for a series and a season number. It returns the first that applies:
  1. the admin's name;
  2. the scanned name;
  3. `Season N`, as today.

  A blank value counts as absent.
- `DetailService` (`Season.name`) and `SegmentRoutes.seasonSheet` (`seasonName`) both call it. Nothing else builds a
  season name from a `MediaItem`.

**FR-3 — `Season` says when the name is the admin's.**
- `Season` (`shared/.../tv/Models.kt`) gains `@SerialName("custom_name") val customName: String? = null`. It is the
  admin's name when there is one, and null otherwise.
- `name` stays as it is, with FR-2's value.
- This is an added field only. An installed app ignores it and already shows the admin's name through `name` in its
  season picker. Update `WireCompatTest`'s baseline as an addition.

**FR-4 — The admin sets it.**
- `PUT /api/media/{id}/season-names/{season}` with `{"name": "…"}`, behind the same admin auth as the other
  `/api/media/{id}` routes. The name is trimmed first.
  - An empty name clears the admin's name for that season.
  - A non-empty name is stored. It may be at most 100 characters and may not contain a control character or a line
    break.
  - The route refuses an invalid name, or an item that is not a TV series, with 400.
  - It refuses a season number that no episode of the series has with 404.
- Each change records one media-history line: `season_name_set`, with the season, the new name and the old name and
  its source.
- It answers with FR-5's `season_names` list.

**FR-5 — The admin's read.**
- `GET /api/media/{id}` for a TV series gains `season_names`. It is added beside the item the same way
  `mediaItemWithEpisodeOrder` adds `newest_first`.
- `season_names` is a list, one entry per season that has an episode, in season-number order:
  `{"season": 7, "name": "Danish dub 🇩🇰", "source": "admin"}`. `name` is FR-2's value. `source` is one of:
  - `admin`, for the admin's name;
  - `jellyfin`, for the scanned name;
  - `default`, for `Season N`.

  The admin renders this list and derives nothing.

### Admin

**FR-6 — The Episodes tab names and renames a season.**
- On a TV series' Episodes tab, the season dropdown, the season heading and each season block's heading show
  `season_names`' `name`, not `"Season $n"`. *Unsorted* is unchanged.
- Each season block's heading gets a **Rename** ghost button (`btn sm ghost`):
  - Pressing it puts the name into a text field, with **Save** and **Cancel**.
  - **Save** sends FR-4 and repaints from its answer. Saving an empty field clears the name.
  - A refusal shows the server's error under the field and keeps what was typed.
- When `source` is `admin`, the heading also shows a small muted *custom* tag. When it is not, the field opens empty
  with the current name as its placeholder.
- The same control shows on a series with a single season.
- No other admin label changes. The `S01` codes in the operator cards stay codes.

This is a small surface: one text field, built from classes that already ship. **No design round** (Bárður, DEB-5321).
If a new class is needed, it goes into `design/app/app.css` as the whole styling step.

### Ravilo

**FR-7 — The player's rail shows the admin's name** (amends R350 FR-R350-6).
- `PlayerEpisodeEntry` gains `seasonCustomName: String?`, set from `Season.customName` wherever entries are built from a
  season (`SeriesDetailScreen.kt`, the current-season list and the series list).
- `playerRailSeasonLabel` returns it when it is not blank. Otherwise it returns exactly what it returns today: the
  viewer's language + the number, and the season's own name for Specials.
- This covers the TV's episode rail and the phone's episode sheet. A season the admin has not named still reads
  *Sæson 7* in Danish.
- It needs a client release. Until a viewer's app is updated, the rail keeps today's header and the picker already
  shows the name.

**FR-8 — Downloads shows the admin's name** (amends the Downloads spec's *Headings*).
- `DownloadTarget` and `DownloadRecord` gain `seasonCustomName: String? = null`. It is taken from `Season.customName`
  when the episode is queued.
- The series line, when it names one season, and the per-season header show it when it is not blank. Otherwise they
  show today's `detail.season` + the number.
- It is captured once and not refreshed, because Downloads must work offline. A rename after the download shows the
  next time that episode is queued.
- A record written before this change has no name, and reads as today.

### Merges, moves and Jellyfin

**FR-9 — A name belongs to its series and its number.**
- The admin's name is keyed by the series' item and the season number, and nothing else.
  - **It does not follow a season into another series.** When episodes are moved into series B's season 1, they take
    whatever name B has for season 1.
  - A season that loses all its episodes keeps its stored name, though it is not shown. The name shows again when
    episodes return under that number, as in a file move that lands back in the same series.
  - A series that Jellyfin removes and adds back as a new item starts with no names, as `newestFirst` does.
- **For DEB-5285:** the names are set on the merged series after the merge.

**FR-10 — Nothing is written to Jellyfin.**
- jellystructure does not write the name to Jellyfin's Season item. No write path for a Season item exists today, see
  *What is true today*. Ravilo viewers never see Jellyfin (owner, 2026-10-23).
- The scan keeps reading Jellyfin's own season name into `seasonNames`. The two never overwrite each other.
- Showing the name in plain Jellyfin clients would be its own task (a `season.nfo` writer). It is not part of this
  one.

## Non-goals

- Naming an episode, a film or a series. This is about seasons only.
- Changing the episode code (`S07E03`) anywhere. Continue Watching, Upcoming, cast and the session keep the code.
- A per-language season name. The admin's name is shown as written, in every language.
- Localising the `Season N` fallback on the wire. It stays as it is today.
- The DEB-5285 merge itself.

## Verification

- **Server unit** (`linuxX64Test`, a new `SeasonNameOverrideTest`):
  - The route stores *Danish dub 🇩🇰* on season 7. `DetailService` then sends that season with `name` and
    `custom_name` both set to it, and season 1 with its scanned name and `custom_name` null.
  - A full rescan through each of the two store write choke points keeps the name.
  - An empty name brings back the scanned name. With no scanned name, it brings back `Season 7`.
  - The route refuses each of these: a film (400), a season with no episode (404), 101 characters (400) and a line
    break (400).
  - `seasonSheet` reads the admin's name.
  - `season_names` lists sources `admin`, `jellyfin` and `default` correctly.
  - `WireCompatTest` stays green, with `custom_name` recorded as an addition.
- **Client unit** (`:ravilo-ui:desktopTest`):
  - `playerRailSeasonLabel` returns the custom name for season 7, `Sæson 1` for an unnamed season 1 in Danish, and the
    Specials name for season 0.
  - Downloads: a record with a custom name heads its season with it. A record with no custom name reads as today, and
    so does a stored JSON record without the field.
- **QA** (a QA leg, not this seat). QA may set a name on a prod series and must clear it afterwards. QA should choose a
  series few viewers watch.
  1. In the admin, rename one season to a name with an emoji. The Episodes tab shows it with the *custom* tag.
  2. The season picker shows it on Ravilo web and the Android TV. Test both on the build before this release and on
     the build after it.
  3. On the build after this release, start an episode of that season. The TV's rail header and the phone's episode
     sheet header show the name.
  4. Download an episode of that season on the phone. Downloads heads it with the name.
  5. Wait for the hourly `:30` scan, then reload. The name is still there.
  6. Clear the name. Every place above goes back to its previous label.
