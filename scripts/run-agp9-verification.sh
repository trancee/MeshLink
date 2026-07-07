#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

./gradlew --console=plain \
  checkAgp9Invariants \
  :meshlink:build \
  :meshlink-reference:localCheck \
  :meshlink-benchmark:check \
  verifyJvmSmokeBenchmarks \
  :meshlink-proof:android:check \
  verifyDocs
