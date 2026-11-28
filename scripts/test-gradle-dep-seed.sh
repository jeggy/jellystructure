#!/usr/bin/env bash
# DEB-5992 FR-7 and FR-8 — the image build neither fetches test-only configurations nor asks a repository for what a
# carried dependency set already holds. The Gradle half is measured by the acceptance builds in the spec; this holds what
# a push can break without anyone noticing: the seed script's three cases and the wiring that hands the set to every build.
set -uo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail=0
check() { if ! eval "$2"; then echo "FAIL: $1"; fail=1; fi; }

# --- scripts/seed-gradle-cache.sh --------------------------------------------------------------------------------
mkseed() { mkdir -p "$1/modules-2/files-2.1/g/a/1/h" "$1/modules-2/metadata-2.107"; echo jar >"$1/modules-2/files-2.1/g/a/1/h/a-1.jar"; }

mkseed "$tmp/seed"
out=$(GRADLE_USER_HOME="$tmp/home1" scripts/seed-gradle-cache.sh "$tmp/seed" 2>&1); rc=$?
check "an empty Gradle home is seeded with the carried set" '[ "$rc" -eq 0 ] && [ -f "$tmp/home1/caches/modules-2/files-2.1/g/a/1/h/a-1.jar" ] && [ -d "$tmp/home1/caches/modules-2/metadata-2.107" ]'

mkdir -p "$tmp/home2/caches/modules-2/files-2.1/x/y/2/h"; echo newer >"$tmp/home2/caches/modules-2/files-2.1/x/y/2/h/y-2.jar"
out=$(GRADLE_USER_HOME="$tmp/home2" scripts/seed-gradle-cache.sh "$tmp/seed" 2>&1); rc=$?
check "a cache that already holds artifacts is left alone" '[ "$rc" -eq 0 ] && [ -f "$tmp/home2/caches/modules-2/files-2.1/x/y/2/h/y-2.jar" ] && [ ! -e "$tmp/home2/caches/modules-2/files-2.1/g" ]'

mkdir -p "$tmp/empty"
out=$(GRADLE_USER_HOME="$tmp/home3" scripts/seed-gradle-cache.sh "$tmp/empty" 2>&1); rc=$?
check "an empty seed is not an error and seeds nothing" '[ "$rc" -eq 0 ] && [ ! -e "$tmp/home3/caches" ] && grep -q "everything is downloaded" <<<"$out"'
out=$(GRADLE_USER_HOME="$tmp/home4" scripts/seed-gradle-cache.sh "$tmp/does-not-exist" 2>&1); rc=$?
check "a missing seed is not an error" '[ "$rc" -eq 0 ]'

# --- FR-7: the init script ----------------------------------------------------------------------------------------
check "the fetch step skips the Robolectric android-all configurations" \
  "grep -q \"it.canBeResolved && !it.name.startsWith('robolectricAndroidAll_')\" scripts/fetch-dependencies.init.gradle"

# --- FR-8: every image build is handed the set ---------------------------------------------------------------------
for f in Dockerfile ravilo-web/Dockerfile ravilo-screen/Dockerfile; do
  check "$f has an empty default gradle-dep-seed stage, so a plain docker build still works" "grep -qx 'FROM scratch AS gradle-dep-seed' $f"
  check "$f seeds the Gradle home before its fetch step" \
    "grep -q 'seed-gradle-cache.sh /gradle-dep-seed && ./scripts/fetch-dependencies.sh' $f && grep -q 'type=bind,from=gradle-dep-seed,target=/gradle-dep-seed' $f"
done
check "the app Dockerfile has the export target" "grep -qx 'FROM scratch AS gradle-dep-export' Dockerfile"
for svc in app ravilo-web ravilo-screen; do
  check "docker-compose.test.yml gives $svc the carried set" \
    "awk -v s='  $svc:' '\$0==s{f=1;next} f&&/^  [a-z]/{f=0} f&&/gradle-dep-seed: \\\$\\{GRADLE_DEP_SEED_DIR/{ok=1} END{exit !ok}' docker-compose.test.yml"
done
check "publish.yml hands the restored set to the image build" "grep -q 'gradle-dep-seed=/tmp/gradle-dep-seed' .github/workflows/publish.yml && grep -q 'actions/cache/restore@v4' .github/workflows/publish.yml"
check "publish.yml never writes the set" "! grep -q 'actions/cache/save' .github/workflows/publish.yml"
check "ci.yml saves the set only on main" "grep -q \"refs/heads/main\" .github/workflows/ci.yml && grep -q 'export-gradle-dep-seed.sh' .github/workflows/ci.yml"
check "a pull request restores the set and never writes it" "awk '/export-gradle-dep-seed.sh/{print prev} {prev=\$0}' .github/workflows/ci.yml | grep -q 'refs/heads/main'"

exit $fail
