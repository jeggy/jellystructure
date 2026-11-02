#!/usr/bin/env bash
# DEB-2480 (DEB-2474, FR-6.3) — yt-dlp is a new dependency of the backend image, and the only third-party program in it that fetches pages on
# its own. This fence keeps it what the spec says it is: pinned to ONE release, checked against that release's published SHA-256 at the image
# build, run once at build, and in no Ravilo image.
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0
need() { # file pattern what
  grep -qE -- "$2" "$1" || { echo "$1: $3"; fail=1; }
}

need Dockerfile '^ARG YTDLP_VERSION=[0-9]{4}\.[0-9]{2}\.[0-9]{2}$' 'yt-dlp must be pinned to one dated release, never latest (FR-6.3)'
need Dockerfile '^ARG YTDLP_SHA256=[0-9a-f]{64}$' 'yt-dlp needs the pinned SHA-256 of that release (FR-6.3)'
need Dockerfile 'echo "\$\{YTDLP_SHA256\}  /usr/local/bin/yt-dlp" \| sha256sum -c -' 'the image build must check yt-dlp against the pinned SHA-256 (FR-6.3)'
need Dockerfile '/usr/local/bin/yt-dlp --version' 'the image build must run yt-dlp once (FR-6.3)'
if grep -nE 'yt-dlp/releases/(latest|download/latest)' Dockerfile; then echo "Dockerfile: yt-dlp must not be fetched from 'latest'"; fail=1; fi
# The recipe reader keeps yt-dlp inside the lane: never the generic extractor, never a config file.
need src/linuxX64Main/kotlin/dev/jellystructure/autofetch/YtDlp.kt '--use-extractors default,-generic' 'yt-dlp must run with the generic extractor off (FR-6.3)'
need src/linuxX64Main/kotlin/dev/jellystructure/autofetch/YtDlp.kt '--ignore-config' 'yt-dlp must run with --ignore-config (FR-6.3)'

# Only the backend image carries it.
for f in ravilo-web/Dockerfile ravilo-screen/Dockerfile avsync/Dockerfile; do
  if grep -qi 'yt-dlp' "$f"; then echo "$f: yt-dlp belongs in the backend image only (FR-6.3)"; fail=1; fi
done
exit $fail
