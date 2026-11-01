// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "MeshLinkSwift",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "MeshLinkSwift", targets: ["MeshLinkSwift"]),
    ],
    targets: [
        // Copied from ../build/XCFrameworks/release by ../scripts/sync-xcframework.sh.
        .binaryTarget(name: "MeshLinkKit", path: "Frameworks/MeshLinkKit.xcframework"),
        // Minimal overlay: idioms only (Data <-> KotlinByteArray, Swift defaults), no logic.
        .target(name: "MeshLinkSwift", dependencies: ["MeshLinkKit"]),
        // Waiting/timeout helpers shared by both test targets; uses no overlay API.
        .target(name: "MeshLinkTestSupport", dependencies: ["MeshLinkKit"], path: "Sources/MeshLinkTestSupport"),
        .testTarget(name: "OverlayScenarioTests", dependencies: ["MeshLinkSwift", "MeshLinkTestSupport"]),
        .testTarget(name: "RawScenarioTests", dependencies: ["MeshLinkKit", "MeshLinkTestSupport"]),
    ]
)
