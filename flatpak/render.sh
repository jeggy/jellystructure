#!/usr/bin/env bash
# R333 (FR-R333-3) — renders the manifest template for one release, into a directory that then holds everything a
# Flatpak build needs: the manifest, `flatpak-sources.json` (from `:captureFlatpakSources`) and the init script.
#
#   flatpak/render.sh <out-dir> <version> <commit> [<date>]      a release: the git tag v<version> at <commit>
#   flatpak/render.sh <out-dir> --local                          this working tree, for a local flatpak-builder run
#
# The sources file must already exist at build/flatpak-sources.json (README: "The Flatpak").
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
out=${1:?out-dir}
mode=${2:?version or --local}
template="$here/net.jebster.Ravilo.yml"
sources="$root/build/flatpak-sources.json"

[ -f "$sources" ] || { echo "render.sh: $sources is missing — run: ./gradlew --no-build-cache -Dgradle.user.home=\$(mktemp -d) -Pravilo.desktopOnly=true :ravilo-desktop:createDistributable :captureFlatpakSources" >&2; exit 1; }
mkdir -p "$out"

if [ "$mode" = "--local" ]; then
  version=$(cd "$root" && git describe --tags --always --dirty | sed 's/^v//')
  # jpackage wants N.N.N; a dev build's describe string is not one.
  pkg=$(printf '%s' "$version" | grep -oE '^[0-9]+\.[0-9]+' || echo 1.0)
  date=$(date -u +%F)
  commit=$(cd "$root" && git rev-parse HEAD)   # the screenshot URL points at it — valid once this commit is pushed
  # HEAD of this checkout instead of a tag: flatpak-builder clones the checkout's own repository, so only committed
  # files go in (a `dir` source would walk every build directory and every file only the host's user can read).
  if [ -n "$(cd "$root" && git status --porcelain --untracked-files=no)" ]; then
    echo "render.sh: note — uncommitted changes are NOT in a --local build (it clones HEAD $commit)" >&2
  fi
  python3 - "$template" "$out/net.jebster.Ravilo.yml" "$pkg" "$date" "$root" "$commit" <<'PY'
import re, sys
t, o, v, d, root, c = sys.argv[1:]
s = open(t).read()
s = re.sub(r"      - type: git\n        url: .*?\n        tag: .*?\n        commit: .*?\n        x-checker-data:\n          type: git\n          tag-pattern: .*?\n",
           "      - type: git\n        url: file://%s\n        commit: %s\n" % (root, c), s, count=1, flags=re.S)
s = s.replace("@VERSION@", v).replace("@DATE@", d).replace("@COMMIT@", c)
open(o, "w").write(s)
PY
else
  version=${mode#v}
  commit=${3:?commit}
  date=${4:-$(date -u +%F)}
  [[ "$version" =~ ^[0-9]+\.[0-9]+$ ]] || { echo "render.sh: '$version' is not MAJOR.MINOR" >&2; exit 1; }
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "render.sh: '$commit' is not a full commit id" >&2; exit 1; }
  sed -e "s/@VERSION@/$version/g" -e "s/@COMMIT@/$commit/g" -e "s/@DATE@/$date/g" "$template" > "$out/net.jebster.Ravilo.yml"
fi
cp "$sources" "$out/flatpak-sources.json"
echo "rendered $out/net.jebster.Ravilo.yml ($(grep -c '"url"' "$out/flatpak-sources.json") artefacts)"
