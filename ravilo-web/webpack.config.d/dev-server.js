// Dev server config for Ravilo web — port 8082, API proxy to backend.
// Active when running ./gradlew runDev.
// __dirname at runtime = build/wasm/packages/jellystructure-ravilo-web/
config.devServer = config.devServer || {};
config.devServer.port = 8082;
config.devServer.static = [
    { directory: require("path").resolve(__dirname, "kotlin") },
    // Serve index.html and composeResources from processed resources output.
    // Up 4 levels from build/wasm/packages/jellystructure-ravilo-web/ → project root.
    { directory: require("path").resolve(__dirname, "../../../../ravilo-web/build/processedResources/wasmJs/main") },
];
config.devServer.proxy = [
    { context: ["/api"], target: "http://localhost:9505" },
];
config.devServer.webSocketServer = { options: { path: '/ravilo-hmr' } };
config.devServer.client = { webSocketURL: { pathname: '/ravilo-hmr' } };
