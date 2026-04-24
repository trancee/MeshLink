# MeshLink Spec — Chunked for AI Consumption

14 self-contained files split from `GREENFIELD_SPEC.md` (~3,500 lines), then reviewed and improved.
See `CHANGES.md` for the full changelog.

## Reading Order

Start with **01** (vocabulary), then **02** (architecture), then any section relevant to the task.

| # | File | Scope | Key Topics |
|---|------|-------|------------|
| 01 | [01-vision-and-domain-model.md](01-vision-and-domain-model.md) | §1–2 | **Read first.** Vision, scope, all canonical terms |
| — | [01-research-alternatives.md](01-research-alternatives.md) | Research | Alternatives analysis: 9 technical choices evaluated against modern options |
| 02 | [02-architecture.md](02-architecture.md) | §3 | KMP strategy, module layout, engine/coordinator pattern |
| — | [02-research-alternatives.md](02-research-alternatives.md) | Research | Alternatives analysis: 10 architectural choices evaluated |
| 03 | [03-transport-ble.md](03-transport-ble.md) | §4 | BleTransport, GATT, L2CAP, advertisements, connection strategy, bootstrap mode |
| — | [03-research-alternatives.md](03-research-alternatives.md) | Research | Alternatives analysis: 11 BLE transport choices evaluated; SIG Mesh comparison |
| 04 | [04-wire-format.md](04-wire-format.md) | §5 | All 12 message types (FlatBuffers), binary layouts, versioning |
| — | [04-research-alternatives.md](04-research-alternatives.md) | Research | Alternatives analysis: 10 wire format choices evaluated; KMP FlatBuffers strategy |
| 05 | [05-security-encryption.md](05-security-encryption.md) | §6.1–6.4 | CryptoProvider, Noise XX handshake, Noise K seal (full per message) |
| — | [05-research-alternatives.md](05-research-alternatives.md) | Research | Alternatives analysis: 9 encryption choices + post-quantum readiness assessment |
| 06 | [06-security-identity-trust.md](06-security-identity-trust.md) | §6.5–6.11 | Identity, key rotation, TOFU (2 modes), replay protection, metadata |
| — | [06-research-alternatives.md](06-research-alternatives.md) | Research | Alternatives analysis: 10 identity/trust choices + Key Transparency future assessment |
| 07 | [07-routing.md](07-routing.md) | §7 | Babel, feasibility, composite metric, visited list, dedup, buffering, cut-through |
| — | [07-research-alternatives.md](07-research-alternatives.md) | Research | Alternatives analysis: 12 routing choices evaluated; AREDN Babel migration, DTN, Sybil |
| 08 | [08-transfer.md](08-transfer.md) | §8 | Chunking, SACK, observation-based send rate, transfers, resume, message size guidance |
| — | [08-research-alternatives.md](08-research-alternatives.md) | Research | Alternatives analysis: 9 transfer choices evaluated; BLE throughput, TCP inapplicability, resume |
| 09 | [09-messaging.md](09-messaging.md) | §9 | send()/broadcast(), rate limiting, delivery semantics |
| — | [09-research-alternatives.md](09-research-alternatives.md) | Research | Alternatives analysis: 10 messaging choices evaluated; Meshtastic ACK confusion, delivery semantics |
| 10 | [10-power-and-presence.md](10-power-and-presence.md) | §10 | 3 fixed power tiers, scan duty, presence detection, peer lifecycle |
| — | [10-research-alternatives.md](10-research-alternatives.md) | Research | Alternatives analysis: 9 power/presence choices evaluated; hysteresis, scan duty, eviction |
| 11 | [11-diagnostics.md](11-diagnostics.md) | §11 | DiagnosticSink, 25 event codes, MeshHealthSnapshot |
| — | [11-research-alternatives.md](11-research-alternatives.md) | Research | Alternatives analysis: 8 diagnostics choices evaluated; OTel KMP SDK tracked as post-v1 bridge |
| 12 | [12-platform-and-testing.md](12-platform-and-testing.md) | §12–13 | Per-platform details, hardware quirks, VirtualMeshTransport, benchmarks, coverage, pre-commit hooks |
| — | [12-research-alternatives.md](12-research-alternatives.md) | Research | Alternatives analysis: 9 platform/testing choices evaluated; Block ktfmt migration, KMP testing patterns |
| 13 | [13-distribution-api-compliance.md](13-distribution-api-compliance.md) | §14–15 | Public API, lifecycle FSM, config, threat model, compliance |
| — | [13-research-alternatives.md](13-research-alternatives.md) | Research | Alternatives analysis: 11 distribution/API/compliance choices; BCV gap identified, Swift Export tracked |
| 14 | [14-future.md](14-future.md) | — | Post-v1 candidates (consolidated). Not v1 scope. |

## Dependency Graph

```
01 (terms)
├── 02 (architecture)
│   └── 12 (platform + testing)
├── 03 (transport)
│   └── 12 (platform + testing)
├── 04 (wire format)
│   ├── 05 (encryption)
│   │   └── 06 (identity + trust)
│   ├── 07 (routing)
│   ├── 08 (transfer)
│   └── 09 (messaging)
├── 10 (power + presence)
├── 11 (diagnostics)
├── 13 (API + distribution)
└── 14 (future — no deps, read optionally)
```

## Key Simplifications from Original Spec

1. **Bloom filter removed** — visited list alone prevents loops with zero false positives
2. **Session ratchet removed** — full Noise K per message; DH cost is <1% of BLE latency
3. **Noise IK removed** — one handshake code path instead of two
4. **Compression removed** — CRIME/BREACH risk; DEFLATE unhelpful on small payloads
5. **FlatBuffers for all messages** — one codec, one evolution strategy, TLV system removed
6. **Fixed power tiers** — 3 tiers with hysteresis, not continuous interpolation
7. **Fixed buffer/dedup sizing** — allocate at startup, no elastic grow/shrink
8. **Fixed TTL per priority** — HIGH=45min, NORMAL=15min, LOW=5min
9. **Observation-based send rate** — no RSSI coupling to transfer engine

## Review Pass Fixes

1. **origination_time → wall clock** — monotonic clocks are device-local, incomparable across peers
2. **Delivery ACK restored as standalone 0x0B** — was contradictory (folded vs defined)
3. **Pre-handshake gate blocks Routed Message** — requires Noise XX for hop-by-hop decryption
4. **Metric wire encoding specified** — Double × 100 → uint16, with retraction sentinel
5. **Stale module references removed** — TlvCodec, Compressor, AimdController, hand-specified codecs
6. **Handshake payload exception documented** — raw bytes inside Noise message, not FlatBuffers
7. **Pseudonym rotation staggered** — per-peer offset prevents thundering herd
8. **Safety number entropy documented** — 12 digits intentional for BLE mesh scale
9. **HKDF info string versioned** — `MeshLink-v1-NoiseK-seal` prevents cross-version key reuse
10. **Route digest uses FNV-1a** — not SHA-256; consistency check, not security function
11. **Dedup time bound simplified** — max buffer TTL (45 min), not maxHops × bufferTtl
12. **Buffer sizing rationale documented** — 1 MB intentionally conservative, presets for more
13. **Hop limit behavior at destination specified** — deliver if at destination, drop if at relay
14. **appId added to config** — mesh isolation key, was unspecified
15. **BLE permission revocation specified** — RUNNING → RECOVERABLE, transfers abort, buffers retained
16. **Post-v1 candidates consolidated** — moved to `14-future.md`, reducing noise in v1 files
17. **macOS and Linux targets removed** — shipping platforms are Android + iOS only
18. **JVM reclassified as test/build infrastructure** — runs commonTest, Kover, benchmarks; not a shipping target
19. **Advertisement byte 0 repacked** — 3-bit `protocolVersion` + 2-bit `powerMode` + 3-bit reserved (was 4+4, wasted bits)
20. **L2CAP-first connection model** — peers open L2CAP directly when PSM advertised, skipping GATT; GATT becomes fallback
