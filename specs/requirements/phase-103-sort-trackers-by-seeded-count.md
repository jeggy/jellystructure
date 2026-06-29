# Phase 103 — Sort the Trackers tab by seeded-torrent count (FR-XS4)

## Problem
On **Metadata ▸ Trackers**, each tracker card shows how many torrents are seeded on that tracker
(`torrentCount`), but the list renders in **config-declaration order** — so the busiest tracker can sit
anywhere. The operator wants the most-seeded tracker **first** and the least-seeded **last**, so the
list reads as a usage ranking at a glance.

## Findings
`GET /api/metadata/trackers` (`src/linuxX64Main/.../server/routes/MetadataRoutes.kt:202–212`) maps
`configStore.current.trackers` in declaration order and never sorts:
```kotlin
get {
    val trackers = configStore?.current?.trackers ?: emptyList()
    val snap = runCatching { seedingSnapshot.get() }.getOrNull()
    call.respond(trackers.map { t ->
        val count = snap?.torrents?.count { tor ->
            TrackerResolver.hostOf(tor.tracker.takeIf { u -> u.isNotBlank() } ?: "") in t.hosts
        } ?: 0
        TrackerWithStats(t.name, t.isPrivate, t.hosts, count)
    })
}
```
The frontend `renderTrackersTab` (`Metadata.kt:363–400`) renders cards in received order
(`trackers.forEach { append(trackerCardHtml(it)) }`, `:372`); `wireTrackersTab` (`:434–507`) binds by
the card's `data-tracker` name, so it is **order-independent** — reordering can't break wiring.

**Consistency precedent:** the "Unmapped announce hosts" list is already sorted descending by torrent
count in `TrackerResolver.detectUnmappedGroups` (`TrackerResolver.kt:52` —
`.sortedByDescending { it.torrentCount }`).

## Goal
The Trackers tab lists named trackers ordered by `torrentCount` descending; the unmapped-groups list
already follows this, so the whole tab reads as a usage ranking.

## Requirements
1. **Sort in the backend route** (single source of truth). Append
   `.sortedByDescending { it.torrentCount }` to the `trackers.map { … }` block at
   `MetadataRoutes.kt:206–211`, mirroring `detectUnmappedGroups`:
   ```kotlin
   call.respond(trackers.map { t ->
       val count = /* … */
       TrackerWithStats(t.name, t.isPrivate, t.hosts, count)
   }.sortedByDescending { it.torrentCount })
   ```
   This benefits every consumer (production UI **and** the design mockup) without per-client sort logic.
2. **Stable tie-break (optional):** for equal counts, fall back to name to keep ordering deterministic
   between snapshots — `.sortedWith(compareByDescending<TrackerWithStats> { it.torrentCount }.thenBy { it.name.lowercase() })`.

## Scope
- `src/linuxX64Main/.../server/routes/MetadataRoutes.kt:206–211` — one sort on the mapped list. No
  frontend change (`renderTrackersTab` is order-independent).

## Non-goals
- No change to tracker CRUD, the unmapped-groups detection, or the `?tracker=` Library deep-link.
- Config declaration order is unchanged on disk — this only affects the **display** ordering returned by
  the GET route.

## Acceptance
- Open Metadata ▸ Trackers with several trackers of differing seeded counts: cards appear most-seeded
  first, least-seeded last; ties order by name. Adding/removing a tracker and re-loading re-ranks
  correctly; card actions (edit/delete/assign) still work (order-independent wiring).
