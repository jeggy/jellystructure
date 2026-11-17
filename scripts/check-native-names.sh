#!/usr/bin/env bash
# DEB-4942 — a backticked declaration name that Kotlin/Native rejects never reaches `ci / unit`.
#
# Kotlin/Native's FirNativeIdentifierChecker refuses these 24 characters in a backticked name:
#   . ; , ( ) [ ] { } / < > : \ $ & ~ * ? # | § % @
# (read from kotlin-native-prebuilt 2.3.21, our `kotlin` version; the apostrophe is NOT in the set). JVM and Android
# accept them, so a seat that ran only desktop/Android tests locally saw nothing and `ci / unit` went red at
# compileTestKotlinLinuxX64 about 20 minutes after the push — five times between 2026-11-03 and 2026-11-17.
#
#   check-native-names.sh              scans every source set Kotlin/Native compiles
#   check-native-names.sh --selftest   plants names on canned input and expects the right ones to fail
set -euo pipefail
cd "$(dirname "$0")/.."

# Only source sets compiled for linuxX64/linuxArm64: ravilo-ui and ravilo-castv2 have no native target, so a comma
# in their test names is fine and is deliberately not scanned. The *Main sets are included: the check is on every name.
SETS=(
  src/commonMain src/linuxX64Main src/linuxX64Test
  shared/src/commonMain shared/src/commonTest shared/src/linuxX64Main shared/src/linuxX64Test
  ravilo-i18n/src/commonMain ravilo-i18n/src/commonTest
  web-static-server/src
)

# A declaration keyword followed by a backticked name holding a rejected character. Matching the declaration keeps
# `a.b` in KDoc from being flagged. (*UTF) makes § one character, not two bytes that also occur inside é, ç, ….
PATTERN='(*UTF)\b(fun|val|var|class|object|interface)\s+`[^`]*[.;,()\[\]{}/<>:\\$&~*?#|§%@][^`]*`'

report() {  # stdin: grep -nH output; prints file:line: name — reason, returns 1 on any hit
  local bad=0 file line text name ch
  while IFS=: read -r file line text; do
    [[ -z $file ]] && continue
    name=$(grep -oP '(*UTF)\b(fun|val|var|class|object|interface)\s+`\K[^`]*(?=`)' <<<"$text" | head -n1)
    ch=$(grep -oP '(*UTF)[.;,()\[\]{}/<>:\\$&~*?#|§%@]' <<<"$name" | head -n1)
    echo "$file:$line: $name — Kotlin/Native rejects '$ch'"
    bad=1
  done
  return $bad
}

if [[ ${1:-} == --selftest ]]; then
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' EXIT
  cases=(
    'bad|@Test fun `a stall is excused, then resumes`() {}'
    'bad|@Test fun `a stall is excused (restart scenario)`() {}'
    'bad|class `Foo.Bar` {}'
    'ok|@Test fun `a film'"'"'s commands`() {}'
    'ok|@Test fun `français ça marche é ç`() {}'
    'ok|/** Calls `foo.bar` and `a, b` here. */'
    'ok|val plain = listOf(1, 2)'
  )
  for c in "${cases[@]}"; do
    want=${c%%|*}; src=${c#*|}
    printf '%s\n' "$src" > "$fixture/T.kt"
    if grep -nHP "$PATTERN" "$fixture/T.kt" >/dev/null; then got=bad; else got=ok; fi
    if [[ $got != "$want" ]]; then
      echo "native-names guard: expected $want, got $got, for: $src" >&2
      exit 1
    fi
  done
  echo 'native-names guard rejects , ( ) . in a backticked name and accepts an apostrophe, accents, KDoc and plain code'
  exit 0
fi

hits=$(git ls-files -co --exclude-standard -z -- "${SETS[@]/%//**/*.kt}" "${SETS[@]/%//*.kt}" \
  | xargs -0 -r grep -nHP "$PATTERN" || true)
if [[ -n $hits ]]; then
  echo "A declaration name Kotlin/Native rejects (compileTestKotlinLinuxX64 fails on it, JVM and Android do not; DEB-4942):"
  report <<<"$hits" || true
  echo "Rename it without any of  . ; , ( ) [ ] { } / < > : \\ \$ & ~ * ? # | § % @  (an apostrophe is fine)."
  exit 1
fi
echo "OK — no backticked declaration name holds a character Kotlin/Native rejects."
