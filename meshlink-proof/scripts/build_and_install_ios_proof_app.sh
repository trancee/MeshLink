#!/usr/bin/env bash
# Build, codesign, and freshly (re)install the MeshLink ProofApp on one or more
# attached physical iPhones. Optionally also builds the ProofBenchmarks XCTest
# target (build-for-testing) and can run a specific test against each device
# (test-without-building) -- e.g. BluetoothPermissionGrant, which auto-taps the
# system Bluetooth permission prompt via an XCUITest interruption monitor (see
# meshlink-proof/ios/ProofBenchmarks/BluetoothPermissionGrant.swift).
#
# This script exists because headless/automation shells (no logged-in GUI/Aqua
# session) cannot unlock the login keychain's Apple Development private key --
# `codesign` fails with `errSecInternalComponent` -- see
# docs/how-to/unblock-meshlink-permissions.md ("If iPhone signing fails before
# launch, check the keychain first") and
# docs/how-to/run-reference-app-physical-integration-scenarios.md for the same
# documented limitation. Run this script yourself, interactively, on the
# physical Mac -- not from an automation/agent shell.
#
# Per AGENTS.md's physical-device rule, this script always rebuilds fresh and
# uninstalls any previous install before installing the new one; it never
# reinstalls in place over a stale build.
#
# Usage:
#   scripts/build_and_install_ios_proof_app.sh --team <DEVELOPMENT_TEAM_ID> [--device <udid>]...
#     [--launch] [--build-tests] [--run-test <TARGET/TestClass[/testMethod]>]
#
# With no --device flags, it targets every "available (paired)" device
# reported by `xcrun devicectl list devices`.
#
# Example (build your Apple Development team ID first, e.g. via
# `security find-certificate -c "Apple Development: <you>" -p | openssl x509
# -noout -subject`, the OU= field):
#
#   scripts/build_and_install_ios_proof_app.sh --team 7ZX3WPAP4Y
#
# Example targeting specific devices only:
#
#   scripts/build_and_install_ios_proof_app.sh --team 7ZX3WPAP4Y \
#     --device 6972CDF5-F3EB-5602-B53B-583BDB6D1AD1 \
#     --device 7F5320D7-1D8E-56F5-9D6E-0A6967B25467
#
# Example: also build the XCTest runner (ProofBenchmarks) for physical devices,
# without running any test yet:
#
#   scripts/build_and_install_ios_proof_app.sh --team 7ZX3WPAP4Y --build-tests
#
# Example: build the XCTest runner and run the one-time Bluetooth
# permission-grant test against every target device (only needs to succeed
# once per device -- see BluetoothPermissionGrant.swift's doc comment):
#
#   scripts/build_and_install_ios_proof_app.sh --team 7ZX3WPAP4Y \
#     --run-test ProofBenchmarks/BluetoothPermissionGrant
#
# Note: at the time this flag was added, running any XCTest UI-test runner
# against certain physical device/Xcode pairings in this project's own
# development environment failed with "Root install style is not supported on
# this device" while installing the runner -- a pre-existing Xcode/devicectl
# tooling limitation reproduced even against the project's older, unmodified
# benchmark tests, not something this script can work around. If you hit that
# error, it is an Xcode/device-pairing issue to resolve on your machine (e.g. a
# stale device-support cache), not a bug in this script or the test itself.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project_path="$repo_root/meshlink-proof/ios/ProofApp.xcodeproj"
scheme="ProofApp"
tests_scheme="ProofBenchmarks"
bundle_id="ch.trancee.meshlink.proof.ios"

development_team=""
devices=()
launch_after_install=0
build_tests=0
run_test=""

usage() {
  cat <<'EOF'
Usage: build_and_install_ios_proof_app.sh --team <DEVELOPMENT_TEAM_ID> [--device <udid>]...
         [--launch] [--build-tests] [--run-test <TARGET/TestClass[/testMethod]>]

  --team <id>      Apple Development Team ID used for DEVELOPMENT_TEAM (required)
  --device <udid>  CoreDevice identifier to target (repeatable). Defaults to
                    every "available (paired)" device from
                    `xcrun devicectl list devices` when omitted.
  --launch         Launch the app on each target device after installing it.
  --build-tests    Also build the ProofBenchmarks XCTest target for physical
                    devices (`xcodebuild build-for-testing`), producing the
                    XCTest runner app usable with --run-test or your own
                    `xcodebuild test-without-building` invocation. Implied by
                    --run-test.
  --run-test <id>  Run this test identifier (e.g.
                    ProofBenchmarks/BluetoothPermissionGrant) against every
                    target device via `xcodebuild test-without-building`,
                    after building the test target. Repeatable is not
                    supported; pass one identifier per invocation.
  -h, --help       Show this help text.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --team)
      development_team="${2:?--team requires a value}"
      shift 2
      ;;
    --device)
      devices+=("${2:?--device requires a value}")
      shift 2
      ;;
    --launch)
      launch_after_install=1
      shift
      ;;
    --build-tests)
      build_tests=1
      shift
      ;;
    --run-test)
      run_test="${2:?--run-test requires a value}"
      build_tests=1
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$development_team" ]]; then
  echo "error: --team <DEVELOPMENT_TEAM_ID> is required" >&2
  usage >&2
  exit 1
fi

if [[ ${#devices[@]} -eq 0 ]]; then
  echo "No --device given; discovering attached, paired iPhones via xcrun devicectl..."
  # Columns: Name  Hostname  Identifier  State  Model. Only take rows whose
  # State column starts with "available" (skips "unavailable" entries like a
  # device that was recently unplugged but still cached by devicectl).
  while IFS= read -r udid; do
    devices+=("$udid")
  done < <(
    xcrun devicectl list devices 2>/dev/null \
      | awk 'NR > 2 && $0 ~ /available \(paired\)/ { for (i = 1; i <= NF; i++) if ($i ~ /^[0-9A-Fa-f-]{36}$/) print $i }'
  )
fi

if [[ ${#devices[@]} -eq 0 ]]; then
  echo "error: no attached, paired devices found (and none passed via --device)" >&2
  exit 1
fi

echo "Target devices (${#devices[@]}):"
printf '  %s\n' "${devices[@]}"

echo
echo "==> Building ProofApp fresh for physical iOS (generic/platform=iOS, DEVELOPMENT_TEAM=$development_team)"
# `generic/platform=iOS` produces one arm64 device build reusable across every
# attached physical iPhone; there is no need to build per-device.
xcodebuild \
  -project "$project_path" \
  -scheme "$scheme" \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$repo_root/build/ios-proof-app-derived-data" \
  DEVELOPMENT_TEAM="$development_team" \
  build

app_path=$(find "$repo_root/build/ios-proof-app-derived-data/Build/Products" -maxdepth 2 -type d -name "${scheme}.app" -path "*iphoneos*" | head -n1)
if [[ -z "$app_path" ]]; then
  echo "error: could not locate built ${scheme}.app under Build/Products" >&2
  exit 1
fi
echo "Built app: $app_path"

for device in "${devices[@]}"; do
  echo
  echo "==> $device: uninstalling any previous ${bundle_id} install"
  if ! xcrun devicectl device uninstall app --device "$device" "$bundle_id"; then
    echo "    (no previous install found, or uninstall failed harmlessly -- continuing)"
  fi

  echo "==> $device: installing freshly built app"
  xcrun devicectl device install app --device "$device" "$app_path"

  if [[ "$launch_after_install" -eq 1 ]]; then
    echo "==> $device: launching ${bundle_id}"
    xcrun devicectl device process launch --device "$device" "$bundle_id"
  fi
done

echo
echo "Done. Installed a fresh ${scheme}.app build on: ${devices[*]}"
if [[ "$launch_after_install" -eq 0 ]]; then
  echo "Launch manually with:"
  echo "  xcrun devicectl device process launch --device <udid> $bundle_id"
fi

tests_derived_data="$repo_root/build/ios-proof-benchmarks-derived-data"

if [[ "$build_tests" -eq 1 ]]; then
  echo
  echo "==> Building ${tests_scheme} for testing on physical iOS (generic/platform=iOS, DEVELOPMENT_TEAM=$development_team)"
  # Reuses a dedicated derivedDataPath (separate from the app build above) so
  # xcodebuild can later resolve the built test products/.xctestrun by scheme
  # + derivedDataPath alone in the test-without-building step below.
  xcodebuild build-for-testing \
    -project "$project_path" \
    -scheme "$tests_scheme" \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "$tests_derived_data" \
    DEVELOPMENT_TEAM="$development_team" \
    -allowProvisioningUpdates
fi

if [[ -n "$run_test" ]]; then
  for device in "${devices[@]}"; do
    echo
    echo "==> $device: running $run_test"
    xcodebuild test-without-building \
      -project "$project_path" \
      -scheme "$tests_scheme" \
      -destination "id=$device" \
      -derivedDataPath "$tests_derived_data" \
      DEVELOPMENT_TEAM="$development_team" \
      -allowProvisioningUpdates \
      "-only-testing:$run_test"
  done
  echo
  echo "Done running $run_test on: ${devices[*]}"
fi
