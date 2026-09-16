// Dev server config: port, static files, and API/WS proxy to the backend.
// Active when running `./gradlew runDev` or `./gradlew wasmJsBrowserDevelopmentRun`.
config.devServer = config.devServer || {};
config.devServer.port = 8081;
config.devServer.static = [
    { directory: require("path").resolve(__dirname, "kotlin") },
    // Serve wf.css and app.css directly from the canonical design directory.
    // __dirname is build/wasm/packages/jellystructure/ so ../../../../design/app is the design dir.
    { directory: require("path").resolve(__dirname, "../../../../design/app"), publicPath: "/" },
    // Serve flags.css and flags/** from the design root (sibling of design/app/).
    { directory: require("path").resolve(__dirname, "../../../../design"), publicPath: "/" },
];
config.devServer.proxy = [
    {
        context: ["/api"],
        target: "http://localhost:9505",
        // Without this, any WebSocket endpoint nested under /api (/api/tv/events) silently never
        // reaches the backend when accessed through this dev proxy -- the upgrade request just isn't
        // handled, no error either side. Confirmed live (phase 162, since removed by 217): a daemon
        // enrolling over a nested /api WebSocket never showed up as connected backend-side even
        // though it logged "connecting".
        ws: true,
    },
    {
        context: ["/ws"],
        target: "ws://localhost:9505",
        ws: true,
    },
];
// Move webpack-dev-server's own HMR WebSocket off /ws so it doesn't get
// intercepted by the proxy above and misdirected to the backend.
config.devServer.webSocketServer = { options: { path: '/webpack-hmr' } };
config.devServer.client = { webSocketURL: { pathname: '/webpack-hmr' } };
