#!/usr/bin/env bash
# Regenerates the two Matroska fixtures. tracks-after-cluster.mkv is clean.mkv after one
# mkvpropedit flag edit: Tracks outgrows its slot, the slot becomes a Void, and Tracks is
# appended at EOF, reachable only through SeekHead. A linear reader sees no tracks at all.
set -euo pipefail
cd "$(dirname "$0")"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
for l in dan nor swe; do
  printf '1\n00:00:01,000 --> 00:00:04,000\nsubtitle %s\n\n2\n00:00:05,000 --> 00:00:08,000\nsecond line %s\n' $l $l > "$tmp/$l.srt"
done
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=25" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000" \
  -i "$tmp/dan.srt" -i "$tmp/nor.srt" -i "$tmp/swe.srt" \
  -t 10 -map 0:v -map 1:a -map 2:a -map 3 -map 4 -map 5 \
  -c:v libx264 -preset veryfast -crf 30 -g 25 -pix_fmt yuv420p \
  -c:a ac3 -b:a 96k -c:s srt \
  -metadata:s:a:0 language=dan -metadata:s:a:1 language=eng \
  -metadata:s:s:0 language=dan -metadata:s:s:1 language=nor -metadata:s:s:2 language=swe \
  -cues_to_front 1 clean.mkv
cp clean.mkv tracks-after-cluster.mkv
mkvpropedit -q tracks-after-cluster.mkv \
  --edit track:a1 --set flag-default=0 --set language-ietf=da \
  --edit track:a2 --set flag-default=0 --set language-ietf=en \
  --edit track:s1 --set flag-default=0 --set language-ietf=da \
  --edit track:s2 --set flag-default=0 --set language-ietf=nb \
  --edit track:s3 --set flag-default=0 --set language-ietf=sv
linear() { cat "$1" | ffprobe -v quiet -show_entries stream=index -of csv=p=0 -i pipe:0 | wc -l; }
[ "$(linear clean.mkv)" = 6 ] || { echo "clean.mkv: expected 6 streams read linearly" >&2; exit 1; }
[ "$(linear tracks-after-cluster.mkv)" = 0 ] || { echo "tracks-after-cluster.mkv: mkvpropedit did not evict Tracks" >&2; exit 1; }
echo "ok: clean.mkv 6 streams linear; tracks-after-cluster.mkv 0 linear, $(ffprobe -v quiet -show_entries stream=index -of csv=p=0 tracks-after-cluster.mkv | wc -l) seeking"
