// Dev server config: port, static files, and API/WS proxy to the backend.
// Active when running `./gradlew runDev` or `./gradlew wasmJsBrowserDevelopmentRun`.
config.devServer = config.devServer || {};
config.devServer.port = 8081;
config.devServer.static = [
    { directory: require("path").resolve(__dirname, "kotlin") },
];
config.devServer.proxy = [
    {
        context: ["/api"],
        target: "http://localhost:9505",
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
