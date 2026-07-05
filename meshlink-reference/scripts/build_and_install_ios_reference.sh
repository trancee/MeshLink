#!/bin/bash
# Builds and installs the physical-iPhone ReferenceApp.
#
# Run this from a normal GUI Terminal.app session (NOT from an automation/CI
# shell). Codesigning requires access to the Security Agent/keychain of the
# logged-in Aqua session; a background/launchd-managed shell cannot obtain
# that access and codesign will fail with errSecInternalComponent regardless
# of keychain unlock state.
#
# Usage:
#   ./meshlink-reference/scripts/build_and_install_ios_reference.sh [ios-device-udid] [DEVELOPMENT_TEAM]
#
# Both arguments are optional. The device UDID defaults to the physical
# iPhone 15 used for MeshLink device testing. If DEVELOPMENT_TEAM is
# omitted, a locally cached provisioning profile for
# ch.trancee.meshlink.reference.ios is used if one exists.

set -euo pipefail

DEFAULT_IOS_DEVICE="7F5320D7-1D8E-56F5-9D6E-0A6967B25467"

IOS_DEVICE="${1:-$DEFAULT_IOS_DEVICE}"
DEVELOPMENT_TEAM="${2:-${DEVELOPMENT_TEAM:-}}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

XCODEBUILD_ARGS=(
  -project meshlink-reference/ios/ReferenceApp.xcodeproj
  -scheme ReferenceApp
  -destination "id=$IOS_DEVICE"
  -allowProvisioningUpdates
  build
)

if [[ -n "$DEVELOPMENT_TEAM" ]]; then
  XCODEBUILD_ARGS=(-project meshlink-reference/ios/ReferenceApp.xcodeproj \
    -scheme ReferenceApp \
    -destination "id=$IOS_DEVICE" \
    "DEVELOPMENT_TEAM=$DEVELOPMENT_TEAM" \
    -allowProvisioningUpdates \
    build)
fi

echo "==> Building physical iPhone reference app (device: $IOS_DEVICE)"
xcodebuild "${XCODEBUILD_ARGS[@]}"

APP_PATH="$(find "$HOME/Library/Developer/Xcode/DerivedData" \
  -path "*/ReferenceApp-*/Build/Products/Debug-iphoneos/ReferenceApp.app" \
  -maxdepth 6 -print0 2>/dev/null \
  | xargs -0 ls -dt 2>/dev/null \
  | head -n 1)"

if [[ -z "$APP_PATH" ]]; then
  echo "Could not find a built ReferenceApp.app in Xcode DerivedData" >&2
  exit 1
fi

echo "==> Built app: $APP_PATH"
echo "==> Installing on device $IOS_DEVICE"
xcrun devicectl device install app --device "$IOS_DEVICE" "$APP_PATH"

echo "==> Done. App built and installed successfully."
