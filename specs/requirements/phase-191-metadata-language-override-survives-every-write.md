# Phase 191 — the chosen metadata language must survive every path that writes metadata

> Live report: *"`…/media/c045ed9c87a18fbb1580cfed748acb86` has the resolved language overwritten. So
> instead of korean, it should be english. And clicking on pull from tmdb, does work. But it seems like
> over time it gets set to the korean name again. So let's make sure that this override is respected in
> all scenarios where things are being overwritten."*

## Status
✓ Built 2026-09-06. Not dev-reviewed, not live-verified against the production database (per the
standing "no build/restart" rule — this needs a real scan/sync run against a live item to fully close
the loop). **Confirmed live against the production database**, with timestamps — see Evidence.

**Implementation notes:**
- FR-191-1/191-2: done. Two shared helpers (`normalizedMetadataLanguageOverride`,
  `overriddenLangPriority`) extracted in `Scanner.kt` and now called from every TMDB-fetching path:
  `scanMovie`, `scanMusicVideo`, `scanSeries` (series-level, the languageMix branch, and per-episode),
  `syncMovie`, `syncSeriesEpisodes` (series-level and per-episode), `syncSeason`, and `rescanMetadata`
  (refactored onto the shared helpers, behaviour unchanged). `rescanFromJellyfin` and realtime/webhook
  ingest needed no separate change — both compose `scanItem` → `scanMovie`/`scanSeries`, which now
  consult `store.resolveByJellyfinId(jItem.id)?.metadataLanguage` directly. Per-episode fetches keep a
  separate `epFetchPriority` from the audio-derived `epLangPriority` used for the episode's own
  `resolvedLanguage` display field — the override changes what language is *fetched*, not the per-episode
  audio-derived display language (Phase 128's contract, left untouched).
- FR-191-3: `MetadataLanguageOverrideTest.kt` covers the two helpers directly (5 cases incl. the exact
  oldboy-2003 shape — Korean-first audio, "en" override). This proves the priority-list construction is
  correct; it does **not** exercise a full mocked `scanMovie`/`Scanner` call (no fake `TmdbClient` harness
  exists in this codebase to build one against without significant new test infrastructure) — flagged
  honestly rather than claimed as full path coverage.
- FR-191-4: no new code, per the spec's own "no new UI" scope — the existing Library metadata-language
  facet + per-item Re-pull button are what an operator uses. Production currently has exactly the one
  known-affected item (oldboy-2003); re-pulling it is an operational follow-up, not something this commit
  does.
- FR-191-5: done — `buildMetadataLanguageCard` (`MediaDetail.kt`) now checks `titlesByLang[chosen]`
  against `item.title` and, on a mismatch, shows an inline warning + a "re-pull to fix" button (wired to
  the same `handleRepull` the page-bar Re-pull ▾ button already uses).
- `compileKotlinLinuxX64` and `compileKotlinWasmJs` both clean; `linuxX64Test` green (including the new
  test — see Acceptance item 7's caveat above).

## Evidence

`c045ed9c87a18fbb1580cfed748acb86` is 올드보이's Jellyfin id; the item is `oldboy-2003`. Read from
`~/jellystructure/config/jellystructure.db` on 2026-09-06:

```
metadataLanguage:      en
metadataLanguageSetAt: 1788422845   → 2026-09-03 08:07:25 UTC
title:                 올드보이
overview:              오대수는 어느 날 술이 거나하게 취해 집에…
resolvedLanguage:      ko
scannedAt:             1788631900   → 2026-09-05 18:11:40 UTC
nfoWrittenAt:          1788633509   → 2026-09-05 18:38:29 UTC
titlesByLang:          { "en": "Oldboy", … }   ← the English title is right there, in the same row
```

The operator's choice is **still stored and still `en`**. Phase 184's `preserveMetadataLanguage` guard
is doing its job perfectly. Two and a half days later a scan re-fetched TMDB **in Korean**, overwrote
`title` / `overview` / `genres` / `posterPath` / `backdropPath` / `titlesByLang`, and then the guard
carefully restored the one field that was never in danger. The NFO was written from the Korean values
30 minutes later and pushed to Jellyfin.

So the item now says *"metadata language: English (chosen by you)"* on a page rendering a Korean title.

## Problem

Phase 184 (`FR-184-1`) put the override in `Scanner.rescanMetadata`, above the resolver:

```kotlin
val overrideLang = item.metadataLanguage?.ifBlank { null }?.let { LanguageResolver.normalize(it) } ?: …
val langPriority = if (overrideLang != null && basePriority.firstOrNull() != overrideLang)
    listOf(overrideLang) + basePriority.filter { it != overrideLang } else basePriority
```
— `Scanner.kt:1126-1143`

`rescanMetadata` is one of **several** paths that fetch TMDB and write an item. The from-scratch paths
build a `MediaItem` from nothing and derive `langPriority` purely from the file's audio tracks:

```kotlin
val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
val langPriority = LanguageResolver.priorityList(audioLangs, fallback)
val fetch = fetchTmdbMovieMetadata(jItem.providerIds?.tmdb?.toIntOrNull(), title, searchYear, langPriority)
```
— `Scanner.kt:329-332` (`scanMovie`; `scanSeries`, `scanMusicVideo` and the per-episode branches are
the same shape)

`fetchTmdbMovieMetadata`'s own doc says so explicitly: *"A fresh scan (`scanMovie`/`scanMusicVideo`)
never sets this — it has no operator override to honour."* At the time that was true of the
`acceptTitleOnly` flag. It is also, unintentionally, true of the whole language decision.

Every one of these reaches an item that already exists:

| path | entry point | honours `metadataLanguage`? |
|---|---|---|
| scheduled / manual library scan (`scan_files`) | `Scanner.scanItem` → `scanMovie`/`scanSeries` → `addOrUpdate` | **no** |
| `POST /api/media/{id}/sync` (movie) | `Scanner.syncMovie` → `scanItem` | **no** |
| realtime ingest / Jellyfin webhook (Phase 165/175) | `RealtimeIngestService` → `scanItem` | **no** |
| `POST /api/media/{id}/repull-jellyfin` | `Scanner.rescanFromJellyfin` | **no** |
| `pull_tmdb` pipeline step | `PipelineStepOps.rescanMetadata` | yes |
| `POST /api/media/{id}/sync?scope=series` | `Scanner.rescanMetadata` | yes |
| `PATCH /api/media/{id}/metadata-language` | `Scanner.rescanMetadata` | yes |

That table is exactly why the reporter's summary is precise: *"clicking on pull from tmdb does work…
but over time it gets set to the korean name again."* The two paths they touch by hand honour it; the
one that runs on a schedule does not.

### This is a known shape in this codebase

Phase 174 hit the identical structure one field over, and wrote it down:

> *"`Scanner.rescanMetadata` refuses to touch TMDB for a locked item, but the from-scratch paths
> (`scanItem` → `scanMovie`/`scanMusicVideo` → `addOrUpdate`, and `syncMovie`/`rescanFromJellyfin` →
> `updateOne`) build a `MediaItem` with no store access at all: they carry the flag's `false` default
> and will have re-run the very search that produced the wrong match. **Carrying the flag forward alone
> isn't enough — the freshly-matched fields have to go too, or the match returns on every scan.**"*
> — `TmdbMatchLock.kt:44-56`

Phase 184 copied `preserveTmdbMatchLock`'s *shape* (carry the field forward) but not its *lesson* (the
derived fields come back wrong). `MetadataLanguageLock.kt`'s doc even says the simpler thing is
correct here — *"Simpler than the artwork/match-lock guards: there's no residue to strip"* — which is
true of the field and false of the item.

Phase 174 solved it by discarding the fresh values in the guard. That answer does **not** transfer:
reverting title/overview to the stored ones would freeze the item at its first English fetch and make
every future TMDB correction invisible. The right answer here is to fetch in the right language in the
first place.

## Goal

An operator sets a title's metadata language once. Every subsequent write — scheduled scan, manual
sync, webhook ingest, Jellyfin re-pull, pipeline step — fetches TMDB in that language. The stored field
and the stored metadata never disagree.

## Requirements

### FR-191-1 — The from-scratch scan paths consult the stored override

`Scanner` already holds `private val store: MediaStore? = null` (`Scanner.kt:159`). Before building
`langPriority`, `scanMovie` / `scanSeries` / `scanMusicVideo` resolve the existing item by
`jItem.id` (`store.resolveByJellyfinId`) and, when it carries a non-blank `metadataLanguage`, put that
language at the head of the priority list — the **same** transformation `rescanMetadata` already
applies, extracted into one shared helper so the two can never drift again.

`acceptTitleOnly` follows it: a fresh scan with an override is in exactly the situation FR-184-4's
coverage pips exist for, so a title-only result in the chosen language must be accepted rather than
falling through to the next language.

`store` is nullable on `Scanner` for tests; a null store means no override, which is the correct
degradation (a genuinely new item has none anyway).

### FR-191-2 — One helper, one call site per fetch

The language decision must live in exactly one function — signature roughly
`fun effectiveLangPriority(audioLangs: List<String?>, fallback: String, existing: MediaItem?): Pair<List<String>, Boolean>`
returning the priority list and whether an override is in force. Every TMDB-fetching path calls it.
The current situation, where the override logic exists once and the fetch sites number seven, is the
defect; adding a second copy would reproduce it.

`Scanner.kt`'s per-episode branches (`:629`, `:968`, `:1085`, `:1227`) must be covered too — a series
with `metadataLanguage = "en"` whose episodes are re-fetched in Korean is the same bug one level down.

### FR-191-3 — A test that fails today

`MetadataLanguageLockTest` currently proves the *field* survives. Add a test that proves the *item*
does: given a stored item with `metadataLanguage = "en"` and a Korean-first audio track list, the
priority list handed to TMDB starts with `en`. Assert it on the from-scratch path, not only on
`rescanMetadata`.

### FR-191-4 — Repair what is already wrong

Items whose stored `metadataLanguage` disagrees with the language their metadata was actually fetched
in are already on disk and in Jellyfin. After the fix lands, every item with a non-null
`metadataLanguage` needs one `rescanMetadata` pass to converge.

This is **not** an automatic mass re-pull. It is a bounded, explicit, operator-triggered action:
the set is small and knowable (`SELECT` over `metadataLanguage IS NOT NULL` — one item in production
today), so the Library page's existing metadata-language facet (FR-184-8) already lists them, and
re-pulling one title is an existing button. A one-line note in this spec's acceptance is enough; no
new UI.

### FR-191-5 — The card must not claim a language the metadata isn't in

`buildMetadataLanguageCard` renders the chosen language with an accent border and badge. Nothing
verifies that the metadata on screen came from that language. It cannot be verified cheaply in
general — but the cheap 90 % case is free: `titlesByLang[metadataLanguage]` is already stored, and
when it exists and differs from `title`, the item demonstrably did not last fetch in the chosen
language.

When they disagree, the card says so — *"Showing metadata fetched in {resolved}, not {chosen} — re-pull
to fix"* with the existing Re-pull ▾ action beside it — rather than silently asserting a state that
isn't true. This is a safety net, not the fix; FR-191-1 is the fix.

## Non-goals

- No change to the resolver, the cascade, or the constitution's *"metadata language is driven by the
  actual audio tracks present in each file"* rule for items with no override.
- No per-library default, no global preference, no rules engine. Phase 184's "per title only" scope
  stands.
- No change to `preserveMetadataLanguage` itself — it is correct and stays.
- No Ravilo work. The client renders whatever title the server sends.
- No automatic mass re-pull (FR-191-4).

## Acceptance

1. Set 올드보이's metadata language to English; confirm `title = "Oldboy"`.
2. Run a full library scan (or `POST /api/media/oldboy-2003/sync`). The title is **still** `Oldboy`,
   the overview is still English, and `metadataLanguage` is still `en`.
3. Trigger a webhook/realtime ingest for the same item: same result.
4. `POST /api/media/oldboy-2003/repull-jellyfin`: same result.
5. A title with no `metadataLanguage` is unaffected — a Korean-audio film with no override still
   resolves to Korean, exactly as the constitution says.
6. A series with `metadataLanguage = "en"` re-scanned end to end has English episode titles, not just
   an English series title.
7. `linuxX64Test` green, including the new FR-191-3 test, which must fail against `main`.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt` — `scanMovie` `:315-380` (the
  audio-only `langPriority`, `:329-332`), `scanSeries` `:472-`, `fetchTmdbMovieMetadata` `:294-312`
  (its "never sets this" comment), `rescanMetadata`'s override `:1126-1152`, per-episode branches
  `:629`, `:968`, `:1085`, `:1227`, `store` `:159`.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/MetadataLanguageLock.kt` — the guard that works.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/TmdbMatchLock.kt:44-66` — the identical structure,
  already solved once, with the lesson written down.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/MediaStore.kt:600-620` (`addOrUpdate`),
  `:641-655` (`updateOne`) — the two write choke points.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/MediaRoutes.kt:1711-1722`
  (`POST /{id}/sync` → `syncMovie` for movies), `:1580-1640` (the metadata-language PATCH route),
  `:1680-1697` (`repull-jellyfin`).
- `specs/requirements/phase-184-choose-metadata-language.md` — the phase this completes.

## Open questions

1. `rescanFromJellyfin` merges Jellyfin's own fields back into the item. If Jellyfin holds the Korean
   title (it does — we wrote the NFO), does honouring the override on the TMDB half suffice, or does
   the Jellyfin-sourced `title` also need to lose to the override? Believed sufficient, because
   `syncFromJellyfin` composes `scanItem`'s result; needs confirming against the code path rather than
   assumed.
2. Should `resolvedLanguage` keep showing what the *resolver* would have picked (`ko`) while
   `metadataLanguage` shows the override (`en`)? Phase 184 says yes — the trace stays visible, dimmed —
   and this spec assumes that stands. Worth restating in the dev review because the two fields
   disagreeing is exactly what makes this bug hard to see.
