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
started=$SECONDS
"$root/gradlew" -p "$root" --quiet :meshlink:assembleMeshLinkKitReleaseXCFramework
echo "XCFramework build: $((SECONDS - started))s"

# The task is NO-SOURCE (and writes nothing) when :meshlink has no Kotlin source.
xcframework="$root/meshlink/build/XCFrameworks/release/MeshLinkKit.xcframework"
if [[ ! -f "$xcframework/Info.plist" ]]; then
    echo "error: $xcframework was not produced" >&2
    exit 1
fi

destination="${MESHLINK_SIMULATOR_DESTINATION:-}"
if [[ -z "$destination" ]]; then
    udid="$(cd "$here" && python3 discover-destination.py)"
    destination="platform=iOS Simulator,id=$udid"
fi

cd "$here"
started=$SECONDS
xcodebuild test \
    -scheme MeshLinkKitConsumer-Package \
    -destination "$destination" \
    -derivedDataPath "$here/.build/DerivedData" \
    -quiet
echo "SwiftPM simulator test: $((SECONDS - started))s"
