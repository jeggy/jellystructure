# Phase R231 — A Jellyfin timeout poisons Continue Watching for 5 minutes everywhere

> Live bug report: opening the **DanskTV** channel showed no Continue Watching row at all, despite the
> viewer having in-progress Danish titles. Re-queried moments later (direct API call) and the row was
> back with 15 items — a transient condition, not a config gap.

## Status
Implemented (2026-09-03).

### Implementation notes (2026-09-03)
- **Root cause confirmed by comparing R219's own spec against the shipped code, not guessed:**
  `phase-R219-continue-watching-model.md`'s Invariants section states *"on a Jellyfin timeout the row
  is omitted entirely rather than shipped half-built, and **the SWR cache serves the previous good
  value**."* The shipped `canonicalContinueList`/`buildCanonicalContinueList`
  (`HomeFeedService.kt:576-637`) implements the first half correctly (`withTimeoutOrNull` returns
  `emptyList()` on a `CONTINUE_TIMEOUT_MS` = 6s timeout, so no half-built row ever ships) but **not the
  second half** — the empty result is written straight into `continueListCache` unconditionally
  (`:590`, pre-fix), overwriting whatever good value was cached before. Every consumer sharing that
  cache — the Home row, every channel's Continue row, and the See-all page — then serves genuinely
  empty for the rest of `FEED_TTL_MS` (5 minutes), not just the one request that happened to race a
  slow Jellyfin.
- **The DanskTV report is fully explained by this**: a single slow round trip to Jellyfin (plausible
  any time — scan load, network hiccup) empties the cache; the channel page loaded inside that
  5-minute window saw nothing; my own direct API re-check minutes later landed after the cache had
  either naturally expired or been rebuilt by an intervening request, so it looked fine again.
- **Fix**: `buildCanonicalContinueList`'s return type changed from `List<ContinueEntry>` to
  `List<ContinueEntry>?` — `null` now means "the build could not be trusted" (the `withTimeoutOrNull`
  timeout, and the pre-existing blank-Jellyfin-URL config gap), distinct from an empty list, which
  still means "built successfully, genuinely nothing to show" (a real, cacheable state — e.g. a viewer
  who has finished everything). `canonicalContinueList` now only writes to `continueListCache` when
  the build returns non-null; on `null` it falls back to whatever is already cached for that user
  (even if past its TTL — stale-but-real beats wrongly-empty) and only returns a genuine empty list if
  there was never a cached value at all (first load, cold cache). A failed build is never itself
  cached, so the very next request retries the live fetch instead of waiting out the rest of the
  5-minute window.
- Compiles clean (`compileKotlinLinuxX64`).

## Problem
`canonicalContinueList` (`HomeFeedService.kt:576-592`) is the single SWR-cached source every Continue
Watching view reads from (R219 FR-R219-1). Before this fix, a timed-out build and a genuinely-empty
build were indistinguishable by the time they reached the cache — both were `emptyList()`, and both
got written in unconditionally. This directly contradicts R219's own stated invariant that a timeout
should fall back to "the previous good value," and turns a single slow Jellyfin round trip into an
extended, cache-wide outage of a feature that in reality still has data.

## Requirements

### FR-R231-1 — Distinguish "couldn't build" from "built, genuinely empty"
`buildCanonicalContinueList` must signal a failed/untrustworthy build (timeout, or the blank-Jellyfin-
URL config gap) distinctly from a successful build that happens to produce zero entries.

### FR-R231-2 — Never cache a failed build
`canonicalContinueList` must not write a failed build's result into `continueListCache`. The failure
is transient by nature (`CONTINUE_TIMEOUT_MS` = 6s on live Jellyfin calls) — the very next request
should retry live, not inherit a poisoned cache entry for up to `FEED_TTL_MS` (5 minutes).

### FR-R231-3 — Serve the previous good value on a failed build
When a build fails and a previous cache entry exists for that user (regardless of whether it's past
its own TTL), return its list rather than an empty one. Only a user with **no** previous cache entry
at all (cold cache — first request since server start, or after an explicit `remove()` per
`PlaybackService.stopPlayback`'s `:149` invalidation) sees a genuinely empty row on a failed build —
matching R219's "omitted entirely" framing for the one case where there's nothing better to fall back
to.

### FR-R231-4 — No change to the genuinely-empty case
A successful build that legitimately produces zero entries (a viewer with nothing in progress/next-up/
recently finished/touched) is cached and served exactly as before — this phase must not make Continue
Watching "sticky" once a viewer has truly cleared it.

## Non-goals
- Raising `CONTINUE_TIMEOUT_MS` or `FEED_TTL_MS` — the timeout and cache-freshness windows themselves
  are unchanged; only what happens to the cache on a timeout changes.
- Any change to the channel/Home scoping logic, the conflict rule, or the ordering — R219's model is
  untouched.
- A metric/alert for how often this fallback path triggers — not requested, and this project's Activity
  page has no obvious slot for it; a candidate follow-up if the condition turns out to be frequent.

## Acceptance
- Simulate a Jellyfin timeout (or observe one live during a slow/scan-loaded window): the Home row and
  every channel's Continue row keep showing the last known-good list instead of going empty, and the
  very next request (not the next 5-minute cache cycle) reflects fresh data once Jellyfin responds
  normally again.
- A viewer with a genuinely empty Continue Watching state (nothing in progress, nothing next-up,
  nothing recently finished/touched) still sees no row — unchanged.
- Cold-cache first load (server just started, or right after a `stopPlayback`-triggered
  `continueListCache.remove()`) with a slow Jellyfin still shows an empty row for that one request —
  there is nothing to fall back to — and self-heals on the next request without waiting for
  `FEED_TTL_MS`.

## Source references
- Bug: `src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt:576-592`
  (`canonicalContinueList`), `:616-637` (`buildCanonicalContinueList`'s two `emptyList()` failure
  exits, pre-fix).
- Contradicted invariant: `specs/ravilo/requirements/phase-R219-continue-watching-model.md:316-317`.
- Cache invalidation this phase must not fight: `HomeFeedService.kt:149`
  (`continueListCache.remove(userId)` on stop — still fires; a subsequent failed build after this
  point correctly has nothing to fall back to, per FR-R231-3's cold-cache case).

## Relationships
- Fixes a latent gap in **R219** (Continue Watching model) — the spec's own invariant already called
  for this behavior; the implementation just didn't deliver it.
- Same failure shape as other "shipped code didn't match its own documented invariant" bugs this
  project has found before (e.g. R202) — caught here by re-reading the owning phase's spec text
  against the actual code rather than assuming the invariant held.
- `scripts/check-phases.sh` will want a `STATUS.md` row — **STATUS.md is code-owned; do not add the
  row from the design side.**
