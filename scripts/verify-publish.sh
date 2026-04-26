#!/usr/bin/env bash
# verify-publish.sh — Verify the full publishToMavenLocal artifact tree for
# ch.trancee:meshlink:0.1.0 and ch.trancee:meshlink-testing:0.1.0.
#
# Usage: bash scripts/verify-publish.sh
# Exit 0 on success; non-zero if any artifact is missing or POM metadata fails.

set -euo pipefail

REPO="$HOME/.m2/repository/ch/trancee"
VERSION="0.1.0"
PASS=0
FAIL=0

# ─── Helpers ────────────────────────────────────────────────────────────────

check_file() {
  local path="$1"
  if [[ -f "$path" ]]; then
    echo "  ✅  $(basename "$path")"
    PASS=$((PASS + 1))
  else
    echo "  ❌  MISSING: $path" >&2
    FAIL=$((FAIL + 1))
  fi
}

check_pom_contains() {
  local pom="$1"
  local text="$2"
  if grep -q "$text" "$pom" 2>/dev/null; then
    echo "  ✅  POM contains '$text'"
    PASS=$((PASS + 1))
  else
    echo "  ❌  POM missing '$text': $pom" >&2
    FAIL=$((FAIL + 1))
  fi
}

# ─── Step 1: Clean local Maven cache ────────────────────────────────────────

echo "==> Cleaning local Maven cache for ch.trancee..."
rm -rf "$REPO/meshlink"
rm -rf "$REPO/meshlink-jvm"
rm -rf "$REPO/meshlink-android"
rm -rf "$REPO/meshlink-testing"
rm -rf "$REPO/meshlink-testing-jvm"
echo "    Cache cleaned."

# ─── Step 2: Publish ────────────────────────────────────────────────────────

echo ""
echo "==> Running publishToMavenLocal..."
./gradlew publishToMavenLocal --quiet
echo "    Build complete."

# ─── Step 3: Assert meshlink root publication ────────────────────────────────

echo ""
echo "==> Checking meshlink root publication (KMP metadata):"
ML="$REPO/meshlink/$VERSION"
check_file "$ML/meshlink-${VERSION}.module"
check_file "$ML/meshlink-${VERSION}.pom"
check_file "$ML/meshlink-${VERSION}.jar"
check_file "$ML/meshlink-${VERSION}-sources.jar"
check_file "$ML/meshlink-${VERSION}-javadoc.jar"

# ─── Step 4: Assert meshlink-jvm target publication ─────────────────────────

echo ""
echo "==> Checking meshlink-jvm target publication:"
MLJVM="$REPO/meshlink-jvm/$VERSION"
check_file "$MLJVM/meshlink-jvm-${VERSION}.module"
check_file "$MLJVM/meshlink-jvm-${VERSION}.pom"
check_file "$MLJVM/meshlink-jvm-${VERSION}.jar"
check_file "$MLJVM/meshlink-jvm-${VERSION}-sources.jar"
check_file "$MLJVM/meshlink-jvm-${VERSION}-javadoc.jar"

# ─── Step 5: Assert meshlink-android target publication ──────────────────────

echo ""
echo "==> Checking meshlink-android target publication:"
MLANT="$REPO/meshlink-android/$VERSION"
check_file "$MLANT/meshlink-android-${VERSION}.module"
check_file "$MLANT/meshlink-android-${VERSION}.pom"
check_file "$MLANT/meshlink-android-${VERSION}.aar"
check_file "$MLANT/meshlink-android-${VERSION}-sources.jar"
check_file "$MLANT/meshlink-android-${VERSION}-javadoc.jar"

# ─── Step 6: Assert meshlink-testing root publication ────────────────────────

echo ""
echo "==> Checking meshlink-testing root publication (KMP metadata):"
MLT="$REPO/meshlink-testing/$VERSION"
check_file "$MLT/meshlink-testing-${VERSION}.module"
check_file "$MLT/meshlink-testing-${VERSION}.pom"
check_file "$MLT/meshlink-testing-${VERSION}.jar"
check_file "$MLT/meshlink-testing-${VERSION}-sources.jar"
check_file "$MLT/meshlink-testing-${VERSION}-javadoc.jar"

# ─── Step 7: Assert meshlink-testing-jvm target publication ──────────────────

echo ""
echo "==> Checking meshlink-testing-jvm target publication:"
MLTJVM="$REPO/meshlink-testing-jvm/$VERSION"
check_file "$MLTJVM/meshlink-testing-jvm-${VERSION}.module"
check_file "$MLTJVM/meshlink-testing-jvm-${VERSION}.pom"
check_file "$MLTJVM/meshlink-testing-jvm-${VERSION}.jar"
check_file "$MLTJVM/meshlink-testing-jvm-${VERSION}-sources.jar"
check_file "$MLTJVM/meshlink-testing-jvm-${VERSION}-javadoc.jar"

# ─── Step 8: Validate meshlink POM metadata ──────────────────────────────────

echo ""
echo "==> Validating meshlink POM metadata:"
MESHLINK_POM="$ML/meshlink-${VERSION}.pom"
check_pom_contains "$MESHLINK_POM" "ch.trancee"
check_pom_contains "$MESHLINK_POM" "MeshLink"
check_pom_contains "$MESHLINK_POM" "Apache License"
check_pom_contains "$MESHLINK_POM" "trancee/meshlink"

# ─── Step 9: Validate meshlink-testing POM metadata ──────────────────────────

echo ""
echo "==> Validating meshlink-testing POM metadata:"
TESTING_POM="$MLT/meshlink-testing-${VERSION}.pom"
check_pom_contains "$TESTING_POM" "ch.trancee"
check_pom_contains "$TESTING_POM" "MeshLink Testing"
check_pom_contains "$TESTING_POM" "Apache License"
check_pom_contains "$TESTING_POM" "trancee/meshlink"

# ─── Summary ─────────────────────────────────────────────────────────────────

echo ""
echo "==> Results: $PASS passed, $FAIL failed"

if [[ $FAIL -gt 0 ]]; then
  echo "FAILED: $FAIL check(s) did not pass." >&2
  exit 1
fi

echo "SUCCESS: All artifact checks passed."
exit 0
