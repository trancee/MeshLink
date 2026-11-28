#!/usr/bin/env bash
# Builds the release MeshLinkKit.xcframework, checks that the Swift reference scenarios carry the titles
# of meshlink-testing's reference-scenarios.feature, then runs the swift/ package's XCTest targets on an
# iOS Simulator with `xcodebuild test`. `swift test` can't be used: it runs on macOS, and the
# XCFramework has only iOS slices. The destination comes from tools/ios-simulator-destination.sh
# (MESHLINK_SIMULATOR_DESTINATION overrides it); a concrete arm64 simulator, because a generic
# destination also builds x86_64, which the XCFramework lacks.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

python3 "$root/tools/check-scenario-parity.py"

# Always rebuild through Gradle (up-to-date checks keep it cheap), so a stale framework is never tested.
started=$SECONDS
if ! "$root/gradlew" -p "$root" --quiet :meshlink:assembleMeshLinkKitReleaseXCFramework; then
    echo "error: ./gradlew :meshlink:assembleMeshLinkKitReleaseXCFramework failed" >&2
    exit 1
fi
echo "XCFramework build: $((SECONDS - started))s"

xcframework="$root/meshlink/build/XCFrameworks/release/MeshLinkKit.xcframework"
if [[ ! -f "$xcframework/Info.plist" ]]; then
    echo "error: $xcframework was not produced by :meshlink:assembleMeshLinkKitReleaseXCFramework" >&2
    exit 1
fi

destination="$("$root/tools/ios-simulator-destination.sh" "$here" MeshLink-Package)"

cd "$here"
started=$SECONDS
xcodebuild test \
    -scheme MeshLink-Package \
    -destination "$destination" \
    -derivedDataPath "$here/.build/DerivedData" \
    -quiet
echo "swift/ simulator test: $((SECONDS - started))s"
