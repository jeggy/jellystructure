// R156: same Kotlin/Wasm-generated require() warning as the Ravilo web target (see
// ravilo-web/webpack.config.d/zz-warnings.js) — suppress it here too and keep the
// dev-server overlay to errors only, for consistency across both wasmJs dev servers.
//
// Named to sort alphabetically AFTER dev-proxy.js so it can safely merge into
// config.devServer.client without dev-proxy.js's own assignment clobbering it.
config.ignoreWarnings = (config.ignoreWarnings || []).concat([
    /Critical dependency: the request of a dependency is an expression/,
]);
config.devServer = config.devServer || {};
config.devServer.client = Object.assign({}, config.devServer.client, {
    overlay: { errors: true, warnings: false, runtimeErrors: true },
});
