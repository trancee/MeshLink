#!/usr/bin/env bash
# Prints the xcodebuild destination for a SwiftPM package's simulator tests:
# MESHLINK_SIMULATOR_DESTINATION if set, else the iPhone simulator with the newest iOS version among the
# scheme's available destinations, as `platform=iOS Simulator,arch=arm64,id=<udid>` (arm64, because
# the XCFramework has no x86_64 Swift interface). Asking
# `xcodebuild -showdestinations` lets the selected Xcode reject runtimes it can't run, which matters on
# CI images that install runtimes for several Xcode versions. Verified 2026-11-14 with Xcode 16.4 and
# 27.2 and the iOS 18.6 runtime (iPhone 16 Pro).
#
# Usage: tools/ios-simulator-destination.sh <package-dir> <scheme>
set -euo pipefail

if [[ -n "${MESHLINK_SIMULATOR_DESTINATION:-}" ]]; then
    echo "$MESHLINK_SIMULATOR_DESTINATION"
    exit 0
fi

udid="$(cd "$1" && xcodebuild -showdestinations -scheme "$2" | python3 -c '
import sys
available = sys.stdin.read().split("Available destinations", 1)[-1].split("Ineligible destinations", 1)[0]
simulators = []
for line in available.splitlines():
    fields = dict(f.split(":", 1) for f in line.strip().strip("{} ").split(", ") if ":" in f)
    if fields.get("platform") == "iOS Simulator" and "OS" in fields and fields.get("name", "").startswith("iPhone"):
        simulators.append(([int(p) for p in fields["OS"].split(".")], fields["id"]))
if not simulators:
    sys.exit("no available iPhone simulator for the selected Xcode; create one with xcrun simctl create")
print(max(simulators)[1])
')"
echo "platform=iOS Simulator,arch=arm64,id=$udid"
