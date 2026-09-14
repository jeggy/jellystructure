# Phase 211 — PlaystateCache never fetched an episode id, so every series lost its progress and its next-up

> Reported live, 2026-09-14, from the stue TV, hours after Phase 205 deployed: "All progress is gone on
> every series. Both in episode progress and which episode is next up." Movies were unaffected.

## Status
✓ Built 2026-09-14 (fixes the FR-205-1/2 regression below). Not dev-reviewed, not deployed.

## Root cause

Phase 205 replaced `DetailService.getPlaystate`'s per-request live Jellyfin fetch with a pure read of the
new `PlaystateCache` — a background-refreshed, per-user whole-catalog map the doc comment describes as
covering "every read site that used to fetch it live," explicitly including `DetailService.getPlaystate`
(episode/season-open playstate).

But `PlaystateCache.refreshOne` builds its id list from:

```kotlin
val ids = mediaStore.liveItems(device).mapNotNull { it.jellyfinId }
```

`mediaStore.liveItems(device)` returns top-level `MediaItem`s only — one id per movie or per series. It
never walks `MediaItem.episodes`, so no episode's own `jellyfinId` was ever a member of `ids`, and no
episode ever appeared in the map `PlaystateCache` holds. `DetailService.getPlaystate(device, jellyfinIds)`
is called by the client with a season's own episode ids; every one of those lookups was an unconditional
miss against the cache — not intermittently, always — because the cache never had episode-keyed entries
to begin with.

The pre-205 code (a live `getUserDataBulk` call per season open) took whatever id list the caller passed
and fetched exactly those ids from Jellyfin, so it never had this gap — episode ids arrived as ordinary
function arguments and got fetched like any other id. The regression is specific to the cache's own
*background* fetch never being told to include them.

Movies were unaffected because a movie's own playstate is keyed by the movie's top-level id, which *is*
in `liveItems()`'s output. Series-level rollups (a season row's own aggregate ✓, if any renders from the
top-level id) were also unaffected. Only per-episode state — resume position, played flag, and whatever
the client derives "next up" from — was silently empty on every series, for every user, from the moment
the Phase 205 image went live.

**Why untested:** `HomeFeedServiceReadPathTest`'s fixtures all construct `MediaItem`s with
`episodes = emptyList()` (confirmed by inspection) — no existing test exercises a series with episodes
through `PlaystateCache`, so the gap between "top-level ids" and "every id the client might ask about"
had no test to catch it.

## Fix

`PlaystateCache.refreshOne`'s id list now flattens each item's own id with every one of its episodes'
ids, for series:

```kotlin
val ids = mediaStore.liveItems(device).flatMap { item ->
    listOfNotNull(item.jellyfinId) + item.episodes.mapNotNull { it.jellyfinId }
}
```

No change to `fetchPlaystate`/`getUserDataBulk` — both already take an arbitrary id list and already
chunk it (`HYDRATE_CHUNK`), so this is purely a matter of asking for the right set of ids. No change to
`DetailService.getPlaystate` or `hydrateRelated` — both were already correct pure map reads; they were
only ever as good as the map they read from.

**Traded-off cost, accepted:** this materially grows the per-cycle outbound id count — a large series
(Ed, Edd n Eddy: 3 seasons; Two and a Half Men: 12 seasons × 24 episodes) now contributes one id per
episode, every 20s refresh, for every recently-seen user. `HYDRATE_CHUNK`-based chunking and the existing
`hydrateGate` concurrency cap absorb this the same way they already absorb Home/Browse/Search's own
whole-catalog card counts; no separate pacing was added. If this reproduces Phase 183's TMDB-fan-out
shape against Jellyfin's own UserData endpoint, that is exactly the live A/B FR-205-7 (deliberately not
built in 205) would have caught — flagged here rather than guessed at.

## Verification
`compileKotlinLinuxX64` clean. Not device-tested (per working-agreement: TV testing needs the user's
go-ahead) — the fix is a straightforward id-set correction with no new branching, verified by inspection
against the pre-205 code's own id source (arbitrary caller-supplied ids, including episodes) and by the
`Episode.jellyfinId` field already existing and already being populated by the scanner (Phase R82).
