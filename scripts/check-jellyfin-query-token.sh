#!/usr/bin/env bash
# Phase 239 — a Jellyfin credential belongs in a header. The one exception is a URL handed to a
# PLAYER (a Ravilo client's ExoPlayer/AVPlay, the admin page's own <video> element), which cannot
# attach one — and that exception has exactly one implementation, `withJellyfinToken`.
#
# Two things are fenced here.
#
# 1. `api_key=` must not appear in a Jellyfin URL anywhere. Measured live against the household server
#    (12.1.0, 2026-09-20) on /Users, which genuinely enforces: `?api_key=<valid>` -> 401, while
#    `?apikey=<valid>` -> 200. The old spelling is not merely unfashionable, it does not authenticate.
#    TMDB's own `api_key` is a different service and a correct spelling for it, so tmdb/ is exempt.
#
# 2. Nothing may hand-write the query parameter. FR-239-3: one spelling, one place, separator included.
#    Five call sites each writing their own `?` or `&` is the half of the problem that actually broke.
#
# 3. R271 — no Ravilo client composes a Jellyfin URL at all. `ravilo-ui`'s player used to template one
#    from the ticket when `hls_url` was null (dead code: both server producers always set it), and that
#    line was the only consumer of the raw `StreamTicket.access_token`, which is now gone from the DTO.
#    R264's `ravilo-screen` was built clean and must stay that way.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

# Test sources are exempt: the helper's own unit test necessarily contains the strings this fence
# looks for (that is what it is asserting about). Only production sources are fenced.
is_test_source() {
  case "$1" in
    */linuxX64Test/*|*/commonTest/*|*/androidUnitTest/*|*Test.kt) return 0 ;;
    *) return 1 ;;
  esac
}

# ── 1. No `api_key=` in a Jellyfin URL ──────────────────────────────────────────
# Comments may discuss it (several record the measurement), so only real code lines count: the
# literal inside a string, with no `//` before it on that line.
while IFS= read -r hit; do
  file=${hit%%:*}
  is_test_source "$file" && continue
  case "$file" in
    */tmdb/*|*Tmdb*|*TMDB*) continue ;;   # a different service, correct spelling for it
  esac
  line=${hit#*:}; line=${line#*:}
  # Strip a trailing line comment before looking, and skip KDoc/block-comment lines.
  code=${line%%//*}
  trimmed=$(printf '%s' "$line" | sed 's/^[[:space:]]*//')
  case "$trimmed" in \**|/\**) continue ;; esac
  if printf '%s' "$code" | grep -q 'api_key='; then
    echo "$hit"
    echo "    ^ Jellyfin does not honour api_key on 12.1 (measured 401). Use a header, or withJellyfinToken()."
    fail=1
  fi
done < <(grep -rn 'api_key=' --include='*.kt' src/ ravilo-ui/src ravilo-screen/src ravilo-cast/src ravilo-receiver-core/src ravilo-phone/src shared/src 2>/dev/null || true)

# ── 2. Only the shared helper writes the query parameter ────────────────────────
ALLOWED_TOKEN_QUERY_FILE="src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt"
while IFS= read -r hit; do
  file=${hit%%:*}
  is_test_source "$file" && continue
  [ "$file" = "$ALLOWED_TOKEN_QUERY_FILE" ] && continue
  echo "$hit"
  echo "    ^ FR-239-3: only withJellyfinToken() in $ALLOWED_TOKEN_QUERY_FILE writes this parameter."
  fail=1
done < <(grep -rniE '[?&]apikey=' --include='*.kt' src/ ravilo-ui/src ravilo-screen/src ravilo-cast/src ravilo-receiver-core/src ravilo-phone/src shared/src 2>/dev/null || true)

if [ ! -f "$ALLOWED_TOKEN_QUERY_FILE" ] || ! grep -q 'fun withJellyfinToken' "$ALLOWED_TOKEN_QUERY_FILE"; then
  echo "MISSING: withJellyfinToken() — phase 239's single spelling site is gone, so this fence proves nothing."
  fail=1
fi

# ── 3. No client composes a Jellyfin path (R271 FR-R271-2/-4) ───────────────────
while IFS= read -r hit; do
  file=${hit%%:*}
  is_test_source "$file" && continue
  line=${hit#*:}; line=${line#*:}
  trimmed=$(printf '%s' "$line" | sed 's/^[[:space:]]*//')
  case "$trimmed" in \**|//*|/\**) continue ;; esac   # comments may discuss the deleted code
  echo "$hit"
  echo "    ^ R271: a client SELECTS a URL the ticket carries, it never composes one."
  fail=1
done < <(grep -rn '/Videos/' --include='*.kt' ravilo-ui/src ravilo-screen/src ravilo-cast/src ravilo-receiver-core/src ravilo-phone/src 2>/dev/null || true)

# R271 — `StreamTicket.access_token` still EXISTS (removing it broke a pre-v1.31 client, which cannot
# deserialize a ticket without it — the stue TV, 2026-09-20). What must never come back is a
# credential inside it, so the fence checks the VALUE, not the field: every StreamTicket construction
# site must pass the empty string.
#
# Scoped to PlaybackService, the only place a StreamTicket is built. Live TV's ticket has its own
# `access_token` on its own model (LiveTvModels.kt) and legitimately carries a token — R271's spec
# carves it out by name, and widening this to every `accessToken =` in the tree flagged it at once.
TICKET_FILE="src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt"
while IFS= read -r hit; do
  line=${hit#*:}
  if ! grep -q 'accessToken = ""' <<<"$line"; then
    echo "$hit"
    printf '    ^ R271: StreamTicket.accessToken must always be the empty string, never a credential.\n'
    fail=1
  fi
done < <(grep -n 'accessToken = ' "$TICKET_FILE" 2>/dev/null || true)

if ! grep -q 'val accessToken: String,' shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt 2>/dev/null; then
  echo "StreamTicket.accessToken is gone, or has gained a default."
  echo "    ^ It must stay present AND non-defaulted: encodeDefaults = false omits a property equal to"
  echo "      its default, which is the same wire breakage as deleting it (stue TV, v1.27, 2026-09-20)."
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "See specs/requirements/phase-239-no-jellyfin-token-in-a-query-string.md"
  echo "and specs/ravilo/requirements/phase-R271-no-client-built-jellyfin-urls.md"
  exit 1
fi
echo "OK: no Jellyfin api_key= in src/, and only withJellyfinToken() spells the query parameter."
