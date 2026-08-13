#!/usr/bin/env bash
# Regression fence for CSS class-name collisions between a page-scoped stylesheet and the shared
# design system (wf.css/app.css). Every stylesheet listed below claims (in its own header comment)
# to keep its selectors under one wrapper class so it can "ship next to wf.css untouched" — this
# script actually verifies that, rather than trusting the comment.
#
# Why this matters: index.html links every page's CSS unconditionally (wf.css, app.css, ..., this
# file), regardless of which route is showing. A selector that escapes its own scope silently wins
# the cascade on EVERY page for any shared component that happens to share its class name (.card,
# .dot, .stat, .seg, ...), since page-specific files load last.
#
# Found live 2026-08-13: segments.css's header said "Scoped under .sx" but only .sx/.sx * actually
# were — 160+ other selectors were bare. .seg{position:absolute;top:0;bottom:0} (meant only for that
# page's own timeline segment bars) silently overrode wf.css's unrelated .seg (the app-shell sidebar's
# shared segmented-control component, e.g. the theme picker) on every single page, turning the theme
# picker into a full-height overlay that covered the sidebar's logo/nav/status underneath it.
set -euo pipefail
cd "$(dirname "$0")/.."

# file -> its own declared scope-wrapper class (from that file's own header/established convention).
declare -A SCOPED_FILES=(
  ["design/app/segments.css"]=".sx"
  ["design/app/towo.css"]=".towo"
)

# Selectors allowed to stay outside the scope for this file, with the reason inline in the file
# itself — e.g. an element that renders as a SIBLING of the wrapper, not a descendant, or a rule that
# only ever matches a standalone mockup page the real app never loads. Pipe-separated regex alternates.
declare -A ALLOWED_UNSCOPED=(
  ["design/app/segments.css"]="\.sxtoast|#toasts|html\.sxpage|body\.sxpage|#root"
)

fail=0

for file in "${!SCOPED_FILES[@]}"; do
  scope="${SCOPED_FILES[$file]}"
  allowed="${ALLOWED_UNSCOPED[$file]:-x^}"  # "x^" never matches anything — a harmless default
  scope_escaped=$(printf '%s' "$scope" | sed 's/[.[\*^$/]/\\&/g')

  violations=$(
    perl -0777 -pe 's{/\*.*?\*/}{}gs' "$file" \
      | grep -oP '(?:^|[{};])\K[^{}]+(?=\{)' \
      | tr ',' '\n' \
      | sed -E 's/^[[:space:]]+|[[:space:]]+$//g' \
      | grep -v '^$' \
      | grep -vE '^@media|^@keyframes' \
      | grep -vE '^[0-9]+%$|^from$|^to$' \
      | grep -vE "^${scope_escaped}(\$|[[:space:].*])" \
      | grep -viE "^(${allowed})"
  ) || true

  if [ -n "$violations" ]; then
    echo "UNSCOPED SELECTORS in $file (expected every rule under $scope):"
    echo "$violations" | sort -u | sed 's/^/  /'
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "OK — every page-scoped stylesheet keeps its selectors under its own wrapper class."
else
  echo
  echo "A selector outside its file's declared scope can silently override a same-named shared"
  echo "component (wf.css/app.css) on every page that loads after this file in index.html — this is"
  echo "the exact bug class that hid the app-shell sidebar behind a theme-picker overlay on 2026-08-13."
  echo "Fix: prefix the selector with the file's own scope class (e.g. \"$scope .whatever\"), or if it"
  echo "genuinely needs to stay unscoped (renders outside the wrapper, or is dead/mockup-only code),"
  echo "add it to ALLOWED_UNSCOPED in this script with a comment explaining why."
fi
exit "$fail"
