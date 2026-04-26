// swift-tools-version:5.9
// Package.swift — SPM binary target manifest for MeshLink XCFramework.
//
// The URL and checksum below are updated automatically by the publish-xcframework job in
// .github/workflows/release.yml on every tag push.  Do not edit the checksum manually.
import PackageDescription

let package = Package(
    name: "MeshLink",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "MeshLink", targets: ["MeshLink"])
    ],
    targets: [
        .binaryTarget(
            name: "MeshLink",
            url: "https://github.com/trancee/meshlink/releases/download/v0.1.0/MeshLink.xcframework.zip",
            checksum: "PLACEHOLDER_CHECKSUM_UPDATED_BY_RELEASE_WORKFLOW"
        )
    ]
)
