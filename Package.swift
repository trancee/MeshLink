// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MeshLink",
    platforms: [
        .iOS(.v14),
    ],
    products: [
        .library(name: "MeshLink", targets: ["MeshLink"]),
    ],
    targets: [
        .binaryTarget(
            name: "MeshLink",
            path: "meshlink/build/swiftpm/MeshLink.xcframework"
        ),
    ]
)
