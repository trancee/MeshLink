# What are MeshLink's Maven coordinates, Kotlin package and Swift module names?

Type: grilling
Status: resolved

## Question

Decide the Maven Central namespace and groupId (a verified io.github namespace or an owned domain), the artifact IDs, the root Kotlin package, the XCFramework and Swift module names, and the meshlink-swift package and product names. These cannot change after 0.1.0 without breaking every consumer.

## Context

- ADR 0011 decision 1 names the artifacts `meshlink`, `meshlink-testing`, `MeshLinkKit.xcframework` and the `MeshLink` Swift package; no groupId or package is recorded anywhere.

## Answer

2026-11-13, grilling; recorded as the 2026-11-13 amendment to [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments).

- **Maven Central:** namespace `ch.trancee` (already verified through a DNS TXT record on `trancee.ch`, as confirmed by the owner). groupId `ch.trancee.meshlink`; artifacts `meshlink` and `meshlink-testing`. Chosen over `io.github.trancee` to match cairn and the lab bundle ID; the domain must stay registered.
- **Kotlin:** `ch.trancee.meshlink` (public API), `ch.trancee.meshlink.testing` and `ch.trancee.meshlink.internal`. Android `namespace` `ch.trancee.meshlink`.
- **Apple:** XCFramework and Swift module `MeshLinkKit`, bundle ID `ch.trancee.meshlink.kit`.
- **Swift package:** repository `trancee/meshlink-swift`, package `MeshLink`, products `MeshLink` (re-exports `MeshLinkKit`) and `MeshLinkTesting`.
- No new ADR and no glossary entry: these are names, not domain terms.

