# What are MeshLink's Maven coordinates, Kotlin package and Swift module names?

Type: grilling
Status: open

## Question

Decide the Maven Central namespace and groupId (a verified io.github namespace or an owned domain), the artifact IDs, the root Kotlin package, the XCFramework and Swift module names, and the meshlink-swift package and product names. These cannot change after 0.1.0 without breaking every consumer.

## Context

- ADR 0011 decision 1 names the artifacts `meshlink`, `meshlink-testing`, `MeshLinkKit.xcframework` and the `MeshLink` Swift package; no groupId or package is recorded anywhere.
