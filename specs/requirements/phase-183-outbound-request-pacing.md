# Phase 183 — pace outbound requests by rate, not just by concurrency

> Requested 2026-08-31: *"Currently when doing a scan we almost everytime get rate-limited, and I'm
> guessing this is because, even though we have amount of 'workers', when we are working with series it
> just goes ahead and starts it's own concurrency or something. So let's figure out a good approach to
> tackle this."*

**Status:** Planned. Design-authored 2026-08-31 from a source read of the live tree; **not yet
dev-reviewed**. The user's diagnosis is correct and is confirmed below by the logs they supplied.

Related: **Phase 182** makes the server survive a scan. This phase removes the load that makes a scan
dangerous in the first place — the two are independent fixes for one incident. Phase 182 §2.4's
unsynchronised-cache defect also covers `TmdbClient`'s two caches, which this phase makes much hotter;
do not ship 183 without 182's FR-182-2.

---

## 1. The reported case

```
19:05:03 Scan [W#3] TMDB rate-limited (429), retrying in 3s (attempt 4/5): …/tv/2777/season/2/episode/77/credits
19:05:03 Scan [W#3] TMDB rate-limited (429), retrying in 3s (attempt 2/5): …/tv/2777/season/3/episode/18/credits
19:05:03 Scan [W#3] TMDB rate-limited (429), retrying in 3s (attempt 4/5): …/tv/2777/season/3/episode/19/credits
19:05:03 Scan [W#3] TMDB rate-limited (429), retrying in 3s (attempt 1/5): …/tv/2777/season/2/episode/23/credits
19:05:03 Scan [W#3] TMDB rate-limited (429), retrying in 3s (attempt 5/5): …/tv/2777/season/4/episode/2
19:05:03 Scan [W#3] TMDB rate-limited (429), retrying in 3s (attempt 5/5): …/tv/2777/season/3/episode/0
… (18 lines, all timestamped 19:05:03)
```

Three things are provable from this excerpt alone, before reading any code:

1. **Every line carries the same worker id, `[W#3]`.** The user's intuition — "even though we have
   amount of workers … it just goes ahead and starts its own concurrency" — is exactly right, and this
   is the proof. `WorkerId` is a `CoroutineContext` element installed once per worker
   (`MediaRoutes.kt:2252`, `launch(scanDispatcher + WorkerId(wid))`) and **inherited by every child
   coroutine**. Eighteen simultaneous in-flight TMDB requests all reporting `W#3` means eighteen
   concurrent children of a single worker slot.
2. **Eighteen requests share one second**, spanning attempts 1/5 through 5/5. Retries at different
   attempt counts have converged onto the same instant — a synchronised wave, not scattered traffic.
3. **`attempt 5/5` appears**, which is the last retry before the request is abandoned.

---

## 2. What's there now

### 2.1 A series fans out one request per episode, unbounded, from a single worker

`Scanner.scanSeries` (`Scanner.kt:504-585`):

```kotlin
val episodes = coroutineScope {
    filesToProbe.map { file ->
        async {
            …
            val epDetails = tmdb.getEpisodeDetailsLocalized(seriesTmdbId, seasonNum, epNum, epLangPriority)
            val (epGuests, epCrew) = fetchEpisodeCredits(seriesTmdbId, seasonNum, epNum)
            …
        }
    }.awaitAll().flatten()
}
```

There is **no bound on the number of `async` children.** A 300-episode series dispatches 300 coroutines
at once. `Scanner.syncSeriesEpisodes` (`:838-903`) has the identical shape and the identical absence of
a bound. The change that introduced this was deliberate and its reasoning is in the code comment at
`:496-503` — *"real concurrency stays bounded by the existing ProcessGate (ffprobe) and OutboundHttp
(TMDB) gates exactly as before, this only changes how many files get DISPATCHED into those gates at
once"*. That reasoning is sound for **ffprobe**, where `ProcessGate` bounds a real resource. It does not
hold for TMDB, for the reason in §2.3.

**Requests per episode**, all outbound, none cached across episodes:

| Call | Count | Site |
|---|---|---|
| `getEpisodeDetailsLocalized` | 1 per language in the priority list, **plus** a regional retry per language that returns empty, **plus** a final untagged fallback | `TmdbClient.kt:761-779` |
| `fetchEpisodeCredits` | exactly 1, **unconditional** | `Scanner.kt:1366`, `TmdbClient.kt:541-546` |

With a two-language priority list and a series TMDB has no localised data for, that is **up to 5-6
requests per episode**. The credits call is issued on every scan regardless of whether we already hold
that episode's guest stars and crew — a full re-scan re-fetches all of them.

For the reported series that is comfortably **1 500-2 000 requests for one item**, dispatched as a
single burst by one of four workers.

### 2.2 The retry is a fixed 3 s with no jitter and ignores `Retry-After`

`TmdbClient.httpGet` (`:350-363`):

```kotlin
private suspend fun httpGet(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    var attempt = 0
    while (true) {
        val response = OutboundHttp.withPermit { http.get(url, block) }
        if (response.status != HttpStatusCode.TooManyRequests) return response
        attempt++
        if (attempt > MAX_429_RETRIES) { Logger.warn("… giving up: $url"); return response }
        Logger.warn("TMDB rate-limited (429), retrying in 3s (attempt $attempt/$MAX_429_RETRIES): $url")
        delay(3000)
    }
}
```

Three defects, in ascending order of severity:

- **No jitter.** Every 429'd request sleeps exactly 3 000 ms, so a batch that gets rate-limited together
  retries together, gets rate-limited together again, and re-converges. This is precisely what the
  18-lines-in-one-second log shows.
- **`Retry-After` is never read.** TMDB tells us how long to wait; we ignore it and guess.
- **No backoff.** Attempt 5 waits exactly as long as attempt 1, so five attempts cover a 12-second
  window regardless of how long the limiter actually wants us to back off.

This function was itself a bug fix — it replaced ~18 per-call-site unbounded recursive retries, and its
doc comment (`:338-348`) says so. It correctly bounded and made visible a retry storm. It did not stop
one.

### 2.3 The only throttle in the system limits concurrency, and concurrency is not rate

`OutboundHttp` is `Semaphore(64)` (`OutboundHttp.kt:31`). A semaphore bounds **how many requests are in
flight**; a rate limit bounds **how many start per second**. With 64 in flight and a ~100 ms TMDB
round-trip, the emitted rate is roughly **640 requests/second** — and it rises as latency falls, which
is the wrong direction entirely. There is **no token bucket, no requests-per-second ceiling, and no
per-host pacing anywhere in the backend.**

`pipelineStepConcurrency` (`PipelineStepPool.kt:150-159`) shows the team already knew a per-host rate
problem existed and solved it once, narrowly:

```kotlin
fun pipelineStepConcurrency(step: String, scanWorkers: Int): Int = when (step) {
    "sync_imdb_ratings" -> scanWorkers.coerceIn(1, 2)   // "each call keeps its own inter-call delay"
    else -> scanWorkers.coerceIn(1, 100)
}
```

That is a per-*step* concurrency cap standing in for a per-*host* rate limit. It cannot help `scanSeries`
at all — `scan_files` doesn't go through `runPipelineStepPool`, and the fan-out happens *below* the
worker level regardless.

Note also that `behavior.scan_workers = 4` in the live config. The operator set a small, careful number.
It bounds items, not requests, and nothing in the UI or config says so.

### 2.4 Being rate-limited silently loses metadata

After `MAX_429_RETRIES`, `httpGet` **returns the 429 response** rather than throwing. Every caller then
does `response.body<TmdbEpisodeDetails>()` inside a `runCatching`, deserialisation of the error body
fails, and the caller returns `null` (`TmdbClient.kt:741-754` is the pattern). The episode is then
written with `title = null`, `overview = null`, `stillPath = null`, `runtime = null`, `airDate = null`
and no cast.

So a rate-limit storm does not merely make the scan slow — it **writes an incomplete library and marks
the item as freshly scanned**. Nothing distinguishes "TMDB has no data for this episode" from "we got
429'd five times". The `attempt 5/5` lines in the reported log are exactly this case, already happening.
`Phase 181`'s freshness logic will then treat those items as checked and not revisit them.

### 2.5 The retry loop makes the queue worse, not better

Each retry re-enters the same 64-permit queue from the back, behind every other in-flight request from
the same burst. At the volumes in §2.1 this is self-sustaining: the burst causes 429s, the 429s produce
retries, the retries lengthen the queue, and the lengthened queue is what Phase 182 §2.5 shows starving
Ravilo. **The two reported incidents are one incident.**

---

## 3. Non-goals

- **Not** reducing metadata coverage. Fewer requests must come from *not repeating work*, not from
  fetching less.
- **Not** dropping `OutboundHttp`. The concurrency/FD ceiling is load-bearing (`FdWatchdog`'s budget)
  and stays. Rate pacing is an **additional**, per-host layer above it.
- **Not** a TMDB API-key or plan change.
- **Not** making scans faster. A correctly paced scan may well take longer in wall-clock terms; it will
  finish with a complete library and without taking the server down, which is the point.

---

## 4. Requirements

**FR-183-1 — A per-host outbound rate limiter.** Introduce a token-bucket rate limiter keyed by outbound
host, applied **inside** `OutboundHttp` so no call site can bypass it, and composed with (not replacing)
the existing permit. Per host: a sustained requests-per-second rate and a burst allowance.

- TMDB's current numeric limit is **not published** — the documented 40-per-10-seconds limit was
  withdrawn in 2019 and what remains is an undocumented per-IP ceiling. **Do not hard-code a guessed
  number as if it were a fact.** Make the rate a named, configurable constant with a conservative
  default, and let FR-183-3's feedback loop find the real ceiling empirically.
- Hosts with no configured entry are unlimited, so this changes nothing for Jellyfin, *arr, Seerr or
  Bazarr unless someone deliberately configures them.
- The limiter must be **fair** — a request that has waited longer goes first — so one series' burst
  cannot starve a small item queued behind it indefinitely.

**FR-183-2 — Honour `Retry-After`, back off, and jitter.** `httpGet`'s 429 branch is replaced with:

- read the `Retry-After` response header and wait at least that long when present;
- otherwise exponential backoff from a small base, not a flat 3 s;
- **full jitter on every wait**, so a batch that is limited together does not retry together. This is
  the specific fix for the 18-identical-timestamps evidence.

A 429 also **feeds back into FR-183-1's bucket for that host** — the limiter's rate drops on 429 and
recovers gradually on sustained success (an AIMD-style controller). This is what lets the system find
TMDB's real ceiling instead of us guessing it.

**FR-183-3 — Bound per-item fan-out.** `scanSeries` and `syncSeriesEpisodes` stop dispatching one
coroutine per episode file with no ceiling. Both route their per-episode work through a **bounded**
dispatch — the same `runPipelineStepPool` shape already used everywhere else, or an equivalent bounded
helper — so the number of concurrent episodes per item is a stated number.

The ceiling must be **derived from `scan_workers`, not independent of it**, so that the operator's "4"
means something end to end. Concretely: total concurrent per-episode work across the whole run should
not exceed a small multiple of `scan_workers`, not `scan_workers × episodes_per_series`. This is the
requirement that makes the user's mental model true.

**FR-183-4 — Stop re-fetching data we already have.** `fetchEpisodeCredits` is called unconditionally on
every scan of every episode (`Scanner.kt:546`, `:867`). Skip the call when the stored episode already
has guest stars/crew and nothing about the episode's identity has changed, unless the run is an explicit
full/forced re-pull. Apply the same test to `getEpisodeDetailsLocalized` where the stored episode
already carries a title, overview, still and runtime in the resolved language.

Expected effect on the reported series: a re-scan drops from ~1 500-2 000 requests to approximately
zero. **This is the single largest lever in the phase** — pacing makes the storm survivable, but not
issuing the requests makes it not happen.

**FR-183-5 — A rate-limited fetch must never be recorded as a successful one.** When a request is
abandoned after exhausting retries, the caller must be able to tell that apart from "TMDB has no data".
At minimum:

- `httpGet` surfaces exhaustion as a distinct, typed outcome rather than returning the 429 `HttpResponse`
  for a caller to fail to deserialise;
- an item whose scan hit an exhausted 429 is **not** stamped as freshly/completely checked, so Phase
  181's freshness filter re-visits it rather than treating the gap as settled;
- the run summary and the Activity page state the count plainly — "N fields not fetched: TMDB rate
  limit" — instead of the current silence.

**FR-183-6 — Rate-limiting is visible while it is happening.** The Activity page shows, for a run in
flight: current outbound rate per host, the limiter's current ceiling, and 429s in the last minute. A
run that ends having been rate-limited says so in its summary. Today the only evidence is a wall of
identical WARN lines in the process log, which is how this went unnoticed long enough to become "almost
every time".

**FR-183-7 — Measure it.** Before and after, on the real deployment, for the same series:

| Metric | Before | After |
|---|---|---|
| Total TMDB requests for one full scan | | |
| Peak observed requests/second | | |
| 429 responses | | (target: 0 in steady state) |
| Requests abandoned at attempt 5/5 | | (target: 0) |
| Wall-clock scan duration | | |
| Episodes written with null title/overview | | (target: 0) |

Wall-clock is expected to move in the wrong direction and that is acceptable; the last row is not.

---

## 5. Open questions for dev review

1. **What is TMDB's actual current ceiling from this IP?** Worth measuring directly (a controlled ramp
   against a cheap endpoint) before picking FR-183-1's default, rather than inheriting a number from
   documentation that no longer exists. Record the measured figure in this spec.
2. **Should the limiter be global or per-API-key?** Only relevant if a second TMDB key is ever
   configured; per-host is simpler and almost certainly right. Confirm and close.
3. **Does the regional-retry cascade in `getEpisodeDetailsLocalized` need to be per-episode at all?**
   Whether TMDB carries `fo`/`fo-FO` data for a *series* is a series-level fact —
   `getRegionedLanguageTags` is already cached per series for exactly this reason. If a series is known
   to have no localisation, every episode's language cascade could collapse to one untagged call. That
   would be a further large reduction on top of FR-183-4, but it changes resolution semantics, so it
   needs a deliberate decision rather than an implementer's judgement call.
4. **Interaction with Phase 182's FR-182-6 partition.** A background rate limiter and a background
   permit reserve are two ceilings on the same traffic. Confirm they compose sensibly (the rate limiter
   should sit inside the permit, so a waiting-for-tokens request is not also holding an FD) and that the
   interactive class is exempt from per-host pacing, or at minimum has its own far higher bucket.
5. **`sync_imdb_ratings`' bespoke cap.** Once FR-183-1 exists, `pipelineStepConcurrency`'s
   `imdbapi.dev` special case should become an ordinary per-host rate entry and the special case should
   be deleted. Confirm the inter-call delay it references lives somewhere that FR-183-1 subsumes.
