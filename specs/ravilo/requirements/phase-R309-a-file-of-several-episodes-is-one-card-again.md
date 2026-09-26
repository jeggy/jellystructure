# Phase R309 — A file that holds several episodes is one card again

> Owner, 2026-09-26: *"We did this whole design for Johnny Bravo series, where a single media file, could
> be 3 episodes and have nice graphics for it. Now it's just one episode in ravilo. So there is episode 1
> and then episode 4 etc. We had everything in place, like the minute shower as well. currently it shows
> one episode to be 7-8 minute, but this is not one episode it's 3 episodes and should be around
> 20minutes etc."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). **A regression of Phase 149 / R179**, not a new design: nothing new is drawn, and the
combined card, the triptych and the copy all still exist in the code. Needs **both halves**: backend
(`DetailService`, `HomeFeedService`) and client (`SeriesDetailScreen`, `HomeScreen`). Either alone still
shows one card per file (see FR-R309-2). **Open questions decided the same day**: the owner handed the
calls over (*"You just decide for me. We want all best solutions for everything"*). See *Decisions* at
the end. **Numbering:** verified against `STATUS.md` the same day — Ravilo taken through **R307**.

## What is wrong, measured

Johnny Bravo on production, 2026-09-26: the scanned series holds **165 episodes in 60 files**. 163 of
them live in 2- or 3-episode files (`S01E01E02E03.mkv`), each carrying `partCount` 2 or 3 as Phase 149
intended. `GET /api/tv/series/{id}`, asked with a real TV's device token, answers **60 episodes, one per
file**: season 1 is E1, E4, E7, E9, E15, E18… Each renders as an ordinary `EpisodeCard`, *"E1 · Afsnit
1"*, with a **7m** badge. That is one episode's TMDB runtime, while the file plays for 21 min 23 s.

It is not only this series. Across the library, **7 series have multi-episode files: 283 episodes in
118 files, and Ravilo shows 118 of them, so 165 episodes are missing from every Ravilo series page**.
They are also missing from the season's episode count and from the player's episode list.

## Why: three correct changes that add up to a wrong one

1. **2026-07-12, R179.** The rail groups the season's episodes by **file** (`TvEpisode.file`) and draws
   a group of two or more as one `MultiEpisodeCard`. Each contained episode was its own `TvEpisode` with
   its own `id`. At the time only the first part had a Jellyfin id: Jellyfin lists a multi-episode file
   as **one** item numbered by its first episode, and the scanner joined ids by
   `(season, IndexNumber)`. So parts 2 and 3 got the fallback id `path#2` / `path#3`.
2. **2026-07-26, the auto-play-next loop fix (`d75c5682`).** Two *different files* claiming one
   `(season, episode)` had been handed one Jellyfin id, and the rail made two slots of them. The fix
   added "an id occupies exactly one entry" on both sides: `tvEpisodes.distinctBy { it.id }`
   (`DetailService.kt`, the `Season(...)` build) and `episodes.distinctBy { it.id }` in
   `episodeGroups` (`SeriesDetailScreen.kt`). This was harmless then, because the parts of a file still
   had distinct ids.
3. **2026-07-30, Phase 152 (`5f840ceb`).** When `(season, episode)` finds no Jellyfin item, the scanner
   falls back to matching the **path**. Parts 2 and 3 have the same path as part 1, so they now get
   **the file's Jellyfin id**. That is correct in itself: the file *is* one Jellyfin item, and playing
   any of its episodes plays that item.

Net effect: every part of a file now carries the same id, and both "one id, one entry" rules keep only
the first. The oldest readable production backup (2026-09-05) already has all three parts sharing one
id. Neither the scanner nor the card is wrong. The dedupe rule is wrong: the loop fix needed **one id,
one file**, not one id, one entry. The case it guarded against was two files claiming one id, never
three episodes of one file.

Two more places show the same file as a single episode, even when the group survives:

- The player's title for a group is the hard-coded English string `"Episodes 1-3"`
  (`episodeDisplayTitle`, `SeriesDetailScreen.kt`), while the card beside it says *Partar 1–3* in
  Faroese through `up.episodes_range`.
- The series page's Resume button names one episode (*Resume · S1E1*) while the kicker already says
  *S1 · E1–3*.

## Requirements

**FR-R309-1 — The series payload carries every episode of a multi-episode file (backend).** The
last-line dedupe in `getSeriesDetail` becomes **per file**: several entries may share an id when they
share one `file`. An entry is dropped only when its id already belongs to an entry from a **different
file** earlier in the season. That keeps exactly the guarantee the loop fix exists for: no id ever
reaches a client from two files. `DuplicateEpisodes.deduped` (one entry per `(season, episode)`) is
unchanged. It already treats the parts of one file as distinct keys, and says so in its doc comment.
The rule is one pure function, unit-tested with a 3-in-1 file (three entries kept), two files claiming
one id (the first file's entries only), and a mix of both in one season.

**FR-R309-2 — The rail and the player list group by file first (client).** `episodeGroups` applies the
same rule as FR-R309-1: group by `file`, and drop a group whose id already belongs to an earlier group.
It does not drop entries inside a group. Without this half, a fixed backend still produces one card per
file on the TV. Without the backend half, a fixed app never receives parts 2 and 3. **An app installed
before this phase keeps showing one card per file even after the backend ships.** That is unavoidable,
because the collapse happens in the installed code, and it is why the two halves ship together.

**FR-R309-3 — The id stays shared, on purpose.** Do not mint per-part ids. The file is one Jellyfin
item, so playing, resuming, the playstate overlay, Continue Watching, the watched toggle and cast all
act on the **file**:

- Select plays the file from its resume point, as R179's `groupEntryPoint` already does.
- Marking the card watched marks the file, and all of its episodes read watched.
- The season's *N of M watched* counts **episodes**, as R179 §C2 requires. A watched 3-in-1 file counts
  three.

Anything on the series page that indexes by episode id must tolerate several entries with one id inside
a group. The rail's `LazyRow` key (the group's first id) stays unique across groups by FR-R309-2's rule.

**FR-R309-4 — What the card shows is R179's card as built.** The range (*Episodes 1–3* / *Afsnit 1–3* /
*Partar 1–3*), the triptych of the three stills, *1 file · 3 episodes · 21m*, and a duration badge with
the combined runtime (the sum of the contained episodes' runtimes, as R179 specified). Nothing new is
drawn and no string is added.

**FR-R309-5 — The file is named as a range wherever it is named as one thing.**

- The player's title for a group uses `up.episodes_range` in the viewer's language instead of the
  hard-coded `"Episodes {a}-{b}"`. It is built outside a composable today, so it needs the
  non-composable string lookup or to be resolved before the context is built.
- The series page's Resume button names the file's range (*Resume · S1E1–3*), matching the kicker.

**FR-R309-6 — A regression test on each side, in production's exact shape.** Three `TvEpisode`s with one
id, one `file`, `part_count` 3 and `part_index` 0/1/2. Server: `getSeriesDetail` over a series whose
parts share a `jellyfinId` returns all three. Client: `episodeGroups` returns one group of three, and
still returns one group for two files claiming one id (the loop fix's own case, kept as a test). Home:
a resume candidate on a 3-in-1 file carries `episode_number` 1 and `episode_number_end` 3, and a lone
episode carries no `episode_number_end`.

**FR-R309-7 — Continue Watching and Next Up name the range too.** The Home tile for a multi-episode
file reads *S1:E1–3*, not *S1:E1* (R113's on-image badge, `HomeScreen.kt`), and Next Up's label reads
*S1E1–3 · …*. The backend already has what it needs when it builds these cards (`HomeFeedService`'s
resume and next-up candidates): the played item's Jellyfin id and the scanned series. The contained
episodes are the scanned episodes carrying that id, the same set FR-R309-1 keeps together.

- `MediaCard` gains an additive `episode_number_end`. It is present only when the card's file holds
  more than one episode, and it is the highest contained episode number. `episode_number` stays the
  lowest.
- The client draws `S{season}:E{episode}–{episode_number_end}` when the field is present, and the badge
  exactly as today when it is absent. An app installed before this phase ignores the field and keeps
  *S1:E1*.
- Next Up's label is composed server-side today (`"S${s}E${e} · ${play.name}"`), so the server writes
  the range into it.
- The same helper resolves the range for both candidates, and for R199's fallback numbering too, so a
  tile, its Next Up label and the series page never disagree about which episodes a file holds.

## Non-goals

- **Per-episode watched state inside a file** (R179 §C2's "finishing a chapter marks that episode
  watched"). It was never built, and none of the affected files has chapters (Johnny Bravo's: zero).
- **The combined runtime vs the file's real length.** The sum of TMDB runtimes is what R179 drew: 21m
  for a file that plays 21:23, and 23m for a 2-in-1 file that plays 21:01. It stays.
- The admin's series page, which renders the scanned episodes directly and is unaffected.

## Acceptance

1. `GET /api/tv/series/{id}` for Johnny Bravo answers **165** episodes (today 60). Season 1's first
   three entries share one id and one `file`, with `part_count` 3.
2. On the TV (and the phone): season 1 opens with one card, *Partar 1–3* in Faroese, three stills and a
   **21m** badge, then *Partar 4–6*, and so on. A lone episode file is still an ordinary card.
3. Select plays the file from where it was left. The Watched toggle under a card marks it, and the
   season's count moves by the number of episodes in the file.
4. The player's title for that file reads *Partar 1–3* in Faroese, *Afsnit 1–3* in Danish and
   *Episodes 1–3* in English. Resume reads *S1E1–3*.
5. The auto-play-next loop does not return: a season with two files claiming one episode still shows one
   slot for it, and the next-up card after it names the next file (the loop fix's own test passes).
6. The other six series with multi-episode files show one card per file on their series pages.
7. Leave the first file half watched: Continue Watching on Home shows *S1:E1–3* on the tile, and Next Up
   (after finishing it) names *S1E4–6*.

## Decisions (2026-09-26, delegated by the owner)

1. **Continue Watching and Next Up name the range, in this phase** (FR-R309-7). The file is one thing
   everywhere it is named, or the Home tile contradicts the series page it opens. The fix is one
   additive field and one helper, because the backend already holds the scanned episodes when it builds
   the card.
2. **The guard against this happening again is FR-R309-6's tests**, built from production's exact id
   shape (several episodes, one id, one file). They fail on today's code on both sides, which no test
   did for two months. No device-sweep entry is added: a unit test in CI catches this before a release.

## Dev review (2026-09-26, against `main` `0e5e434f`)

The two dedupes are the only ones, and the live payload is exactly what the code produces. Six items.

1. **Server.** `episodes = tvEpisodes.distinctBy { it.id }` (`DetailService.kt:173`) becomes one pure
   function, `oneIdPerFile(episodes)`. Walk the season in episode order, remember the file each id first
   came from, and drop an entry only when its id is already owned by a **different** file. Unit-test it
   in `linuxX64Test` with production's shape (three entries, one id, one `file`) and the loop fix's case
   (two files, one id).
2. **Client.** `episodeGroups` (`SeriesDetailScreen.kt:175-183`) is the only client dedupe:
   `episodes.distinctBy { it.id }.groupBy { file }`. It becomes group-by-file first, then drop a whole
   group whose id an earlier group already owns. Everything downstream already goes through
   `episodeGroups`: the rail (`:748`), `buildEpisodeContext` (`:199`) and the player's episode list
   (`:216-236`).
3. **The player's title.** `episodeDisplayTitle` (`:163-166`) is a plain function, which is why it
   hard-codes English. The non-composable lookup exists: `t(key, lang, vars)` (`i18n/Strings.kt:18`).
   Pass the viewer's `lang` into `buildEpisodeContext` from its composable callers (`LocalLang.current`)
   and use `t("up.episodes_range", lang, mapOf("a" to …, "b" to …))`.
4. **The Resume label** is built at `:593-597` (`"S${s}E${e}"`). Use `episodeGroupRange` (`:150-156`),
   the same helper the kicker uses, so the two cannot disagree.
5. **Continue Watching and Next Up (FR-R309-7).** `resolvedEpisodeNumbers` (`HomeFeedService.kt:1033-
   1037`) already finds the scanned episode by the played item's Jellyfin id. Return the highest
   `episodeNumber` among `mediaItem.episodes` sharing that id as the end of the range. Carry it on
   `MediaCard` as `@SerialName("episode_number_end") val episodeNumberEnd: Int? = null` (beside `:294-295`),
   used by the resume candidate (`:982`) and in the next-up label (`:993-994`). The client's badge is
   `HomeScreen.kt:602-603`.
6. **One release for both halves.** A backend alone changes nothing on installed apps (item 2), and an
   app alone receives one entry per file (item 1). Ship the backend first (harmless alone), then the app
   release. The acceptance runs after both.

**Net effect.** One pure function on each side, a `lang` parameter, one helper reused, one additive
`MediaCard` field, the badge and label. No migration.
