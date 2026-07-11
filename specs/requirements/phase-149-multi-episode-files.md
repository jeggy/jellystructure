# Phase 149 — Multi-episode files: one file, several episodes (FR-MEF1)

> Some episodes ship **three-to-a-file**: `Johnny.Bravo.S01E01E02E03.NORDIC.PDTV.x264-ROCKETRACCOON.mkv`,
> so a 24-episode season lives in **8 files**. Today the scanner maps such a file to **episode 1 only** —
> E02 and E03 get no item and show up **missing** in the Library and in Ravilo. This phase makes
> jellystructure model one physical file as **N episodes** (each with its own metadata, still, watched-
> state), write the correct multi-episode NFO, and present it correctly in the admin — while never
> splitting or re-muxing the file. The Ravilo/phone viewing side is
> **[Phase R179](../ravilo/requirements/phase-R179-multi-episode-file-card.md)**.
> Design mockup: **`design/app/series-johnnybravo.html`** (combined file rows + per-episode artwork
> picker); exploration in **`design/Multi-Episode Files.html`** and **`design/Multi-Episode Files — Option B.html`**.

## Goal
A file whose name spans an episode range (`S01E01E02E03`, `S01E01-E03`, `1x01x02x03`, …) is ingested as
**every episode it contains**, each a first-class item with its own title/overview/still/air-date and
watched-state, all pointing at the same physical file (with chapter offsets when the container has them).
The admin shows one **combined row per file** (expandable to its episodes) and a **per-episode artwork
picker**. A multi-episode file is a **normal, valid state — never a triage issue**.

## Current state (verified)
- The scanner derives an episode from the filename's `SxxEyy`. A multi-episode filename resolves to a
  single episode (the first match), so the other episodes in the file are **never created** — they read as
  gaps in Seasons & episodes, in coverage counts, and in Ravilo's episode strip.
- Jellyfin itself understands multi-episode files (it reads the stacked numbering) and its NFO convention
  is **multiple `<episodedetails>` blocks in one `.nfo`** (or per-episode nfos), one per contained episode.
- `ffprobe` already runs at scan time; when a file has **chapter markers** at the episode boundaries they
  give per-episode start/end offsets. Many files have none (one continuous stream).
- Constitution holds: jellystructure is the metadata/organization layer; it never edits the media stream
  except through the existing track-edit path (mkvpropedit/ffmpeg), which operates on the **whole file**.

## Requirements

### A. Detection & parsing
1. Recognize multi-episode filenames and parse the **full contained range**, not just the first number:
   `SxxEyyEzz`, `SxxEyy-Ezz`, `SxxEyyEyzEzz…` (arbitrary count), `1x01x02`, `Eyy-Ezz`. ~~Widen the existing
   `SxxExx` matcher (it already grew for `S2025E01` in Phase 53) rather than adding a parallel parser.~~
   **(Dev review: this undersells the work — see addendum §1.)**
2. From one physical file, create **N episode records** — one per contained episode number — each carrying
   its own `(season, episode, title, overview, still, aired)` and all referencing the same `filePath`
   **(dev review: this is the existing `Episode.path` field, not a new one — see addendum §2)**.
3. **Chapter detection (ffprobe):** if the container has chapters whose count matches the contained
   episodes, map each episode to a `chapterStart`/`chapterEnd` (ms) and set `hasChapters = true`. Otherwise
   `hasChapters = false` (continuous file) — the episodes still exist as metadata but share one playable
   span. Never *create* chapters; only read what exists. **(Dev review: no chapter reading exists anywhere
   today — greenfield, not an extension. See addendum §3.)**

### B. Data model
1. `Episode` (see [`plan.md`](../plan.md)) gains: ~~`file` (shared physical path)~~ **reuse the existing
   `path: String` field (`model/Media.kt`) — `Episode` already carries the full file path there; a second
   `file` field would just duplicate it under a different name**, `partIndex` (0-based order within the
   file), `partCount`, `chapterStart`/`chapterEnd` (nullable ms), `hasChapters`. A **file group** is the set
   of episodes sharing a `path`, ordered by episode number.
2. Episode **identity is stable across re-scans** — keyed by `(series, season, episode)`, **not** by
   filename position — so per-episode watched-state and artwork survive a re-scan or a rename ~~(reuses the
   Phase 53 `itemId` discipline)~~. **(Dev review: `Episode` has no `itemId`/`id` field at all today, and
   Phase 53's discipline applies only to top-level `MediaItem`s. The actually-relevant existing code —
   `Scanner.kt`'s `syncSeriesEpisodes` — currently re-matches an episode across a rescan by
   `it.filename == file.substringAfterLast('/')`, i.e. by filename equality, to preserve
   title/overview/stillPath/tmdbEpisodeId/guestStars/crew/jellyfinCreatedAt. Once N episodes share one
   filename, that `firstOrNull` collapses all of them onto the same match — **this must be widened to match
   by `(season, episode)` instead of filename**, or every rescan of a multi-episode file corrupts N-1 of its
   episodes' preserved metadata. See addendum §2.)**
3. The additive fields must be tolerated by older consumers (`ignoreUnknownKeys` on the Ravilo DTO), as with
   the Phase 140 `query` field.

### C. Per-episode metadata & artwork
1. Each episode fetches its **own** TMDB metadata (title/overview/still) exactly like a normal single-file
   episode — independent of its file siblings. ~~Per-episode stills are cached/served per the existing
   artwork path (Phase 47/81), one still per episode number.~~ **(Dev review: this claim is false for the
   shared-file case as the path scheme stands — see addendum §4; it needs a real change, not reuse
   as-is.)**
2. The admin **Artwork** surface treats each episode independently: pick one still from TMDB per episode,
   the standard gallery (`.art-cand` `current`/`sel`, ON-DISK ribbon). Changing E02's still touches only E02
   **(contingent on addendum §4's path-keying fix)**.

### D. NFO
1. On **Save → NFO**, write the multi-episode NFO Jellyfin expects: **N stacked `<episodedetails>` blocks**
   in the file's `.nfo` (one per contained episode), each with its own `<season>`, `<episode>`, `<title>`,
   `<plot>`, `<thumb>`, `<aired>`. Round-trips with re-pull. **(Dev review: this is a real change to the
   write-orchestration layer, not just extending the XML builder — see addendum §5; the existing per-episode
   write loop would silently corrupt this today.)**
2. **Sync Jellyfin** re-reads them; Jellyfin then presents the file as its multiple episodes.

### E. Editing & the shared physical file
1. Track edits (reorder / default / forced via mkvpropedit/ffmpeg) operate on the **shared file once** and
   therefore affect **all N episodes**. The editor must state this plainly ("This file contains E01–E03 —
   the edit applies to all three"). Do not offer per-episode track edits for a shared file. **(Dev review:
   confirmed — `MkvpropeditRunner` is genuinely file-level. But the post-edit cache refresh only updates the
   one `Episode` the request resolved, not its siblings — see addendum §6.)**
2. The qBittorrent seeding-guard (Phase 26/37) and cross-seed accounting key on the **file**, so ~~a lock
   covers~~ **(dev review: it's a stateless path-comparison check, not an acquired lock — but the effect is
   the same)** the whole group is naturally covered.
3. **No file splitting / re-muxing into separate files** — out of scope (see Non-goals).

### F. Admin series page (Seasons & episodes + Artwork)
1. **Seasons & episodes:** render **one row per file** (Option B) — `S01E01–E03` · combined runtime · a
   **"3 in 1 file"** badge · the source filename · a **mini-triptych** thumbnail composed of the 3 stills.
   The row is **expandable** to its episodes, each with its chapter offset and (for the shared file) an
   **Edit tracks** entry. A per-file chapter indicator ("chapters" / "no chapters — continuous").
2. **Artwork tab:** a **per-episode picker** — one column per episode (selected still + candidate strip +
   "More from TMDB"), with a **file switcher** to move between the season's files.
3. A multi-episode file is **not** an attention item — no dirty/triage badge for the fact of being multi-
   episode (per-episode issues like a missing still still surface normally).
4. Design target: **`design/app/series-johnnybravo.html`**.

### G. Library, coverage & recency
1. All N episodes count individually in episode totals, coverage/NFO-covered counts (Phase 89), and
   **Newly Added** (each episode carries its own `createdAt`, Phase 108). Each contained episode is
   independently searchable (multi-language search, Phase 29).

## Non-goals
- **No splitting or re-muxing** the physical file into separate per-episode files.
- **No chapter authoring** — only read existing chapter markers; a chapter-less file is fully supported as a
  continuous unit.
- **No per-episode track edits** on a shared file (the stream is one file).
- No new triage type for multi-episode files (they are a normal state).

## Acceptance
- A `S01E01E02E03` file ingests as **three episodes**, each with its own title/overview/still and stable
  watched-state; none read as missing anywhere (Library, coverage, Ravilo).
- The series page shows **one combined row per file**, expandable to its episodes with chapter offsets; the
  **Artwork** tab picks stills **per episode**; a file switcher moves between files.
- **Save → NFO** writes N `<episodedetails>` blocks; **Sync Jellyfin** makes Jellyfin present the episodes.
- A track edit on a shared file **warns it applies to all contained episodes**; the seeding-guard covers the
  whole file.
- Chapter status is shown; a chapter-less file works as a continuous unit with no error.

## Dev-review addenda (2026-07-12) — reconciled with live code

1. **Filename parsing is new code, not a widened regex.** The current matcher
   (`Scanner.kt:23,28,451-457`) is two regexes, each capturing exactly one episode number via `.find()`:
   ```kotlin
   private val SEASON_EP_RE = Regex("""[Ss](\d{1,4})[Ee](\d{1,3})""")
   private val ALT_SEASON_EP_RE = Regex("""\b(\d{1,2})x(\d{2,3})\b""")
   ```
   `parseSeasonEpisode` returns a single `Pair<Int?, Int?>`. Three of the four target patterns need
   genuinely new handling: `SxxEyyEzz…` needs a repeated-group/`findAll` scan for trailing `E\d+` tokens
   after the season anchor; `SxxEyy-Ezz` needs numeric **range expansion** (the middle episode numbers
   aren't in the string at all); `1x01x02x03` isn't matched by `ALT_SEASON_EP_RE` today (it captures exactly
   one episode group) and needs the same repeated-token treatment. Land this as a new
   `parseSeasonEpisodes(path): List<Int>` (season + episode list) alongside the existing single-episode
   function, not a regex edit.

2. **Rescan identity — widen `syncSeriesEpisodes`'s match, not "itemId."** `Episode` has no id field;
   Phase 53's `itemId()` (`Scanner.kt:883`) only ever keys top-level `MediaItem`s. The mechanism that
   actually needs widening is `Scanner.kt:551` (`syncSeriesEpisodes`), which today re-matches an episode
   across a rescan via `item.episodes.firstOrNull { it.filename == file.substringAfterLast('/') }` to carry
   forward title/overview/stillPath/tmdbEpisodeId/guestStars/crew/jellyfinCreatedAt. Change this match to
   `(seasonNumber, episodeNumber)` equality before this phase ships, or every rescan of a multi-episode file
   collapses all N episodes onto the same stale match and corrupts N-1 of them. (Separately,
   `MediaStore.kt:137` already has an `episodeKey() = "$season:$episode"` helper used only for
   `stampEpisodeCreatedAt` — same idea, reusable here.)

3. **Chapters are greenfield.** `FfprobeRunner.kt:64-106` only runs `-show_streams`; `FfprobeOutput` has no
   chapters field and nothing else in the repo reads them. Needs: a new `-show_chapters` invocation (or a
   combined `-show_streams -show_chapters` call to avoid a second process spawn), a new serializable model
   (ffprobe's JSON exposes a top-level `"chapters"` array — `id`, `time_base`, `start`/`start_time`,
   `end`/`end_time`, `tags.title`), and the count-matching logic against contained-episode count described
   in §A.3.

4. **Artwork path collision — must key by episode, not just filename.** `ArtworkDownloader.episodeStillPath`
   derives the still's disk path from the episode's `path`/`filename` (same basename as the video,
   `"$dir/$baseName-thumb.jpg"`, plus a `.src` provenance sidecar). Since a file group's episodes all share
   `path`, every episode's still would land on the **identical** disk path today — E02's fetch silently
   overwrites E01's. Fix: fold `episodeNumber` (or `partIndex`) into the still filename, e.g.
   `"$baseName-thumb-e{episodeNumber}.jpg"`, before per-episode artwork can work at all for a shared file.

5. **NFO writing needs a new write-orchestration path, not just a bigger XML builder.**
   `NfoWriter.writeEpisode(episode, cast)` (`NfoWriter.kt:111`) writes exactly one `<episodedetails>` block
   to `episodeNfoPath(episode)`, derived the same way as the artwork path above (same basename as the
   video). `PipelineStepOps.kt:72` already does
   `for (ep in current.episodes) runCatching { NfoWriter.writeEpisode(ep, current.cast) }` — once a file
   group's N episodes share that nfoPath, this loop resolves all N to the **same file** and each iteration
   overwrites the last, silently dropping every earlier episode's block. Needed: a new
   `writeEpisodeGroup(episodes: List<Episode>)` that concatenates N `buildEpisodeXml`-shaped blocks into one
   atomic write, and updating every call site that currently iterates episodes independently —
   `PipelineStepOps.kt:72` plus `MediaRoutes.kt:440,736,1838,1884` — to group by shared `path` first.

6. **Track-edit cache refresh must cover the whole group.** Confirmed file-level via `MkvpropeditRunner`
   (takes a raw path + stream indices), so §E.1's "applies to all three" is architecturally already true on
   disk. But the post-edit re-probe in `TrackRoutes.kt` only updates the one `Episode` matched by
   `it.filename == ep.filename` (lines 576, 592) — sibling episodes in the group keep **stale** cached
   `tracks` until a full rescan. Update: after an edit, refresh every episode in the file group, not just
   the one the request resolved.

Pairs with **[R179](../ravilo/requirements/phase-R179-multi-episode-file-card.md)** (also dev-reviewed
2026-07-12 — see its own addenda; R179 is blocked on this phase's DTO fields landing first).
