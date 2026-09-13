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
Planned, written 2026-09-13. Audit-authored, not dev-reviewed, not built. Backend-only, no UI, no
client change. Small and self-contained — a wrong URL and an avoidable fan-out.

Fixes a shipped phase: **179** (`✓ Implemented`). Its functional goal has never once been achieved in
production, so R183's cold-extraction problem is entirely unmitigated today.

## The finding

### The URL rejects the token, and the codebase already documented that

`getItemMediaStreams` (`JellyfinClient.kt:876`):

```kotlin
val url = baseUrl.trimEnd('/') + "/Items/$jellyfinId?Fields=MediaStreams"
httpGet(url) { jellyfinAuth(token) }        // token = cfg.apiKeys.jellyfinToken, the admin token
```

Verified live against this household's Jellyfin, same token, same host:

| request | result |
|---|---|
| `GET /Items/{id}?Fields=MediaStreams` | **HTTP 400** — `Error processing request.` |
| `GET /Items?Ids={id}&Fields=MediaStreams` | HTTP 200 |

The failure mode was already written down, **four hundred lines earlier in the same file**, in
`getItem`'s own comment (`:398`):

> The non-user-scoped single-item route `/Items/{id}` 400s with a server token across Jellyfin versions
> (it expects `/Users/{userId}/Items/{id}`); the `Ids=` filter on the list endpoint is accepted with the
> same token + Fields.

`getItemMediaStreams`' doc comment says it *"[u]ses the admin token, same pattern as
[getSeriesEpisodesMeta]"* — and that sibling uses `/Shows/{seriesId}/Episodes?Fields=…`, a **list**
endpoint, which is exactly why the admin token works there. Phase 179 copied the token half of the
pattern and paired it with a single-item route, the one shape that rejects it.

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

### The fix is one call per series, and it is measured

Verified live, admin token, same host:

```
GET /Shows/{seriesId}/Episodes?Fields=MediaStreams   →  HTTP 200, 38 ms, 23/23 episodes carry MediaStreams
```

One 38 ms request replaces N sequential failing ones, using the token the pipeline already holds and a
route shape the codebase already trusts.

### What this has been costing

Phase 179 exists because R183 measured a **4m37s cold ffmpeg subtitle extraction on a 26 GB file**, and
a live client request can lose the race against Jellyfin extracting the same file. FR-179-1's pre-warm
is the mitigation. It has never run. Every embedded text subtitle in the library is still cold at first
play, which is a viewer-facing stall nobody has connected to this step because the step reports success
with a count of zero.

## Requirements

**FR-207-1 — read a title's media streams through a route the admin token is accepted on.** Either the
`Ids=` list-endpoint shape `getItem` already documents, or — preferred, and measured above —
`/Shows/{seriesId}/Episodes?Fields=MediaStreams`, which answers for a whole series in one request. The
fix must not be a third variation invented here; both working shapes already exist in this file.

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

**FR-207-5 — verify the warm actually warms.** `warmSubtitleExtraction` has never executed against a
real file. Once FR-207-1 lands, confirm on one title with an embedded text subtitle that the pre-warm
request returns successfully and that a subsequent play of that subtitle is measurably faster than a
cold one. Without this, the phase has only fixed the *lookup* and would report success on the strength
of a non-zero count — which is precisely how the original bug survived.

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

1. **Does a movie need a different route than a series?** `/Shows/{id}/Episodes` has no movie
   equivalent, so movies likely still want the `Ids=` shape — possibly batched across the whole working
   set in one request rather than one per movie. Worth confirming whether `Ids=` accepts
   `Fields=MediaStreams` for a large id list, given `getUserDataBulk` returns an **empty body** at 250
   ids (a URL-length ceiling with no error, found while measuring Phase 205).
2. **Should the step skip titles whose streams it already knows?** After Phase 200, `Track` records
   subtitle streams from our own ffprobe, including `external`. If the local record is sufficient to
   decide which streams need warming, the Jellyfin lookup could be skipped entirely for most titles —
   but Jellyfin's stream *indices* are what `warmSubtitleExtraction` needs, and those are Jellyfin's
   numbering, not ours. Probably not, but it would reduce this step to zero lookups if true.
3. **How many other Jellyfin calls have never worked?** Two now confirmed (`POST /MediaSegments` → 405,
   `GET /Items/{id}` → 400), both discovered only when someone measured rather than read a log. A
   deliberate audit — every `JellyfinClient` method exercised once against the live server, with its
   status recorded — would be a cheap, high-value pass, and this is the second phase in a row that
   would have been unnecessary if it existed.
