# Phase R203 — Faroese UI-language label misspelled "Foroyskt" (bug fix)

> Found during a 2026-08-18/20 screenshot session (`presentation/observed-issues-2026-08-18.md`,
> item 5), not a live user report. Settings → Language lists `English · Dansk · Foroyskt` — the third
> should read **Føroyskt** (missing the ø). The app already spells it correctly elsewhere:
> `PlayerScreen.kt:2819`'s language-name map has `"fo" to "Føroyskt", "fao" to "Føroyskt"`. Only the
> R161 settings picker has it wrong.

**Status:** Implemented.

## Bug report
Self-found while auditing screenshots for a work presentation. Confirmed in source, not just on
screen (`ravilo-ui/.../screens/SettingsScreen.kt:331`), so this isn't a font-rendering artefact —
the string itself is missing the character.

## Investigation
`SettingsScreen.kt:329-331`:
```kotlin
// R161 — endonyms, not translated (a language picker names languages in themselves regardless of
// the currently active UI language, same convention the admin editor's language list already uses).
private val UI_LANGUAGES = listOf("en" to "English", "da" to "Dansk", "fo" to "Foroyskt")
```
No dated spec file exists for R161 (STATUS.md-only, like R59) so there's no prior spec text to have
gotten this right and drifted — it was simply typed wrong when R161 first wrote this list. Nothing
else in the codebase references this exact list; the player's language map (`PlayerScreen.kt:2819`)
is a separate, independently-authored table for a different UI (in-player track names) and has always
had the correct spelling.

## Requirements

### FR-RV-R203-1 — Correct spelling in the language picker
`SettingsScreen.kt:331`: `"fo" to "Foroyskt"` → `"fo" to "Føroyskt"`.

## Invariants
- Endonyms in `UI_LANGUAGES` are not translated (unchanged, R161's own rule) — this fix only corrects
  a transcription error within that endonym, it doesn't touch the "don't translate" policy.

## Out of scope
- Auditing every other hardcoded string in the codebase for similar typos — this was found by direct
  source inspection of the one flagged screen, not a sweep.

## Source references
- Bug site: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/SettingsScreen.kt:331`.
- Correct spelling already present: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt:2819`.
- Evidence screenshot: `presentation/screenshots/48-settings-skins-language-playback.png`.
