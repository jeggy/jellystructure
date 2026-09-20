#!/usr/bin/env bash
# Phase 240 (FR-240-4) — the model-field guard that replaces the OpenAPI document.
#
# `GET /api-docs/openapi.json` returned **500** on Jellyfin 12.1.0, so the reference this project
# would have used to check its own DTOs against a new server is gone. This script is the replacement,
# and it is better than the document was: it checks what the server *actually sends*, not what it
# claims to.
#
# ## The failure it exists to catch, and why it is silent
#
# A field the server **stops sending** takes its Kotlin default (or null) with no error anywhere —
# that is plain kotlinx.serialization behaviour and has nothing to do with `ignoreUnknownKeys`. A
# field with NO default throws loudly and gets noticed within minutes.
#
# `ignoreUnknownKeys = true` (OutboundHttp.kt) is what hides a **rename**: the server's new key is
# ignored as unknown (without the flag it would throw), while the old key is simply absent, so the
# default applies. Both halves are quiet, so a rename is invisible in a way an absence is not.
#
# Phase 246 found exactly this shape live: `TranscodingTempPath` read "/transcode" before the upgrade
# and "/cache/transcodes" after it, and printing the unset value as "(default)" is what hid the move.
#
# So this script reports **default-or-nullable** fields the server did not send just as loudly as
# required ones — the quiet bucket is the dangerous bucket.
#
# ## Usage
#
#   JELLYFIN_LIVE_TEST_URL=... JELLYFIN_LIVE_TEST_TOKEN=... JELLYFIN_LIVE_TEST_USER_ID=... \
#     ./scripts/check-jellyfin-models.sh
#
# Opt-in like the live route guard: with no env vars it prints what it WOULD check and exits 0, so it
# can sit in CI unconditionally. Point it at a disposable server (scripts/jellyfin-test-server.sh),
# never the household's.
set -euo pipefail
cd "$(dirname "$0")/.."

MODELS="src/linuxX64Main/kotlin/dev/jellystructure/auth/Models.kt"
[ -f "$MODELS" ] || { echo "MISSING: $MODELS"; exit 1; }

BASE="${JELLYFIN_LIVE_TEST_URL:-}"
TOKEN="${JELLYFIN_LIVE_TEST_TOKEN:-}"
USERID="${JELLYFIN_LIVE_TEST_USER_ID:-}"
BASE="${BASE%/}"

# model class -> a live endpoint whose payload it parses, and a jq-ish path to the object to compare.
#
# **Each URL must request the same `Fields` the product requests**, or this check is noise: Jellyfin
# omits most optional properties unless asked for them, so probing with a bare `/Items` reports nine
# "missing" fields that are simply not requested. Keep these in step with `JellyfinClient`'s own query
# strings — that coupling is the point, not an inconvenience.
#
# "@ITEM@" is substituted with a real item id, "@USER@" with the user id.
read -r -d '' ENDPOINTS <<'EOF' || true
JellyfinUser|/Users|[0]
JellyfinLibrary|/Library/VirtualFolders|[0]
JellyfinSystemInfoAuth|/System/Info|.
JellyfinItem|/Items?Recursive=true&IncludeItemTypes=Movie,Series,MusicVideo&Limit=1&Fields=Path,ProviderIds,ProductionYear,Tags,DateCreated,SortName,SeriesId|Items[0]
JellyfinItem@locks|/Items/@ITEM@?userId=@USER@|.
JellyfinItemsResponse|/Items?Recursive=true&IncludeItemTypes=Movie,Series&Limit=1|.
JellyfinItemDetail|/Items/@ITEM@?userId=@USER@&Fields=UserData,RunTimeTicks,MediaStreams|.
EOF

# Fields Jellyfin genuinely omits when there is nothing to report, even though the product asks. Each
# needs a reason, because an unexplained entry here is how a real rename gets waved through.
#
# The point of listing them is that they are the ONLY excused absences: everything else absent is
# either a rename or a capability that moved, and both deserve to fail this check.
read -r -d '' CONDITIONAL_FIELDS <<'EOF' || true
JellyfinUser.PrimaryImageTag|only present when that user actually has a profile photo (phase 187's own doc says so)
JellyfinItem.ProviderIds|absent on an item no provider has matched — a freshly scanned file with no TMDB hit
JellyfinItem.SeriesId|set on Episode-type items only (phase 114); a Movie legitimately has none
JellyfinItem.LockData|list shape only: phase 251 measured that 12.1 sends it on GET /Items/{id} and NOT on /Items?Ids=. The JellyfinItem@locks row below checks the shape that carries it, so this is covered, not excused away.
JellyfinItem.LockedFields|as LockData — checked on the detail shape by the JellyfinItem@locks row
JellyfinItem.DateLastSaved|phase 251 FR-251-3: 12.1 sends it on NO shape at all (measured with and without an explicit Fields=DateLastSaved). Nothing in the product treats a null here as a fact any more; if a future server starts sending it again the value flows through unchanged.
JellyfinItem@locks.Path|the detail shape is probed for the LOCK fields only; everything else is covered by the list row above
JellyfinItem@locks.ProviderIds|as above
JellyfinItem@locks.SeriesId|as above
JellyfinItem@locks.DateLastSaved|as above, and see FR-251-3
JellyfinItem@locks.Tags|as above
JellyfinItem@locks.DateCreated|as above
JellyfinItem@locks.SortName|as above
JellyfinItem@locks.ProductionYear|as above
EOF

python3 - "$MODELS" <<'PY'
import re, sys
# FR-240-4's parse half runs with or without a server: it is also the inventory the report is built
# from, and it catches a malformed model file on its own.
src = open(sys.argv[1]).read()
blocks = re.findall(r'@Serializable\s*(?:\n\s*@\w+[^\n]*)*\s*\ndata class (\w+)\s*\((.*?)\n\)', src, re.S)
if not blocks:
    sys.exit("check-jellyfin-models: parsed 0 @Serializable data classes — the parser is broken, not the models")
total_fields = 0
for name, body in blocks:
    for m in re.finditer(r'@SerialName\("([^"]+)"\)\s*(?:val|var)\s+(\w+)\s*:\s*([^,\n=]+)(=\s*[^,\n]+)?', body):
        total_fields += 1
print(f"parsed {len(blocks)} @Serializable classes, {total_fields} @SerialName fields")
PY

if [ -z "$BASE" ] || [ -z "$TOKEN" ]; then
  echo "No JELLYFIN_LIVE_TEST_URL/_TOKEN set — parse-only run (this is the CI default)."
  echo "Set all three env vars against a DISPOSABLE Jellyfin to compare against a live payload."
  exit 0
fi

AUTH="Authorization: MediaBrowser Client=\"jellystructure-model-guard\", Device=\"guard\", DeviceId=\"jellystructure-model-guard\", Version=\"1.0\", Token=\"$TOKEN\""

ITEM=$(curl -s -m 20 -H "$AUTH" "$BASE/Items?Recursive=true&IncludeItemTypes=Movie,Series&Limit=1" \
  | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin); print(d.get("Items",[{}])[0].get("Id",""))
except Exception: print("")' 2>/dev/null || true)

# The comparison program. Kept in a variable rather than a heredoc inside the loop, because the loop
# already feeds the payload in on stdin and a command can only have one.
COMPARE=$(cat <<'PYEOF'
import json, os, re, sys
label = os.environ["MODEL"]; pick = os.environ["PICK"]
# "Model@variant" probes the SAME Kotlin class through a second endpoint shape. Phase 251 needs it:
# on 12.1 the lock fields live on the detail shape and everything else on the list shape, so one
# endpoint can no longer stand for the whole model.
model = label.split("@", 1)[0]
src = open(os.environ["MODELS"]).read()
m = re.search(r'@Serializable\s*(?:\n\s*@\w+[^\n]*)*\s*\ndata class ' + re.escape(model) + r'\s*\((.*?)\n\)', src, re.S)
if not m:
    print(f"MODEL-GONE {label}"); sys.exit(0)
fields = []
for f in re.finditer(r'@SerialName\("([^"]+)"\)\s*(?:val|var)\s+(\w+)\s*:\s*([^,\n=]+?)\s*(=\s*[^,\n]+)?\s*,', m.group(1)):
    wire, prop, ktype, default = f.group(1), f.group(2), f.group(3).strip(), f.group(4)
    # The two buckets FR-240-4 cares about, discriminated by exactly what this parse already knows.
    quiet = bool(default) or ktype.endswith("?")
    fields.append((wire, prop, quiet))
try:
    payload = json.load(sys.stdin)
except Exception as e:
    print(f"UNPARSEABLE {label}: {e}"); sys.exit(0)
obj = payload
if pick != ".":
    mm = re.match(r'^(\w*)\[(\d+)\]$', pick)
    if mm:
        key, idx = mm.group(1), int(mm.group(2))
        if key: obj = obj.get(key, [])
        if not isinstance(obj, list) or len(obj) <= idx:
            print(f"EMPTY {label}: the server returned no rows to compare against"); sys.exit(0)
        obj = obj[idx]
if not isinstance(obj, dict):
    print(f"NOT-AN-OBJECT {label}"); sys.exit(0)
excused = {}
for raw in os.environ.get("CONDITIONAL_FIELDS", "").splitlines():
    if "|" in raw:
        key, why = raw.split("|", 1)
        excused[key.strip()] = why.strip()
for wire, prop, quiet in fields:
    if wire in obj:
        continue
    why = excused.get(f"{label}.{wire}")
    if why:
        print(f"EXCUSED {label}.{prop}  ({why})")
        continue
    kind = "SILENT " if quiet else "THROWS "
    print(f"{kind}{label}.{prop}  (@SerialName \"{wire}\" not sent by this server)")
PYEOF
)

fail=0
while IFS='|' read -r model path pick; do
  [ -z "${model:-}" ] && continue
  case "$path" in
    *@ITEM@*)
      if [ -z "$ITEM" ]; then
        echo "SKIP  $model — the server's library is empty, so no item-shaped payload exists."
        echo "      A seeded server is the point (phase 240 OQ1): an empty one tests a third of this."
        continue
      fi
      path=${path//@ITEM@/$ITEM} ;;
  esac
  path=${path//@USER@/$USERID}

  body=$(curl -s -m 25 -H "$AUTH" "$BASE$path" || true)
  out=$(MODEL="$model" PICK="$pick" MODELS="$MODELS" CONDITIONAL_FIELDS="$CONDITIONAL_FIELDS" python3 -c "$COMPARE" <<<"$body") || true
  if [ -n "$out" ]; then
    echo "$out" | while IFS= read -r line; do echo "  $line"; done
    # A SILENT miss is reported as loudly as a throwing one — that is the whole point of FR-240-4.
    case "$out" in *SILENT*|*THROWS*|*MODEL-GONE*) fail=1 ;; esac
  else
    echo "OK    $model — every declared field present in the live payload."
  fi
done <<< "$ENDPOINTS"

if [ "$fail" -ne 0 ]; then
  echo
  echo "A field this product declares is not in the server's payload."
  echo "  THROWS … the call fails loudly. Annoying, but you will find out within minutes."
  echo "  SILENT … the property takes its default/null forever, with no error anywhere. This is the"
  echo "           dangerous one: it is how a check can go blind without a single log line."
  echo "See specs/requirements/phase-240-the-live-route-guard-tests-what-actually-breaks.md"
  exit 1
fi
echo "OK: every model field this product declares is present in the live payloads checked."
