# Vendored third-party assets

Phase 235 (FR-235-9) self-hosts the player libraries the wasm player loads on demand
(`ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerWasm.kt`), which used to
load them from `cdn.jsdelivr.net` — blocked by the corrected `script-src 'self'` CSP (phase 235's own
§Dev review item 3). Fetched unmodified, same versions the code already pinned:

| File | Source | License |
|---|---|---|
| `hls.min.js` | `hls.js@1.5.13` — `cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js` | Apache-2.0 |
| `jassub.umd.js` | `jassub@1.7.0` — `.../dist/jassub.umd.js` | LGPL-2.1-or-later AND (FTL OR GPL-2.0-or-later) AND MIT AND MIT-Modern-Variant AND ISC AND NTP AND Zlib AND BSL-1.0 |
| `jassub-worker.js` | `jassub@1.7.0` — `.../dist/jassub-worker.js` | (same as above) |
| `jassub-worker.wasm` | `jassub@1.7.0` — `.../dist/jassub-worker.wasm` | (same as above) |
| `default.woff2` | `jassub@1.7.0` — `.../dist/default.woff2` (Liberation Sans, JASSUB's fallback font, resolved as `./default.woff2` relative to its own script — kept as a sibling of the two `jassub-*` files above for that reason) | SIL Open Font License (bundled by JASSUB) |

Not vendored (present in the upstream `dist/` but unreferenced by this app): `jassub-worker-modern.wasm`,
`jassub-worker.wasm.js` (an asm.js fallback for browsers with no WebAssembly support — irrelevant to a
WebAssembly-only Compose app), `jassub.es.js`, `COPYRIGHT`, `README.md`, `LICENSE`, `package.json`.

Committed as plain binary files, not fetched by a build step — a reproducible build should not depend on
a CDN being reachable at build time (the same reasoning phase 167 applied to the self-hosted admin
fonts). Re-fetch with the exact URLs above if these ever need a version bump.
