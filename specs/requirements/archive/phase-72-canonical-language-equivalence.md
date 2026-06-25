# Phase 72 — Canonical language-code equivalence (FR-LC1)

**Status:** ✓ Done

> Fixes a false-positive "Default ≠ resolved" cascade that fires on nearly every tagged movie
> because ffprobe emits ISO 639-2 three-letter codes (`eng`) while the resolver normalises to
> ISO 639-1 two-letter codes (`en`).

## Problem

`buildResolverTrace()` in `MediaDetail.kt` compared raw track language tags directly against
`item.resolvedLanguage` (e.g. `t.language == resolved`). Since `resolvedLanguage` is always
normalised to ISO 639-1 by `LanguageResolver.priorityList`, but `t.language` carries whatever
the file tag says (`eng`, `deu`, …), the equality check always returned `false` for three-letter
codes. The trace therefore showed every tagged track as "tried, no TMDB result" even when it was
the winning language.

`LanguageResolver.sameLanguage(a, b)` (added in Phase 72) normalises both operands before
comparing, making `eng ≡ en`, `deu ≡ ger ≡ de`, etc.

## Fix

- Added `sameLanguage(a: String?, b: String?): Boolean` to `LanguageResolver` (normalises both
  sides via `normalize()` before `==`; null/blank never matches anything).
- Routed both comparison sites in `buildResolverTrace()` through `sameLanguage`:
  - `t.language == resolved && !winnerFound` → `LanguageResolver.sameLanguage(t.language, resolved) && !winnerFound`
  - `t.language == resolved` → `LanguageResolver.sameLanguage(t.language, resolved)`
- `TriageRoutes.detectCascadeMismatch()` was already correct (uses `sameLanguage` on the
  `expectedTrack` lookup).

## Scope

Comparison/display-only; no data model changes, no backend logic changes.
