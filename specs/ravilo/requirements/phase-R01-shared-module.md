# Phase R01 — `:shared` KMP module: DTOs + `TvApiClient` (FR-RV1)

**Status:** Planned · _foundation; everything else depends on this._

## Problem
Ravilo (Android TV + Web) and the jellystructure backend must agree on the wire format for the
`/api/tv/**` API. Defining DTOs three times (server, web client, TV client) guarantees drift. We need
**one** Kotlin source of truth for the models and a typed client, shared by all consumers.

## Current state (as-is)
- The backend defines its own `@Serializable` models in `commonMain`/server code; the WASM admin
  frontend consumes the existing `/api/**` surface.
- There is no `/api/tv/**` API and no shared client. No Android or Compose targets exist yet.

## Requirements

### Module
1. Add a KMP module **`:shared`** with `commonMain` and targets **`wasmJs`**, **`androidTarget`**, and
   the **backend host target** the server already builds. No platform-UI dependencies (no Compose, no
   `kotlinx.browser`, no Ktor **server**). Allowed deps: `kotlinx.serialization`,
   `kotlinx.coroutines`, **Ktor client** core.
2. The backend and the admin frontend keep compiling; `:shared` is **additive**. Where a model already
   exists for the admin API and is reused verbatim by `/api/tv`, prefer **moving it into `:shared`**
   and re-exporting, rather than duplicating (no parallel `MediaItem`).

### DTOs (defined once, in `commonMain`)
3. All `/api/tv` DTOs from [`../plan.md` §2](../plan.md): `TvSession`, `PairingChallenge`,
   `StreamTicket`, `HomeFeed`/`Hero`/`Channel`/`Row`/`MediaCard`, `MovieDetail`/`SeriesDetail`/
   `Season`/`Episode`/`PlaybackState`, `SearchResults`, `RaviloConfig`. All `@Serializable`, all using
   shared enums (`MediaKind`, `RowKind`, `ChannelStyle`, `TileShape`, `Skin`).
4. Reuse shared **language value types** already used by jellystructure where relevant (e.g. language
   codes), rather than raw strings, so resolution semantics match.

### API client
5. A coroutine **`TvApiClient`** built on the Ktor client: one `suspend fun` per `/api/tv` endpoint
   (R03–R08 surface), returning the DTOs above; takes a base URL + a device-token provider; sets the
   auth header automatically; centralised JSON config + error mapping (`TvApiError` with HTTP status).
6. The client is **transport-only** — no caching, no UI. (Stores in `:ravilo-ui` own caching/state.)

## Invariants
- **DTOs defined once** in `:shared`; backend + both clients consume them — no duplicate models.
- `:shared` never depends on platform UI or a server engine; platform/server modules depend on it.
- JSON contract is identical on both ends because both serialize the same classes.

## Out of scope
- The actual route implementations (R03–R08) and any UI (R02, R09+).
- Caching / offline (client is transport-only).
