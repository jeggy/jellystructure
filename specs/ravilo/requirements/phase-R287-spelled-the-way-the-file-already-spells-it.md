# Phase R287 — Faroese and Danish, spelled the way the file already spells them

> Seen on the stue TV, 2026-09-21, on the Settings screen in Faroese: *Mal · Spael naesta evni
> sjalvvirkandi · Skift loyniord · Vis framgongd a Halt fram at siggja*.
>
> R279 reported this and did not fix it: *"⚠ Reported, not fixed: 82 Danish and 83 Faroese words
> spelled two ways, accent-stripped."* Owner, this session: *"Also fix other stuff."*

## Status

`✓ Built` — design-authored and built 2026-09-21, observed on the stue TV. Not dev-reviewed.
**85 strings changed across `fo.json` and `da.json`; no key added, removed or reordered.**

### Where the map outgrew the file's own evidence

The spec below says the correct spelling is always already in the file. That was true of 33 word
forms and **not** of three further groups, which were added with their reasoning rather than quietly:

- **The `ae` / `oe` digraph words** — `Ætlan`, `Næst`, `Næsti`, `Fjarlægir`, `går`, `Føjes`,
  `tilføjelser`, `Sæson`, `Tilføj`. Each appears once, with no accented twin to learn from. The
  digraph convention is unambiguous in both languages. **`Servaraadressa` is deliberately left
  alone**: its `aa` is a word join (*Servara* + *adressa*), and Faroese has no `å` for an `aa` rule
  to produce.
- **`öllum` → `øllum`** — Faroese has no `ö`; `account.photo_sub` already writes `øllum`.
- **`account.pw_err_rep`'s `loyniordð`** — FR-R287-3, the one the map cannot reach.

### One bug in the first pass, worth recording

Pass one looked words up by `lowercase`, not by the accent-folded form, so a **half-corrected** word
slipped through: `sjónvarpid` has its `ó` and is missing its `ð`, lowercases to `sjónvarpid`, and
matched no key. The checker caught it, which is the argument for FR-R287-4 in miniature — the fix and
the check were written together and the check found a defect in the fix.

## Context

`i18n/fo.json` and `i18n/da.json` spell the same word two ways — `loyniord` seven times and
`loyniorð` five, `pa` twice and `på` thirty-one. It is not a font problem: `ø` and `ý` render fine on
the TV, and both spellings appear in the same file, sometimes in the same string (`"Nýtt loyniord"`
carries a correct `ý` and a missing `ð`).

**The file is its own authority.** For every affected word, the correct spelling is already present in
the same file — so this is not a translation task and needs no guessing at Faroese. It is a
consistency repair with the evidence sitting next to the defect.

Counting word *forms* rather than occurrences: **28 in Faroese, 5 in Danish**.

One string is not merely stripped but **corrupt**: `account.pw_err_rep` reads
`"Tey tvey nýggju loyniordð eru ikki eins."` — `loyniord` with a `ð` appended rather than the `d`
replaced. That is the signature of a find-and-replace that ran once over already-stripped text, which
is probably how the whole thing happened.

### Three things that look like the defect and are not

A blanket accent-restoring pass gets all three wrong, which is why this phase is a reviewed map and
not a regex:

- **`browse.sort.az` / `browse.sort.za`** are `A–Á` (fo) and `A–Å` (da). Those single letters are the
  **first and last letters of the alphabet**, a deliberate range label. Correcting `A` to `Á` there
  would destroy the label.
- **`up.episodes_range`** is `Partar {a}–{b}`. The `a` is a **placeholder name**, not a word. This is
  the whole of Danish's apparent `a → å` finding: Danish has no stripped standalone `a` at all.
- **`profile.signed_in`** is `Innloggin/ur sum {name}` and `login.success` is `Innritad/ur sum {name}`.
  Here `ur` is a **grammatical ending after a slash**, not the word `úr`. Elsewhere — `Fjern ur minum
  lista` — the same three letters *are* the word and must become `úr`.

## Non-goals

- **Translation quality.** Whether `Fjern` is the best Faroese for *Remove*, or whether `A–Á` is even
  the right sort label, is a separate question for someone who speaks it. This phase changes spelling
  to what the file already says, and nothing else.
- **English.** `en.json` is untouched.
- **Adding or removing keys.** The key set is identical before and after.

## Functional requirements

### FR-R287-1 — one canonical spelling per word, taken from the file itself

Each word below is replaced by the accented form **that file already uses elsewhere**. The map is
lowercase; the replacement preserves the case pattern of what it replaces (`Ljod` → `Ljóð`,
`SPAELIR` → `SPÆLIR`, `spael` → `spæl`).

**Faroese (28):** a→á · beinleidis→beinleiðis · bid→bið · bidar→bíðar · brukara→brúkara · fra→frá ·
i→í · ljod→ljóð · loyniord→loyniorð · naesta→næsta · nu→nú · nytt→nýtt · saett→sætt ·
sambandid→sambandið · siggj→síggj · siggja→síggja · sjonvarpid→sjónvarpið · sjonvarpinum→sjónvarpinum ·
skjott→skjótt · skra→skrá · spael→spæl · spaelir→spælir · tad→tað · tinum→tínum · ur→úr · ut→út ·
utgava→útgáva · verdur→verður

**Danish (5):** fortsaet→fortsæt · naeste→næste · nar→når · pa→på · tilgaengelig→tilgængelig

### FR-R287-2 — the three exclusions, by name

A word is left alone when it is: inside a `{placeholder}`; directly adjacent to `/`; or in
`browse.sort.az` / `browse.sort.za`. Each exclusion exists because a real string would otherwise break
— see the section above. They are named in the code, not inferred.

### FR-R287-3 — the corrupt string is repaired

`account.pw_err_rep`: `loyniordð` → `loyniorð`. It is the one defect the map cannot reach, because it
strips to something no other word strips to.

### FR-R287-4 — a check, so it cannot drift back

`scripts/check-i18n-spelling.sh` fails when one language file spells a single word two ways that
differ only by accents. Its allow-list is the FR-R287-2 exclusions and nothing else, each with its
reason written down.

R279's own lesson, one phase later: *"a rule nothing enforces is a rule that decays"*. This defect
existed because nothing could see it — the strings were valid JSON, every key resolved, and every test
passed.

## Acceptance

1. The stue TV's Settings in Faroese reads *Mál · Spæl næsta evni sjálvvirkandi · Skift loyniorð ·
   Vis framgongd á Halt fram at síggja*.
2. `browse.sort.az` is still `A–Á` in Faroese and `A–Å` in Danish.
3. `profile.signed_in` is still `Innloggin/ur sum {name}`.
4. `up.episodes_range` is still `Partar {a}–{b}`, and the placeholder still substitutes.
5. The key set of every `i18n/*.json` is unchanged, and the existing build check still passes.
6. `scripts/check-i18n-spelling.sh` passes, and fails when `på` is changed back to `pa` in one string.

## Verified on the stue TV, 2026-09-21

Settings in Faroese, before → after:

| before | after |
|---|---|
| `Vis framgongd a Halt fram at siggja` | **`Vis framgongd á Halt fram at síggja`** |
| `Spael naesta evni sjalvvirkandi` | **`Spæl næsta evni sjalvvirkandi`** |
| `A` (the On toggle) | **`Á`** |
| `Skift loyniord` | **`Skift loyniorð`** |
| `rita ut` | **`Rita út`** |
| `Innloggin/ur sum {name}` | unchanged — acceptance 3 |

## What is still wrong, and why I did not fix it

Five words are stripped **everywhere** in the file, so there is no correct twin and no digraph to
learn from — and the checker cannot see them either, because it detects *inconsistency*, not
*wrongness*. Fixing them means knowing the language, not reading the file, so they are the owner's
call rather than my guess:

| key | now | probably |
|---|---|---|
| `settings.language` | `Mal` | `Mál` |
| `settings.playback` | `Spaling` | `Spæling`? (the file's own stem is `spæl`) |
| `settings.autoplay_next` | `sjalvvirkandi` | `sjálvvirkandi` |
| `up.*` (8 keys) | `Latid` / `latid` | `Latið` |
| `login.success` | `Innritad/ur` | `Innritað/ur` |

Also spotted and **not** a spelling question: `up.subtitle` and `up.foot_upcoming` contain
`bogvahandan`, which does not look like a word at all — more likely a mangled *bókasavnið*. That is a
translation defect, not an accent one.

A stem-matching heuristic was tried to reach these automatically and **rejected**: its own output was
mostly false positives (`Heim`, `samband`, `Hjem`, `Stor` are all correctly unaccented), so it would
have introduced errors while claiming to remove them.

## Open questions

1. **Is `A–Á` the right sort label at all?** In English it is `A–Z`, first-to-last. Faroese runs
   A…Ø and Danish A…Å, so Danish's `A–Å` is right and Faroese's `A–Á` names the first *two* letters.
   Left alone here: it is a translation question, not a spelling one.
2. **How many of these strings are machine-drafted?** R279 shipped da/fo as drafts with the shipped
   table winning. If a human reviews them, this map is a floor, not a ceiling.
