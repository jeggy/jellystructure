# Phase R219 — Continue Watching: one merged list, one order, many views

> Continue Watching has produced three separate user-visible bugs in two days (R186's fetch window,
> then R217's starvation, then R217's own precedence and ordering fixes). Each fix was correct about the
> symptom it chased and wrong about the shape of the problem, because each one picked a *proxy* for
> "what should I watch next" — stream position, then stream identity, then a partial timestamp — instead
> of modelling the question directly. This phase replaces the accumulated patches with one explicit
> model: **jellystructure owns a single canonical Continue Watching list**, complete and correctly
> ordered, and every surface is a *view* over it.

**Status:** Planned — design-authored 2026-08-30, jointly with the owner. Supersedes **FR-R217-1**,
**FR-R217-2** and **FR-R217-3**; R217's root-cause analysis and its live findings remain the historical
record of why this phase exists. Not yet built.

---

# ⚠ THE RULE THAT KEEPS GETTING BROKEN

> **jellystructure does the merging, sorting, filtering and capping. Jellyfin does none of it.**
>
> **Therefore: every Jellyfin fetch must be COMPLETE. `Limit` is never a cap, a filter, or a shortcut.**

Jellyfin splits this feature across **two unrelated endpoints that know nothing about each other**
(`/Users/{id}/Items/Resume` and `/Shows/NextUp`). To Jellyfin they are not one list, so Jellyfin
**cannot** sort across them, filter across them, rank across them, or cap them. Only we can, and only
after we have both halves in full.

That makes any Jellyfin-side `Limit` a **pre-merge truncation**: it throws away candidates *before
their rank in the merged list is knowable*. A candidate cut at that stage is not "low priority" — it is
invisible, and no amount of correct sorting afterwards can bring it back.

**This exact mistake has now caused four bugs:**

| # | What was truncated / cut early | Symptom |
|---|---|---|
| 1 | R186 — `getResumeItems`/`getNextUp` capped at 20 | channel rows starved of entries |
| 2 | R217 — cap applied *after concatenating*, before merging | next-up entries could never survive; "Three and a Half Uncles" absent despite being next-up #1 |
| 3 | R217 fix — per-series dedup decided by *stream position* | Jellyfin's wrong S1E1 suggestion beat real S5E1 progress ("Vi kvæles i nips") |
| 4 | R217 fix — `CONTINUE_RECENCY_POOL = 500` bounded the history lookup | titles whose last finish fell outside the window silently lost their sort key |

Every one was the same error in a new place: **deciding something before all the inputs were in.**

**Consequences that are non-negotiable in this phase:**

- Page every Jellyfin input to completion, using `TotalRecordCount` as the guard (both endpoints report
  it independently of `Limit`, and both support `StartIndex` — verified live).
- A constant that merely "looks generous" (200, 500, …) is **not** a guard. If it can be exceeded, it
  will be, and the failure is silent.
- The **only** legitimate cap is the final per-view one (Home/channel = 20), applied **last**, after
  membership, conflict resolution, ordering and filtering are all complete.
- The one bounded fetch in this phase (FR-R219-3's recent-touch window) is bounded **by time**, not by
  a row count, and stops when it crosses the window edge — it can never cut a ranked candidate.
- Reducing payload is fine and encouraged (`EnableImages=false`, minimal `Fields`); reducing
  *completeness* is not. Those are different things.

---

## Why the old shape kept failing

Jellyfin does not have a "continue watching" concept. It has **two unrelated endpoints**:

| Jellyfin endpoint | What it means | Has a timestamp? |
|---|---|---|
| `/Users/{id}/Items/Resume` | an episode/movie with a saved position | yes — `UserData.LastPlayedDate` |
| `/Shows/NextUp` | a series whose most recent episode was *finished* | **no** — the episode it points at is unwatched |

Everything hard about this feature follows from that split:

1. **The merge is ours** — with all the consequences set out in *The rule that keeps getting broken*
   above. This is the source of the whole problem and of four bugs so far.
2. **Only one of the two sides carries a time.** A next-up entry has no timestamp at all, so the two
   lists cannot be ordered against each other without jellystructure supplying the missing one.
3. **The two sides can disagree about the same series**, in both directions — see the conflict rule.

---

## The model

### 1. The canonical list

One list per **(Jellyfin user, device visibility scope)**, built from complete inputs, containing
**one entry per title** (series or movie), each carrying:

- the **card** to display (resume progress, or the next-up episode label),
- a **`lastActivityAt`** instant — the single value that orders the whole list.

Every surface — Home, any channel row, "See all" — is this same list with a filter and/or a cap
applied. No surface re-derives membership or order.

### 2. Membership — what is in the list at all

A title is in the list when it is **genuinely started** *and* **has something left to watch**.

**Genuinely started** — any one of:
- **(a)** an episode/movie with a real resume position (`Resume` endpoint), or
- **(b)** at least one finished episode (`Filters=IsPlayed`), or
- **(c)** any episode played within the **last 7 days**, even with no saved position and no finish
  (`PlayCount > 0`). This is the "pressed play, didn't get a position saved" case — see the note below.

**Something left to watch** — a resume position, or a next-up episode. A series watched to completion
with nothing pending is *not* in the list.

> **On (c).** Measured on the live library, this is the only category the strict rule would have
> excluded: 10 titles, every one with `PlayCount ≥ 1`, `Played=false`, position 0 — i.e. genuinely
> pressed play, but Jellyfin recorded no position. Nine were 6 weeks to 6 months old (Pocketmon: 1 of
> 1128 episodes; Andersson and Mittens: February). One — *The Crash of Flight 88* — was **3 days
> old**, and dropping something the household started this week is clearly wrong. The 7-day window
> keeps that and sheds the stale tail. A title in this category **silently leaves the list on day 8**;
> that is accepted, and is the price of not accumulating one-off samples forever.
>
> A **never-touched** title is excluded — but note this category does not actually occur here: all 73
> current next-up entries have some play history. Jellyfin's `/Shows/NextUp` appears to only suggest
> series with at least one touched episode. The rule is stated anyway so behaviour is defined if that
> ever changes.

### 3. The conflict rule — which card, when a title is in both lists

**The most recent activity wins.** Compare the title's newest resume position against its newest
finished episode:

- resume is newer, or there is no next-up → show the **resume** card (progress bar, resumed episode).
- the finish is newer and a next-up exists → show the **next-up** card (`S2E5 · Title`).

This single rule fixes both directions, which is why it replaces R217's "resume always wins":

| Live case | Resume | Last finish | Correct card | Old rule |
|---|---|---|---|---|
| **Vi kvæles i nips** | S5E1, Jul 7 | *(none)* | resume S5E1 | ✓ resume |
| **Fumi** | S1E26, Jul 9 | Aug 1 | **next-up** | ✗ showed 3-week-stale S1E26 |

`/Shows/NextUp` is not trustworthy on its own — for Vi kvæles i nips it suggested **S1E1 of a series
with real progress at S5E1** — which is why the rule is anchored on timestamps, never on which endpoint
an entry came from.

### 4. The order

Sort the whole merged list by `lastActivityAt`, descending. That value is:

- **resume-card entries** → the resume point's `LastPlayedDate`;
- **next-up-card entries** → the series' most recent **finished** episode's `LastPlayedDate`.

Both answer the same question — *when did I last watch this title* — so they are directly comparable.
Ties keep a stable order. An unparseable/missing date sorts last, never first (R198).

### 5. The views

| Surface | Filter | Cap |
|---|---|---|
| Home row | device visibility only | **20** |
| Channel row (`scope = channel`) | visibility **+ channel membership** | **20**, applied *after* the filter |
| Channel row (`scope = library` / inherit) | visibility only — identical to Home | 20 |
| "See all" page | same as its originating row | **uncapped** |

Filtering always precedes capping, so a channel row shows the 20 most recent titles *of that channel*,
not "whatever survived a library-wide cut and happens to be in this channel."

---

## Requirements

### FR-R219-1 — One canonical list, built once, reused by every view

`HomeFeedService` gains a single function that returns the canonical list for a
`(user, visibility scope)` pair. Every Continue row in a feed response — Home's, a channel's, the
See-all page — is produced by filtering and capping **that one result**, not by re-deriving it.

- It is computed at most once per feed response, and cached under the existing R86-A SWR cache
  (`FEED_TTL_MS`, 5 minutes), keyed by user + visibility scope — **not** by channel, since channel
  membership is a filter over the same list.
- Existing invalidation is unchanged: a reported playback stop invalidates it immediately
  (`invalidatePlaystate`), so backing out of the player shows a corrected row at once.
- No persisted/materialised copy. Jellyfin remains the source of truth for playstate; a stored mirror
  would add a sync surface that can silently disagree with it, which is the failure mode
  [[R185]] already documented for played/position desync.

### FR-R219-2 — Every Jellyfin input must be complete, and truncation must be impossible-by-construction

Because the merge, sort, filter and cap all happen on our side, **no Jellyfin-side `Limit` may act as
an effective cap**. Each input is fetched to completion:

- `Resume` and `NextUp` both report `TotalRecordCount` independently of `Limit`, and both support
  `StartIndex` (verified live: `Limit=50` + `StartIndex=50` returned the exact remainders, 50+46=96 and
  50+23=73). Page until the collected count reaches `TotalRecordCount`.
- The finished-episode history (`Filters=IsPlayed`) is likewise paged to completion — it is needed in
  full, because a title whose only finish is old is still a valid (merely low-ranked) entry, and
  truncating it would wrongly *exclude* that title rather than merely misplace it.
- A fixed constant that "looks generous" is not acceptable as the only guard. If a fetch is ever
  observed short of `TotalRecordCount`, that is a logged warning, not a silent truncation.

**Payload control.** All of these fetches set **`EnableImages=false`**, which halves the response
(measured: full finished history 1.89 MB → **0.97 MB** for 1104 episodes) with no loss — the artwork
comes from jellystructure's own catalog, never from these responses. Requests carry only the fields
actually used (`SeriesId` + `UserData`).

### FR-R219-3 — The recent-touch window is bounded by time, not by a row count

Membership rule (c) needs episodes that are touched but neither finished nor resumable, which no
Jellyfin filter can express (verified against the live OpenAPI: `minDateLastSaved` exists,
**`minDateLastPlayed` does not**). It is therefore a `SortBy=DatePlayed&SortOrder=Descending` query with
**no** played filter, read in pages and **stopped as soon as an item older than the window is seen** —
so its cost is bounded by the window, not by a guessed limit. Measured: 183 touched episodes in the
last 7 days, comfortably one page.

### FR-R219-4 — Membership, conflict and ordering exactly as modelled above

Implement §2, §3 and §4 as specified. In particular:

- one entry per title, keyed by `seriesId ?: id` (unchanged);
- for a title with several in-progress episodes, the **most recent** one is the resume candidate
  (this already works, and is why Tellytots correctly shows S1E5/88% and not its stale S1E3);
- **R185 preserved** — an item flagged `Played` is never resurrected as in-progress, whatever its
  position says;
- **R199 preserved** — `resolvedEpisodeNumbers` still supplies season/episode when Jellyfin's own
  numbering fails.

### FR-R219-5 — Views: filter, then cap

Implement §5. The cap is a single constant per surface; Home and channel rows use **20**
(down from 30 — with a genuinely time-sorted list the row's head is now always the most recent, so a
shorter row costs nothing and scans faster on a remote), and See-all stays uncapped.
`Row.seedTotalCount` continues to report the **pre-cap** match count so the "→ See all" tile is honest.

---

## Invariants

- **Merge before you cut.** Filtering and capping happen only on the fully merged, fully sorted list.
  No stage may drop a candidate before its rank is known — this is the invariant all three prior bugs
  violated in different places.
- **Timestamps decide, never provenance.** Which endpoint an entry arrived from must never determine
  its card or its position. Only `lastActivityAt` does.
- **Jellyfin owns playstate; we own the merge.** We never write playstate to make the row easier to
  compute, and never keep a persisted copy that could disagree with it.
- **The row is atomic** (R102 / no-flicker rule): on a Jellyfin timeout the row is omitted entirely
  rather than shipped half-built, and the SWR cache serves the previous good value.
- **Frontend renders server-pushed state only** (constitution) — this is entirely a backend change; no
  client change is required or permitted by this phase.

---

## Edge cases, stated explicitly

| Case | Behaviour |
|---|---|
| Half-watched **movie** | In the list, resume card. Movies have no next-up; that is fine. |
| **Finished** movie | Excluded (R185), even if a stale position lingers. |
| Series watched to completion, nothing pending | Excluded — "something left to watch" fails. 20 such titles today. |
| Series with in-progress **and** next-up | Conflict rule: most recent activity wins. |
| Next-up suggests **S1E1** while real progress is deeper | Resume wins *if newer* — the Vi kvæles i nips case. |
| Stale resume, newer finish | Next-up wins — the Fumi case. |
| Several in-progress episodes in one series | Most recent one becomes the resume candidate. |
| Played once ≤7 days ago, no position, no finish | Included (rule c). |
| Played once >7 days ago, nothing since | Excluded; leaves the list on day 8. |
| Never touched at all | Excluded. Does not currently occur — see §2's note. |
| Title absent from jellystructure's catalog | Excluded. Ravilo only shows what jellystructure indexes; a play in another Jellyfin library does not conjure a card. |
| Title not visible to this device (library allow-list, kids policy) | Excluded via the existing `visibleTo` scope — the canonical list is per visibility scope for exactly this reason. |
| Empty result | Row omitted entirely, not rendered empty. |
| Jellyfin slow/unreachable | Row omitted (R102); previous cached value continues to serve. |
| Rewatching a completed series | Re-enters naturally: starting S1E1 creates a resume position. |
| `LastPlayedDate` missing/unparseable | Sorts last, never first (R198). |

---

## Out of scope

- **Manual "remove from Continue Watching".** Deliberately deferred (owner's call, 2026-08-30) to keep
  this phase to the list model. The membership rules above remove most of what would motivate it. If
  it is built later it becomes a *filter stage over the canonical list*, which this design already
  leaves room for — no re-architecture needed.
- **Persisting the merged list.** See FR-R219-1's rationale.
- **Any client/UI change.** Card layout, badges and the "→ See all" tile are unchanged.
- **A staleness cutoff for started titles.** Rejected: sorting + the cap already sink old entries, and
  See-all is meant to be complete. (The 7-day window in rule (c) is a *membership* test for the
  weakest evidence class, not a general expiry.)
- **Live TV**, which has its own row and lifecycle (R177).

---

## Source references

- `tv/HomeFeedService.kt` — `buildContinueRow()` (to be replaced by the canonical-list function),
  its three call sites (Home row, channel row, `continueWatchingAll()` for See-all), `ROW_ITEM_LIMIT`,
  `CONTINUE_TIMEOUT_MS`, `FEED_TTL_MS`, and R217's current `CONTINUE_RECENCY_POOL` (removed by
  FR-R219-2's completeness requirement).
- `auth/JellyfinClient.kt` — `getResumeItems` / `getNextUp` (both currently `Limit = 200`, to be paged),
  `getRecentlyPlayed` (the finished-history source; needs `EnableImages=false` and paging).
- Related: **R217** (superseded requirements; its analysis stands), **R186** (fetch window — the same
  truncation class), **R185** (played/position desync, preserved), **R198** (never trust upstream sort),
  **R199** (episode-number fallback, preserved), **R102** (atomic row on timeout), **R86-A** (SWR cache).

## Measurements (live, jogvan profile, 2026-08-30)

Recorded so future changes can be judged against real numbers rather than re-guessed:

| Quantity | Value |
|---|---|
| Resume entries (`TotalRecordCount`) | 96 → 65 distinct titles |
| Next-up entries | 73 |
| Finished episodes, all time | 1104 (0.97 MB with `EnableImages=false`; 1.89 MB without) |
| Touched episodes, all time | 1202 |
| Touched within 7 days | 183 |
| **Resulting merged list** | **≈89 titles** |
| Excluded: watched to completion | 20 |
| Excluded: played once, >7 days, no position | 9 |

## Open questions

1. **Home cap of 20** is chosen from the owner's "~15–20"; it is a single constant and worth a look on
   a real TV once this ships — the right number is however many fit ~3 screens of remote scrolling.
2. **Deep-history caching.** The finished-episode history only ever grows at the head, so it could be
   cached with a much longer TTL than the 5-minute feed and merged with a short recent window, cutting
   the steady-state cost well below 0.97 MB. Deliberately not designed in yet — correctness first,
   and the current cost is acceptable at a 5-minute refresh.
3. **Does `/Shows/NextUp` ever include a genuinely never-touched series?** It does not today (all 73
   have history), so rule (c)'s "never touched" branch is currently unreachable. Worth re-checking if
   Jellyfin's next-up behaviour changes across a version upgrade.
