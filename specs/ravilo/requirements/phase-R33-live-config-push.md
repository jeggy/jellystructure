# Phase R33 — Live config push: instant Ravilo layout updates on the TV

**Status:** Planned · _when a viewer's RaviloConfig changes (operator edit in the Jellystructure config
screen, or the viewer's own on-device settings), every connected TV/web client signed in as that user
reflects the change within ~1s — no manual reload — while still rendering only server-composed state._

> Builds on the per-user config store **[R04](phase-R04-per-user-config-store.md)** /
> **[R26](phase-R26-config-dto-unification.md)**, the home-feed composition
> **[R05](phase-R05-home-feed-api.md)**, the device-pairing auth **[R03](phase-R03-device-pairing-auth.md)**,
> and the R32 config editor. It adds a push channel; it does **not** add any new way to mutate state.

## Problem
The TV pulls its layout once: on launch the client calls `GET /api/tv/home` + `GET /api/tv/config` and
renders. If an operator opens the Jellystructure **Ravilo config** screen and adds a channel, drags a row,
changes the hero height, tile shape, auto-advance, or skin for that user, **the viewer sees nothing until
they manually leave and re-enter the app** (or it cold-starts). There is no live link from a config write
to the viewer's screen.

## Goal
A config change for user X **propagates to X's connected devices in ~1s, automatically**, and the visible
surface updates in place (a new channel slides into the rail; the hero resizes; rows reorder) **without a
loading flash and without losing scroll/focus**. The viewer never sees a half-applied or optimistic state —
the client always re-pulls the authoritative, server-composed feed on the signal.

## Current state (as-is)
- **Single write point:** `RaviloConfigService.save(userId, config)` (SQLite upsert, runs `normalize()`).
  Called by admin `PUT /api/tv/admin/config` and viewer `PUT /api/tv/settings` (`applyViewerSettings`).
- **Pull-only data plane:** `GET /api/tv/home`, `/api/tv/channel/{id}`, `/api/tv/config`. `HomeFeed`
  already carries `heroes/channels/rows` + `heroHeightPct/autoAdvanceSeconds/tileShape`; `RaviloConfig`
  carries skin etc. So a single feed re-pull refreshes everything visible on Home.
- **Client:** `TvApiClient` (`:shared`) is REST-only — no socket. `HomeStore`/`ChannelStore` hold a
  `StateFlow<HomeState>`; `load()/refresh()` re-pull; **`load()` flips to `HomeState.Loading` first**
  (would blank the screen). `RaviloApp.refreshConfig()` re-pulls config + applies skin. Pairing already
  runs a polling loop, so async patterns exist but there is no persistent connection.
- **Auth:** `/api/tv/**` (non-admin) requires `Authorization: Bearer <deviceToken>`; `DeviceData` has
  `jellyfinUserId`. Admin endpoints use the session cookie. Ktor `WebSockets` is installed; the only
  socket is the admin **`/ws`** (global, unauthenticated, JobEvents) — not a model to copy directly.
- **Constitution:** control plane = jellystructure; the TV renders **server-composed layout + server-pushed
  state**; Ravilo never mutates the library.

## Requirements

### A. Per-user event channel (backend)
1. A `TvEventBus` maintains live device WebSocket sessions **keyed by `jellyfinUserId`** (a user may have
   several devices; a TV hosts several users but only the **active** session connects). Register on
   connect, unregister on disconnect/close; mutex-guarded like `jobs/WsBroadcaster`.
2. `notify(userId, event)` fan-outs a JSON event to all of that user's sessions (no-op if none connected).
3. Dead-socket hygiene: heartbeat ping/pong; drop sessions whose send fails.

### B. WebSocket route + auth that works on Android **and** browser
1. New route `GET /api/tv/events` upgrades to a WebSocket, validates the device, resolves its
   `jellyfinUserId`, and registers the session on the bus under that user.
2. Browsers cannot set an `Authorization` header on a WS handshake, so the device token is accepted via a
   **query parameter** (`?token=…`) — and/or a `Sec-WebSocket-Protocol` subprotocol — in addition to the
   bearer header (Android). The handshake validates via `RaviloDeviceService.validateDeviceToken`;
   missing/invalid ⇒ close with a policy code. **The token must never be logged.**
3. The route must sit **outside** the bearer-header `AuthPlugin` gate (which 401s a header-less browser
   handshake) and perform its own token check.

### C. Change events
1. `RaviloConfigService.save(userId, …)` emits a **`config_changed`** event to that user's bus channel
   **after the write commits** (post-normalize). This single hook covers admin edits *and* viewer settings.
2. The event is a **small signal, not the payload**: `{ "type": "config_changed", "rev": <monotonic> }`.
   The client treats it as "your authoritative layout changed — re-pull." (Keeps one source of truth and
   avoids divergent push/pull state; an optional payload push is §F.)

### D. Client subscription + apply (`:shared` + `:ravilo-ui`)
1. After pairing/sign-in the client opens `/api/tv/events` and keeps it open for the **active session**.
   On `config_changed` it refreshes the **currently-visible surface**: `HomeStore.refresh()` on Home,
   `ChannelStore.refresh()` in a channel view, **plus** `RaviloApp.refreshConfig()` (skin / global). Detail
   & Player don't depend on layout — they may ignore the event and pick up changes on the next Home visit.
2. **Flicker-free refresh:** add a `refresh(silent = true)` that does **not** drop to `HomeState.Loading`
   — keep the current `Loaded` feed on screen and swap in the new feed when it arrives. Compose diffs
   rows/cards by `id`/key, so an added channel/row animates in and hero-height / tile-shape / auto-advance
   update in place **without resetting scroll or focus**.
3. The socket client lives in `:shared` (a `TvEventClient` beside `TvApiClient`, using the same injected
   Ktor `HttpClient`/engine) exposing a `Flow<TvEvent>`, reused by Android and Web.

### E. Resilience
1. Reconnect with capped backoff on drop. **On every (re)connect, do one full refresh** to catch anything
   missed while disconnected (also covers cold start).
2. The `rev` counter lets the client ignore redundant events and detect gaps (if `rev` jumps, just
   refresh — it is idempotent).
3. If the socket can't be established (e.g. a reverse proxy that won't upgrade), **degrade to a
   low-frequency poll** of a cheap `GET /api/tv/config/rev` rather than failing — the feature is additive.

### F. Optional later — payload push
A future optimization may embed the recomposed `HomeFeed`/`RaviloConfig` directly in the event to drop the
re-pull round-trip. **Deferred:** it duplicates feed composition on the push path and weakens the single
authoritative-pull model; revisit only if round-trip latency is visibly poor.

## Invariants
- **The TV renders server-pushed/composed state only** — the client never optimistically applies the
  operator's edit; it re-pulls the authoritative feed/config on the signal (constitution).
- **Per-user scoping** — an edit to user X reaches only X's connected devices; other users (same TV or
  elsewhere) are unaffected. Multi-user TVs subscribe under the **active** session's user and **re-subscribe
  on profile switch** ([R18](phase-R18-multi-user-profiles.md)).
- Writes still go through the **server-owned per-user store** (R04/R26); the event is a notification, not a
  new mutation path. Ravilo stays read-only on the library.

## Out of scope
- Live push of **library/scan** changes (new media appearing) to the TV — a separate concern; this phase is
  operator/viewer **config** changes only.
- The admin-frontend live preview already re-renders locally (no change needed); an optional "viewer's TV
  updated ✓" confirmation in the editor is a nice-to-have, not required.
- Multi-admin collaborative editing / config conflict resolution.

## Acceptance
- On Home, an operator **adding a channel** in the config screen shows the new channel in the rail within
  ~1s, no manual reload, scroll/focus preserved.
- **Hero height / tile shape / auto-advance** changes reflect live on Home.
- **Reordering / hiding rows** and **skin** changes reflect live.
- Only the **targeted user's** devices update.
- **Disconnect → reconnect** re-syncs to the latest layout.

## Design reference / implementation pointers
- Backend: emit at `RaviloConfigService.save`; new `TvEventBus` (mirror `jobs/WsBroadcaster`, keyed by
  `jellyfinUserId`); `webSocket("/api/tv/events")` in `Server.kt` (alongside the admin `/ws`), with
  token-in-query auth via `RaviloDeviceService.validateDeviceToken`; optional `GET /api/tv/config/rev`.
- Shared: `TvEventClient` next to `TvApiClient` (Ktor `WebSockets` client) → `Flow<TvEvent>`.
- Client: `HomeStore.refresh(silent=true)` (+ `ChannelStore` equivalent), `RaviloApp` owns the subscription
  for the active session and the reconnect/backoff + refresh-on-connect loop; `RaviloApp.refreshConfig()`
  already applies skin.
