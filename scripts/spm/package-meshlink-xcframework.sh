#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
artifact_dir="$repo_root/meshlink/build/swiftpm"
xcframework_name="MeshLink.xcframework"
xcframework_source="$repo_root/meshlink/build/XCFrameworks/release/$xcframework_name"
xcframework_staging="$artifact_dir/$xcframework_name"
zip_path="$artifact_dir/$xcframework_name.zip"
checksum_path="$artifact_dir/$xcframework_name.checksum"

cd "$repo_root"
./gradlew :meshlink:assembleXCFramework

rm -rf "$artifact_dir"
mkdir -p "$artifact_dir"
cp -R "$xcframework_source" "$xcframework_staging"
rm -f "$zip_path" "$checksum_path"

cd "$artifact_dir"
zip -qry "$xcframework_name.zip" "$xcframework_name"
checksum="$(swift package compute-checksum "$xcframework_name.zip")"
printf '%s\n' "$checksum" > "$checksum_path"

printf 'XCFramework staged at: %s\n' "$xcframework_staging"
printf 'Archive written to: %s\n' "$zip_path"
printf 'SwiftPM checksum: %s\n' "$checksum"
