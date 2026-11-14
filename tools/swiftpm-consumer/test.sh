#!/usr/bin/env bash
# Builds the release MeshLinkKit.xcframework, then runs the SwiftPM consumer's XCTest target on an
# iOS Simulator with `xcodebuild test`. `swift test` can't be used: it runs on macOS, and the
# XCFramework has only iOS slices.
#
# Destination: MESHLINK_SIMULATOR_DESTINATION if set, else the first available iPhone on the newest
# installed iOS Simulator runtime, as `platform=iOS Simulator,id=<udid>`. Verified 2026-11-14 with
# Xcode 27.2 (DEVELOPER_DIR) and the iOS 18.6 runtime (iPhone 16 Pro).
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
    udid="$(xcrun simctl list devices available --json | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
runtimes = sorted(
    (r for r in devices if ".SimRuntime.iOS-" in r),
    key=lambda r: [int(p) for p in r.rsplit("iOS-", 1)[1].split("-")],
    reverse=True,
)
for runtime in runtimes:
    for device in devices[runtime]:
        if device["name"].startswith("iPhone"):
            print(device["udid"])
            sys.exit(0)
sys.exit("no available iPhone simulator; create one with xcrun simctl create")
')"
    destination="platform=iOS Simulator,id=$udid"
fi

cd "$here"
xcodebuild test \
    -scheme MeshLinkKitConsumer-Package \
    -destination "$destination" \
    -derivedDataPath "$here/.build/DerivedData" \
    -quiet
