#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

./gradlew \
  --console=plain \
  -Pandroid.experimental.lint.version=8.13.2 \
  :meshlink-reference:localCheck \
  "$@"
