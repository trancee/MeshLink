# MeshLink SwiftPM support

Swift Package Manager support now lives at the repository root.

Use:

- `Package.swift` for local checkout integration
- `Package.release.swift` for the release binary-target template

For packaging, run:

```bash
./scripts/spm/package-meshlink-xcframework.sh
```

That helper builds the MeshLink XCFramework from the Gradle module, stages it
at `meshlink/build/swiftpm/MeshLink.xcframework`, zips the artifact, and prints
the Swift Package checksum.
