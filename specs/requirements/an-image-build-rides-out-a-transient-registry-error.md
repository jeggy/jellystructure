# An image build rides out a transient registry error, and a real failure still fails at once

Task: DEB-4106

> CI watch, 2026-11-09 (DEB-4105 → DEB-4106): five Publish runs in about 20 hours failed because an upstream
> registry answered one request with a 5xx. No code was involved. Each time the next push went green with no
> change in between. One 5xx fails the image, then the job, then the whole Publish run, and that SHA gets no
> image.

## Scope

This covers the Gradle builder stage of the three Kotlin Dockerfiles: `Dockerfile` (app), `ravilo-web/Dockerfile`
and `ravilo-screen/Dockerfile`. Two callers build them, `ci / e2e` (`docker compose -f docker-compose.test.yml
build …`, three sequential builds) and `publish (…)` (`docker/build-push-action`). This spec does not change
what gets built, tested or asserted, so phase 231's gate is unchanged: nothing publishes before CI passes.
Phase 250's shared cache mounts (`gradle-shared`, `konan-shared`, `npm-shared`, `sharing=locked`) and its fence
stay as they are, and so do phase 319's lockfiles. `4142d5c0` (committed lockfiles in image builds) pins
versions. It does not retry, and the 07:15 failure came after it.
`avsync/Dockerfile` is out of scope. It has no Gradle step and has not failed this way.

## What happens today (traced 2026-11-09)

| Run | Job | Failing download |
|---|---|---|
| `42476427909` | `ci / e2e` | `:kotlinNpmInstall` (under `:ravilo-cast:syncCastReceiver`), yarn registry `500` |
| `42477725633` | `ci / e2e` | `:kotlinNpmInstall` (under `:ravilo-screen:syncScreenReceiver`), yarn registry `500` |
| `42568463083` | `ci / e2e` | `:kotlinNpmInstall`, `504 Gateway Timeout` |
| `42574793608` | `publish (ravilo-web)` | `:kotlinWasmYarnSetup`, `Could not resolve com.yarnpkg:yarn:1.22.17` |
| `42578208660` | `ci / e2e` | `:ravilo-i18n:compileKotlinJs`, `kotlin-compiler-embeddable-2.3.21.jar` → `504` |

- **Every failure is a download inside a compile step.** Each Dockerfile has one warm-up `RUN` before
  `COPY src`, `./gradlew [:<project>:]dependencies --no-daemon 2>/dev/null || true`. It resolves only the named
  project's configurations. It never runs the yarn/node setup or `kotlinNpmInstall`. It also hides its own
  errors and cannot fail. So the yarn distribution, the npm packages and another module's compiler classpath
  are all fetched for the first time by the compile `RUN`s, and a 5xx there ends the build.
- **Nothing retries for long enough.** Neither the Dockerfiles, `gradle.properties` nor `publish.yml`/`ci.yml`
  sets a retry or a timeout on a download. Gradle's own repository retry is still at its defaults, a few
  attempts with a backoff that starts at 125 ms, so it gives up within about a second. It did not ride out the
  504 in `42578208660`. The npm install that `kotlinNpmInstall` runs reports a 5xx as `Request failed "500 …"`
  and stops.
- **A warm layer cache hides the problem, and a cold one exposes it.** Publish's `type=gha` cache and e2e's
  `/tmp/.buildx-cache` skip these steps on an unchanged build script. Any change to the cache key
  (`build.gradle.kts`, `libs.versions.toml`, a Dockerfile, a lockfile) makes every download happen again,
  and that is the run that meets the 5xx.

## Requirements

**FR-1 — A transient upstream error during a dependency download is retried, with a bound, before the image
build fails.** A transient error is an HTTP 5xx, a connect or read timeout, or a reset connection. This applies
to every download in the three Dockerfiles' builder stage: the Gradle distribution, Maven/Ivy artifacts
(including the Kotlin compiler jars and `com.yarnpkg:yarn`), the Node and yarn setup, and the npm packages
`kotlinNpmInstall`/`kotlinWasmNpmInstall` fetch. A download is tried at least three times. The retries
together span at least two minutes, with growing pauses between them, so a gateway that is down for a minute
does not fail the run. The bound is finite. An outage longer than that still fails the build.

**FR-2 — A compile or test failure is never retried.** A Kotlin compile error, a failing test or a broken build
script fails the build on its first attempt, in the same wall-clock time it takes today. This is why the retry
sits on the downloads and not on the whole build step. Re-running a red cold build costs another 10–30 minutes
on the 2-core runner. A real red would then take two or three times as long to report, and it would look
flaky.

**FR-3 — Downloads happen before the compile, and their failure is legible.** Each Dockerfile fetches everything
its compile `RUN`s will download in a fetch step that compiles nothing: every configuration the later tasks
resolve, the Node/yarn setup, and the npm install. The compile `RUN`s then find all of it in the shared cache
mounts. Only the fetch step carries FR-1's retry. Its errors are not hidden (`2>/dev/null`). When the retries
run out, the build fails at the fetch step, and the log shows the upstream error the last attempt saw. A
repeat of the five runs above then reads as a registry outage, not as a failure in
`compileKotlinJs`. The fetch step's position may stay before `COPY src` (its layer cache then survives a
source-only change) if the tasks it runs can be resolved without the sources. That is the engineer's call,
measured.

**FR-4 — A ride-out is visible.** Each retried attempt prints one line to the build log naming the attempt, the
pause and the error it is retrying. A run that survived a 5xx is then distinguishable from a clean run, so the
CI watch (DEB-4105) can count sightings without a red.

**FR-5 — No new third-party code on the publishing path.** The retry is a script in this repository (under
`scripts/` or inline in the `RUN`) and/or Gradle's own repository retry properties. No new GitHub Action is
added. No new npm or Maven dependency is added. The repository is public, and `publish.yml` pushes the images
prod runs.

## Recommended approach (the build's call, not a requirement)

Two layers, both small:

1. **Raise Gradle's repository retry.** Set `systemProp.org.gradle.internal.repository.max.retries` and
   `systemProp.org.gradle.internal.repository.initial.backoff` in `gradle.properties` so that one download's
   retries span FR-1's two minutes. This covers every Maven/Ivy fetch: the compiler jar, `com.yarnpkg:yarn` and
   the Node distribution. It also helps developers and the runner-side `unit`/`android-release` jobs for free.
   Check the exact property names and the backoff growth against the Gradle 9.2.1 source before relying on
   them.
2. **Make the warm-up a real, retried fetch step.** Replace each Dockerfile's
   `dependencies … 2>/dev/null || true` with one script, the same for all three. It runs the fetch tasks
   (FR-3) in a loop of three attempts with 30 s and 90 s pauses, prints FR-4's line on each retry, and exits
   non-zero after the last attempt. The npm/yarn fetch is a Gradle task, not a Maven download, so Gradle's
   property does not reach it. This loop is what covers the three `kotlinNpmInstall` sightings. The loop
   re-runs only downloads, so FR-2 holds by construction. The compile `RUN`s keep no loop.

Rejected:
- **A job-level retry of the build step** (a retry action, or a second `build-push-action` step on
  `failure()`). It breaks FR-2 because it re-runs real reds. The retry action also breaks FR-5.
- **Relying on a warmer cache** (a persisted dependency layer). Publish already uses `type=gha mode=max`, and
  e2e already uses a local layer cache. The failures happen on the runs where the cache key changed, so a
  cache cannot help those. Phase 250's non-goal (keeping cache-mount content across runs) stays a non-goal.

## Acceptance

1. **A transient error is ridden out.** Run the shared fetch script locally against a command that fails twice
   with a 5xx-shaped error and then succeeds. It succeeds on attempt 3, prints two FR-4 lines and exits 0. A
   command that always fails exits non-zero after attempt 3 and prints the last error.
2. **A real red fails once.** A local `docker build` of `ravilo-web/Dockerfile` with a deliberate Kotlin compile
   error fails at the compile `RUN` after one attempt, with no retry line in the log.
3. **The compile steps download nothing.** On a cold local build of each of the three Dockerfiles (cache mounts
   cleared), every `Download`/`Downloading` line and every yarn/npm fetch appears under the fetch step and none
   under a later `RUN`. This is FR-3's completeness. Phase 250 measured its claim the same way.
4. **CI green, and both callers unchanged.** One `Publish` run on `main` that changes the cache key (for
   example the commit that lands this) passes `ci / e2e` and both `publish (…)` jobs. `scripts/check-docker-cache-ids.sh`
   still passes, and so does phase 250's acceptance that the Gradle distribution and the Konan toolchain are
   downloaded once per e2e run.
5. **Watched, not assumed.** After it lands, the CI watch records each `ci / e2e` or `publish` failure whose
   cause is an upstream 5xx on this row. If a sighting comes back (an outage longer than FR-1's bound), this
   spec gets a dated amendment with the measured outage length. The bound is not silently widened.
