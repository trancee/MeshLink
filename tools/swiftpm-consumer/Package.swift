// swift-tools-version: 6.0
import PackageDescription

// Consumes the locally built MeshLinkKit.xcframework the way an app does: through a binaryTarget.
// Run it with tools/swiftpm-consumer/test.sh, which builds the framework first.
let package = Package(
    name: "MeshLinkKitConsumer",
    platforms: [.iOS(.v15)],
    targets: [
        .binaryTarget(
            name: "MeshLinkKit",
            path: "../../meshlink/build/XCFrameworks/release/MeshLinkKit.xcframework"
        ),
        .testTarget(
            name: "MeshLinkKitConsumerTests",
            dependencies: ["MeshLinkKit"]
        ),
    ]
)
