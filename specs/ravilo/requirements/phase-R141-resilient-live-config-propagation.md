# Phase R141 — Resilient live config propagation: never need an app restart again

> Hardens **R33** (live config push). R33's mechanism is fully built and
> correct; this phase removes its single-point-of-failure so a config change **always** reaches the TV
> within seconds — even when the one WebSocket can't be established. Interacts with
> **R51** (global vs per-user scope),
> **R40** (retained stores), **R18**
> (profile switch). It adds a fallback + self-heal path; it does **not** add a new way to mutate state.

## Problem
An operator added a **new channel** in the Jellystructure Ravilo config editor. It saved, but the running
TV clients did **not** show it until the app was closed and reopened. The expectation: **any** add/change
in the Ravilo config takes effect on the clients **immediately**, with no close/reopen, ever.

## Findings (R33 is correct — the architecture is fragile)
The whole config-change → client-render path was traced. R33 is **fully wired and working in source**, and
the running backend emits the change event on every save:
- **Save → emit (works):** admin save (`RaviloConfig.kt:334–349` → `RaviloApi.putGlobalConfig/putConfig`)
  → `PUT /tv/admin/config` (`TvRoutes.kt:462–478`) → `RaviloConfigService.save()`
  (`RaviloConfigService.kt:82–94`): SQLite upsert, then emits — `eventBus.notifyGlobalConfigChanged()`
  for `?scope=global`, `notifyConfigChanged(userId)` for per-user. `getConfig()` decodes from SQLite every
  call (no config cache) → reads are fresh immediately after save.
- **Bus + socket (works):** `TvEventBus` (per-user keyed by `jellyfinUserId`; `notifyConfigChanged`
  `:35–42`, `notifyGlobalConfigChanged` broadcast `:49–56`) fans out `{"type":"config_changed","rev":N}`.
  `webSocket("/api/tv/events")` (`Server.kt:265–287`, token via `?token=`, auth-exempt `AuthPlugin.kt:24`,
  30s ping `:145–146`). Wired live in `Main.kt:126–127`.
- **Feed cache (works, not the cause):** `HomeFeedService` `feedCache` keys on
  `cfgHash = config.hashCode()`; `RaviloConfig`/`ChannelConfig` are `data class`es, so adding a channel
  changes the value-hash → cache miss → `buildChannels()` rebuilds with the new channel on the next
  `/api/tv/home`. `FEED_TTL_MS = 5min` is only the Continue-row window and is short-circuited by cfgHash.
- **Client refresh triggers — the fragility:** `HomeStore` is retained (`keptStore`, `RaviloApp.kt:360`);
  `init { load() }` runs **once**. While Home is foregrounded the **only** re-fetch trigger is the R33
  live signal (`HomeScreen.kt:75` `LaunchedEffect(live) { live?.collect { store.refresh(silent=true) } }`).
  There is **no polling, no timer, and no Android ON_RESUME/lifecycle refresh**. Cold start builds a fresh
  store → `load()`. So **if the live signal never arrives, Home never updates until relaunch** — the exact
  reported symptom.
- **The missing fallback:** `GET /api/tv/config/rev` **does not exist** (R33 §E3 "degrade-to-poll" was
  deferred and never built). The entire live-update feature depends on the single `/api/tv/events`
  WebSocket with **no fallback and no self-heal while foregrounded**.

### Root cause
Not a missing feature or a cache bug — the backend broadcasts `config_changed` on every save. The
"updates only after close/reopen" signature means the signal **isn't reaching the TV at runtime** (the
backend is broadcasting to zero/wrong listeners), and because the degrade-to-poll fallback was never
built, that one socket failing silently regresses the whole feature to cold-start-only. The concrete
runtime trigger is one of (identical symptoms):
1. **Stale/незарегистрированный client:** the hand-deployed TV runs a client build whose `/api/tv/events`
   subscription isn't active/establishing — an older sideloaded APK (the repo leads the TV), or a WS
   handshake that fails in the operator's network/proxy path. Backend emits → nobody registered → no
   refresh. `TvEventBus.register` already logs `"TV events: device connected for user <id>"`
   (`TvEventBus.kt:24`) — its **absence** in the backend log while the TV is on Home confirms this.
2. **Scope mismatch (R51):** operator edited the **global** layout while the target viewer has a
   **per-user override**; `notifyGlobalConfigChanged()` does reach the TV, but the re-pulled
   `getConfig(viewerId)` returns the override (no new channel), so nothing visibly changes — and this also
   fails on cold restart, so it only fits if "appears on restart" wasn't verified for that exact user.

## Goal
A Ravilo config change propagates to the affected viewer's foregrounded device **within seconds, every
time**, with no manual reload — and the feature **self-heals** so a single failed/missed WebSocket can
never again degrade it to cold-start-only. Make the failure observable instead of silent.

## Requirements

### A. Degrade-to-poll fallback (R33 §E3 — finally build it)
1. Add `GET /api/tv/config/rev` returning the monotonic rev for the device's resolved config (the counter
   already lives in `TvEventBus`; or derive from the config row's `updated_at`). Cheap, device-token
   auth like the rest of `/api/tv/**`; must reflect **the config the device actually resolves** (global or
   per-user override per R51), so the client can detect a change that applies to it.
2. In `RaviloApp` (`:169–189`), add a **low-frequency poll** (e.g. every 10–15s) that runs whenever the WS
   is **down**, and as a steady safety net: when `rev` has advanced past the last applied rev, emit on
   `liveConfig` (the same SharedFlow the WS feeds) → the visible screen does its existing
   `refresh(silent=true)`. The poll is idempotent and cheap; it is the durable fix the spec prescribed.
3. The poll cadence may back off when the WS is healthy (it's a safety net then) and tighten when the WS is
   known-down, to keep it light.

### B. Foreground / re-entry self-heal (client)
1. Add an **Android lifecycle ON_RESUME** silent refresh and/or a silent `refresh()` on **Home re-entry**
   in `RaviloApp` (around the `Dest.Home` block, `:359–360`), so a missed live signal heals on the next
   foreground **without a full relaunch**. This directly kills the "had to close and reopen" experience
   even if A and the WS both lapse momentarily.
2. The refresh must stay **flicker-free** (R33 §D2 `refresh(silent=true)` — no `Loading` flash, scroll/
   focus preserved). Applies to the currently-visible layout surface (Home / Channel / Settings).

### C. WebSocket reconnect + on-connect resync (verify/harden)
1. Confirm the reconnect-with-backoff loop keyed on `activeUserId` (`RaviloApp.kt:175–189`) and the
   **full refresh on every (re)connect** (R33 §E1) are intact, and that profile switch (R18)
   re-subscribes under the active user. On-connect resync is what catches anything missed while
   disconnected; it must run on the **first** connect too (cold start parity).
2. Ensure send-failure/heartbeat hygiene drops dead sockets (R33 §A3) so a half-open socket doesn't mask a
   live one.

### D. Scope clarity (R51 — make a global edit's reach legible)
1. When the operator edits at **global** scope but the targeted viewer has a **per-user override**, the
   change legitimately doesn't alter that viewer's layout. Surface this in the editor: indicate the active
   **scope** and that a given viewer **resolves to an override** (so "nothing happened on the TV" is
   explained, not mysterious). At minimum, document the resolution rule near the scope switcher.
2. A **new channel added at global scope** must reach all viewers **without** a per-user override
   immediately (A/B/C guarantee this); for override viewers it's correctly a no-op until their own layout
   is edited — make that distinction visible rather than silent.

### E. Observability (turn silent failure into a signal)
1. Keep/clarify the `TvEventBus.register` connect log (`:24`) and add a disconnect log, so an operator can
   confirm a device is subscribed.
2. (Optional) Surface **connected devices + their last-applied rev** in the config editor (a small
   "viewer's TV connected ✓ / updated to rev N" indicator), turning R33's deferred "viewer's TV updated ✓"
   into a real diagnostic. This makes both failure modes (no connection / override scope) self-evident.

### F. Deployment guarantee (operational)
1. The live-update feature only works if the TV runs a client build that **includes R33's `/api/tv/events`
   subscription**. Note in the phase that a deploy must ship a current Ravilo client (the hand-deployed TV
   can lag the repo); after the fallback (A/B) lands, even a transient socket failure no longer breaks
   propagation, but a client **predating R33** would have no subscription at all — verify the deployed
   build is ≥ R33.

## Invariants
- **Server-composed state only** (constitution): the client never optimistically applies the edit — on any
  signal (WS event **or** poll-detected rev bump **or** foreground re-entry) it **re-pulls** the
  authoritative feed/config. The fallback changes *when* we re-pull, never *what* we render.
- **Per-user scoping** (R51/R18): an edit reaches only the devices whose resolved config changed; override
  viewers are unaffected by a global edit by design.
- Writes still go through the server-owned per-user store; the poll/rev endpoint is read-only.

## Out of scope
- Live push of **library/scan** changes (new media) to the TV — R33's existing out-of-scope; this phase is
  **config** propagation only.
- Payload-in-event optimization (R33 §F) — still deferred; the re-pull model is preserved.
- Multi-admin collaborative editing / conflict resolution.

## Acceptance
- An operator **adding a channel** (global scope, viewer with no override) sees it appear on the viewer's
  **foregrounded** Home within seconds — **no close/reopen**.
- With the `/api/tv/events` WebSocket forcibly blocked (e.g. a proxy that won't upgrade), the same change
  still propagates within the poll interval — the feature no longer depends on the single socket.
- A change that arrives while the app was backgrounded is applied on **foreground** without a relaunch.
- Editing **global** while a viewer has a **per-user override** is visibly explained in the editor (the
  viewer correctly doesn't change); editing that viewer's own layout updates their TV live.
- The backend log (or editor indicator) shows whether the target device is **connected** and at which
  **rev**, so a non-propagating case is diagnosable in seconds rather than guessed at.
