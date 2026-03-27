// swift-tools-version: 5.9

// Package.swift
// MeshLink - Secure BLE mesh messaging library
//
// Build the XCFramework with:
//   ./gradlew :meshlink-core:assembleMeshLinkCoreXCFramework
//
// The XCFramework will be generated at:
//   meshlink-core/build/XCFrameworks/release/MeshLinkCore.xcframework

import PackageDescription

let package = Package(
    name: "MeshLink",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(
            name: "MeshLinkCore",
            targets: ["MeshLinkCore"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "MeshLinkCore",
            path: "meshlink-core/build/XCFrameworks/release/MeshLinkCore.xcframework"
        ),
    ]
)
