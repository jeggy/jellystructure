// R156: the Kotlin/Wasm toolchain emits a dynamic require(mod) shim in the module's
// *.import-object.mjs (kotlinx.io.node.requireModule) that webpack flags as
// "Critical dependency: the request of a dependency is an expression". It's a harmless
// warning (tracked as KT-86192, fixed only in Kotlin 2.4.20 — this repo is on 2.3.x), but
// webpack-dev-server's overlay shows it fullscreen on every load, reading like a crash.
//
// This file is named to sort alphabetically AFTER dev-server.js so it can safely merge
// into config.devServer.client without dev-server.js's own assignment clobbering it.
config.ignoreWarnings = (config.ignoreWarnings || []).concat([
    /Critical dependency: the request of a dependency is an expression/,
]);
config.devServer = config.devServer || {};
config.devServer.client = Object.assign({}, config.devServer.client, {
    overlay: { errors: true, warnings: false, runtimeErrors: true },
});

// TODO: once this repo upgrades to Kotlin 2.4.20+, delete the ignoreWarnings entry above
// and verify the warning is gone at the source (KT-86192). Keep the overlay setting.
