#!/usr/bin/env bash
# Phase R287 (FR-R287-4) — one word, one spelling, per language.
#
# i18n/fo.json spelled `loyniord` seven times and `loyniorð` five; da.json had `pa` beside `på`
# thirty-one times. Not a font problem — both spellings sat in the same file, sometimes in the same
# string. It survived because nothing could see it: the JSON was valid, every key resolved, and every
# test passed. This is what would have seen it.
#
# It compares words with their accents folded away, so two spellings of one word collide, and fails
# when a language file holds both. It does NOT judge translations — only self-consistency.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/check_i18n_spelling.py "$@"
