# Phase R198 — Continue Watching resume order is untrustworthy from Jellyfin (bug fix, FR-RV-CW3)

> A viewer on stue TV reported *Severance* (watched today) sitting behind *Mouk* (last watched four
> days earlier) in Continue Watching. First investigated as a possible repeat of R185's
> Played/PlaybackPositionTicks desync or a resume/next-up merge-order bug — both ruled out once the
> viewer confirmed Severance still shows a progress bar (i.e. it's genuinely in the resumable set, not
> next-up). Root-caused live against Jellyfin (jellyfin.example.net): Jellyfin's own
> `/Users/{id}/Items/Resume` endpoint, called with `SortBy=DatePlayed&SortOrder=Descending`, does not
> reliably honor that sort for every item — jellystructure has never re-sorted the response itself.

**Status:** Implemented. Verified via `compileKotlinLinuxX64`. Not yet on-device verified (no backend
restart/deploy this session, per standing preference).

## Bug report
"why is severnce behind mouk in the contonie watching section on stue tv? i did watch severance today
but not mouk." Clarified once asked whether Severance had finished an episode (which would make it a
next-up entry instead of resumable): "i did not actually finish an episode in severance, and can see in
ravilo that its half way through an episode directly on the continue watching content row by its
progress indicator." (2026-08-18.)

## Investigation
Two hypotheses were checked and ruled out by reading the code first:
- **Not R185's Played/PlaybackPositionTicks desync** — `buildContinueRow`'s `resumeItems` loop already
  skips `play.userData?.played == true` (`HomeFeedService.kt:521`, the R185 guard); this doesn't explain
  a still-in-progress item misordered against another still-in-progress item.
- **Not a resume/next-up concatenation bug** — `buildContinueRow` places all `resumeItems` cards before
  all `nextUpItems` cards with no shared-recency merge (`HomeFeedService.kt:511-539`), which *would*
  explain a just-finished title landing at the row's tail — but the viewer confirmed Severance still
  shows a progress bar, meaning it's still in `resumeItems`, not `nextUpItems`. (This concatenation
  pattern is still real and worth fixing eventually — see **Out of scope**.)

**Live evidence** (`GET /Users/{id}/Items/Resume?Recursive=true&SortBy=DatePlayed&SortOrder=Descending&Fields=UserData`
against jellyfin.example.net, user `jogvan`, 2026-08-18, 94 resumable items): the list is correctly
sorted descending by `UserData.LastPlayedDate` for the surrounding ~90 entries, but two adjacent
entries are swapped:

```
9.  Sára og Dunna – Hyggja at stjørnum   2026-08-16T09:16:50Z
10. Mouk – Cheer Up Donkey               2026-08-14T16:17:47Z   ← out of order
11. Severance S02E03 – Who Is Alive      2026-08-17T18:02:05Z   ← out of order
12. NEPHEW – 07.07.07                    2026-08-13T21:00:03Z
```

Severance's real `LastPlayedDate` (2026-08-17, i.e. "yesterday" relative to the 2026-08-18 report — the
viewer had in fact resumed it again today, which only advances `PlaybackPositionTicks`/`PlayedPercentage`
via a progress heartbeat, not necessarily `LastPlayedDate`, itself a known Jellyfin nuance worth
remembering but not the bug here) is later than Mouk's (2026-08-14), yet Jellyfin placed Mouk first
despite the request explicitly asking for `SortOrder=Descending` on that exact field. Every other
adjacent pair in the 94-item response is correctly ordered — this isn't the whole endpoint being
unsorted, just an unreliable guarantee on `SortBy=DatePlayed` that jellystructure has never defended
against.

### Root cause
`buildContinueRow` (`HomeFeedService.kt:502-528`) fetches `resumeItems` via
`jellyfinClient.getResumeItems(..., SortBy=DatePlayed, SortOrder=Descending)` (`JellyfinClient.kt:358-374`)
and iterates it in the exact order Jellyfin returns, building cards 1:1 with no re-sort of its own
(confirmed: no `sortedBy`/`sortWith` anywhere in the function). The request's sort parameters are a
*request*, not a guarantee — as demonstrated live, Jellyfin can and does return at least some items out
of the requested order. Every downstream consumer of this row (Home, every per-channel row via
`getChannelFeed`, and `continueWatchingAll` for the R187 "→ See all" page) inherits whatever order
Jellyfin happened to return.

Notably, `TvRoutes.kt:742` already establishes the right pattern elsewhere in this codebase — when
merging Jellyfin's finished-history list with in-progress items for the admin "Recently watched" panel,
it explicitly does `(finishedEntries + inProgress).sortedByDescending { it.lastPlayedAt }` rather than
trusting either source list's order. `buildContinueRow` has no equivalent.

## Requirements

### FR-RV-CW3-1 — Re-sort `resumeItems` by `LastPlayedDate` before building cards
`buildContinueRow`'s `resumeItems` loop (`HomeFeedService.kt:517-528`) must sort the (already
R185-filtered) candidate list by `play.userData?.lastPlayedDate` descending, via the existing
`dev.jellystructure.util.isoToEpochSeconds` parser (same helper `TvRoutes.kt:127/730` and
`Scanner.kt` already use for Jellyfin ISO timestamps — no new date-parsing code), before assigning
positions in `cards`. An entry with a null/unparseable `lastPlayedDate` sorts last, not first (treat as
epoch 0), so a malformed timestamp can't jump to the top of Continue Watching.

## Invariants
- **Continue Watching's resumable block is always ordered by actual last-played recency**, not by
  whatever order the upstream Jellyfin call happened to return — jellystructure owns the guarantee, the
  same way `TvRoutes.kt:742` already does for watch history.

## Out of scope
- The resume/next-up concatenation (`HomeFeedService.kt:511-539` places all resumable cards ahead of all
  next-up cards, unconditionally) is a separate, still-real gap — a title that finishes an episode today
  drops into `nextUpItems` and lands at the row's tail regardless of how recent that finish was, since
  `getNextUp()` (`JellyfinClient.kt:676-690`) requests no `SortBy` at all and isn't merged against
  `resumeItems` on a shared key. Not what this report turned out to be (Severance never left the
  resumable set), so left for its own phase if a next-up-tail report ever surfaces.
- Whether Jellyfin's `LastPlayedDate` itself lags a live in-progress session (updated only on a clean
  `/Sessions/Playing/Stopped` report, not on `/Progress` heartbeats) — noted during investigation as a
  possible contributing nuance to "watched today" perception, but FR-RV-CW3-1 fixes the *ordering*
  regardless of exactly when `LastPlayedDate` itself last moved, and doesn't require pinning down that
  server-side semantic to close this report.
- Repairing Jellyfin's own sort behavior — out of jellystructure's control; the fix is to stop trusting it.

## Source references
- Bug site: `src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt` (`buildContinueRow`,
  lines 488-540).
- Existing correct pattern to mirror: `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/TvRoutes.kt:742`.
- Date parsing helper: `src/linuxX64Main/kotlin/dev/jellystructure/util/IsoDate.kt` (`isoToEpochSeconds`).
- Jellyfin call: `src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt` (`getResumeItems`,
  lines 358-374).
- Related: **R185** (`phase-R185-continue-watching-played-desync.md`, the Played/PlaybackPositionTicks
  desync this report was first mistaken for), **R186** (`phase-R186-continue-watching-fetch-window.md`,
  fetch-window widening — same function, different bug class).
