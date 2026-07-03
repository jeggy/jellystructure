# Phase 137 — Jellystructure: Ravilo config ▸ Request — Seerr discover rows builder

> Renumbered **135 → 137** after syncing with the repo.

> Replace the per-user **Top 10** section of the Ravilo config editor with a **Request** section: a
> **show-the-tab** toggle, a **request-permission** toggle, and a draggable list of **Seerr discover feeds**
> (with an **Add-row** picker exposing the full endpoint catalogue). This is the per-user authoring surface for
> the TV **Request** tab ([R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)); the Seerr
> **connection** is [Phase 136](phase-136-seerr-connection-retire-charts.md).

**Status:** Planned — **design built** (`design/app/ravilo-config.html`), integration unbuilt.

## Problem
Top 10 was configured per user by picking third-party chart lists (source + country + which charts, in order).
With charts retired (Phase 136) and Seerr as the source, the per-user surface must instead let an operator pick
**which Seerr discover feeds** appear on a viewer's Request tab, and in what order.

## Current state (as-is) — design built in `design/app/ravilo-config.html`
The left-nav **Top 10** item is renamed **Request**; the `sect-top10` card is repurposed:
- Header: **Request** + `Jellyseerr` badge + **show this tab** toggle (reuses the existing reveal handler).
- **Allow this user to request** toggle (off = browse & see status only; admins always may; approvals in Seerr).
- **Rows shown in Request** — a draggable `.cfg-row` list, each row a **Seerr discover feed** with a type badge
  (MOV/TV/MIX), a name, and a `discover/...` source line; per-row **show/hide** toggle + remove. Seeded example:
  Trending · Popular Movies · Action Movies · A24 · HBO Originals · Anime · Upcoming Movies (hidden).
- **＋ Add row** — an opaque popover (`.req-addmenu`, `--fill` surface) listing the endpoint catalogue, grouped:
  **Movies** (Discover / by genre / by original language / by studio / Upcoming) · **TV** (Discover / by genre /
  by original language / by network / Upcoming) · **Mixed** (Trending).
- The retired chart IIFEs no-op safely (their `#top10list`/`#t10-*` elements are gone).

## Requirements
### A. Request section (per user)
1. Per-user **show Request tab** toggle and **allow-request** permission toggle.
2. A **reorderable list** of Seerr discover feeds shown on that user's Request tab; each row toggles
   **show/hide** and can be removed. Order + visibility persist in the user's Ravilo layout.

### B. Add-row endpoint catalogue
3. **＋ Add row** offers every Seerr discover endpoint: **Movies** — Discover (popular), by genre, by original
   language, by studio, Upcoming; **TV** — Discover (popular), by genre, by original language, by network,
   Upcoming; **Mixed** — Trending. **Parameterised** feeds (genre / studio / network / language) prompt for the
   value when added.

### C. Wiring
4. Requires the Seerr **connection** (Phase 136). The resulting per-user row set drives the TV **Request** tab
   (R171). Availability/request status is resolved live from Seerr at render time (not stored in the layout).

## Invariants
- Per-user (respects the global-vs-custom layout scope switcher, like other Ravilo-config sections).
- The Add-row popover is **opaque** (`--fill`, not the translucent card token).
- Facets here are **Seerr discover endpoints**, distinct from the library workbench facets.

## Out of scope
- The Seerr **connection** (Phase 136) and the TV **Request** tab (R171).
- A live-preview of the Request tab inside the editor (possible follow-up).
- Bulk import / templates of feed sets across users.

## Source references
- Design: `design/app/ravilo-config.html` (nav "Request", repurposed `#sect-top10` card, `.cfg-row` rows,
  `.req-addmenu` picker); `design/app/wf.css` (`.cfg-row`, `--fill` popover surface).
- Related: **[Phase 136](phase-136-seerr-connection-retire-charts.md)** (connection),
  **[R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)** (consumer), retired **R50** (Top-10 config).

---

## Dev-review addenda (2026-07-04 — backend design, verified against code)

> The design mock is accepted; this makes the per-user data model + editor wiring concrete. Depends on
> [Phase 136](phase-136-seerr-connection-retire-charts.md) §A/§D (the `[seerr]` connection + `SeerrClient`).

### A. Per-user model — repurpose `RaviloConfig.discover`, don't add a parallel record
The per-user Top-10 config already lives at **`RaviloConfig.discover: DiscoverConfig`**
(`shared/.../tv/Models.kt:540`), stored in the R51 per-user layout override (one JSON blob per `user_id` in the
`ravilo_config` SQLite table; `__global__` sentinel for global — `RaviloConfigService.kt:24`). Rework the type,
keep the slot:
- `DiscoverConfig` (`Models.kt:598-609`) today: `enabled`, `@SerialName("can_request") canRequest`, `source`,
  `sources`, `region`, `lists: List<String>` (ordered `ChartListSpec` ids). **Replace `lists` (and the dead
  chart fields `source`/`sources`/`region`) with `feeds: List<SeerrFeed>`.** Keep `enabled` (show-the-tab) and
  `canRequest` (allow-request permission). Optionally rename `DiscoverConfig` → `RequestConfig` and the field
  `RaviloConfig.discover` → `.request` (cosmetic; `renderDiscover` etc. rename with it — weigh churn vs clarity).
- **New `data class SeerrFeed`** (`shared/.../tv/Models.kt`): `id: String`, `kind: SeerrFeedKind` (`MOVIE|TV|MIXED`
  → the MOV/TV/MIX badge), `endpoint: SeerrDiscoverEndpoint` (enum of the §B catalogue), `param: String? = null`
  (the genre id / studio id / network id / ISO-639-1 language for parameterised feeds), `name: String` (display
  label, e.g. "A24", "Anime"), `visible: Boolean = true`. Order is list order; `visible` is the per-row show/hide.
- **Resolution is unchanged** — `RaviloConfigService.getConfig(userId)` (`:57`) returns the per-user override
  else global; the R162 behaviour overlay is separate and untouched. (The pre-R162 "first viewer write forks the
  whole layout" trap is **already fixed** — `applyViewerSettings` `:184` now writes only the behaviour table.)

### B. The Add-row endpoint catalogue (`SeerrDiscoverEndpoint`)
Enum + the Seerr path each maps to (Phase 136 §D0), grouped as the mock's popover:
- **Movies:** `MOVIES_POPULAR` → `/discover/movies` · `MOVIES_GENRE` → `/discover/movies/genre/{param}` ·
  `MOVIES_LANGUAGE` → `/discover/movies/language/{param}` · `MOVIES_STUDIO` → `/discover/movies/studio/{param}` ·
  `MOVIES_UPCOMING` → `/discover/movies/upcoming`.
- **TV:** `TV_POPULAR` → `/discover/tv` · `TV_GENRE` → `/discover/tv/genre/{param}` · `TV_LANGUAGE` →
  `/discover/tv/language/{param}` · `TV_NETWORK` → `/discover/tv/network/{param}` · `TV_UPCOMING` →
  `/discover/tv/upcoming`.
- **Mixed:** `TRENDING` → `/discover/trending`.
- **Parameterised feeds prompt for `param` when added.** Genre picker uses the Seerr genre catalogue
  (`GET /api/v1/genres/movie`, `/genres/tv`) surfaced through jellystructure (a small `GET /api/tv/admin/seerr/genres`
  proxy — reuses `SeerrClient`). Studio / network are **TMDB ids** — offer a name search
  (`GET /search` company/network) or accept a raw id + display name (mock shows free-typed "A24" / "HBO"). Language
  is an ISO-639-1 code from the existing language picker.

### C. Editor wiring (reuse the existing config load/save path)
- **Save/load unchanged:** the editor still `PUT`s the whole `RaviloConfig` to **`/api/tv/admin/config`**
  (`TvRoutes.kt:533` → `RaviloConfigService.save` `:93` → `validate` `:138` → `normalize` `:111` → upsert +
  `notifyConfigChanged`); load via `GET /api/tv/admin/config` (`:489`, `AdminConfigEnvelope{config, hasOverride,
  isGlobal}`). Scope switch (`?scope=global` / `?userId=`) already handled `:493-496`. **No new persistence** —
  `feeds` ride inside the existing per-user layout blob.
- **wasm editor:** rebuild `ui/RaviloConfig.kt` `renderDiscover(container)` (`:1991`) into the Request section
  (draggable `.cfg-row` feeds + `.req-addmenu` popover from the mock `design/app/ravilo-config.html:205-243`);
  relabel the nav item `data-rav-sect="sect-discover"` → "Request" (`:249`). The retired chart list-picker
  (`RaviloApi.getDiscoverLists`/`getDiscoverCoverage`, backed by the now-deleted `ChartRoutes`) is dropped.
- **Validation:** extend `RaviloConfigService.validate` (`:138`) — a parameterised feed must carry a non-blank
  `param`; unknown `endpoint`/`kind` rejected. `normalize()` (`:111`) assigns/dedupes feed ids + order.

### D. What this does NOT do
- Doesn't fetch feed *contents* — the editor stores which feeds + order; the TV resolves availability/tiles live
  from Seerr at render (R171). No feed data in the layout blob.
- Doesn't touch the R162 behaviour overlay, the hero/channels/content-rows config, or global-vs-custom scope
  mechanics — only the one `discover`→`request` slot changes.

**Implementation note (2026-07-04):** shipped per the design above, with one naming simplification —
`RaviloConfig.discover` **kept its field name** (didn't rename to `.request`) since it's a persisted
per-user JSON blob key; renaming would only add churn across the wasmJs editor without any migration
benefit (old chart-list selections are being invalidated either way — the referenced chart list ids no
longer resolve to anything after Phase 136). `DiscoverConfig`'s *shape* changed as designed:
`enabled`/`canRequest` kept, `lists`/`source`/`sources`/`region` replaced by `feeds: List<SeerrFeed>`.
`SeerrDiscoverEndpoint.needsParam` lives on the shared enum itself (not a separate lookup table) so the
editor's add-row UI and `RaviloConfigService.validate()` can't drift apart on which endpoints are
parameterised. Also removed the now-dead `RaviloApi.getDiscoverLists`/`getDiscoverCoverage` client calls
(Phase 136 deleted their backend routes) that the pre-137 editor was still calling.

### Source references (backend anchors)
- Model: `shared/.../tv/Models.kt:540` (`RaviloConfig.discover`), `:598-609` (`DiscoverConfig`).
- Service: `tv/RaviloConfigService.kt:57/93/111/138/184`, sentinel `:24`; DB `db/RaviloConfig.sq`.
- Routes: `server/routes/TvRoutes.kt:489/533` (`/api/tv/admin/config` GET/PUT), scope `:493-496`.
- Editor: `ui/RaviloConfig.kt:249/1991`; client `api/RaviloApi.kt:47/88/96`. Mock: `design/app/ravilo-config.html:137/205-243`.
- Seerr endpoints: [Phase 136](phase-136-seerr-connection-retire-charts.md) §D0.
