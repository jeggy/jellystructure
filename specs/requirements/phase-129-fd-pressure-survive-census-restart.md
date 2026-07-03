# Phase 129 — Bound FD growth at the source: census-driven in-code queue + last-resort restart (FR-OPS1)

## Goal
A real crash occurred: `FdWatchdog` logged `FD count critical: 921 open file descriptors (>900, ceiling
is 1024)` + fired the `fd_pressure` webhook during a Mumi NFO sync + Jellyfin refresh, then the process
went down. Ktor Native's CIO `select()` crashes **fatally/uncatchably** at any FD ≥ 1024 (KTOR-8703, no
upstream fix). Phase 118 made the budget *observable* but not *bounded*.

**Primary fix (this phase): bound every FD source in code so the ceiling is never approached — a proper
queue/budget, not "just running things" behind a restart.** The controlled restart + supervisor is
demoted to a **last-resort backstop that should essentially never fire**; we lean on the supervisor that
**already exists** (Docker `restart: unless-stopped`) and **do not** re-add bare-metal systemd/shell
scripts. "We really do not want crashes" is met by prevention, not recovery.

## What actually happened (investigation — 3 code traces)
- The `921` is a **60 s high-water sample** (`FdWatchdog.kt:51` `delay(60_000L)`), so it is **not** proven
  to be caused by the Mumi sync — the sync coincided with the tick. The true source accumulated over the
  session (most plausibly a preceding library scan's outbound fan-out).
- **No leak in the reported path.** All 12 `HttpClient(Curl)` are process-lifetime singletons routed
  through `OutboundHttp.withPermit(Semaphore(24))` (`OutboundHttp.kt:17`); `JellyfinClient` is one shared
  client (`:44`, `Main.kt:104`); `refreshItem` (`:177-193`) reuses it; ffprobe `popen` is `pclose`d in a
  `finally` under `ProcessGate(4)` (`FfprobeRunner.kt:111-121`); `NfoWriter` closed every sink and all 47
  writes succeeded. The sync path nets ~0 FDs.
- **The un-bounded sources** (what the 24-permit gate does *not* cap, i.e. the missing "queue"):
  1. **Resting idle Curl keep-alive sockets** — none of the 12 clients caps its connection pool;
     `withPermit(24)` bounds *in-flight* requests, not *idle* pooled sockets, which persist after each
     request across **12 independent pools** and drift upward under sustained load (scan re-pull + artwork
     + Jellyfin + *arr + chart providers).
  2. **Inbound sockets** — bounded only by CIO `connectionIdleTimeoutSeconds = 20` (`Server.kt:164`); there
     is no live-count admission control.
- **Watchdog is observational, lagged, and narrow.** Below 950 it only logs + webhooks; above 950 it
  sheds **image + `/api/tv/events` routes only** (`CacheHeaders.shedIfFdCritical:48-53`; `Server.kt:330`),
  reading a count up to 60 s stale. No controlled exit before 1024.
- **Latent write-path leak (fires only on write failure):** `NfoWriter.writeAtomically`
  (`nfo/NfoWriter.kt:321-343`), the sinks at `MediaRoutes.kt:713-716` / `:434-435`, and
  `CrashResilience.writeFileBlocking:74-79` close by a bare `sink.close()`, not `use{}` → one leaked FD
  per *failed* write. Reachable via corrupt items (Mumi S01E43, Phase 128).

**Feasibility note that shaped this spec:** every FD source EXCEPT inbound sockets can be hard-bounded in
code. Ktor CIO Native exposes **no accept-time connection cap** (the `Server.kt:161` comment: "the only
inbound FD knob CIO Native exposes"), so inbound can only be *shed fast*, not hard-capped in-process.
That residual is why a last-resort restart is retained — but it should never fire once the bounded budget
below holds.

## Decisions (confirmed with product owner)
- **In-code bounding is the primary fix**; the controlled restart is **last-resort insurance only**.
- **Rely on the existing supervisor** (Docker `restart: unless-stopped`). **Do NOT** re-add
  `scripts/run-supervised.sh` / systemd unit (deleted in `00713b8`; out of scope here).
- The FD **census** decides exactly which source to bound (avoids guessing / needless refactor).

## Requirements

### A. FD census — prove the source (`ops/FdWatchdog.kt`) [diagnostic, gates §B]
1. `fun censusOpenFds(): FdCensus` — `readlink` every `/proc/self/fd` entry, bucket by target prefix:
   `sockets` (`socket:[…]`), `pipes` (`pipe:[…]`), `anon` (`anon_inode:[…]`), `files` (regular paths + top
   ~10 distinct paths & counts), `other`. `data class FdCensus(total, sockets, pipes, anon, files,
   topFiles)`.
2. Emit compact-JSON census with the `Logger.warn`/`Logger.error` crossings **and** in the `fd_pressure`
   webhook; expose it on `/api/health` beside `fd_count`. Cheap (readlink only; no `/proc/net` parse).
3. This makes the *next* spike attributable — sockets (outbound idle vs inbound vs WS), files (a leak), or
   pipes (child procs) — so §B bounds the real source instead of all of them.

### B. Bound the resting FD sources in code — the actual "queue" [PRIMARY]
1. **Outbound idle sockets → one shared connection pool.** Consolidate the stateless outbound callers onto
   a **single shared `HttpClient(Curl)`** (one pool: distinct-hosts × depth ≈ <15 idle sockets total,
   instead of 12 independent pools). Each service keeps its thin wrapper but injects the shared client;
   the two WS clients (`JellyfinLibraryListener`, `JellyfinSessionBridge`) stay separate. Keep every call
   inside `OutboundHttp.withPermit`. Update the `FdWatchdog` budget KDoc. *(If the §A census from the
   first real spike shows sockets are NOT the contributor, skip this refactor — but this is the expected
   root cause.)*
2. **Inbound sockets → live-count admission + faster reaping** (the best in-process bound CIO allows):
   - Add a global `ApplicationCallPipeline` intercept at the earliest phase that, while over the shed
     threshold, responds `503` + `Retry-After: 5` + **`Connection: close`** on **all** routes (except
     `/api/health`), so the inbound FD lives ~one request cycle instead of keep-alive-held. Replaces the
     image/TV-only shed with a global one.
   - Lower `connectionIdleTimeoutSeconds` 20 → **10** (`Server.kt:164`) to reap idle inbound keep-alive
     FDs twice as fast. (Accept: a transient burst can still spike briefly — CIO has no hard cap; this is
     the ceiling of in-code inbound control, and the reason for the §D backstop.)
3. **Reaffirm the provable budget.** With §B.1 the documented gate sum (OutboundHttp 24 + shared idle
   pool ≈15 + ProcessGate 4 + WS 16+1 + SQLite ~6 + stdio ~10) is provably « 1024, leaving inbound
   headroom of ~900. Update the `FdWatchdog` KDoc budget table to match.

### C. Fix the latent write-path FD leak (`nfo/NfoWriter.kt`, `MediaRoutes.kt`, `CrashResilience.kt`)
Wrap the sinks in `writeAtomically` (`NfoWriter.kt:321-343`), `MediaRoutes.kt:713-716` / `:434-435`, and
`CrashResilience.writeFileBlocking:74-79` in `use {}` / try-finally so a mid-write throw (ENOSPC/EIO/
corrupt target) closes the FD instead of leaking it.

### D. Faster detection + last-resort controlled restart [insurance — should never fire]
1. Sample every **2 s** (was 60 s) so the shed/exit decisions read a fresh count. Census only computed
   at/above the warn threshold to keep the hot tick to a bare count. Thresholds (config-overridable):
   warn 700, alert+webhook+census 850, **global shed 900**, **drain+exit 980**.
2. `> 980`: set a `@Volatile draining` flag (the §B.2 intercept then refuses everything new), wait ~2 s for
   in-flight to drain, write an **intentional** `last-restart.json` marker (distinct from the crash
   marker), fire a **synchronous** `{"event":"fd_controlled_restart","count":…,"census":…}` webhook (reuse
   `CrashResilience`'s blocking-curl pattern), then `platform.posix._exit(17)` — a clean **non-zero** exit
   the existing Docker `restart: unless-stopped` brings back. `reportCrashRecoveryIfAny`
   (`CrashResilience.kt:58`) also consumes `last-restart.json` → Activity entry +
   `server_recovered_from_fd_restart` (gated by `notify_on_crash`).
3. This path is **insurance**: if §B holds, FDs never reach 980. It exists only for a pathological inbound
   burst that in-process code cannot hard-cap. **No new host scripts** — it depends solely on the
   already-present container restart policy.

## Scope / critical files
- `ops/FdWatchdog.kt` — census, 2 s tick, thresholds, drain trigger, budget KDoc.
- `ops/CrashResilience.kt` — `last-restart.json` write + recovery reporting; `use{}` on `writeFileBlocking`.
- `server/Server.kt` (`:145/:158-164/:330`), `server/CacheHeaders.kt` (`:48-53`) — global shed intercept +
  `Connection: close` + drain flag; idle-timeout 20→10.
- Outbound clients (`tmdb/TmdbClient.kt`, `auth/JellyfinClient.kt`, `arr/ArrClient.kt`,
  `media/ArtworkDownloader.kt`, `media/LogoDownloader.kt`, `tv/RaviloArtworkService.kt`, chart providers)
  + `Main.kt` wiring — shared-client consolidation (§B.1).
- `nfo/NfoWriter.kt` (`:321-343`), `server/routes/MediaRoutes.kt` (`:434-435`, `:713-716`) — `use{}` fix.

## Non-goals
- **No bare-metal systemd/`run-supervised.sh` restore** — explicitly out of scope; rely on Docker's
  existing `restart: unless-stopped`.
- **No reverse proxy / OS-level inbound cap** — the global shed + short idle timeout is the in-code brake;
  a hard inbound cap is deferred.
- No attempt to survive an already-triggered `select()` crash in-process (impossible by construction); the
  §D exit happens strictly *before* 1024.
- No change to the Phase-118 genuine-crash last-gasp reporter.

## Acceptance
- **Census:** trigger FD pressure (a scan) → logs + `fd_pressure` webhook + `/api/health` include the
  type breakdown; the source of a spike is attributable.
- **Bounded outbound (primary):** after §B.1, steady-state `sockets` in the census stays flat across
  repeated scans/syncs (no per-scan idle-socket drift); resting FD count returns to the documented budget.
- **Global inbound shed:** above the shed threshold a normal `GET /api/media` returns
  `503 + Retry-After + Connection: close` (not just image routes); `/api/health` stays reachable; idle
  inbound FDs reaped within ~10 s.
- **Leak fix:** a forced `writeAtomically` failure no longer leaks an FD (census `files` returns to
  baseline).
- **Backstop never fires in normal operation:** a healthy full scan + multi-series sync completes without
  crossing the shed threshold. Only in a forced-threshold test build does `>980` drain, write
  `last-restart.json`, emit `fd_controlled_restart`, `_exit(17)` **before** any FD reaches 1024, and the
  Docker policy restarts it → next boot logs `server_recovered_from_fd_restart`.
