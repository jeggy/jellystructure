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
   *2026-11-09, measured:* in Gradle 9.2.1 the attempt-count property is `org.gradle.internal.repository.max.tentatives`
   (total attempts, not `max.retries`; that name is silently ignored), and the pause doubles from
   `initial.backoff` (ms). Against a server that answers every request 503, 6 attempts from 4000 ms made 6
   requests over 124 s.
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

## Amendment, 2026-11-27 — a plugin that will not resolve (DEB-5946)

> CI watch (DEB-5945 → DEB-5946): Publish `45514238176` (`ee9b43002`), `45603662095` (`432f7923d`) and
> `45985231412` (`106f57b9c`) each failed `ci / e2e` in the app `Dockerfile`'s fetch step, with
> `Plugin [id: 'app.cash.sqldelight', version: '2.0.2'] was not found … could not resolve plugin artifact`.
> No `retry-transient:` line appears in any of the three logs. Later runs passed with no change.

**What the three logs show (read 2026-11-27).**

- **Gradle's own retry ran, and the outer retry never did.** In each log the build reaches its first
  plugin resolution about 20 s in, prints nothing more for 249–250 s, and then fails. That is two repositories ×
  this spec's raised Gradle retry: 6 attempts with the pause doubling from 4 s is about 124 s each. Google is
  content-filtered out for this group, so the two are the Plugin Portal and Maven Central, and the Portal answers
  this marker with a `303` to `repo.maven.apache.org`, so both are the same host. A missing artifact answers
  `404` at once and Gradle does not retry it. A four-minute silence therefore means the requests kept failing
  in a way Gradle counts as retryable, for longer than about four minutes, on three separate days.
- **The cause never reaches the log, so FR-3 does not hold here.** Gradle's plugin resolver reports a failed
  marker as `could not resolve plugin artifact` and drops the underlying error. The 5xx, the timeout or the
  `429` it retried does not appear in the output. `retry-transient.sh` (FR-1) and the init script's rethrow both
  match on that underlying error, so they see nothing transient and exit on the first attempt. FR-3 promised
  that when downloads fail, the log shows the upstream error the last attempt saw. For a plugin, it shows none.
- **Why it is always `app.cash.sqldelight` is not known.** It is the third entry in the root `plugins {}` block,
  after two Kotlin plugins on the same host that resolved. The logs cannot tell a short outage from per-client
  throttling. This amendment's FR-6 is what makes the fourth sighting answer that.

**The trade-off the CI watch asked about.** One option is to count the bare `could not resolve plugin artifact`
as transient. That breaks FR-2: a mistyped plugin id or version prints exactly that message. It would fail
after about 20 s, then be re-run twice behind 30 s and 90 s pauses, and go red after roughly three minutes
instead of twenty seconds, looking like flake. **Rejected.** A broken build script must still fail on its first
attempt. The fix has to show the cause, not loosen the match.

**FR-1, amended.** The transient errors are an HTTP 5xx, an HTTP `429 Too Many Requests`, a connect or read
timeout, and a reset connection. A `429` asks the client to try again later by definition, and a mistyped
coordinate never produces one. The bound and the pauses are unchanged.

**FR-6 — A plugin is a download like any other.** The plugins the builds apply through `plugins {}` blocks are
resolved in the fetch step, the plugin markers and the artifacts behind them (`libs.versions.toml`'s `[plugins]`,
and the settings plugin). FR-1 to FR-4 apply to them as to any other download:
- a transient upstream error while resolving one is retried under FR-1's bound, and each retry prints the FR-4 line;
- a plugin id or version that does not exist fails on the first attempt, as FR-2 requires, in about the time it
  takes today;
- when the retries run out, the log shows the upstream error the last attempt saw (FR-3), not only
  `could not resolve plugin artifact`.

**Recommended approach (the build's call).** Check first whether the cause survives in Gradle 9.2.1's
`--stacktrace` output for a plugin-resolution failure. If it does, running the fetch step with `--stacktrace`
is the whole fix, and the existing pattern then matches. If it does not, which is the expected answer because the
resolver turns the failure into a "not found" result, the init script resolves the markers itself before the
root `plugins {}` block does. In `settingsEvaluated`, it resolves each `[plugins]` entry's
`<id>:<id>.gradle.plugin:<version>` through a detached configuration over `pluginManagement`'s repositories, and
calls `rethrowFailure()` so the real error reaches the log. The markers are then in the cache when the plugins
block runs. Add `429` to both patterns (`retry-transient.sh` and `fetch-dependencies.init.gradle`), and add it to
Acceptance 1's local test. Rejected: running the fetch step with `--info`. It would put the cause in the log, but
it adds thousands of lines to every build log, including the green ones.

**Acceptance, added.**
6. **A mistyped plugin fails once.** A local `./scripts/fetch-dependencies.sh` with a plugin version in
   `libs.versions.toml` changed to one that does not exist fails on its first attempt, prints no
   `retry-transient:` line, and names the missing coordinate.
7. **A plugin behind a transient error is retried, and the error is shown.** The fetch script, run with
   `pluginManagement` pointed at a local server that answers every request `503` (or `429`), prints FR-4's
   lines and exits non-zero after the last attempt. The last attempt's `503`/`429` is in the log.
8. **Watched.** The next sighting on the CI watch quotes the upstream error the log now shows. If it is
   throttling (`429`), not an outage, that sighting is this spec's next amendment, with the measured length.
   The bound is not widened silently.

*Built 2026-11-27 (DEB-5946), measured on Gradle 9.2.1 against a local server answering `503`/`429`:* `--stacktrace`
prints no cause for a failed plugin marker, only the `UnknownPluginException` text, so the init script resolves the
`[plugins]` markers (and the plugin artifacts behind them) in `settingsEvaluated`. A `503` and a `429` reach the log as
`Received status code 5xx/429`, and `retry-transient.sh` retries both. A version that does not exist fails on the first
attempt with `Could not find <coordinate>`. The settings plugin (`org.meshtastic.flatpak.sources.settings`) is resolved
before `settingsEvaluated` fires, so the init script cannot pre-resolve it; it stays covered by Gradle's own retry only.

## Amendment, 2026-11-28 — Central throttles the runner for longer than any retry (DEB-5992)

> CI watch (DEB-5991 → DEB-5992): Publish `46046043315` (`edd5c8ba0`), `46061404458` (`6c3b05d3e`) and
> `46064429609` (`e8068f940`), all on 2026-11-27, failed `ci / e2e` in the app `Dockerfile`'s fetch step on
> `429 Too Many Requests` from `repo.maven.apache.org`. `b25395bf2` and `d9a0b7ee6` passed in between and after
> with no change to the fetch step. This is Acceptance 8's sighting. It is throttling, not an outage.

**What the three logs show (read 2026-11-28).**

- **DEB-5946 works as written.** The `429` reaches the log, `retry-transient.sh` retries it, and each retry prints
  its FR-4 line.
- **The throttle outlasted every attempt.** Every attempt in all three runs ended on the same `429`:

  | Run | Fetch step, start to failure | `429` on |
  |---|---|---|
  | `46046043315` | 17 min 01 s | `org.robolectric:android-all-instrumented:14-robolectric-10818077-i7` (jar) |
  | `46061404458` | 10 min 45 s | the same jar |
  | `46064429609` | 27 min 16 s | `kotlin-gradle-plugins-bom` / `kotlin-gradle-plugin` `2.3.21` (POMs) |

  One outer attempt takes 4–9 minutes, because Gradle's own retry (6 attempts per request, the pause doubling from
  4 s) runs inside it. The 30 s and 90 s pauses are a small part of that. Central refused these runners for at
  least 10, 17 and 27 minutes.
- **The throttle can come before the build asks for anything.** In `46064429609` the failing request was among the
  first the fetch step makes: FR-6 resolves the plugin markers in `settingsEvaluated`, before any configuration.
  So the limit is not only a reaction to this build's own volume, and fewer bytes alone will not prevent it.
- **The image build downloads 419 MB from Central that it never uses.** Since DEB-4745 (`0722db463`, 2026-11-20),
  `ravilo-ui` declares three `robolectricAndroidAll_*` configurations. They hold the `android-all-instrumented`
  jars (7.0, 14 and 15) for its Android unit tests. `fetchDependencies` resolves every resolvable configuration,
  so every cold image build downloads them. No compile `RUN` in the three Dockerfiles runs a test. Two of the three
  sightings failed on one of those jars.
- **Every cold image build downloads everything from Central again.** Phase 250's cache mounts do not outlive the
  runner, and the layer caches skip the fetch step only when its cache key is unchanged. That is the same gap the
  2026-11-09 analysis found, now hit by a rate limit instead of a 5xx.

**The trade-off the CI watch asked about.**
- **A longer bound, or one that honours `Retry-After`: rejected.** Covering the measured 27 minutes means a fetch
  step that keeps trying for over half an hour, on a run that already spends 10–27 minutes failing. A real outage
  would then take most of the e2e job's 120-minute ceiling to report red. Gradle's transport does not read
  `Retry-After`, and `retry-transient.sh` sees only Gradle's output, so honouring the header would mean replacing
  Gradle's transport. FR-1's bound and pauses stay as they are. Acceptance 5 is answered here, with the measured
  lengths, and the bound is not widened.
- **A mirror ahead of Central: not now.** A mirror changes which host the bytes in the prod images come from. This
  repository has no dependency verification metadata, so Gradle would trust the mirror's own checksums. That is
  the owner's call, not a CI fix. It is the next step if FR-7 and FR-8 do not hold (Acceptance 11).
- **Carry what the last build already had, and stop downloading what nothing uses: adopted** as FR-7 and FR-8.
  A run whose dependencies have not changed then asks Central for nothing, and that covers all three sightings.

**FR-7 — The image fetch step does not resolve test-only configurations.** A configuration that only a test task
resolves is not fetched in an image build. The Robolectric `android-all-instrumented` configurations are the
measured case. FR-3 still holds as Acceptance 3 measures it: nothing an image compile step downloads moves out of
the fetch step. The runner-side jobs (`unit`, `android-release`) do not use the fetch step, so DEB-4745's offline
Robolectric jars are unaffected.

**FR-8 — A warm image build does not ask a repository for what it already had.** Both callers' image builds
(`ci / e2e` and `publish (…)`) start from the Maven/Ivy artifacts that a recent build on `main` downloaded. Only
an artifact missing from that set is requested from a repository. A build whose version catalog and lockfiles are
unchanged since that build makes no request to Maven Central or the Plugin Portal. A build that bumps one
dependency requests only what changed.
- A missing, partial or evicted carried set falls back to today's behaviour: everything is downloaded under FR-1.
  It never fails the build.
- The carried set is written only by runs on `main`. A pull request run may read it but never writes it.
- FR-5 holds. `actions/cache` already runs in `ci / e2e`. Docker's named build contexts (`additional_contexts` in
  compose, `build-contexts` in `docker/build-push-action`) are part of Docker. Nothing new is added on the
  publishing path.

**Recommended approach (the build's call, measured).**
1. **FR-7:** in `fetch-dependencies.init.gradle`, skip configurations that only test tasks resolve: the
   `robolectricAndroidAll_*` ones, and those whose name marks them as test classpaths (`*Test*`, `*UnitTest*`). Use
   Acceptance 3's cold-build method to confirm that no compile `RUN` downloads anything afterwards.
2. **FR-8:** use Gradle's read-only dependency cache (`GRADLE_RO_DEP_CACHE`), which exists for this case. It is a
   directory holding a `modules-2` copied from another Gradle home. Gradle reads artifacts from it and never writes
   there. Mount it read-only into the fetch step and the compile `RUN`s from a named build context, and set the
   variable. Then the `gradle-shared` cache mount and phase 250's fence stay as they are. Produce the set from a
   green build's `gradle-shared` mount, for example with a small export stage built with `--output type=local`.
   Keep it in `actions/cache`, keyed on the version catalog and the lockfiles, with a prefix `restore-keys` so that
   a bump starts from the previous set. Check the read-only cache's layout rules against Gradle 9.2.1 before relying
   on it. Measure the set's size against the repository's 10 GB Actions cache budget, which it shares with the
   buildx layers and `~/.konan`.

**Acceptance, added.**
9. **Nothing unused is fetched.** A cold local build of each of the three Dockerfiles prints no
   `android-all-instrumented` request, and Acceptance 3 still holds.
10. **A warm build asks Central nothing.** A local cold build of `Dockerfile`, given a carried set from an earlier
    build of the same commit, makes no request to `repo.maven.apache.org` or `plugins.gradle.org` in its fetch
    step or after it. Point both at a local server that answers every request `429` to show it: the build passes.
    The same build with the carried set missing still passes against the real repositories.
11. **Watched.** The CI watch records whether a carried set was restored on each `429` sighting. If a run that
    restored one still fails on a `429` for an artifact the set should have held, FR-8 is broken and that run is a
    bug against this spec. If the failing runs are all dependency bumps (the set cannot hold a new version), the
    mirror goes to the owner as a decision, with this amendment's measured lengths.
