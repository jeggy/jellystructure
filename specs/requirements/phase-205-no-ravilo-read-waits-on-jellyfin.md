# Phase 205 — no Ravilo read waits on Jellyfin, and a row that cannot be built is not an empty row

> Captured live, 2026-09-13, from one device token, twenty-five minutes apart, with nothing changed in
> between:
>
> ```
> 17:2x  /api/tv/home → 12 rows, CONTINUE present: False
> 17:5x  /api/tv/home → 13 rows, CONTINUE present: True   (20 items)
> ```
>
> Continue Watching is not broken. It is **intermittent** — and the thing that decides is whether
> Jellyfin happened to answer four questions inside six seconds.

## Status
Planned, written 2026-09-13. Audit-authored, not dev-reviewed, not built. Backend-only — the client
already renders whatever row it receives, so there is no Ravilo counterpart and no contract change.

Sibling of Phase 204: that phase reduces how *often* a feed is rebuilt, this one reduces what a rebuild
*costs* and removes the failure mode where the rebuild silently produces less than the truth. Either is
useful alone. Shares FR-203-2's principle — *"not yet known" is a third state, and it is not zero* —
which is the second time in two days that has been the load-bearing requirement.

## The finding

### A 6-second budget shared by four calls, one of which cannot fit in it

`buildCanonicalContinueList` (`HomeFeedService.kt:696`):

```kotlin
val fetched = withTimeoutOrNull(CONTINUE_TIMEOUT_MS) {          // 6_000 ms
    coroutineScope {
        val resumeDeferred   = async { jellyfinClient.getResumeItemsAll(...) }
        val nextUpDeferred   = async { jellyfinClient.getNextUp(...) }
        val finishedDeferred = async { jellyfinClient.getRecentlyPlayedAll(...) }
        val touchedDeferred  = async { jellyfinClient.getRecentlyTouched(...) }
        listOf(resumeDeferred.await(), nextUpDeferred.await(), finishedDeferred.await(), touchedDeferred.await())
    }
} ?: return@coroutineScope null
```

The four run concurrently, which is correct. But the budget is shared, and `getRecentlyTouched` asks
Jellyfin for the **whole library sorted by `DatePlayed` with no played filter** — 8 720 items — because
R219 FR-R219-3 verified against the live OpenAPI that no Jellyfin filter expresses "has been played"
(`minDateLastSaved` exists, `minDateLastPlayed` does not). Measured directly against
`http://192.0.2.20:8096`, connect time 0.3 ms, so this is Jellyfin and not the network:

| Jellyfin query shape | latency |
|---|---|
| `Ids=` bulk lookup, 100 ids (`getUserDataBulk`) | **84 ms** |
| `Ids=` bulk lookup, 120 ids | 106 ms |
| `Limit=1`, no sort | 1.49 s |
| `Limit=1`, `SortBy=DatePlayed` | 4.61 s |
| `Limit=200`, `SortBy=DatePlayed`, whole library — **what `getRecentlyTouched` issues** | **1.26–2.01 s** (median 1.42 s over 40 samples) |
| the same query, measured during a pipeline run | **8.56 s** |
| `getRecentlyPlayedAll` full paged loop (1 381 items, 7 pages, sequential) | ~1.8 s |

A `Ids=` lookup is 17× cheaper than a whole-library sort. The expensive shape is the one on the
critical path, it is issued on every rebuild, and at 8.56 s it cannot fit a 6 s budget at all.

### When the budget blows, all four are cancelled — and the log accuses the innocent

25 minutes of production log, counted by endpoint:

```
40  getRecentlyTouched      ← the actual cause
39  getRecentlyPlayedAll
39  getNextUp
11  getResumeItemsAll
10  getUserDataBulk
```

**Forty builds, forty failures.** But `getNextUp` measured **1.0 s** standalone. Its 39 "failures" are
collateral: `withTimeoutOrNull` cancels the enclosing `coroutineScope`, every `async` inside it dies,
and each one's own `runCatching`/`Logger.warn` reports its cancellation as its own failure. The log
names four broken calls when one is slow, which is why this took a direct measurement to find rather
than a log read.

### And the fallback turns a transient failure into a missing row

`canonicalContinueList` (`:652`) handles the `null` correctly on its own terms — R231's rule, a failed
build must never poison the cache:

```kotlin
val built = buildCanonicalContinueList(...) ?: return cached?.list ?: emptyList()
```

A *cold* cache therefore ships `emptyList()`, and `buildRows` drops a row with no cards
(`if (cont.cards.isNotEmpty())`). The viewer sees a Home screen with **no Continue Watching row at
all** — not an error, not a spinner, not a stale row. Exactly the shape FR-203-2 was written about the
day before: an unknown answer rendered as a confident "there is nothing here."

And Phase 204's finding makes the cold-cache window common rather than rare: any background write
invalidates `continueListCache`, so the next reader rebuilds, and a rebuild that times out on a
freshly-invalidated entry falls back to `emptyList()`.

### Jellyfin is the shared resource, and nothing rations it

This is the coupling the owner's question was actually about. Background CPU is already isolated —
Phase 134's `scan-pool` works, and across 40 samples with a `detect_segments` ffmpeg at 736–1282%,
`corr(ffmpeg %cpu, channel latency) = +0.02`. The one real correlation in the dataset was:

```
corr(ffmpeg %cpu, jellyfin /Items latency) = +0.53
```

Background work does not slow our APIs. It slows **Jellyfin**, which our APIs then wait for
synchronously. `OutboundHttp`'s `INTERACTIVE_RESERVE = 16` rations *our* sockets; nothing rations
Jellyfin's capacity. Phase 183 built a TMDB token bucket with AIMD for precisely this class of problem
and Jellyfin never got the equivalent — while background steps hit it constantly (`sync_jellyfin`
refreshes, `fetch_artwork`, `getAllLibraryItemIds` paging 8 935 items at 200/page, and Phase 207's
285-per-run failures).

**The mechanism behind that correlation is not established**, and this phase must not pretend
otherwise: n=40 across a narrow CPU band, system-wide iowait was 0–1%, and Jellyfin's own CPU sat at a
flat 6.9% throughout — so it is not CPU-starved. The leading candidate is shared disk: `detect_segments`
streams 4K HEVC remuxes off `/dev/sdc1` and `/dev/sda1`, the same spindles Jellyfin's media mounts point
at. Which leads to a concrete, separately-verified finding:

**`ionice -c3` on the `detect_segments` ffmpeg is a no-op on this host.** The command line is
`nice -n 19 ionice -c3 ffmpeg …`; the idle I/O class only exists under CFQ/BFQ, and this host's
schedulers are `[none] mq-deadline` (nvme0n1) and `none [mq-deadline]` (sda–sdd). The I/O politeness
that command line claims has never been in effect.

### Home is not the only read path that waits on Jellyfin

This is the full inventory, because an earlier draft of this phase enumerated only `HomeFeedService` and
FR-205-1's invariant covers every viewer read. **Every surface the owner named — collections, media
items — is on this list:**

| read path | Jellyfin call | bound |
|---|---|---|
| `GET /api/tv/playstate` → `DetailService.getPlaystate` (`:225`) | `tvToken` + `getUserDataBulk` × ⌈ids/100⌉ chunks, sequential | **none at all** |
| `GET /api/tv/movie/{id}` · `/series/{id}` → `hydrateRelated` (`:96`, `:206`) | `tvToken` + `fetchPlaystate` over 12 related cards | 2 500 ms |
| `GET /api/tv/browse` → `BrowseService.browseFiltered` (`:78`) | `fetchPlaystate` over **every matched item**, not a page | 2 500 ms |
| `GET /api/tv/search` (both forms, `:172` / `:205`) | `fetchPlaystate` over the result cards | 2 500 ms |
| `GET /api/tv/channels` → `getChannels` (`:104`) | `tvToken` | **none** |
| `getHomeFeed`/`buildHomeFeed` (`:203`) · `getChannelFeed` (`:280`) | `tvToken` | **none** |
| `fetchAllPlaystate` (`:183`) | `fetchPlaystate` over the feed's ids | 2 500 ms |
| `canonicalContinueList` (`:696`) | the four calls above | 6 000 ms shared |

Three things follow that the Home-only framing hid:

- **`/api/tv/playstate` is the one read route with no timeout anywhere on it**, and it is the route the
  detail screen exists on: `MovieDetailScreen`, `SeriesDetailScreen`, `EpisodeCard` and `DetailStore`
  all call it, and `DetailService` has **no cache of any kind**. Every episode row on every season open
  is a live Jellyfin fan-out. R83 designed it that way deliberately — `playback`/`progress` are
  explicitly `null` in the detail payload and hydrated by this route — so the fix is FR-205-2's
  refresher serving it, not a timeout bolted on.
- **`browseFiltered` hydrates the whole match set, not the page it returns.** A "→ See all" over 400
  titles is four chunked round trips before anything renders, and it is the surface R187 added for
  exactly the navigation the owner asked about.
- **`tvToken` is awaited with no timeout** at three sites (`getChannels`, `buildHomeFeed`,
  `getChannelFeed`). On a cache miss — every 5 minutes per token, `TOKEN_VALID_TTL_MS`
  (`PlaybackService.kt:43`) — it makes a live `isTokenValid` round trip. `OutboundHttp`'s client is
  configured `socketTimeoutMillis = 120_000`, `requestTimeoutMillis = 120_000`
  (`OutboundHttp.kt:183-185`), so a wedged Jellyfin can hold a TV request for **two minutes** with
  nothing in between to stop it.

The 2 500 ms bounds are not a defence, only a ceiling: at the measured 84 ms per 100-id lookup they are
never hit in the healthy case, and when they *are* hit the surface silently ships with no watched state —
the same confident-wrong-answer shape as the missing Continue row, one screen over.
- **`fetchAllPlaystate`** is bounded at `WATCHED_TIMEOUT_MS = 2_500`, and its `PLAYSTATE_TTL_MS` is 20 s
  against the feed's 5 min. That is the measured steady-state floor: over four quiet minutes with no
  scan, `/api/tv/home` was 28/40 hits at 13 ms and **12/40 misses at 494 ms median** — the misses being
  playstate expiry, not feed expiry. The underlying call is 84 ms for 100 ids; it only exceeds 2.5 s
  when Jellyfin is degraded, and then the feed ships with no watched state at all.

## Requirements

**FR-205-1 — no viewer read under `/api/tv/**` initiates a Jellyfin request.** The invariant. A read path
serves what is already known and never makes a viewer wait on a dependency whose median for the shape we
need is 1.4 seconds. Everything below serves this. Two deliberate exclusions: the **playback** path
(negotiation, progress, stop — Jellyfin *is* the streaming server, and `PlaybackService` is not in
question), and **`/api/tv/admin/**`**, where `GET /tv/admin/users/{userId}/history`
(`TvRoutes.kt:873`) is documented as "the one section of the overview that must read Jellyfin live" —
an operator-initiated, lazy, one-user-at-a-time read behind a *Show more* expander, which is a different
contract from a viewer's Home screen.

**FR-205-2 — Continue Watching and playstate are maintained off the request path.** Both become
background-refreshed per user on their own cadence, writing into the caches the read path already
consults. Scope the refresh to users with a recently-seen device (`ravilo_device.last_seen`) so a
household of ten historical pairings does not generate ten users' worth of Jellyfin traffic forever.
The refresher, not the reader, owns every Jellyfin call.

**Playstate here means all seven read sites in the table above, not just the feed's.** One refreshed
per-user playstate map, consulted by Home, channel feeds, browse, search, related and — the one with no
cache today — `GET /api/tv/playstate`. That route is the detail screen's only source of resume position
and ✓ (R83 leaves `playback`/`progress` null in the detail payload on purpose), so it must keep
answering the same shape; what changes is where the answer comes from. A single shared map is also the
only way the same title cannot show a ✓ on Home and no ✓ on the detail page, which is reachable today
whenever one of the 2 500 ms bounds fires and the other does not.

**FR-205-3 — an unbuildable row is omitted, never shipped empty.** A Continue Watching list that has
never been successfully built for this user is *unknown*, and an unknown row is absent — which is what
it already does — but it must be **distinguishable in the response and in logs from a genuinely empty
one**, so "this viewer has finished everything" and "we could not ask Jellyfin" stop looking identical.
R231's rule stands: a failed build never overwrites a good cached value. FR-203-2's precedent, and the
same confident-false-negative that cost the v1.12 release.

**FR-205-4 — bound every remaining Jellyfin call on an interactive path.** Any call that survives
FR-205-1 gets an explicit local timeout well under the 120 s client default, and a documented behaviour
on expiry. A 120-second ceiling is not a timeout; it is the absence of one. The known unbounded ones are
in the table above: `tvToken` revalidation at **three** sites (`getChannels`, `buildHomeFeed`,
`getChannelFeed`) and `DetailService.getPlaystate`, which has no bound at all and is reached on every
detail open. If FR-205-2 removes the latter entirely, say so rather than bounding a call that no longer
happens.

**FR-205-5 — a cancelled sibling must not be logged as a failed call.** When one of a concurrent group
times out, the others' cancellations are reported as their own failures (39 `getNextUp` "failures" for a
call that takes 1.0 s). Distinguish cancellation from failure at the `runCatching` sites so the log
names the slow call. This is a diagnosability requirement, not cosmetic: the log actively misdirected
this investigation.

**FR-205-6 — reduce what `getRecentlyTouched` asks for, or stop asking on a schedule that matters.**
Once FR-205-2 moves it off the request path its latency stops being a user-visible cost, but it remains
~1.4 s (and up to 8.6 s) of whole-library sort load on the shared dependency, per user, per refresh.
R219 FR-R219-3 verified the filter that would make it cheap does not exist, so the options are a coarser
cadence than the other three fetches, a narrower window than `CONTINUE_TOUCHED_WINDOW_DAYS = 7`, or
deriving "touched" from a source we own. See open question 2 — **this phase should not guess.**

**FR-205-7 — measure the background-work-to-Jellyfin coupling before changing any I/O behaviour.** The
`+0.53` correlation and the dead `ionice -c3` are both real, and together they are suggestive, not
conclusive. Required before any scheduler, `ionice`, `nice`, or read-throttling change ships: Jellyfin
`/Items` latency sampled with and without a `detect_segments` job in flight, several dozen samples each,
with `iostat` per-device figures alongside. The A/B this phase's investigation attempted produced 39
busy samples and **1** idle sample, which is why this is a requirement and not a conclusion. Same
posture as FR-187-1's live endpoint probe and R240's invariant-11 measurement: probe, then build.

**FR-205-8 — Jellyfin gets the Phase 183 treatment, or a stated reason it does not.** Phase 183 gave
TMDB a token bucket with AIMD on 429, `Retry-After` handling, and jittered backoff, because an unpaced
background fan-out against a shared dependency was starving the thing viewers use. Jellyfin is the same
shape with higher stakes — it is also the playback path. Either pace and prioritize it the same way, or
record why Jellyfin is different. Not left implicit.

**FR-205-9 — a playback stop still corrects the row at once.** `invalidatePlaystate` must keep
correcting Continue immediately (the bug it fixed: a just-finished episode still in the row for five
minutes). Under FR-205-2 that becomes *trigger a refresh*, not *make the next reader pay for one*.

## Out of scope

- **The cache-key thrashing that makes cold rebuilds frequent.** Phase 204. Deliberately separate: 204
  is a small-blast-radius cache-key change, this phase restructures where Jellyfin is called from, and
  shipping them together would make a regression in either impossible to attribute.
- **Channel feed cost.** Phase 206.
- **`prewarm_subtitles`' 285 failing calls per run.** Phase 207. Related — it is background load on the
  same shared dependency — but it is a two-line URL bug with its own root cause and does not belong
  inside a restructuring phase.
- **Replacing Jellyfin, or changing how playback negotiates with it.** Jellyfin is not replaceable and
  the playback path is not in question. This phase is about *reads that could have been answered without
  asking it*.
- **The public-HTTPS hop.** `jellyfin_url` is `https://jellyfin.example.net`, which resolves to
  `192.0.2.20` — the same host. Measured: connect + TLS is ~30 ms against a 1.4 s query. Switching to a
  local address is not worth the config churn. Recorded so it is not re-proposed.
- **Making `getUserDataBulk` chunk larger.** `HYDRATE_CHUNK = 100` is safely under Jellyfin's URL-length
  ceiling, but only by luck: 250 ids returns an **empty body** with no error. A guard on that
  relationship is worth having and is a separate small item, not a latency fix — 100 ids is already
  84 ms.

## Open questions

1. **What does a genuinely cold user see on their first load?** FR-205-2 means nobody's list is built
   until the refresher has run for them. A first-ever sign-in, or the first load after a restart, has no
   cached answer and no reader allowed to fetch one. Options: omit the row until the first refresh lands
   (honest, and the row appears seconds later), or allow exactly one bounded synchronous build per cold
   user (faster, but reintroduces the thing FR-205-1 forbids, for the case most likely to be slow).
   **Leaning omit**, on FR-203-2's reasoning, but this is the decision that most shapes the phase.
2. **Can "touched in the last 7 days" come from a source we own?** `playback_start_sample` and
   `playback_qoe` record Ravilo sessions only — a title watched in Jellyfin's own web UI or on Wholphin
   would be invisible, so this would narrow Continue Watching's membership rule, which R219 §2(c)
   defined deliberately. Needs a decision about whether that narrowing is acceptable before it can be
   treated as an optimization rather than a behaviour change.
3. **What refresh cadence?** `PLAYSTATE_TTL_MS` is 20 s today because watched state changes often on a
   shared household. A background refresher at 20 s per active user × N users is real sustained Jellyfin
   load; at 2 minutes the ✓ marks lag noticeably. The R176 `playstate_changed` push may make a slower
   poll acceptable, since a device that just watched something gets corrected by FR-205-9's path rather
   than by the poll.
4. **Should the refresher back off when Jellyfin is degraded?** If Jellyfin is answering in 8 s, polling
   it harder is the wrong response — but stopping means every viewer's row goes stale with no signal.
   FR-205-8's pacing work may answer this; if it does, say so there rather than twice.
5. **Should `browseFiltered` hydrate the page or the match set?** It currently hydrates every matched
   item (`BrowseService.kt:78`) though it returns a page, which on a 400-title "→ See all" is four
   chunked round trips for tiles nobody scrolled to. Under FR-205-2 the cost moves off the request path
   and the question becomes moot — unless the refresher's map is itself scoped, in which case a browse
   over rarely-touched titles finds nothing cached and the page-vs-match-set choice returns. Worth
   deciding once, where the refresher's scope is decided.
6. **Is `getRecentlyPlayedAll`'s sequential paging worth parallelizing?** 7 pages × ~0.25 s warm. Off the
   request path it hardly matters, which may be the answer — but it is 1.8 s of one user's refresh and
   the page count grows with watch history, unbounded.
