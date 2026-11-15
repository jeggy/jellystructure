#!/usr/bin/env bash
# DEB-4674 FR-13: parse the built production widget at the Tizen 5.0 syntax floor.
set -euo pipefail
bundle="${1:?usage: scripts/check-tizen-bundle.sh path/to/ravilo-screen.js}"
test -s "$bundle" || { echo "Missing ravilo-screen bundle: $bundle" >&2; exit 1; }
# Pin the parser so the CI check cannot drift with the runner's Node installation.
npm exec --yes --package=acorn@8.17.0 -- acorn --ecma2017 "$bundle" > /dev/null
echo "ES2017 parse passed: $bundle"
