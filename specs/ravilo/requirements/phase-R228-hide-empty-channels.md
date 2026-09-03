# Phase R228 — Hide a channel that has nothing in it for this viewer

> Reported live: the "Olivar" channel is only meaningful for one household user (full library
> access); for other Ravilo profiles restricted to a subset of libraries, opening it shows nothing.
> It should just not be there for them, the same way it's automatically there for the user it does
> have content for.

## Status
Planned.

## Problem — verified against live code
Channel visibility and channel content are computed by two completely disconnected code paths in
`HomeFeedService.kt`, and only one of them is per-viewer:

- **Which channels exist in the "Collections" row / the client's channel list** is
  `buildChannels(config)` (`HomeFeedService.kt:290-304`) — a pure function of `RaviloConfig.channels`
  (`enabled` + sort by `order`). It never looks at a `MediaItem`, never receives the requesting
  `device`, and is literally documented as "config-only, no Jellyfin/MediaStore calls"
  (`:91-95`, on the standalone `getChannels(device)` route used by the R187 browse-page facet).
- **What a channel actually contains for one specific viewer** is decided later and separately, once
  the channel is opened: `getChannelFeed(device, channelId)` (`:205-232`) filters the device's own
  already-access-scoped item list (`mediaStore.liveItems(device)`, Phase 142's per-user
  library/tag policy) through `MediaItem.matchesChannel(channelCfg, heroIds)` (`:732-744`), then
  builds heroes + rows from what's left.

A channel is rendered as a tile in Home's "Collections" row (`HomeScreen.kt:284-285`,
`str("section.channels")`) and in the R187 browse Channel facet purely because it's `enabled` in
config — with no regard for whether *this* viewer's `liveItems(device)` has anything matching it.
Olivar is `enabled` and matches real items in the full library, so it always renders as a tile; a
restricted profile's `liveItems(device)` excludes every item Olivar's filter would ever match, so
opening the tile lands on an empty page — the tile itself gave no indication of that.

## What "empty" actually has to mean
A channel's own top-level filter (`matchesChannel`) is **not** sufficient on its own to define
"empty," because of how `buildRows` (`:312-413`) already treats **inherit-mode** channels (R202):
an inherit-mode channel's Continue Watching row is always Home's own library-wide row
(`libraryAll`, `RowKind.CONTINUE` branch, `:354-363`) regardless of whether anything in the
viewer's library matches the channel's own filter — "inherit" means "same as Home," genuinely, not
"channel-filtered." So a channel with zero matching items can still legitimately show a non-empty
Continue Watching row when opened, and hiding it in that case would be wrong.

The only definition that can't disagree with what the viewer actually sees on opening the channel
is: **build the channel exactly as `getChannelFeed` already does (heroes + all rows, inherit or
custom) and check whether the result is completely empty** — zero heroes and every row's `items`
empty. Anything cheaper (e.g. `all.any { it.matchesChannel(...) }` alone) is wrong for inherit-mode
channels for the reason above.

## Goal
A channel that would render as heroless and rowless if this specific viewer opened it right now
does not appear as a tile anywhere for that viewer — not in Home's Collections row, not in the
R187 browse-page Channel facet, not as a navigable destination that silently lands on a blank page.
A different viewer with different library access for whom the same channel *does* resolve to real
content keeps seeing it exactly as today. Enabling/disabling a channel in the admin config editor is
unaffected — this is purely a per-viewer render-time decision, same spirit as the frontend-renders-
server-pushed-state rule (nothing new is written to config).

## Requirements

### FR-R228-1 — Channel visibility is decided from the same content build the channel itself uses
`buildChannels` must stop being a config-only function. For each `enabled` channel (in `order`),
compute its content for the requesting `device` using the **same** row/hero-building path
`getChannelFeed` uses — not a separate, cheaper approximation that could disagree with it — and
include the channel only if that computed content is non-empty (at least one hero, or at least one
row with at least one item). Reuse, don't re-derive: factor the shared heroes+rows build so
`getChannelFeed` and this emptiness check call the identical code, the same way R219 made Continue
Watching membership a single canonical computation reused by every view rather than something each
call site could independently get slightly wrong.

### FR-R228-2 — Applies everywhere a channel list is served
Both call sites of `buildChannels` need the fix, not just one:
- `buildHomeFeed`/`getChannelFeed`'s own `channels = buildChannels(config)` (`:195`, `:225`) — these
  already have `device`, `all`/`allItems`, and `heroIds` in scope, so this is a threading change,
  not new data.
- The standalone `getChannels(device)` (`:96-97`), which currently makes *no* MediaStore call at
  all and backs the R187 browse page's Channel facet — it must now also resolve `device`'s own
  `liveItems` to run the same emptiness check. This turns it from a config-only lookup into a
  per-device one; call out this cost explicitly rather than silently accepting it (see Open
  questions).

### FR-R228-3 — `/tv/channel/{id}` for a now-hidden channel
Directly requesting a channel id that resolves to empty content for this device (e.g. a stale
client-side cache, or the channel was reachable a moment ago and just emptied) must not error —
`getChannelFeed` already returns a well-formed (empty) `HomeFeed` for an unknown channel id
(`:208`); an empty-for-this-viewer channel should behave the same way on direct navigation. The
*hiding* only applies to where a channel is offered as a thing to tap into.

## Invariants (must not change)
- **No config write.** Whether a channel is `enabled` in `RaviloConfig.channels` is unchanged;
  this phase only decides render-time visibility per device, exactly like every other per-viewer
  Phase 142 restriction already does to rows/heroes.
- **Inherit-mode Continue Watching keeps R202's "same as Home" behavior untouched** — it's the
  reason the emptiness check has to build real content rather than reuse `matchesChannel` alone.
- **A channel that has content for this viewer is completely unaffected** — same tile, same
  position, same content, same cost profile it has today aside from the cache/build-timing changes
  in the Open questions below.
- **`Frontend renders server-pushed state only`** (constitution) — the client makes no visibility
  decision of its own; it only ever receives the already-filtered channel list.

## Non-goals
- No admin-facing indicator ("this channel is empty for user X") — this phase is purely about what
  a given viewer's own Ravilo sees, not an admin diagnostic. Could be a reasonable follow-up on
  `app/ravilo-config.html`'s channel list, not required here.
- No change to how a channel's own content is filtered (`matchesChannel`, `buildRows`,
  `ChannelRowsConfig`) — this phase only decides whether the already-correct result is nonempty.
- No caching/perf redesign beyond what FR-R228-1's reuse requirement naturally gives — see Open
  questions for what's deliberately left as a follow-up.

## Open questions (resolve during implementation)
- **Cost of computing N channels' full content on every Home-feed build.** Today the Collections
  row costs nothing beyond config; after this phase, `buildHomeFeed`/`getChannelFeed` must build
  (or at least evaluate down to a boolean) every enabled channel's rows for this device on top of
  the rows it was already building for the current view. `feedCache` (`:72-73`, keyed on
  `(userId, libVer, cfgHash, allowedHash)`, TTL `FEED_TTL_MS`) already caches the *whole* `HomeFeed`
  including `channels`, so a repeat load within the TTL is free — the real cost is the first build
  after cache invalidation, once per channel. Acceptable for a typical household's channel count;
  flag if a library with many configured channels makes this noticeably slower and consider a
  per-channel emptiness cache (keyed like `feedCache`) as a follow-up rather than blocking this
  phase on it.
- **`getChannels(device)`'s new MediaStore dependency.** It backs only the R187 browse Channel
  facet — confirm that facet's call frequency (once per browse-page load) tolerates the added
  `liveItems(device)` call; if not, consider having it reuse `feedCache`'s already-built channel
  list for that device instead of rebuilding independently.
- **Short-circuiting the emptiness check.** FR-R228-1 requires reusing the exact same build, but
  the cheapest correct implementation can stop as soon as it finds one hero or one non-empty row
  rather than fully materializing every row's `MediaCard` list — an implementation detail, not a
  behavior change, left to the implementer.

## Source references
- Config-only channel list: `src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt:290-304`
  (`buildChannels`), `:91-97` (`getChannels`, doc comment claiming "no Jellyfin/MediaStore calls").
- Per-viewer channel content build to reuse: `HomeFeedService.kt:205-232` (`getChannelFeed`),
  `:312-413` (`buildRows`), `:732-744` (`matchesChannel`).
- Why `matchesChannel` alone is insufficient: R202's inherit-mode Continue Watching fix,
  `HomeFeedService.kt:354-363`; `specs/ravilo/requirements/phase-R202-inherit-channel-missing-continue-watching.md`.
- Per-device library/tag filtering already in place: `src/linuxX64Main/kotlin/dev/jellystructure/media/MediaStore.kt:514-524`
  (`liveItems(device)`, Phase 142).
- Client render site: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/HomeScreen.kt:173,284-285`
  (`hasChannels`, `section.channels` row over `feed.channels`).
- Feed caching to reuse/extend: `HomeFeedService.kt:69-73` (`feedCache`, `FeedEntry`).
- Route: `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/TvRoutes.kt:349-361`
  (`/tv/channels`, `/tv/channel/{id}`).

## Relationships
- Direct precedent for "one canonical computation, every view reuses it instead of re-deriving and
  risking disagreement": **R219**'s Continue Watching merge rule
  (`reference-continue-watching-merge-rule` in project memory) — FR-R228-1 applies the same
  discipline to channel-content emptiness.
- Builds on **Phase 142** (per-device library/tag access policy) and **R202** (inherit-mode channel
  Continue Watching) — both already-correct pieces this phase composes rather than modifies.
- `scripts/check-phases.sh` will want a `STATUS.md` row — **STATUS.md is code-owned; do not add the
  row from the design side.**
