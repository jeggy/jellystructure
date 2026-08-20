# Phase R206 — Subtitle/audio picker row renders with an icon and no text (bug fix)

> Found during the 2026-08-18/20 screenshot session (`presentation/observed-issues-2026-08-18.md`,
> item 2), visible in `screenshots/28-subtitles-picker-language-list-full.png`: one picker row shows
> only a microphone glyph, no label at all. [[phase-R195-same-language-subtitle-picker]] explicitly
> specified a fallback for exactly this shape of problem — a group with no recognizable name should
> fall back to a kind word, never render empty — but it only wired that fallback for the *null*-language
> case. A non-null but *unrecognized* language code falls through a different, unguarded path.

**Status:** Implemented.

## Bug report
Self-found while auditing screenshots. Confirmed by code inspection.

## Investigation
`PlayerScreen.kt`'s `groupDisplayName` (the row's name for both level-1 rows and the level-2 crumb
header):
```kotlin
private fun groupDisplayName(group: PickerLanguage, lang: String): String = when {
    group.language != null -> pickerName(group.language, "")
    group.versions.all { it.kind == VariantKind.COMMENTARY } -> t("player.badge_commentary", lang)
    group.versions.all { it.kind == VariantKind.DESCRIBE } -> t("player.badge_describes_action", lang)
    else -> t("player.unnamed", lang)
}
```
and `pickerName`:
```kotlin
private fun pickerName(language: String?, fallbackLabel: String): String =
    endonym(language) ?: languageName(language) ?: fallbackLabel
```
The **first** branch (`group.language != null`) is the one the R195 doc comment calls "the
overwhelming majority of groups" and treats as the safe, already-handled case — the kind-word
fallback (branches 2-4) was written only to cover a *null*-language cluster. But branch 1 calls
`pickerName(group.language, "")` — if `group.language` is non-null yet unrecognized by **both**
`endonym()` and `languageName()`, `pickerName` falls through to its third parameter, which
`groupDisplayName` hardcodes as **`""`**. The row then renders an empty name.

This also explains the specific glyph seen in the screenshot: `PickerGlyphBox` shows a language flag
when `LANG_CC[language.lowercase()]` resolves, and otherwise falls back to a generic icon — `PickerGlyphMic`
specifically whenever the group is neither Off nor Unnamed, regardless of what kind of track it
actually is:
```kotlin
else -> Box(...) {
    when {
        isOff -> PickerGlyphOff(...)
        isUnnamed -> PickerGlyphGlobe(...)
        else -> PickerGlyphMic(...)   // ← the generic "no flag" fallback, not a commentary-specific icon
    }
}
```
So a `group.language` that's set but maps to no flag *and* no name renders exactly "mic icon, blank
text" — a language code present in the data but absent from `LANG_CC`/`endonym()`/`languageName()`
all at once.

## Requirements

### FR-RV-R206-1 — `groupDisplayName` never returns an empty string
When `group.language` is set but neither `endonym()` nor `languageName()` recognizes it, fall through
to the same kind-word logic already used for the null-language case (commentary/describes-action
word), and if that doesn't apply either, show the raw language code (uppercased) rather than nothing.
Recognized languages are unaffected — this only changes what happens when the existing two lookups
both return null.

## Invariants
- A picker row is never rendered with no name at all — carried over unchanged from R195's own stated
  invariant ("never render empty... falls back to the dominant kind word"), just extended to cover
  the non-null-but-unrecognized case R195 didn't reach.

## Out of scope
- Adding the missing language to `LANG_CC`/`endonym()`/`languageName()` — this fix guarantees a
  non-empty, honest label (the raw code) when a mapping is missing; it doesn't chase down which
  specific code was missing on the reporting library, since that will vary by library.
- `PickerGlyphBox`'s glyph choice (mic as generic fallback) — cosmetic and not misleading once the
  text label is fixed; not reported as its own issue.

## Source references
- Bug site: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`
  (`groupDisplayName`, `pickerName`).
- Glyph fallback: `PlayerScreen.kt` (`PickerGlyphBox`).
- Prior related spec: `specs/ravilo/requirements/phase-R195-same-language-subtitle-picker.md`.
- Evidence screenshot: `presentation/screenshots/28-subtitles-picker-language-list-full.png`.
