# Phase R210 — Split the Android HTTP client: CIO for the WebSocket only, a REST-capable engine for everything else

> Found during the 2026-08-26/27 Ravilo TV startup-performance investigation (read-only pass, no
> code changed). Corroborates a live incident already on file: [[bug-ravilo-tv-cio-connect-timeout]]
> (2026-08-21, soveværelse TV) — Ravilo "just loads and loads" on launch. That investigation
> narrowed the cause to a **client-side Ktor CIO connect bug on Android** (a raw shell HTTP request
> from the same device, at the same moment, succeeded instantly; CPU was 0% throughout, ruling out
> JIT/cold-start starvation) but stopped short of a fix, explicitly suggesting as the next step:
> *"try swapping to the Android/OkHttp engine for the plain-REST client (keep CIO only for the WS
> client)."* This phase does exactly that.

**Status:** Planned.

## Problem

`RaviloRootActuals.kt:17-37` (androidMain) builds **one** `HttpClient(CIO)` and uses it for every
REST call (`getHome`, `getConfig`, `getDiscover`, `getUpcoming`, login, playback reporting, …) *and*
the long-lived `/api/tv/events` WebSocket (`TvApiClient.kt:414-443`, `connectEvents()`'s
`client.webSocket(...)`). CIO was chosen specifically for its WebSocket support — the comment at
`RaviloRootActuals.kt:18` notes Android's native engine has none, and R33's live config push depends
on the socket.

On at least one real TV, CIO's **connect** step itself intermittently fails/hangs on Android while
the exact same network path is instantly reachable via a raw shell request from the same device at
the same time. `HomeStore.load()`'s exponential-backoff retry (`HomeStore.kt:66-94`, 10 attempts,
1s→256s, each bounded by the client's 10s `HttpTimeout`) exists precisely to survive real transient
blips — but it also means a *persistent* client-side connect bug is fully masked as "loading…" for
minutes, with the backend never at fault and no way for the retry logic to tell the difference.

## Requirements

### FR-RV-R210-1 — REST calls move off CIO to an engine without the observed connect bug
Add a second `HttpClient` on Android, built on **`ktor-client-android`** (the `HttpURLConnection`
-based Ktor engine — already declared unused in `gradle/libs.versions.toml:35`, so no new dependency
needs adding), configured identically to the current client (`ContentNegotiation` + the same
`HttpTimeout` values). This client handles every plain REST call `TvApiClient` makes. The existing
`HttpClient(CIO)` is kept, scoped to **WebSocket use only**.

Chosen over `ktor-client-okhttp`: no new Gradle dependency, and `ktor-client-android`'s lack of
WebSocket support (the original reason CIO was picked at all) is irrelevant once it's never asked to
open one. If a future need arises for connection pooling/HTTP-2 that `ktor-client-android` can't
provide, `ktor-client-okhttp` is the documented fallback (see Non-goals).

### FR-RV-R210-2 — `TvApiClient` accepts a separate WS client
`TvApiClient`'s constructor (`shared/.../TvApiClient.kt:24-29`) gains an optional fourth parameter:
```kotlin
class TvApiClient(
    private val client: HttpClient,
    val baseUrl: String,
    private val deviceToken: () -> String?,
    private val wsClient: HttpClient = client,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
)
```
`connectEvents()` (`TvApiClient.kt:414-443`) is the only method touched — its `client.webSocket(...)`
call becomes `wsClient.webSocket(...)`. Every other method is unchanged and keeps using `client`.
Defaulting `wsClient = client` keeps the wasmJs actual (`RaviloRootActuals.kt` in `wasmJsMain`,
`TvApiClient(httpClient, baseUrl, deviceTokenProvider)`, 3 positional args) source-compatible with no
edits — wasmJs's `ktor-client-js` engine already supports WebSockets natively, so it has no reason to
split.

### FR-RV-R210-3 — Android's actual wires both clients
`createTvApiClient()` (Android `RaviloRootActuals.kt:17-37`) constructs both `HttpClient`s and passes
them as `TvApiClient(restClient, baseUrl, deviceTokenProvider, wsClient = cioClient)`.

## Invariants
- **The WebSocket path is unchanged** — same CIO engine, same `INFINITE_TIMEOUT_MS` override for the
  socket (`TvApiClient.kt:438-442`), same reconnect/backoff logic in `RaviloApp.kt`. This phase does
  not touch R33 live-push behavior at all.
- **`HomeStore`'s retry/backoff logic is unchanged.** This phase doesn't remove or shorten it — a
  genuinely unreachable server should still retry with backoff before showing Error. It only changes
  which engine performs the connect, on the theory (not yet proven, see Acceptance) that the bug is
  CIO-specific.
- **No behavior change on wasmJs/Tizen** — this is an Android-only actual + a backward-compatible
  optional constructor parameter in shared code.

## Non-goals
- **Not root-causing the CIO connect bug itself.** This phase routes around it; it does not file or
  fix an upstream Ktor issue. If `ktor-client-android` turns out to have its own unrelated bug on some
  device, that's a new investigation, not a regression of this phase's scope.
- **Not adopting OkHttp.** Considered and rejected for this phase specifically to avoid a new Gradle
  dependency; revisit if `ktor-client-android`'s connection-per-request model (no pooling) turns out
  to matter in practice.
- **Not changing timeout values** — `connectTimeoutMillis=5s`/`requestTimeoutMillis=10s`/
  `socketTimeoutMillis=10s` carry over unchanged to the new REST client.

## Acceptance
- A cold launch issues `getHome`/`getConfig`/`getDiscover`/`getUpcoming` over the
  `ktor-client-android`-backed client and the `/api/tv/events` upgrade over the CIO-backed client —
  confirmed by engine-specific logging or a debugger breakpoint during implementation review.
- The original repro conditions from [[bug-ravilo-tv-cio-connect-timeout]] (soveværelse TV,
  `192.0.2.12:5555`) no longer show a connect-timeout error on the REST path when the same
  device's raw shell HTTP request succeeds — **on-device verification is user-initiated per standing
  instruction ([[feedback-no-tv-deploy]]/[[feedback-no-auto-deploy]]); this phase's own build/compile
  checks cannot prove the fix, only that it compiles and doesn't regress the WS path.**

## Source references
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/RaviloRootActuals.kt:17-37` —
  `createTvApiClient()`, the single `HttpClient(CIO)` to be split.
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/TvApiClient.kt:24-29,414-443` —
  constructor and `connectEvents()`.
- `ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/RaviloRootActuals.kt:36-53` — the
  other `createTvApiClient()` actual, confirmed unaffected.
- `gradle/libs.versions.toml:35` — `ktor-client-android`, already cataloged, currently unused
  anywhere in the project.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/HomeStore.kt:66-94` — the
  retry/backoff this phase leaves unchanged but whose worst-case masking this phase aims to prevent.
- Prior investigation: [[bug-ravilo-tv-cio-connect-timeout]] (root-cause narrowing, 2026-08-21).
