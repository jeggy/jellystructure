# Phase 11 — Language Pickers (FR-L1)


## Problem
All language inputs are free-text fields. Users must know the exact BCP-47/ISO 639 code. There is no
discovery, autocomplete, or validation.

## Current state (as-is at time of spec)
- `lang-override-input` in MediaDetail: `<input ... maxlength="10">`
- `fallback-language` in Settings: `<input type="text" placeholder="en">`
- Per-library `fallback_language` inputs in Settings library list
- Fallback language input in `Language.kt`

## Requirements

### Language data
1. A static lookup table compiled into the WASM bundle (`wasmJsMain`): an array of `(code, name)` entries covering all languages recognised by TMDB (~185+ entries). Codes are ISO 639-1 (2-letter) where available, falling back to ISO 639-2 (3-letter) — matching what TMDB actually uses.
2. Display format in the picker: `"Language name (code)"`. Examples: `"English (en)"`, `"Faroese (fo)"`, `"Japanese (ja)"`.
3. The stored/returned value is always the raw code string, unchanged from current behaviour.

### Picker component
4. A reusable function `renderLanguagePicker(inputEl: HTMLInputElement)` that upgrades an existing `<input>` into a searchable picker in-place:
   - The input displays the full `"Name (code)"` label for the current value when known; falls back to the raw code if not in the table.
   - On focus/click, a dropdown panel opens below the input, containing a search field pre-filled with the current text and a scrollable list of matching entries.
   - List filters live as the user types — matches by language name or code prefix/substring (case-insensitive).
   - Selecting an entry sets the underlying `<input>` value to the raw code and closes the dropdown. Fires an `input` event so existing change listeners still work.
   - Keyboard navigation: Arrow keys move selection, Enter confirms, Escape closes without selecting.
   - Clicking outside closes the dropdown.
   - Max visible rows: 8, with overflow scroll.
5. Apply the picker to every free-text language input in the app:
   - `lang-override-input` in `MediaDetail.kt`
   - `fallback-language` in `Settings.kt`
   - Per-library `fallback_language` inputs in `Settings.kt` library list
   - The fallback language input in `Language.kt`
6. Picker width matches the original input width (`width: 100%`). Dropdown is `position: absolute`, z-indexed above other content.
7. No backend changes. Values stored and sent to the API are unchanged code strings.
