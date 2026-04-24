# Spec Review — Agreed Changes

Applied across all `spec/` chunks. Reference this when checking what changed from the original `GREENFIELD_SPEC.md`.

## Simplifications (Removed)

| # | Change | Rationale | Affected Files |
|---|--------|-----------|----------------|
| S1 | **Remove Bloom filter** from Routed Messages | Visited list alone is sufficient. 32B overhead per message, interop hash spec, and secondary code path removed. Linear scan of ≤120 bytes is trivially fast. | 01, 04, 07 |
| S2 | **Remove session ratchet** (Noise K) | Full Noise K seal per message is ~0.1–0.5ms; BLE transmission is 50–200ms. Ratchet saved µs for massive complexity: desync recovery, NACK reason 5, look-ahead, epoch, re-key storms, old-chain retention. | 01, 04, 05, 06, 09, 10, 11, 13 |
| S3 | **Remove Noise IK** fast reconnection | Saves ~80ms on a 28s connection budget (<0.3%). One handshake code path instead of two. IK fallback race on key rotation removed. | 05 |
| S4 | **Remove probabilistic broadcast relay suppression** | TTL=2 with 5 neighbors is 10–15 messages. Dedup + maxHops + rate limiting already prevent amplification. Non-determinism makes debugging harder. | 09 |
| S5 | **Remove compression** for v1 | CRIME/BREACH oracle risk. DEFLATE on small payloads often increases size. Removes: compression envelope, paddingBlockSize, compressionMinBytes. Padding for traffic analysis deferred to v2 alongside sealed sender. | 04, 05, 13 |
| S6 | **Remove elastic buffer/dedup sizing** | Fixed allocation at startup. 2.8MB on 4–8GB device = 0.04% RAM. Presets handle constrained devices. Removes grow/shrink thresholds, timers, resize races. | 07, 10 |
| S7 | **Remove Soft Re-pin** trust mode | Contradicts spec's own "silent accept deliberately excluded" statement. Two modes: STRICT + PROMPT. | 01, 06 |
| S8 | **Remove ephemeral key pool** (DH cache) | Leaks communication patterns in memory. Speculative pre-computation for ~0.05ms savings on 50ms+ BLE link. Static-static cache retained. | 05 |
| S9 | **Remove RSSI-based send rate tiers** | Replace with observation-based: start 1 chunk (stop-and-wait), increase to 2 after 3 ACKs, to 4 after 6 more. No coupling between routing metrics and transfer. | 08 |
| S10 | **Fixed power tiers** replace continuous scaling | 3 tiers with ±2% hysteresis + 30s delay. Android/iOS scan hardware has limited granularity anyway. Connection count is integer. Removes lerp, adaptive scan reduction, integer connection hysteresis math. | 10 |
| S11 | **Compress GATT Peer ID Resolution** to 5 lines | Platform implementation detail, not protocol. | 03 |

## Improvements (Modified)

| # | Change | Rationale | Affected Files |
|---|--------|-----------|----------------|
| M1 | **FlatBuffers for all messages** | One codec strategy, one parsing library, one evolution mechanism. TLV extension system removed. 12–20B overhead acceptable even on GATT. | 04 |
| M2 | **Fixed TTL per priority** | HIGH=45min, NORMAL=15min, LOW=5min. Predictable. Pressure handled by existing 3-tier eviction. | 07 |
| M3 | **Route cost as Double** internally | 12KB difference at 2,000 routes is irrelevant. Eliminates fixed-point conversion, 6553.5 ceiling, rounding bugs. | 07 |
| M4 | **Safety number from Ed25519 only** | X25519 changes on rotation → confusing safety number change. Signal uses identity key only. | 06 |
| M5 | **Observation-based send rate** | Start 1 chunk, increase after successful ACKs. No RSSI coupling. | 08 |

## Additions

| # | Change | Rationale | Affected Files |
|---|--------|-----------|----------------|
| A1 | **Max message age** (`maxMessageAge`, default 30 min) | Prevents messages bouncing indefinitely through mesh. Checked at each relay via timestamp in Routed Message. | 04, 07 |
| A2 | **Mesh partition handling** | Routes go stale → retractions propagate → buffered messages NACKed with `unknownDestination` when route expires. Document the behavior. | 07 |
| A3 | **Concurrent rotation spec** | Two peers rotating simultaneously: each processes the other's announcement independently. Single-flight per peer prevents local race. | 06 |
| A4 | **First-launch bootstrap behavior** | Aggressive scan + shorter ad interval for first 60s. Configurable via `transport.bootstrapDurationMs`. | 03, 10, 13 |
| A5 | **Message size guidance** | Recommendation: text ≤10KB, small images 10–50KB, avoid >50KB multi-hop. | 08, 09 |
| A6 | **Document Sybil limitations** | Permissionless BLE mesh has fundamental Sybil limits. Document + existing 30% cap + handshake rate limit. | 07, 13 |
| A7 | **Routing table query API** | Read-only `routingSnapshot()` for debugging mesh issues. | 13 |
| A8 | **L2CAP force override config** | `transport.forceL2cap` / `transport.forceGatt` for testing. | 03, 13 |
| A9 | **Document counter gap on stop()** | Pre-incremented replay counter for unsent messages wastes counter values. Harmless (uint64) but should be documented. | 06 |
| A10 | **Benchmark tests** | kotlinx-benchmark suites for crypto, wire format, routing, transfer, dedup. JVM/JMH with stored baselines. CI warns on regression. | 12 |
| A11 | **detekt + ktfmt pre-commit hook via prek** | Deterministic formatting (ktfmt) + static analysis (detekt) enforced before every commit. Eliminates style debates and catches issues pre-review. | 12 |
| A12 | **100% code coverage per slice** | Kover enforces 100% line + branch coverage on production code. Verified before every commit via `koverVerify`. | 12 |
| A13 | **CI via GitHub Actions** | Two workflows: `ci.yml` (lint, test, coverage, android instrumented, benchmarks) on every push/PR; `release.yml` (publish Android + iOS) on version tags. Branch protection requires all CI jobs pass. | 12 |

## Review Pass Fixes

Bugs, contradictions, and AI-readability improvements found during second review.

| # | Fix | Category | Affected Files |
|---|-----|----------|----------------|
| R1 | **origination_time → wall clock** | Bug | 04, 07 |
| R2 | **Delivery ACK restored as standalone 0x0B** | Bug | 04 |
| R3 | **Pre-handshake gate blocks Routed Message** | Bug | 06 |
| R4 | **Metric wire encoding specified** (Double × 100 → uint16) | Bug | 04, 07 |
| R5 | **Stale module references removed** (TlvCodec, Compressor, AimdController) | Bug | 02 |
| R6 | **Handshake payload exception documented** (raw bytes, not FlatBuffers) | Bug | 04 |
| R7 | **Pseudonym rotation staggered per-peer** | Design | 06 |
| R8 | **Safety number entropy tradeoff documented** (12 digits intentional) | Design | 06 |
| R9 | **HKDF info string versioned** (`MeshLink-v1-NoiseK-seal`) | Design | 05 |
| R10 | **Route digest uses FNV-1a** (not SHA-256 — consistency check, not security) | Design | 07 |
| R11 | **Dedup time bound simplified** (max buffer TTL, not maxHops × bufferTtl) | Design | 07 |
| R12 | **Buffer sizing rationale documented** (1 MB conservative, presets for more) | Design | 07 |
| R13 | **Hop limit at destination specified** (deliver if self, drop if relay) | AI-readability | 07 |
| R14 | **appId added to MeshLinkConfig** (was missing from config) | AI-readability | 13 |
| R15 | **BLE permission revocation behavior specified** | AI-readability | 13 |
| R16 | **Post-v1 candidates consolidated into 14-future.md** | AI-readability | 05, 06, new 14 |
| R17 | **RFC comparison table compressed** (7-row table → one-liner) | AI-readability | 07 |
| R18 | **macOS and Linux targets removed** | Scope | 01, 02, 03, 06, 12, 13 |
| R19 | **JVM reclassified as test-only** (not a shipping target) | Scope | 01, 02, 12, 13 |
| R20 | **Advertisement byte 0 repacked** — `versionMajor` (4 bits) + `powerMode` (4 bits) → `protocolVersion` (3 bits) + `powerMode` (2 bits) + reserved (3 bits). Explicit encoding values for power mode. | Optimization | 03 |
| R21 | **L2CAP-first connection model** — when peer advertises `l2capPsm ≠ 0x00`, connection opens L2CAP directly (skipping GATT service discovery + MTU negotiation). GATT becomes fallback for `l2capPsm = 0x00` or L2CAP failure. Handshake runs over whichever transport is established. | Design | 03, 04 |

## Research Artifacts

| File | Scope | Result |
|------|-------|--------|
| `01-research-alternatives.md` | 9 domain-level technical choices | All validated; no changes |
| `02-research-alternatives.md` | 10 architectural choices | All validated; Kotlin 2.4 rich errors tracked as future watch |
| `03-research-alternatives.md` | 11 BLE transport choices + SIG Mesh comparison | All validated; 2 non-breaking refinements suggested (OEM probe cache key, BT 6.0 Channel Sounding tracking) |
| `04-research-alternatives.md` | 10 wire format choices + KMP FlatBuffers strategy | All validated; 1 minor doc fix (seq_num max comment); KMP implementation note on pure-Kotlin decoder approach |
| `05-research-alternatives.md` | 9 encryption choices + post-quantum readiness | All validated; PQ migration path documented (CryptoProvider interface already PQ-ready) |
| `06-research-alternatives.md` | 10 identity/trust choices + Key Transparency future | All validated; 2 non-breaking refinements (optional QR safety number, DTLS RFC reference update) |
| `07-research-alternatives.md` | 12 routing choices: Babel, metrics, loops, buffering, Sybil | All validated; no changes needed; AREDN Babel migration and 2025 BLE relay study cited |
| `08-research-alternatives.md` | 9 transfer choices: chunking, SACK, flow control, resume | All validated; no changes needed; TCP congestion control confirmed wrong model for BLE point-to-point |
| `09-research-alternatives.md` | 10 messaging choices: send API, broadcast, rate limiting, delivery, ordering | All validated; no changes needed; Meshtastic ACK confusion confirms two-tier model; FLP impossibility confirms at-most-once |
| `10-research-alternatives.md` | 9 power/presence choices: tiers, hysteresis, scan duty, eviction, lifecycle | All validated; no changes needed; Android scan throttling documented as implementation gotcha |
| `11-research-alternatives.md` | 8 diagnostics choices: SharedFlow surface, ring buffer, event catalog, payloads, health snapshot | All validated; OTel Kotlin KMP SDK (CNCF March 2026) tracked as post-v1 bridge candidate |
| `12-research-alternatives.md` | 9 platform/testing choices: VirtualTransport, coverage, ktfmt+detekt, benchmarks, CI | All validated; Block Engineering's ktfmt migration validates tooling; KMP testing patterns confirmed |
| `13-research-alternatives.md` | 11 distribution/API/compliance choices: SKIE, Maven Central, lifecycle, config, threat model, export controls, GDPR, Maestro | 10 validated; **Gap: add binary-compatibility-validator (BCV)**; SKIE correct for v1, track Swift Export as post-v1 |

## Subagent-Validated Fixes (Post-Research)

Applied after parallel subagent validation against primary sources (BIS eCFR, CJEU judgments, GitHub build configs, JetBrains docs).

| # | Change | Evidence | Affected Files |
|---|--------|----------|----------------|
| R-BCV | **Add binary-compatibility-validator** to build system + public API section | 5/5 major kotlinx libraries confirmed using BCV (coroutines, serialization, ktor, datetime, io). RevenueCat KMP SDK also uses it. | 13 |
| R-EXPORT | **Fix TSU notification — eliminated in 2021.** §740.13(e) is now [Reserved] per 86 Fed. Reg. 16482. Source code on public repos requires NO filing. Object code distribution as standalone library falls under §740.17(b)(2)(i) (ERN + classification request). | BIS eCFR §740.13(e) confirmed [Reserved]; BIS encryption controls page | 13 |
| R-GDPR | **Refine Key Hash classification** per ECJ C-413/23 P (September 2025). Relative approach: Key Hash is NOT personal data for parties lacking re-identification means; MAY be personal data for app developers with user→Key Hash mapping. | CJEU judgment ECLI:EU:C:2025:632; Skadden analysis | 13 |
| R-APPSTORE | **Add Apple `ITSAppUsesNonExemptEncryption = YES`** requirement. MeshLink uses non-standard encryption (not HTTPS/CryptoKit). Google Play has no equivalent. | Apple developer docs; App Store Connect encryption compliance flow | 13 |
| R-SWIFT-EXPORT | **Track Kotlin Swift Export as post-v1** SKIE replacement. Swift Export still Experimental (targeting Alpha, KT-80305). No Flow→AsyncSequence, no sealed class→enum as of 2.3.20. | kotlinlang.org docs; Kotlin 2.3.0/2.3.20 release notes; SKIE feature page; JetBrains Aug 2025 roadmap blog | 14 |
| R-ABI-BUILTIN | **Track built-in Kotlin ABI validation** as post-v1 BCV replacement. `@ExperimentalAbiValidation` since 2.1.20, known bugs (KT-78625). kotlinx-coroutines + kotlinx-io already migrated. | Kotlin Gradle plugin docs; kotlinx-coroutines convention plugin; kotlinx-io build-logic | 14 |

## Cross-Validation Fixes (Research Audit)

Applied after 4 parallel subagents audited all 13 research docs against their corresponding spec files.

| # | Change | Affected Files |
|---|--------|----------------|
| V-01 | **Fix type prefix range** `0x00–0x0A` → `0x00–0x0B` (12 types, not 11 — Delivery ACK 0x0B was missing from range) | 01-vision |
| V-02 | **Fix Noise K overhead** `33 bytes` → `48 bytes` (32B ephemeral + 16B AEAD tag — arithmetic was wrong in both docs) | 05-security, 05-research |
| V-03 | **Fix seq_num max transfer** `~16MB` → depends on chunk size: 64 MB at 1,024B chunks, 512 MB at 8,192B chunks | 04-wire-format |
| V-04 | **Fix RFC reference** `6347 §4.1.2.6` → `9147 §4.5.3` (DTLS 1.3, published April 2022, obsoletes RFC 6347) | 06-identity-trust, 13-distribution |
| V-05 | **Fix event code count** `25 total` → `26 total` (L2CAP_FALLBACK was added without updating header; 3 Critical + 8 Threshold + 15 Log) | 11-diagnostics |
| V-06 | **Fix BT SIG Mesh segment count** `11 segments` → `up to 32 segments` (11 × 12 = 132, not 380; ~32 segments is correct) | 03-research |
| V-07 | **Fix stale TSU text** in research — spec was corrected but research still said "File TSU notification" | 13-research |
| V-08 | **Fix choice count** `10 choices, 9 validated` → `11 choices, 10 validated` | 13-research |
| V-09 | **Add missing `14-future.md` entries** — post-quantum cryptography and Wi-Fi Aware (NAN) transport were referenced but missing | 14-future |
| V-10 | **Soften unverifiable citations** — removed specific author/institution names from 3 suspect paper references; replaced with general claims | 07-research, 08-research |
| V-11 | **Fix FLP citation** — FLP proves consensus impossibility, not delivery impossibility; added Two Generals Problem (1975) as the precise reference | 09-research |
