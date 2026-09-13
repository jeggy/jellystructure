# Phase 206 — opening one collection must not build the other five

> Measured on production, 2026-09-13, warm caches, no scan running, nothing to wait on:
>
> ```
> /api/tv/channels      (builds all 6 channels)              0.33–0.42 s
> /api/tv/channel/{id}  (builds all 6, plus the one asked for) 0.46–0.52 s
> ```
>
> Roughly **three quarters of the time it takes to open a collection is spent building the five
> collections you didn't open** — to decide whether to draw their tiles.

## Status
Planned, written 2026-09-13. Audit-authored, not dev-reviewed, not built. Backend-only — no client
change, no contract change, no new UI.

**This phase is not about background work.** Unlike Phases 204 and 205 it does not close a
background-to-interactive coupling: this cost is paid identically whether a scan is running or the box
is idle (`corr(ffmpeg %cpu, channel latency) = +0.02` across 40 samples). It is a flat ~450 ms on every
navigation, always. Recorded plainly so it is not mistaken for part of that thread and prioritized as if
it were.

## The finding

### The emptiness check builds the whole channel

`buildChannels` (`HomeFeedService.kt:363`) exists to produce the channel rail — id, name, logo, style.
To decide whether a channel belongs on the rail at all, R228 requires knowing whether it resolves to
anything for *this* viewer:

```kotlin
for (ch in config.channels.filter { it.enabled }.sortedBy { it.order }) {
    val (heroes, rows) = buildChannelContent(device, config, ch, allItems, jellyfinBase, token, heroIds)
    if (heroes.isEmpty() && rows.all { it.items.isEmpty() }) continue
    result.add(Channel(id = ch.id, name = ch.name, logoUrl = ch.logoUrl, ...))
}
```

Nothing from `heroes` or `rows` is used. Both are computed in full, then thrown away, and the only
question asked of them is whether they are empty.

What "in full" means, per row, in `buildFilterRow` (`:554`):

```kotlin
val matched  = all.filter { ConditionEvaluator.matches(it, query, heroIds, ageRatingCascade) }
val filtered = when (rowCfg.mediaKind) { "MOVIE" -> matched.filter { ... }; ... }
val cards    = filtered
    .sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title })
    .take(ROW_ITEM_LIMIT)                 // 30
    .mapNotNull { it.toMediaCardOrNull() }
    .distinctBy { it.id }
if (cards.isEmpty()) null else Row(...)
```

A full predicate evaluation over the channel-scoped list, then a **sort of every match**, then **thirty
`MediaCard` constructions** — before anyone reads `cards.isEmpty()`. `rows.all { it.items.isEmpty() }`
can be answered by a short-circuiting `any { matches }` that stops at the first hit. For a non-empty row
that is one item examined instead of a sort and thirty card builds.

Six enabled channels on this household (Olivar, DanskTV, Barna TV, Føroyskt, Disney+,
Thriller / Gyser), each with several rows.

### It is paid on every navigation, twice over on a channel page

`getChannelFeed` (`:273`) builds the requested channel's content, and then calls `buildChannels` — which
builds **all six, including the one just built**:

```kotlin
val (heroes, rows) = buildChannelContent(device, config, channelCfg, allItems, ...)
applyPlaystate(HomeFeed(
    heroes = heroes,
    channels = buildChannels(config, device, allItems, ...),   // ← all six again
    rows = rows, ...
), playstateDeferred.await())
```

`buildHomeFeed` (`:199`) calls it too. So the rail's emptiness verdict is recomputed from scratch on
every Home load and every channel open.

### And no channel feed is cached at all

`feedCache` is keyed by `jellyfinUserId` and populated only by `getHomeFeed`. There is no equivalent for
`getChannelFeed`, so a viewer moving between collections — or in and out of one — pays the full build
each time. Measured across 40 samples: min 0.332 s, median 0.456 s, p95 0.586 s. Flat, and never fast.

The one 5.345 s outlier in that series is a different problem — a cold `continueListCache` rebuild
inside `buildRows`, which is Phases 204 and 205.

## Requirements

**FR-206-1 — deciding whether a channel is non-empty must not build its content.** The rail's verdict is
a boolean per channel; computing it must cost a short-circuiting membership test, not a sorted, carded
row set. `buildChannelContent` stays as the single shared path for content that is actually *returned*
(R228's reason for factoring it out — the feed and the verdict must never disagree), so the non-empty
test must be derived from the same predicates rather than written as a second, hand-kept-in-sync rule.
That is the trap R228 was created to close and this phase must not reopen it.

**FR-206-2 — the requested channel is built once per request.** `getChannelFeed` must not build the
channel it was asked for a second time as a side effect of assembling the rail.

**FR-206-3 — the channel rail is cached.** It depends on (user, visibility scope, config, library
content) and on nothing about which channel is being viewed, so it is computable once and reused by
Home, every channel page, and `getChannels`. Its invalidation signal must be **the one Phase 204
establishes**, not `libraryVersion` directly — otherwise this cache inherits exactly the thrashing 204
exists to remove. If 204 has not landed, this phase states the dependency rather than duplicating the
fix.

**FR-206-4 — channel feeds are cached like Home is.** Same shape as `feedCache`: per user, same TTL, the
**same library-write invalidation signal FR-206-3 uses** (Phase 204's, not `libraryVersion`), and the same
targeted invalidation on a reported playback stop (FR-204-5 / FR-205-9). Keyed by `(user, channelId)`.
A viewer moving between four collections and back should pay for four builds, not eight.

**FR-206-5 — `seedTotalCount` stays exact.** `buildFilterRow` reports `filtered.size` — the pre-cap match
count — which R187/FR-R219-5 need for the "→ See all" tile. FR-206-1's short-circuit applies **only** to
the discarded emptiness check, never to a row that is returned. A See-all count that silently becomes
"at least 30" is a correctness regression, not an optimization.

**FR-206-6 — a test that pins the work down.** Assert that assembling the rail for N channels does not
construct `MediaCard`s for channels whose content is not being returned. Like FR-204-6, this regression
is invisible — the response is byte-identical and only the latency differs, so nothing else would catch
a future change that reintroduces the full build.

## Out of scope

- **Which channels appear on the rail.** R228's rule — a channel that resolves to nothing for this
  viewer is not shown anywhere — stands exactly as specified. This phase changes only how much work
  answering that question costs. Every response must remain byte-identical.
- **`ConditionEvaluator`'s own cost.** Evaluating a query tree per item over 526 items is the honest work
  of a filter row and is not examined here. If the rail still costs too much after FR-206-1/3, a
  per-channel membership index is a separate phase with its own invalidation problem.
- **`ROW_ITEM_LIMIT = 30` and `CONTINUE_ROW_LIMIT = 20`.** Product decisions, unexamined.
- **The Continue row inside a channel build.** Its cost is Jellyfin's and belongs to Phase 205;
  `canonicalContinueList` is already shared across the rail's six builds by its own SWR cache
  (FR-R219-1), so it is not multiplied by six the way the filter rows are.
- **The 5.3 s channel outlier.** A cold continue-list rebuild — Phases 204 and 205. Chasing it here
  would be fixing someone else's bug in the wrong file.

## Open questions

1. **Can the non-empty test reuse `matchesChannel` alone?** `buildChannelContent` computes
   `allItems.filter { it.matchesChannel(channelCfg, heroIds) }` first, and a channel whose scoped list is
   empty is trivially empty. But a channel with matching items whose *rows* all filter down to nothing is
   also empty, and only the row predicates can say so. If the common case is settled by the cheap outer
   test, FR-206-1 may need nothing more than an early `continue` — worth checking against this
   household's six channels before designing anything larger.
2. **Should `getChannels` and the rail inside `HomeFeed` share one code path?** They already answer the
   same question, and R187's fix added `getChannels` precisely because a caller needed the rail without a
   full feed. FR-206-3's cache would make them the same lookup — which is a simplification worth taking
   deliberately rather than as a side effect.
3. **What TTL for a channel feed?** Home uses `FEED_TTL_MS = 5 min`. A channel page is arguably staler-
   tolerant than Home, but two surfaces disagreeing about how fresh "Newly Added" is would be its own
   confusion. Leaning: same TTL, same invalidation, no new constant.
4. **Can the rail's cache key drop the user id?** Only if it keeps `cfgHash` — and that is the whole
   answer, so this is nearly closed. The rail is built from `configService.getConfig(device.jellyfinUserId)`
   (`:102`), and `RaviloConfig` is **per Jellyfin user** per constitution §3: the channel list itself, not
   just what the viewer may see, differs between users. Keying on `allowedHash` alone would serve one
   viewer's collections to another — a visibility bug, not an optimization. A `(cfgHash, allowedHash)` key
   is sound and still collapses a household whose members share a config, which on this household is most
   of them. FR-206-3 already states the dependency correctly; this question only exists to record why the
   cheaper key is wrong.
