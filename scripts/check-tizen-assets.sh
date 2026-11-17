#!/usr/bin/env bash
# DEB-4818 FR-17, FR-18 — the .wgt carries the real launcher icon and the on-screen mark.
# DEB-4893 (DEB-4889 FR-19) — and a flag for every country code `shared`'s language table names: the title page draws
# `flags/<cc>.png` from the widget and never fetches a flag from the server.
#   check-tizen-assets.sh                  checks ravilo-screen/wgt, and that ravilo-ui holds the source PNG for every flag the table names
#   check-tizen-assets.sh --packed <dir>   checks that <dir>/flags/<cc>.png exists for every flag the table names (a built widget)
#   check-tizen-assets.sh --selftest       plants a placeholder icon, a missing mark and a missing flag and expects the guard to fail
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

table="$root/shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/LanguageFlags.kt"
sources="$root/ravilo-ui/src/commonMain/composeResources/drawable"

# table_ccs <table file> prints every country code the language table maps to, once.
table_ccs() {
  grep -oE '"[a-z0-9-]+" to "[a-z]{2}"' "$1" | sed 's/.* to "//; s/"$//' | sort -u
}

# check_flags <dir holding flags/> <table file> — every country code in the table has a flags/<cc>.png.
check_flags() {
  local dir="$1" cc ccs missing=0
  ccs=$(table_ccs "$2")
  if [[ -z $ccs ]]; then
    echo "$2 names no flag country codes: the guard could not read the language table" >&2
    return 1
  fi
  while read -r cc; do
    [[ -s $dir/flags/$cc.png ]] || { echo "$dir/flags/$cc.png is missing: the language table maps a language to '$cc'" >&2; missing=1; }
  done <<< "$ccs"
  return $missing
}

# check_sources <drawable dir> <table file> — every country code in the table has the ravilo-ui flag_<cc>.png the build packs.
check_sources() {
  local dir="$1" cc ccs missing=0
  ccs=$(table_ccs "$2")
  [[ -n $ccs ]] || { echo "$2 names no flag country codes: the guard could not read the language table" >&2; return 1; }
  while read -r cc; do
    [[ -s $dir/flag_$cc.png ]] || { echo "$dir/flag_$cc.png is missing: the language table maps a language to '$cc'" >&2; missing=1; }
  done <<< "$ccs"
  return $missing
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
  if [[ $size != 512x288 ]]; then
    echo "$dir/$icon is $size; the Smart Hub tile takes the icon's own shape, so the launcher icon is the 16:9 512x288" >&2
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
  png_header 512 288 > "$fixture/icon.png"
  if check_wgt "$fixture" 2>/dev/null; then echo 'Tizen assets guard accepted a mark that is not packed' >&2; exit 1; fi
  printf '<div class="mark"></div>\n' > "$fixture/index.html"
  if check_wgt "$fixture" 2>/dev/null; then echo 'Tizen assets guard accepted a mark that is not an image' >&2; exit 1; fi
  printf '<svg/>' > "$fixture/mark.svg"
  printf '<img class="mark" src="mark.svg" alt="">\n' > "$fixture/index.html"
  check_wgt "$fixture"
  # FR-19 — a flag the table names but the widget (or the source drawables) lacks.
  printf 'val T = mapOf("en" to "gb", "da" to "dk")\n' > "$fixture/table.kt"
  mkdir -p "$fixture/flags" "$fixture/drawable"
  printf 'png' > "$fixture/flags/gb.png"
  if check_flags "$fixture" "$fixture/table.kt" 2>/dev/null; then echo 'Tizen assets guard accepted a table flag that is not packed' >&2; exit 1; fi
  printf 'png' > "$fixture/flags/dk.png"
  check_flags "$fixture" "$fixture/table.kt"
  printf 'png' > "$fixture/drawable/flag_gb.png"
  if check_sources "$fixture/drawable" "$fixture/table.kt" 2>/dev/null; then echo 'Tizen assets guard accepted a table flag with no source image' >&2; exit 1; fi
  printf 'png' > "$fixture/drawable/flag_dk.png"
  check_sources "$fixture/drawable" "$fixture/table.kt"
  printf 'nothing here\n' > "$fixture/empty.kt"
  if check_flags "$fixture" "$fixture/empty.kt" 2>/dev/null; then echo 'Tizen assets guard accepted a table it could not read' >&2; exit 1; fi
  echo 'Tizen assets guard rejects a placeholder icon, a missing mark, a non-image mark and a flag the table names that is not packed'
  exit 0
fi

if [[ ${1:-} == --packed ]]; then
  check_flags "${2:?usage: check-tizen-assets.sh --packed <dir>}" "$table"
  echo "Widget at $2 packs a flag for every country code the language table names"
  exit 0
fi

check_wgt "$root/ravilo-screen/wgt"
check_sources "$sources" "$table"
# A built widget (syncScreenReceiver has run) must carry them too.
[[ ! -d $root/ravilo-screen/wgt/flags ]] || check_flags "$root/ravilo-screen/wgt" "$table"
echo "Tizen widget carries the 512x288 icon, a packed Ravilo mark and a flag for every language the table names"
