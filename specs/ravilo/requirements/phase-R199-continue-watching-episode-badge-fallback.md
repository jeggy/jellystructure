# Phase R199 — Continue Watching's episode badge goes blank when Jellyfin can't parse the filename (bug fix, FR-RV-CW4)

> Found live on stue TV immediately after deploying R198: every other in-progress series in Continue
> Watching shows its "S1 · E3"-style badge except *Severance*, whose S02E03 card has no episode number
> at all. Root-caused live against Jellyfin (jellyfin.jebster.net) and jellystructure's own DB — this is
> the Continue Watching badge's own gap, not a repeat of Phase 152 (which already fixed the *scanner's*
> side of exactly this problem).

**Status:** Implemented. Verified via `compileKotlinLinuxX64`.

## Bug report
"please take a look on stue tv. currently other series do show episode number, but not severance."
(2026-08-18, immediately following the R198 restart.)

## Investigation
Live query (`GET /Users/{id}/Items/Resume?...&Fields=UserData`, jellyfin.jebster.net, user `jogvan`):
Severance's current resume entry ("Severance.S02E03.Who.Is.Alive") comes back with
`ParentIndexNumber: 2` (season, present) but **`IndexNumber: null`** (episode, missing) — Jellyfin's own
metadata parser failed to extract an episode number from this file's scene-release-style name. Two other
resumable items in the same 94-item list show the identical pattern (`IndexNumber: null` with a present
`ParentIndexNumber`): "Temptation.Island.Danmark.S01E01.NORDiC" and
"Happy.Tree.Friends.S01E01.The.Wrong.Side.of.the.Tracks...". By contrast, Severance's Season 1 files
(`Severance (2022) S01E01 (2160p...) [REPACK].mkv` — a bracketed, non-dotted naming convention) all parse
fine in Jellyfin.

Cross-checked jellystructure's own scan of the same file (`config/jellystructure.db`, `media` table,
`json->episodes[]` matched by `jellyfinId == "1f7ce1f555cbf4bb6b25005ef16c0f16"`): jellystructure already
knows this is `seasonNumber: 2, episodeNumber: 3` — correctly resolved via **Phase 152**'s filename
fallback (`(season, episode)` parsed from the filename when Jellyfin has no `IndexNumber`), confirmed for
**every** Season 2 file (all ten use the same dotted naming convention Jellyfin can't parse).

### Root cause
`HomeFeedService.buildContinueRow` (`HomeFeedService.kt:517-544`) builds the on-card episode badge
straight from Jellyfin's live response — `play.seasonNumber`/`play.episodeNumber` (R113,
`JellyfinPlayItem.seasonNumber`/`episodeNumber`, mapped from `ParentIndexNumber`/`IndexNumber`) — with no
fallback to jellystructure's own already-correct `Episode.seasonNumber`/`episodeNumber` (`Media.kt:118-119`,
resolved by the scanner, reachable via `mediaItem.episodes.firstOrNull { it.jellyfinId == play.id }`).
Phase 152 fixed the scanner's *own* data; nothing downstream was ever taught to prefer it over Jellyfin's
when Jellyfin's own field is null. The badge (and, for `nextUpItems`, the "S{s}E{e} · {name}" label) simply
renders without a number whenever Jellyfin's parse fails, regardless of what jellystructure already knows.

## Requirements

### FR-RV-CW4-1 — Fall back to jellystructure's own scanned episode number
Both loops in `buildContinueRow` (`resumeItems` at `HomeFeedService.kt:524-535` and `nextUpItems` at
`537-544`) must resolve `seasonNumber`/`episodeNumber` as: Jellyfin's own field if present, else the
matching `Episode` in `mediaItem.episodes` (matched by `jellyfinId == play.id`) if Jellyfin's is null. Each
field falls back independently (a file could plausibly have one present and the other missing). A card for
an item with no matching local `Episode` at all (e.g. `mediaItem` not yet re-scanned) keeps today's
behavior — no badge, not a crash.

## Invariants
- **If jellystructure's own scan already knows a file's season/episode, Continue Watching shows it** —
  Jellyfin's own metadata gaps for a given file never suppress a badge jellystructure could otherwise
  render, since Phase 152 means the scanner-side truth is usually the more reliable one for
  irregularly-named files.

## Out of scope
- Fixing Jellyfin's own filename-parsing behavior — not something jellystructure controls; Phase 152 and
  this phase both work around it rather than through it.
- Any other surface that reads `IndexNumber`/`ParentIndexNumber` directly from a live Jellyfin call
  without going through jellystructure's own scanned data (not audited here) — flagged as a class of risk,
  not chased down phase-by-phase in this report.

## Source references
- Bug site: `src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt` (`buildContinueRow`).
- Scanner-side fix this complements: **Phase 152** (`specs/requirements/phase-152-scanner-episode-path-fallback.md`).
- Episode model: `src/commonMain/kotlin/dev/jellystructure/model/Media.kt` (`Episode.seasonNumber`/
  `episodeNumber`/`jellyfinId`).
- Related: **R198** (`phase-R198-continue-watching-resume-sort-order.md`), the fix whose live redeploy
  surfaced this report.
