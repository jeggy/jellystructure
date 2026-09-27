#!/usr/bin/env bash
# Phase R315 (FR-R315-4) — every character Ravilo draws as text is in a font the web app bundles.
#
# Compose on the web has no system fonts (Compose Multiplatform 1.9.3), so a character that neither
# Sora and Space Grotesk both carry, nor the bundled fallback font, is drawn as a replacement box — the
# "question marks" the web app used to show for ✓, ✕, ★, the dropdown chevrons, and the native names of
# Russian, Japanese, Arabic, Hindi… tracks. This scans every Kotlin string literal in
# ravilo-ui/src/commonMain and every value in i18n/*.json, reads the fonts' cmap tables itself (no
# fontTools), and fails naming each uncovered character. The fix is one of:
#   - an icon on its own: draw it with ravilo-ui/.../components/Glyphs.kt;
#   - a symbol in a sentence, or a new language name: python3 scripts/build-web-fallback-font.py
#     (needs fonttools) and commit the regenerated web_fallback.ttf.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/web_glyphs.py "$@"
