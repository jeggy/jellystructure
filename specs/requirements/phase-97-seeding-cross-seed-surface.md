# Phase 97 — Seeding & cross-seed surface on detail pages (FR-XS1)

> **Status: Planned** (design done, not built). Builds on the existing integrations — **qBittorrent**
> (`SeedingGuard`, Phases 26/37), **Radarr/Sonarr** (Phase 54). It **unifies** the Phase 37 guard:
> the seeding surface is now the single source of the "edits blocked" warning, replacing the lone
> guard chip on the Tracks tab.

## Problem
Jellystructure already talks to qBittorrent (the seeding guard) and to Radarr/Sonarr (root folders +
rescan), but the operator can't *see* the seeding picture for a title. Phase 37 only surfaced a binary
"⛨ guard active" chip on the movie Tracks tab — no torrent name, no count, no trackers, nothing for
series. Operators want to know, per title: **is this being seeded, how many times, and to which
trackers** — so they can judge edit risk and ratio health at a glance.

The hard part is **cross-seed + series granularity**:
- **Cross-seed** means one physical file is registered in **several torrents at once**, each on a
  different tracker (same info-content, different info-hash). "Seeded 3×" is the cross-seed depth.
- For a **series**, torrents exist at **different scopes**: a single-episode torrent, a **season pack**,
  and a **complete-series (multi-season) pack** — and a given episode file can belong to **all three
  simultaneously**. Editing that episode would desync every torrent that contains it.

## Goal
A **Seeding tab** on both Movie detail (`media.html`) and Series detail (`series.html`), plus an
at-a-glance **pagebar pill**, that shows every torrent referencing the title's file(s), cross-referenced
to the *arr grab history, and — for series — a **coverage chart** that makes the single-episode /
season / multi-season overlap legible. Read-only (plus copy-hash / open-in-qBittorrent / reveal-coverage
navigations); it never acquires, mutates, or deletes a torrent (scope fence, Phase 54).

> **Companion phases (designed together):** torrent → named-tracker resolution (announce-host mirrors,
> the tracker column, the library *seeded-on* filter) is **[Phase 98](phase-98-tracker-registry.md)**;
> the shared short-lived qBittorrent snapshot that makes all these reads cheap (and the freshness bar)
> is **[Phase 99](phase-99-seeding-snapshot-cache.md)**. This phase owns the per-title surface itself.

## Current state (as-is)
- `GET /api/media/{id}/seeding` → `SeedingStatus { status, torrentName?, detail? }`,
  `status ∈ {unconfigured, allowed, blocked, unreachable}` — **one** torrent, movie-only, shown as a
  chip/banner on the Tracks tab (Phase 37 / design-gap 2B).
- `SeedingGuard.check(path)` is the fail-closed gate on all five mkvpropedit/ffmpeg call sites; the
  Phase 96 bulk job runs it per file.
- Radarr/Sonarr (Phase 54) know which app/indexer grabbed a release (read-only, non-fatal).
- **No** multi-torrent, cross-seed, per-episode, ratio/peer, or *arr-provenance data is surfaced.

## Requirements

### A. Data model (new DTO — replaces the single-status shape for this surface)
`GET /api/media/{id}/seeding` returns the full set:
```
SeedingReport {
  guard: { configured, reachable },                 // qBittorrent availability
  torrents: [ TorrentRef ]
}
TorrentRef {
  infoHash, name, tracker, trackerPrivate: Bool,   // tracker = resolved name (Phase 98); from announce[] hosts
  scope: "movie" | "complete" | "season" | "episode",
  covers: All | { season } | { season, episode },   // which library files this torrent contains
  state: "seeding" | "paused" | "errored",
  ratio, seeders, leechers, uploaded, addedAt, seedingTime,
  crossSeedGroup: String?,                           // same id ⇒ same files on other trackers
  arr: { app: "radarr"|"sonarr", indexer } | null,   // Phase-54 grab provenance, null = manual
  error: String?                                     // when state = errored
}
```
- `covers` is computed by mapping each torrent's file list to the library's episode files (movie = the
  one file). This is what powers the coverage chart and the per-episode guard.
- **Cross-seed grouping**: torrents sharing identical content (hard-linked by a cross-seed tool) carry
  the same `crossSeedGroup`; the UI marks them `⇄ cross-seed`.

### B. Pagebar pill (both pages)
A compact pill next to the title, color-coded, `title=` explains the count:
- **Seeded ×N · M trackers** (amber) when ≥1 torrent is actively seeding — N = active torrents.
- **Not seeded · safe to edit** (green) when no torrent references the file(s).
- **risk** (red) when any torrent is errored / 0-seeders / (private && ratio < 1).
Clicking it jumps to the Seeding tab (`#tab=seeding`).

### C. Seeding tab — shared shell (`seeding.js` / `seeding.css`, loaded by both pages)
1. **Summary strip**: torrents · actively-seeding · trackers · health (✓ all healthy / N need
   attention).
2. **Unified guard banner** (the Phase-37 replacement):
   - **block** (red) when ≥1 torrent seeding — "Track-order & tag edits to seeded files are blocked so
     info-hashes don't break. Cross-seeded copies share the same files, so one edit desyncs every
     linked tracker." (This is the canonical edit-guard message; the Tracks tab links here.)
   - **warn** (amber) when registered but all paused.
   - **ok** (green) when not seeded — "safe to edit".
3. **Torrent detail list**, grouped by scope (Complete-series packs → Season packs → Single episodes,
   or a flat list for a movie). Each card (click to expand):
   - state dot + label, torrent name (mono), tracker + **private/public** badge, `⇄ cross-seed` marker.
   - metrics: **ratio** (amber if private && < 1), **S/L** swarm peers (red if 0 seeders), uploaded.
   - expanded: scope, **covers** (episode pills / "all N episodes"), **provenance**
     (`sonarr`/`radarr` badge + indexer, or "manual / not from *arr"), seeding time + added date,
     info-hash, cross-seed note, and an errored sub-banner when applicable.
   - actions (read-only / navigational): **↗ Open in qBittorrent**, **⧉ Copy info-hash**, and (packs
     only) **⊞ Reveal covered episodes** → highlights the bar in the chart.

### D. Coverage chart — series hero (`series.html` only)
A Gantt-style matrix that makes scope overlap obvious:
- **X-axis** = episodes, grouped under **Season N** headers (label column pinned left on scroll).
- **Depth strip** ("Seeded × depth"): per episode, a mini bar-stack + the count of torrents covering it,
  with a **🔒** when any covering torrent is actively seeding (i.e. that episode is edit-locked).
- **Torrent rows**, grouped by scope: each torrent is a **bar spanning the episodes it contains** —
  a complete pack spans everything, a season pack spans its season, a single-episode torrent is one
  cell. Bar color = state (seeding / paused / errored); 🔒 marks edit-locked (seeding) bars.
- **Overlapping rows over the same episode column = the cross-seed / multi-scope depth**, read directly.
- Clicking a bar selects + expands its detail card below (and vice-versa via "Reveal covered episodes").
- A legend documents the colors and the "overlap = depth" reading.

### E. Guard unification (replaces Phase 37 chip)
- The Tracks tab no longer owns guard state; its old `#guard-chip` becomes a link/cross-reference to
  the Seeding tab. The Seeding tab's banner is the canonical block/warn/ok surface.
- **Per-episode lock**: an episode is edit-locked iff it is `covered` by an actively-seeding torrent.
  This is what the bulk re-order flow (Phase 96 §E3) and the per-episode editor (Phase 42) check —
  a locked episode's track edit returns **409** unless its covering torrent(s) are paused.

## Invariants
- **Read-only / scope fence** (Phase 54): surface + navigate only. No add/remove/recheck/pause that
  mutates qBittorrent state. "Open in qBittorrent" hands off to qBt's own UI; "copy hash" is local.
- **Frontend renders server state only** (constitution §4) — no derived seeding state.
- **Cross-seed = shared files**: editing any member of a `crossSeedGroup` desyncs all of them; the guard
  treats the file as locked if *any* covering torrent is seeding.
- **qBittorrent is the only fail-closed integration**; *arr provenance is decorative and never blocks
  (Phase 54). An unconfigured/unreachable guard degrades to "unknown", never to "safe".
- **API key / credentials never leave the server** (Phase 54 / design-gap QB-1).
- One taxonomy: `covers` maps to the **existing** library episode files — no parallel episode model.

## Out of scope
- Mutating torrents (pause/resume/recheck/reannounce/delete) — a possible later, explicitly-gated phase.
- A library-wide seeding dashboard / ratio report across all titles (this phase is per-title detail).
- Tracker naming / announce-host mapping (**Phase 98**) and the snapshot-cache fetch strategy
  (**Phase 99**) — split into their own phases.
- Acquisition (sending releases to *arr) — hard scope fence.
- Mapping subtitle/external sidecar files to torrents (only the media file is mapped).

## Design reference
- `design/app/seeding.js` + `design/app/seeding.css` — the shared module (`window.Seeding`:
  `renderMovie`, `renderSeries`, `pillHTML`).
- `design/app/media.html` — Seeding tab (flat cross-seed list) + pagebar pill; Sintel seeded in 2
  torrents across 2 trackers (one a cross-seed, one low-ratio).
- `design/app/series.html` — Seeding tab with the **coverage chart** hero + grouped detail list;
  Nordvest (2 seasons / 14 eps) exercises a complete pack, two cross-seeded S01 packs on different
  trackers, a paused S02 pack, a public single-episode seed, and an errored single — so the depth
  strip, lock states, ratio-health, and provenance variants all appear.
