# Phase R225 — Show the connected server, and let `ravilo-web` default to one via env var

> Requested 2026-09-03, right after redeploying the public demo stack to fix a stale-image bug (R224's
> images had never been re-pulled on the demo host). Root-causing that live confirmed a second, separate
> problem: `ravilo-web` has no way to know it isn't self-hosted alongside its backend.
> `raviloBaseUrl()`'s web actual falls back to `window.location.origin` when no server override is saved
> (`ravilo-ui/src/wasmJsMain/.../RaviloRootActuals.kt`) — correct for the real household deploy (backend
> and `ravilo-web` share an origin there) but wrong for the demo stack, where `ravilo-web` is served from
> `demo.ravilo.jebster.net` and the backend actually lives at `demo.jellystructure.jebster.net`, a
> different origin. The login screen rendered fine (confirmed via a real headless-Chrome load 2026-09-03)
> but was silently pointed at itself — sign-in would have failed against a server with no `/api/tv/**`
> routes at all. The user asked for two things in response: *"a small indicator showing what server is
> currently used"* (so this class of mistake is visible instead of a silent wrong-origin guess), and *"a
> default server via an environment variable... on ravilo-web, but not on tv or mobile devices"*.

**Status:** Planned — spec only, not yet built.

## 1. Server indicator (all platforms)

The connected server's host has never been visible anywhere in the app — a viewer (or, per this phase's own
trigger, a person debugging a deploy) has no way to confirm what `raviloBaseUrl()` actually resolved to
short of reading `localStorage`/`SharedPreferences` directly. Add a small, plain-text, non-interactive line
showing the current server's host on the **login screen**, near the existing `ChangeServerLink` ("Wrong
server?") — that's the one screen every session passes through, and the one place a wrong-server mistake is
otherwise invisible until sign-in fails.

- Format: host only, no scheme (`demo.jellystructure.jebster.net`, not `https://demo.jellystructure.jebster.net`)
  — matches how `ServerSetupScreen` already asks for host separately from the `useHttps` toggle.
- Styling: `colors.textSecondary`, small (`11.sp`), sits below `ChangeServerLink` — clearly secondary to the
  actual sign-in action, never competing with it for focus (not `dpadFocusable`, not part of the D-pad
  traversal order).
- Source: `LoginStore` gains a public `val baseUrl: String` (delegates to its already-held `apiClient.baseUrl`,
  `shared/.../TvApiClient.kt:33` is already public) — `LoginScreen` reads `store.baseUrl` rather than being
  threaded a second parameter that could drift from what `LoginStore` is actually using.
- No new persistence, no new state — this is a read-only reflection of the value `RaviloRoot` already
  resolved before constructing `LoginStore`'s `TvApiClient`.

Non-goal: no indicator elsewhere in the signed-in app (Home, Settings, etc.) — once signed in, the server
choice is no longer ambiguous or actionable (the only way to change it is `ChangeServerLink`, which already
signs the session out), so the login screen is the only point of real doubt.

## 2. `ravilo-web` default server via env var

### FR-R225-1: `DEFAULT_SERVER_URL`, read by `web-static-server`

`web-static-server` (`web-static-server/src/linuxX64Main/.../Main.kt`) already reads real process env vars
at startup (`STATIC_DIR`, `SERVER_PORT` via the existing `env()` helper) — it's the only runtime process in
front of the `ravilo-web` static bundle, and the natural place for this: a compiled wasmJs bundle has no
env access of its own, and there is no backend process to ask (unlike the admin frontend, which is served
*by* the backend it talks to). Add `DEFAULT_SERVER_URL` (optional, unset by default — unset means "no
change to today's behavior").

### FR-R225-2: injected into `index.html` only, at request time

When `DEFAULT_SERVER_URL` is set, `serveStaticFile`'s existing index.html special-case (already `NoCache`,
already read fresh per request — `Main.kt:76-82`) injects one inline script before the `<script
src="ravilo.js">` tag:

```html
<script>window.__RAVILO_DEFAULT_SERVER__="https://demo.jellystructure.jebster.net";</script>
```

Every other static asset (`ravilo.js`, the `.wasm` files, `composeResources/**`) is untouched — they stay
content-addressed and cacheable exactly as today. No new endpoint (a `/config.json` fetch was considered
and rejected — it would add a network round-trip before the app can even resolve its own base URL, and
index.html is already fetched fresh on every load with no caching to fight).

### FR-R225-3: `ravilo-ui`'s wasmJs actual gains a third fallback tier

`RaviloRootActuals.kt`'s `raviloBaseUrl()` becomes a three-tier fallback, in this precedence:

1. **Saved override** (`localStorage['ravilo_base_url']`) — a person explicitly changed server via
   `ChangeServerLink`; that choice must keep winning over any operator-configured default.
2. **`window.__RAVILO_DEFAULT_SERVER__`** (new) — set by FR-R225-2 when the operator configured one.
3. **`window.location.origin`** (existing, unchanged) — the household deploy's implicit "I'm served by my
   own backend" assumption, when neither of the above is present.

`jsGetDefaultServer(): String? = js("window.__RAVILO_DEFAULT_SERVER__")` (returns `undefined`/`null` when
the script tag was never injected — `DEFAULT_SERVER_URL` unset is indistinguishable from "not on web" and
falls straight through to tier 3, unchanged from today).

### FR-R225-4: demo stack adopts it

`~/jellystructure/demo/docker-compose.yml`'s `ravilo-web` service gets
`environment: DEFAULT_SERVER_URL: https://demo.jellystructure.jebster.net` — closing the exact gap this
phase's own trigger investigation found. This is an operational change (compose file + redeploy), not a
code change, and ships alongside this phase rather than as a separate followup.

## 3. Explicit non-goal: TV and phone get no env-var default

`ravilo-android` (TV + phone, merged since R224) has no runtime process in front of it the way
`web-static-server` sits in front of `ravilo-web` — it's a compiled APK with no env var to read at launch,
and no existing precedent in this repo for baking a server URL in at build time (`ravilo-android/build.gradle.kts`
only overrides version fields, per R215/R224 — see that phase's spec). Baking a default server into a
distributed APK would also be actively wrong for the real distribution model: **every household running
Ravilo points at its own backend**, so a single build-time default could never be correct for more than one
installer. Nothing changes here — TV/phone keep resolving straight to `ServerSetupScreen` on first launch,
exactly as today. `DEFAULT_SERVER_URL` only ever reaches the web build.

## 4. Verification

- Unset `DEFAULT_SERVER_URL`: confirm `index.html` is byte-identical to today's (no injected script), and
  `raviloBaseUrl()` still falls back to `window.location.origin` — the household deploy's behavior must not
  change.
- Set `DEFAULT_SERVER_URL` on the demo stack: confirm a fresh browser profile (no `localStorage`) lands
  directly on the login screen (not `ServerSetupScreen`) with the indicator showing
  `demo.jellystructure.jebster.net`, and that a real sign-in against the `demo` viewer account succeeds —
  this was the concrete failure this phase closes.
- Confirm `ChangeServerLink` still overrides the default: pick "Wrong server?", enter a different host,
  confirm the indicator and subsequent sign-in both reflect the override, and confirm it survives a reload
  (localStorage tier still wins over the injected default on tier 2).
