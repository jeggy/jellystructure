// R189 — bakes the jellystructure backend's base URL into the bundle at build time (Tizen apps have no
// dev-server proxy concept once packaged as a .wgt; the TV talks to the backend directly over the LAN).
// Override via `-PraviloTizenBaseUrl=http://<host>:9505` on the Gradle command line, or the
// RAVILO_TIZEN_BASE_URL env var. The localhost default only works for a browser-hosted dev build;
// a packaged .wgt on a TV must be given the backend's LAN address explicitly.
const webpack = require('webpack');
const baseUrl = process.env.RAVILO_TIZEN_BASE_URL || 'http://localhost:9505';
config.plugins.push(
    new webpack.DefinePlugin({
        RAVILO_BASE_URL: JSON.stringify(baseUrl),
    })
);
