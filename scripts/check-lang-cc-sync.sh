#!/usr/bin/env bash
# Phase R239 (FR-R239-4) — the admin and Ravilo language-flag tables are two independent, hand-written
# copies of "which language code maps to which flag" (MediaDetail.kt's LANG_CC for the admin pagebar,
# AudioFlagStrip.kt's LANG_CC for the Ravilo detail hero). They agreed by luck — 73 codes, same keys,
# zero divergence, measured 2026-09-06 — and FR-R239-3 (adding flags for languages the library actually
# holds, e.g. Serbian/Bulgarian/Indonesian) doubles every edit, which is exactly when luck runs out.
#
# Extracts the quoted language-code keys from each LANG_CC map literal and diffs the two sets. Exits
# non-zero on any divergence.
set -euo pipefail
cd "$(dirname "$0")/.."

ADMIN="src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt"
RAVILO="ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/AudioFlagStrip.kt"

extract_keys() {
  # From the `private/internal val LANG_CC = mapOf(` line to the first line that is just `)`.
  # Each entry is `"key" to <value>` — only the token before " to " is the language code; the admin
  # table's value is itself a quoted string ("gb"), which a value-blind regex would double-count.
  sed -n '/val LANG_CC/,/^)/p' "$1" \
    | grep -oE '"[a-z]{2,3}"[[:space:]]+to[[:space:]]' \
    | grep -oE '"[a-z]{2,3}"' \
    | tr -d '"' | sort -u
}

admin_keys="$(extract_keys "$ADMIN")"
ravilo_keys="$(extract_keys "$RAVILO")"

only_admin="$(comm -23 <(echo "$admin_keys") <(echo "$ravilo_keys"))"
only_ravilo="$(comm -13 <(echo "$admin_keys") <(echo "$ravilo_keys"))"

fail=0
if [ -n "$only_admin" ]; then
  echo "ONLY IN ADMIN ($ADMIN), missing from Ravilo's LANG_CC:"
  echo "$only_admin" | sed 's/^/  /'
  fail=1
fi
if [ -n "$only_ravilo" ]; then
  echo "ONLY IN RAVILO ($RAVILO), missing from admin's LANG_CC:"
  echo "$only_ravilo" | sed 's/^/  /'
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "OK — admin and Ravilo LANG_CC agree ($(echo "$admin_keys" | wc -l | tr -d ' ') keys each)."
fi
exit "$fail"
