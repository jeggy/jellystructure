#!/usr/bin/env bash
# DEB-4106 FR-1, FR-2, FR-4 — run a command; if it fails with a TRANSIENT upstream error (HTTP 5xx, a connect or
# read timeout, a reset connection), pause and run it again, up to a bound. Any other failure (a compile error, a
# failing test, a broken build script) exits on the first attempt, so a real red is never retried.
#
#   retry-transient.sh -- <command> [args...]
#
# RETRY_PAUSES   seconds to sleep before each retry, space separated; the attempts are one more than the number
#                of pauses. Default "30 90": three attempts over two minutes.
# Every retry prints one "retry-transient:" line naming the attempt, the pause and the error it is riding out.
set -uo pipefail

[ "${1:-}" = "--" ] && shift
[ "$#" -gt 0 ] || { echo "usage: retry-transient.sh -- <command> [args...]" >&2; exit 2; }

read -r -a pauses <<<"${RETRY_PAUSES:-30 90}"
attempts=$(( ${#pauses[@]} + 1 ))
log=$(mktemp)
trap 'rm -f "$log"' EXIT

transient='status code 5[0-9][0-9]|Request failed "5[0-9][0-9]|(^|[^0-9])5[0-9][0-9] (Internal Server Error|Bad Gateway|Service Unavailable|Gateway Time-?out|Gateway Timeout)|Read timed out|Connect timed out|connect timed out|Connection reset|Connection timed out|Remote host terminated the handshake|Premature end of Content-Length|Unexpected end of file from server|ETIMEDOUT|ECONNRESET|EAI_AGAIN|Temporary failure in name resolution|socket hang up'

n=1
while :; do
  "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  [ "$rc" -eq 0 ] && exit 0
  if ! grep -qE "$transient" "$log"; then
    exit "$rc"
  fi
  if [ "$n" -ge "$attempts" ]; then
    echo "retry-transient: attempt $n/$attempts failed and no retries are left; the upstream error above is the last one seen" >&2
    exit "$rc"
  fi
  pause=${pauses[$((n - 1))]}
  err=$(grep -E "$transient" "$log" | tail -n 1 | cut -c1-200)
  echo "retry-transient: attempt $n/$attempts failed on a transient upstream error (${err}); retrying in ${pause}s" >&2
  sleep "$pause"
  n=$((n + 1))
done
