#!/usr/bin/env bash
# Creates a broken media fixture library for Jellystructure integration tests.
# Requires: ffmpeg, mkvpropedit (mkvtoolnix)
# Output:   /tmp/jellystructure-fixtures/  (or $FIXTURE_DIR)
#
# What "broken" means here:
#   - Movie has two audio tracks (eng, fra); wrong one (fra) is set as default
#   - TV series has uniform eng audio across all episodes (correct for sampling)
#   - A second movie has untagged audio tracks (triage candidates)
#
# Tests must fix the defaults and verify via ffprobe.
set -euo pipefail

FIXTURE_DIR="${FIXTURE_DIR:-/tmp/jellystructure-fixtures}"
DURATION=5  # seconds per synthetic clip — short for CI speed

log() { echo "[fixtures] $*"; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not found — install $1"; exit 1; }
}
require_cmd ffmpeg
require_cmd mkvpropedit

mkdir -p "$FIXTURE_DIR"

# ─── helpers ──────────────────────────────────────────────────────────────────

# make_audio_track <lang> <hz> <output.wav>
# Generates a silent WAV with the given sample rate (different per track for
# easy identification in ffprobe JSON).
make_audio_track() {
  local lang=$1 hz=$2 out=$3
  ffmpeg -y -f lavfi -i "anullsrc=r=${hz}:cl=mono" -t "$DURATION" "$out" -loglevel error
}

# make_video <output.y4m>
make_video() {
  ffmpeg -y -f lavfi -i "color=c=blue:s=320x180:r=24" -t "$DURATION" "$1" -loglevel error
}

# mux_mkv <video> <audio1> <lang1> <audio2> <lang2> <output.mkv>
# Muxes one video + two audio streams; audio1 is set default=1, audio2 default=0.
mux_mkv() {
  local video=$1 a1=$2 l1=$3 a2=$4 l2=$5 out=$6
  ffmpeg -y \
    -i "$video" -i "$a1" -i "$a2" \
    -map 0:v -map 1:a -map 2:a \
    -metadata:s:a:0 language="$l1" \
    -metadata:s:a:1 language="$l2" \
    -disposition:a:0 default \
    -disposition:a:1 0 \
    -c copy "$out" -loglevel error
}

# set_wrong_default <mkv> — flips: track 0 default=0, track 1 default=1
set_wrong_default() {
  local mkv=$1
  mkvpropedit "$mkv" \
    --edit track:a1 --set flag-default=0 \
    --edit track:a2 --set flag-default=1 \
    2>/dev/null
}

# ─── fixture 1: Sintel (2010) — wrong default (fra before eng) ────────────────
log "Building: Sintel (2010) — wrong fra default"
SINTEL_DIR="$FIXTURE_DIR/movies/Sintel (2010)"
mkdir -p "$SINTEL_DIR"
TMP=$(mktemp -d)
make_video          "$TMP/vid.y4m"
make_audio_track eng 44100 "$TMP/eng.wav"
make_audio_track fra 48000 "$TMP/fra.wav"
# Mux: eng first (correct default from mux), then flip it so fra becomes default
mux_mkv "$TMP/vid.y4m" "$TMP/eng.wav" eng "$TMP/fra.wav" fra \
        "$SINTEL_DIR/Sintel (2010).mkv"
set_wrong_default "$SINTEL_DIR/Sintel (2010).mkv"
rm -rf "$TMP"
log "  → $SINTEL_DIR/Sintel (2010).mkv (fra is wrong default)"

# ─── fixture 2: Big Buck Bunny (2008) — untagged audio tracks ─────────────────
log "Building: Big Buck Bunny (2008) — untagged tracks"
BBB_DIR="$FIXTURE_DIR/movies/Big Buck Bunny (2008)"
mkdir -p "$BBB_DIR"
TMP=$(mktemp -d)
make_video          "$TMP/vid.y4m"
make_audio_track ""  44100 "$TMP/a1.wav"   # no language tag
make_audio_track ""  48000 "$TMP/a2.wav"
ffmpeg -y \
  -i "$TMP/vid.y4m" -i "$TMP/a1.wav" -i "$TMP/a2.wav" \
  -map 0:v -map 1:a -map 2:a \
  -disposition:a:0 default \
  -c copy "$BBB_DIR/Big Buck Bunny (2008).mkv" -loglevel error
rm -rf "$TMP"
log "  → $BBB_DIR/Big Buck Bunny (2008).mkv (untagged tracks → triage)"

# ─── fixture 3: Tears of Steel TV series — uniform eng audio ──────────────────
log "Building: Tears of Steel (TV series) — uniform eng, 3 episodes"
TOS_DIR="$FIXTURE_DIR/tv/Tears of Steel"
mkdir -p "$TOS_DIR/Season 01"
TMP=$(mktemp -d)
make_video         "$TMP/vid.y4m"
make_audio_track eng 44100 "$TMP/eng.wav"
for ep in 01 02 03; do
  OUT="$TOS_DIR/Season 01/Tears of Steel S01E${ep}.mkv"
  ffmpeg -y \
    -i "$TMP/vid.y4m" -i "$TMP/eng.wav" \
    -map 0:v -map 1:a \
    -metadata:s:a:0 language=eng \
    -disposition:a:0 default \
    -c copy "$OUT" -loglevel error
  log "  → $OUT"
done
rm -rf "$TMP"

# ─── fixture 4: Babel Fish (2000) — mixed-language TV series ──────────────────
log "Building: Babel Fish (2000) — mixed-language TV series (eng/dan/fao)"
BF_DIR="$FIXTURE_DIR/tv/Babel Fish"
mkdir -p "$BF_DIR/Season 01"
TMP=$(mktemp -d)
make_video "$TMP/vid.y4m"

# Each episode has a different audio language — triggers languageMix=true in scanner
make_audio_track eng 44100 "$TMP/eng.wav"
ffmpeg -y \
  -i "$TMP/vid.y4m" -i "$TMP/eng.wav" \
  -map 0:v -map 1:a \
  -metadata:s:a:0 language=eng \
  -disposition:a:0 default \
  -c copy "$BF_DIR/Season 01/Babel Fish S01E01.mkv" -loglevel error

make_audio_track da 48000 "$TMP/dan.wav"
ffmpeg -y \
  -i "$TMP/vid.y4m" -i "$TMP/dan.wav" \
  -map 0:v -map 1:a \
  -metadata:s:a:0 language=dan \
  -disposition:a:0 default \
  -c copy "$BF_DIR/Season 01/Babel Fish S01E02.mkv" -loglevel error

make_audio_track fo 32000 "$TMP/fao.wav"
ffmpeg -y \
  -i "$TMP/vid.y4m" -i "$TMP/fao.wav" \
  -map 0:v -map 1:a \
  -metadata:s:a:0 language=fao \
  -disposition:a:0 default \
  -c copy "$BF_DIR/Season 01/Babel Fish S01E03.mkv" -loglevel error

rm -rf "$TMP"
log "  → $BF_DIR/Season 01/ (S01E01=eng, S01E02=dan, S01E03=fao — mixed language)"

log ""
log "Fixtures ready in $FIXTURE_DIR"
log "  movies/Sintel (2010)/Sintel (2010).mkv        — wrong fra default (must be fixed to eng)"
log "  movies/Big Buck Bunny (2008)/...mkv            — untagged tracks (→ triage)"
log "  tv/Tears of Steel/Season 01/...mkv (3 files)  — uniform eng"
log "  tv/Babel Fish/Season 01/...mkv (3 files)      — mixed eng/dan/fao (languageMix=true)"
log ""
log "Verify with:"
log "  ffprobe -v quiet -print_format json -show_streams '$SINTEL_DIR/Sintel (2010).mkv' | grep -E 'language|disposition'"
