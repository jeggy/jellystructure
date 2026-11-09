#!/usr/bin/env bash
# DEB-4106 FR-3 — fetch everything the image build's compile steps would otherwise download, compiling nothing:
# the Gradle distribution (the wrapper), every resolvable configuration of every project (Maven/Ivy artifacts, the
# Kotlin compiler jars, com.yarnpkg:yarn), the Node and yarn setup and the npm install. Retried on a transient
# upstream error by retry-transient.sh and never hidden: a failure here reads as a registry outage.
#
#   fetch-dependencies.sh [extra gradle args]
set -euo pipefail
cd "$(dirname "$0")/.."

exec scripts/retry-transient.sh -- ./gradlew --no-daemon \
  --init-script scripts/fetch-dependencies.init.gradle \
  fetchDependencies \
  kotlinNodeJsSetup kotlinWasmNodeJsSetup kotlinWasmYarnSetup kotlinNpmInstall kotlinWasmNpmInstall \
  "$@"
