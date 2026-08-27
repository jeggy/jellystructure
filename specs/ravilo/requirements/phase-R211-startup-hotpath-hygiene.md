# Phase R211 — Startup hot-path hygiene: session-store caching + FontFamily churn

> Found during the 2026-08-26/27 Ravilo TV startup-performance investigation (read-only pass). Two
> small, unrelated-but-adjacent pieces of avoidable work sit directly on the cold-start critical path
> and on Home's hottest recomposition path. Bundled into one phase because each is a one-file,
> low-risk fix not worth its own number — the same pattern as R203–R207's bundled screenshot-audit
> fixes.

**Status:** Implemented.

## FR-RV-R211-1 — `MultiTokenStore` caches its parsed session list instead of re-reading on every call

### Problem
Neither platform actual caches anything — every `getAll()`/`getActive()` call independently re-reads
its backing storage and re-parses the session list from scratch:
- Android (`MultiTokenStoreAndroid.kt:20-36,70-73`): `getActive()` calls `loadAll()`, which does a
  `SharedPreferences.getString("sessions", ...)` read + a full `JSONArray` parse, every time.
- Web (`MultiTokenStoreWasm.kt:48-52,69-72`): `getActive()` calls `getAll()`, which does a
  `localStorage.getItem` read + the hand-rolled `parseAll()` string-split parse, every time.

During just the first composition of `RaviloApp` on the fast (single-cached-session) path, this
already happens **3-4 times**: `RaviloApp.kt:287` (`getActive()`), `:288` (`getActive()` again),
`:340` (`getAll()`, inside `initialDest`), `:365` (`getActive()` again, the R58 self-heal). On top of
that, every single `TvApiClient` REST/WS call resolves its device token via
`MultiTokenStore.getActive()?.deviceToken` (the `deviceTokenProvider` lambda passed into
`createTvApiClient`), so a cold start's concurrent `getHome`/`getConfig`/`getDiscover`/`getUpcoming`
calls (see [[project-ravilo-tv-startup-investigation-2026-08]]) each trigger it again.

Individually cheap (small JSON, a handful of sessions); cumulatively, real duplicated main-thread
work sitting on the path to first paint, with no source of invalidation reasoning needed — the
sessions list only ever changes through this object's own `add`/`remove`/`setActive`/`clear`.

### Requirement
Each platform actual gains a private in-memory cache of the parsed session list + active id,
populated on first read and invalidated (cleared, not merged) by every write:
- `getAll()` returns the cached list if present; otherwise loads, caches, and returns it.
- `getActive()` reads the (now-cached) list and the active id the same way it does today — the id
  itself is cheap enough (`ravilo_active_user`/`active_user`, a single small SharedPreferences value)
  that it doesn't need its own cache slot; the parse it currently forces via `getAll()`/`loadAll()`
  is what's eliminated.
- `add()`, `remove()`, `setActive()`, `clear()` write through to storage exactly as today, then
  invalidate the in-memory cache (simplest correct approach: null it out so the next read reloads,
  rather than hand-maintaining the cached copy in sync with every mutation).

No behavior change from the caller's perspective — same return values, same persistence — purely
removing redundant re-parsing within a single process lifetime.

## FR-RV-R211-2 — `Sora`/`SpaceGrotesk` stop rebuilding on every recomposition

### Problem
`Typography.kt:14-25`:
```kotlin
val SpaceGrotesk: FontFamily
    @Composable get() = FontFamily(Font(Res.font.space_grotesk, weight = FontWeight.SemiBold), ...)
val Sora: FontFamily
    @Composable get() = FontFamily(Font(Res.font.sora, weight = FontWeight.Normal), ...)
```
Both are plain `@Composable get()` properties, not `remember`ed — every composable that reads `Sora`
or `SpaceGrotesk` (`HeroCarousel.kt:79`, `Tile.kt`, `RaviloButton.kt`, `EpisodeCard.kt`,
`MultiEpisodeCard.kt`, `ProfileMenu.kt`, `ImdbChip.kt`, `TrailerOverlay.kt`, `SeerrSearchScreen.kt`)
constructs a brand-new `FontFamily` object (2-3 `Font()` entries each) on **every single
recomposition** of that composable — including `HeroCarousel`'s Ken-Burns drift, per-tile
focus-scale animation, and every scroll frame, all of which recompose constantly by design. The
underlying font bytes are still cached internally by Compose-resources' `Font()` loader (this is not
re-reading files off disk every frame), but the `FontFamily`/`Font` wrapper objects themselves churn
on Home's hottest paths for no reason — they never change after the app starts.

### Requirement
Wrap each in `remember`, computed once per composition tree rather than once per call site:
```kotlin
val SpaceGrotesk: FontFamily
    @Composable get() = remember { FontFamily(Font(Res.font.space_grotesk, weight = FontWeight.SemiBold), Font(Res.font.space_grotesk, weight = FontWeight.Bold)) }
```
(exact key/scoping decided at implementation time — a top-level `remember` with no keys is correct
here since the font family is a startup-time constant, not something that varies across the
composition's lifetime). Every existing call site (`val sora = Sora`, etc.) is unchanged — this is
purely a change inside the property getter.

## Investigated and not pursued
- **Moving `HttpClient(CIO)` construction out of Compose composition** (`RaviloRoot.kt:109-113`,
  `remember(baseUrl) { createTvApiClient(...) }`) — flagged during the investigation as "on the
  pre-first-frame path for no structural reason," but there is no earlier viable hook: `baseUrl` is
  itself only known after a `SharedPreferences` read inside the same composable
  (`raviloBaseUrl()`, `RaviloRoot.kt:99`), and the client is needed immediately by every subsequent
  screen. `remember` already prevents rebuilding it across recompositions, and CIO engine
  construction (selector manager + coroutine dispatcher setup, no I/O) is not expensive enough on its
  own to justify restructuring around. Not included as a requirement here — documented so it isn't
  independently "rediscovered" later without this context.

## Non-goals
- Not touching `RaviloApp.kt`'s six `LaunchedEffect`s (WS connect, config poll, remote-command
  collectors) — none of those were found to be duplicated work, just concurrent work (see
  [[project-ravilo-tv-startup-investigation-2026-08]] finding #3, addressed separately if at all).
- Not adding a cross-platform-shared `MultiTokenStore` implementation — it stays an `expect object`
  with two independent actuals; this phase adds the same caching *shape* to each, not a shared class.

## Acceptance
- `MultiTokenStore.getAll()`/`getActive()` called repeatedly within one process lifetime with no
  intervening write hit storage exactly once (verifiable via a debug log line or unit test on the
  Android actual; the Wasm actual has no test harness today per [[reference-e2e-mock-stack]] scope).
- `Sora`/`SpaceGrotesk` return the same `FontFamily` instance across repeated reads within one
  composition tree (verifiable via reference equality in a Compose UI test, or by inspection that the
  `remember` block only runs once per composer per the standard Compose contract).
- Both fixes verified via `:ravilo-ui:compileDebugKotlinAndroid` + `:ravilo-ui:compileKotlinWasmJs`
  at minimum; on-device confirmation is not required for either (neither changes visible behavior).

## Dev-review addendum (2026-08-27 — implementation notes)
1. **FR-RV-R211-2's sample code doesn't compile as written — corrected during implementation.**
   `Font()` (`org.jetbrains.compose.resources.Font`) is itself `@Composable`, and `remember`'s
   calculation lambda is annotated `@DisallowComposableCalls` — a composable function genuinely
   cannot be called from inside it. The actual fix calls both `Font(...)`s directly in the property
   getter (unavoidable — they need `@Composable` context to resolve the resource) and only wraps the
   **`FontFamily(...)` construction** in `remember(a, b) { FontFamily(a, b) }`, keyed on the two
   `Font` results. This still removes the wrapper-object (+ internal list) churn on every
   recomposition — the actual measured cost per the investigation — but the two `Font()` calls
   themselves still execute on every recomposition (a cheap resource-id/weight lookup per the type's
   own semantics, not a file read). The spec's original "wrap the whole thing in remember{}" framing
   was wrong; corrected here rather than silently shipping different code than what's written above.
2. **No unit test added for either fix** — `MultiTokenStore`'s Android actual needs a real
   `android.content.Context` (`RaviloAppContext.get().getSharedPreferences(...)`), which isn't
   available in a plain JVM unit test without Robolectric (not set up in this module); the Wasm actual
   needs a `localStorage` this test environment doesn't provide either. The `FontFamily` fix needs a
   Compose UI test harness to observe recomposition counts, which also doesn't exist here (same
   limitation [[phase-R196-remembered-track-regression]]'s test file documents for Compose-lifecycle
   bugs). Both fixes are mechanical enough that a careful read of the diff is the practical
   verification; a genuine correctness check is compile + (for the FontFamily fix) that the two
   targets that actually exercise Home's composables — `:ravilo-ui:compileDebugKotlinAndroid` and
   `:ravilo-ui:compileKotlinWasmJs` — still compile clean.
3. Verified via `:ravilo-ui:compileKotlinWasmJs`, `:ravilo-ui:compileDebugKotlinAndroid`,
   `:ravilo-android:compileDebugKotlin`, `:ravilo-phone:compileDebugKotlin`. Not on-device verified —
   neither fix changes visible behavior, so there's nothing an on-device pass would additionally catch
   beyond what compiling already confirms.

## Source references
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/screens/MultiTokenStoreAndroid.kt`
- `ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/screens/MultiTokenStoreWasm.kt`
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/RaviloApp.kt:287,288,340,365`
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/theme/Typography.kt:14-25`
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/HeroCarousel.kt:79`
- Investigation: [[project-ravilo-tv-startup-investigation-2026-08]]
