# Ravilo TV — Jank round (R96–R102) implementation + on-device measurement

**Date:** 2026-06-27
**Device:** stue TV — Sony BRAVIA XR-65X93K (Android TV), `10.10.11.128`
**Build under test:** `dev.jellystructure.ravilo` release APK
**Method:** `dumpsys gfxinfo` frame stats, driven by scripted `adb input` D-pad
navigation, with **forced AOT compilation** (`cmd package compile -m speed -f`) and
warm-up passes so JIT/cold-start state is not a confound. Baseline = commit `a27407b`
(R94, pre-round); After = the R96–R102 round.

> Caveat on inputs: `adb input keyevent` injection reports a large "High input latency"
> in gfxinfo — that is an artifact of synthetic input and is **ignored**. Frame render
> times (histogram / percentiles / janky-frame counts) are valid regardless of input source.

---

## 1. What shipped this round

| Phase | Change | Status |
|---|---|---|
| **R96** | `RemoteImage requestedWidth` → `?w=` for LANDSCAPE tiles (1920px backdrop → 640px; ~9× less bitmap) | ✅ |
| **R98** | Web Coil memory cache 64 → 128 MB (Android left at adaptive 20%/150 MB disk) | ✅ |
| **R99** | Prefetch high-water-mark (no overlapping re-enqueue) + `remember(items, urlResolver)` | ✅ |
| **R100** | Detail backdrop pre-warm on select (`openDetail` funnel) + `MediaStore` genre index for related | ✅ |
| **R101** | Freeze Ken Burns drift while the home list is actively scrolling | ✅ |
| **R102** | 6 s timeout on the home Continue-row Jellyfin calls (ship without Continue on timeout) | ✅ |
| **R96/R99 fix** | Prefetch the *same* `?w=` URL the tile requests (shared `sizedProxyUrl`) — found by measurement | ✅ |
| **R97** | gzip Compression on the backend | ⛔ **Deferred** — `ktor-server-compression` has **no Kotlin/Native variant** (needs `java.util.zip`). See R104. |

All frontend changes are in the deployed APK. **The backend changes (R100 genre index,
R102 timeout) require a server redeploy to take effect and were NOT live during this
measurement** — the TV talked to the existing server.

---

## 2. Results

### 2.1 Scroll — warm vs warm (apples-to-apples, both AOT-compiled)

| Metric | Baseline R94 | After R96–R102 |
|---|---|---|
| Total frames | 866 | 863 |
| **Janky frames (true deadline)** | **0.12%** (1) | **0.12%** (1) |
| Janky frames (legacy >16 ms) | 0.23% (2) | 0.23% (2) |
| 50th / 90th / 99th pct | 20 / 22 / 23 ms | 19 / 22 / 23 ms |
| Missed Vsync · Slow UI thread | 0 · 0 | 0 · 0 |

**When warm, scroll is already smooth on both builds and essentially identical.** There is
no regression and no large frame-rate gain — because warm steady-state scroll had almost
nothing left to fix at the frame-timing level.

### 2.2 Scroll — the real culprit is cold start

The very first measurement of the freshly-installed After build (before AOT/warm-up) told
the real story:

| State | Legacy janky frames | 50th pct |
|---|---|---|
| After, **cold** (just installed, JIT) | **63.9%** | 32 ms |
| Baseline, semi-warm (run a few times) | 42.4% | 22 ms |
| Either build, **AOT + warm** | **0.2%** | 19–20 ms |

→ The dropped frames the user feels while scrolling are dominated by **cold-start /
not-yet-AOT-compiled code**, *not* a steady-state rendering defect. Forcing AOT
(`compile -m speed`) took scroll from ~64% janky to ~0.2%. This points squarely at a
**Baseline Profile** as the highest-value follow-up (see R103) — R96–R102 do not address
cold start.

### 2.3 Detail / sub-screen open

- **Visual:** detail pages render correctly with the new image-width + pre-warm logic
  (verified the series page "Ups, vi er voksne" — backdrop, audio/subtitle flags, synopsis,
  Play/My-List all correct).
- **Frame timing (caveated):** the automated sub-screen-open pass (open + back, ×4) showed
  **legacy janky frames 34.3% → 18.1%** and 90th pct 34 → 32 ms (true-deadline jank ~1.37%
  on both). The histogram confirms fewer slow (28–40 ms) frames after. **Caveat:** that
  navigation opened a *mix* of detail pages and channel views (one channel returns
  "Connection refused"), so it is not a clean detail-only number. A clean detail-only
  measurement was **blocked when the backend went offline mid-session** (see §3).

The detail improvement is consistent with the changes that apply there — smaller backdrop
decode (R96) and backdrop pre-warm (R100) — but should be treated as *indicative, not
definitive*, pending a clean re-measure against a live backend.

---

## 3. Incidental finding — backend "Connection refused"

The backend at `10.10.10.10` served the app fine at 09:22 and became **unreachable ~09:24**
(the whole app showed "Connection refused"; it is also unreachable from the dev host on
:9505/:8080). This is the user's separate server and was not redeployed by this work. It
matters because **a backend that intermittently refuses connections is itself a major source
of felt "lag"/failure** (channels and detail pages that never load). Worth investigating
whether it is a crash (e.g. the Kotlin/Native CIO FD-ceiling class), a Jellyfin-connectivity
stall, or a server-lifecycle event. See R105.

---

## 4. Honest bottom line

- **Scroll smoothness, warm:** already good; this round keeps it good and removes a
  LANDSCAPE double-download bug (R96/R99 fix). The felt scroll lag is mostly **cold start**
  (→ R103) plus **image-load latency / pop-in**, which gfxinfo does not measure and which
  R96/R98/R99 reduce qualitatively (smaller, pre-warmed images; less cache thrash).
- **Detail-open:** pre-warm + adaptive width are in and render correctly; indicative
  smoothness gain (34%→18% legacy jank on sub-screen opens), pending a clean re-measure.
- **Biggest remaining lever is not in R96–R102:** it is the **Baseline Profile** (R103),
  which my own data shows takes scroll from ~64% janky (cold) to ~0.2% (AOT).

---

## 5. Follow-up specs (new phases)

### R103 — Ship/repair the Baseline Profile (★ highest value) — ✅ DONE (FPS re-measure pending backend)
**Update 2026-06-27:** Implemented. Root cause confirmed: the merged Compose+Coil library
profile *was* embedded (~7.4 KB `.dm`) but a sideloaded release APK had no
`androidx.profileinstaller`, so ART never applied it → JIT every launch. Added
`profileinstaller 1.4.1` (its `ProfileInstallerInitializer`/`ProfileInstallReceiver` are now in
the merged manifest) + `ravilo-android/src/main/baseline-prof.txt` (app composables + Coil;
wildcards expand to ~4.8k app + 3k Coil rules pre-R8, ~12.7k post-R8). On the stue TV:
`INSTALL_PROFILE` broadcast → `result=1`; `compile -m speed-profile -f` → `status=speed-profile`.
The full apply-chain works on-device. **Still to do:** re-run the §2.2 cold-scroll measurement
once the backend is reachable, to confirm cold scroll lands near the AOT-warm ~0.2% jank.

_Original spec:_
**Why:** cold-start was measured as the dominant scroll-jank source (64% → 0.2% under
forced AOT). The release build already emits a `baselineProfiles` artifact, yet the cold run
was still janky — so either the profile is not being applied at runtime (missing/old
`androidx.profileinstaller`), or it does not cover the Compose lazy-list scroll + detail
paths.
**Do:** confirm `androidx.profileinstaller` is a dependency and the `ProfileInstallerInitializer`
runs; generate a Baseline Profile (Macrobenchmark `BaselineProfileRule`, or a hand-authored
`baseline-prof.txt`) that exercises Home hero + row scroll, Browse grid scroll, and detail
open; verify it is packaged and applied (`dumpsys package dexopt` shows `speed-profile`).
Re-measure cold scroll — target ≤ a few % legacy jank without forced `compile -m speed`.
**Files:** `ravilo-android/build.gradle.kts` (profileinstaller dep + baseline profile),
new `baseline-prof.txt` / a `:ravilo-baseline` macrobenchmark module.

### R104 — Backend response compression (replaces deferred R97)
**Why:** R97's `ktor-server-compression` has no Kotlin/Native target. Large JSON feeds
(series detail = every episode overview) still ship uncompressed.
**Options (pick one):**
1. **zlib cinterop + minimal interceptor** — add a `zlib` cinterop (`-lz`; the server
   already uses cinterop + links curl/zlib transitively) and a small `ApplicationPlugin`
   that gzips `application/json` responses above ~1 KB and sets `Content-Encoding: gzip`.
   Must be crash-safe (the Native CIO server SIGABRTs on uncaught exceptions). Exclude the
   image-proxy bytes.
2. **Slim the payload instead** — clip/omit episode `overview` in the initial `SeriesDetail`
   and lazy-load it per episode as a fixed-size overlay (respects the no-flicker rule).
**Recommendation:** try (1) behind a flag, validated on web first; fall back to (2).
**Files:** `build.gradle.kts` (cinterop), `server/Server.kt` (plugin) — or
`tv/DetailService.kt` (payload slim).

### R105 — Backend stability: "Connection refused" investigation
**Why:** the server became unreachable mid-session; an intermittently-down backend is a
direct cause of felt lag/failures on the sofa.
**Do:** capture server logs around an outage; determine crash vs hang vs lifecycle; verify
the FD-ceiling guards (`ImageProxyService Semaphore(8)`, playstate `Semaphore(4)`, WS
read-loop catches) still hold under the burst load a fast-scrolling TV generates; confirm
the one "Connection refused" channel's upstream. Redeploy the backend so **R100 genre index
+ R102 Continue-row timeout** go live, then re-run the §2.3 detail measurement cleanly.
**Files:** server logs; `tv/ImageProxyService.kt`, `tv/HomeFeedService.kt`,
`media/ArtworkDownloader.kt` (latent unbounded fan-out per the backend-perf report).

### Deploy note
R100-backend and R102 are committed but **need a server redeploy** to take effect; this
measurement reflects frontend changes only.

---

## 6. Post-implementation validation (R103 + R105)

### R103 — Baseline Profile, cold-scroll re-measurement (now with a live backend)
With the app `speed-profile`-compiled (baseline profile applied), force-stopped, then cold-launched:

| Pass (R103 build) | Modern jank | Legacy jank | 50th pct |
|---|---|---|---|
| **Cold, 1st scroll** (code AOT, **images cold**) | 0.57% | 53.5% | 30 ms |
| **2nd scroll** (code AOT, **images warm**) | **0.23%** | **1.15%** | **19 ms** |
| (reference) original cold, **no profile applied** | 0.80% | 63.9% | 32 ms |

This cleanly isolates **two** cold-start axes:
1. **Code cold-start (JIT)** — *fixed by R103.* The app is AOT-compiled from frame one;
   modern jank on a cold scroll is 0.57% (vs 0.80% un-profiled), and the very next pass is
   fully warm (0.23% / 19 ms) with no `compile -m speed` needed.
2. **Image cold-cache** — *not a code problem.* The first scroll past never-seen tiles
   decodes / GPU-uploads / crossfades them all at once (53.5% → 1.15% legacy jank between
   pass 1 and pass 2). Mitigated by R96 (smaller decode), R98 (cache), R99 (prefetch ahead)
   and R100 (detail pre-warm), but inherent the first time through fresh content.

**Takeaway:** R103 delivers the win a Baseline Profile can deliver (code AOT from cold). The
residual first-open image warming is a separate axis; the R96–R100 image work is what
addresses it.

### R105 — Backend outage + redeploy
- **Where it runs:** the backend is a bare `jellystructure.kexe` on the dev host itself
  (`10.10.10.10`), pointed at the live `config/` data (DB + pairing). Not the docker-compose
  service (no such container exists).
- **The outage:** it stopped mid-measurement (~09:22–24). **Root cause undetermined** — the
  original process's output was not captured, so there is no crash trace. It coincided with
  heavy measurement churn (repeated `am force-stop` → WS reconnects, fast-scroll image bursts,
  plus local gradle/git activity). The Kotlin/Native fragilities (FD-ceiling, WS-handler
  exception on abrupt disconnect) remain the prime suspects, but are **not demonstrated**.
- **Redeploy:** rebuilt from `main` (so **R100 genre index + R102 Continue-row timeout are now
  live**) and relaunched detached with **stdout/stderr captured to `backend.log`**. The stue TV
  auto-reconnected with pairing intact. **It then survived the full R103 re-measurement load
  (heavier than before) with a clean log — 0 errors.** A pre-existing
  `[WARN] paired user token rejected by Jellyfin (401) → using server token` is unrelated
  (stale per-user Jellyfin token; catalog is Jellyfin-free since R83, so home/detail still work).
- **Follow-up if it recurs:** `backend.log` will now hold the trace. The defensive guidance
  stands — keep every fan-out `Semaphore`-bounded and every WS read-loop / send wrapped in
  `try`/`runCatching` so an abrupt TV disconnect can't abort the Native process.

### R106 — Proactive first-screen image prewarm — ⛔ TRIED, REVERTED (negative result)
Attacked the cold-image residual from §6 with a client-side prewarm: on home-feed load, warm
the first 3 content rows' initial 8 tiles (width-matched, 400 ms after first paint). Measured
it as a negative result and reverted (commit reverted same day).

**Why reverted — the residual is backend-side, not client-side.** Three identical R106 cold
trials returned **50.8% / 36.4% / 6.3%** legacy janky — a huge spread with a clear *downward
trend as the trials ran*. That trend is the finding: the cost is the **backend image-proxy
cold disk-cache** — the first view of an item fetches the image from Jellyfin through the
`Semaphore(8)` gate; once the proxy has it on disk, the same scroll is ~6% janky (trial 3).
A *client* prewarm can't fix that — it is gated by the same cold proxy, and a 24-image burst
can *add* contention if it overlaps the scroll. Benefit was unmeasurable above the noise, so
shipping it would add per-load fetches for no demonstrable gain.

**The real lever (if pursued):** warm the **backend** image-proxy disk cache — e.g. fetch
each item's poster/backdrop at scan time, or a background warm after a scan — so *every*
client's first view is fast. That is a larger, backend-touching change with diminishing
returns: the cold-image residual is intrinsic to first-view content and is already made
graceful by the R87 colored placeholders (no blank pop, just a settle) + R88/R99 scroll-ahead
prefetch + R96 smaller decodes. **Recommendation: do not add a client-side bulk prewarm.**

## 7. Vertical-nav + detail-open round (R107–R108)

User feedback after R96–R103: "better, but still laggy going **up/down** (not left/right) and
opening detail pages." Measured both (gfxinfo breakdown counters, not just jank %):

- **Fast vertical nav:** 50th 30ms / 90th 34ms, "Slow bitmap uploads" = 0 → a **layout/scroll**
  cost, not images.
- **Detail-open:** 99th 129ms + **14 "Slow UI thread"** frames → heavy **synchronous composition**.

Root causes (verified in source; two Explore agents, findings cross-checked — one bogus claim
discarded: a boolean `derivedStateOf` does **not** recompose the AppBar per frame):

- **Vertical nav = a redundant *double* bring-into-view.** On D-pad DOWN the focused tile's
  *native* bring-into-view scrolls the `LazyColumn`, AND `ContentRow` launched a *second*
  `bringIntoView()` of the whole row — two competing animated scrolls re-laying-out the nested
  `LazyColumn(LazyRow…)` each frame, coroutines piling up under fast presses. Home's spec already
  reserves a top inset (R65) that shows the row title via the native scroll, so the explicit one
  is redundant *there*.
- **Detail-open = eager off-screen composition.** The detail is a non-lazy
  `Column(verticalScroll)`; the hero fills the viewport, so the cast + related rails (+ series'
  season-picker + episode rail — ~10-12 off-screen `CastCircle`/`Tile`/`EpisodeCard`) all compose
  on the first frame. Plus `SeriesDetail` re-scanned all episodes O(N) on every recomposition.

**R108 — drop Home's redundant row bring-into-view** (`bringRowHeaderIntoView=false`; Discover/
Channel keep it, they have no top-inset spec). **Result (measured): fast vertical nav 50th 30→17ms,
legacy jank 63%→5%.** ✅ The big win — directly fixes the felt up/down lag; verified titles still show.

**R107 — defer below-hero detail rails ~280ms + memoize the O(N) episode scans.** First paint
composes hero-only; rails paint settled (below the fold → no visible reflow). **Result: detail-open
peak 99th 129→97ms** (worst hitch ~25% smaller and now on a static screen, not mid-slide); total
composition similar (rails still compose, just later).

**R109 — detail `Column(verticalScroll)` → `LazyColumn`** (supersedes R107's timed defer). The
hero is item 0; each rail (cast, related; series' season-picker + episode rail) is its own lazy
item, composed only when scrolled into view — so opening a detail and not scrolling composes *only*
the hero. `scrollState`→`LazyListState`; AppBar `scrolled` becomes a boolean `derivedStateOf` (also
removes a prior per-scroll-frame recompose from reading `scrollState.value` at composition scope).
**Result: detail-open peak 99th 97→85ms (129→85 across the round).** Validated on the stue TV:
movie + series detail render correctly, rails compose lazily on scroll with working focus, season
switching recomposes the episode rail correctly, UP-NEXT playstate overlay + playback intact.

## Measurement-method caveat (important)
Single cold-scroll runs on this rig vary widely (legacy janky 6–64% under identical
conditions) because of background dexopt, GC, and especially **backend-proxy cache warmth**.
Trust *trends across several trials* and the *modern* janky-frame metric, not any single
legacy-jank number. The warm-scroll comparison (§2.1) is reliable because the caches are warm
and the numbers are tight; cold single-runs are not.
</content>
