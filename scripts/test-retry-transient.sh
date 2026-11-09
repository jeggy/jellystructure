#!/usr/bin/env bash
# DEB-4106 acceptance 1 and FR-2 — retry-transient.sh rides out a transient error, gives up after its bound, and
# never retries a failure that is not a transient upstream one.
set -uo pipefail
cd "$(dirname "$0")/.."
export RETRY_PAUSES="0 0"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail=0
check() { if ! eval "$2"; then echo "FAIL: $1"; fail=1; fi; }

# Fails twice with a 5xx-shaped error, then succeeds.
cat >"$tmp/flaky.sh" <<'S'
#!/usr/bin/env bash
n=$(cat "$1" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" >"$1"
[ "$n" -ge 3 ] && { echo ok; exit 0; }
echo "ResponseError: Request failed \"504 Gateway Timeout\""; exit 1
S
chmod +x "$tmp/flaky.sh"
out=$(scripts/retry-transient.sh -- "$tmp/flaky.sh" "$tmp/count" 2>&1); rc=$?
check "succeeds on attempt 3" '[ "$rc" -eq 0 ] && [ "$(cat "$tmp/count")" = 3 ]'
check "prints one line per retry" '[ "$(grep -c "^retry-transient: attempt" <<<"$out")" = 2 ]'

# Always fails with a 5xx: exits non-zero after attempt 3 and shows the last error.
out=$(scripts/retry-transient.sh -- bash -c 'echo "Could not GET x. Received status code 503 from server"; exit 7' 2>&1); rc=$?
check "gives up with the command's exit code" '[ "$rc" -eq 7 ]'
check "3 attempts: 2 retry lines + the final line" '[ "$(grep -c "^retry-transient:" <<<"$out")" = 3 ]'
check "the upstream error is still in the log" 'grep -q "status code 503" <<<"$out"'

# A compile error is not transient: one attempt, no retry line.
out=$(scripts/retry-transient.sh -- bash -c 'echo "e: file.kt:3:1 Unresolved reference: foo"; exit 1' 2>&1); rc=$?
check "a real failure fails at once" '[ "$rc" -eq 1 ] && ! grep -q "^retry-transient:" <<<"$out"'

exit $fail
