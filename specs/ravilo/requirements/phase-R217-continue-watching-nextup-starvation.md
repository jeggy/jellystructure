# Phase R217 — Continue Watching must not let a large resume backlog starve next-up entries

> User report, live, 2026-08-28: watched Offboarding S02E05 to completion the evening before, but the
> series is nowhere in Continue Watching on Home — not buried, not late, **absent** — despite Jellyfin's
> own `/Shows/NextUp` correctly listing it at position 4 (right behind other things watched the same
> evening). Reproduced directly against the real backend using stue TV's own live device token (no
> restart, no synthetic test): of the 30 cards `GET /api/tv/home` actually returned for that device, **all
> 30 came from the resume list and zero came from next-up** — every slot was full before next-up ever got
> a turn.

**Status:** ✓ Done (2026-08-29). Design-authored and deferred 2026-08-28; user re-reported the same
symptom live the next day ("Two and a half men" missing from Continue Watching) and asked for the fix.
Re-verified the exact same root cause against live data before writing code, then confirmed live again
after restarting the dev backend — see Build notes.

## Build notes (2026-08-29)

- **Re-confirmed live, one day later, worse than the original report:** resume backlog now 92 items
  (was 55), next-up now 73 (was ~62 raw). "Three and a Half Uncles" sits at **next-up position 1** — the
  most-recently-actionable next-up entry there is — and was still fully absent from the live (unpatched)
  `/api/tv/home` Continue row, whose 30 slots were 100% resume, 0% next-up. Confirms the starvation gets
  worse as the backlog grows, exactly as the spec's own invariant warned.
- **Implemented exactly as FR-R217-1 specified** — no design changes needed. `buildContinueRow()` now
  builds `resumeCandidates`/`nextUpCandidates` as two separate lists (identical per-item logic to
  before: R185's played-skip, R199's episode-number fallback, the same card-building calls), then merges
  them with a strict 1:1 round-robin (resume, next-up, repeating; the longer stream continues alone once
  the other is exhausted) before `cards.take(limit)`. `seen`-based dedup preserved, now checked at
  interleave-placement time rather than per-stream-then-per-stream time — equivalent in every case that
  matters, since a series is essentially never both resumable and next-up simultaneously in Jellyfin's
  own model.
- Compiles clean (`compileKotlinLinuxX64`). No new unit test — `buildContinueRow` is a private suspend
  fn with several service dependencies (`mediaStore`/`jellyfinClient`/`configStore`) and no existing test
  harness for `HomeFeedService`; correctness verified by re-deriving the exact live scenario above by
  hand (Three and a Half Uncles at next-up position 1 → merged position 2, comfortably inside any reasonable
  cap) rather than a mocked unit test. Worth adding a real test harness if this class gets touched again.
- **Live-verified after restart (2026-08-29, before FR-R217-3):** re-queried `/api/tv/home` as the stue TV device against
  the restarted dev backend — Three and a Half Uncles now sits at **merged position 2** (Offboarding, Three and a Half Uncles, Ruffy, …), exactly matching the by-hand prediction above. Several other titles absent from
  the pre-fix row also now appear (Pratarna, Fristelsens Ø Danmark, The Crash of Flight 88,
  Klettarnir, Mintys Træhus, Beacon Watch, Muldvarpen, Kulsort, Danmarks klogeste, Mark og mage, La Curva) — all next-up entries the old concatenate-then-cap order was silently dropping.

- **A second bug found the same day, by the user, from the live fix above** — the interleave itself
  introduced a NEW failure mode the original concatenate-then-cap order had accidentally avoided.
  Reported live: on the DanskTV channel page, "Vi kvæles i nips" showed as the very first Continue
  Watching card, which the user correctly flagged as wrong ("very long ago this was played"). Root
  cause, confirmed against live Jellyfin data: the household has a genuine RESUME entry for this series
  (S5E1, `PlaybackPositionTicks` > 0, `Played: false`, last played 2026-07-07 — exactly the "long ago"
  the user remembered) sitting at **resume position 63**. But Jellyfin's own `/Shows/NextUp` *also*
  suggests this series — wrongly, suggesting **S1E1** (`PlayCount: 0`, never watched at all) — at
  **next-up position 46**. A plain interleave-then-cap reaches next-up's round 46 long before resume's
  round 63 ever places the correct entry, so stream *position* let the wrong next-up suggestion win the
  per-series dedup over the genuinely-correct resume entry — worse than before this phase, since the old
  code's strict resume-then-next-up concatenation had always given resume unconditional priority
  regardless of its own position within the resume list.
  - **User's own fix direction, implemented exactly:** "load everything and then merge together
    everything and then only after that we can do filters on it or add a cap." Rebuilt as three
    explicit phases: (1) build the full resume-candidate list and the full next-up-candidate list, each
    completely, nothing capped; (2) **merge** — drop any next-up candidate whose series already has a
    resume candidate, *before* interleaving, so stream position can never again decide the winner, only
    whether genuine in-progress state exists; (3) interleave the now non-overlapping streams and cap.
  - **Live-verified after a second restart:** DanskTV's Continue row now shows "Vi kvæles i nips" with
    `season_number: 5, episode_number: 1, progress_pct: 0.36` (the real resume state) instead of the
    wrong S1E1 suggestion. It still sits at position 0 within the DanskTV-filtered subset specifically —
    confirmed this is correct, not a residual bug: it is genuinely the most-recently-watched *Danish*
    title among this channel's resume candidates, even though it ranks far lower (outside the top 30)
    in the *global* Home row once compared against the household's non-Danish viewing. Channel-scoped
    rows reuse the same globally-computed resume-recency order, filtered to channel membership — a
    correct, if initially surprising, relative ordering.

## Root cause

`buildContinueRow()` (`tv/HomeFeedService.kt:497-559`, the single function backing Home's Continue row,
every channel's Continue row, and the uncapped "→ See all" page) does this:

1. Fetch every resumable (in-progress) item, sorted by last-played date (`resumeItemsSorted`).
2. Fetch every next-up item — a series whose most recent episode was *finished*, now waiting on the next
   one (Offboarding's exact situation: finishing S02E05 drops it off "resume" and moves it to "next-up" for
   S02E06 "Attila").
3. Append the whole of list 1 to `cards`, then append the whole of list 2, **then** cap the combined list
   at `ROW_ITEM_LIMIT = 30` (`:41`).

Step 3 is the bug: capping happens *after* concatenation, so every next-up entry sits strictly behind
every resume entry in the combined list regardless of true recency. Once the resume list alone has ≥30
distinct titles, no next-up entry can ever survive the cap — not a rare edge case for this household:

- Live count (jogvan's profile, 2026-08-28, via the exact request `buildContinueRow`/`getResumeItems`
  makes): **55 distinct in-progress series/movies** currently resumable (`TotalRecordCount: 62` raw items,
  55 after the same `SeriesId ?? Id` dedup the code itself uses) — mostly kids' shows re-watched in short
  bursts (Ruffy, Smábørn, Tellytots, Peter Pote…) sitting alongside adult in-progress titles.
- `getResumeItems`/`getNextUp` themselves are healthy and NOT the bug — both already fetch a 200-item
  candidate pool (R186) and Jellyfin returns Offboarding correctly (next-up position 4, `SeriesId` matches
  jellystructure's own stored `jellyfinId` for the title exactly — no id-drift, no scan/match bug).
- This is a different failure mode from **R186** (fetch limit too small — already fixed and confirmed
  still working) and **R185**/**R198** (data desync / unreliable upstream sort — also unaffected here).
  This is the first time the two already-correctly-fetched, already-correctly-sorted lists are merged
  wrong.

Both input lists are independently already ordered by a real recency signal: `resumeItemsSorted` explicitly
(`SortBy=DatePlayed`, `HomeFeedService.kt:530`), and `nextUpItems` implicitly (Jellyfin's own `/Shows/
NextUp` ordering — empirically confirmed recency-biased in this same live test: Offboarding's neighbours in
the raw response were things watched the same evening, in the same relative order Jellyfin's dashboard
history shows). The bug is purely in how the two pre-sorted streams are combined before the display cap.

## Requirements

### FR-R217-1 — Merge, don't concatenate, before capping

Replace the concatenate-then-cap combination in `buildContinueRow` with an **interleaving merge** of the
two already-sorted candidate streams (round-robin: alternate one from `resumeItemsSorted`, one from
`nextUpItems`, continuing with whichever stream still has entries once the other is exhausted; existing
per-item dedup by `seriesId ?: id` stays exactly as it is), and apply `ROW_ITEM_LIMIT`/`limit` to the
**merged** result, not to either input stream separately.

- With today's numbers this alone is enough: Offboarding sits at next-up position 4, so a strict 1:1
  alternation places it at merged position ≤8 — comfortably inside a 30-cap regardless of how large the
  resume backlog grows. Growth-proof: adding more resume items no longer pushes next-up entries out at
  all, only how far down the *next-up* portion of the merge they land.
- Applies uniformly everywhere `buildContinueRow` is called — Home's own row (`:340`), every custom
  channel's Continue row (`:315`), and the uncapped "→ See all" page (`:489`, where nothing was ever
  dropped, but ordering was equally wrong cosmetically — this fix corrects that too, for free, from the
  same code path).

### FR-R217-3 — Sort the merged row by actual last-watched time (added 2026-08-29)

**Supersedes FR-R217-1's round-robin.** The 1:1 alternation fixed starvation but produced an order that
is not chronological: it alternates streams regardless of recency, so a three-week-old next-up entry
lands at position 2 above something watched yesterday. User-reported after the interleave shipped.

Every candidate gets a real **last-watched instant** and the merged list is sorted by it, descending,
before `limit` is applied:

- **Resume candidates** already carry one — `UserData.LastPlayedDate` on the in-progress episode.
- **Next-up candidates carry none of their own** (the episode they point at is by definition unwatched),
  so they take "when did I last *finish* an episode of this series", from one additional
  `getRecentlyPlayed` call (`Filters=IsPlayed&SortBy=DatePlayed`, first hit per `SeriesId` wins).
  Issued as a **third parallel** call inside the existing `CONTINUE_TIMEOUT_MS` block, so it adds no
  wall-clock time and rides the same R86-A SWR cache.
- **Tail fallback — carry-forward.** A series whose last finish predates the fetch depth, or that was
  only ever *sampled* (an episode opened then abandoned: `Played=false` with a zero position, so it
  appears in neither the played list nor the resume list), has no timestamp anywhere. Rather than
  collapsing all of those to "equally ancient" and shuffling them arbitrarily, each inherits the
  previous next-up entry's instant, so Jellyfin's own next-up ordering decides their relative places.
  This is sound because that ordering is itself recency-biased — verified live: it matched real
  timestamps for 13 of 14 consecutive entries that had them (the one inversion is consistent with
  R198's standing finding that Jellyfin's sort is a request, not a guarantee).
- The sort is **stable**, so entries sharing an instant (notably a carry-forward run) keep their
  build order: resume first, then Jellyfin's next-up sequence.

**Depth is deliberately bounded** (`CONTINUE_RECENCY_POOL = 500`). Measured live on this household
(1185 finished plays): 400 covered the 13 most recent next-up series for ~650 KB, while fetching all
1185 still reached only 24 of 34 — the remainder being the sampled-and-abandoned case above, which no
played/resume query can see at any depth — for ~2 MB, a poor trade on a backend already sensitive to
large JSON decodes (see the perf-incident history). Past this depth, carry-forward takes over.

> This revises the original spec's Out-of-scope entry, which rejected real timestamps for next-up on the
> assumption they would cost "up to ~70 extra per-item Jellyfin round trips." That assumption was wrong:
> one bulk `DatePlayed`-sorted query returns them all at once. The *conclusion* it drew (don't pay
> per-item round trips) still stands; the premise did not.

### FR-R217-2 — Preserve every existing invariant this function already carries

The R185 already-watched skip (`play.userData?.played == true` → never resurrect a finished item as
in-progress), the R199 episode-number fallback (`resolvedEpisodeNumbers`), and the existing
`seen`-based dedup must all continue to apply exactly as today — this phase changes only the
*combination order* of the two streams, nothing about which items qualify or how a card is built.

## Invariants

- **No data is dropped that wasn't already being dropped by the cap itself.** A household with more
  combined resume+next-up candidates than `ROW_ITEM_LIMIT` will still not show everything — that's an
  intentional row-size limit, unchanged. What changes is *which* items lose the coin flip: by true
  relative recency instead of by which stream they happened to come from.
- **No new Jellyfin API calls.** The fix operates entirely on the two lists `buildContinueRow` already
  fetches; it does not fetch a per-candidate "true" last-watched timestamp (see Out of scope).
- **Frontend renders server-pushed state only** (constitution) — unaffected; this is a pure backend
  ordering fix, no client change.

## Out of scope

- **Fetching each next-up candidate's real last-watched-episode timestamp** for a byte-perfect
  chronological merge. Jellyfin doesn't expose it on `/Shows/NextUp`'s own response or on the series-level
  `UserData` (checked live — series `UserData` carries `PlayedPercentage`/`UnplayedItemCount`/`Played`,
  no `LastPlayedDate`), so this would mean up to ~70 extra per-item Jellyfin round trips on a code path
  that already has its own 30s timeout budget (`CONTINUE_TIMEOUT_MS`) for the *combined* resume+next-up
  fetch. The round-robin interleave gets the practical result (a recent next-up entry reliably survives
  the cap) without that cost.
- **Raising `ROW_ITEM_LIMIT`.** Doesn't fix the architectural issue (an even larger resume backlog would
  eventually reproduce the same starvation) and affects every row's layout/width, not just this bug.
- **A fixed reserved quota** ("at least N slots for next-up"). Considered and rejected in favour of the
  interleave: a quota is an arbitrary number that could itself starve a household with more next-up
  entries than the quota, whereas alternation scales with however many of each type actually exist.
- **Any change to `getResumeItems`/`getNextUp` themselves** — both are confirmed healthy (R186's fetch
  window is working; no id-drift; no desync). This phase touches only the merge step.

## Source references

- `tv/HomeFeedService.kt:497-559` — `buildContinueRow()`, the function this phase changes.
- `tv/HomeFeedService.kt:41` — `ROW_ITEM_LIMIT = 30`, the cap the merge order needs to respect fairly.
- `tv/HomeFeedService.kt:315,340,489` — the three call sites (Home, custom-channel, See-all) this fix
  covers uniformly.
- `auth/JellyfinClient.kt:429-445` (`getResumeItems`) and `:747-761` (`getNextUp`) — both confirmed
  healthy in this investigation; not touched by this phase.
- Related: **R185** (played/position desync — the skip this phase preserves unchanged), **R186** (fetch
  window — the candidate-pool size this phase's merge now has to handle fairly), **R198** (never trust
  upstream sort blindly — the same principle extended from "one list" to "combining two lists"), **R199**
  (episode-number fallback — preserved unchanged).

## Open questions

1. **Is strict 1:1 round-robin the right weighting**, or should resume (active, mid-episode engagement)
   get a mild edge over next-up (passively queued, not yet started) — e.g. 2 resume : 1 next-up? Untested
   against real usage; 1:1 is proposed as the simplest fix that already solves the reported case with
   large headroom (position 4 → merged position ≤8 of 30).
2. **Should the merge weight decay for a stale next-up entry** — e.g. a series finished months ago that
   the household never returned to shouldn't necessarily keep contesting fresh slots forever alongside a
   next-up entry from last night. Out of scope for the immediate fix; worth a follow-up if it turns out to
   matter in practice.

## Build notes — FR-R217-3 (chronological sort), 2026-08-29

Third live-reported issue in the same session, after the precedence fix shipped: *"it needs to be
sorted by time properly."* Correct — the round-robin put the right **items** in the row but in the
wrong **order**, alternating streams regardless of recency.

- Implemented exactly as FR-R217-3 above: real `LastPlayedDate` for resume, bulk per-series
  last-finished lookup for next-up, carry-forward for the tail, one stable descending sort, then cap.
- **Live-verified** against the real backend, both surfaces:
  - **Home:** predicted the exact expected order by hand from raw Jellyfin data first, then compared —
    the live row matched item-for-item (Offboarding 08-28 22:10 → Three and a Half Uncles 08-28 19:02 →
    Pratarna 08-28 06:40 → Temptation Island 08-27 22:20 → …). The only prediction/live differences
    were two titles absent from the device's own visible catalog, which `byJellyfinId` correctly filters
    — pre-existing, unrelated behaviour.
  - **DanskTV channel row** (where the ordering problem was originally reported): now leads with
    Fristelsens Ø Danmark (08-27) instead of Vi kvæles i nips, which has moved to position 10 —
    matching its genuine 2026-07-07 last-played date, and still correctly showing its real S5E1/36%
    resume state rather than the wrong S1E1 next-up suggestion.
- `CONTINUE_RECENCY_POOL = 500` chosen from measured coverage-vs-payload on live data; see the constant's
  own comment in `HomeFeedService.kt` and FR-R217-3's rationale.
