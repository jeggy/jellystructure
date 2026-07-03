# Phase 127 — Qodana remediation, part 2: correctness review + baseline (FR-QA3)

## Goal
Work the **judgment-heavy** Qodana findings that survive Phase 126 (≈121) — the ones that need a human
decision because a blanket "apply fix" would be wrong in a KMP/serialization/JS-interop codebase —
then commit a **Qodana baseline** so future local runs flag only *new* regressions.

## The review set (from the `qodana.recommended` run)

### A. `UnusedSymbol` (63) — remove genuine dead code, KMP-aware
Many are real leftovers (`Dimens.sectionPadH`, `ChannelButtonSpec.GLOW_ALPHA`,
`LogoDownloader.batchFetchPeople`, `TvApiClient.removeSession`, `MediaApi.jsonStr`,
`RaviloConfig.MEDIA_KINDS`). Delete the genuinely-unused ones. **Do not blanket-delete** — Qodana's
Community linter cannot see all cross-module use; verify each is truly unused before removing, and
**keep** false positives:
- `@Serializable` DTO fields (used by kotlinx-serialization, not by call graph),
- `expect`/`actual` members and `@JsExport`/`js()`-interop declarations,
- anything referenced only from another Gradle module (shared/ravilo-ui/wasm),
- public API deliberately kept for symmetry (document why, or drop if truly orphaned).

### B. `DuplicatedCode` (43) — extract where it cuts bug-surface, accept incidental
Concentrated in `Scanner.kt` (6), `MovieDetailScreen.kt` (5), `NfoWriter.kt` (4), `TrackEditor.kt` (4),
`TrackRoutes.kt` (3), `HomeFeedService.kt` (3). Extract a shared helper **only where duplication is a
real maintenance/bug risk** — the prime example is the **`TrackEditor.kt` audio/subtitle twins**, which
are literally why the Phase-120 default-diff bug had to be fixed in two places; deduping them prevents
the class of bug. Accept incidental duplication (near-identical DTOs, similar-but-distinct branches)
rather than over-abstracting.

### C. `RedundantSuspendModifier` (9) — remove where safe, keep deliberate async surface
`MediaStore.kt` (3), `MediaJobQueue.kt` (2), `SessionService.kt` (2), `JellyfinSessionBridge.kt` (1),
`MediaHistory.kt` (1). Drop `suspend` where the function neither suspends nor is part of a consistent
suspending API surface. **Keep** it where removing it would break a uniform async store/service API or
force churn if suspending work is added back (note the decision per site).

### D. `RedundantNullableReturnType` (5) — tighten where safe, keep JS-interop defensiveness
`RaviloRootActuals.jsGetBaseUrl`/`jsGetToken`, `MultiTokenStoreWasm.jsGet` are `js()` interop — the
nullable is **defensive** against JS `null`/`undefined`; keep them. `PlaybackService.startPlayback`/
`restream` returning non-null can be tightened to a non-null type if no caller relies on null for error
signaling — verify then tighten.

### E. `UselessCallOnCollection` (1) — fix (a redundant collection call).

## Baseline (keep it clean going forward)
1. After A–E and Phase 126 land, run `scripts/qodana.sh` once more; anything **intentionally** left
   (documented keeps from A–D, plus Phase-126 suppressions) becomes the accepted state.
2. Commit a Qodana **baseline** (`qodana.sarif.json` baseline via `--baseline`, or the documented
   equivalent) referenced from `qodana.yaml`, so subsequent local runs report only **new** problems —
   turning Qodana into a regression gate for future work rather than a static backlog.

## Scope
- Source review/edits across `src/**` and `ravilo-ui/**` (dead-code removal, targeted de-duplication,
  suspend/nullable tightening).
- `qodana.yaml` + a committed baseline artifact.

## Non-goals
- No mechanical cleanups or profile suppressions (Phase 126).
- No forced de-duplication where it would hurt readability.
- No renaming of Compose functions; no Long→Duration.

## Acceptance
- Each `UnusedSymbol` is either removed or documented as a deliberate keep (with the KMP/serialization/
  interop reason); no live cross-module reference is broken (all targets compile).
- The `TrackEditor` audio/subtitle duplication is unified (or explicitly deferred with reason); other
  `DuplicatedCode` hits are triaged (extract vs accept).
- `RedundantSuspendModifier` / `RedundantNullableReturnType` are each resolved or documented-kept.
- A committed Qodana baseline exists; a fresh `scripts/qodana.sh` run reports **zero new** problems.
