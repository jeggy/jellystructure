# Phase R312 — Faroese: *Kemur skjótt*, *sjangra*, and *heitir* when there are several

> Owner, 2026-09-26:
> - *"`Uppdaga` → `Komandi skjótt`: Sounds very bad. Let's change it to `Kemur skjótt`."*
> - *"`Sjanrur`: Sounds bad. Let's use `Sjangra`/`Sjangrur` for genre/genres."*
> - *"`Uppdaga → Sjónvarpsrásir / Studio / Sjangrur` has `heiti` for their count for each item. Example
>   TV 2 has `26 heiti`, but this should be plural when it's more than 1. So it should say
>   `25 heitir`."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). **Strings only:** `i18n/fo.json`, one dead key removed from all three languages, the
regenerated lexicons, and one entry in `scripts/check_i18n_spelling.py`. No key added or renamed, and no
placeholder changed. **Open questions decided the same day**: the owner handed the calls over (*"You
just decide for me. We want all best solutions for everything"*). See *Decisions* at the end. Every
client (TV, phone, web, Chromecast receiver, the Tizen receiver) reads the same table (R279), so this
reaches all of them. **Numbering:** verified against `STATUS.md` the same day — Ravilo taken through
**R307**.

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
is not part of the owner's note. R311 replaces that heading with *Uppdaga* on every tab and deletes
the key, so no *Komandi* is left above the chip (decision 2).

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

`lib.count` (`{n} heiti` / `{n} titles` / `{n} titler`) is **deleted from all three files**. It was
added for R267's Library dropdown, which ended up printing a bare number (`LibraryTypePill.kt`), so no
client has ever read it. It has no singular to pair with, so it could only ever go stale. That is
exactly what it did: it is the one title count this phase would otherwise have left saying *heiti*.

## Requirements

**FR-R312-1 — The nine strings above, exactly, and `lib.count` deleted.** Placeholders unchanged
(`{n}`, `{count}`), so `generateRaviloStrings` accepts the files as they are. Before the deletion, one
`grep` confirms `lib.count` has no reader in any client or script.

**FR-R312-2 — The lexicons are regenerated and committed with the strings.**
`scripts/check-i18n-spelling.sh --update-lexicon` rewrites `i18n/lexicon/fo.txt` (and `da.txt`, for the
deleted `lib.count`) from the strings in use. It gains `sjangra`, `sjangru`, `sjangrur` and `heitir` and
drops `sjanra`, `sjanru` and `sjanrur`. It is committed **in the same commit** as `fo.json`, so the diff
shows the spellings as a decision (the i18n README's rule).

**FR-R312-3 — The old genre word is kept out.** `sjanra`, `sjanru` and `sjanrur` are added to the
Faroese `DISCOURAGED` list in `scripts/check_i18n_spelling.py`, with the owner's reason, the way
`telefonur` is. That way a string copied from an older draft (the design mockup still has them) fails
the check instead of slipping back in.

**FR-R312-4 — Checks green.** `check-i18n-spelling.sh`, `generateRaviloStrings`,
`checkRaviloStrings` (drift report reviewed, nothing new) and `check-ravilo-strings.sh` all pass.

## Non-goals

- Danish and English wording. The owner's note is about Faroese; the only change to `da.json` and
  `en.json` is the dead key's deletion.
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

## Decisions (2026-09-26, delegated by the owner)

1. **`heitir` is right, and it is what ships.** Wiktionary's Faroese entry for the neuter noun *heiti*
   gives the indefinite plural as *heiti, heitir*, both accepted (checked, not assumed: the first draft
   of this spec doubted it). Of the two, *heitir* is the one that reads as plural at a
   glance, which is the owner's point: *26 heiti* looks like a singular. The singular keys keep *1
   heiti*.
2. **The Coming Soon heading question is answered by R311.** The heading reads *Uppdaga* on every tab,
   and `nav.upcoming` is deleted there, together with its last reader. It is not deleted here, because
   this phase may ship first and the key still has a reader until R311 does.
3. **`lib.count` is deleted here**, in all three languages (see above). It has no reader, it has already
   gone stale once, and this is the strings phase that regenerates the lexicons anyway.

## Dev review (2026-09-26, against `main` `0e5e434f`)

1. **The keys are where the tables say.** `fo.json`: `seg.coming` `:61`, `seg.genres` `:68`,
   `tx.sub_genres` `:71`, `tx.n_genres` `:74`, `tx.titles` `:75`, `detail.genre` / `detail.genres`
   `:170-171`, `browse.titles` `:220`, `browse.facet.genre` `:223`. `lib.count` is `:22` in all three
   files, and no Kotlin, JavaScript or script reads it (checked 2026-09-26; R267's dropdown prints a bare
   number, `LibraryTypePill.kt:143`).
2. **The code already picks singular and plural.** `titleCountLabel` (`TaxonomyScreen.kt:209-210`),
   `BrowseScreen.kt:291-292` and `SeededBrowseScreen.kt:429` choose `_one` for exactly one. No code
   changes.
3. **The spelling guard.** `DISCOURAGED["fo"]` (`scripts/check_i18n_spelling.py:27-37`) takes
   `sjanra`, `sjanru` and `sjanrur` with one reason string. `--update-lexicon` rewrites each lexicon from
   the words in use (`:191-199`), so the old forms drop out by themselves, and `da.txt` loses `titler`
   only if no other Danish string uses it.
4. **Independent of R311.** R311 deletes `nav.upcoming`; this phase deletes `lib.count`. They touch
   different keys and can land in either order. The lexicon regeneration in the second one absorbs the
   first.

**Net effect.** Nine values, one key removed, three `DISCOURAGED` entries, the lexicons regenerated.
