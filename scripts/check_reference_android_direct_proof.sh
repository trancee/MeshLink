#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
exec "$repo_root/meshlink-reference/scripts/tests/run_reference_android_direct_proof_tests.sh" "$@"
