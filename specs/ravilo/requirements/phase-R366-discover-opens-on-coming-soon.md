# Phase R366 — Discover opens on Coming Soon

> Owner, 2026-10-04: *"In discover we want coming soon to be the first tab, just before networks."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from the owner's direction. Not dev-reviewed. Client only
(`ravilo-ui` commonMain, `NavItems.kt`) plus the design mockup; no string, DTO, backend or config change.
**Amends R268 FR-R268-1** (the declared order) only; R268's mechanism (one declared order, gating filters it,
the entry chip is the first rendered one) stays exactly as built.

## Requirements

### FR-R366-1 — The declared order
`DiscoverSegment` (and with it `DISCOVER_SEGMENT_ORDER`) becomes:

**Coming Soon · Networks · Studios · Genres · Request**

Only Coming Soon moves; the three library walls keep their order and Request stays last.

### FR-R366-2 — Where Discover opens
Unchanged rule, new result: Discover opens on the first rendered chip (`defaultDiscoverSegment`). So:
- a household with Sonarr/Radarr (Coming Soon available) **opens on Coming Soon**;
- a household without it opens on **Networks**, as today (or the first library wall left, R310).
The Discover nav button's step while on Discover (R170, `nextDiscoverSegment`) walks the new order.

### FR-R366-3 — Every surface follows
TV, phone and desktop take the order from the same list (no per-platform order). The design mockup's
`SEG_ORDER` in `design/ravilo/ravilo-app.js` (and the phone's in `Ravilo Mobile.html`) changes to match.

### FR-R366-4 — Tests
`DiscoverSegmentOrderTest`: the pinned order becomes the one above; `theFirstChipIsNetworksOnEveryHousehold`
becomes *Coming Soon when available, else Networks*; the subsequence and always-rendered-default tests stay.

## Acceptance

1. With Sonarr/Radarr configured: Discover opens on Coming Soon, the strip reads *Coming Soon · Networks · Studios ·
   Genres · Request* (Request only with Seerr).
2. Without Sonarr/Radarr: the strip starts at Networks and Discover opens there.
3. On the TV, Down from the app bar's Discover tab lands on the selected chip (R350 FR-5), Left from Coming Soon
   does nothing, Right walks the strip in the new order.
4. `DiscoverSegmentOrderTest` green.
