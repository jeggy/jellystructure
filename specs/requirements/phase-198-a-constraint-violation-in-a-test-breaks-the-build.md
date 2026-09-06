# Phase 198 — One test exercises a UNIQUE constraint, and that breaks the whole backend test task

## Status
`✓ Built` 2026-09-06 — found while auditing the test suite after phase 197. Not dev-reviewed.

## What happens

`./gradlew linuxX64Test` fails, on a clean run, with one of two errors depending on prior state:

```
> Multiple entries with same key: 108=…TestOutputStore$Index@… and 108=…TestOutputStore$Index@…
```
```
> Buffer underflow.
```

Neither names a test, and **no test is failing** — running the linked binary directly passes 221/221:

```
./gradlew linkDebugTestLinuxX64 && ./build/bin/linuxX64/debugTest/test.kexe
[==========] 221 tests from 30 test cases ran.
[  PASSED  ] 221 tests.
```

The failure also hides behind Gradle's up-to-date checks: with stale results present the task is
skipped and the build reports SUCCESS, so the suite looks healthy until someone runs it clean.

## Root cause

`MediaJobDedupeTest.secondActiveEnqueueUnderTheSameDedupeKeyIsRejected` is Phase 164's proof
(FR-164-1) that the `media_job_dedupe_active` partial unique index — not a check-then-insert in
Kotlin — is what stops the same unit of work being queued twice. It deliberately inserts a duplicate
and asserts it throws. **The test is correct and must keep doing exactly that.**

When the constraint fires, sqliter's default `WarningLogger` prints to **stdout**:

```
executeNonQuery error | error code SQLITE_CONSTRAINT
co.touchlab.sqliter.interop.SQLiteExceptionErrorCode: executeNonQuery error
    at 0   test.kexe   0xd304bd   kfun:kotlin.Throwable#<init>…
    …~40 more frames…
```

Kotlin/Native's test runner reports results over the **TeamCity service-message protocol**, on that
same stdout — and in that protocol `|` is the **escape character**. The driver's first line contains
a literal pipe (`executeNonQuery error | error code SQLITE_CONSTRAINT`), so it corrupts the message
stream. Gradle's parser then attributes output to no test case and dies:

```
Error while processing test process output message "executeNonQuery error | error code SQLITE_CONSTRAINT"
java.lang.NullPointerException: Cannot invoke "java.util.Map.computeIfAbsent(…)" because "testCaseRegions" is null
    at org.gradle.api.internal.tasks.testing.junit.result.TestOutputStore$Writer.mark(TestOutputStore.java:122)
```

The half-written output store is what produces `Multiple entries with same key` / `Buffer underflow`
on the following run.

## Problem, stated plainly

A passing test breaks the build, because a third-party driver writes an unstructured stack trace —
containing the protocol's own escape character — onto the channel the test runner uses to report
results. Nothing about it is visible as a test failure, and up-to-date checking hides it until a
clean run.

## Goal

`./gradlew linuxX64Test` passes on a clean run and reports all 221 tests, with the dedupe test still
proving the constraint fires.

## Requirements

### FR-198-1 — The driver stops writing to stdout

`createDatabase` passes `loggingConfig = DatabaseConfiguration.Logging(logger = NoneLogger)`.

sqliter's default is `WarningLogger`, which `println`s the message and then `printStackTrace()`s the
exception. Both are redundant here: every call site that can hit a SQLite error already catches it
and reports it through jellystructure's own `Logger`, with context the raw dump does not have (which
item, which job, which route). The driver's copy adds ~40 unattributed frames to container stdout and,
in a test process, corrupts the result stream.

This is deliberately global rather than test-only. A test-only seam would fix this one test and leave
the same trap armed for the next test that touches a constraint — and constraint-exercising tests are
exactly the ones worth writing.

### FR-198-2 — The dedupe test is unchanged

No `try`/`catch` softening, no dropping to a Kotlin-side pre-check, no excluding it from the Gradle
run. It asserts a real `SQLITE_CONSTRAINT` from a real index and must continue to.

### FR-198-3 — Verified on a clean run, not an up-to-date one

Acceptance requires deleting `build/test-results/linuxX64Test` first. The task is up-to-date-checked
on its results directory, so a stale pass masks this entirely — which is why it survived unnoticed
through phase 197's CI work, where `linuxX64Test` is not what CI runs.

## Non-goals

- No change to what jellystructure's own `Logger` records. This removes a duplicate, contextless
  channel, not a diagnostic one.
- No Gradle-side workaround (disabling output capture, custom test listeners). The upstream
  fragility is real, but the fix within our control is to stop emitting the output.
- No sqliter/SQLDelight version change.

## Acceptance

1. `rm -rf build/test-results/linuxX64Test && ./gradlew linuxX64Test` succeeds and records 221 tests.
2. `MediaJobDedupeTest.secondActiveEnqueueUnderTheSameDedupeKeyIsRejected` still passes, still via
   `assertFailsWith`.
3. No `executeNonQuery error` text appears in the test process output.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/db/Database.kt:22-55` — `DatabaseConfiguration`; the
  `loggingConfig` parameter was left at its default.
- `src/linuxX64Test/kotlin/dev/jellystructure/media/MediaJobDedupeTest.kt:46-56` — the test, and its
  `assertFailsWith<Throwable>`.
- `src/commonMain/sqldelight/dev/jellystructure/db/33.sqm` — `media_job_dedupe_active`, the index
  under test.
- `co.touchlab:sqliter-driver:1.3.1` — `WarningLogger` (default) vs `NoneLogger`, both in
  `co.touchlab.sqliter`.
- Phase 164 (FR-164-1) — why the constraint, rather than Kotlin, is the enforcement point.

## Open questions

1. **Does anything else in the suite write a `|` to stdout?** jellystructure's own `Logger` writes to
   stdout too, and a log line containing a pipe would corrupt the same stream. Nothing does today, but
   it is the same trap. Worth considering whether `Logger` should be silenced under test, or whether
   the test runner should be given a separate channel.
