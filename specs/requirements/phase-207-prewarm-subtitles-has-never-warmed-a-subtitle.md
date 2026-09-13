# Phase 207 — `prewarm_subtitles` has never warmed a subtitle

> Production log, 2026-09-13, one pipeline run, verbatim:
>
> ```
> [INFO] Pipeline step: prewarm_subtitles
> [INFO] prewarm_subtitles: 1 items
> [WARN] Jellyfin getItemMediaStreams failed: 400 Error processing request.     ← ×285, over 14 seconds
> [INFO] prewarm_subtitles: 0 subtitle stream(s) warmed
> ```
>
> The step reports its own failure on every run, in its own summary line, and has done since Phase 179
> shipped. `0 subtitle stream(s) warmed` was read as "nothing needed warming."

## Status
✓ Built 2026-09-13. Audit-authored, not dev-reviewed, not deployed. Backend-only, no UI, no client
change. `compileKotlinLinuxX64` clean; not live-verified against production (the running backend still
has the old 400-on-every-call behaviour until redeployed).

**Build notes:** `getItemMediaStreams` now uses `/Items?Ids={id}&Fields=MediaStreams` (FR-207-1) — the
`Ids=` shape rather than `?userId=`, since this is a scan-time pipeline step with no per-device viewer
to scope a `userId` to; it needs none, unlike the `?userId=` option this spec originally favoured. This
also fixes `TrackRoutes.kt:701`'s `/media/health/subtitle-reconciliation` route for free — a second
caller of the same broken function this spec's "Out of scope" section undercounted as having "a single
caller." New `JellyfinClient.getSeriesEpisodesMediaStreams` gives FR-207-2 its one-call-per-series
shape and reads Jellyfin's own episode list directly (no join against `item.episodes`, so an
un-backfilled `jellyfinId` no longer skips an episode). FR-207-3 is `PipelineStepOps.PrewarmOutcome`
(`Warmed`/`Skipped`/`LookupFailed`) plus `PipelineEngine`'s run-summary WARN when every attempted lookup
failed. FR-207-6 needed no change — the step already runs inside the existing scan-pool/`ProcessGate`
partitioning.

Fixes a shipped phase: **179** (`✓ Implemented`). Its functional goal has never once been achieved in
production, so R183's cold-extraction problem is entirely unmitigated today.

## The finding

### The route is fine. It is missing one query parameter.

`getItemMediaStreams` (`JellyfinClient.kt:876`):

```kotlin
val url = baseUrl.trimEnd('/') + "/Items/$jellyfinId?Fields=MediaStreams"
httpGet(url) { jellyfinAuth(token) }        // token = cfg.apiKeys.jellyfinToken, the admin token
```

Probed live against this household's Jellyfin 10.11.11, same token, same host (2026-09-13):

| request | result |
|---|---|
| `GET /Items/{id}` | **HTTP 400** — `Error processing request.` |
| `GET /Items/{id}?Fields=MediaStreams` | **HTTP 400** |
| `GET /Items/{id}?userId={uid}` | **HTTP 200** |
| `GET /Items/{id}?userId={uid}&Fields=MediaStreams` | **HTTP 200** |
| `GET /Items?Ids={id}&Fields=MediaStreams` | HTTP 200 |

**`GET /Items/{itemId}` is declared in 10.11.11's OpenAPI and accepts the admin token.** It 400s only
because it has no user context. One `userId` query parameter is the entire defect.

This corrects the existing account in `getItem`'s own comment (`:398`), which is approximately right and
precisely wrong — and which is what a future reader will trust:

> The non-user-scoped single-item route `/Items/{id}` 400s with a server token across Jellyfin versions
> (it expects `/Users/{userId}/Items/{id}`); the `Ids=` filter on the list endpoint is accepted with the
> same token + Fields.

The token is not the problem and `/Users/{userId}/Items/{id}` is not required — that route is in fact
**undocumented** in 10.11.11 (see Phase 208). The same 400-on-missing-user-context shows up on
`GET /Users/Me` with a server token, so this is a general Jellyfin behaviour and not a quirk of one
route: **a route that needs a user answers 400, not 401 or 403, when you don't name one.** Worth
knowing, because 400 reads as "malformed request" and sends you looking at the wrong half.

`getItemMediaStreams`' doc comment says it *"[u]ses the admin token, same pattern as
[getSeriesEpisodesMeta]"* — and that sibling uses `/Shows/{seriesId}/Episodes?Fields=…`, a list endpoint
that needs no user context at all, which is why it works. Phase 179 copied the token half of the pattern
onto a route that needed a user named and never got a 200 to notice.

Because `warmPlayable` bails on the null (`?: return 0`), `warmSubtitleExtraction` — the function that
does the actual work this phase exists for — **has never been called in production either.** It is
untested by anything except a compiler.

### One call per episode, sequential, all of them failing

`PipelineStepOps.prewarmSubtitles` (`:74`):

```kotlin
MediaKind.TV_SHOW -> item.episodes.sumOf { warmPlayable(it.jellyfinId) }
```

One Jellyfin round trip per episode, in a `sumOf` — sequential. The observed burst was **285 calls in
14 seconds for a single series**. Each 400 returns in roughly 5 ms, so the wasted load is real but
modest (~1.4 s of cheap failures per run); this is a correctness bug that also generates log noise
against the shared dependency, not a significant source of Jellyfin load. Recorded that way on purpose:
an earlier draft of this investigation overweighted it as a latency cause, and the measurement did not
support that.

### Two working fixes, both measured

Verified live, admin token, same host:

```
GET /Shows/{seriesId}/Episodes?Fields=MediaStreams         → 200,  38 ms, 23/23 episodes carry MediaStreams
GET /Items/{id}?userId={uid}&Fields=MediaStreams           → 200
GET /Items?Ids={id}&Fields=MediaStreams                    → 200
```

The `/Shows` form answers a whole series in one request and is the better shape for series. The `userId`
form is the minimal change and — unlike `/Shows` — **also works for movies and music videos**, which is
what settles this phase's original open question about them.

### What this has been costing, measured

Phase 179 exists because R183 measured a **4m37s cold ffmpeg extraction on a 26 GB file**, and a live
client request can lose the race. FR-179-1's pre-warm is the mitigation and it has never run, so the
premise had never actually been tested end to end either. It has now — the exact URL
`warmSubtitleExtraction` builds, executed for the first time in this library's history, against an
ordinary `subrip` stream in an ordinary movie:

| | HTTP | time | body |
|---|---|---|---|
| cold (first ever request for this stream) | 200 | **21.16 s** | 85 941 b of valid WEBVTT |
| warm (immediately after) | 200 | **0.015 s** | identical |

**A ~1 400× difference, and 21 seconds is the ordinary case** — a text `subrip` track in a normal-sized
film, not R183's 26 GB worst case. So every embedded text subtitle in the library is currently paying
roughly this on first selection, on a surface where the viewer has already pressed a button and is
waiting. The mechanism works; it has simply never been switched on.

This also confirms `warmSubtitleExtraction`'s own URL is correct
(`/Videos/{id}/{id}/Subtitles/{index}/0/Stream.vtt`, matching the documented
`Subtitles/{routeIndex}/{routeStartPositionTicks}/Stream.{routeFormat}`), so the *only* defect in the
whole step is the missing `userId` one function upstream.

## Requirements

**FR-207-1 — name a user on the media-streams read.** Three shapes are live-verified above; the fix must
be one of them and not a fourth invented here. `/Shows/{seriesId}/Episodes?Fields=MediaStreams` for a
series (one request, satisfies FR-207-2 for free) and `?userId=` for a movie or music video is the
combination this phase recommends. Whichever is chosen, correct `getItem`'s comment at the same time —
it currently misattributes this failure to the token and names a route that is itself undocumented, and
leaving it in place invites the next author to repeat the mistake from the same bad note.

**FR-207-2 — one Jellyfin request per series, not one per episode.** A 300-episode series must not
produce 300 sequential round trips whether they succeed or fail. If FR-207-1 takes the `/Shows` route
this falls out for free; if it takes `Ids=`, the ids must be batched.

**FR-207-3 — a step that achieves nothing must not report success.** `prewarm_subtitles: 0 subtitle
stream(s) warmed` is indistinguishable from "this title has no embedded text subtitles", which is a
perfectly normal outcome. A run where **every** stream lookup failed is a different thing and must say
so at `WARN` with a count, once per run rather than once per episode. This is the requirement that would
have surfaced the bug on day one, and it generalizes: a pipeline step's summary line is the only thing
anyone reads, so it must distinguish "nothing to do" from "could not tell".

**FR-207-4 — probe the endpoint against this household's Jellyfin before trusting it.** Phase 163's
`POST /MediaSegments` → 405 and Phase 187's FR-187-1 (where Jellyfin's own OpenAPI document turned out to
be *wrong* about the image body) are the standing precedent, and this phase is the third instance of the
same lesson. The probe results for FR-207-1's chosen route are recorded above; if the implementation
picks a different shape, it re-probes rather than assuming.

**FR-207-5 — verify the warm actually warms.** **Satisfied for one file during this phase's audit**
(21.16 s cold → 0.015 s warm, valid WEBVTT, table above), which establishes that the mechanism works and
that the URL is right. What remains is to confirm it end-to-end *through the step* once FR-207-1 lands:
a non-zero warmed count, and a spot check that the second request for the same stream is fast. Without
that last part the phase would only have fixed the *lookup* and would report success on the strength of
a count — which is precisely how the original bug survived.

**FR-207-6 — the sweep stays on the background lane.** Unchanged from Phase 179, restated because
FR-207-2 makes the step genuinely do work for the first time: once these calls start succeeding, the
step will pull real ffmpeg extraction work out of Jellyfin, which is a substantially larger load than
285 instant 400s. It must respect Phase 182's `GateClassKind.BACKGROUND` partitioning, and Phase 205's
open question about pacing Jellyfin applies to it directly.

## Out of scope

- **Whether pre-warming every text subtitle is the right strategy.** FR-179-1 decided to warm every
  text stream rather than guess which files will transcode, because that depends on the *playing
  device's* decode ceiling (Phase 177) and not on the file. That reasoning stands and is not revisited.
- **Sidecar subtitles.** Phase 179 already skips `isExternal` streams — a sidecar `.srt` is served as-is
  and never ffmpeg-extracted, so there is nothing to warm. Phase 200's ingestion work does not change
  that.
- **The pipeline's own cadence or step ordering.** Unexamined.
- **`getItem`'s and `getSeriesEpisodesMeta`'s callers.** Both work today. This phase touches
  `getItemMediaStreams` and its single caller.
- **Auditing every other Jellyfin URL in the file for the same mistake.** Tempting, and arguably owed
  given this is the second time a single-item route has been used with a server token — but a
  file-wide audit is a different piece of work with a different risk profile, and three other call
  sites that currently work should not be touched inside a bug fix. Worth its own pass; see open
  question 3.

## Open questions

1. ~~**Does a movie need a different route than a series?**~~ **Answered by the 2026-09-13 audit:**
   `GET /Items/{id}?userId={uid}&Fields=MediaStreams` returns 200, so movies and music videos are covered
   by the `userId` fix without needing the `/Shows` form. Batching several movies into one
   `Ids=`+`Fields=MediaStreams` request also works and would cut the per-movie call count — but keep it
   under the URL-length ceiling, since `getUserDataBulk` returns an **empty body with no error** at 250
   ids (found while measuring Phase 205).
2. **Should the step skip titles whose streams it already knows?** After Phase 200, `Track` records
   subtitle streams from our own ffprobe, including `external`. If the local record is sufficient to
   decide which streams need warming, the Jellyfin lookup could be skipped entirely for most titles —
   but Jellyfin's stream *indices* are what `warmSubtitleExtraction` needs, and those are Jellyfin's
   numbering, not ours. Probably not, but it would reduce this step to zero lookups if true.
3. ~~**How many other Jellyfin calls have never worked?**~~ **Audited 2026-09-13 — this one is done.**
   All 25 read-only `JellyfinClient` methods were exercised against the live 10.11.11 with the real admin
   token: **24 returned 200, and `getItemMediaStreams` was the only failure.** So this phase is the whole
   of that problem, not the tip of it. The audit did surface a separate, unrelated risk — 13 call sites
   depend on Jellyfin routes that are **undocumented** in 10.11.11 though they work today — which is
   **Phase 208**, not this phase. Mutating methods were deliberately not fired against a live household
   and are cross-referenced against the OpenAPI document only.
