#!/usr/bin/env bash
# DEB-1469 (FR-4, FR-7) / DEB-2122 — the .dmg window is still the drawn window.
#
# The layout is only ever *built* on a macOS runner (deploy-macos.yml), so this is the part that can be held on any
# host, on every push: the two background images are the sizes the window needs, the icon centres in the settings
# file and in the read-back verifier are the spec's numbers, and the workflow writes the layout BEFORE it signs the
# image and before the appcast's EdDSA signature is computed over the final bytes (FR-7).
set -euo pipefail
cd "$(dirname "$0")/.."

dir=ravilo-desktop/dmg
wf=.github/workflows/deploy-macos.yml
fail=0
bad() { echo "FAIL: $*"; fail=1; }

# A PNG's size is two big-endian 32-bit integers right after the signature and the IHDR tag.
png_size() { python3 -I -c 'import struct,sys; b=open(sys.argv[1],"rb").read(24); assert b[:8]==b"\x89PNG\r\n\x1a\n"; print(*struct.unpack(">II", b[16:24]))' "$1"; }

# 720 x 520pt content area plus the 28pt strip that covers the title bar (see scripts/render-mac-open-anyway.mjs).
[ "$(png_size $dir/background.png)" = "720 548" ] || bad "$dir/background.png is not 720 x 548"
[ "$(png_size "$dir/background@2x.png")" = "1440 1096" ] || bad "$dir/background@2x.png is not 1440 x 1096"

grep -Eq '^\s+app_name: \(190, 190\),' $dir/dmg-settings.py || bad "dmg-settings.py: the app icon is not centred at (190, 190)"
grep -Eq '^\s+"Applications": \(530, 190\),' $dir/dmg-settings.py || bad "dmg-settings.py: Applications is not centred at (530, 190)"
grep -Fq 'window_rect = ((200, 120), (720, 548))' $dir/dmg-settings.py || bad "dmg-settings.py: the window is not 720 x 548"
grep -Fq '(190, 190)' $dir/verify-layout.py && grep -Fq '(530, 190)' $dir/verify-layout.py \
  && grep -Fq '{{200, 120}, {720, 548}}' $dir/verify-layout.py || bad "verify-layout.py does not hold the settings file's numbers"

# FR-7 — order of the lines in the workflow: layout, then read-back, then the .dmg signature, then the appcast.
line() { grep -nF -- "$1" $wf | head -1 | cut -d: -f1; }
layout=$(line 'bin/dmgbuild" -s ravilo-desktop/dmg/dmg-settings.py')
verify=$(line 'ravilo-desktop/dmg/verify-layout.py "$MNT"')
signdmg=$(line 'codesign --force --timestamp=none --keychain "$KEYCHAIN" --sign "$IDENTITY" "$DMG"')
appcast=$(line 'sign_update" --ed-key-file')
if [ -z "$layout" ] || [ -z "$verify" ] || [ -z "$signdmg" ] || [ -z "$appcast" ]; then
  bad "$wf: cannot find the dmgbuild, verify-layout, .dmg codesign and sign_update lines"
elif ! [ "$layout" -lt "$verify" ] || ! [ "$verify" -lt "$signdmg" ] || ! [ "$signdmg" -lt "$appcast" ]; then
  bad "$wf: the window layout must be written and read back before the .dmg is signed and before the appcast signature (FR-7)"
fi

[ "$fail" = 0 ] && echo "OK: the .dmg window background, icon centres and signing order are as drawn"
exit "$fail"
