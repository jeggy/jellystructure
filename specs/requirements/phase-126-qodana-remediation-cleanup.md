# Phase 126 — Qodana remediation, part 1: profile tuning + mechanical cleanup (FR-QA2)

## Goal
Take the `qodana.recommended` backlog from **550 findings** down to a small, real set in two moves:
(1) **suppress** the inspection families that are false positives *given this codebase's deliberate
conventions* (with documented rationale — do **not** churn correct code), and (2) **fix** the safe,
mechanical, IDE-automatable cleanups. The judgment-heavy findings are **Phase 127**.

## Current state (from a local `qodana-jvm-community:2026.1 --profile-name qodana.recommended` run)
550 results (300 High / 250 Moderate) across 34 rules, over `src/` and `ravilo-ui/`.

## Requirements

### A. Suppress convention-clashing inspections (≈200 findings) — `qodana.yaml` `exclude`, with rationale
These are **not** defects in this codebase; suppress each in `qodana.yaml` (each with a one-line comment
saying why), do **not** "fix" them:
1. **`FunctionName` (93)** — every hit is a `@Composable` function (`RankTile`, `PairingScreen`,
   `CertBadge`, `PlayerVideoSurface`, …). **PascalCase for `@Composable` is the official Compose
   convention** — renaming would be *wrong*. Suppress.
2. **`ConvertLongToDuration` (78)** — the codebase deliberately uses `Long` milliseconds for
   `delay()`/timeouts/ktor/`-progress` math (they cross expect/actual, SQLDelight, and native APIs that
   take `Long`). Converting to `kotlin.time.Duration` is pure churn with no benefit here. Suppress.
3. **`CanConvertToMultiDollarString` (23) + `CanUnescapeDollarLiteral` (5)** — the flagged strings are
   ffmpeg/shell command lines and hand-built JSON with intentional literal `$`. The multi-dollar prefix
   is a brand-new Kotlin idiom that adds no clarity to these; leaving explicit `\$`/`$` is clearer.
   Suppress.
4. **`UnstableApiUsage` (1)** — `settings.gradle.kts:41` uses Gradle's `@Incubating`
   `dependencyResolutionManagement { repositories { … } }` — the **recommended** Gradle DSL. Suppress
   (or accept via a file-level annotation); not a real instability risk.

### B. Fix the safe mechanical backlog (≈229 findings)
All IDE-automatable, low-risk — apply the fix and verify compilation across every target:
1. **Dead-weight removal:** `KotlinUnusedImport` (62), `UnusedVariable` (24). (`UnusedSymbol` is **not**
   here — it needs review, Phase 127.)
2. **Redundancy:** `RemoveRedundantQualifierName` (53), `RemoveExplicitTypeArguments` (9),
   `UnnecessaryVariable` (6), `SimplifiableCallChain` (6), `ConvertCallChainIntoSequence` (4),
   `ReplaceIsEmptyWithIfEmpty` (7), `ReplaceWithOperatorAssignment` (2), `IntroduceWhenSubject` (2),
   `MoveVariableDeclarationIntoWhen` (2), `IfThenToElvis` (2), `IfThenToSafeAccess` (2), `CanBeVal` (2),
   `CanBeParameter` (1), `CascadeIf` (1), `RedundantCompanionReference` (1),
   `RemoveCurlyBracesFromTemplate` (1), `NestedLambdaShadowedImplicitParameter` (1),
   `VerboseNullabilityAndEmptiness` (2).
3. **Naming (genuinely wrong):** `ConstPropertyName` (24, `const` → `UPPER_SNAKE`), `MayBeConstant`
   (11, promote to `const`/top-level val where correct), `LocalVariableName` (3), `PrivatePropertyName`
   (1).
4. Guard: for the wasmJs target, verify renames/removals don't touch anything referenced by `js()`
   interop or `@JsExport`; for `const`/naming changes, confirm nothing external references the old name.

### C. Re-run to confirm
After A+B, a fresh `scripts/qodana.sh` run (Phase 125) should show only the Phase-127 review set
remaining (no mechanical/suppressed noise).

## Scope
- `qodana.yaml` — `exclude` entries for the four suppressed families (with rationale comments).
- Source: mechanical fixes across `src/**` and `ravilo-ui/**` (imports, redundancy, naming). No
  behaviour change; every build target must still compile.

## Non-goals
- No `UnusedSymbol`, `DuplicatedCode`, `RedundantSuspendModifier`, `RedundantNullableReturnType`
  changes (Phase 127 — they need judgment).
- No renaming of `@Composable` functions (they are correct).
- No `Long → Duration` migration.

## Acceptance
- `qodana.yaml` excludes `FunctionName`, `ConvertLongToDuration`, `CanConvertToMultiDollarString`,
  `CanUnescapeDollarLiteral`, `UnstableApiUsage`, each with a rationale comment.
- The ≈229 mechanical findings are fixed; all targets compile (`linkDebugExecutableLinuxX64`,
  `compileKotlinWasmJs`, `:ravilo-web:compileKotlinWasmJs`, `:ravilo-ui:compileDebugKotlinAndroid`).
- A re-run reports only the Phase-127 categories.
