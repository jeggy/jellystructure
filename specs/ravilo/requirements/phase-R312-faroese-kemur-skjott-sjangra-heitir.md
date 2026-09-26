# Phase R312 — Faroese: *Kemur skjótt*, *sjangra*, and *heitir* when there are several

> Owner, 2026-09-26:
> - *"`Uppdaga` → `Komandi skjótt`: Sounds very bad. Let's change it to `Kemur skjótt`."*
> - *"`Sjanrur`: Sounds bad. Let's use `Sjangra`/`Sjangrur` for genre/genres."*
> - *"`Uppdaga → Sjónvarpsrásir / Studio / Sjangrur` has `heiti` for their count for each item. Example
>   TV 2 has `26 heiti`, but this should be plural when it's more than 1. So it should say
>   `25 heitir`."*

## Status

`Planned` — written 2026-09-26, not dev-reviewed. **Strings only:** `i18n/fo.json`, the regenerated
`i18n/lexicon/fo.txt`, and one entry in `scripts/check_i18n_spelling.py`. No key added, removed or
renamed, no placeholder changed, and `en.json` and `da.json` untouched. Every client (TV, phone, web,
Chromecast receiver, the Tizen receiver) reads the same table (R279), so this reaches all of them.
**Numbering:** verified against `STATUS.md` the same day — Ravilo taken through **R307**.

Amends **R288** (*Faroese says what it means*): its FR-R288-2 turned `Comandi skjótt` into
`Komandi skjótt`, and its term table fixed *title* as `heiti`. The owner's word stands on both; this
changes the chip's wording and adds the plural.

## What changes

### 1. The Coming Soon chip — `Kemur skjótt`

| Key | Now | Becomes |
|---|---|---|
| `seg.coming` | `Komandi skjótt` | **`Kemur skjótt`** |

`Kemur skjótt` is already the file's phrase for the same idea: `sonarr.upcoming` (R149's key) says it,
though no current client draws that key. `nav.upcoming` (`Komandi`), the heading on the Coming Soon tab,
is not part of the owner's note and stays (see open question 2).

### 2. Genre — `sjangra`, `sjangrur`

Every Faroese string that says *genre*. It is six keys, and all of them change so that no screen says
one word while another says the other:

| Key | Now | Becomes | Where |
|---|---|---|---|
| `seg.genres` | `Sjanrur` | **`Sjangrur`** | Discover's chip |
| `tx.n_genres` | `{n} sjanrur` | **`{n} sjangrur`** | the Genres wall's header |
| `tx.sub_genres` | `Alt í tínum savni, eftir sjanru` | **`Alt í tínum savni, eftir sjangru`** | the Genres wall's subtitle |
| `detail.genre` | `Sjanra` | **`Sjangra`** | the detail page's genre row label, one genre (R221) |
| `detail.genres` | `Sjanrur` | **`Sjangrur`** | the same label, two or more genres |
| `browse.facet.genre` | `Sjanra` | **`Sjangra`** | the browse page's Genre filter |

`sjanru` → `sjangru` keeps the dative singular on the new stem, as the current string has it on the
old one.

### 3. Counting titles — `heitir` when there are several

| Key | Now | Becomes | Where |
|---|---|---|---|
| `tx.titles` | `{n} heiti` | **`{n} heitir`** | every wall tile's count and the wall header (*"26 heitir"*) |
| `tx.title_one` | `1 heiti` | *unchanged* | the same, for exactly one |
| `browse.titles` | `{count} heiti` | **`{count} heitir`** | the browse and seeded-browse count (*"See all"*, a studio's grid) |
| `browse.title_one` | `1 heiti` | *unchanged* | the same, for exactly one |

The singular is already its own key in both pairs, and the code already chooses between them
(`titleCountLabel` in `TaxonomyScreen.kt`, `BrowseScreen.kt`, `SeededBrowseScreen.kt`), so no code
changes. `browse.titles` is included although the owner named the walls: selecting a tile opens its
grid, and a wall that says *26 heitir* must not open a page that says *26 heiti*. That is R288's own
rule, one word used one way through the whole file.

`lib.count` (`{n} heiti`) is left alone: no client reads it, and it has no singular to pair with. See
open question 3.

## Requirements

**FR-R312-1 — The nine strings above, exactly.** Placeholders unchanged (`{n}`, `{count}`), so
`generateRaviloStrings` accepts the file as it is.

**FR-R312-2 — The lexicon is regenerated and committed with the strings.**
`scripts/check-i18n-spelling.sh --update-lexicon` rewrites `i18n/lexicon/fo.txt` from the strings in
use. It gains `sjangra`, `sjangru`, `sjangrur` and `heitir` and drops `sjanra`, `sjanru` and
`sjanrur`. It is committed **in the same commit** as `fo.json`, so the diff shows the spellings as a
decision (the i18n README's rule).

**FR-R312-3 — The old genre word is kept out.** `sjanra`, `sjanru` and `sjanrur` are added to the
Faroese `DISCOURAGED` list in `scripts/check_i18n_spelling.py`, with the owner's reason, the way
`telefonur` is. That way a string copied from an older draft (the design mockup still has them) fails
the check instead of slipping back in.

**FR-R312-4 — Checks green.** `check-i18n-spelling.sh`, `generateRaviloStrings`,
`checkRaviloStrings` (drift report reviewed, nothing new) and `check-ravilo-strings.sh` all pass.

## Non-goals

- Danish and English. The owner's note is about Faroese.
- `design/ravilo/ravilo-i18n.js`, the mockup's own copy, which still says `Sjanrur` and
  `Komandi skjótt`. The shipped table wins wherever the two differ (R279). The design project follows
  on its next pass.
- Any other plural in the file. This phase changes the counts the owner pointed at and their twin on
  the browse page, nothing else.

## Acceptance

1. With the interface language set to Føroyskt, on the TV: Discover's chips read *… · Sjangrur ·
   Kemur skjótt · …*; the Genres wall's header reads *41 sjangrur · 531 heitir*, and its subtitle ends
   *eftir sjangru*.
2. On Networks, a tile with 26 titles reads *26 heitir*; a tile with one reads *1 heiti*. Selecting
   the first opens a grid that says *26 heitir*.
3. A film with two or more genres: the genre row's label reads *Sjangrur* (one genre: *Sjangra*).
   The browse page's filter reads *Sjangra*.
4. The same on the phone and in the web app. The spelling check fails if `sjanrur` is put back.

## Open questions

1. **`heitir` or `heiti`?** Dictionaries give *heiti* as a neuter noun with an unchanged plural
   (*eitt heiti, tvey heiti*), the same as *epli*. The owner asked for *heitir*, and this phase writes
   what the owner asked. Worth one confirmation before it ships, since it becomes the file's word for
   every count of titles.
2. **Should the Coming Soon tab's heading (`nav.upcoming`, *Komandi*) also read *Kemur skjótt*?** It
   sits directly above the chip. R311 open question 1 asks whether that heading should read *Uppdaga*
   on every tab, as the mockup draws it, and if so this question goes away.
3. **Delete `lib.count`?** No client reads it. Removing a key is a separate, deliberate change to all
   three languages. **Lean: leave it for the next i18n sweep.**
