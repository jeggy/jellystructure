// Bug fix (2026-08-17, live report) — "RenderNodeContextKt_RenderNodeContext_1nMake is not a
// function" crash on every load, both dev and production. Root cause traced to Kotlin/Wasm's
// generated entry file (<module>.mjs): it does `await WebAssembly.instantiateStreaming(...)` for
// the APP's own wasm, then calls `exports._start()` immediately -- with no wait at all for
// skiko's separately-loaded wasm (skiko.mjs statically imports skiko.wasm and kicks off loading
// async, but nothing in the generated bootstrap ever awaits it). Since skiko.wasm (~8MB) is
// larger than the app's own wasm (~6MB) here, the app routinely finishes loading and calls into
// skiko's still-unset lazy-binding export stubs first -- skiko.mjs's own exports are literally
// `export let X = (...a) => (X = loadedWasm._[X])(...a)`, which throws exactly this "is not a
// function" the instant `loadedWasm._` isn't populated yet.
//
// skiko.mjs already exports a promise for exactly this purpose: `export const awaitSkiko =
// loadSkikoWASM().then(...)`. The fix: patch the generated entry file to import and await it
// immediately before `exports._start()`. This can't be fixed by editing the generated file
// directly (recompiled fresh every build) -- a webpack loader is the right place, matching this
// project's existing webpack.config.d customization pattern. Matched by content, not filename,
// since the generated module name (currently jellystructure-ravilo-web.mjs) is derived from the
// Gradle project coordinates and could change.
// These webpack.config.d/*.js files are concatenated directly into the generated
// webpack.config.js under build/wasm/packages/jellystructure-ravilo-web/ (confirmed by reading
// the actual generated file after a failed build) -- they are NOT require()'d as their own
// modules, so a companion file next to this one in the real source tree is not itself copied
// anywhere and can't be found via a bare relative require.resolve. __dirname here is
// build/wasm/packages/jellystructure-ravilo-web/ (same fact dev-server.js's own comment
// documents), four levels under the project root.
const path = require('path');
config.module = config.module || {};
config.module.rules = config.module.rules || [];
config.module.rules.unshift({
    test: /\.mjs$/,
    exclude: /node_modules/,
    use: [{ loader: path.resolve(__dirname, '../../../../ravilo-web/webpack.config.d/await-skiko-loader.cjs') }],
});
