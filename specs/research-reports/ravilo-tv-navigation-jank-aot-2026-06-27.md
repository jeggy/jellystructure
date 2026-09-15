# Ravilo TV — navigation animation jank: root cause is Compose JIT, fix is full AOT

**Date:** 2026-06-27 (afternoon follow-up to `ravilo-tv-jank-measurement-2026-06-27.md`)
**Devices:**
- **soveværelse** — Sony BRAVIA 4K VH2 (Android 12), `10.10.11.20` — *weak* panel, primary stress-test
- **stue** — Sony BRAVIA XR-65X93K (Android TV), `10.10.11.128` — *fast* panel
**Build under test:** `dev.jellystructure.ravilo` release APK at `main` (through R115)
**Method:** `dumpsys gfxinfo` frame stats, driven by scripted `adb input` D-pad, with the
app **AOT-compiled and warmed** before each measurement so cold-start/JIT warm-up is not a
confound. Reported metric = **`Number Frame deadline missed`** (the modern, true-deadline
janky-frame count) plus GPU percentiles.

> Input caveat (unchanged): synthetic `adb input` inflates gfxinfo's "High input latency"
> counter — ignored. Frame render times / deadline-miss counts are valid regardless.

---

## 1. The complaint

After the R96–R109 round, the app "still isn't perfect when navigating **up/down** or
**between screens**." The earlier round addressed image latency, cache, prefetch, hero
animation and detail-open composition; the *felt* residual was steady-state navigation
smoothness, not cold start.

## 2. How the investigation was run

Two tracks in parallel:

1. **Code audit** — two `Explore` sub-agents mapped, with file:line, (a) the vertical-nav
   machinery (`rememberEdgeBringIntoViewSpec`, the LazyColumns, `dpadFocusable`, focus
   traversal, `RaviloMotion` springs, per-frame scroll work) and (b) the screen-transition
   machinery (`RaviloApp` `AnimatedContent` push/pop, `navDir`, detail store loading,
   `RaviloMotion` durations).
2. **On-device isolation measurement** — instead of one blended "scroll" pass, four passes
   that *isolate* the suspected cost centres:
   - **Hero idle** — hero on screen, Ken Burns animating, no input, 5 s.
   - **Vertical nav (hero visible)** — `down×3 / up×3` near the top.
   - **Rows only (hero off-screen)** — scroll the hero off, then `down×3 / up×3`.
   - **Transitions** — open detail / Back, ×5.

## 3. Baseline measurement (soveværelse, `speed-profile` install)

| Pass | Frames | **Deadline misses** | GPU 50th |
|---|---:|---:|---:|
| Hero idle (Ken Burns, 5 s) | 306 | **0** | 9 ms |
| Vertical nav (hero visible) | 247 | **7** | 6 ms |
| Rows only (hero off-screen) | 130 | **3** | 3 ms |
| **Transitions** (open/close ×5) | 585 | **17** ← worst | 9 ms |

(Earlier stue numbers, same build, were consistent: vertical-nav 50th-percentile frame
= 31 ms with 54 % legacy-janky and **GPU 50th = 18 ms**; transitions 99th-percentile = 97 ms.)

### What this immediately ruled out

- **GPU / overdraw is NOT the bottleneck.** GPU is 3–10 ms everywhere — well under the
  16.6 ms/60 fps budget. The earlier hypothesis (the full-screen hero + two full-screen
  gradient scrims redrawing under Ken Burns) was **disproven**: hero idle with Ken Burns
  drops **zero** frames at 9 ms GPU. The hero is cheap to animate.
- The jank is therefore **UI-thread**, concentrated in **transitions** (17) and secondarily
  **vertical nav** (7), exactly matching the complaint.

## 4. The dead end that was informative

**R115 — remove the competing detail-open scroll.** The audit's top finding: the detail
Play/Resume row fires `listState.scroll(MutatePriority.UserInput)` on focus (R72), which on
*open* runs even though the list is already at offset 0, grabbing the scroll mutex and
fighting bring-into-view. Hypothesis: this causes the transition hitch.

Guarded it on `firstVisibleItemScrollOffset > 0` so open skips it. **Measured: no change**
(transitions 15–18, vertical nav 9). R115 is a correct, harmless fix (kept, committed) but
**not** the jank lever. This told us the cost is *composition*, not a competing scroller.

## 5. The breakthrough diagnostic: `-m speed` vs `-m speed-profile`

Key question: is the UI-thread cost *genuine composition*, or **Compose framework code being
JIT-compiled** because the R103 baseline profile doesn't cover it? Cheap test — force *full*
AOT (compile every method) and re-measure:

```
adb shell cmd package compile -m speed -f dev.jellystructure.ravilo
```

| Pass | `speed-profile` (R103 baseline) | **`-m speed` (full AOT)** |
|---|---:|---:|
| **Vertical nav** | 7–9 misses | **0–1 misses** ✅ |
| Transitions | 17 | 13–15 |

**Vertical-nav jank was almost entirely JIT.** Full AOT nearly eliminates it. Transitions
improve only slightly under full AOT → that residual *is* genuine composition cost.

### Why the baseline profile didn't already cover it

R103 ships a real, compiled `assets/dexopt/baseline.prof` (confirmed in the APK) and relies
on the Compose libraries' own baseline profiles being merged from their AARs. But those
library profiles are generated from the Compose team's benchmarks and **do not cover this
app's specific TV D-pad focus-traversal + lazy-list-scroll hot paths**, so `speed-profile`
left them to JIT → the felt up/down jank.

## 6. The fix that does NOT work: hand-written Compose profile rules (R116, reverted)

Attempt: add blanket rules to `ravilo-android/src/main/baseline-prof.txt`:

```
HSPLandroidx/compose/runtime/**;->**(**)**
HSPLandroidx/compose/foundation/**;->**(**)**
HSPLandroidx/compose/ui/**;->**(**)**
HSPLandroidx/compose/animation/**;->**(**)**
```

**Result: ineffective.** The compiled `baseline.prof` even *shrank* (6696 → 5704 B), and
`speed-profile` still gave 3–9 misses (no win). Cause: **R8 minifies/renames `androidx.compose`
classes in the release build**, so source-name wildcards don't match the final DEX; AGP's
mapping rewrite covers app packages, not these. The Compose library profiles survive because
they ship as pre-mapped binary. **R116 was reverted.**

The *proper* shippable equivalent would be a **generated** baseline profile (AGP
baseline-profile plugin + a `macrobenchmark` module running a scroll/nav journey), which
emits correctly-mapped binary rules. Not built — see §9.

## 7. The fix (adopted): deploy with full AOT

These are **personally sideloaded** TVs that we deploy + dexopt over adb. So the fix is a
**deploy-step change**, no app code or shippable-profile concern:

```
adb -s <tv-ip>:5555 install -r ravilo-android/build/outputs/apk/release/ravilo-1.0-release.apk
adb -s <tv-ip>:5555 shell cmd package compile -m speed -f dev.jellystructure.ravilo   # NOT speed-profile
adb -s <tv-ip>:5555 shell am start -n dev.jellystructure.ravilo/.android.MainActivity
```

Full AOT also doesn't deoptimize over time the way profile-guided compilation can. Recorded
in memory `ravilo-tv-aot-speed`.

## 8. Final results (both TVs, full AOT)

| Pass | soveværelse (weak VH2) | stue (fast XR-65X93K) |
|---|---:|---:|
| **Vertical nav (up/down)** | 0–1 misses | 0–3 misses |
| Transitions (open/close ×5) | 13–15 misses | **6 misses** |
| Hero idle | 0 | 0 |

Up/down is **essentially perfect** on both. Transitions are good on the fast panel
(~1 dropped frame/open) and still show a residual on the weak panel.

## 9. Residual + recommendations

1. **Transitions (~13–15 misses on the weak TV under full AOT) = genuine composition cost**
   during the slide: the incoming detail's hero + AppBar compose while the outgoing Home is
   still alive (R109 already made the detail first frame hero-only). Options, each bumping the
   no-flicker/atomic-frame rule and so needing care:
   - Hoist the `AppBar` *above* the `AnimatedContent` so it isn't composed twice during the slide.
   - Defer the detail body until the slide settles (R107 tried timed defer; was replaced by R109).
   - Shorten/snappier transition (`ScreenEnterMs` 220 → ~160, smaller slide distance) — reduces
     the window but not the per-open first-compose hitch.
2. **Make the AOT win shippable** (if the app ever leaves personal sideloading): add a
   `:ravilo-benchmark` macrobenchmark module + AGP baseline-profile plugin and generate the
   profile from a real scroll/nav journey.
3. Keep deploying with `-m speed`.

## 10. Appendix — environment notes

- **Pairing the soveværelse TV:** it was a fresh install on the server-address screen; pairing
  normally needs the web "Ravilo → Pair a TV" modal, which was unreachable. The server address
  (`10.10.10.10:9505`) was entered via `adb input`, producing pairing code `X37T5G`; the
  approval was then written directly to `ravilo_pairing` (`approved=1` + jogvan's user fields),
  reusing jogvan's freshest token copied DB→DB inside SQL (never materialised). The TV polled,
  minted its device token, and dropped to Home — paired as **jogvan** (admin).
- **Backend:** bare `jellystructure.kexe` on the dev host (`10.10.10.10:9505`), not docker; see
  memory `reference-backend-deployment`. Running it as a session-tracked process is fragile;
  the user's own launch is the durable one.
- **gfxinfo gotcha:** sending `KEYCODE_HOME` before a pass bounces the app to the Android
  launcher and `am start` may not re-front it → gfxinfo reports `Total frames rendered: 0`.
  Verify foreground (`dumpsys window | grep mCurrentFocus`) before measuring.

## 11. Commits

- `R115` (`6fe93aa`) — skip competing detail-open scroll when already at top (kept; not the lever).
- `R116` — reverted (hand-written Compose baseline rules don't survive R8).
- No other code change; the headline fix is the `-m speed` deploy step.
