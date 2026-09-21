#!/usr/bin/env bash
# Phase R287 (FR-R287-4) — one word, one spelling, per language.
# Phase R288 (FR-R288-4/-5) — and one word, the RIGHT spelling.
#
# R287: i18n/fo.json spelled `loyniord` seven times and `loyniorð` five; da.json had `pa` beside `på`
# thirty-one times. Not a font problem — both spellings sat in the same file, sometimes in the same
# string. It survived because nothing could see it: the JSON was valid, every key resolved, and every
# test passed. This is what would have seen it. It compares words with their accents folded away, so
# two spellings of one word collide, and fails when a language file holds both.
#
# That check has an edge, and R287 hit it and stopped at it honestly: a word stripped in EVERY
# occurrence is perfectly self-consistent. `Mal`, `Spaling`, `sjalvvirkandi` and `Latid` all sailed
# through it, and R287's STATUS row says so. R287 tried a stem-matching heuristic for that class and
# rejected it, rightly — it flagged `Heim` and `samband`, which are correctly unaccented, because a
# heuristic has no ground truth to appeal to.
#
# R288 adds the ground truth: i18n/lexicon/<code>.txt, every word form that language really uses,
# reviewed once by a speaker. A word that is NOT in the lexicon but whose folded form IS now fails —
# `Mal` against `mál`, `Sog` against `Søg` — as does a new word carrying an `ae`/`oe`/`aa` digraph
# instead of the real letter. Owner, R288: "we should never use terms like `Naest`, we should use
# real letters like `Næst`."
#
# A third, deliberately tiny part judges WORD CHOICE, from standing owner decisions rather than
# from the file: a bare `TV` in Faroese (it is a `sjónvarp`), and `telefonurin` (`telefon` is
# feminine — telefonin / telefonina / telefonini). Both existed because each string read fine on
# its own; only the whole file showed the disagreement. Every rule names what to use instead, and
# every exception is a key with a reason.
#
# Beyond that, neither half judges translations.
#
#   scripts/check-i18n-spelling.sh                   check
#   scripts/check-i18n-spelling.sh --update-lexicon  regenerate the lexicons from the current files
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/check_i18n_spelling.py "$@"
