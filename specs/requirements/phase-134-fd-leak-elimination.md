# Phase 134 — FD leak elimination: close every file read, and prove the 50-TV + 100-worker-scan budget (FR-OPS2)

## Goal
A second real FD incident occurred — this time **user-visible on a TV**: during a `runDev` session the
watchdog logged `[ERROR] FD count critical: 851 open file descriptors (>850, ceiling is 1024)`, the
Phase-129 global shed then (correctly) started refusing work at >900, and the Ravilo TV showed
*"Something went wrong — {"error":"server under FD pressure, retry shortly"}"*. The shed backstop worked
as designed; the point of this phase is that it must **never be reached** in normal operation. Target
capacity, explicitly: **1–50 connected TVs browsing while a full library scan runs with up to 100
concurrent scan workers** (revised up from single-digit scan concurrency — this deployment's host has
the CPU/network headroom to make 100 real, not just configured-and-ignored), with comfortable headroom
under the hard 1024 ceiling (Ktor Native CIO `select()`, KTOR-8703, unfixable upstream — see Phase
118/129).

## Addendum — scaling scan concurrency for real (FR-OPS2 §F)
The original draft of this spec left `scan_workers`/`scan_threads` at their existing 32-ceiling and
`ProcessGate`/`OutboundHttp` at their existing 4/24 permits, on the reasoning that worker count doesn't
by itself cost FDs — workers queue behind the downstream gates. That's true, but it also means 100
workers would buy **no extra scan throughput** behind gates sized for 4-8 concurrent devices: ffprobe
capped at 4 processes and TMDB/artwork/Jellyfin HTTP capped at 24 in-flight would just make 96 of the 100
workers sit idle. On a host with real CPU/network capacity to spare, that's leaving throughput on the
table for no FD-safety reason. Revised targets (all still comfortably bounded, see §Budget below):
- `scan_workers` / `scan_threads` config ceiling: **32 → 100** (admin- and frontend-settable; the
  frontend's own `max="32"` mirrors the backend clamp and must move with it or the backend change is
  invisible).
- `ProcessGate` (ffprobe/ffmpeg/mkvpropedit/screengrab/health-check — every `popen` site, not
  scan-specific): **4 → 16**. Deliberately *not* 100 — each permit is a real forked OS process, and
  100 concurrent ffprobe/ffmpeg children is a resource-exhaustion risk unrelated to FDs (CPU/memory
  contention, not the thing this phase is fixing). 16 concurrent probes meaningfully speeds up a big
  scan without turning the host into a fork bomb.
- `OutboundHttp` in-flight permits: **24 → 64**, idle pool assumption **≈15 → ≈40**. This is the gate 100
  scan workers actually queue behind for TMDB/artwork/Jellyfin calls, so it's the one that should scale
  the most — a stalled TMDB/CDN round-trip costs latency, not host resources, so a higher cap is safe
  headroom, not a fork-bomb risk the way ProcessGate is.

## Root cause — proven live, not inferred
The broken process was still running and was inspected directly (`/proc/<pid>/fd`, 922 FDs at the time
of the census):

- **~800 of 922 FDs were leaked plain-file read handles** — hundreds of distinct
  `…-thumb.jpg.src` episode-still provenance sidecars at 2–3 open handles *each*, plus artwork-cache
  files. Sockets: **7**. Pipes: 5. SQLite: 10 (5× db + 5× wal). This was never a socket/scale problem.
- **Mechanism (confirmed from kotlinx-io 0.9.0 library source):** `SystemFileSystem.source(path)` does a
  raw `fopen()` and returns a `FileSource` with **no cleaner/finalizer**. `readString()`/`readByteArray()`
  read to EOF but do **not** close. An unclosed source is a **permanent** FD leak — GC never recovers it.
  The idiom `SystemFileSystem.source(p).buffered().readString()` therefore leaks exactly one FD per call,
  every call.
- **Trigger math:** `ArtworkDownloader.checkEpisodeStill()` calls `readStillSrc()` (the leaking read) for
  every episode with a still on disk. It runs per-episode from `stampHasStill()` (every scan/rescan/sync)
  **and** from `isArtworkIncomplete()` (pipeline "missing"-scope evaluation). Two-three passes over this
  library's few thousand episodes ≈ the observed count, matching the 2–3× duplication per path exactly.
- Why Phase 129 missed it: its audit ("no leak in the reported path") covered HTTP client pools, `popen`,
  and `NfoWriter` — the **one** file in the tree that already used `.use{}` correctly. Whole-file *reads*
  were never audited as a class. The census Phase 129 added is what made this diagnosis trivial.

## Complete leak-site inventory (all verified by reading each site)
**Read-side leaks — `source(...).buffered().readString()/readByteArray()` with no close:**

| Site | Frequency class |
|---|---|
| `ArtworkDownloader.kt:193` `readStillSrc` · `:237` `readAssetSrc` | per episode × every scan/rescan/pipeline pass (**the incident**) |
| `RaviloArtworkService.kt:189,193` (`readFresh`) · `:201,203` (`readSimple`) · `:122` (resize tmp) | **1–2 per image-proxy request, cache hits included** — the single highest-volume route (every poster/backdrop/still/logo a TV renders) |
| `Server.kt:451,458` `serveFrontendFile` | 1 per static asset — every admin SPA / Ravilo-web page load |
| `LogoDownloader.kt:44` `serveLogo` · `:90` `servePersonImage` | per studio/network/person image request |
| `ChannelLogoStore.kt:67` `read` | per channel-logo request |
| `MediaRoutes.kt:791` still preview | per admin still-picker preview |
| `ConfigStore.kt:33` · `JsTagStore.kt:26` · `ActivityLog.kt:63,70` · `CrashResilience.kt:65,93` | startup-only (bounded, but same broken idiom — fix for hygiene/copy-paste safety) |

**Write-side hardening (close exists but is not exception-safe — leaks only on a mid-write throw, e.g.
disk full):** `ArtworkDownloader.kt:140` (`download`) `,:198` (`writeStillSrc`) `,:242` (`writeAssetSrc`);
`LogoDownloader.kt:107`; `ConfigStore.kt:48`; `JsTagStore.kt:64`; `ChannelLogoStore.kt:42`;
`ActivityLog.kt:135,149`. The correct pattern already exists in-tree: `NfoWriter.kt:107,132,325,340`,
`MediaRoutes.kt:435,510,731`, `RaviloArtworkService.atomicWrite:211`, `CrashResilience.writeFileBlocking:109`
(which even carries a Phase-129 comment explaining *why* — applied to that one write path only).

**Outbound-pool strays (secondary, found during the same audit):**
- `QBittorrentClient.kt:45` builds a private `HttpClient(Curl)` — a second, ungoverned idle keep-alive
  pool outside the documented Phase-129 "one shared pool ≈15 idle" budget. Safe to migrate: it manages
  its qBittorrent `SID` cookie **manually** via headers (no cookie-jar plugin semantics to preserve).
- `TrackRoutes.kt:768` `estimateRemuxSeconds` runs `popen("stat …")` outside `ProcessGate` — transient
  and sequential (max 1 pipe), gate it for uniformity.

## The 50-TV + 100-worker-scan budget (post-fix, post-§F scale-up)
With reads transient (opened → read → closed inside one call), the **committed** long-lived budget is:

| Consumer | Bound | Enforced by |
|---|---|---|
| stdio / misc | ~10 | — |
| SQLite (writer + WAL + readers) | 5 (hard) | SQLDelight native Pool blocks at capacity (verified in driver source, not just config intent) |
| outbound HTTP in-flight | ≤ 64 (was 24) | `OutboundHttp.withPermit` — this is the gate 100 scan workers actually queue behind |
| outbound HTTP idle pool (one, shared) | ≈ 40 (was ≈15) | Phase 129 §B.1 (+ this phase folds in the qbit stray) |
| child processes (ffprobe/ffmpeg/stat) | ≤ 16 (was 4) | `ProcessGate` — deliberately *not* scaled to 100 workers; see §F (real OS processes, not just FDs) |
| per-TV `/api/tv/events` WS | **≤ 128 (new hard cap)** | this phase §D |
| Jellyfin session bridges (outbound WS) | ≤ 16 | `JellyfinSessionBridge` semaphore (17th+ TV: no dashboard remote-control until a slot frees — existing, documented degradation) |
| Jellyfin library listener WS | 1 | Phase 114 |
| admin `/ws` | ~few | low-volume by nature |

≈ **290 committed at the full 50-TV + 100-worker-scan target** (50 event sockets live, both scan gates at
their new full capacity simultaneously), leaving **~610 FDs** to the 900 shed threshold / **~734** to the
1024 ceiling for transient inbound request sockets and transient file reads. 50 TVs bursting a home
screen of images concurrently (clients hold 6–8 connections each) plus 100 scan workers each briefly
holding a `.use{}`-scoped read (artwork/.src sidecar checks, now microsecond-duration since Phase 134's
fix) fit comfortably within that headroom under realistic load; only a genuinely pathological
everything-at-once peak would approach the shed, and even then the outcome is a graceful 503 + retry, not
a crash. **Conclusion: the architecture is now sized for 50 TVs + 100 real scan workers; the leak was the
entire story for the *incident*, and §F's gate scale-up is what turns "100 workers" from a config number
into actual throughput.**
The 900-shed + 980-drain backstops stay, expected to never fire.

## Requirements

### A. `FileIo` — one safe way to read/write whole files
1. New `dev.jellystructure.io.FileIo` (linuxX64Main): `readBytes(path)`, `readText(path)`,
   `writeBytes(path, bytes)`, `writeText(path, text)` — every one `.use{}`-scoped internally so the FD
   closes on success **and** on throw. Throwing variants; existing `runCatching` call-site wrappers keep
   their current null-on-failure semantics unchanged.
2. Migrate **every** read-side leak site in the inventory to `FileIo`. No behavioral change anywhere —
   same bytes, same nullability, same error handling; only the handle lifetime changes.
3. Migrate the listed write-side sites to `FileIo.write*` (their tmp-write + `rename` atomicity stays at
   the call site; only the sink open/write/close collapses into the helper).

### B. Hygiene invariant (regression fence)
4. **Outside `FileIo.kt`, `SystemFileSystem.source(`/`.sink(` may only appear immediately `.use{}`-scoped**
   (streaming call sites like `NfoWriter`'s sink lambdas stay as-is — already compliant). New
   `scripts/check-fd-hygiene.sh` greps the tree and exits non-zero on any bare `source(`/`sink(` call not
   followed by `.use` on the same statement; run it alongside `scripts/check-phases.sh` after syncs/reviews.

### C. Outbound-pool strays
5. `QBittorrentClient` drops its private `HttpClient(Curl)` for `OutboundHttp.client` (in-flight calls
   already go through `withPermit` — only the idle pool moves). **Done.**
6. ~~`estimateRemuxSeconds`'s `popen` goes through `ProcessGate.withPermit`~~ — **investigated, descoped.**
   Its only callers (`classifyEpisode`, from `classifyEpisodes`'s plain `.map{}`) are synchronous and
   iterate episodes strictly sequentially — there is no concurrent fan-out to gate against, so this
   `popen`/`pclose` pair (already correctly paired, no leak) never has more than one instance in flight
   regardless of gating. Making the whole `classifyEpisodes`/`classifyEpisode`/`estimateRemuxSeconds`
   chain `suspend` just to route through `ProcessGate` would be a real refactor for a purely cosmetic
   uniformity concern with no safety benefit — not worth it.

### D. `/api/tv/events` defensive cap
7. `TvEventBus.register` becomes `tryRegister` — atomically (inside its existing mutex) refuses a **new**
   device when 128 distinct devices are already registered (a reconnect of an already-registered deviceId
   always succeeds — it overwrites its own entry and must never be blocked by the cap). The `/api/tv/events`
   handler closes refused sockets with `TRY_AGAIN_LATER`. 128 = 2.5× the 50-TV target, covering reconnect
   churn while making growth bounded by construction.

### E. Observability polish
8. The `>850` critical log line appends `census=${census.toJson()}` — the same breakdown the `>700` warn
   and the webhook already carry. (This incident's only surfaced line was the census-less critical line;
   the diagnosis had to come from a live `/proc` inspection that a restart would have destroyed.)
9. `FdWatchdog`'s budget doc-comment is updated to this phase's table (event-WS cap line, FileIo note).

### F. Scale scan concurrency for real (§Addendum)
10. `scan_workers`/`scan_threads` config clamp raised 32 → 100, backend (`Main.kt`) and the admin
    frontend's mirrored `coerceIn`/`max="32"` input constraint (Settings.kt) together — the backend clamp
    alone is invisible if the input field silently discards anything typed above 32.
11. `ProcessGate` permits: 4 → 16. `OutboundHttp` in-flight permits: 24 → 64 (doc-comment budget math
    updated in place).

## Invariants
- **No whole-file read/write outside `FileIo`** (or an inline `.use{}` for streaming) — enforced by
  `scripts/check-fd-hygiene.sh`.
- The FD budget table in `FdWatchdog`'s doc-comment stays current; any new long-lived FD source adds its
  line there **in the same PR** that introduces it (existing Phase-129 rule, restated).
- Shed (>900) and drain (>980) thresholds unchanged — backstops, not operating points.
- No change to any HTTP/WS wire behavior except the new 129th-device refusal.

## Out of scope
- A bytes-level in-memory LRU for the image proxy (perf win, zero FD relevance once reads close).
- Ravilo-side UX for a 503 shed response (raw JSON on the TV is ugly; with the leak fixed the shed is
  ~unreachable — candidate for a later R-phase as a friendly "server busy, retrying…" toast + auto-retry
  honoring `Retry-After`).
- Ktor engine swap / upstream KTOR-8703 (re-affirmed unfixable; see Phase 118/129).
- Inbound accept-time admission control beyond the existing global shed (CIO Native exposes no hook).

## Source references
- Live evidence: `/proc/1797514/fd` census 2026-07-03 (922 FDs; ~800 file reads; 7 sockets) — the full
  dump is in the session log; the census JSON shape is `FdCensus.toJson()`.
- Leak mechanism: kotlinx-io-core 0.9.0 `FileSource` (`fopen` in `SystemFileSystem.source`, close only in
  `close()`, no finalizer).
- Phase 118 (observability), Phase 129 (FR-OPS1: shared pool, shed, drain, census) — this phase closes
  the file-read class both of them missed.
- `reference-ktor-native-fd-setsize` memory (FD_SETSIZE background, KTOR-8703).
