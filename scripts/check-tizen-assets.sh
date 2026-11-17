#!/usr/bin/env bash
# DEB-4818 FR-17, FR-18 — the .wgt carries the real launcher icon and the on-screen mark.
#   check-tizen-assets.sh            checks ravilo-screen/wgt
#   check-tizen-assets.sh --selftest plants a placeholder icon and a missing mark and expects the guard to fail
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# png_size <file> prints "<width>x<height>" from the IHDR chunk (bytes 16-23, big-endian).
png_size() {
  local w h
  read -r w h < <(od -An -tu1 -j16 -N8 "$1" | awk '{print $1*16777216+$2*65536+$3*256+$4, $5*16777216+$6*65536+$7*256+$8}')
  echo "${w}x${h}"
}

# png_header <w> <h> writes the 24 bytes png_size reads: the signature, an IHDR length and tag, then width and height.
png_header() {
  local v
  printf '\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR'
  for v in $1 $2; do
    printf "$(printf '\\x%02x\\x%02x\\x%02x\\x%02x' $((v>>24&255)) $((v>>16&255)) $((v>>8&255)) $((v&255)))"
  done
}

# check_wgt <wgt dir>
check_wgt() {
  local dir="$1" icon size src
  icon=$(sed -n 's|.*<icon src="\([^"]*\)".*|\1|p' "$dir/config.xml" | head -1)
  if [[ -z $icon || ! -s $dir/$icon ]]; then
    echo "$dir/config.xml names icon '$icon', which is not in the widget" >&2
    return 1
  fi
  size=$(png_size "$dir/$icon")
  if [[ $size != 512x423 ]]; then
    echo "$dir/$icon is $size; Samsung's TV launcher icon is 512x423" >&2
    return 1
  fi
  # Every <img class="mark" src="…"> must name a file packed beside index.html.
  local marks
  marks=$(grep -oE '<img class="(brand-)?mark" src="[^"]*"' "$dir/index.html" | sed 's|.*src="||; s|"$||' || true)
  if [[ -z $marks ]]; then
    echo "$dir/index.html has no <img class=\"mark\">: the setup screen would draw no Ravilo logo" >&2
    return 1
  fi
  while read -r src; do
    case $src in http*|//*) echo "$dir/index.html fetches its mark from $src; it must be packed in the widget" >&2; return 1 ;; esac
    [[ -s $dir/$src ]] || { echo "$dir/index.html names mark '$src', which is not in the widget" >&2; return 1; }
  done <<< "$marks"
}

if [[ ${1:-} == --selftest ]]; then
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' EXIT
  printf '<icon src="icon.png"/>\n' > "$fixture/config.xml"
  png_header 128 128 > "$fixture/icon.png"
  printf '<img class="mark" src="mark.svg" alt="">\n' > "$fixture/index.html"
  if check_wgt "$fixture" 2>/dev/null; then echo 'Tizen assets guard accepted a 128x128 icon' >&2; exit 1; fi
  png_header 512 423 > "$fixture/icon.png"
  if check_wgt "$fixture" 2>/dev/null; then echo 'Tizen assets guard accepted a mark that is not packed' >&2; exit 1; fi
  printf '<div class="mark"></div>\n' > "$fixture/index.html"
  if check_wgt "$fixture" 2>/dev/null; then echo 'Tizen assets guard accepted a mark that is not an image' >&2; exit 1; fi
  printf '<svg/>' > "$fixture/mark.svg"
  printf '<img class="mark" src="mark.svg" alt="">\n' > "$fixture/index.html"
  check_wgt "$fixture"
  echo 'Tizen assets guard rejects a placeholder icon, a missing mark and a non-image mark'
  exit 0
fi

check_wgt "$root/ravilo-screen/wgt"
echo "Tizen widget carries the 512x423 icon and a packed Ravilo mark"
