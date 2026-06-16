# MeshLink SwiftPM support

Swift Package Manager support lives at the repository root:

- `Package.swift` for local checkout integration
- `Package.release.swift` for the release binary-target template

Use the packaging helper to stage the XCFramework and print the checksum:

```bash
./scripts/spm/package-meshlink-xcframework.sh
```

The helper builds the MeshLink XCFramework from the Gradle module, stages it at
`meshlink/build/swiftpm/MeshLink.xcframework`, zips the artifact, and prints
the Swift Package checksum.
