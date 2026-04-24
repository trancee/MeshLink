# 14 — Post-v1 Candidates

> **Purpose:** Consolidated list of deferred features. Not part of v1 scope. Preserved for future planning.
> **Dependencies:** All v1 spec files.

---

## Security & Privacy

| Candidate | Rationale for Deferral | Source |
|-----------|----------------------|--------|
| **Noise IK** (2-message fast reconnect) | Saves ~80ms on 28s connection budget (<0.3%). IK→XX fallback on key rotation adds a code path exercised in the most critical scenario. One handshake for v1. | 05 |
| **Sealed sender headers** | Encrypt origin/destination in Routed Messages to prevent relay social-graph inference. Requires relay cooperation protocol. | 06 |
| **Onion routing** | Each hop knows only next hop. Major protocol redesign. | 06 |
| **Post-quantum cryptography** | Replace X25519 with ML-KEM-768 hybrid (X25519 + ML-KEM) and Ed25519 with ML-DSA-65 when post-quantum standards mature on mobile. ML-KEM adds ~1,184B to key exchange; ML-DSA signatures are ~3,293B vs Ed25519's 64B — significant for BLE MTU. `CryptoProvider` interface already abstracts algorithms cleanly, enabling swap without protocol redesign. Signal shipped PQXDH (X25519 + ML-KEM-768) in September 2023; broader ecosystem migration expected by ~2029–2035. | 01, 05 |
| **Constant-rate cover traffic** | Prevents timing-based traffic analysis. Significant battery cost on BLE. | 06 |
| **Traffic-analysis-resistant padding** | Deferred alongside compression removal (CRIME/BREACH risk). Revisit with sealed sender. | 06 |

## Transport & Performance

| Candidate | Rationale for Deferral | Source |
|-----------|----------------------|--------|
| **BLE 5.3 Connection Subrating** | Allows fast idle↔active transitions without renegotiation. Limited device support in 2024–2025. | 12 |
| **Bluetooth 6.0 Channel Sounding** | Centimeter-level distance measurement between peers. Could enhance mesh topology decisions (prefer physically closer peers), improve routing metrics, and enable proximity-based trust policies. Requires BLE 6.0 hardware — early chipsets shipping 2025–2026 but not yet widespread on Android/iOS. | 03 |
| **Compression** | CRIME/BREACH oracle risk when compressing before encryption. DEFLATE on small BLE payloads rarely helps. Revisit with traffic-analysis-resistant padding. | 05 |
| **Native benchmarks** (Kotlin/Native) | kotlinx-benchmark JMH is JVM-only. Native perf characteristics differ but v1 ships JVM benchmarks first. | 12 |
| **Wi-Fi Aware (NAN) secondary transport** | Wi-Fi Aware / Neighbor Awareness Networking enables high-bandwidth (~10 Mbps) device-to-device communication without AP. Android supports NAN since API 26. Apple has not implemented NAN as of 2026. If Apple ever supports it, MeshLink could use Wi-Fi Aware as a secondary high-bandwidth transport alongside BLE for large transfers. | 01 |

## Scalability

| Candidate | Rationale for Deferral | Source |
|-----------|----------------------|--------|
| **Safety number expansion** (12 → 20+ digits) | Current 12 digits (~40 bits) sufficient for BLE mesh sizes (<1,000 peers). If MeshLink scales beyond BLE-range meshes, increase for stronger collision resistance. | 06 |
| **Group messaging** | v1 is 1:1 encrypted + flood-broadcast. Group key management (MLS, Sender Keys) is a separate protocol layer. | 01 |

## Observability & Tooling

| Candidate | Rationale for Deferral | Source |
|-----------|----------------------|--------|
| **OpenTelemetry Kotlin KMP bridge** | The OTel Kotlin SDK was accepted by CNCF in March 2026 (Embrace donation) with Tracing and Logging APIs in Development status. When the SDK reaches Stable, provide an optional `meshlink-otel` bridge artifact that adapts `DiagnosticEvent` → OTel `LogRecord` and `MeshHealthSnapshot` → OTel metrics. Core library remains dependency-free. | 11 |
| **Kotlin Swift Export** (replace SKIE) | Swift Export is enabled by default since Kotlin 2.2.20 but remains Experimental (targeting Alpha per Feb 2026 roadmap, KT-80305). As of Kotlin 2.3.20 (March 2026): no Flow→AsyncSequence, no sealed class→Swift enum, no default arguments, suspend functions "limited." SKIE provides all of these today. When Swift Export reaches Stable with Flow and sealed class support (est. 2027), migrate from SKIE. Migration should be transparent to Swift consumers. | 13 |
| **Built-in Kotlin ABI validation** (replace BCV plugin) | Kotlin Gradle plugin includes `abiValidation` since 2.1.20 (`@ExperimentalAbiValidation`). kotlinx-coroutines and kotlinx-io already migrated to it. Still experimental with known bugs (KT-78625). When Stable, migrate from standalone BCV plugin to built-in. | 13 |
