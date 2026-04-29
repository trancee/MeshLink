# Changelog

All notable changes to MeshLink are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-04-29

### Added

#### Public API
- `MeshLinkApi` interface with lifecycle, messaging, peer management, identity, and observability methods
- `meshLinkConfig {}` DSL builder with 8 sub-configs (messaging, transport, security, power, routing, diagnostics, rateLimiting, transfer)
- 4 config presets: `smallPayloadLowLatency`, `largePayloadHighThroughput`, `minimalResourceUsage`, `sensorTelemetry`
- `MeshLinkState` lifecycle FSM (Idle → Starting → Running → Stopping → Stopped)
- `PeerEvent` sealed interface (Found, Lost, StateChanged, KeyChangeDetected)
- `PeerIdHex` value class for type-safe peer identifiers
- `DiagnosticEvent` flow with 27 codes across 3 severity tiers
- `MeshHealthSnapshot` point-in-time and periodic health reporting
- `RegulatoryRegion.EU` with automatic ETSI EN 300 328 clamping
- Platform factories: `MeshLink.createAndroid()`, `MeshLink.createIos()`

#### Crypto
- `CryptoProvider` with Ed25519, X25519, ChaCha20-Poly1305, SHA-256, HMAC-SHA-256, HKDF-SHA-256
- Noise XX mutual authentication (`Noise_XX_25519_ChaChaPoly_SHA256`)
- Noise K end-to-end payload sealing (`Noise_K_25519_ChaChaPoly_SHA256`)
- Sliding-window replay protection (64-slot bitmap)
- TOFU trust model with Strict and Prompt modes
- Identity key rotation with signed announcements

#### Protocol
- Babel routing (RFC 8966 adaptation) with feasibility condition, differential updates, seqNo
- 12 wire message types with pure-Kotlin FlatBuffers codec
- SACK-based reliable transfer with retransmission and resume
- Cut-through relay forwarding (pipeline chunks at relay nodes)
- Flood-fill broadcast with Ed25519 signatures and TTL
- DedupSet (TTL + LRU) for broadcast deduplication

#### Transport
- L2CAP-first connection model with automatic GATT fallback
- 16-byte advertisement with mesh hash, PSM, and rotating pseudonym
- `ConnectionInitiationPolicy` (FNV-1a tie-breaking for simultaneous discovery)
- `OemSlotTracker` for learning per-device-model L2CAP reliability
- Android: `AndroidBleTransport` with GATT server (5 characteristics) + L2CAP
- iOS: `IosBleTransport` with CoreBluetooth + State Preservation/Restoration

#### Power Management
- Three-tier model (Performance, Balanced, PowerSaver)
- Automatic tier selection from battery level with hysteresis
- 30-second bootstrap period at Performance on cold start
- `TieredShedder` for connection limit enforcement per tier

#### Peer Lifecycle
- Three-state FSM (Connected → Disconnected → Gone)
- `MeshStateManager` 30-second sweep with 2-sweep eviction
- `PresenceTracker` with grace period for transient disconnects

#### Privacy
- HMAC-SHA-256 pseudonym rotation on 15-minute epochs
- Per-peer stagger to prevent synchronized rotation

#### Distribution
- Maven Central: `ch.trancee:meshlink:0.1.0` (JVM JAR, Android AAR, iOS KLib)
- Swift Package Manager: XCFramework binary target
- SKIE 0.10.11 for Swift-native async/sealed class interop
- `meshlink-testing` module with `MeshTestHarness` for consumer integration tests

#### Quality
- 1460+ JVM tests at 100% line and branch coverage (Kover)
- Zero `@CoverageIgnore` annotations
- BCV API baselines (JVM + KLib)
- 5 benchmark suites (kotlinx-benchmark)
- Detekt + ktfmt enforcement
- CI pipeline: lint, test, coverage, benchmark, docs link check

#### Documentation
- Diataxis-structured docs (33 files): tutorials, how-to guides, reference, explanation
- `SPEC.md` greenfield build specification for AI agents
- `CONTRIBUTING.md` developer guide

[0.1.0]: https://github.com/nicegram/MeshLink/releases/tag/v0.1.0
