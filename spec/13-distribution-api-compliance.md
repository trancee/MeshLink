# 13 — Distribution, Public API, Configuration & Compliance

> **Covers:** §14 (Reference Applications & Distribution) + §15 (RFC Reference Map & Appendices)
> **Dependencies:** `01-vision-and-domain-model.md` (all terms), `02-architecture.md` (build system)
> **Key exports:** Reference app architecture, distribution channels, public API surface, lifecycle state machine, configuration system, threat model, compliance
> **Changes from original:** S2 (ratchet config/API removed), S5 (compression config removed), S1 (Bloom filter removed from threat model), S10 (fixed tiers in config), A4 (bootstrap config), A7 (routing table query API), A8 (L2CAP force override), M2 (fixed TTL in config). Review fixes: appId added to config, BLE permission revocation behavior specified. Research fixes: R-BCV (binary-compatibility-validator added to build + API), R-EXPORT (TSU notification corrected — eliminated 2021, EAR §740.13(e) now [Reserved]), R-GDPR (Key Hash classification refined per ECJ C-413/23 P relative approach), R-APPSTORE (Apple ITSAppUsesNonExemptEncryption added), R-SWIFT-EXPORT (tracked in 14-future.md).

---

## 1. Reference App Architecture

A reference app ships alongside the library to demonstrate integration patterns. **It is NOT the product.**

**Shared module** (`meshlink-sample/shared/`):
- `MeshController` — state machine driving mesh lifecycle
- `MeshCommand` / `MeshEvent` / `MeshState` — unidirectional data flow
- `CommandResult` — sealed result types

**Platform UIs:**

| Platform | Framework |
|----------|-----------|
| Android | Jetpack Compose (4 screens) |
| iOS | SwiftUI (4 screens) |

**Four screens:** Chat, Mesh Visualizer, Diagnostics, Settings.

## 2. Build System

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Kotlin (Multiplatform) | 2.x |
| Build | Gradle (Kotlin DSL) | 9.x |
| KMP targets | `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`, `jvm()` (test-only) | — |
| Android | AGP | 9.x |
| Android SDK | compile/min | 36 / 29 |
| iOS | Xcode | 15+ |
| Crypto | libsodium | 1.0.18+ |
| Serialization | FlatBuffers | 24.x |
| Concurrency | kotlinx-coroutines | 1.10.x |
| Linting | detekt | 1.x |
| Testing | kotlin.test + Power Assert | — |
| API compat | binary-compatibility-validator | 0.18.x |
| UI Tests | Maestro | — |

## 3. Distribution Channels

| Platform | Channel | Artifact |
|----------|---------|----------|
| Android | Maven Central | `ch.trancee:meshlink:<version>` (AAR) |
| iOS | SPM | XCFramework via GitHub release tags |
| Testing | Maven Central | `ch.trancee:meshlink-testing:<version>` |

## 4. Public API (`MeshLinkApi` Interface)

### Lifecycle
`start()`, `stop(timeout: Duration = Duration.ZERO)`, `pause()`, `resume()`

### Messaging
`send(recipient, payload, priority)`, `broadcast(payload, maxHops, priority)`

### Event Streams (Kotlin Flow)
- `state` — StateFlow<MeshLinkState>
- `peers` — PeerEvent.Found / PeerEvent.Lost
- `messages` — received Message
- `deliveryConfirmations` — MessageId of delivered
- `transferProgress` — TransferProgress
- `transferFailures` — TransferFailure
- `meshHealthFlow` — periodic MeshHealthSnapshot
- `keyChanges` — KeyChangeEvent
- `diagnosticEvents` — DiagnosticEvent

**iOS interop:** SKIE (build-time-only Gradle plugin by Touchlab) provides `AsyncSequence` wrappers. Callback-based APIs also available.

### Power
`updateBattery(percent, isCharging)` *(manual override for embedded/headless)*, `setCustomPowerMode(mode?)`

### Identity & Trust
`localPublicKey`, `peerPublicKey(id)`, `peerDetail(id)`, `allPeerDetails()`, `peerFingerprint(id)`, `rotateIdentity()`, `repinKey(id)`, `acceptKeyChange(peerId)`, `rejectKeyChange(peerId)`, `pendingKeyChanges()`

### Health
`meshHealth()`, `shedMemoryPressure()`

### Routing (read-only query)
`routingSnapshot(): RoutingSnapshot` — returns a read-only point-in-time snapshot of the routing table for debugging. Includes destinations, next-hops, costs, sequence numbers, and route age. Not a live view — call again for updated data.

### Data Erasure (GDPR)
`forgetPeer(peerId)` — erase all stored data for peer (trust store, replay counters, rotation nonces, buffered messages, routing table entries). Send retractions for any routes through the forgotten peer. `factoryReset()` — erase all identity keys, trust store, replay counters, rotation nonces.

### Experimental
`addRoute(destination, nextHop, cost, seqNo)` — `@ExperimentalMeshLinkApi`. Manual route injection for testing. Bypasses feasibility condition + sanity validation.

### Binary Compatibility
The `binary-compatibility-validator` (BCV) Gradle plugin tracks the public ABI surface. `.api` dump files are committed to source control. CI runs `apiCheck` in the lint job — any unintentional public API change fails the build. Intentional changes require `./gradlew apiDump` and review. KLib validation enabled for iOS targets (`klib { enabled = true }`). All 5 major kotlinx libraries use this tool.

## 5. Lifecycle State Machine

States: `UNINITIALIZED`, `RUNNING`, `PAUSED`, `STOPPED`, `RECOVERABLE`, `TERMINAL`

| From | To | Trigger |
|------|-----|---------|
| UNINITIALIZED | RUNNING | `start()` succeeds |
| UNINITIALIZED | TERMINAL | `start()` fails (no crypto, invalid config) |
| RUNNING | PAUSED | `pause()` |
| PAUSED | RUNNING | `resume()` |
| RUNNING | STOPPED | `stop()` |
| PAUSED | STOPPED | `stop()` |
| RUNNING | RECOVERABLE | Transient failure (BLE reset, storage I/O) |
| RECOVERABLE | RUNNING | `start()` succeeds |
| RECOVERABLE | TERMINAL | `start()` fails again |
| RECOVERABLE | STOPPED | `stop()` |
| STOPPED | UNINITIALIZED | New instance |

**RECOVERABLE:** BLE stack unresponsive, transient storage I/O, BLE permission revoked (re-grantable).
**TERMINAL:** Missing CryptoProvider + `requireEncryption`, invalid config, permanent storage failure.
**Oscillation bound:** 3 failed `start()` from RECOVERABLE within 60s → TERMINAL.

### BLE Permission Revocation at Runtime
If BLE permission is revoked while RUNNING:
1. All active BLE operations fail immediately
2. In-flight transfers abort — buffered messages retained (E2E encrypted, resumable)
3. State transitions: RUNNING → RECOVERABLE
4. Noise XX sessions are invalidated (new handshakes required on resume)
5. Consuming App receives `BLE_STACK_UNRESPONSIVE` diagnostic
6. On permission re-grant: `start()` from RECOVERABLE re-initializes BLE + resumes buffered sends

Invalid transitions → no-op + `INVALID_STATE_TRANSITION` diagnostic. STOPPED/TERMINAL are absorbing.

### `stop()` Draining
- `Duration.ZERO` (default): Immediate teardown, in-flight abandoned. Pre-incremented replay counters for unsent messages are "wasted" (harmless — uint64 space is effectively infinite).
- Non-zero: Graceful drain — block new ops, await transfers up to timeout, then abandon remainder.

## 6. Configuration

### Structure (Nested Config Objects)
```kotlin
data class MeshLinkConfig(
    val appId: String,                   // Required. Mesh isolation key — hashed to meshHash in ads.
                                         // Only peers with matching appId will connect.
    val messaging: MessagingConfig,      // maxMessageSize, bufferCapacity, broadcastTtl, maxBroadcastSize, ...
    val transport: TransportConfig,      // mtu, l2capEnabled, forceL2cap, forceGatt,
                                         // bootstrapDurationMs, l2capRetryAttempts, ...
    val security: SecurityConfig,        // requireEncryption, trustMode, keyChangeTimeoutMillis,
                                         // requireBroadcastSignatures, ackJitterMaxMillis, ...
    val power: PowerConfig,              // powerModeThresholds, customPowerMode,
                                         // evictionGracePeriodMillis, ...
    val routing: RoutingConfig,          // routeCacheTtlMillis, maxHops, dedupCapacity,
                                         // maxMessageAgeMillis, ...
    val diagnostics: DiagnosticsConfig,  // enabled, bufferCapacity, redactPeerIds, ...
    val rateLimiting: RateLimitConfig,   // maxSends, broadcastLimit, handshakeLimit, ...
    val transfer: TransferConfig,        // chunkInactivityTimeout, maxConcurrentTransfers,
                                         // ackTimeoutMultiplier, degradationThreshold, ...
)
```

DSL builder with nested scoping:
```kotlin
meshLinkConfig {
    security { trustMode = TrustMode.PROMPT }
    transport { l2capEnabled = false }
}
```

### Presets

| Preset | maxMessage | buffer | dedup | Use Case |
|--------|-----------|--------|-------|----------|
| `smallPayloadLowLatency` | 10K | 512KB | 25K | Text chat |
| `largePayloadHighThroughput` | 100K | 2MB | 25K | Images/files |
| `minimalResourceUsage` | 10K | 256KB | 10K | IoT/wearables |
| `sensorTelemetry` | 1K | 64KB | 5K | Sensors |

### Validation Rules

| Rule | Behavior |
|------|----------|
| `maxMessageSize ≤ bufferCapacity` | Throw |
| `broadcastTtl ≤ maxHops` | Clamp |
| `maxHops ∈ [1, 20]` | Clamp |
| `bufferCapacity ≥ 64 KB` | Clamp |
| `dedupCapacity ∈ [1000, 50000]` | Clamp |

Safety-critical → throw. Best-effort → clamp + `CONFIG_CLAMPED` diagnostic. Validation at `build()` time.

## 7. Threat Model Summary (14 Threats)

| ID | Threat | Severity | Mitigation |
|----|--------|----------|------------|
| TM-001 | Chunk flood → memory exhaustion | Critical | `maxConcurrentInboundSessions` (100 global hard cap across all connections; per-connection limits are 8/4/1 by power mode — see `08-transfer.md` §4) |
| TM-002 | Plaintext fallback | Critical | `requireEncryption = true` default |
| TM-003 | Dedup exhaustion → replay | High | TTL-based DedupSet, configurable cap |
| TM-004 | Route poisoning → blackhole | High | Cost sanity, per-next-hop failure tracking |
| TM-005 | Broadcast amplification | High | `maxHops = 10`, per-sender relay rate limit |
| TM-006 | Weak PRNG | High | Platform CSPRNG |
| TM-007 | Rotation announcement replay | Medium | Monotonic nonce |
| TM-008 | Decryption failure passthrough | Medium | `unsealOrDrop()` returns null |
| TM-009 | Exception message leak | Low | `throwable::class.simpleName` only |
| TM-010 | Debug logging exposure | Low | `debugLogging` → `internal` |
| TM-011 | Independent keypair compromise | Medium | Signing ≠ encryption; compartmentalized |
| TM-012 | Libsodium binding integrity | Low | SHA-256 pinned build, constant-time C code |
| TM-013 | Key Hash collision | Low | ~2⁴⁸ birthday bound; full keys verified in handshake |
| TM-014 | Sybil attacks | Medium | 30% per-neighbor route cap, handshake rate limit, connection slot limits. **Fundamental limitation:** permissionless BLE mesh cannot fully prevent Sybil. Documented for consumer awareness. |

## 8. Compliance

### Export Controls (ECCN 5D002)

MeshLink uses X25519, ChaCha20-Poly1305, and Ed25519 — classified under **ECCN 5D002** (15 CFR Part 774, Category 5 Part 2).

| Distribution form | EAR path | Requirements |
|-------------------|----------|--------------|
| **Source code** (public GitHub repo) | §740.13 (TSU) | **None.** §740.13(e) notification was eliminated by the 2021 BIS rule (86 Fed. Reg. 16482). Publicly available source code requires no filing. |
| **Object code** (standalone AAR/XCFramework) | §740.17(b)(2)(i) | ERN via SNAP-R + classification request (30-day BIS review) + semi-annual sales report. Applies when distributing the library as a compiled artifact independently (e.g., Maven Central AAR). |
| **Object code** (embedded in consumer app) | §740.17(b)(1) | ERN via SNAP-R + annual self-classification report. Applies when end-user apps ship MeshLink inside their APK/IPA. **This is the app developer's responsibility, not MeshLink's.** |

**EU Dual-Use:** Open-source software published on GitHub is excluded from EU dual-use controls entirely (Regulation (EU) 2021/821, Article 2(1) + General Software Note). No EU notification or authorization required. The 2025 EU Dual-Use list update (Commission Delegated Regulation (EU) 2025/2003, effective Nov 15, 2025) made no changes to Category 5 Part 2 encryption controls.

**Prohibited destinations:** Export prohibited to Country Group E:1/E:2 (Cuba, Iran, North Korea, Syria) regardless of license exception.

Distribute `EXPORT_CONTROL.md` with every release summarizing the classification and consumer obligations.

### BLE Spectrum (ETSI EN 300 328)
`regulatoryRegion` config: `EU` (min 300ms ad interval, 70% scan cap) or `UNRESTRICTED`.

### GDPR

**Key Hash classification (updated per ECJ C-413/23 P, September 2025):** The ECJ adopted the **relative approach** — pseudonymized data is not automatically personal data for all parties. Whether Key Hash is personal data depends on context:
- **For the MeshLink library itself:** Key Hash is a truncated 96-bit hash of a BLE public key. The library does not map Key Hashes to natural persons. Under the relative approach, Key Hash is likely **not personal data** within the library's processing context.
- **For app developers:** If an app creates a mapping (e.g., user profile → Key Hash), the Key Hash becomes personal data **for that controller.** The app developer is the data controller, not MeshLink.
- **For third parties** (other mesh peers who only see Key Hashes): Not personal data — they lack means to re-identify.

**Regardless of classification,** MeshLink provides erasure mechanisms as defense-in-depth:
- `forgetPeer(peerId)` — erase all stored data for a specific peer (trust store, replay counters, rotation nonces, buffered messages, routing table entries)
- `factoryReset()` — erase all identity keys, trust store, replay counters, rotation nonces
- 90-day TOFU pin expiry — automatic data minimization
- Apps should provide privacy impact assessment when creating user → Key Hash mappings

### App Store
- **iOS:** `NSBluetoothAlwaysUsageDescription` required. Justify background BLE. Set `ITSAppUsesNonExemptEncryption = YES` in `Info.plist` — MeshLink uses non-standard encryption (ChaCha20-Poly1305/X25519/Ed25519), which is not exempt. Provide Export Compliance Documentation in App Store Connect. France deployments may require ANSSI declaration.
- **Android:** BLE permission rationale required. No location permission needed on API 29+. Complete Data Safety form accurately. Google Play has no encryption-specific declaration requirement — US EAR compliance is the developer's responsibility.

## 9. RFC Reference Map

**Crypto:** RFC 2104 (HMAC), RFC 5869 (HKDF), RFC 6234 (SHA), RFC 7539/8439 (ChaCha20-Poly1305), RFC 7748 (X25519), RFC 8032 (Ed25519)

**Routing:** RFC 8966 (Babel — selected), RFC 1058/1075/2328/2453/3561/4271 (evaluated, rejected)

**Other:** RFC 9147 §4.5.3 (DTLS 1.3 replay window; obsoletes RFC 6347), RFC 7435 (TOFU), Noise Protocol Framework
