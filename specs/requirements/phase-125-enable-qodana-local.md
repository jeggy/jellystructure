# Phase 125 — Enable Qodana locally (Community linter, no cloud) (FR-QA1)

## Goal
Run JetBrains **Qodana** against this project **entirely locally** — no Qodana Cloud, no token, no CI —
as a repeatable code-health pass, and make its output usable as the input to the remediation work
(**Phase 126**). This phase is the enablement + configuration + local-run process; it does **not** fix
any reported issues.

## Current state (verified by running it)
- `qodana.yaml` exists at the repo root (committed) but points at the **release** linter
  `linter: jetbrains/qodana-jvm:2026.1` with `profile.name: qodana.starter`.
- **The release linter cannot run locally.** Since v2023.2, `jetbrains/qodana-jvm:*` release images
  **require** a Qodana Cloud connection: running it offline exits with *"release versions of Qodana
  Linters require connection to Qodana Cloud … provide the token as the `QODANA_TOKEN` environment
  variable."* — which directly conflicts with the "local only, no cloud" requirement.
- **The Community linter runs fully offline.** `jetbrains/qodana-jvm-community:2026.1` completed with no
  token and produced a real report (103 findings across 32 files spanning both `src/` and `ravilo-ui/`).
- **The committed `qodana.starter` profile is near-useless for this codebase.** It surfaced only 4
  inspection types, 100 of 103 being two cosmetic Kotlin-modernization inspections
  (`ConvertLongToDuration` ×78, `CanConvertToMultiDollarString` ×23). A meaningful pass needs the
  broader **`qodana.recommended`** profile.
- **Gradle-daemon lock gotcha (verified).** A running Gradle daemon holds
  `.gradle/<ver>/fileHashes/fileHashes.lock`; Qodana's containerised Gradle project-import then
  deadlocks — *"Timeout waiting to lock file hash cache … currently in use by another process"* — and
  aborts with an empty report. The daemon must be stopped first.
- **Community coverage caveat.** The Community linter disables Ultimate-only modules (deep dataflow,
  duplicate detection, some Java analyses). This is acceptable for a Kotlin/KMP project — standard
  Kotlin/Java inspections all run.

## Requirements

### A. Point the config at the offline Community linter + a useful profile
1. `qodana.yaml`: set `linter: jetbrains/qodana-jvm-community:2026.1` (offline, no token) and
   `profile.name: qodana.recommended` (broad, meaningful — the starter profile hides everything).
2. Keep `projectJDK: "21"` (matches the JBR-21 build JDK). Remove/omit any cloud-only keys.

### B. A documented, one-command local run
1. Add `scripts/qodana.sh` (bash, `#!/usr/bin/env bash` + `set -euo pipefail` + `cd "$(dirname "$0")/.."`
   like the other scripts). It must:
   - `./gradlew --stop` first (avoid the fileHashes-lock deadlock).
   - `docker run --rm` the Community linter with the project mounted read-write at `/data/project/` and
     a **gitignored** results dir mounted at `/data/results/` (e.g. `.qodana/`), `--save-report`.
   - Print where the SARIF (`qodana.sarif.json`) and HTML report landed.
2. `.gitignore`: ensure `.qodana/` and `qodana.sarif.json` are ignored (already added this session).
3. No CI wiring, no `qodana.cloud` project, no token — invocation is manual/local only.

### C. Triage input for Phase 126
1. The first `qodana.recommended` run's `qodana.sarif.json` is the authoritative issue list that Phase
   126 groups and remediates. Do **not** commit a Qodana *baseline* yet — the intent is to fix the
   backlog, not freeze it. (A baseline can be added later, after the backlog is cleared, so future runs
   only flag *new* issues.)

## Scope
- `qodana.yaml` (linter + profile), `scripts/qodana.sh` (new), `.gitignore` (done).
- No source changes; no CI; no cloud.

## Non-goals
- No Qodana Cloud, no `QODANA_TOKEN`, no GitHub Action / CI gate.
- No fixing of any reported issue (that is Phase 126).
- No custom `.inspectionKts` scripts (Ultimate-only; unavailable in Community).

## Acceptance
- `scripts/qodana.sh` runs the Community linter offline (no token) to completion and writes a SARIF +
  HTML report to a gitignored dir, even when a Gradle daemon was running beforehand.
- The report reflects the `qodana.recommended` profile (substantially more than the 4 starter-profile
  inspection types).
- Nothing Qodana-related is committed except `qodana.yaml`, `scripts/qodana.sh`, and the `.gitignore`
  entries.
