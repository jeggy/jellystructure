# Phase 72 — Canonical language-code equivalence across all comparisons (FR-LC1)


> Authored from a live bug report: a single-audio-track movie shows a false
> **"Default ≠ resolved"** cascade warning. The FR code (FR-LC1) is the stable reference.

## Problem

Language codes flow through the system in **three incompatible spellings** for the *same*
language, and several equality checks compare them as **raw strings** without canonicalising
first. The same language therefore fails to compare equal to itself.

The three forms:

- **ffprobe track tags** — ISO 639-**2** three-letter, often the bibliographic (/B) form:
  `eng`, `ger`/`deu`, `fre`/`fra`, `fao`. Stored verbatim on `Track.language`
  (`FfprobeRunner.kt` → no normalisation).
- **Resolved metadata language** — ISO 639-**1** two-letter: `en`, `de`, `fr`. Produced by
  `LanguageResolver.priorityList(...)`, which runs every entry through `normalize()`, then
  stored on `MediaItem.resolvedLanguage` (`Scanner.kt`).
- **UI language picker** — ISO 639-1 two-letter (`LanguagePicker.LANGUAGES`).

### The reported symptom (root cause)

The track-editor cascade check (`TrackEditor.kt`, `cascade()`):

```kotlin
val show = def != null && !def.lang.isNullOrBlank() && def.lang != resolvedLanguage
```

compares the raw track tag against the normalised resolved language: `"eng" != "en"` → **true**.
So a movie whose only audio track is `eng`, resolved to `en` — *the same language* — shows
**"Default ≠ resolved"**. Because track tags are almost always 3-letter, this mis-fires for
**nearly every tagged movie**, not just single-track ones. The banner even prints `(eng)`
because `langShortName("eng")` can't find a 3-letter code in the 2-letter picker list, so the
display path is broken too.

### This is not isolated to the cascade

Every place that compares a track's language to a resolved/normalised code with raw `==`/`!=`
has the same latent bug. Known sites (audit for completeness during implementation):

- `TrackEditor.kt` `cascade()` — the reported warning (frontend).
- `TriageRoutes.kt` `detectCascadeMismatch()` — `audioTracks.firstOrNull { it.language == resolvedLanguage }`
  fails the *opposite* way (never matches → expected track is null → returns "no mismatch"),
  which is why the backend triage and the in-page editor disagree.
- Any majority-language / language-mix / per-library-override comparison in `Scanner.kt`.
- Library audio-track / language filters and the `meta-facets` language buckets.

### Two correctness gaps under the headline bug

1. **`normalize()` is not total over the languages we support.** It maps via `ISO2TO1`
   (a *partial* 639-2→639-1 table) then `INV_ISO1TO2` (the inverse of the full picker table,
   /T forms only), else returns the code unchanged. A 639-2/**B** form that is absent from
   `ISO2TO1` and is not a /T form (e.g. Tibetan `tib` vs `bod`) **fails to canonicalise** and
   silently won't match its 2-letter twin → *a missed language*.
2. **Untagged audio tracks** (`language == null`/blank) must keep working: never coerced to a
   language, never falsely flagged as a cascade mismatch, always surfaced for manual tagging
   (constitution §Language Resolution step 2), and never excluded from resolution of the *other*
   tracks in the same file.

## Goal

One canonical comparison for language equality, used **everywhere**, so the same language always
compares equal regardless of spelling — and no real language is ever "missed" because of code
form. Codes are still **stored and written in their original form** (track tags keep their B/T
form; NFO/file writes still go through `toIso6392` per Phase 46); normalisation is applied
**only at comparison and display time**.

## Change A — canonical equality helper

Introduce a single comparison primitive in `LanguageResolver` (commonMain, so backend **and**
the wasmJs frontend share it) and route every language equality decision through it:

```kotlin
/** True iff both codes denote the same language, B/T- and 639-1/2-agnostic.
 *  Untagged (null/blank) is NEVER equal to a tagged code, and two untagged are NOT "equal"
 *  for cascade purposes (callers decide how to treat untagged explicitly). */
fun sameLanguage(a: String?, b: String?): Boolean =
    !a.isNullOrBlank() && !b.isNullOrBlank() && normalize(a) == normalize(b)
```

- `cascade()` → `def != null && def.lang-tagged && !sameLanguage(def.lang, resolvedLanguage)`.
- `detectCascadeMismatch()` → match the expected track with `sameLanguage(it.language, resolvedLanguage)`;
  keep comparing `specifier`s (not languages) when checking which track is currently default.
- Audit and convert the other sites listed above. No raw `==`/`!=` on a language code may
  survive where one side could be a track tag and the other a normalised/picker code.

## Change B — make `normalize()` total over supported languages

`normalize()` must canonicalise **every** language the system can emit, in **both** ISO 639-2/B
and /T forms, to the picker's 2-letter code.

- Extend the mapping so each supported language's /B form resolves (e.g. `tib→bo`, `cze→cs`
  already present — fill the gaps; the picker's `ISO1TO2`/`INV_ISO1TO2` covers /T, `ISO2TO1`
  must cover the remaining /B aliases).
- Add a unit test that asserts, for every code in the picker, that all of its ISO 639-2 B and T
  spellings `normalize()` back to the picker's 2-letter code — so "we are not missing any
  languages" is enforced, not assumed.
- Unknown / non-standard codes still pass through unchanged (no guessing), consistent with
  Phase 46's "fail rather than write a guess" rule.

## Change C — display the right name for any code form

Language display must not depend on the picker spelling.

- `langShortName(code)` (and any sibling lookup) normalises **before** searching the picker list,
  so a `eng`/`deu`/`fao` track renders **English / German / Faroese**, not the raw 3-letter code.
- An untagged track keeps its explicit **"untagged"** treatment (it is not a language).

## Change D — untagged tracks stay first-class

- Untagged audio tracks are **never** a cascade mismatch (guarded by `sameLanguage` returning
  false for a blank side, plus the existing tagged-default check).
- They remain surfaced for manual tagging (Triage dock / detail editor) and never block resolution
  or writes for the file's other tracks. A file that is *entirely* untagged resolves via the
  `fallback_language` ladder unchanged (constitution §Language Resolution step 4).
- Assigning a language to a previously-untagged track writes through Phase 46's `toIso6392`
  boundary; the new tag then participates in `sameLanguage` like any other.

## Scope / invariants

- **No automatic flag/order changes** — Change A only fixes *detection*; the default flag is still
  changed solely by the explicit **Fix default** action (constitution §Language Resolution step 5).
- **Storage/write forms are unchanged** — track tags keep their on-disk form; NFO/file writes still
  go through Phase 46. Canonicalisation is comparison/display-only.
- Aligns with the constitution's Language Resolution algorithm; adds no new config or taxonomy.
- Reuses `LanguageResolver` (commonMain) as the single source of language-equality truth — no
  parallel normalisation tables in the frontend.

## Verification

- The reported single-`eng`-track movie shows **no** cascade banner; a genuinely mismatched file
  (e.g. default audio `fre`, resolved `en`) **does**, and **Fix default** re-flags the `en`/`eng`
  track.
- `normalize()` totality test passes for the full picker set (B + T forms).
- A `deu`/`fao`/`tib`-tagged track displays its proper language name in the editor.

## Files (implementation pointers)

- `src/commonMain/kotlin/dev/jellystructure/resolver/LanguageResolver.kt` — `sameLanguage`,
  extend `ISO2TO1`, totality test.
- `src/wasmJsMain/kotlin/dev/jellystructure/ui/TrackEditor.kt` — `cascade()`, `langShortName`.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/TriageRoutes.kt` — `detectCascadeMismatch()`.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt` — majority/mix/override comparisons.
- Library language/audio filter + `meta-facets` buckets (audit).

## Mockup

`design/app/media.html` (Tracks & order tab — the "Default ≠ resolved" alert should only appear on
a *genuine* language mismatch), `design/app/series.html` (per-episode editor, same rule).
