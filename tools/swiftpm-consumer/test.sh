#!/usr/bin/env bash
# Builds the release MeshLinkKit.xcframework, then runs the SwiftPM consumer's XCTest target on an
# iOS Simulator with `xcodebuild test`. `swift test` can't be used: it runs on macOS, and the
# XCFramework has only iOS slices.
#
# Destination: MESHLINK_SIMULATOR_DESTINATION if set, else the iPhone simulator with the newest iOS
# version among the scheme's available destinations, as `platform=iOS Simulator,id=<udid>`. Asking
# `xcodebuild -showdestinations` lets the selected Xcode reject runtimes it can't run, which matters
# on CI images that install runtimes for several Xcode versions. Verified 2026-11-14 with Xcode 16.4
# and 27.2 and the iOS 18.6 runtime (iPhone 16 Pro).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"

# Always rebuild through Gradle (up-to-date checks keep it cheap), so a stale framework is never tested.
"$root/gradlew" -p "$root" --quiet :meshlink:assembleMeshLinkKitReleaseXCFramework

# The task is NO-SOURCE (and writes nothing) when :meshlink has no Kotlin source.
xcframework="$root/meshlink/build/XCFrameworks/release/MeshLinkKit.xcframework"
if [[ ! -f "$xcframework/Info.plist" ]]; then
    echo "error: $xcframework was not produced" >&2
    exit 1
fi

destination="${MESHLINK_SIMULATOR_DESTINATION:-}"
if [[ -z "$destination" ]]; then
    udid="$(cd "$here" && xcodebuild -showdestinations -scheme MeshLinkKitConsumer-Package | python3 -c '
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
    destination="platform=iOS Simulator,id=$udid"
fi

cd "$here"
xcodebuild test \
    -scheme MeshLinkKitConsumer-Package \
    -destination "$destination" \
    -derivedDataPath "$here/.build/DerivedData" \
    -quiet
