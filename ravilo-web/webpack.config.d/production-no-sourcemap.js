// FR-167-5 — the production dist (wasmJsBrowserDistribution) was shipping ravilo.js.map (1.7MB), a
// source map exposing internal file/module structure, to a bundle that's about to be served on the
// public internet. Kotlin/JS's generated webpack config apparently still sets a devtool even in
// production mode; this repo has no other webpack.config.d override touching it. Scoped to
// config.mode === 'production' so the dev server (webpack-dev-server, ./gradlew runDev) keeps source
// maps for debugging.
if (config.mode === 'production') {
    config.devtool = false;
}
