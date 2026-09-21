# Ravilo's translations

Everything Ravilo says to a viewer is in this directory — one JSON file per language — and nowhere
else. That is true for **every** client: the Android TV app, the phone app, the web app, the
Chromecast receiver and the Tizen TV receiver all read the same table.

If you are translating, you need nothing from this repository but this directory.

## Adding a language

1. Copy `en.json` to `<code>.json` — the code is what the file is called, e.g. `es.json`.
2. Change `_meta`:
   ```json
   "_meta": { "code": "es", "name": "Español", "englishName": "Spanish" }
   ```
   `name` is what the language calls **itself** — that is what the picker shows. `Føroyskt`, not
   `Faroese`.
3. Translate as much as you like. **You do not have to finish.** Anything you leave out falls back
   to English, key by key, and the build tells you what is still missing.

That is the whole procedure. Nothing in the app's source code lists the languages; the interface
language picker on the TV and the one in the jellystructure admin both read this directory.

## Writing a translation

A value is either the text, or the text with a note attached:

```json
"nav.home": "Hjem",

"cast.connecting": {
  "text": "Forbinder til {device}…",
  "note": "{device} is the TV's own name, as the viewer named it."
}
```

The `note` is for whoever translates it next — where the string appears, what a placeholder will be
replaced with, whether the text is a draft somebody still has to check. Notes are never shown to a
viewer. `en.json` carries most of them.

**`{name}` is a placeholder.** The app replaces it with something at the moment it draws the string.
Move it wherever the sentence needs it, but do not rename it and do not drop it — `{device}` has to
stay `{device}`. The build refuses to compile a file that renames one, because a placeholder the app
does not recognise is printed on the TV exactly as you typed it.

Ellipses are `…`, not three dots. Quotes are `"` … `"` inside a JSON string (escaped as `\"`).

## What the build does with this

`./gradlew :ravilo-i18n:generateRaviloStrings` turns these files into the table every client
compiles against. It runs on its own as part of any build; you never edit the generated Kotlin, and
it is never committed.

It **fails** on:

- a key that `en.json` does not have — always a typo;
- a placeholder that does not match English;
- a missing or inconsistent `_meta`.

It **warns**, and builds anyway, when a language is missing keys. That is deliberate: a half-finished
language has to be able to ship, or nobody starts one.

`./gradlew :ravilo-i18n:checkRaviloStrings` writes
`ravilo-i18n/build/reports/ravilo-i18n/drift.txt` — the same word spelled two ways in one language,
strings still identical to English, `...` where `…` was meant. It never fails anything. It is a list
of things for a person to look at.

## Spelling: use the real letters

**Write `Næst`, never `Naest`. Write `Mál`, never `Mal`.** Accents and `æ` `ø` `ð` are letters, not
decoration, and they render fine on every screen Ravilo draws on — a TV, a phone, a Chromecast.

`scripts/check-i18n-spelling.sh` enforces that, and it is worth knowing how, because it decides what
you have to do when you add a word:

- It fails when one file spells a word **two ways** that differ only by accents. Pick the spelling the
  file already uses.
- It fails when a word is **not in `lexicon/<code>.txt`** but an accented version of it is — `Mal`
  when the lexicon has `mál`. This is the half that catches a word you stripped *everywhere*, which
  is otherwise perfectly self-consistent and invisible.
- It fails when a **new** word contains `ae`, `oe` or `aa`.

`lexicon/<code>.txt` is every word form that language really uses, one per line. It is ground truth,
not a dictionary: a word is in it because somebody read the string it appears in. `Heim` is in there
and is correct — it never needed an accent.

So when you write a genuinely new word, the check will stop and ask about it once. Add the line:

```
scripts/check-i18n-spelling.sh --update-lexicon
```

and commit the lexicon **with** the strings that needed it, so whoever reads the diff sees that the
new spelling was a decision and not a slip. If you are starting a new language, the first run writes
your whole lexicon in one go.

A few rules are about **word choice**, not spelling. In Faroese: a bare `TV` fails, because a TV is
a `sjónvarp` (`TV` is only for a line genuinely too narrow for the word, and there is no such line
today); and `telefonurin` fails, because `telefon` is feminine — `telefonin` / `telefonina` /
`telefonini`. Both of those existed in this file for months, because each string read perfectly well
on its own and only the whole file showed the disagreement. Danish is untouched by these: `dette tv`
is ordinary Danish.

A word can legitimately be the odd one out. `Ambætaraadressa` keeps its `aa` because it is a word
join (*Ambætara* + *adressa*) and Faroese has no `å` for an `aa` rule to produce. Unit abbreviations
are exempt too — `min` is minutes in every language, and is not a misspelling of Faroese `mín`.

## Which language a viewer sees

```
the language configured for the signed-in viewer     (Ravilo → Settings → Interface language)
  ↓ nobody is signed in, or that language is not in this directory
the language this device last drew in                (remembered locally; survives signing out)
  ↓ this device has never drawn one
English
```

The middle rung is why a Chromecast sitting idle, a TV showing its pairing code and the login screen
are in the household's own language rather than English. On a Chromecast the top rung is the
language of **whoever pressed cast**, which travels with the hand-off — so two people in one house
each get their own.
