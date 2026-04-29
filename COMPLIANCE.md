# MeshLink Compliance Summary

Version: 0.1.0  
Date: 2026-04-26

This document maps the MeshLink implementation to the specification sections it covers, states which
sections are implemented, which are deferred, and summarises the validation evidence for each claim.

---

## Overview

MeshLink implements §4 Identity, §5 Cryptography, §6 Transport, §7 Routing, §8 Messaging,
§9 Chunked Transfer, §10 Power Management, §11 Diagnostics, and §13 Public API in full.
§12 OEM Hardening is partially deferred: the public API surface, ProGuard consumer rules, and
BCV-enforced binary stability are in place, but the optional hardware-attestation and OEM-specific
per-device slot tracking customisation hooks are not exposed.

---

## Spec Section Coverage

| Section | Title | Status | Evidence |
|---------|-------|--------|----------|
| §4 | Identity & Key Management | ✅ Implemented | `Identity.loadOrGenerate`, Ed25519 keypair, `IosSecureStorage` / `AndroidSecureStorage`, `TrustStore`, key-rotation announce; 1 517 unit tests pass, Kover 100% |
| §5 | Cryptography | ✅ Implemented | Noise XX handshake (ChaCha20-Poly1305), Ed25519 sign/verify, X25519 DH; Wycheproof test vectors (all suites pass); constant-time equality (`ConstantTimeEquals`); libsodium JNI + cinterop; 1 517 unit tests pass |
| §6 | Transport | ✅ Implemented | `AndroidBleTransport` (L2CAP CoC + GATT GATT fallback, `OemSlotTracker`, `OemL2capProbeCache`, `MeshHashFilter`); `IosBleTransport` (CoreBluetooth central+peripheral, L2CAP CoC, GATT fallback, state restoration); `VirtualMeshTransport` for integration testing; S06 two-device manual UAT |
| §7 | Routing | ✅ Implemented | Babel-inspired distance-vector with feasibility condition (`RoutingEngine`, `RoutingTable`), dedup set (`DedupSet`), presence tracking (`PresenceTracker`); 1 517 unit tests pass, Kover 100% |
| §8 | Messaging | ✅ Implemented | Unicast with store-and-forward (`DeliveryPipeline`), broadcast with Ed25519 signature and TTL flood-fill, replay guard, rate limiting, priority queues, GDPR `forgetPeer` + `factoryReset`; 1 517 unit tests pass, Kover 100% |
| §9 | Chunked Transfer | ✅ Implemented | `TransferEngine` with chunk-level ACK, inactivity timeout (scaled by hop count), concurrent session limit, `TransferFailure.Timeout` / `PeerUnavailable` / `Cancelled` events; 1 517 unit tests pass, Kover 100% |
| §10 | Power Management | ✅ Implemented | `PowerTierController` with PERFORMANCE / BALANCED / POWER_SAVER tiers, battery-level thresholds, bootstrap override, `setCustomPowerMode`, per-tier connection limits, peer eviction; 1 517 unit tests pass, Kover 100% |
| §11 | Diagnostics & Observability | ✅ Implemented | All 27 `DiagnosticCode` values (4 critical, 8 threshold, 15 log), `DiagnosticSink` ring-buffer with drop counter, `MeshHealthSnapshot` periodic emission, `DiagnosticsConfig.redactPeerIds` GDPR truncation, `DiagnosticsScreen` in reference app; 1 517 unit tests pass |
| §12 | OEM Hardening | ⚠️ Partial | `explicitApi()` enforced, BCV baseline locked (`meshlink/api/jvm/meshlink.api`), `@ExperimentalMeshLinkApi` annotation, ProGuard consumer rules in AAR, `OemSlotTracker` + `OemL2capProbeCache` extension points; hardware-attestation and custom slot-tracker injection API deferred to post-1.0 |
| §13 | Public API | ✅ Implemented | `MeshLinkApi` interface (lifecycle, messaging, flows, power, identity, health, routing, GDPR), `MeshLinkConfig` DSL with validation + clamping, `MeshLinkState` FSM (5 states, guarded transitions), `MeshLink(config)` JVM factory, `MeshLink.createAndroid(context, config)` Android factory, `MeshLink.createIos(config)` iOS factory; BCV check passes |

---

## Validation Evidence

| Class | Evidence |
|-------|----------|
| Unit tests | 1 517 `@Test` methods in 47 test files across `commonTest`; runs via `./gradlew :meshlink:jvmTest` (CI) |
| Coverage | 100% line + branch on `commonMain` enforced by Kover 0.9.8 (`./gradlew :meshlink:koverVerify`); `androidMain` / `iosMain` factory classes excluded per D024 (require real BLE hardware) |
| Binary compatibility | BCV baseline committed at `meshlink/api/jvm/meshlink.api`; enforced by `./gradlew apiCheck` on every PR |
| Cryptographic vectors | Wycheproof test vectors (ECDH X25519, Ed25519, ChaCha20-Poly1305, HMAC-SHA-256, HKDF) — all suites pass |
| Two-device integration | Manual UAT procedure in `.gsd/milestones/M004/slices/S06/S06-UAT.md`; real BLE hardware required (see MEM136) |

---

## Deferred Items

| Item | Reason | Target |
|------|--------|--------|
| §12 Hardware attestation | Requires vendor-specific attestation API not yet available | Post-1.0 |
| §12 Custom `OemSlotTracker` injection via public factory | Deferred to allow API stabilisation | Post-1.0 |
| Maven Central artifact resolution from clean cache | Requires published artifacts at `ch.trancee:meshlink:0.1.0` | Pre-release |
| SPM binary target checksum | `Package.swift` checksum placeholder updated by `release.yml` on tag push | Pre-release |

---

## Export Control

MeshLink uses strong cryptography (ChaCha20-Poly1305, Ed25519, X25519) and is classified under
**ECCN 5D002**. Full details — including the algorithm inventory, TSU §740.13(e) open-source
exemption analysis, and notification requirements — are documented in
**[`EXPORT_CONTROL.md`](EXPORT_CONTROL.md)**.

> ⚠️ **Pre-publish obligation:** Before publishing to Maven Central or any public repository,
> the library maintainer must file the TSU §740.13(e) notification with BIS and NSA as
> described in `EXPORT_CONTROL.md`. CI does **not** enforce this — completion is the
> maintainer's responsibility.

---

## GDPR

MeshLink stores no plaintext user data. All payloads are application-supplied bytes encrypted via
Noise ChaCha20-Poly1305. The `DiagnosticsConfig.redactPeerIds = true` flag truncates peer key
hashes in diagnostic output to 8 hex characters. `forgetPeer(peerId)` erases all data for a given
peer. `factoryReset()` wipes all locally stored keys and state. No telemetry or analytics is
collected by the library.
