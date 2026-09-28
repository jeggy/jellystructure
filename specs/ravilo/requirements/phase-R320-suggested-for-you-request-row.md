# Phase R320 — *Suggested for you*: the household's suggestions as a Request row, per viewer

## Status

`✓ Built` 2026-09-28, **deployed** with 274 (build notes at the end) — written 2026-09-27 with admin **274**, from
`specs/ravilo/design-brief-suggested-movies-from-seerr-2026-09-27.md` §3. **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). Numbering verified
against `main` `e437def3`: Ravilo taken through **R319**. Builds on **R171** (the Request tab), **137** (Request
feeds), **R318** (unknown row kinds never break an app), **155** (unrated is 18).

## Requirements

**FR-R320-1 — One more Request row, nothing new drawn.** A viewer whose Request feeds include *Suggested for you*
(274 FR-274-15) gets it in Discover ▸ Request exactly like *Trending* or *Popular*: the same tile with its request
status, the same request detail on Select/tap, the same *Request* button gated on `can_request`, the same states
afterwards. TV, phone and web. No new screen, component or layout.

**FR-R320-2 — It is this viewer's list.** The row's items are `suggestion_viewer` for this device's user and
visibility scope (274 FR-274-6), in stored order, at the feed's limit. The server resolves them; the app renders.

**FR-R320-3 — No history, no row.** A viewer with no sources (nothing finished, nothing in progress) gets **no
row** — not an empty row, and not someone else's list.

**FR-R320-4 — Kids profiles.** Built against the same pool, filtered by the Home feed's age gate using the
certification Seerr returns; a candidate with **no** certification is never shown to a kids profile (155).

**FR-R320-5 — What the viewer never sees.** No *No thanks*, no reason picker, no *because* line, no cluster name,
no product name (*Request*, never Seerr/Radarr/TMDB). Titles on Seerr's blacklist never appear (274 FR-274-16).

**FR-R320-6 — Installed apps keep working.** On the wire the feed is an ordinary resolved Request row. Whatever
new value the editor stores (a feed kind) never reaches anything an installed app decodes without a default.
`WireCompatTest` (R319) passes with a *Suggested for you* row in the payload.

**FR-R320-7 — One string.** The feed's default title, `rq_suggested`: *Suggested for you* · da *Forslag til dig* ·
fo *Uppskot til tín* (drafts; R288's lexicon — the shipped table wins). The admin can rename the row as any feed.

## Acceptance

1. With the feed on for a viewer who has finished films, the TV's Request tab shows *Suggested for you*; Select
   opens the existing request detail, and *Request* sends a request as that viewer (their own, as any Request row).
2. The same viewer with the feed off: no row. A viewer with no history: no row.
3. A kids profile's row holds only rated films its age allows.
4. A title dismissed on the admin page is gone from this row after the next feed refresh.
5. An app built before this phase shows the row like any other Request row.

## Mockup

`design/ravilo/ravilo-data.js` (`suggested`, first in Eyð's feeds), rendered by the existing Request tile in
`Ravilo TV.html` and `Ravilo Mobile.html`; the string in `ravilo-i18n.js`.

## Dev review (2026-09-28, against `main` `728f22ea`)

Four items; item 1 is a correction that changes FR-R320-6's mechanism.

1. **A new feed kind cannot be a new enum value on the wire.** `RaviloConfig.discover.feeds[]` is
   `SeerrFeed{id, kind: SeerrFeedKind, endpoint: SeerrDiscoverEndpoint, param, name, visible}`
   (`shared/…/tv/Models.kt:1188-1207`), served by `GET /tv/config` to **every installed app**. R318 gave
   `endpoint` a default and `coerceInputValues`, but apps before R318 (v1.23–v1.40 are in use) decode it
   strictly — which is exactly what R319's `WireCompatTest` forbids (*an unknown enum value unless listed as
   never sent*). **Correction, 269's pattern:** the feed is stored and sent as an **existing** `endpoint`
   (e.g. `TRENDING`) plus an additive **`suggested: Boolean = false`** on `SeerrFeed`; the server resolves a
   `suggested` feed from `suggestion_viewer` instead of calling Seerr; the admin's own config route may carry
   the real kind. `DiscoverRow{feedId, feedName, entries[]}` and `RequestEntry` are unchanged, so an old app
   renders the row as any other. FR-R320-6 is to be read this way.
2. **The kids gate is new machinery, not reuse.** `getRequestFeeds(userId, isAdmin, isKids)`
   (`seerr/SeerrDiscoverService.kt:80-94`) uses `isKids` only for request-language options; Request feeds carry
   **no** age filter today, and Seerr's discover/recommendation results carry **no** certification (only
   `movieDetails` does, via `releases`). So FR-R320-4 needs: 274's build stores each suggestion's DK/US/GB
   certification from the details it already fetches, 155's `CertificationResolver` normalises it, and the
   viewer row filters `age ≤ the device's cap`; no certification ⇒ excluded for a kids profile (as written).
   **Only the suggested row is gated** — the other Request feeds stay as they are.
3. **Feed titles are admin-typed, not viewer strings.** The title on the wire is `feed.name`
   (`SeerrDiscoverService.kt:91`, `DiscoverRow.feedName`), set in the admin editor whose catalogue labels are
   English (`ui/RaviloConfig.kt:87-97`). So `rq_suggested` is **not** a viewer i18n key unless the server
   localises system feeds; if a per-language default is wanted, the key is dotted like the shipped table
   (`req.suggested`) and the admin's rename still wins. FR-R320-7 amended.
4. **`WIRE_ROOTS` is hand-listed** (`shared/src/linuxX64Test/…/wire/WireRoots.kt:6`); `SeerrFeed` is already
   covered through `RaviloConfig`; the new flag has a default → `WireCompatTest` passes. Run it before shipping.

## Build notes (2026-09-28)

Built with 274 (the server side is all of it; no app changed).

1. **On the wire (dev review 1):** `SeerrFeed.suggested: Boolean = false`, additive with a default; the editor stores the
   feed as `endpoint = TRENDING` plus `suggested = true`, and the server resolves it to an ordinary `DiscoverRow`. No
   enum gained a value; `WireCompatTest` passes against the released baseline, so an app built before this phase shows
   the row like any other (acceptance 5, by the test).
2. **The row:** `getRequestFeeds` serves a suggested feed from `suggestion_viewer` for the device's user and 269's scope
   key (any scope the user has, when this one has none yet), drops what has reached the library since the build, and
   leaves out the row entirely when the list is empty — no history, no row (FR-R320-3). Every Request feed, this one
   included, drops Seerr's blocklist and 274's dismissals (FR-R320-5).
3. **Kids (FR-R320-4, dev review 2):** decided at build time from the viewer's own Jellyfin parental cap and 155's age
   for the film's certification; no certification ⇒ not in a kids profile's list. Only this row is gated.
4. **The string (FR-R320-7 as amended by dev review 3):** no viewer i18n key. The row's title is the feed's name, typed
   in the admin editor like every other Request feed; the editor fills in *Suggested for you* and the admin can rename
   it. A per-language default would need the server to localise system feed names, which nothing does today.
5. **Not tried on a device:** the household's Request config has no *Suggested for you* row yet — adding it (Ravilo
   config ▸ Request ▸ Add row) is the owner's choice. The stored per-viewer lists were built on the household server
   (274's build notes).
