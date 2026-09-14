# Phase 210 — `prewarm_subtitles` reports fake success when its own deadline is hit

> Production log, 2026-09-14, one pipeline run, verbatim (two items shown, same run):
>
> ```
> 08:48:03 Subtitles Jellyfin subtitle pre-warm failed (item=0d9b95332af0ae695f2b99cdbea21169 index=6): Timed out waiting for 600000 ms
> 08:48:03 Subtitles Jellyfin subtitle pre-warm failed (item=0d9b95332af0ae695f2b99cdbea21169 index=7): Timed out waiting for 600000 ms
> ... (indices 8-14, same item, same timestamp)
> 08:48:03 Subtitles Jellyfin subtitle pre-warm failed (item=20b65fe097c5b82ec57450ad96564fab index=3): Timed out waiting for 600000 ms
> 08:48:03 Subtitles Jellyfin subtitle pre-warm failed (item=20b65fe097c5b82ec57450ad96564fab index=4): Timed out waiting for 600000 ms
> 08:48:03 Subtitles Jellyfin subtitle pre-warm failed (item=20b65fe097c5b82ec57450ad96564fab index=5): Timed out waiting for 600000 ms
> ```
>
> Nine identically-timestamped "timeout" lines for one item, three for another. Investigated after the
> user noticed the burst and asked why. Phase 207 (2026-09-13, the day before) is what made this step
> reach real Jellyfin extraction work for the first time — before 207 it 400'd instantly on every call
> and never got this far.

## Status
✓ Built 2026-09-14 — spec'd and fixed same day. Backend-only, no UI, no client change.
`compileKotlinLinuxX64` clean. Not dev-reviewed, not deployed, not live-verified against production (the
running backend still has the old swallow-and-overcount behavior until redeployed). No new test coverage
added — `JellyfinClient` is a concrete class with no test double in this codebase, so the fix is verified
by tracing the cancellation/return-value path by hand rather than by a unit test; flagged as a gap
consistent with how prior phases in this same file (207, 209) recorded the same limitation rather than
building new test infrastructure inside a bug-fix pass.

**Build notes:** implemented exactly as specified — `JellyfinClient.warmSubtitleExtraction` now returns
`Boolean` and explicitly rethrows `CancellationException` before its generic catch (FR-210-1);
`PipelineStepOps.warmedCountOf` counts only confirmed-true returns and gained an `onStreamWarmed`
callback, invoked per-success rather than once at the end (FR-210-2/FR-210-4); `PipelineEngine`'s
`prewarm_subtitles` call now passes both `onStreamWarmed = { warmed.incrementAndGet() }` and an
`onItemFailure` that credits `attempted`/`failed` for an item PipelineStepPool's own deadline abandons
(FR-210-3).

## The finding

### Where the 600000ms comes from

`600000` is `PipelineStepPool.DEFAULT_ITEM_DEADLINE_MS` (`10 * 60_000L`, `PipelineStepPool.kt:42`) — the
per-item ceiling `runPipelineStepPool` wraps every item in via `withTimeout(itemDeadlineMs)`
(`PipelineStepPool.kt:166`). It is a Phase 182 safety net, deliberately uniform across every step routed
through this pool, sized for "I/O-bound work expected to complete in seconds." `prewarm_subtitles` is
routed through it. Its own justifying spec (Phase 179, citing R183) measured a **4m37s** cold ffmpeg
extraction on one large file, and Phase 207's own live audit measured **21.16s** for an ordinary `subrip`
track in an ordinary movie. `PipelineStepOps.warmedCountOf` (`PipelineStepOps.kt:93-101`) calls
`warmSubtitleExtraction` **sequentially**, one full round trip per text-subtitle stream, and for a
`TV_SHOW` item this is summed across **every episode's** streams under one shared per-item deadline
(Phase 207 §FR-207-2's one-call-per-series shape). An item with several slow-to-extract streams, or a
series with many episodes, exhausts the 600s budget by simple arithmetic — this is expected to keep
happening more often as more of the library gets its first-ever warm pass.

That part — the deadline firing — is not itself the bug; 182's per-item ceiling existing at all is
correct. The bug is what happens next.

### `runCatching` swallows the cancellation, so the deadline never actually stops anything

`warmSubtitleExtraction` (`JellyfinClient.kt:938-942`):

```kotlin
suspend fun warmSubtitleExtraction(baseUrl: String, token: String, jellyfinId: String, streamIndex: Int) {
    val url = ...
    runCatching { httpGet(url) }
        .onFailure { Logger.warn("Jellyfin subtitle pre-warm failed (item=$jellyfinId index=$streamIndex): ${it.message}") }
}
```

`kotlin.runCatching` catches `Throwable`, which includes `CancellationException` — the exact anti-pattern
`PipelineStepPool.kt:74-82` already documents fixing at its own layer (Phase 182, FR-182-5): "a worker
whose Job was genuinely cancelled... silently swallowed that cancellation, logged it as an ordinary
per-item failure, and carried on." That fix was applied where `runPipelineStepPool` calls `perItem`
(`PipelineStepPool.kt:172`, which correctly re-throws `CancellationException`). It was never applied here,
one layer further in, and this is the first step whose real workload is slow enough to ever hit the outer
deadline and expose it.

What actually happens when the 600s deadline fires mid-loop:

1. The stream that was genuinely in flight gets a real `TimeoutCancellationException` — message
   `"Timed out waiting for 600000 ms"`, the standard `kotlinx.coroutines` format for exactly this
   scenario. `runCatching` catches it, logs it as an ordinary per-stream WARN, and returns
   `Result.failure` — **it does not rethrow**.
2. Because the coroutine was never actually torn down (the cancellation was absorbed, not propagated),
   the `for` loop in `warmedCountOf` keeps running. Every *remaining* iteration's `httpGet` call hits its
   own suspension point, and a coroutine whose Job is already Cancelled throws immediately on the next
   suspend — no real network call, no real wait. Each of those is *also* caught by `runCatching` and
   logged with the *same* `"Timed out waiting for 600000 ms"` message, which is why the log shows a burst
   of identically-timestamped lines for consecutive stream indices rather than one real timeout followed
   by silence.
3. The loop reaches its natural end (every `textSubs` entry visited, however fast), `warmedCountOf`
   **returns normally** with `textSubs.size` — the *attempted* count, not a success count (see next
   section) — and `prewarmSubtitles` returns `PrewarmOutcome.Warmed(...)` as if nothing had gone wrong.
4. No exception ever reaches `runPipelineStepPool`'s own `withTimeout` wrapper. Its
   `catch (e: TimeoutCancellationException)` (`PipelineStepPool.kt:169`, the branch that correctly logs
   "abandoning this item" and calls `onItemFailure`) never fires. The 600s deadline that was supposed to
   bound this item's worker-slot occupancy did not bound anything — the item ran to normal completion
   anyway, just very fast for everything after the real timeout, and reported success throughout.

### The count was already fake independent of cancellation

Separate from the cancellation-swallowing bug, `warmedCountOf` never checks whether any individual
`warmSubtitleExtraction` call succeeded — it can't, because that function returns `Unit`:

```kotlin
for (s in textSubs) jellyfinClient.warmSubtitleExtraction(base, token, jellyfinId, s.index)
return textSubs.size
```

`PrewarmOutcome.Warmed(count)` is documented (`PipelineStepOps.kt:58-67`, Phase 207) as existing
specifically to distinguish "genuinely warmed" from "nothing needed warming" from "could not tell" — the
lesson Phase 207 drew from the original 285-instant-400s bug. But `Warmed(count)` today means "this many
streams were *attempted*," not "this many were confirmed warmed." Even with the cancellation-swallowing
fixed, an ordinary HTTP error on one stream (Jellyfin briefly unavailable, a malformed stream, whatever)
is counted as a success today. `PipelineEngine`'s run-summary line —
`"prewarm_subtitles: N subtitle stream(s) warmed"` — is reporting an attempt count with a success label on
it, for every item, not only the ones that hit the deadline. Phase 207's own FR-207-5 flagged this exact
risk and left it as an open item: *"the implementation... would report success on the strength of a
count — which is precisely how the original bug survived."*

### Net effect

For any item whose subtitle-stream count makes the step legitimately expensive — increasingly common as
the one-time warm pass works through a library with libraries like this one recording "32 unnamed tracks
in one file" as an observed worst case (R195 spec) — Phase 179/207's entire purpose (avoid a real client
losing the extraction race, R183's motivating 4m37s measurement) is silently defeated for the streams that
never got a real request, while every layer of logging and the run summary both say the opposite.

## Requirements

**FR-210-1 — a genuine job cancellation must propagate, not log-and-continue.** `warmSubtitleExtraction`
must not let `runCatching`/its `onFailure` branch absorb `CancellationException`
(including `TimeoutCancellationException`): catch it explicitly and rethrow, or scope the try/catch to
exclude it, matching the pattern `PipelineStepPool.kt:172` already established for exactly this failure
mode. This is what lets the outer per-item deadline (Phase 182) actually abandon a slow item instead of
racing through its remaining iterations and reporting success.

**FR-210-2 — `Warmed` must count confirmed successes, not attempts.** `warmSubtitleExtraction` returns
whether its request actually succeeded; `warmedCountOf` sums only the calls that returned true. A stream
whose extraction request failed for any non-cancellation reason (HTTP error, malformed response) is not
counted, matching FR-207-3's own stated principle one layer deeper than where 207 applied it.

**FR-210-3 — an item abandoned by its own deadline must not vanish from the run summary.** Today
`runPipelineStepPool`'s call for `prewarm_subtitles` (`PipelineEngine.kt:351-366`) passes no
`onItemFailure`, so it silently defaults to a no-op — once FR-210-1 lets a genuine per-item timeout
propagate as intended, that item would disappear from `attempted`/`warmed`/`failed` entirely rather than
counting against `failed`, which would make the existing "every lookup failed" WARN
(`PipelineEngine.kt:371-372`) under-report exactly the runs where the deadline is actually biting. Wire an
`onItemFailure` that increments `attempted`/`failed` for this step, so an item-level deadline abandonment
is visible in the same summary line Phase 207 built for lookup failures.

**FR-210-4 — streams warmed before an item's own timeout still count.** A `TV_SHOW` item that times out
partway through its episode list may have genuinely warmed several streams already (the Jellyfin-side
cache write happened; the HTTP call to trigger it succeeded) before the deadline aborted the rest. Losing
that count entirely to FR-210-3's item-level failure bucket would under-report real, useful work and
create a new discouragement to raise the deadline later (an operator watching the summary would see "0
warmed" for an item where most streams actually succeeded). Thread a counter (or callback) into
`warmedCountOf` that credits `warmed` as each individual stream succeeds, rather than only at the end of a
normally-returning loop — so a mid-item cancellation still keeps whatever real progress was made.

## Out of scope

- **Raising or restructuring the 600s per-item deadline itself, or parallelizing per-stream extraction
  within an item.** Real design questions (how much concurrent ffmpeg extraction load is safe to put on
  Jellyfin at once, whether a `TV_SHOW`'s full episode list should share one budget at all) that deserve
  their own investigation with real numbers, not a guess folded into a correctness fix. This phase makes
  the step honest about what it actually accomplished within whatever budget it's given; it does not
  change the budget. Noted as the natural follow-up.
- **`PipelineStepPool`'s own cancellation handling.** Already correct (Phase 182); this phase brings
  `warmSubtitleExtraction` in line with the pattern it already established, not the other way around.
- **Any other `runCatching`-over-a-suspend-call site in `JellyfinClient.kt`.** Worth a dedicated audit —
  this phase fixes the one proven live to matter today.

## Open questions

1. Whether a `TV_SHOW`'s full episode list sharing one 600s budget is sustainable long-term as episode
   counts grow, or whether per-episode (rather than per-series) budgeting is needed — left to the
   follow-up flagged above rather than guessed here.
2. Whether `warmSubtitleExtraction`'s underlying `httpGet` should carry its own shorter per-request
   timeout (distinct from the item-level 600s) so one genuinely wedged stream can't consume the entire
   item's budget by itself, leaving zero time for the rest. Not investigated; the fix in this phase makes
   the existing behavior honest, it does not change how the budget gets spent within an item.
