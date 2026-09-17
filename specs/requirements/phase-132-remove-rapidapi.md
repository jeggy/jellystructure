# Phase 132 — Remove RapidAPI (streaming-availability Discover provider) end to end (FR-RAPI1)

> jellystructure no longer uses RapidAPI. Remove the one provider that depends on it —
> `StreamingAvailabilityProvider`, which backs the **Max / Disney+ / Amazon Prime / Apple TV+** Top-10
> charts — along with its config key, its Settings UI, and its registry wiring, so RapidAPI is neither
> configurable nor mentioned anywhere, in code **or** in the design mockups. Netflix (Tudum) and
> JustWatch (Viaplay / Paramount+ / SkyShowtime) are independent of RapidAPI and **stay**.

**Status:** ✓ Done — see `STATUS.md`, which is authoritative. (Header as originally written: Planned) — investigated (see reference map below).

## Problem
The Discover / Top-10 feature ships a `StreamingAvailabilityProvider` (added by Phase 107) that calls
`streaming-availability.p.rapidapi.com` (vendor movieofthenight.com) with an `X-RapidAPI-Key`, gated on
a `streaming_availability_key` config field the operator pastes into Settings. We've moved off RapidAPI,
so this provider, its key, and all its UI are dead surface — and it still advertises a paid RapidAPI
signup to the operator.

## What depends on RapidAPI (and what does not)
- **RapidAPI-backed (remove):** `StreamingAvailabilityProvider` → provider ids `max`, `disney`, `prime`,
  `apple` and their per-region list slugs (`max-mov-*` / `max-tv-*` / `disney-*` / `prime-*` / `apple-*`).
- **Independent (keep):** `NetflixTudumProvider` (public Tudum TSVs, no key) and `JustWatchProvider`
  (JustWatch GraphQL, no key — Viaplay / Paramount+ / SkyShowtime). Confirmed by reading both sources.
- `crunchyroll` appears only in the design/spec mockups — it was **never registered** in `Main.kt`, so
  nothing real is lost there (just prune the mock).

## Requirements

### FR-RAPI1-1 — Backend provider + wiring
Remove `StreamingAvailabilityProvider` and its four registrations (`Main.kt:16` import, `:183-186` the
`max`/`disney`/`prime`/`apple` entries) so `ChartRegistry` holds only `NetflixTudumProvider` + the three
`JustWatchProvider`s. **Before deleting `chart/StreamingAvailabilityProvider.kt`, relocate its
`internal fun weekKey()` (`:85-90`)** — it is also called by `JustWatchProvider.kt:60`, so move it to a
shared home (e.g. `ChartProvider.kt` or a small util) or JustWatch won't compile.

### FR-RAPI1-2 — Config field + backward-compat (must not regress config load)
Delete `ApiKeys.streamingAvailabilityKey` (`config/AppConfig.kt:103`) and its frontend mirror
(`api/ConfigApi.kt:84`). **First** make the TOML parser tolerant: set
`Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))` in `config/ConfigStore.kt:28`. Today's
default (`ignoreUnknownNames = false`) throws `UnknownNameException` on an existing config that still
contains `streaming_availability_key`, and the `runCatching` at `ConfigStore.kt:26-30` then **silently
falls back to full defaults, discarding the operator's entire live config**. With the tolerant parser the
stale key is ignored and old configs load intact (this also hardens every future field removal). Scrub
the live secret at `config/config.toml:7`.

### FR-RAPI1-3 — Settings UI (admin frontend)
Remove all RapidAPI surface from `ui/Settings.kt`:
- the "Max · Disney+ · Amazon Prime · Apple TV+" card (`:236-262`) — the "RapidAPI key needed" badge, the
  `#sa-api-key` input, the "how to get a key" guide link/steps, and the four provider toggles;
- drop `max`/`disney`/`prime`/`apple` from `DISCOVER_PROVIDER_IDS` (`:564`);
- delete the whole key-gating path — `DISCOVER_KEYED_PROVIDER_IDS` (`:565-567`),
  `updateDiscoverProviderLocks()` (`:1658-1672`) and its two call sites (`:634`, `:891`);
- remove the `sa-api-key` populate / read / guide lines (`:617`, `:898-902`, `:1153`) and the buildToml
  emit (`:1219-1220`).

Prune the four dead entries from `ui/RaviloConfig.kt`'s `DISCOVER_PROVIDER_NAMES` (`:70-74`).

### FR-RAPI1-4 — Design mockups (same-repo mirror — keep in step)
Remove the RapidAPI surface from `design/app/settings.html` (the `#sa-key` field `:249`, the
movieofthenight intro `:384`, the `max/disney/prime/apple/crunchyroll {needsKey:true}` PROVIDERS
`:1007-1011`, the `movieofthenight.com` GROUP `:1016`, and the `sa-key` JS `:1023`/`:1039`) and the
`max/disney/prime/apple` `via:'movieofthenight'` descriptors in `design/app/ravilo-config.html:705-708`.
Keeping code and mock aligned stops the next "updated designs" sync from re-introducing it.

### FR-RAPI1-5 — Stored chart rows (one-time cleanup)
Existing `ChartStore` rows for the dropped list ids (`max-*`, `disney-*`, `prime-*`, `apple-*`) are not
auto-cleaned — once the providers are unregistered, `ChartIngestService.refresh()` never revisits them,
so they persist as stale orphans a Discover read could still surface. Do a **one-time delete** of those
rows on boot (reuse the existing `ChartStore.deleteList(listId)`), keyed by the removed provider ids.
(Unknown ids left in a stored `[discover] providers` list need no migration — `ChartRegistry.enabled()`
`mapNotNull`s unknown ids away — but scrub `config/config.toml:119` for tidiness.)

## Invariants
- **RapidAPI appears nowhere** after this — no config field, no key input, no provider, no code path, no
  guide link — in code **or** the design mockups.
- **Netflix + JustWatch Discover keep working** unchanged (they never used RapidAPI).
- **No config-load regression:** the tolerant-parser change lands with (or before) the field deletion, so
  an existing `config.toml` carrying the stale key still loads its real values rather than silently
  reverting to defaults.
- `[discover].providers` default stays `["netflix"]`; unknown stored ids are silently skipped.

## Out of scope
- Adding a **replacement** provider for the Max / Disney+ / Prime / Apple lists — they simply go away.
- Any change to the Netflix Tudum / JustWatch providers beyond hosting the relocated `weekKey()`.
- Reworking the Discover config model or the Top-10 UI beyond deleting the four provider chips + gating.

## Source references
- Backend: `chart/StreamingAvailabilityProvider.kt` (whole file; relocate `weekKey()` `:85-90` first),
  `Main.kt:16,183-186`, `config/AppConfig.kt:102-103`, `config/ConfigStore.kt:26-30`,
  `chart/ChartProvider.kt` (`ChartRegistry.enabled` mapNotNull; a possible `weekKey()` home),
  `chart/ChartStore.kt` (`deleteList`), `server/routes/ConfigRoutes.kt:68-91` (the key was returned to the
  browser unmasked — moot after removal).
- Frontend: `ui/Settings.kt` (`:236-262`, `:564-567`, `:617`, `:634`, `:891`, `:898-902`, `:1153`,
  `:1219-1220`, `:1658-1672`), `ui/RaviloConfig.kt:70-74`, `api/ConfigApi.kt:84`.
- Design: `design/app/settings.html` (`:249,384,1007-1011,1016,1023,1039`),
  `design/app/ravilo-config.html:705-708`.
- Data/secret: `config/config.toml:7` (live key — scrub), `:119` (stale providers list).
- Related: **Phase 107** (added the Discover-sources Settings UI + RapidAPI key — this reverses its
  RapidAPI parts), **R154** (Top-10 config validation references `StreamingAvailabilityProvider` — its
  coverage checks / provider chips need the same four ids removed), **Phase 57 / R48–R50** (the
  Discover / Top-10 feature that keeps Netflix + JustWatch).
