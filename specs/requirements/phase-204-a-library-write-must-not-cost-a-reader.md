# Phase 204 — a library write must not cost a reader anything

> Owner, 2026-09-13, after a latency investigation: *"We want the server to keep doing all this
> background work — we just need to make sure that when the host machine is not under pressure, it has
> no effect on the APIs Ravilo uses. Our server is massive, so should not be affected in any way."*
>
> The server is not under pressure. 32 cores, load 12, eighteen cores idle, the backend using a quarter
> of one core. And a single background write still costs every viewer on the household a **two-to-six
> second** Home screen.

## Status
Planned, written 2026-09-13. Audit-authored, not dev-reviewed, not built. Backend-only — no Ravilo
counterpart, no client change, no new UI.

**This phase was filed by Phase 182, in Phase 182's own words**, and has sat unclaimed since
2026-08-31. From that spec's FR-182-9 measurement notes:

> `/api/tv/home` degrades **~120×** at p50. […] **This is a real, reproducible gap** worth its own
> follow-up phase (a home-feed cache keyed on a coarser signal than per-item `libraryVersion`, or a
> short debounce on invalidation) — filed as a candidate for the next unassigned number rather than
> fixed inline here, since it's outside this phase's own FRs.

## The finding

Two caches decide whether a Ravilo Home load is instant or expensive, and both are gated on a counter
that moves for reasons neither of them cares about.

```kotlin
// HomeFeedService.kt:120 — the assembled feed
val cachedStructural = feedCache[userId]?.takeIf {
    it.libVer == libVer && it.cfgHash == cfgHash && it.allowedHash == allowedHash && (now - it.builtAt) < FEED_TTL_MS
}

// HomeFeedService.kt:641 — the canonical Continue Watching list
cached?.takeIf {
    it.libVer == libVer && it.allowedHash == allowedHash && (now - it.builtAt) < FEED_TTL_MS
}
```

`libVer` is `mediaStore.libraryVersion`, and `libraryVersionAtomic.incrementAndGet()` is the **first
line of `upsertItemDbOnly`** (`MediaStore.kt:897`) — it fires on every single item write, whatever the
write was.

So a poster path written for one title discards the fully-assembled feed for **every user**, including
its Continue Watching list, whose rebuild costs four live Jellyfin round trips (see Phase 205). The
cache is not wrong about anything; it is being told the library changed, which is true, and inferring
that the feed changed, which almost never is.

### Measured on production, 2026-09-13

Warm against cold, same route, same device token, minutes apart:

| | `/api/tv/home` |
|---|---|
| cache hit | **10–16 ms** |
| cache miss (rebuild) | **2.7 s** (fresh user, warm library) — **4.1 s**, **5.6 s** observed |

And the miss rate in steady state, sampled every ~6 s for four minutes with **no scan running at all**:

| | samples | median |
|---|---|---|
| cache hit | 28 / 40 | 0.013 s |
| cache miss | **12 / 40** | 0.494 s |

A 30% miss rate while nothing was writing. That floor is `PLAYSTATE_TTL_MS` (20 s) expiring against
`FEED_TTL_MS` (5 min) — see *Out of scope*. During background writes the rate goes to 100%, which is
Phase 182's ~120×.

The write volume is not small. From one restart's own log:

```
[INFO] Startup: corrected stale hasStill flags for 142 series    ← 142 invalidations, at boot
[INFO] Scan summary: 12 stored, 3 skipped                        ← 12 more
```

None of those 142 writes changed anything a `MediaCard` renders.

### This is not a capacity problem, and that is the point

The same investigation ruled out every hardware explanation, on 40 samples taken while a
`detect_segments` ffmpeg held 736–1282% CPU:

```
corr(ffmpeg %cpu, channel latency) = +0.02
corr(ffmpeg %cpu, home latency)    = +0.15
corr(load,        channel latency) = -0.24
```

Phase 134's dedicated `scan-pool` (`newFixedThreadPoolContext`, created for exactly this) is doing its
job — background CPU does not touch request latency. Nor does the database: `SELECT json FROM media`
for all 526 rows (33.8 MB of blobs) takes **62 ms**, and read paths serve from `allItemsCache` without
touching SQL at all. A bigger host, more cores, or a different database change none of this. The
coupling is a cache key.

### Four caches, one counter, three different correctness needs

`libraryVersion` is also the key for `nfoCoveredCache` (`MediaStore.kt:741`), `trackFacetsCache`
(`:752`), `metaFacetsCache` (`:787`) and the Triage summary (`TriageRoutes.kt:117`). Those genuinely
*do* want to invalidate on almost any write — a facet count changes when a track changes. The feed does
not. One counter serving all of them means the most write-sensitive consumer sets the invalidation
policy for the least write-sensitive one.

## Requirements

**FR-204-1 — a write that cannot change a feed must not invalidate a feed.** This is the invariant;
everything below serves it. After a background write to title X, a warm feed that does not contain a
changed card stays warm. Stated as an acceptance test rather than a mechanism: write an unrelated
title, then load `/api/tv/home` and observe a cache hit.

**FR-204-2 — the feed caches get their own invalidation signal, separate from `libraryVersion`.** Phase
182 named two candidate mechanisms — a coarser signal, or a debounce. This phase must **pick one and
record why**, not ship both:

- *Content signature.* A version that advances only when a field a `MediaCard` or `FocusDetailFacts`
  actually carries has changed. Precise. Costs a decision about which fields are card-visible — a list
  that Phase 202 just grew and will grow again.
- *Debounce.* Invalidate at most once per N seconds. Trivial, mechanism-independent, immune to a future
  field being forgotten. Costs a bounded staleness window, and a write that *does* matter is delayed.

**There is a third option, and it is nearly free: a signal for "this write changed nothing at all"
already exists at the write site.** `stampTimestamps` (`MediaStore.kt:181`) computes
`contentSignature(old) != contentSignature(fresh)` on **every** upsert, and `contentSignature` (`:178`)
is not a version — it is `item.copy(scannedAt = 0, createdAt = null, updatedAt = null,
jellyfinUpdatedAt = null)`, i.e. the item with the fields that differ on every write regardless of real
change zeroed out. That comparison's `changed` boolean is already paid for on the same code path that
then unconditionally increments `libraryVersion`, and it is currently used for nothing but stamping
`updatedAt`. A feed version gated on `changed` is a coarser filter than a card-field signature — an
IMDb-rating or `hasStill` write is a real content change and would still invalidate — but it is
**strictly better than today at zero added cost**, and it removes the "a scan re-found 500 unchanged
titles" case entirely.

So the trade is not precision against maintainability with a comparison cost on one side: the
comparison is already happening. The phase must evaluate all three, and note that the field-list
failure mode is the worst of them — a card-visible field added later and not registered means a stale
feed, silently, with no test that would catch it. **No longer leaning debounce by default**; measure
what fraction of writes are `changed == false` first (open question 1).

**FR-204-3 — invalidation stays per-consumer.** `nfoCoveredCache`, `trackFacetsCache`,
`metaFacetsCache` and the Triage summary keep invalidating on `libraryVersion` exactly as they do
today. This phase must not "improve" their keys as a side effect; they have different correctness needs
and none of them is on a Ravilo read path. `libraryVersion` itself, and FR-182-2's atomicity guarantee
for it, are unchanged.

One caveat on the Triage consumer, which is `triageCountCache` (`TriageRoutes.kt:112`, keyed at `:117`):
since Phase 201's 2026-09-13 amendment, an invalidation there can trigger `MkvHealthCache.brokenPaths`'
~88-second library walk (`:147`). Leaving its key alone is still correct, but it is only *safe* once
**Phase 203**'s FR-203-1 has landed. If 204 ships first, nothing gets worse than it is today; if 203 is
ever reverted or deferred, this bullet is the reason the Dashboard re-enters that walk on every write.

**FR-204-4 — a write that genuinely changes a card must still be visible promptly.** Whatever FR-204-2
chooses, a newly-added title appearing in Newly Added, a corrected poster, or a retitled item must
reach Home within a bounded, stated window — not "eventually". The existing `FEED_TTL_MS` (5 min) is
already the outer bound today; this phase must not make it worse, and must state the new worst case
explicitly so R33's push path and this TTL can be reasoned about together.

**FR-204-5 — a playback stop still corrects the row immediately.** `invalidatePlaystate`
(`HomeFeedService.kt:147`) drops
`feedCache`, `playstateCache` and `continueListCache` for one user on a reported stop, and the bug it
fixed (a finished episode still showing in Continue for up to five minutes) must not come back. An
explicit, targeted invalidation is a different thing from incidental invalidation by an unrelated
write, and only the latter is in scope here.

**FR-204-6 — a test that fails if this regresses.** The regression is invisible: nothing errors, no log
line appears, the feed is simply rebuilt. A unit test asserting "unrelated write ⇒ cache still valid"
is the only thing standing between this fix and its silent reversal by a future writer added to the
scan path. Phase 182 shipped the correct atomic increment and *created* this problem without a single
test going red.

## Out of scope

- **The 20 s `playstateCache` TTL**, which is the 12-of-40 steady-state miss floor above. It is a real
  cost and it is **Phase 205's**, because the fix is not a longer TTL — it is not deriving playstate
  from Jellyfin on the request path at all. Lengthening it here would trade a correctness property
  (watched state going stale on a shared household) for latency, and 205 gets both.
- **Channel feeds**, which have no cache to invalidate. That is Phase 206.
- **The cost of a rebuild.** This phase reduces how *often* a feed is rebuilt; 205 reduces what a
  rebuild costs. They are independent and either is useful alone. Deliberately not merged: 204 is a
  cache-key change with a small blast radius, 205 restructures where Jellyfin is called from.
- **`allItemsCache` invalidation.** `update()` nulls it wholesale (`:329`), correctly — it is a
  wholesale replace. The per-item path already patches in place rather than nulling (`:938`), which was
  an earlier fix for this same class of bug and is the precedent this phase follows.
- **Compression, payload size, and `focus_detail`'s 65% share of the 275 KB Home response.** Measured,
  but with no evidence it costs the client anything: ~25 ms of wire time on this link, and client-side
  parse cost on the BRAVIA was never measured. Needs a measurement before it needs a phase, and moving
  `focus_detail` off the initial payload would reverse FR-202-7 on purpose.

## Open questions

1. **What fraction of writes change nothing, and what fraction change only fields no card carries?**
   This is the measurement that settles FR-204-2, and both numbers come from the same instrumentation:
   count writes where `stampTimestamps`' `changed` is false (the free option's yield), and among the
   rest, how many touch a `MediaCard`/`FocusDetailFacts` field (the signature's yield over it). The
   142-series `hasStill` correction is a real content change, so the free option does not catch it; a
   scan re-finding unchanged titles is caught entirely. If the answer is that most writes are `changed
   == true` **and** most of those touch a card field, all three options converge and the debounce wins
   on simplicity. **Measure before choosing.** Note `FocusDetailFacts` alone carries eleven fields
   including `overview` and `imdbRating`, and an IMDb sync writes those on a schedule.
2. **Should the debounce window be per-user or global?** Global is simpler and the invalidation cause is
   global. Per-user would let a user who just stopped playback skip the wait, but FR-204-5 already
   handles that case explicitly.
3. **Does R33's `library_changed` push need to agree with the new signal?** If a client is told the
   library changed and re-fetches, but the server's feed cache has debounced, the client gets the same
   bytes it already had and paid a round trip for nothing. Not harmful, but the two clocks should be
   deliberately related rather than accidentally different.
4. **Is `cfgHash = config.hashCode()` sound?** Unexamined here, but it is in the same `takeIf` and a
   `hashCode()` over a large config object is a collision risk that would show up as a *stale* feed
   after a config change — the opposite failure, and much harder to notice than a slow one.
