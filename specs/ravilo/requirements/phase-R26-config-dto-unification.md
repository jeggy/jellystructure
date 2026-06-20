# Phase R26 — Ravilo config DTO unification & round-trip correctness (FR-RV26)

**Status:** Done · _correctness fix — the admin Ravilo config editor now round-trips through the
shared DTO. Compiles on both targets; end-to-end runtime verification pending._

## Problem

The jellystructure admin **Ravilo TV** config screen (`/ravilo`,
`src/wasmJsMain/.../ui/RaviloConfig.kt`) defines a **parallel DTO family** — `AdminRaviloConfig`,
`AdminHeroConfig`, `AdminChannelConfig`, `AdminRowConfig` (`src/wasmJsMain/.../api/RaviloApi.kt`) —
whose field names, shapes, and enum sets diverge from the **shared** `RaviloConfig` family
(`shared/.../tv/Models.kt`) that the backend persists (`tv/RaviloConfigService.kt`) and the TV reads
(`GET /api/tv/config`). The editor therefore cannot round-trip the layout with the store it is meant
to edit. This violates **Constitution Invariant 2** ("DTOs are defined once in `:shared` and reused
by backend, web frontend, and TV") and the **R16 invariant** ("writes the same per-user store the TV
reads").

### Field divergence

| Entity | Admin frontend sends | Shared store expects |
|---|---|---|
| Channel | `label, kind(GENRE/STUDIO/NETWORK/TAG), filter, logo_url, color` | `id*, name, style(LOGO/TEXT), brand_color, filter_network/studio/genre/tag, enabled, order` |
| Row | `label, kind(GENRE/STUDIO/NETWORK/TAG/CONTINUE_WATCHING/NEXT_UP/NEWLY_ADDED), filter, hidden` | `id*, kind: RowKind(CONTINUE/NEWLY_ADDED/GENRE/CUSTOM), title, enabled, order, media_kind` |
| Hero | `item_id, item_title` | `item_id, override` |
| tile_shape | `POSTER/THUMB/SQUARE` (String) | `TileShape(POSTER/LANDSCAPE)` |

`*` = required field with no default.

## Current state (as-is)

Both server and client use `Json { ignoreUnknownKeys = true }` **without** `coerceInputValues`, so
unknown keys drop silently but **missing required fields and invalid enum values still throw**.

- **Save is broken.** `PUT /tv/admin/config` does `call.receive<RaviloConfig>()`
  (`TvRoutes.kt:280-287`). With ≥1 channel or row, the shared `id` field is absent →
  `MissingFieldException`; admin row `kind` values such as `STUDIO`/`CONTINUE_WATCHING` are not valid
  `RowKind`s → enum failure; a `tile_shape` of `THUMB`/`SQUARE` is not a valid `TileShape`. Any of
  these → **400/500**, so a real layout cannot be persisted.
- **Load is silently wrong.** `GET /tv/admin/config` returns the shared shape; the frontend decodes
  it as `AdminRaviloConfig`, reading `label` (store has `title`), `hidden` (store has `enabled`),
  `color`/`filter` (store has `brand_color`/`filter_*`). Existing rows render with **blank labels,
  all-shown state, and empty channel filters/colors** — the stored config is invisible in its own
  editor.

## Requirements

### Frontend (admin app — DOM/Tailwind, page `ravilo`)

0. **Add `:shared` to the admin frontend's `wasmJsMain` dependencies** (root `build.gradle.kts`). It
   is **not** currently a dependency — which is *why* the parallel DTOs were created. `:shared` has a
   `wasmJs` target with only client-safe deps (serialization, coroutines, ktor-client), so this is
   safe.
1. **Delete the `Admin*` DTOs.** Import and use the shared `RaviloConfig`, `HeroConfig`,
   `ChannelConfig`, `RowConfig`, `RowKind`, `Skin`, `TileShape`, `ChannelStyle` directly.
   `RaviloApi.getConfig/putConfig` take/return the shared `RaviloConfig`.
2. **Map the editor form to the correct shared fields:**
   - Row: write `title` (not `label`) and `enabled` (not `hidden`, and note the **inverted**
     boolean — a "Hidden" checkbox sets `enabled = false`).
   - Channel: write `name`, `style` (`ChannelStyle`), `brand_color`, and exactly one typed
     `filter_*` field.
   - Hero: write `item_id` (and `override` where applicable); drop `item_title` (the TV resolves the
     display title from Jellyfin, the store does not carry it).
3. **Generate stable `id`s and contiguous `order`** on add and on reorder; **never** emit a channel
   or row without an `id`. Use a short stable scheme (e.g. `"row-" + epochMillis` or a counter);
   preserve existing `id`s on edit so saves are idempotent.
4. **Channel kind ↔ typed filter.** The editor's single "kind + value" control maps to one of
   `filter_network/studio/genre/tag`; on load, derive the selected kind from whichever `filter_*` is
   non-null. Only one filter is set at a time.
5. **Constrain enums in the UI:** row kind options are exactly `RowKind`; tile-shape options map to
   `TileShape` (`POSTER`/`LANDSCAPE`) — the invalid `THUMB`/`SQUARE` values are removed (the richer
   tile-shape control is R28).

### Backend (jellystructure server)

6. `PUT /tv/admin/config` keeps receiving the shared `RaviloConfig`. **Add validation** before
   `save`: reject blank or duplicate channel/row `id`s, clamp `order` to a contiguous range, and
   return `400` with a JSON `{error}` message rather than letting a malformed body 500. Do **not**
   silently `coerceInputValues` over enums — fail explicitly so a buggy client is visible.
7. `GET /tv/admin/config` already returns the shared config and seeds `DEFAULT_CONFIG`/`DEFAULT_ROWS`
   on first access (`RaviloConfigService.getConfig`); confirm the editor renders those defaults
   correctly once the DTOs are unified.
8. **Auth: exempt `/api/tv/admin/**` from the device-token guard.** `AuthPlugin` requires a TV
   **device token** for everything under `/api/tv/**` except an allowlist. The admin config endpoints
   are operator-facing (jellystructure web app) and are authenticated by the **admin cookie session**,
   so they must fall through to the session branch — otherwise the editor 401s with
   `"Invalid or missing device token"` before the handler runs. Skip the device-token branch when
   `path.startsWith("/api/tv/admin/")`.

## Invariants

- **One DTO family** (`:shared`) across backend, admin frontend, and TV (Constitution Invariant 2).
- **Round-trip:** `GET → edit → PUT → GET` yields an equivalent config; a non-empty channel/row list
  persists without exception.
- The admin write path and the TV read path (`GET /api/tv/config`) share the **same** stored bytes
  (R16 invariant).

## Out of scope

- New layout fields — hero height, auto-advance, per-item enable/order (**R27**).
- Editor UI fidelity — reorder, channel/row pickers, live preview, page chrome (**R28**).
