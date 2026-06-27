# Ravilo TV — Jank round (R96–R102) implementation + on-device measurement

**Date:** 2026-06-27
**Device:** stue TV — Sony BRAVIA XR-65X93K (Android TV), `192.0.2.11`
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
  (verified the series page "Hov, vi er gamle" — backdrop, audio/subtitle flags, synopsis,
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

The backend at `192.0.2.10` served the app fine at 09:22 and became **unreachable ~09:24**
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

### R103 — Ship/repair the Baseline Profile (★ highest value)
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
</content>
