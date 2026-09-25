# Phase R305 — A request the app stopped waiting for is closed off the main thread

> Found 2026-09-25 while verifying R265 on the Pixel 9 (debug): the app was killed in the background
> with a cast running, relaunched, and died 300 ms after its Activity started —
> `NetworkOnMainThreadException`, thrown from inside Home's feed request, which Compose had just
> cancelled. Nothing the viewer did caused it; restoring after a process death is enough.

## Status

`✓ Built` — written and built 2026-09-25, not dev-reviewed. Client only (`shared`'s `TvApiClient`); no
backend, route, DTO or string change.

**Build:** `DetachedHttp` (the four calls `TvApiClient` makes, same shape as Ktor's extensions, so every
call site reads as before — `client` is now the wrapper) over `DetachedCalls` (a supervisor scope; the
caller awaits, and on the caller's cancellation the request is cancelled from `scope.launch`, off the
caller's thread). `DetachedCallsTest` (linuxX64): a request's cancellation handler — registered with the
same internal `invokeOnCompletion(onCancelling = true)` Ktor's response channel uses — runs on another
thread than the caller that cancelled it, and results and failures reach the caller unchanged. **The
test fails on the old path** (checked by making `run` call its block directly: *"the request's
cancellation ran on the caller's thread"*).

**Verified on the Pixel 9 (debug):** cast running, Home, `run-as … kill -9`, relaunch — the app comes up
and stays up (three runs). On the previous build the same sequence died in one of four runs: the window
is the time Home's response body is in flight, so it is a race, which is also why nobody had seen it.

## What happens

R210 moved every REST call on Android to Ktor's `Android` engine (`HttpURLConnection`). A response body
is read through a `RawSourceChannel` that registers a cancellation handler which **closes the socket**
(for TLS: writes a `close_notify`) — and Ktor attaches the request's job to the **caller's** job
(`attachToUserJob`), so when the caller is cancelled, that handler runs **synchronously on the thread
that cancelled it**.

A Compose effect is cancelled on the main thread. So any `LaunchedEffect` whose request is between its
headers and the end of its body when the effect leaves composition — a screen navigated away from, a
branch replaced by a state restore — closes a TLS socket on the main thread, and Android's default
thread policy kills the process for it:

```
FATAL EXCEPTION: main
kotlinx.coroutines.CompletionHandlerException: Exception in completion handler InvokeOnCancelling …
Caused by: android.os.NetworkOnMainThreadException
  at com.android.org.conscrypt.ConscryptEngineSocket.close(…)
  at io.ktor.utils.io.jvm.javaio.RawSourceChannel._init_$lambda$0(Reading.kt:69)
  at dev.jellystructure.ravilo.ui.screens.HomeStore.refresh(HomeStore.kt:202)
  at dev.jellystructure.ravilo.ui.screens.HomeStore.onReturn(HomeStore.kt:83)
```

The window is narrow for a small response and wide for Home's feed, which is why a relaunch into saved
state (R292 restores the stack; Home composes, then is replaced within a frame) hits it reliably.

## Requirements

**FR-R305-1 — Every REST call runs detached from its caller's job.** `TvApiClient` makes its requests
in a coroutine of its own (a supervisor scope held by the client), and the caller *awaits* it. A caller
that is cancelled stops awaiting at once — its own cancellation is unchanged — and the request is then
cancelled **from a background thread**, so Ktor's close runs there. One funnel for all of them (the
`get`/`post`/`put`/`delete` the client already calls), never a per-call-site change.

**FR-R305-2 — Cancellation still cancels.** A request whose caller is gone does not run to completion
for nobody: it is cancelled, only on another thread. No `NonCancellable`, no fire-and-forget.

**FR-R305-3 — Nothing else changes.** Same routes, headers, timeouts, errors and exceptions reaching
the caller (a failed request still throws in the caller, a timeout still times out). The WebSocket
client is out of scope (CIO, and its lifecycle is R293's).

## Verification

- Device, Pixel 9 (debug): connect a cast, press Home, `run-as … kill -9` the process, relaunch —
  the app comes up (the 2026-09-25 build died every time).
- Unit: a request whose caller is cancelled mid-flight is cancelled too, and the caller sees its own
  `CancellationException`, not the request's.
