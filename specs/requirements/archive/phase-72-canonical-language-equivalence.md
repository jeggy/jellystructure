# Phase 72 — Canonical language-code equivalence (FR-LC1)

## Problem

Language codes in Jellystructure exist in two forms:

- **ISO 639-2** (three-letter) — `eng`, `fra`, `ger`, `deu` — raw output from ffprobe and stored
  verbatim in `Track.language`.
- **ISO 639-1** (two-letter) — `en`, `fr`, `de` — the normalized form produced by
  `LanguageResolver.normalize()` and stored in `MediaItem.resolvedLanguage`.

Two comparisons used string equality without normalization, causing false mismatches:

1. **`detectCascadeMismatch` in `TriageRoutes.kt`**: `it.language == resolvedLanguage` compared raw
   `eng` track codes to normalized `en` resolved language — the condition was never true, so the
   cascade-mismatch triage item was reported even for correctly-tagged movies.
2. **`matchesAudioFilter` in `MediaStore.kt`**: `t.language?.equals(lang, ignoreCase = true)` did
   the same — audio-language filter results were wrong when users searched by two-letter code.

## Fix

Added `LanguageResolver.sameLanguage(a: String?, b: String?): Boolean` in `commonMain`:

```kotlin
fun sameLanguage(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    return normalize(a) == normalize(b)
}
```

Both callers updated:

- `TriageRoutes.kt:310`: `LanguageResolver.sameLanguage(it.language, resolvedLanguage)`
- `MediaStore.kt:289`: `audioLangs.any { lang -> LanguageResolver.sameLanguage(t.language, lang) }`

No other language comparisons were affected — null checks (`== null`) are safe, and
`Scanner.kt` majority-voting already explicitly called `normalize()`.

## Scope

Comparison/display-only. No track write paths changed.
