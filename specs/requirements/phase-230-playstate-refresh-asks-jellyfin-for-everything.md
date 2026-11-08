# Phase 230 — The playstate refresher asks Jellyfin for everything, every 20 seconds

> Second cause behind the household's 2026-09-17 report that *Continue Watching didn't work so well*
> (the first is **229**). The background refreshers time out at Jellyfin several times an hour with
> the outbound pool idle (`permit wait 0 ms`). The reason is our own load: since **211** the playstate
> refresher fetches user data for **every item and every episode** — 9 369 ids, 94 requests, 14 MB of
> JSON — **per user, every 20 s**, inside a 5 s budget that a quiet Jellyfin already uses 3.4 s of.

## Scope

Backend-only (`PlaystateCache.kt`, `HomeFeedService.kt`, `PlaybackService.kt`, `Main.kt`). No wire
change. Amends **205** FR-205-2 (the refresh cadence) and **211** (the id set) without undoing either.
Written from production measurements on 2026-09-17. The 2026-11-08 amendment (DEB-3955, below) adds
FR-230-7 to FR-230-9 and touches `TvRoutes.kt` too.

## Measured (production, 2026-09-17 ~15:45, no playback running)

| | |
|---|---|
| ids per user (`idsToRefresh`) | **9 369** = 537 titles + 8 832 episodes (before 211: 537) |
| requests per user per cycle | **94** (`HYDRATE_CHUNK` = 100, 4 concurrent) |
| one user's sweep, replayed with the same shape | **3.4 s**, 14.0 MB; per chunk median 136 ms, p95 261 ms |
| budget | `FETCH_TIMEOUT_MS` = **5 s**; cycle every **20 s**; 4 recently-seen users |
| sustained load on Jellyfin | ≈ **19 requests/s**, ≈ 2.8 MB/s of JSON parsed in our heap, forever |
| timeouts seen | 9 in 20 min (playstate + Continue), all `permit wait 0 ms` |

With 1.6 s of headroom, anything else Jellyfin does — a playback start, the Continue build's own
four fetches, the segments lane reading `/mnt/series` (sda at 72 % util at the time) — tips a sweep
over. A timed-out playstate sweep costs a stale ✓; a timed-out **Continue** build, which competes
with it, costs the row (and after a restart, when there is no previous list to stand on, the row is
simply absent: `2 never built` at 14:42 after the v1.20 restart).

The same full sweep also runs **inside the stop path** (`invalidatePlaystate` → `refreshOne`), so
229's rebuild and R248's `home_changed` push wait 3.4 s+ behind 94 requests to learn about one episode.

**Probed and rejected:** Jellyfin's `MinDateLastSavedForUser` would make this incremental. On
10.11.11 it is **ignored** — 2 h, 24 h and 7 d windows all returned all 9 202 items (3.8–4.6 s).
(Phase 163's and 187's lesson again: probe before designing.)

## Requirements

- **FR-230-1 — titles every cycle, episodes on a rotation.** Each cycle fetches the top-level ids
  (537 → 6 requests) plus **one slice** of the episode ids, `EPISODE_SWEEP_CYCLES` = 15 slices, so
  every episode is still re-read every 5 min but a cycle is ~12 requests, not 94. Results **merge**
  into the user's map (today the map is replaced wholesale). What this loop exists for — changes made
  *outside* Ravilo — tolerates 5 min on an episode tick; changes made *inside* Ravilo never waited on
  it (FR-230-2).
- **FR-230-2 — a stop refreshes what the stop touched.** `onStopLanded` carries the stopped item's
  Jellyfin id; `invalidatePlaystate(device, stoppedId)` refreshes the top-level ids plus the episodes
  of the title that owns `stoppedId` (or just that movie) — a handful of requests — and only then
  rebuilds Continue. Callers without an id (`/tv/playback/*` fallbacks) get top-level only.
- **FR-230-3 — the first sweep is whole, and patient.** A user with no map yet gets the full id set
  once, under `COLD_FETCH_TIMEOUT_MS` = 20 s — there is no good value to protect and a slow answer
  beats none.
- **FR-230-4 — the same patience for a cold Continue list.** `buildCanonicalContinueList` uses
  `CONTINUE_COLD_TIMEOUT_MS` = 20 s when the user has no cached list, and the loop retries such users
  after 5 s instead of 60. With a list in hand the 6 s budget and R231's rule are unchanged.
- **FR-230-5 — say what a cycle cost.** The per-cycle INFO line gains ids and requests
  (`Playstate refresh: 4/4 users, 2 520 ids in 28 requests`), so the next regression of this kind is
  readable in the log rather than found with a stopwatch.
- **FR-230-6 — tests.** Slicing covers every episode id exactly once per `EPISODE_SWEEP_CYCLES` and
  the top-level ids every cycle; a targeted set for an episode id is its title's episodes + all
  top-level ids; a movie id yields top-level only; an unknown id degrades to top-level only.

## Non-goals

An event-driven design (Jellyfin's `UserDataChanged` over the per-device session bridge) is the
right end state and removes the loop; it is a bigger change and is not this phase. Throttling the
segments lane against playback is 213's territory. `ionice` being inert on this host is known (212).

## Acceptance

1. Production log after deploy: no `timed out … at Jellyfin (permit wait 0 ms)` lines across an hour
   with no scan running; the cycle line shows ~12 requests per user.
2. After a restart every recently-seen user has a Continue list within the first minute.
3. A stop's `home_changed` push follows the stop by well under 2 s on an idle Jellyfin.

## Amendment (2026-11-08, DEB-3955) — a tick made in Ravilo stays ticked

**What the owner saw.** On the TV he marked five episodes watched by hand, and they came unticked again.

**Why.** FR-230-1 says changes made inside Ravilo never wait on the rotation. FR-230-2 kept that
promise only for a stop. `PUT /tv/played` and `POST /tv/mark` both call `invalidatePlaystate(device)`
with no id, so FR-230-2's "callers without an id get top-level only" applied to them. The refresh
then re-read the series' own row, but never the episode that had just been written.
`setPlayed` returns the right answer to the client, but writes nothing into `PlaystateCache`.

The route then pushes `home_changed`. The open series page re-reads its playstate on that push
(R343 FR-R343-12, R351 FR-R351-7), and `GET /tv/playstate` answers from the cache. So the tick shows
for about a second, then drops. Each further mark sends another `home_changed`, and each one drops every
tick made so far. The ticks come back one by one, as the 15-slice rotation reaches each episode, over
up to 5 minutes.

R352's FR-R352-8 safety net does not help either: it reads a series whole when the series' own row
changed between cycles. The titles-only refresh has already put the new series row in the cache, so
the next cycle sees no change. A film is a top-level id and is re-read, which is why only episodes
were affected.

- **FR-230-7 — a mark is the cache's word at once.** `PUT /tv/played` writes the state it read back
  from Jellyfin (`setPlayed`'s return: the item and every affected episode) into `PlaystateCache`
  with `patch`. It does this before `invalidatePlaystate` runs and before any push. `POST /tv/mark`
  does the same with the state it wrote: `played = watched` and `resumeMs = 0`. Its other fields
  stay as the cache already had them. This is R343 FR-R343-11's pattern, applied to the viewer's own
  ticks.
- **FR-230-8 — a mark refreshes what it touched.** Both routes call `invalidatePlaystate(device, itemId)`
  with the id they wrote, so FR-230-2's targeted set (the top-level ids plus that title's episodes)
  is re-read. For a season set (`episodeIds`), the id passed is the first episode's. "Callers without
  an id" now means only the `/tv/playback/*` fallbacks, as FR-230-2 originally intended.
- **FR-230-9 — an older read never undoes a newer mark.** A background cycle or targeted refresh
  that began reading Jellyfin before a viewer's mark landed does not merge that id's value over
  the mark. It also does not push that id in its `playstate_changed` patch. The record of the mark
  is kept per user and id, like the one phase 312 already keeps (`noteViewerMarked`), and is stamped
  **once the write has landed** at Jellyfin. 312's stamp is taken before the write, so it is too early
  for this rule. A read that **began** after the landed stamp is Jellyfin's word and is taken as it is.

**Unchanged:** the rotation, its budgets and its log line; R176's push; the wire. The series page
still replaces its overlay with what `GET /tv/playstate` answers (R343 FR-R343-12): once the cache is
right, the server's answer is the right one to show.

**Restored, not amended.** A multi-episode card's toggle marks only the episode it would play.
R179 §C.1 says the toggle covers every episode in the file. The same build passes the group's ids
as `episodeIds` and restores that.

**Acceptance (amendment).**

4. `linuxX64Test`. Before the fix: `PUT /tv/played` for an episode, then `GET /tv/playstate` for
   that episode, read the old value. After the fix the same pair reads the new value, with no
   background cycle between them. The same holds for `POST /tv/mark`.
5. `linuxX64Test`. A cycle whose read began before a mark of episode E, and returns E's old value,
   leaves E as marked in the cache and leaves E out of its push. A read that began after the mark
   replaces the value.
6. On a TV: mark five episodes of one season watched in a row. Leave the page, come back, switch
   seasons and back again. All five stay ticked the whole time. Untick two and the same holds. Mark
   a multi-episode card and every episode in it is ticked.
