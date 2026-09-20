# Phase 250 — The e2e job's three Docker builds don't share a toolchain cache

## Status

`✓ Built` 2026-09-20 — written 2026-09-19 ("the spec now, the fix later"), built the next day. Measured
against a real CI run (`35448419659`), not guessed.

**Build note.** All 24 cache mounts across the three Dockerfiles now carry `gradle-shared` /
`konan-shared` / `npm-shared`. The mechanical form of FR-250-1 is a new fence,
`scripts/check-docker-cache-ids.sh`, run by `ci / checks` beside the other three: it rejects any
per-project suffix coming back **and** any cache mount with no explicit `id=` at all (an anonymous
mount is per-target, so it is the same bug spelled differently). Verified by breaking one id on
purpose and watching the fence fail. FR-250-2's measurement — the real CI run showing one download
instead of three — is recorded below once the first cold-cache run lands.

**⚠ Correction to FR-250-1, found by running it.** The requirement claimed "BuildKit's own locking on
a shared cache mount id makes concurrent local `docker compose build` safe too, at worst serializing
rather than corrupting." **That is wrong.** BuildKit's default is `sharing=shared` — concurrent
access is *allowed*, not serialized — and a plain `docker compose build` builds targets in parallel.
Measured locally on the first run after this change: three Gradle processes contended on
`/root/.gradle/caches/journal-1.lock`, whose lock has a **timeout** and fails the build rather than
waiting:

```
> Timeout waiting to lock journal cache (/root/.gradle/caches/journal-1). It is currently in use by
  another process.  Owner PID: 59  Our PID: 58
```

So a shared id *without* `sharing=locked` is strictly worse than the per-project ids it replaced. All
24 mounts now carry `sharing=locked`, which is what the requirement assumed was happening for free,
and the fence requires it. CI was never exposed to this (`ci.yml` builds the three images in three
separate sequential steps on purpose), which is precisely why it had to be caught by running the
local path rather than reasoned about.

## What is wrong

The `ci / e2e` job builds three images in sequence — `app`, `ravilo-web`, `ravilo-screen` — each its own
Gradle/Kotlin-Native/Kotlin-Wasm/Kotlin-JS toolchain. On a run with no reusable Docker layer cache
(confirmed live, run `35448419659`, triggered by files that changed the layer-cache key), all three
independently downloaded the **identical** Gradle distribution and Kotlin/Native toolchain (LLVM,
sysroot, ~600 MB) from scratch, back to back, in the same job:

```
14:22 app:           Downloading gradle-9.2.1-bin.zip … native deps (LLVM, sysroot)
14:39 ravilo-web:    Downloading gradle-9.2.1-bin.zip … native deps (LLVM, sysroot)   ← identical, again
14:48 ravilo-screen: Downloading gradle-9.2.1-bin.zip … native deps (LLVM, sysroot)   ← identical, again
```

The whole `e2e` job took **34 minutes**; the actual Playwright run inside it — all 42 specs, serially by
design (`workers: 1`, shared backend state) — took **33.7 seconds**. The job's entire cost is the Docker
builds, and a meaningful slice of that cost is redundant within a single run.

**Root cause:** each Dockerfile's `RUN --mount=type=cache,id=...` uses its own distinct id
(`gradle-jellystructure`/`konan-jellystructure`/`npm-jellystructure` for the main `Dockerfile`,
`gradle-ravilo-web`/`konan-ravilo-web`/`npm-ravilo-web`, `gradle-ravilo-screen`/`konan-ravilo-screen`/
`npm-ravilo-screen`). BuildKit cache mounts are shared across builds **only when the id matches** — three
different ids means three separate, cold caches for the exact same Gradle+Konan install, even run
sequentially on one runner in one job.

**A second, separate fact, worth recording so it isn't mistaken for solved by the fix below:** BuildKit
cache-mount content (as opposed to image layers) is not what `docker-compose.test.yml`'s
`cache_from`/`cache_to` (`type=local`) persists across CI runs — that mechanism captures image layers
only. A shared cache-mount id fixes the **within-one-run** triple-download; it does not, by itself,
guarantee the toolchain survives to the *next* run on a fresh runner. Whether it does in practice
(BuildKit's local cache directory sometimes outliving a runner between consecutive jobs) is not
something to design around — see the non-goal below.

## Requirements

**FR-250-1 — One shared cache-mount id per toolchain kind, across all three Dockerfiles.** Every
`id=gradle-*` becomes `id=gradle-shared`; every `id=konan-*` becomes `id=konan-shared`; every
`id=npm-*` becomes `id=npm-shared`. No other change to any Dockerfile. Safe because
`docker-compose.test.yml`'s own build sequencing already serializes `app` → `ravilo-web` →
`ravilo-screen` (explicitly, "to keep peak memory to one Kotlin toolchain's worth") — there is no
concurrent access to guard against in CI; BuildKit's own locking on a shared cache mount id makes
concurrent local `docker compose build` (no explicit sequencing) safe too, at worst serializing rather
than corrupting.

**FR-250-2 — Measured, not assumed.** Acceptance is a real CI run showing the second and third builds'
Gradle/Konan download step either skipped entirely or clearly reduced, not a theoretical argument.

## Non-goals

- Making the toolchain survive to a *separate* CI run on a fresh runner. That needs either a
  BuildKit cache exporter that captures `--mount=type=cache` content (e.g. `type=gha`) or moving
  compilation out of Docker entirely onto the runner (where `actions/cache` already works today for
  the `unit`/`android-release` jobs) — a materially bigger change, and the natural next phase if this
  one's measured savings aren't enough on their own.
- Sharding Playwright's own test execution. Measured at 33.7s for the whole suite — not the cost
  center this phase (or a follow-on) should spend effort on.
- Any change to what gets built, tested, or asserted. Pure build-cache plumbing.

## Acceptance

1. All three Dockerfiles' cache-mount ids match per FR-250-1; `docker compose config` / a plain grep
   confirms no `-jellystructure`/`-ravilo-web`/`-ravilo-screen` suffix remains on any `id=`.
2. A real CI run (cold layer cache, e.g. triggered by a `build.gradle.kts` touch) shows the Gradle
   distribution and Kotlin/Native toolchain downloaded once, not three times.
3. `ci / e2e`'s wall-clock on that same run is measurably shorter than `35448419659`'s 34 minutes.
4. `ci / unit` and `ci / android-release` (which build on the runner directly, not in Docker) are
   untouched by this phase and unaffected.

## Open questions

1. Whether BuildKit's local cache-mount storage on GitHub's `ubuntu-latest` runners ever survives
   between separate job runs in practice (same physical host reused) — worth checking empirically once
   FR-250-1 ships, since if it does, some of phase 250's benefit may already show up on warm-cache runs
   for free. Not something to build around either way.
