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
            url: "https://example.invalid/releases/MeshLink.xcframework.zip",
            checksum: "REPLACE_WITH_SWIFT_PACKAGE_CHECKSUM"
        ),
    ]
)
