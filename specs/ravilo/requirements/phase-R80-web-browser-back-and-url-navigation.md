# Phase R80 — Ravilo web: browser Back button + URL navigation

> Make the browser's Back button pop the Ravilo navigation stack (it currently exits the app), and
> reflect the current screen in the address bar so navigation is URL-addressable and deep-linkable —
> matching the Back behaviour that already works on Android and the phone.

## Problem
In the browser build (`ravilo-web`), two coupled things are broken:
1. **The browser Back button does nothing useful.** It exits the SPA instead of popping the Ravilo
   stack. Back works on Android/TV/phone because the system delivers a `Key.Back` **key event** that
   Ravilo's root `onKeyEvent` catches — but a browser Back button fires a `popstate` event, never a
   keydown, and nothing in Ravilo listens for it.
2. **The URL never changes.** Navigation is pure in-memory Compose state, so the address bar stays
   fixed, there are no history entries to go back through, and no screen is shareable/bookmarkable.

## Goal
On the **web target only**, keep the in-memory `Dest` stack as the source of truth but mirror it into
the browser's history: each navigation writes a URL (hash route), and a browser Back/Forward (or a
`popstate`) drives the Ravilo stack. Deep-linking to a URL at boot seeds the stack. Android and phone
behaviour is untouched.

## Current state (as-is)
- **Hand-rolled in-memory stack** in `ravilo-ui/.../RaviloApp.kt`:
  - `private sealed class Dest` (≈ lines 93–116): `Pairing`, `ProfilePicker`, `Home(displayName)`,
    `ChannelView(channel: Channel, displayName)`, `Browse(kind: BrowseKind, displayName)`,
    `Search(displayName)`, `Discover(displayName)`, `DiscoverItem(listId, rank, displayName)`,
    `MovieDetail(itemId, displayName)`, `SeriesDetail(itemId, displayName)`,
    `Player(itemId, title, … nextEp…, episodes?, currentEpIndex, displayName)`, `Settings(displayName)`.
    `Dest` is **`private`** to `RaviloApp.kt`.
  - `var stack by remember { mutableStateOf(listOf<Dest>(initialDest)) }` (≈ line 179); current screen
    `= stack.last()`. `fun push(d) { stack = stack + d }`, `fun pop() { if (size>1) drop last }`
    (≈ lines 201–202).
  - **Stack mutations are scattered**: besides `push`/`pop`, several nav lambdas rewrite `stack`
    directly — e.g. tab switches `stack = listOf(Dest.Home(...))` and `stack = stack.dropLast(1) +
    Dest.Browse(...)` (≈ lines 322–327), and `onNavigateToEpisode` replaces the top `Player`
    (≈ lines 448–458).
  - `initialDest` is computed from cached sessions (≈ lines 168–178): none → `Pairing`, one → `Home`,
    many → `ProfilePicker`.
- **Back is commonMain key handling only.** Root `Box.onKeyEvent` in `RaviloApp.kt` (≈ lines 211–216)
  catches `Key.Back`/`Escape`/`Backspace` (KeyDown, `stack.size > 1`) → `pop()`. Plus per-screen
  `onBack → pop()` lambdas and `Modifier.backToTopOnBack` (`focus/BackToTop.kt`) which consumes the
  same keys to scroll-to-top first. **No `BackHandler`, no `OnBackPressedDispatcher`, no
  `expect`/`actual` back seam anywhere** — verified across `ravilo-ui`/`-android`/`-phone`/`-web`.
- **Web entry point** is `ravilo-web/src/wasmJsMain/.../web/Main.kt`:
  ```kotlin
  fun main() { CanvasBasedWindow(title = "Ravilo") { RaviloRoot() } }
  ```
  A Compose-canvas window (not the DOM model the admin app uses). Nothing here or in `ravilo-ui`
  touches `window.location`/`history`/`popstate`/hash. The only `window` read in the whole Ravilo wasm
  tree is `window.location.origin` (for the API base URL) in `RaviloRootActuals.kt`.
- **`kotlinx.browser` is already on the wasm classpath** (used in `seams/RaviloPlayerWasm.kt`), so
  `window.history` / `popstate` / `location.hash` are directly usable from `wasmJsMain`.
- **Existing platform seams** (`RaviloRoot.kt`): `createTvApiClient`, `raviloBaseUrl`, `saveBaseUrl`,
  `TokenStore`, plus player/image seams — **none navigation-related**. A new seam is needed.
- **The admin app is a working hash-router to mirror** (`src/wasmJsMain/.../Router.kt` + `Main.kt`):
  - reads `window.location.hash`, splits `path` vs `?query` (`current()`/`currentPath()`/`currentQuery()`);
  - `navigate(path, query, replace)` → sets `window.location.hash =` (push) or
    `window.history.replaceState(null,"",hash)` (replace);
  - **back hook:** `window.addEventListener("hashchange") { handler(current()) }`;
  - `handleRoute()` decodes `path → screen`, `path tail → id`, `?query → sub-state`
    (e.g. `#/media/<id>?tab=…`, `#/metadata?tab=…`).
  Key difference: the admin router keeps a **single current route** and lets the browser own history;
  Ravilo keeps its **own `List<Dest>` stack** and must keep browser history in **sync** with it.

## Requirements

### A. Dest ⇄ URL mapping
1. Define a canonical hash route per `Dest` and a bidirectional mapping. Proposed routes:
   `#/home` · `#/browse/<movies|series|my-list|all>` · `#/discover` ·
   `#/discover/<listId>/<rank>` · `#/movie/<id>` · `#/series/<id>` · `#/player/<id>` · `#/search` ·
   `#/channel/<channelId>` · `#/settings` · `#/pairing` · `#/profiles`.
2. `displayName` rides on every `Dest` but is the active-profile label — derive it from the active
   session on deep-link rather than encoding it in the URL.
3. **Identity that can't round-trip through a URL must be re-resolved on deep-link**, not lost:
   - `ChannelView` carries a full `Channel` object → URL has only `channelId`; re-fetch the channel
     (or fall back to Home if it can't be resolved).
   - `Player` and `SeriesDetail → Player` carry pre-resolved episode lists / resume context →
     a deep-linked `#/player/<id>` must re-derive these server-side (or open the detail screen instead
     of the bare player). `Search` query text lives in `SearchStore`, not in `Dest`, so it is not part
     of the URL.
   - Where full re-resolution is out of scope, the route must **degrade gracefully** to the nearest
     resolvable screen (e.g. detail instead of player, Home instead of an unknown channel) — never a
     blank screen.

### B. Stack → browser history (web only)
4. Centralize every `stack` mutation behind a small set of functions (`push`, `pop`, and a
   `replaceTop`/`resetTo` for the current inline rewrites) so there is **one** place that can also
   update history. Refactor the scattered inline `stack = …` reassignments (Browse tab switches,
   `onNavigateToEpisode`, pairing reset) to go through these.
5. On the web target, each `push` issues `history.pushState` (or sets `location.hash`) with the new
   `Dest`'s route; each `pop`/reset issues the matching `pushState`/`replaceState` so the browser
   history depth tracks the Ravilo stack depth. Use **replace** (not push) for in-place changes that
   shouldn't add a history entry (e.g. live-filter/tab-equivalent swaps), mirroring the admin router's
   `replace=true` for query updates.

### C. Browser history → stack (the missing Back)
6. Install a `popstate` (or `hashchange`) listener on the web target that maps the new URL back to a
   `Dest` and drives the Ravilo stack — the browser-side counterpart of the existing `Key.Back`
   handler. Browser Back/Forward must move through the same screens as the on-device remote Back.
7. Guard against feedback loops: a stack change that *originated* from a `popstate` must not re-push
   history. (Track an "applying from history" flag, as the admin router effectively does by routing
   all changes through the hash.)

### D. Deep-link / initial route
8. At boot, parse `window.location.hash` in `ravilo-web/.../Main.kt` (or feed it into `RaviloApp`'s
   `initialDest`) and seed the stack from it, re-resolving server-derived context per §A.3. An empty/
   unknown hash falls back to the session-based default (`Pairing`/`Home`/`ProfilePicker`) **and**
   writes the corresponding URL so the address bar is never empty.

### E. Platform isolation
9. Android and phone keep `Key.Back` and have no URL — do **not** regress them. Put the history
   integration behind a **new `expect`/`actual` nav seam** (declared in `commonMain`, e.g. an
   `installHistoryBridge(currentDest, onNavigateToRoute)` / `pushRoute` / `replaceRoute`):
   - **android actual:** no-op.
   - **wasm actual:** the real `kotlinx.browser` history + `popstate` implementation.
   `RaviloApp` calls the seam from `commonMain`; only the wasm actual touches the browser. (Confining
   everything to `wasmJsMain`/`ravilo-web` is acceptable only if `RaviloApp`'s stack functions expose
   a clean hook — the seam is preferred so the wiring lives next to the existing `expect`s in
   `RaviloRoot.kt`.)

## Invariants
- **The in-memory `Dest` stack stays the source of truth.** Browser history is a *mirror*, not the
  owner (unlike the admin app, where the browser owns the single route). All screen rendering still
  flows from `stack.last()`.
- **No regression to on-device Back.** The existing `Key.Back`/`Escape`/`Backspace` handler and
  `backToTopOnBack` must keep working unchanged on every platform.
- Renders server-pushed state only — deep-linking re-fetches from the server; the URL never carries
  client-derived state beyond ids/routing keys.
- `Dest` may need to become non-`private` (or gain a sibling route serializer) — keep the routing
  mapping in one place so a new `Dest` can't silently lack a URL.

## Out of scope
- Adopting a navigation library (Jetpack Navigation / Decompose) — keep the hand-rolled stack.
- Server-side / path-based routing or SSR — hash routing only (matches the admin app; no web-server
  rewrite rules needed).
- Full fidelity deep-linking into mid-playback state (exact resume position, pre-built episode rail) —
  a deep-linked player/detail re-resolves from the server; bit-exact restoration is a later concern.
- URL routing for the Android/phone targets (they have no address bar).

## Source references
- `ravilo-ui/.../RaviloApp.kt` (`Dest`, `stack`, `push`/`pop`, scattered `stack =` rewrites, root
  Back `onKeyEvent`, `initialDest`),
  `ravilo-ui/.../RaviloRoot.kt` + `RaviloRootActuals.kt` (existing `expect`/`actual` seams,
  `window.location.origin` precedent),
  `ravilo-ui/.../focus/BackToTop.kt` + `focus/FocusModifiers.kt` (existing key-based Back paths),
  `ravilo-web/src/wasmJsMain/.../web/Main.kt` (web entry / `CanvasBasedWindow`).
- **Reference pattern (admin hash router):** `src/wasmJsMain/.../Router.kt` (`navigate`, `current`,
  `hashchange` listener), `src/wasmJsMain/.../Main.kt` (`Router.init` + `handleRoute` dispatch),
  `src/wasmJsMain/.../JsInterop.kt` (`historyPushState`/`historyReplaceState`).
- Related: **R40** (instant Back navigation), **R60** (Android phone target — must stay key-based),
  **R45** (entry scroll/focus restore — interacts with deep-link entry focus).
