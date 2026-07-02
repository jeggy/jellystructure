# Phase R156 — Ravilo web: kill the fullscreen "Critical dependency" dev overlay (FR-RV-W1)

## Problem
Opening the Ravilo web client (dev server) greets the viewer with a **fullscreen webpack-dev-server
overlay**:

> WARNING in ./kotlin/jellystructure-ravilo-web.import-object.mjs 5542:50-62
> Critical dependency: the request of a dependency is an expression

Dismissing it works and the app is fine — it's a **warning**, not an error — but it fires on every
load and reads like a crash.

## Root cause (researched + verified)
- The Kotlin/Wasm toolchain generates a dynamic `require(mod)` shim inside the module's
  `*.import-object.mjs` (`kotlinx.io.node.requireModule`); webpack flags any expression-argument
  require as "Critical dependency". Tracked as **KT-86192** — fixed only in **Kotlin 2.4.20-Beta1**
  (this repo is on 2.3.x), so suppression is the correct near-term fix.
- This repo has **no `ignoreWarnings` and no overlay config anywhere**: root `webpack.config.d/dev-proxy.js`
  (admin bundle) and `ravilo-web/webpack.config.d/{dev-server.js, skiko.js}` configure proxy/ports only.
  Both dev-server configs **assign** `config.devServer.client = { webSocketURL: … }` — a naive overlay
  snippet added earlier in the merge order would be clobbered.
- The warning also appears (harmlessly, no overlay) in production builds' stats; suppressing it keeps
  CI logs clean too.

## Requirements

### FR-R156-1 — Suppress the known warning
Add a `ravilo-web/webpack.config.d/90-warnings.js` (name sorts after `dev-server.js`):
```js
config.ignoreWarnings = [...(config.ignoreWarnings || []),
  /Critical dependency: the request of a dependency is an expression/];
```
Scoped to this one pattern — other warnings must keep surfacing.

### FR-R156-2 — Overlay shows errors only
In the same file (running after `dev-server.js` so nothing clobbers it), merge — don't replace — the
client config:
```js
config.devServer = config.devServer || {};
config.devServer.client = Object.assign({}, config.devServer.client,
  { overlay: { errors: true, warnings: false, runtimeErrors: true } });
```
Real errors still take over the screen; warnings go to the console only. Apply the same overlay merge to
the **admin** dev server (`webpack.config.d/dev-proxy.js`) for consistency.

### FR-R156-3 — Un-suppress reminder
A comment in the file references **KT-86192 / Kotlin 2.4.20**: when the toolchain upgrade lands, delete
the `ignoreWarnings` entry and verify the warning is gone at the source (the overlay-errors-only setting
stays — it's correct regardless).

## Non-goals
- No Kotlin upgrade in this phase (2.4.20 is pre-stable; tracked separately).
- No blanket warning suppression, no `stats: 'errors-only'`.
- No production-serving changes (`/tv/**` static route untouched).

## Acceptance
- `ravilo-web` dev server boots to the app directly — no fullscreen overlay; the warning is absent from
  the webpack output.
- Introducing a real compile error still shows the overlay.
- Admin dev server behaves the same; production build logs are free of the warning.
