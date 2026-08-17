# Music-video library support — investigation

**Date:** 2026-08-17
**Scope:** what it would take to give a Jellyfin `musicvideos`-collection-type library first-class support in
jellystructure, given that music videos will **never** have a TMDB match.
**Trigger:** a user added a music-video library; nothing in it gets ingested by a scan. Traced why, then
investigated the full blast radius of actually supporting it.
**Status:** investigation only — no code changed, no spec written yet, no implementation decision made.

---

## Why nothing is ingested today

Confirmed by reading `JellyfinClient.kt` and `Scanner.kt` directly. Three independent layers all exclude it,
each one sufficient on its own:

1. **The Jellyfin query itself excludes it.** `getItems()`/`getItemsByParent()`
   (`auth/JellyfinClient.kt:171/182`) both hardcode `IncludeItemTypes=Movie,Series` — Jellyfin never even
   returns music-video items (Jellyfin's own item type for them is `MusicVideo`). A redundant client-side
   `.filter { it.type == "Movie" || it.type == "Series" }` (`:174/185`) would exclude them a second time even
   if the query didn't.
2. **`Scanner.scanItem()`'s dispatch has no branch for it** (`media/Scanner.kt:181-185`):
   `when (jItem.type) { "Movie" -> …; "Series" -> …; else -> null }`. A `MusicVideo` item falls into `else`.
3. **`classifySkip()` already tolerates this gracefully** (`Scanner.kt:1091,1102-1103`): non-Movie/Series types
   return `"unsupported-type"`, which is in the *expected*-skip set (`MediaRoutes.kt:2194`) — so today's
   behavior is a clean, silent no-op, not a crash or a spurious triage/warning entry. Your library config
   (`skip=false`, path mapping) is correct and irrelevant to the problem; the item is never fetched from
   Jellyfin in the first place.

There is **zero** existing code for music videos anywhere in the tree (confirmed via a case-insensitive
search for `musicvideo`/`music_video` across all of `src/`) — this is a genuinely new media type, not a
partially-built feature.

---

## Full blast radius

`MediaKind` (`commonMain/model/Media.kt:6`) is `enum class MediaKind { MOVIE, TV_SHOW }` — exactly two cases,
consumed in two different ways across the tree, which matters a lot for how risky adding a third case is:

### A — Places that will **fail to compile** the instant a third case exists

These are exhaustive `when (kind) { MOVIE -> …; TV_SHOW -> … }` blocks with no `else` — the Kotlin compiler
itself forces every one of these to be touched, which is actually a *feature*: nothing here can be
forgotten.

| # | File:line | What it does |
|---|---|---|
| 1 | `model/Media.kt:274-277` | `MediaItem.recencyKey()` — every "recently added" surface |
| 2 | `nfo/NfoWriter.kt:17-20` | `buildXml()` — picks the whole NFO shape |
| 3 | `nfo/NfoWriter.kt:44-51` | `write()`'s NFO `dir`/`filename` |
| 4 | `nfo/NfoWriter.kt:65-72` | `nfoPath()` |
| 5 | `nfo/NfoWriter.kt:84-91` | `exists()` |
| 6 | `nfo/NfoWriter.kt:96-103` | `readRaw()` |
| 7 | `media/ArtworkDownloader.kt:45-48` | `posterArtworkExists()` |
| 8 | `media/ArtworkDownloader.kt:308-311` | `mediaDir()` |
| 9 | `media/Scanner.kt:904-905,960` | re-pull TMDB dispatch |

### B — Places that will **silently misclassify** a third case (binary `if`/`else`, not exhaustive)

These are the dangerous ones — the compiler won't flag them, so a music video would render/behave as if it
were a movie unless each is deliberately revisited. The full, file:line-cited list (~25 sites) covers:
`MediaStore.kt` (episode/track helpers, all default to "else = movie"), `DetailService.kt` /
`BrowseService.kt` / `HomeFeedService.kt` (Ravilo's card/detail projection — **every one of these currently
maps any non-TV_SHOW kind straight to `MediaKind.MOVIE`** in the shared wire model), `ConditionEvaluator.kt`
(admin-configured Home-row rules only recognize the literals `"MOVIE"`/`"SERIES"`), and the admin frontend's
`MediaDetail.kt` (~16 sites — director/network field swap, TMDB deep-link builder, edit-form save logic,
NFO-tree kind check). Full list with line numbers is in the session transcript this report is based on;
worth re-deriving with a real grep pass at implementation time rather than trusting this list as exhaustive
forever, since the tree will have moved on.

**Two independent `MediaKind` enums must stay in sync**: the backend/storage one above, and Ravilo's own wire
DTO enum (`shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:8`,
`{ MOVIE, SERIES }`) — every mapping site between the two needs the same third case added in lockstep.

### C — Jellyfin query hardcoding, beyond the two already found

`IncludeItemTypes=Movie,Series` (or `Movie,Episode`) is hardcoded in **six** places in `JellyfinClient.kt`,
not just the two scan-entry ones already identified: `getItems` (:171), `getItemsByParent` (:182),
`findRecentItemByPath` (:301, `Movie,Episode`), `getResumeItems` (:366, `Movie,Episode`),
`getRecentlyPlayed` (:391, `Movie,Episode`), `getFavoriteItemIds` (:582, `Movie,Series`). Whether music
videos should ever appear in "resume"/"recently played"/"favorites" is itself a product question (see Open
Questions) — some of these six may deliberately stay Movie/Series-only.

---

## Answering the user's specific concern: TMDB is never expected

This turns out to be **easier than the blast radius above suggests**, because the codebase already treats a
missing `tmdbId` as a non-error almost everywhere:

- **`TriageDetection.kt` never reads `tmdbId` at all.** None of its 14 triage types are TMDB-match-related —
  they're all track-tagging/file-quality issues (untagged tracks, missing stills, zero-audio, cascade
  mismatches, segment-confidence). **A missing TMDB match does not put an item in triage today, for any
  kind.** The user's mental model ("triage vs. expected") maps onto something that doesn't currently exist as
  a triage type — good news, since it means there's no existing "missing TMDB ⇒ triage" behavior to gate
  per-library; it only needs building once, correctly, from the start.
- **The one real "missing TMDB" alarm is a global scan-completion webhook**, not a triage row:
  `notifyOnNoMatch` in `Main.kt:475-479` and duplicated in `MediaRoutes.kt:2214-2217` —
  `unmatched = items.count { it.tmdbId == null }` counted across **every item from every library**, no
  per-library scoping. This is the one concrete place that would need a per-library "don't count this
  library's items" flag, or the webhook becomes permanently noisy the moment a music-video library exists.
- **The scheduled `pull_tmdb` "missing" step would retry forever, for nothing**: `Main.kt:531-533` filters
  `workingSet` to `it.tmdbId == null` when `scope != "all"` — a music-video item can never satisfy that, so
  every scheduled run would re-issue a doomed TMDB search call for every music-video item, indefinitely. This
  needs the same per-library gate, or the step needs to skip non-TMDB-eligible items entirely.
- **`ArtworkDownloader` already handles a missing `tmdbId` gracefully** — poster/backdrop fetch is gated on
  `posterPath`/`backdropPath` being non-blank (never populated without a TMDB match), not on `tmdbId` itself;
  a music video would just silently get no automatic artwork, not an error. Manual artwork upload
  (`saveAsset()`) already works independent of `tmdbId`.
- **`NfoWriter` writes `<tmdbid>`/`<uniqueid type="tmdb">` conditionally** (`if (item.tmdbId != null)`,
  `NfoWriter.kt:248`) — already the right pattern, just needs the same treatment inside a new
  `buildMusicVideoXml`.

So the user's instinct — "movies/series expect a TMDB id, music videos don't" — is really about **two
specific, well-isolated call sites** (the webhook counter, the `pull_tmdb` retry filter), not a
codebase-wide assumption. This significantly shrinks the risky part of the design.

---

## NFO shape gap

Kodi/Jellyfin's `musicvideo.nfo` schema is meaningfully different from `movie.nfo`/`tvshow.nfo` — it wants
`<artist>`, `<album>`, `<track>` in place of `<director>`/`<studio>`/season-episode structure. None of that
vocabulary exists anywhere in `NfoWriter.kt` today; a real `buildMusicVideoXml` would be new code, not a
variant of the existing two builders.

---

## Recommended shape for further discussion (not a decision)

Three design questions worth resolving before writing a real spec:

1. **A new `MediaKind.MUSIC_VIDEO` enum case is the right lever, not a config flag alone.** The user's
   instinct to make TMDB-expectation configurable *per library* is right for the webhook/pull_tmdb gating
   (§ above), but the deeper need — a different NFO shape, a different "primary person" field
   (artist vs. director), no seasons/episodes, a different Ravilo detail DTO — is a *kind* difference, not a
   *policy* difference. Recommendation: **both** — add `MUSIC_VIDEO` to `MediaKind` (forces every call site in
   §A to be handled, which is the safety net you want for something this cross-cutting) **and** add a
   library-level config flag (see #2) that controls the TMDB-expectation *behavior* (triage/webhook/pull_tmdb
   gating), since that's a policy an admin might reasonably want to toggle independent of the item's kind
   (e.g. a "home videos" library might also never have TMDB matches, for the same reason).
2. **Library-level flag, not item-level.** `LibraryMapping` (`AppConfig.kt:244-252`) has no existing
   behavior-toggle precedent to model this on (`skip: Boolean` means something different — exclude from
   scanning entirely). A new field like `requires_tmdb: Boolean = true` (defaulting to today's implicit
   behavior for movies/series) would gate: the `notifyOnNoMatch` webhook counter, the `pull_tmdb` "missing"
   scope filter, and — worth deciding — whether the admin UI's "Recently processed"/attention-needed surfaces
   ever surface a TMDB-related nudge for that library's items.
3. **Where does music-video metadata come from, if not TMDB?** TMDB has no music-video entity at all. Options
   worth scoping in a real spec: (a) filename/folder parsing only (artist/track from path, matching how the
   scanner already does season/episode filename parsing per phase 152/160), (b) Jellyfin's own metadata if a
   Jellyfin plugin populates it (would need a new `JellyfinClient` read path), (c) MusicBrainz (a real,
   free API with a proper music-video/recording model) as a genuine second metadata provider — a bigger lift
   (new client, new config section, new matching logic) but the only option that gives real
   artist/album/genre data rather than filename-derived guesses. This is the single biggest open scoping
   question and should be resolved before any implementation spec is written, since it changes almost
   everything downstream (NFO fields, triage types, artwork sourcing).

---

## What a real implementation would touch (rough shape, not a commitment)

- `MediaKind` (+ Ravilo's parallel enum) — new case, propagated through every §A/§B site.
- `JellyfinClient` — `MusicVideo` added to the relevant `IncludeItemTypes` queries (product decision needed
  per query, not blanket — see §C).
- `Scanner.scanItem` — new `"MusicVideo" -> scanMusicVideo(...)` branch; likely closer to `scanMovie`'s
  single-file shape than `scanSeries`'s multi-episode shape.
- `NfoWriter` — new `buildMusicVideoXml`, new filename convention.
- `AppConfig.LibraryMapping` — new `requires_tmdb` (or similarly named) field, plumbed through
  `ConfigApi.kt`/`Settings.kt`.
- `Main.kt` / `MediaRoutes.kt` — gate `notifyOnNoMatch` and `pull_tmdb`'s missing-scope filter on the new
  library flag.
- `DetailService`/`BrowseService`/`HomeFeedService` — real third branch instead of the current silent
  fallback-to-MOVIE; a new shared DTO (`MusicVideoDetail` or similar) if Ravilo is to render it distinctly
  (artist field, no cast/seasons).
- Admin frontend (`MediaDetail.kt`, `Library.kt`) — dedicated filter chip, artist-labeled field instead of
  director/network toggle, TMDB-link builder either omitted or pointed at whatever metadata source is chosen.
- New/chosen metadata source client (§3 above) if going beyond filename-only.

---

## Open questions for the next round

1. Filename-only vs. Jellyfin-plugin vs. MusicBrainz for metadata (§3) — the decision that shapes everything
   else.
2. Should `requires_tmdb=false` libraries be excluded from `notifyOnNoMatch` entirely, or counted separately
   in the webhook payload (e.g. `"unmatched_expected": N, "unmatched_unexpected": M`)?
3. Do music videos belong in Ravilo's existing Movies tab (with a badge), a new dedicated tab, or folded into
   a generic "Music Videos" row keyed off `MediaKind.MUSIC_VIDEO` the same way Live TV got its own row?
4. Should `pull_tmdb`'s "missing" scope simply skip any item whose kind doesn't support TMDB, or should the
   whole step become kind-aware (`scope="missing"` meaning something different per kind, e.g. "missing
   MusicBrainz match" for music videos)?
5. Track editing / segment detection (Skip Intro etc.) — do these make sense for a music video at all? They're
   generic per-file audio/video track operations today (not movie/series-specific), so they'd likely "just
   work" once a music video is a stored `MediaItem` with a `tracks` list — worth confirming, not assuming.

## Method

Two focused Explore passes: first traced the exact ingestion-blocking code path (JellyfinClient → Scanner →
classifySkip) with file:line citations; second traced every TMDB-assumption and every `MediaKind`-branching
site across the full backend, shared model, and both frontends (admin wasmJs + Ravilo), distinguishing
compile-time-safe (exhaustive `when`, will force a fix) from silently-unsafe (binary `if`/`else`, won't).
Everything above is cited against the current tree; no code was changed, no spec written, no design finalized.
