#!/usr/bin/env bash
# DEB-1490 — the jellystructure and ravilo-web images are built for linux/amd64 and linux/arm64, and the arm64 half is
# CROSS-compiled: Kotlin/Native 2.3 has no Linux arm64 compiler host, so a builder stage that runs as arm64 (what
# `platforms: linux/amd64,linux/arm64` does on its own) cannot start the compiler. This fence keeps the four pieces that
# make the arm64 image work from being undone one at a time:
#   - each Dockerfile's builder is `FROM --platform=$BUILDPLATFORM` and links linuxArm64 for TARGETARCH=arm64
#   - the main Dockerfile picks dovi_tool's asset AND checksum per architecture
#   - publish.yml builds both images for both platforms (with QEMU) and leaves avsync amd64
#   - the module build scripts still declare the linuxArm64 target
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0
need() { # file pattern what
  grep -qE -- "$2" "$1" || { echo "$1: $3"; fail=1; }
}

for f in Dockerfile ravilo-web/Dockerfile; do
  need "$f" '^FROM --platform=\$BUILDPLATFORM eclipse-temurin' 'builder stage must be FROM --platform=$BUILDPLATFORM (DEB-1490 FR-2)'
  need "$f" 'linkReleaseExecutableLinux\$K' 'must link the executable for $TARGETARCH, not a fixed linuxX64 (DEB-1490 FR-2)'
  need "$f" 'arm64\) K=Arm64' 'must map TARGETARCH=arm64 to the linuxArm64 link (DEB-1490 FR-2)'
done
need Dockerfile 'DOVI_TOOL_SHA256_AMD64=[0-9a-f]{64}' 'dovi_tool needs a pinned amd64 SHA-256 (DEB-1490 FR-2)'
need Dockerfile 'DOVI_TOOL_SHA256_ARM64=[0-9a-f]{64}' 'dovi_tool needs a pinned arm64 SHA-256 (DEB-1490 FR-2)'
need Dockerfile 'libsqlite3-dev:arm64' 'the builder needs the arm64 libsqlite3-dev for the cross link (DEB-1490 FR-2)'

P=.github/workflows/publish.yml
need "$P" 'docker/setup-qemu-action' 'publish.yml must set up QEMU for the arm64 runtime stage and smoke (DEB-1490 FR-3)'
need "$P" 'Smoke the arm64 image' 'publish.yml must smoke the arm64 image after the push (DEB-1490 FR-5)'
# The matrix entry for each image carries its platforms on the line after `dockerfile:`.
for pair in "Dockerfile:linux/amd64,linux/arm64" "ravilo-web/Dockerfile:linux/amd64,linux/arm64" "avsync/Dockerfile:linux/amd64"; do
  df=${pair%%:*}; want=${pair#*:}
  got=$(grep -A1 -E "^[[:space:]]+dockerfile: $df\$" "$P" | sed -nE 's/^[[:space:]]+platforms: ([^ #]+).*/\1/p')
  [ "$got" = "$want" ] || { echo "$P: $df is built for '${got:-nothing}', expected '$want' (DEB-1490 FR-3)"; fail=1; }
done

for f in build.gradle.kts shared/build.gradle.kts ravilo-i18n/build.gradle.kts web-static-server/build.gradle.kts; do
  need "$f" 'linuxArm64' 'must declare the linuxArm64 target (DEB-1490 FR-1)'
done

[ "$fail" -eq 0 ] || exit 1
echo "OK: the jellystructure and ravilo-web images cross-build linuxArm64 (DEB-1490)."
