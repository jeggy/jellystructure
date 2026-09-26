# Phase R316 — A fast scroll never kills the phone app

> Reported by users, 2026-09-26: *"On android mobile app, and scrolling real fast to the bottom in library or
> discover it crashes."* The owner: *"i have not been able to reproduce, but guessing is because im so close
> to the actual server?"*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). **Reproduced on the Pixel 9 the same day** (debug build 1.39-19). Android client
(`ravilo-ui` androidMain, both the phone and TV apps) plus one backend change. **Numbering:** verified
against `STATUS.md` the same day — Ravilo taken through **R315**.

## Evidence

**The phone already recorded it.** The Pixel 9's system crash log (`dumpsys dropbox data_app_crash`) holds
six Ravilo crashes from 2026-09-25/26:

- one `NetworkOnMainThreadException`, which R305 fixed;
- **five `IllegalStateException: Unbalanced enter/exit`**: three on the Play release **v1.36** (the
  version the household phones run) and two on debug builds (1.38-36 and 1.39-5).

**Reproduced in 20 seconds.** The debug build on the Pixel 9: open Library, then scripted fast swipes
(`adb shell input swipe … 40 ms`, fifteen down and ten up, three rounds). The app died with the same
exception. The trace, top to bottom:

```
FATAL EXCEPTION: main
CompletionHandlerException … DispatchedCoroutine{Cancelling}  [Dispatchers.IO]
  at coil3.compose.AsyncImagePainter.setRememberJob / restart
  at coil3.compose.internal.ContentPainterNode.onReset          ← a grid cell reused for another tile
  at androidx.compose.runtime.CompositionImpl.deactivate
Caused by … io.ktor.client.engine.UtilsKt$attachToUserJob$cleanupHandler
Caused by: java.lang.IllegalStateException: Unbalanced enter/exit
  at com.android.okhttp.okio.AsyncTimeout.enter
  at com.android.okhttp.internal.Util.discard / Http1xStream$FixedLengthSource.close
  at com.android.okhttp.okio.GzipSource.close
  at io.ktor.utils.io.jvm.javaio.RawSourceChannel.closeSource
```

## Why

1. **Every image in Ravilo on Android is fetched through Ktor's Android engine.** Coil uses
   `coil-network-ktor3` with no client of its own, so it takes `HttpClient()` from the classpath. Next
   to CIO (used for the events socket) that is `ktor-client-android`, the `HttpURLConnection` engine R210
   chose for REST. `HttpURLConnection` on Android is the platform's internal OkHttp 2 fork
   (`com.android.okhttp`).
2. **A fast scroll cancels image downloads that are in flight.** A lazy grid reuses a cell for the next
   tile. Coil restarts that cell's painter, which cancels the previous request **on the main thread**,
   mid-body.
3. **The Android engine's cancellation closes the stream from the cancelling thread.** Ktor's
   `RawSourceChannel` closes the `InputStream`. `HttpURLConnection`'s close **drains the rest of the
   body** (`Util.discard` → `skipAll` → `read`) while the IO thread is still inside a read of the same
   source. The platform's okio `AsyncTimeout` is not safe for that, and throws *Unbalanced enter/exit*.
4. **The throw lands in a cancellation handler.** Coroutines wrap it in a `CompletionHandlerException`
   and rethrow it to whoever called `cancel()`, here the main thread inside a Compose frame. Nothing can
   catch it there, so the process dies.

*"I'm so close to the server"* is part of it. On a fast connection the body of a tile image is being
read at the moment the cell is recycled far more often than on a slow one, where the request is still
waiting for bytes. Discover was not reproduced separately; its walls and rows are image grids built the
same way, so the same recycling applies.

**REST calls hit the same thing.** The 2026-09-26 02:46 debug crash is a REST call:
`DetachedCalls$run` (R305's own background-thread cancel) → the same `attachToUserJob` cleanup → the
same drain. R305 moved *where* a REST request is cancelled from; it could not change what the Android
engine does on cancel.

### A server-side waste found in the trace

The image stream passes through a `GzipSource`, because the backend **gzips JPEGs**.
`installGzipCompression` compresses any byte-array response over 860 bytes whose client accepts gzip. A
production poster is 30,296 bytes plain and 30,251 bytes gzipped: 0.15 % smaller, for a compression pass
on the server and an inflate on the device, for every image. It does not cause the crash, but it makes
each drained close do more work.

## Requirements

**FR-R316-1 — Android fetches over OkHttp.** On Android, both the REST client and Coil's image fetcher use
**OkHttp**. That means `ktor-client-okhttp` for REST, and for images either `coil-network-okhttp` or
Coil's Ktor fetcher handed that same OkHttp-backed client explicitly (never a client discovered from the
classpath). OkHttp cancels a call by closing its socket. It is built to be cancelled from any thread,
and it never drains a body on the cancelling thread. **One `OkHttpClient`** backs both, so images and
REST share a connection pool, which `ktor-client-android` never had. R210 named OkHttp as the
documented fallback *"if `ktor-client-android` turns out to have its own bug"*. This is that bug.
The events socket stays on CIO, as R210 set it. Moving it is not needed for this crash.

**FR-R316-2 — Coil is configured, not discovered.** The Android `ImageLoader` (`RaviloAppContext`)
names its network fetcher explicitly. No engine is picked by `ServiceLoader` order, which today depends
on which Ktor engines happen to be on the classpath.

**FR-R316-3 — A cancellation can never throw into the UI.** R305's `DetachedCalls` stays, so a REST
request is still awaited detached and cancelled off the main thread. The reproduction above becomes a
**device check that is run, not assumed**, on the Pixel 9 and on a TV. The check is the scripted fast
fling, five rounds each, on Library (all kinds), Discover's Studios wall and the Request tab, with
`logcat` and `dumpsys dropbox` checked for any Ravilo crash afterwards.

**FR-R316-4 — The server does not gzip what is already compressed (backend).** `installGzipCompression`
skips an explicit list of already-compressed types: `image/jpeg`, `image/png`, `image/webp`,
`image/gif`, `image/avif`, `video/*`, `audio/*`, `application/zip` and `application/gzip`. JSON, HTML,
JS, CSS, WASM and SVG keep being compressed. SVG is `image/svg+xml` but it is text, which is why the rule
is a list and not the `image/` prefix.

**FR-R316-5 — The release test catches an engine regression.** `scripts/verify-release-apk-on-art.sh`
(the release-build check) or the existing Android instrumentation path gains a smoke step that scrolls a
long image grid fast with cancellations in flight, so a future engine change cannot bring the crash back
unseen. Where an emulator cannot reproduce the timing, the FR-R316-3 device check stays mandatory before
a release that touches networking.

## Non-goals

- The web app (Ktor JS engine, the browser's fetch) and the Tizen and Chromecast receivers: not affected.
- Moving the events socket off CIO.
- Crash reporting from the field. The phone's own crash log was enough here. A way for household devices
  to report crashes is a separate decision.

## Acceptance

1. The Pixel 9, debug build with the fix: the FR-R316-3 fling check on Library, Discover Studios and
   Request, five rounds each. No crash, and no Ravilo entry in `dumpsys dropbox data_app_crash` after it.
2. The same on the release build (R8-minified). R8 must keep what OkHttp needs.
3. The stue or bedroom TV: fast scrolling through Movies and Discover still works, with the same image
   load times.
4. `curl -H 'Accept-Encoding: gzip'` on a poster URL: no `Content-Encoding: gzip`. On `/api/tv/home`:
   still gzipped.

## Dev review (2026-09-26, against `main` `0e5e434f`)

The reproduction and the stack pin the cause. Six items.

1. **What exists today.** No OkHttp in the build (`gradle/libs.versions.toml` has `ktor-client-android`
   `:49` and Media3, which fetches media through its own data source and is unaffected). REST is
   `HttpClient(Android)` (`androidMain/.../RaviloRootActuals.kt:39-44`), and the events socket is
   `HttpClient(CIO)` (`:47-50`). Coil gets its fetcher from `coil-network-ktor3`, declared in
   **`commonMain`** (`ravilo-ui/build.gradle.kts:63`), so Android loads it too, and it builds a default
   `HttpClient()` from the classpath.
2. **The dependency change.**
   - Add `ktor-client-okhttp` (same Ktor version) and `coil-network-okhttp` (Coil 3.2.0) to
     `androidMain`.
   - **Move `coil-network-ktor3` from `commonMain` to `wasmJsMain`.** The web root sets its own loader
     (`wasmJsMain/.../RaviloRootActuals.kt:27`) and keeps the Ktor fetcher, which is right in a browser.
     Android then has no Ktor fetcher on its classpath to be discovered.

   OkHttp 4.x supports the app's minimum SDK (24). Okio is already present through Coil. OkHttp ships
   its own R8 rules.
3. **One `OkHttpClient`, shared.** Build it once (`RaviloAppContext`, which already owns the image
   loader). Pass it to `HttpClient(OkHttp) { engine { preconfigured = shared } }` for REST, and to
   `OkHttpNetworkFetcherFactory(callFactory = { shared })` in the `ImageLoader.Builder`
   (`RaviloAppContext.kt:18-33`), next to the `SvgDecoder` already added there. Set
   `serviceLoaderEnabled(false)` on the builder, so no component is picked up from the classpath
   (FR-R316-2). The `HttpTimeout` settings move onto the Ktor client unchanged.
4. **R305 stays.** `DetachedCalls` (`shared/.../DetachedHttp.kt`) keeps REST cancellation off the main
   thread. With OkHttp it is belt and braces rather than the only defence.
5. **The server half.** `installGzipCompression` (`server/GzipCompression.kt:44-60`) gains a content-type
   check before compressing: skip `image/jpeg|png|webp|gif|avif`, `video/*`, `audio/*`,
   `application/zip|gzip`. The content type is on `original.contentType`. `/api/tv/image/*` answers
   `image/jpeg` today (checked on production), so posters and stills stop being gzipped.
6. **The check is a device check.** No emulator reproduces the timing reliably, and the unit suite
   cannot. FR-R316-3's fling script (the reproduction above) runs on the Pixel 9 against a debug and a
   release build, with `dumpsys dropbox --print data_app_crash` read before and after. FR-R316-5's
   release step can run the same script against whichever device the release check uses.

**Net effect.** Two dependencies added and one moved, one shared client built in one place, one
`ImageLoader` line, one content-type check on the server. No change to screens.
