// swift-tools-version: 6.0
import PackageDescription

// Consumes MeshLink the way an app does: through the swift/ package's `MeshLink` product, which
// re-exports the locally built MeshLinkKit.xcframework. Run it with tools/swiftpm-consumer/test.sh,
// which builds the framework first.
let package = Package(
    name: "MeshLinkKitConsumer",
    platforms: [.iOS(.v15)],
    dependencies: [
        .package(name: "MeshLink", path: "../../swift"),
    ],
    targets: [
        .testTarget(
            name: "MeshLinkKitConsumerTests",
            dependencies: [.product(name: "MeshLink", package: "MeshLink")]
        ),
    ]
)
