// The compiled wasm output imports './skiko.mjs' relative to the kotlin/ dir,
// but the file lives in the skiko npm package. Redirect the request using
// NormalModuleReplacementPlugin so both the bundler and devServer can find it.
const path = require('path');
const skikoDir = path.resolve(__dirname, "../../node_modules/skiko-js-wasm-runtime");
const skikoMjs = path.join(skikoDir, "skiko.mjs");

// Resolve the import for the webpack bundler
const webpack = require('webpack');
config.plugins.push(
    new webpack.NormalModuleReplacementPlugin(/skiko\.mjs$/, skikoMjs)
);

// Also serve the directory (skiko.wasm is fetched at runtime, not bundled)
config.devServer = config.devServer || {};
config.devServer.static = config.devServer.static || [];
config.devServer.static.push({ directory: skikoDir, watch: false });
