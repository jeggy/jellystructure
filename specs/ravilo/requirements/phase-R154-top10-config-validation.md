# Phase R154 — Validate the Top 10 / Discover config + warn in the Ravilo config editor

> Extends **R48–R50** (Discover / Top 10) and **R51** (global vs per-user config), and consumes the global
> Discover config from **[Phase 107](../../requirements/phase-107-discover-sources-settings.md)**. A viewer's
> Top 10 tab silently breaks when the chosen **sources × country × lists** don't line up with what's actually
> ingested (a provider isn't available in that country, the country isn't ingested, Radarr isn't connected,
> no lists remain). Surface those problems **at configuration time**, in the jellystructure Ravilo config
> editor — not as an empty tab on the TV.

## Problem
`app/ravilo-config.html ▸ Top 10` lets an admin pick **Sources** (now **multiple** — Netflix, Viaplay,
Paramount+, SkyShowtime, Max… whatever is enabled globally), a **Country**, and which generated **lists** to
show. Nothing verified the combination:
- A source may publish **no chart for the selected country** (Netflix's Tudum feed covers ~94 countries —
  Denmark yes, the Faroe Islands no; JustWatch/movieofthenight services vary) — pick an unsupported
  country and those lists come back empty.
- The **country isn't ingested** globally (`discover.regions`) — its country lists will always be empty.
- Top 10 requests need **Radarr** connected; if it isn't, requests silently fail.
- No sources selected, or every list unavailable, leaves an **empty** Top 10 tab.

## Current state (verified in code, 2026-07-02) — what this phase must add, precisely
- **The per-user config is single-source.** `DiscoverConfig` (`shared/…/tv/Models.kt:468-474`) carries
  `source: String = "netflix"` + `region` + `lists` — the multi-source model in FR-R154-0 requires a DTO
  migration: add `sources: List<String>` (empty ⇒ fall back to legacy `source`), keep `source` for old
  clients, normalize server-side. (R144 already made the *backend feed* scan all registered providers;
  it's the config/editor that is single-source.)
- **No coverage data exists anywhere.** Every provider's `availableLists(region)` returns a **static
  template for any region** (`NetflixTudumProvider.kt:36`, `JustWatchProvider.kt:66`,
  `StreamingAvailabilityProvider.kt:59`) — it cannot answer "does Viaplay have a DK chart". Actual
  ingested data lives in the `ChartStore` (SQLite `chart_entry` per provider×list×week).
- `GET /api/discover/lists` (`ChartRoutes.kt:19-24`) returns list specs for a region using the global
  providers — but with **no availability/coverage flags**.
- Radarr-connected is knowable (`config.radarr` + the Phase 35 health check); the TV feed already gates
  requests on `discoverAvailable`.

## Requirements

### FR-R154-0 — Multiple sources, drawn from the global config
The **Sources** control is a **multi-select** (not a single dropdown), offering only the providers enabled
in the global Discover config (Phase 107). This requires the `DiscoverConfig.sources: List<String>`
migration described above (legacy `source` folds in on first save). Enabling/disabling a source
**regenerates the list rows**: Netflix contributes its country (movies + TV), global, non-English and
all-time lists; each JustWatch / movieofthenight service contributes its country movies + series lists
("Top 10 Movies on <service>"). The Country dropdown offers the ingested regions.

### FR-R154-1 — Verified/needs-attention banner
Below the Sources/Country fields, show a live status banner: **✓ verified** ("<sources> for <Country> — N
lists shown") when there are no issues, otherwise the list of issues (errors before warnings), each with a
short explanation and, where relevant, a fix link.

### FR-R154-2 — Checks
Validate on load and on every relevant change (sources, country, per-list show toggles, master enable):
1. **Radarr connection** — Top 10 requests require Radarr connected ⇒ error with a "Connect Radarr →"
   deep-link (the tab still *displays* without it; requesting is what breaks).
2. **No sources selected** ⇒ warning (empty tab).
3. **Country not ingested** — the selected country isn't in `discover.regions` ⇒ warning with an
   "Add it in Settings → Discover" link, and every country list is locked.
4. **Provider country coverage** — for each enabled source, if it publishes no chart for the selected
   country (per FR-R154-4's coverage data) ⇒ warning + that source's country lists locked.
5. **Empty result** — a covered source×country whose latest ingest produced 0 entries ⇒ warning.
6. **Empty tab** — no lists shown ⇒ warning.

### FR-R154-3 — Inline markers + lock unavailable lists
The affected **list rows** show an inline warning chip (e.g. "⚠ No chart for Faroe Islands") and a warning
style; the **Country** field shows a warning border + hint when the country is unsupported. Country list
titles reflect the selected country live ("Top 10 Movies in <Country>"). A list that **can't work** for the
current source/country is **locked**: its show toggle is forced **off** and **disabled** (can't be enabled)
for as long as it's unavailable, and is **restored to its previous state** once the config becomes valid
again (e.g. switching the country back to a supported one).

### FR-R154-4 — Provider coverage is data, verified server-side (new backend surface)
Coverage must come **from the jellystructure setup** — the same source of truth the feed builder uses —
via a new endpoint, e.g. `GET /api/discover/coverage?region=CC` returning per enabled provider:
`{ id, displayName, listTypes, covered, reason?, lastIngestWeek?, entryCount }` plus
`{ ingestedRegions, radarrConnected }`. `covered` is determined by, in order:
1. **Ingested data** — the `ChartStore` holds entries for (provider, region) in the latest week ⇒
   covered; ingested-but-empty ⇒ `covered=false, reason="empty_feed"`.
2. **Provider-declared coverage** where cheaply knowable — Netflix: the region appears in the Tudum
   countries TSV's country set (~94 countries, cached from the last ingest); movieofthenight: its
   countries/service endpoint; JustWatch: no declaration → probe.
3. **On-demand probe** — a "Verify now" action on the banner runs a bounded live `availableLists` +
   `fetch` dry-run for the unresolved combos (through `OutboundHttp`), caching the verdict on the
   coverage record.
Never hard-code coverage in the client; the editor renders exactly what this endpoint says, so the
editor's verdict matches runtime behaviour.

## Implementation

| Layer | File | Change |
|---|---|---|
| Shared DTO | `shared/…/tv/Models.kt` | `DiscoverConfig.sources: List<String>` (legacy `source` fallback). |
| Coverage | `chart/` + `ChartRoutes.kt` | Coverage derivation (store-backed + declared + probe) behind `GET /api/discover/coverage`; Netflix country-set capture at ingest. |
| Config editor | `ui/RaviloConfig.kt` (Top 10 section) | Sources multi-select; status banner + per-row warning slots; `validateTop10()` on load + change against the coverage endpoint; lock/restore toggles. |
| Feed (guard) | `HomeFeedService`/discover feed | Already multi-provider (R144); ensure it reads `sources` with the same legacy fallback. |

### Design reference (already built)
`design/app/ravilo-config.html`: `PROVIDERS`/`COUNTRIES` model, `validate()` (readiness · Radarr · country
coverage · list-type coverage · empty), the `#t10-warnings` banner, `.rowwarn` inline chips, the
locked/disabled toggles on unavailable rows, and the Country-field warning. Demo: Netflix + Denmark →
**✓ verified**; switch to **Faroe Islands** → banner + both country rows warn "No chart for Faroe Islands"
and their toggles **lock off** + Country field flagged; back to Denmark → rows unlock and restore.

## Non-goals
- No synchronous vendor probe on every keystroke — validation reads stored coverage; the live probe is
  the explicit "Verify now" action only.
- No auto-fix (it suggests supported countries / hiding lists; the admin chooses).
- No new provider integrations.

## Acceptance
- Netflix+DK shows ✓ verified; switching the country to FO flags both country rows, locks their toggles,
  and warns on the Country field; switching back restores the previous toggle states.
- A source with an ingested-but-empty feed for the country warns distinctly from "not covered".
- Radarr disconnected ⇒ error with a working deep-link into Settings ▸ Download tools.
- The editor never contradicts the TV: any combination the editor marks ✓ produces non-empty rows on the
  TV, and vice versa (same server data).
- Old configs with the legacy single `source` load, validate, and save cleanly as `sources=[source]`.
