#!/bin/sh
# Copies the release XCFramework into the SwiftPM package (binary targets must live inside it).
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
rm -rf "$here/swift/Frameworks/MeshLinkKit.xcframework"
mkdir -p "$here/swift/Frameworks"
cp -R "$here/build/XCFrameworks/release/MeshLinkKit.xcframework" "$here/swift/Frameworks/"
