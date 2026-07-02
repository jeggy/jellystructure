# Phase 107 — Global Discover / Top 10 sources config in Settings (providers · key · regions · refresh)

## Goal
Expose the **global** Discover/Top 10 configuration (`DiscoverFeedConfig` + the Streaming Availability API
key) in **Settings**, so an admin controls **which chart providers are ingested**, for **which countries**,
how often, and can supply the API key the movieofthenight sources need. Per-user Top 10 tabs
([R154](../ravilo/requirements/phase-R154-top10-config-validation.md)) then draw only from what's enabled
here.

## Background
The codebase already implements a **multi-provider** chart system (`providers: List<String>` in
`DiscoverFeedConfig`) across three backends, but Settings exposed none of it:
- **Netflix · Tudum** (`NetflixTudumProvider`) — country + global + non-English + all-time lists.
- **JustWatch** (`JustWatchProvider`, one instance per service) — **Viaplay**, **Paramount+**,
  **SkyShowtime** — country lists only; availability per country comes from JustWatch.
- **movieofthenight.com** (`StreamingAvailabilityProvider`, one per service) — **Max**, **Disney+**,
  **Amazon Prime**, **Apple TV+**, **Crunchyroll** — country lists only; **requires**
  `api_keys.streaming_availability_key` (RapidAPI).

**Verified in code (2026-07-02):** `DiscoverFeedConfig` exists exactly as assumed —
`{ enabled=false, providers=["netflix"], regions=["DK"], refresh_hours=24 }` (`config/AppConfig.kt:50-56`)
with `streaming_availability_key` in `ApiKeys` (`:88`) — and none of it is surfaced in Settings.
Provider ids in the registry: `netflix` (Tudum), `viaplay`/`paramount`/`skyshowtime` (JustWatch),
`max`/`disney`/`prime`/`apple`(+`crunchyroll`) (movieofthenight). Note each provider's
`availableLists(region)` is a **static list template** (it does not verify the region has data) —
per-country *coverage* is R154's server-side addition, not something this phase can read off the
registry.

> **⚠ Migration note (backend):** the `StreamingAvailabilityProvider` currently calls movieofthenight
> **through the RapidAPI proxy** (`https://streaming-availability.p.rapidapi.com`, with
> `X-RapidAPI-Key`/`X-RapidAPI-Host` headers). This should be **changed to call movieofthenight's API
> directly** instead of the RapidAPI (Nokia) proxy — direct auth against movieofthenight.com, dropping the
> RapidAPI host/headers. The Settings key field (A) should carry whichever credential the direct API uses;
> keep the label generic (“Streaming Availability API key”) so the UI doesn't change when the transport does.

## Requirements

### A. Streaming Availability API key (Connections)
Add a `streaming_availability_key` field to **Connections** (with the TMDB/Jellyfin keys), with a note that
it's only needed for the movieofthenight sources (Max/Disney+/Prime/Apple TV+/Crunchyroll); Netflix (Tudum)
and JustWatch sources don't need it.

### B. Discover / Top 10 sources card (Download tools)
1. A master **enable** toggle (`discover.enabled`).
2. A **provider multi-select**, grouped by data source (**Netflix · Tudum** / **JustWatch** /
   **movieofthenight.com**), each provider a selectable chip with its name and the lists it offers. This
   maps to `discover.providers: List<String>`.
3. movieofthenight providers are **gated on the API key**: when the key is blank they show a **"needs key"**
   tag and can't be enabled; supplying the key (A) unlocks them live.
4. A **country multi-select** (`discover.regions: List<String>`) — which country charts to ingest.
5. A **refresh** cadence (`discover.refresh_hours`: daily / every 3 days / weekly). Feeds are week-gated,
   so weekly is the sensible default for **new** configs — note the current backend default is
   `refresh_hours = 24` (daily); existing configs keep whatever value they carry.

### C. Consumed by the per-user picker
The per-user Top 10 editor (R154) offers only globally-enabled providers and ingested regions, and flags
mismatches (e.g. a country not ingested, a provider with no chart for the country).

## Scope
- `design/app/settings.html` — Connections key field; the Discover/Top 10 card (provider groups, key
  gating, regions, refresh). **Built.**
- Backend/WASM: surface `DiscoverFeedConfig` + `streaming_availability_key` in `Settings.kt`/config API;
  the provider registry already exists (`ChartRegistry`, `NetflixTudumProvider`, `JustWatchProvider`,
  `StreamingAvailabilityProvider`).

### Design reference (already built)
`design/app/settings.html`: the `PROVIDERS`/`GROUPS`/`REGIONS` model, `.prov-chip` grouped multi-select,
`#sa-key` gating the movieofthenight group, region chips, and the refresh select.

## Non-goals
- No new chart providers — this wires up the ones already implemented.
- No per-user overrides here (that's R154, per-user); this is the global source-of-truth.
- No live vendor probe — availability is provider metadata, refreshed by the backend.
- The **RapidAPI → movieofthenight direct** migration (see the Migration note) is **backend transport**;
  this phase's UI is unaffected by it and shouldn't block on it.
