// Proxy API and WebSocket calls to the backend during development.
// Only active when running `./gradlew wasmJsBrowserDevelopmentRun`.
if (config.devServer) {
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
}
