#!/usr/bin/env bash
# R333 (FR-R333-5) — proves the offline build without flatpak-builder: fills an `offline-repository` from
# build/flatpak-sources.json (the artefacts a fresh capture just downloaded, matched by SHA-256, else
# fetched), unpacks the very Gradle the manifest names, and runs the manifest's build command with an EMPTY Gradle
# home and `--offline`. If this passes, Flathub's sandbox lacks nothing but the sandbox.
#
#   flatpak/verify-offline.sh <work-dir> [<gradle-home-of-the-capture>]
#
# Run `./gradlew --no-build-cache -Dgradle.user.home=<capture-home> -Pravilo.desktopOnly=true
#      :ravilo-desktop:createDistributable :captureFlatpakSources` first.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=${1:?work-dir}
capture_home=${2:-}
sources="$root/build/flatpak-sources.json"
[ -f "$sources" ] || { echo "verify-offline.sh: $sources is missing — capture first" >&2; exit 1; }
mkdir -p "$work"
work=$(cd "$work" && pwd)
repo="$work/offline-repository"

# 1. The offline repository, exactly as flatpak-builder lays the `type: file` sources out (dest + dest-filename).
python3 - "$sources" "$repo" "$capture_home" <<'PY'
import hashlib, json, os, shutil, subprocess, sys
sources, repo, capture = sys.argv[1], sys.argv[2], sys.argv[3]
entries = json.load(open(sources))
# Every file the capture downloaded, by SHA-256 — copying beats fetching a gigabyte twice.
by_sha = {}
if capture:
    for dp, _, fns in os.walk(os.path.join(capture, "caches", "modules-2", "files-2.1")):
        for fn in fns:
            p = os.path.join(dp, fn)
            h = hashlib.sha256()
            with open(p, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            by_sha.setdefault(h.hexdigest(), p)
copied = fetched = 0
for e in entries:
    if e.get("type") != "file":
        continue
    dest = os.path.join(repo, e["dest"].split("/", 1)[1] if e["dest"].startswith("offline-repository/") else e["dest"])
    os.makedirs(dest, exist_ok=True)
    target = os.path.join(dest, e["dest-filename"])
    if os.path.exists(target):
        continue
    src = by_sha.get(e["sha256"])
    if src:
        shutil.copyfile(src, target); copied += 1
    else:
        subprocess.run(["curl", "-sSfL", "--retry", "3", "-o", target, e["url"]], check=True); fetched += 1
    h = hashlib.sha256(open(target, "rb").read()).hexdigest()
    if h != e["sha256"]:
        sys.exit(f"{target}: sha256 {h} != {e['sha256']}")
print(f"offline repository: {copied} copied, {fetched} fetched, {sum(1 for e in entries if e.get('type') == 'file')} artefacts")
PY

# 2. The Gradle the manifest names — the same archive, the same checksum.
zip_url=$(grep -oE 'https://services.gradle.org/distributions/gradle-[0-9.]+-bin.zip' "$root/flatpak/net.jebster.Ravilo.yml" | head -1)
zip_sha=$(grep -A1 "$zip_url" "$root/flatpak/net.jebster.Ravilo.yml" | grep -oE '[0-9a-f]{64}')
if [ ! -x "$work/gradle/bin/gradle" ]; then
  curl -sSfL --retry 3 -o "$work/gradle.zip" "$zip_url"
  echo "$zip_sha  $work/gradle.zip" | sha256sum -c - >/dev/null
  rm -rf "$work/gradle" "$work/gradle-unpack"; mkdir -p "$work/gradle-unpack"
  (cd "$work/gradle-unpack" && unzip -q "$work/gradle.zip")
  mv "$work"/gradle-unpack/gradle-* "$work/gradle"; rmdir "$work/gradle-unpack"
fi

# 3. The build, as the manifest runs it: no network, an empty Gradle home, every repository the offline directory.
rm -rf "$work/gradle-home"; mkdir -p "$work/gradle-home"
version=$(cd "$root" && git describe --tags --always | sed 's/^v//' | grep -oE '^[0-9]+\.[0-9]+' || echo 1.0)
cd "$root"
"$work/gradle/bin/gradle" --offline --no-daemon --console=plain \
  -Dgradle.user.home="$work/gradle-home" \
  --init-script flatpak/gradle-offline.init.gradle.kts \
  -Pravilo.desktopOnly=true \
  -Pravilo.flatpakOfflineRepo="$repo" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pjellystructure.version="$version" \
  -Pravilo.macPackageVersion="$version.0" \
  :ravilo-desktop:createDistributable
test -x ravilo-desktop/build/compose/binaries/main/app/Ravilo/bin/Ravilo
ravilo-desktop/build/compose/binaries/main/app/Ravilo/bin/Ravilo --self-test
echo "verify-offline.sh: the offline build passed"
