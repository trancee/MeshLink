# MeshLink SwiftPM support

This directory holds the Swift Package Manager manifests for MeshLink.

## Local checkout package

Use `Package.swift` when you are working from this repository checkout.
It points at the XCFramework staged by the packaging helper:

```bash
./scripts/spm/package-meshlink-xcframework.sh
```

That helper:

1. builds the MeshLink XCFramework from the Gradle module
2. stages it at `meshlink/build/swiftpm/MeshLink.xcframework`
3. zips the artifact for release distribution
4. prints the Swift Package checksum for the zip

## Release package template

Use `Package.release.swift` when preparing a release binary target.
Replace the placeholder URL and checksum with the published artifact values.
