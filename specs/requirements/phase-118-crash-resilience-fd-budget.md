# Phase 118 — Crash resilience: never die silently, and keep FDs under the select() ceiling (FR-CR1)

## Goal
Jellystructure should **never crash silently**: route errors become clean 500s, an unavoidable fatal
error produces a **webhook alert + crash marker + supervised restart**, and — since the fatal
FD_SETSIZE selector crash cannot be caught meaningfully — the file-descriptor pressure that causes it is
**engineered away**: a documented FD budget, cache headers that kill the request storms, bounds on every
FD source, live FD telemetry with early warning, and graceful load-shedding before the ceiling.

## The hard constraints (researched + verified)
- **Ktor Native's selector is `select()`-based and unfixed upstream.** The crash
  (`IllegalStateException: File descriptor N is larger or equal to FD_SETSIZE (1024)` from
  `SelectorHelper.addInterest`) is tracked as **KTOR-8703 — open, no fix version, no poll/epoll work
  planned**; `fd_set` is still used on ktor master (3.5.1). FD_SETSIZE=1024 is glibc compile-time —
  `ulimit` cannot raise it. The only durable fix is **keeping the process's total FDs < 1024** (the FD
  *number* matters — any fd ≥ 1024 entering the selector is fatal, so total open FDs must stay below).
  (KTOR-4046 is why we get this exception instead of a SIGSEGV since Ktor 3.0.)
- **The crash cannot be swallowed.** `kotlin.native.setUnhandledExceptionHook` *is* invoked for
  exceptions escaping coroutines, and the process continues **only** for detached failures — but the
  selector loop is a **child of the server engine's job**: its failure cancels the engine via structured
  concurrency and unwinds `main()`. The hook is therefore a **last-gasp reporter**, not a survival
  mechanism → an external **supervisor** is mandatory.
- Current state (verified): **no StatusPages**, **no unhandled-exception hook**, **no FD telemetry**,
  **zero cache headers on any image route** (every admin page load re-fetches every poster/logo/person
  image — the storm behind the second crash log), CIO engine at defaults
  (`connectionIdleTimeoutSeconds=45`), `restart: unless-stopped` only in docker-compose (the dev host
  runs the bare `.kexe` with nothing supervising). Outbound HTTP is fully bounded
  (`OutboundHttp` Semaphore(24), all 10 Curl clients verified through it); `/ws` + `/api/tv/events`
  read-loops/sends are already hardened. The paired-token 401 double-probe (extra outbound per TV
  request, forever) is fixed by Phase 110.

## Requirements

### A. Handle what's handleable
1. **Install StatusPages**: uncaught route-handler exceptions → structured JSON 500 + one Activity error
   entry + `Logger.warn` with the route; 404/405 get small JSON bodies. (CIO already isolates handler
   exceptions; this makes them observable and consistent.)
2. **Background-scope invariant** (codify what exists): every `launch {}` runs under a
   `SupervisorJob + CoroutineExceptionHandler` scope (`rootScope`/`appScope`); every WS read loop
   catches non-cancellation throwables; every WS send is `runCatching`. Applies to the new sockets from
   Phases 110/114.

### B. Report what's fatal
1. **`setUnhandledExceptionHook`** (installed first thing in `main`): write a **crash marker**
   (`config/last-crash.json`: timestamp, message, first stack frames) synchronously, attempt one
   **synchronous** webhook POST (`popen` curl — the async pattern can't be trusted mid-crash), then
   return (the process will exit; that's expected).
2. **On boot**: if a crash marker exists → fire the `server_recovered_from_crash` webhook (reusing
   `fireWebhook` + `notifications_webhook` config) with the marker contents, write an Activity error
   entry, clear the marker. New Behavior toggle `notify_on_crash` (default **true**).
3. **Supervision**: document + ship the restart story — docker path already has
   `restart: unless-stopped`; add `scripts/run-supervised.sh` (restart loop with capped backoff) and a
   sample systemd unit for bare-metal runs, referenced from the README. (Also close the old
   `checkPortFree` TODO in `Main.kt` so a supervisor restart never trips over a half-dead predecessor.)

### C. FD budget — engineered, documented, enforced
1. **Publish the budget** (constitution-adjacent table in this spec, kept current in code comments):
   outbound HTTP 24 (`OutboundHttp`) · per-TV Jellyfin session WS ≤16 (Phase 110, own budget) ·
   Jellyfin listener WS 1 (Phase 114) · child processes ≤4 (see 3) · SQLite ~6 (WAL + 4 readers) ·
   admin/TV app WS ≤ ~20 · stdio/misc ~10 → leaving **≥900 fds of headroom for inbound sockets**, which
   cache headers + idle timeout keep low. **Invariant: any new long-lived FD source must state its
   budget here.**
2. **Cache headers on every image/file route** (the single biggest fix): `/api/tv/image/**`,
   `/api/media/**/artwork|still|poster`, `/api/metadata/**/artwork`, `/api/people/{id}/image` gain
   `ETag` (size+mtime) + `Cache-Control: max-age` + 304 handling — the `serveStaticBytes` pattern
   (`Server.kt:364-381`) generalized. Browsers/Coil stop re-downloading hundreds of images per page
   view; the artwork storms in both crash logs disappear at the source.
3. **Bound child processes globally**: one process gate (Semaphore(4)) shared by every
   `popen` site (`FfmpegRunner`/`FfprobeRunner`/`MkvpropeditRunner`/`Screengrabber`/health checks) on
   top of the existing scan-dispatcher and Screengrabber(2) bounds — user-triggered ffmpeg work can no
   longer stack unbounded pipes (the Phase 109 worker already serializes remuxes to 1).
4. **FD telemetry**: a 60 s watchdog counts `/proc/self/fd` entries; exposes the number on
   `/api/health` + the System health panel; **>700** → Activity warning (once per crossing); **>900** →
   webhook alert. Health panel shows the current count + high-water mark.
5. **Load-shedding before death**: above **950 fds**, image/artwork routes respond `503 + Retry-After: 5`
   instead of doing work, and the TV events route rejects *new* connections — bounded degradation the
   browser/app retries, instead of a dead process. Normal API routes are unaffected (they reuse existing
   sockets).
6. **Trim idle inbound sockets**: set CIO `connectionIdleTimeoutSeconds` explicitly (20 s) — the only
   inbound knob CIO Native has; note in ops docs that a reverse proxy is the real concurrency cap for
   any internet-facing deployment.
7. **Track upstream**: pin a comment/issue-watch on KTOR-8703; re-evaluate on every Ktor upgrade —
   if a poll()-based selector ever ships, items 4–5 become belt-and-suspenders.

## Scope
- Backend: `Main.kt` (hook, marker, boot check, port-free fix), `Server.kt` (StatusPages, CIO config,
  cache-header helper on image routes, shed gate), FD watchdog service, process gate, `/api/health` +
  health-panel fields, `notify_on_crash` config.
- Ops: `scripts/run-supervised.sh`, systemd unit sample, README section.
- FE: System health panel FD line; Settings ▸ Notifications gains the crash toggle.

## Non-goals
- No attempt to keep the process alive through a selector failure (impossible by construction — the
  supervisor owns recovery).
- No Ktor fork/patch, no engine swap, no JVM fallback.
- No proxying/streaming changes (streams are direct TV→Jellyfin and consume no server FDs).
- No log-file management (stdout logging unchanged).

## Acceptance
- Killing the process with a simulated fatal error produces: a crash marker, a webhook within seconds of
  restart, an Activity entry — and under the supervisor the service is back within ~5 s.
- A route handler throwing yields a JSON 500 + Activity entry; the process survives.
- Reloading the admin Library/metadata pages twice fetches images once (second load = 304s/cache hits);
  the webpack dev proxy no longer opens hundreds of upstream sockets per reload.
- `/api/health` reports the live FD count; artificially exhausting FDs (test hook) triggers the warning
  at 700, the webhook at 900, and image-route 503-shedding at 950 — with the process alive throughout.
- The documented FD budget sums below 1024 with all Phase 109/110/114 features active.
