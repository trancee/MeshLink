#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

./gradlew \
  --console=plain \
  -Pandroid.experimental.lint.version=9.2.1 \
  :meshlink-reference:localCheck \
  "$@"
