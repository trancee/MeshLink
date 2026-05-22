#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

./gradlew --console=plain \
  checkAgp9Invariants \
  :meshlink:build \
  :meshlink-reference:localCheck \
  :benchmarks:check \
  :meshlink-proof:android:meshlink-proof-android-app:check \
  verifyDocs
