# Phase R186 — Continue Watching only ever sees a global top-20 (bug fix, FR-RV-CW2)

> Reported as "the DanskTV channel's Continue Watching has one item when it should have a bunch —
> Snedigere end du aner should be there." Root-caused live: jellystructure asks Jellyfin for the **global**
> 20 most recent resume/next-up entries and then filters *that* list down to the channel's members, so
> anything ranked 21st or worse globally can never appear — in a channel row **or** on Home. Compounded
> by R185's played/position desync, whose stale entries occupy slots inside that same 20-item window.

**Status:** Planned.

## Bug report
2026-07-31 — "Snedigere end du aner" has S01E01 watched, so S01E02 is its next episode, but the series
appears in neither the DanskTV channel's Continue Watching nor Home's.

## Investigation (live, jellyfin.example.net · user `jogvan`)
- Jellyfin itself is **correct** for this series: `GET /Shows/NextUp?seriesId=…` returns S01E02, series
  `UserData` reads `PlayedPercentage: 10, UnplayedItemCount: 9`, and E01 is `Played: true`. Nothing is
  broken Jellyfin-side, and the series' network (`Viaplay`) is in the DanskTV channel's condition list —
  so channel membership isn't the issue either.
- `JellyfinClient.getNextUp` (`src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt:574-588`)
  and `getResumeItems` (`:273-289`) both default to **`limit: Int = 20`**, and `HomeFeedService.buildContinueRow`
  (`src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt:432-476`) calls them with that default.
- Live numbers for this user: **NextUp `TotalRecordCount: 54`** (we fetch 20) and **115 resumable items**
  (we fetch 20). **"Snedigere end du aner" sits at position 41 of 54** — well outside the window, so it never
  reaches `buildContinueRow` at all.
- Because `getChannelFeed` (`HomeFeedService.kt:174-201`) reuses the same `buildContinueRow` and merely
  hands it a channel-filtered candidate list, a channel row is a **subset of that global 20**. For a
  narrow channel like DanskTV the expected yield is near zero — matching the report exactly.
- **Interaction with R185:** 62 of the user's 115 `IsResumable` entries are the played/position-desync
  rows R185 documents. They sort by `DatePlayed` descending like everything else, so they consume slots
  *inside* the 20-item window. R185's fix filters `played == true` **client-side, after** the window has
  already been applied — so on its own it makes the row *emptier*, not fuller. The exclusion has to move
  server-side for both fixes to compose.

## Root cause
The Continue row is built from a **globally-ranked, hard-capped 20-item sample**, then filtered — instead
of fetching enough of the user's real resume/next-up state to filter *from*. The cap is invisible on Home
(20 cards is more than a row shows) which is why it went unnoticed, but it silently truncates every
channel-scoped row, and any user with more than 20 in-flight titles loses the tail on Home too.

## Requirements

### FR-RV-CW2-1 — Exclude finished items server-side
`getResumeItems` adds `&IsPlayed=false` to its query so Jellyfin never returns an already-watched item as
resumable. This stops R185's desynced rows from consuming the fetch window, and demotes R185's
`buildContinueRow` `played` check to what it was intended to be — a pure display-time backstop.

### FR-RV-CW2-2 — Fetch enough to filter from
Raise both defaults to a window that covers a realistic library's full in-flight set (`200`), and pass it
explicitly from `buildContinueRow`. The row's own visible cap stays `ROW_ITEM_LIMIT` (30) — this only
widens the *candidate* pool the channel/Home filter draws from. Jellyfin returns `TotalRecordCount`
alongside, so the fetched count is verifiable against the true total.

### FR-RV-CW2-3 — Keep the widened fan-out cheap
`buildContinueRow` currently resolves each entry with `all.firstOrNull { it.jellyfinId == itemId }` — a
linear scan per entry (`HomeFeedService.kt:460,469`). With a 10× larger candidate list that becomes
10× the work on every home/channel load. Build one `jellyfinId → MediaItem` map up front and look up
against it instead.

## Invariants
- **A title the viewer is genuinely part-way through appears in Continue Watching regardless of how many
  other titles are in flight** — no silent truncation by global rank.
- **A channel's Continue Watching reflects that channel's own members**, not "whichever of the global
  top-20 happen to be in it".
- **Nothing already watched occupies a Continue Watching slot** — enforced server-side (FR-RV-CW2-1),
  with R185's client-side check remaining as defence in depth.

## Out of scope
- Per-channel Jellyfin queries (Jellyfin's `NextUp`/`IsResumable` endpoints take no item-id allow-list, so
  a channel-scoped fetch isn't expressible; widening the shared window is the available fix).
- Paging the Continue row beyond `ROW_ITEM_LIMIT` — the row still shows at most 30 cards by design.
- The unnumbered-episode class of missing titles (Mesterholdet S10E07 etc.) — different root cause, see
  **Phase 152/153**.

## Source references
- Fetch limits: `src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt` (`getResumeItems`,
  `getNextUp`).
- Row build + channel reuse: `src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt`
  (`buildContinueRow`, `getChannelFeed`).
- Related: **R185** (played/position desync — must compose with FR-RV-CW2-1, see Investigation),
  **Phase 152/153** (the other, unrelated cause of a title missing from Continue Watching).
