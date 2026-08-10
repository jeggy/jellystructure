# Phase R195 — Telling apart subtitle tracks that share a language

**Status:** Planned (design-authored, not yet dev-reviewed)
**Date:** 2026-08-09
**Supersedes nothing. Extends:** R75/R78/R134 (merged flag line), **R180** (flag-forward Audio &
Subtitles picker), **R181** (default and remembered tracks).
**Research basis:** `specs/research-reports/subtitle-picker-same-language-disambiguation-2026-08-09.md`

---

## 1. Problem

R180 shipped a flag-forward picker on the assumption that **one row per language reads as one
choice**. In this library it usually does not.

From the 2026-08-09 report, measured against the live library:

| Measure | Value |
|---|---|
| Movies with a file carrying 2+ same-language subtitle tracks | **46.5%** |
| Series likewise (episode files) | **47.5%** (1,668 files) |
| File-instances where same-language tracks are identical on **every** field we store | **251** |
| Largest single cluster | **32** tracks in one file — no language, no title |
| Distinct `title` strings across ~24,900 tracks | **489** — no usable shared vocabulary |

The viewer-visible failure is a list that shows `🇬🇧 English`, `🇬🇧 English`, `🇬🇧 English` and
gives no way to tell which is the plain translation, which is SDH, and which is a commentary
track — and, in the tail, a wall of 32 rows that are pixel-identical.

A second, quieter failure: **R181 remembers a language, not a track.** A viewer who always
chooses SDH gets whatever same-language track happens to sit first in stream order on the next
episode.

## 2. Goals / Non-goals

**Goals**
- Every row in the picker differs from its siblings on something the viewer can *read*.
- The common case (one usable version per language) gets **shorter**, not longer.
- The tail case (dozens of unnamed tracks) stays navigable with a D-pad.
- Remembering a choice survives across episodes and files.

**Non-goals**
- Rewriting the badge vocabulary. R180's words stand: **Default · Signs only · Sound described ·
  Describes action · Commentary**, no codec names, no delivery-method cues.
- Subtitle *acquisition*. Fetching, scoring and upgrading remain Bazarr's (Phase 157).
- Any change to the audio side beyond the same grouping mechanics — audio has the same duplicate
  problem in miniature (dub vs. audio-description) and gets the identical treatment for free.

---

## 3. Design — two levels: language first, versions inside

Chosen from three explored directions (`design/ravilo/Audio & Subtitles Picker - Same-Language
Directions.html`). The rejected two are recorded in §8.

### §A The language list (level 1)

One row per **language**, not per track. Each row:

```
[tick] [flag] Dansk                                  (nothing)
[tick] [flag] English    Default · Last used         4 versions ›
[tick] [flag] Português                              4 versions ›
[tick] [flag] Suomi                                  (nothing)
```

- **The flag sits beside the language name** on every row — this is R180's flag-forward
  principle, unchanged.
- The language name is the **native** name (`Dansk`, `Português`, `中文`), as shipped.
- A language with **more than one version** shows a count and a **›** arrow.
- A language with **exactly one version** shows **no arrow**, and **OK selects it immediately** —
  no second screen for a list of one. *The absence of the arrow is the entire affordance;* no
  extra label, badge or colour marks it. (Explicitly settled during design review: an earlier
  draft added a "Straight on" tag and a green edge — both rejected as noise.)
- Badges on a level-1 row belong to the language's single version, or — when the language has
  several — to the one currently playing (`Default`, `Last used`).
- `Off` is the first row, as today.

### §B The version list (level 2)

Entered with **OK** on a row that has an arrow; **Back** returns to level 1 (it must *not* close
the picker — see §D).

```
🇬🇧  English            4 versions
     ─────────────────────────────────────────────
[✓]  English    Default · Last used
     The full version of everything spoken.
[ ]  English    Sound described
     Adds speaker names and sound-effect notes.
[ ]  Commentary  Recording 1
     A recorded commentary on this title.
[ ]  Commentary  Recording 2
```

- **The flag is not repeated on every row.** It sits **once, in the header bar**, with the
  language name and the version count. Repeating one flag down a column of four rows carries no
  information and reads as noise.
- Each version carries a **one-sentence plain-language line** underneath. This is the mechanism
  that replaces the 489 inconsistent `title` strings — we never render a raw track title.
  Sentences are authored per *kind*, not per track:
  | kind | line |
  |---|---|
  | plain | The full version of everything spoken. |
  | `sdh` | Adds speaker names and sound-effect notes. |
  | `forced` | Only the on-screen text and foreign lines. |
  | `describe` | Narrates what happens on screen. |
  | `commentary` | A recorded commentary on this title. |
  | region variant | The {region} version. |
  | no distinguishing data | This one carries no name of its own — pick it to see it. |
- These strings live in `ravilo-i18n.js` (en/da/fo) like every other viewer-facing string.

### §C Regions inside a language

Where a language splits by region, the **version row carries its own flag** — and *only* then:

```
🇵🇹  Português          4 versions
[✓]  Português  Brasil                🇧🇷
[ ]  Português  Brasil   Sound described   🇧🇷
[ ]  Português  Brasil   Signs only        🇧🇷
[ ]  Português  Portugal                  (no flag — the header already shows 🇵🇹)
```

Rule: **a version shows a flag when its region differs from the header's flag; otherwise it shows
none.** When *any* version in the group carries a region flag, flagless rows keep an **empty slot
of the same width** so the text column stays straight.

Region resolution is a **synonym table, never string matching** (§5.2).

### §D Navigation (D-pad)

| Key | Level 1 | Level 2 |
|---|---|---|
| ↑ ↓ | move between languages | move between versions |
| ← → | switch Audio / Subtitles tab | switch Audio / Subtitles tab (returns to level 1) |
| OK | one version → select · several → **enter level 2** | select, stay open |
| Back | close picker | **return to level 1** |

Entering level 2 focuses the first version. Returning to level 1 restores focus to the language
row you came from. Switching tab always resets to level 1.

### §E The tail: tracks with nothing to tell them apart

When every same-language track in a group is identical on all stored fields (**251 file-instances**;
worst case **32**):

- Level 1 shows one row: **`Unnamed`** with a neutral globe placeholder where the flag would be,
  and the count.
- Level 2 numbers them: **`Version 1`** … **`Version n`**, each with the "carries no name of its
  own" line, the currently-showing one badged **`Now showing`**.
- Because a subtitle change applies instantly, **moving down the list is the preview** — the
  footer says so.

**Wording constraint (settled in review):** never say *disc*. Nothing in this library is a disc.
The copy is "nothing in this file names them", "no language name came with this track".

---

## 4. What the viewer sees change

- A title with one usable subtitle per language: the list gets **shorter** (no repeated rows) and
  behaves exactly as before — one press.
- A title with SDH: SDH stops being invisible and becomes a real, labelled second version.
- A Brazilian/European Portuguese title: two clearly different rows instead of two identical ones.
- The 32-track case: navigable instead of a wall.

---

## 5. Backend / data prerequisites

These are the real work. The picker cannot be honest without them.

### 5.1 Detect SDH
The single most common variant (1,300+ raw `SDH` occurrences in track titles) and **nothing sets
it today**. Detect from:
1. track title matching `SDH`, `HI`, `hearing`, `hard of hearing` (case-insensitive, word-boundary), **and**
2. Bazarr's own `hi` flag where a Bazarr connection exists (Phase 157).

Surface as `kind: 'sdh'` → renders as R180's existing **Sound described**.

### 5.2 Region synonym table
Map to a region code, never substring-match:

| Raw | → |
|---|---|
| `Castilian`, `Spanish (Spain)`, `es-ES` | 🇪🇸 `es` |
| `Latin American`, `Latin America`, `es-419` | neutral (no flag) + name `Latinoamérica` |
| `Brazilian`, `Portuguese (Brazil)`, `pt-BR` | 🇧🇷 `br` |
| `Portuguese (Portugal)`, `pt-PT` | 🇵🇹 `pt` |
| `Simplified`, `zh-Hans`, `zh-CN` | 🇨🇳 `cn`, name `简体` |
| `Traditional`, `zh-Hant`, `zh-TW` | 🇹🇼 `tw`, name `繁體` |

**An unconfident match falls back to the plain language flag.** A wrong flag is worse than no
region at all.

### 5.3 Suppress provenance
`BluRay` / `WEB-DL` / `iTunes` / release-group variants of the same language *and* kind are **not
a viewer choice**. Collapse to one row, keeping the best-scoring file. This is the one place the
design deliberately **merges** tracks — see §7.

### 5.4 Remember the version, not the language
`RememberedChoice` currently stores a language code. It must store a **variant signature**
(`lang` + `kind` + `region` + ordinal-within-group) so "always SDH" survives to the next episode.
Falls back to the language when the signature finds no match in the next file.

---

## 6. Implementation surface (design mockups)

| File | Change |
|---|---|
| `ravilo/ravilo-player.js` | `plGroups()` groups the flat track list by language; `plSameSig()` detects the indistinguishable case; `plVarName()` / `plBlurb()` render level 2; `renderPicker()` gains a level; `pickerChoose()` selects-or-descends; new `pickerBack()` |
| `ravilo/ravilo-player.css` | `.pl-more` (count + arrow), `.pl-crumb` (level-2 header bar with the single flag), `.pl-var` / `.pl-vh` (version row + its sentence), `.pl-rgn` (+ `.ghost` slot-holder) |
| `ravilo/ravilo-app.js` | `tracksFor()` sample data extended with `region`, a commentary sub-track, Brazilian/European Portuguese, 简体/繁體 and three indistinguishable French tracks |
| `ravilo/ravilo-i18n.js` | the §B sentences and "versions" / "Unnamed" strings, en/da/fo |
| `ravilo/mobile/` | phone target inherits the same two levels (sheet pushes a second sheet) |

Design reference: `design/ravilo/Audio & Subtitles Picker - Same-Language Directions.html`
(Direction A column = the chosen design; the other two columns are kept as rejected options).

---

## 7. Open question for dev review

R180 states the picker hides and merges **nothing**. §5.3 (provenance merge) and §3 (a second
level) both bend that.

**Recommendation:** keep the invariant for *tracks a viewer could meaningfully choose between*,
and let it yield where the difference is release plumbing rather than content. If the dev team
prefers the invariant absolute, §5.3 drops and the level-2 list simply gets longer — the design
survives it; the rejected Direction B does not.

## 8. Rejected directions

- **B — flat list, honest variants.** Smallest diff from shipped R180: keep one row per track,
  tint same-language runs into a block, add SDH badges and corner flags. Rejected because the
  32-track case degrades into either an endless scroll or an overflow row, which breaks the same
  R180 invariant it was trying to protect.
- **C — language rail + versions pane.** Two columns, nothing hidden, scales best of the three
  (all 32 fit as a number grid, four D-pad presses to the farthest). Rejected as the most
  screen-real-estate and the only one introducing a new left/right gesture inside the picker —
  kept on file as the fallback if §3's second level tests badly.
