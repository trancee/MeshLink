#!/usr/bin/env bash
# Builds the release MeshLinkKit.xcframework, then runs the SwiftPM consumer's XCTest target, which
# depends on the swift/ package's `MeshLink` product, on an iOS Simulator with `xcodebuild test`.
# `swift test` can't be used: it runs on macOS, and the XCFramework has only iOS slices. The destination
# comes from tools/ios-simulator-destination.sh (MESHLINK_SIMULATOR_DESTINATION overrides it).
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

destination="$("$root/tools/ios-simulator-destination.sh" "$here" MeshLinkKitConsumer-Package)"

cd "$here"
started=$SECONDS
xcodebuild test \
    -scheme MeshLinkKitConsumer-Package \
    -destination "$destination" \
    -derivedDataPath "$here/.build/DerivedData" \
    -quiet
echo "SwiftPM simulator test: $((SECONDS - started))s"
