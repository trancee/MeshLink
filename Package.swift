// swift-tools-version: 5.9

// Package.swift
// MeshLink - Secure BLE mesh messaging library
//
// Build the XCFramework with:
//   ./gradlew :meshlink:assembleMeshLinkXCFramework
//
// The XCFramework will be generated at:
//   meshlink/build/XCFrameworks/release/MeshLink.xcframework

import PackageDescription

let package = Package(
    name: "MeshLink",
    platforms: [
        .iOS(.v13),
    ],
    products: [
        .library(
            name: "MeshLink",
            targets: ["MeshLink"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "MeshLink",
            path: "meshlink/build/XCFrameworks/release/MeshLink.xcframework"
        ),
    ]
)
