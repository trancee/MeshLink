// swift-tools-version: 6.0
import PackageDescription

// Development-only package (ADR 0011 decisions 4 and 5): it builds against the MeshLinkKit XCFramework
// that `./gradlew :meshlink:assembleMeshLinkKitReleaseXCFramework` writes. The release manifest comes
// from the meshlink-swift repository (ADR 0019). Run its tests with swift/test.sh.
let package = Package(
    name: "MeshLink",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "MeshLink", targets: ["MeshLink"]),
        .library(name: "MeshLinkTesting", targets: ["MeshLinkTesting"]),
    ],
    targets: [
        .binaryTarget(
            name: "MeshLinkKit",
            path: "../meshlink/build/XCFrameworks/release/MeshLinkKit.xcframework"
        ),
        .target(name: "MeshLink", dependencies: ["MeshLinkKit"]),
        .target(name: "MeshLinkTesting", dependencies: ["MeshLink"]),
        .testTarget(name: "MeshLinkTests", dependencies: ["MeshLink"]),
        .testTarget(name: "MeshLinkTestingTests", dependencies: ["MeshLinkTesting"]),
    ]
)
