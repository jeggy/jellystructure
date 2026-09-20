#!/usr/bin/env bash
# Phase R279 (FR-R279-14) — nothing a viewer reads may be written into Kotlin.
#
# R279 moved every Ravilo string to i18n/*.json. A sweep at the time found thirteen places that had
# never reached a table at all: the AUDIO/SUBTITLES headings, EpisodeCard's "UP NEXT", the NEW and
# Soon badges, a series' episodes-watched line, the IMDb chip's screen-reader text, twelve English
# month names, seven English weekday names, two English date orders, the Request tab's empty state
# and a film's "min left". None was visible to any test. This script is what would have found them.
#
# It looks only at positions that actually paint text — Text(...), RaviloButton(...) and the
# label/title/placeholder/contentDescription family — and fails on a literal there. It deliberately
# does not try to judge prose anywhere else: a check that guesses is a check people switch off.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/check_ravilo_strings.py "$@"
