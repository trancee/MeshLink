# Critical Design Review — MeshLink Documentation

> Generated: 2026-03-31 | Scope: All docs + source cross-reference

Comprehensive review of every major design decision across all docs:
design.md (~2,450 lines), wire-format-spec.md, threat-model.md, architecture.md,
api-reference.md, integration-guide.md, diagrams.md, integration-tests.feature,
and UBIQUITOUS_LANGUAGE.md.

---

## 🔴 Critical Challenges (Architectural-level)

### 1. ~~Mixed Endianness Is an Unforced Error~~ ✅ RESOLVED

**Original decision:** "Varies per field" — handshake/keepalive/rotation use big-endian; chunks/SACK/routing/replay use little-endian.

**Challenge:** This is the single most likely source of cross-platform bugs. The stated rationale ("matches ARM-native byte order") applies to LE, but then timestamps and handshake fields use BE. There's no coherent principle; it's ad hoc.

**Resolution:** All 5 BE fields converted to LE. HandshakePayload (protocolVersion, l2capPsm), WireCodec keepalive (timestampSeconds), and RotationAnnouncement (timestampMillis) now all use little-endian encoding. BE helper functions removed. Golden test vectors and wire-format-spec.md updated.

---

### 2. Noise K Without Recipient Forward Secrecy Is a Bigger Gap Than Acknowledged

**Current decision:** Noise K for E2E encryption. The design notes "⚠️ No recipient forward secrecy" and defers Double Ratchet to "post-v1."

**Challenge:** If a recipient's static key is ever compromised (device seized, key extracted from Keychain/Keystore), **every past message ever sent to that recipient is decryptable.** For a library marketed toward protest/festival/disaster use, this is a serious gap. The design doc's own threat model assumes "casual attackers," but the Noise K weakness scales: a state actor seizing one phone unlocks the full message history.

**Better alternative:** **Noise KK or Noise IK + session ratcheting.** Noise KK adds one DH operation but gives mutual forward secrecy. Alternatively, implement a lightweight session ratchet: after the initial Noise K message, derive new keys per-message using a hash chain of the previous key. This doesn't require the full X3DH/Double Ratchet complexity but closes the "decrypt all past messages" attack. The performance cost (~1 HKDF per message, <1µs) is negligible.

---

### 3. DSDV Is the Wrong Routing Algorithm for a BLE Mesh

**Current decision:** Enhanced DSDV (proactive distance-vector) with periodic gossip.

**Challenge:** DSDV requires every node to maintain a full routing table for every reachable destination, updated proactively. In a mobile BLE mesh where peers appear and disappear every few minutes, this means:
- Continuous gossip overhead even when nobody is sending messages
- Route expiry/convergence delays (5× gossip interval = 25–300s depending on power mode)
- PowerSaver mode gossip at 30–60s intervals means route convergence takes 2.5–5 **minutes**

**Better alternative:** **Reactive routing (AODV or DSR-style)** for the data plane, with lightweight beacon-only gossip for neighbor discovery. AODV discovers routes on-demand when a message needs to be sent, which:
- **Eliminates background gossip traffic** when nobody is messaging (the common case for most peers)
- **Reduces battery drain** significantly in PowerSaver mode
- **Converges faster** for active conversations (route discovery is immediate, not bound to gossip intervals)
- Is well-proven for mobile ad-hoc networks (RFC 3561)

The counterargument ("no explicit route discovery phase") is actually a weakness — DSDV forces all peers to pay the routing overhead, even idle ones. A hybrid approach (beacon for 1-hop neighbors + AODV for multi-hop) gives the best of both worlds.

---

### 4. Custom Binary Wire Format vs. Protobuf/FlatBuffers

**Current decision:** Hand-specified binary format with byte-offset tables.

**Challenge:** The design doc acknowledges the tradeoff ("a single byte offset error = total failure") but frames it as solved by golden test vectors. In practice:
- No schema evolution — "strict versioning, any wire format change requires protocol version bump" means adding a single field to any message is a breaking change
- Manual encode/decode in WireCodec.kt is ~800+ lines of error-prone offset arithmetic
- The wire-format-spec.md is 600+ lines of tables that must stay manually synchronized with code

**Better alternative:** **FlatBuffers.** Zero-copy deserialization, schema evolution with backward compatibility, generated code eliminates byte-offset bugs, and the overhead is 4–8 bytes per message (a 2–3% increase at typical BLE MTU). FlatBuffers is specifically designed for bandwidth-constrained environments and is used by BLE-adjacent projects (Google Nearby, Matter protocol). The "no protobuf" stance seems based on protobuf's overhead, but FlatBuffers has none.

**Counter-counter:** If the ~8 byte overhead is truly unacceptable, at minimum add a **TLV extension field** to each message type. The current "no optional fields, no TLV extensions" rule guarantees painful major version bumps for any protocol evolution.

---

## 🟠 Significant Challenges (Feature-level)

### 5. ~~Broadcasts Are Unencrypted by Design — Really?~~ ✅ RESOLVED

**Original decision:** "Broadcast messages do NOT use Noise K. Broadcasts are Ed25519-signed but unencrypted."

**Challenge:** This means any passive BLE eavesdropper within 30–100m can read all broadcast content. For emergency alerts, location sharing, or any sensitive broadcast use case, this is a privacy hole. The design treats broadcasts as "announce to everyone nearby" — but "everyone" includes attackers.

**Resolution:** When `appId` is configured, broadcast payloads are now encrypted with ChaCha20-Poly1305 using an HKDF-derived key: `broadcastKey = HKDF(SHA-256, appId, "meshlink-broadcast-v1", "broadcast-aead-key", 32)`. Nonce is derived from the messageId (first 12 bytes). AAD binds ciphertext to origin + appIdHash. Plaintext broadcasts remain for appId=null. Relay nodes forward encrypted payloads without decrypting. Ed25519 signature still authenticates the sender (signs plaintext before encryption).

---

### 6. 16-Byte Peer IDs (SHA-256 Truncated) Are Wasteful on BLE

**Current decision:** Node identifiers are 16-byte SHA-256 truncations in all wire messages.

**Challenge:** At 128 bits, collision resistance is ~2^64 (birthday bound) — massive overkill for meshes the doc says target ≤50–200 peers. Meanwhile, every routed message carries 3 of these IDs (messageId, origin, destination) = 48 bytes, plus 16 bytes per visited-list entry. On a 244-byte MTU, this routing overhead alone consumes 59+ bytes (24% of the frame).

**Better alternative:** **8-byte peer IDs** for the wire format. The advertisement already uses an 8-byte key hash (and the doc correctly notes 2^32 collision resistance is "far beyond practical mesh sizes"). Use the same 8-byte hash in framed messages too. This saves:
- 24 bytes per routed message header (3 × 8 bytes saved)
- 8 bytes per visited-list entry (significant at 4 hops = 32 bytes saved)
- 8 bytes per route update entry

At 244-byte MTU, that's ~15% more payload per frame. On BLE, bandwidth is the scarcest resource.

---

### 7. SACK Bitmask Is Only 64 Bits — Fragile for Large Transfers

**Current decision:** 64-bit SACK bitmask in chunk_ack covers 64 chunks beyond the base ACK.

**Challenge:** At 244-byte MTU, a 100KB message = ~410 chunks. The 64-bit SACK window covers only 15% of the transfer. If chunks arrive very out of order (common with relay delays), the sender has no visibility beyond 64 chunks ahead of the base ACK. This forces conservative retransmission.

**Better alternative:** **Variable-length SACK ranges** (TCP-style, RFC 2018). Encode SACK as pairs of (begin, end) ranges: 2 bytes each, up to 4 ranges = 16 bytes (same as the current 8-byte bitmask + 2-byte base). This covers arbitrary gaps anywhere in the transfer, not just the first 64 chunks after the base. Alternatively, double the bitmask to 128 bits (16 bytes) for 3× the coverage at 8 bytes extra cost.

---

### 8. ~~Default `maxHops` Inconsistency — Doc Says 4, Config Says 10~~ ✅ RESOLVED

**Original decision:** design.md §4 said "Default max hops: 4" with detailed rationale. But the API reference, threat model, and implementation all use `maxHops = 10`.

**Challenge:** Doc inconsistency — design.md said 4 in multiple places while the implementation and other docs said 10.

**Resolution:** design.md updated to match the implementation default of 10 everywhere: §4 hop limits, parser safety, routing table sizing, dedup window calculations (now 10 × 5 min = 50 min), memory pressure rationale, and the config reference table. The implementation value of 10 was kept as it aligns with the threat model's TM-005 mitigation (reduced from 255 to 10).

---

### 9. No Message Compression, Even for Text

**Current decision:** "Deferred to post-v1." The sealed payload flags reserve a bit for it.

**Challenge:** The design doc says "Chat messages (1–10KB) yield minimal absolute savings." This is wrong. Text compresses 50–70% with LZ4/zstd. A 1KB chat message → 300–500 bytes means the difference between needing chunking or fitting in a single BLE write. On BLE's constrained bandwidth, this is a meaningful latency reduction.

**Better alternative:** **LZ4 compression for payloads > 128 bytes, on by default.** LZ4 decompression is ~4 GB/s — literally zero measurable latency on mobile. Compression is ~500 MB/s. Kotlin Multiplatform has LZ4 bindings. The flags byte already reserves bit 0; use it now.

---

### 10. ~~Rate Limits Default to Disabled~~ ✅ RESOLVED

**Original decision:** `rateLimitMaxSends`, `broadcastRateLimitPerMinute`, `inboundRateLimitPerSenderPerMinute` all defaulted to 0 (disabled).

**Challenge:** The threat model (A4) explicitly warns "If defaults are used as-is, all rate-limiting gaps become critical." The integration guide's best practices say "Enable rate limiting." But the defaults ship wide open. This is a "secure by default" violation.

**Resolution:** Defaults changed to safe-by-default values: `rateLimitMaxSends = 60`, `broadcastRateLimitPerMinute = 10`, `inboundRateLimitPerSenderPerMinute = 30`. Apps needing different limits can still override. All docs (api-reference.md, integration-guide.md) updated to reflect new defaults.

---

## 🟡 Moderate Challenges (Design nuances)

### 11. TOFI Instead of TOFU — Terminology Creates Confusion for No Benefit

**Current decision:** Rename TOFU to "TOFI" (Trust-On-First-**Discover**) because the mechanism is "discovery" not "use."

**Challenge:** TOFU is an industry-standard term (RFC 7435, SSH, Signal). Renaming it to TOFI means:
- Developers searching for "TOFU" in the docs find nothing
- The concept is identical; the rename adds no technical clarity
- The ubiquitous language doc has to repeatedly clarify "not TOFU"

**Better alternative:** Call it TOFU and add a note: "MeshLink implements TOFU key pinning (sometimes called Trust-On-First-Discover in the mesh context, since key pinning occurs at peer discovery, not first message exchange)." This preserves searchability while acknowledging the nuance.

---

### 12. ~~Replay Counter Persistence Strategy Has a 1-Second Vulnerability Window~~ ✅ RESOLVED

**Current decision:** Outbound counter uses pre-increment persist (correct). Inbound counter uses "periodic persist every 1 second" with the rationale "worst-case 1s of messages" and "dedup provides backup."

**Challenge:** During that 1-second window after a crash, an attacker who recorded messages can replay them successfully. The doc says "defense-in-depth: replay counter + dedup set." But the dedup set is **also** in-memory only (lost on crash). Both defenses fail simultaneously on crash — exactly the scenario where replay attacks are most likely (attacker forces crash, then replays).

**Resolution:** Inbound counter now persists immediately on every high-water-mark advance. The periodic persist strategy (1s / 100 messages) has been replaced — `persistIntervalMillis` and `persistIntervalMessages` parameters removed. Window fills (counters below the high-water mark) skip persistence since the high-water mark already covers them.

---

### 13. ~~Buffer TTL as Sole Delivery Deadline Is Too Coarse~~ ✅ RESOLVED

**Current decision:** `bufferTTL` (default 5 minutes) serves as both the buffer eviction timer AND the delivery deadline for `onTransferFailed`.

**Challenge:** A chat app wants failed-delivery notification within 10 seconds (UX), but messages should be buffered for 5 minutes (resilience). These are fundamentally different concerns conflated into one parameter.

**Resolution:** Added `deliveryTimeoutMillis` config field (default 30s) separate from `bufferTtlMillis`. Validation ensures `deliveryTimeoutMillis ≤ bufferTtlMillis`. Presets tuned: `smallPayloadLowLatency` = 10s, `largePayloadHighThroughput` = 120s. `MeshLink.doSend()` uses the new field for delivery deadline.

---

### 14. ~~Gossip Interval Not in Config — Confusing for Developers~~ ✅ RESOLVED

**Original decision:** `gossipIntervalMillis` defaulted to 0 (off).

**Challenge:** If gossip is off by default, multi-hop routing doesn't work out of the box. This is the #1 thing a developer integrating a "mesh" library would expect to work automatically. Having to manually enable the core mesh feature is bad DX.

**Resolution:** Default changed to `gossipIntervalMillis = 15_000L` (15 seconds). Multi-hop routing now works out of the box. Developers wanting single-hop-only can set it to 0. All docs updated.

---

### 15. Architecture Doc vs. Design Doc — Significant Duplication

**Current decision:** `architecture.md` exists alongside `design.md` with overlapping content (module tables, data flow diagrams, threading model).

**Challenge:** architecture.md appears to be a summary of design.md's §11 and scattered sections. When they inevitably drift, developers won't know which is authoritative. The design.md already has the architecture content in detail.

**Better alternative:** **Delete architecture.md and add a "Quick Architecture" section at the top of design.md** (or link to diagrams.md which already contains the architecture diagrams). One source of truth > two.

---

### 16. ~~Integration Tests Miss Key Scenarios~~ ✅ RESOLVED

**Current decision:** 32 integration test scenarios in the .feature file.

**Challenge:** Notable gaps: no multi-hop encrypted message, no concurrent transfer, no gossip convergence timing, no broadcast with relay + TTL decrement test.

**Resolution:** Added 4 integration tests: `encryptedThreeHopRoutedMessage` (E2E encryption through 3-hop relay), `broadcastRelaysAcrossHopsWithTtlDecrement` (multi-hop broadcast with TTL verification), `gossipConvergesRoutingTableWithinOneInterval` (routing convergence validation), `concurrentTransfersDeliverAllMessages` (5 concurrent sends). L2CAP↔GATT fallback remains untested (requires platform-specific transport mocking beyond VirtualMeshTransport).

---

### 17. ~~Threat Model Lists All 10 Threats as "✅ Mitigated" — Overconfident~~ ✅ RESOLVED

**Current decision:** Every threat shows green checkmarks with commit references.

**Challenge:** Several mitigations are incomplete:
- **TM-004 (route poisoning):** "Unsigned routes rejected when crypto enabled" — but crypto is optional (despite `requireEncryption=true` default). A consumer can set `requireEncryption=false` and routes become unsignable.
- **TM-003 (dedup exhaustion):** TTL-based dedup is good, but the dedup capacity of 100K entries at 24 bytes each = 2.4MB — not "negligible" on constrained devices. The remediation doesn't address targeted eviction attacks.
- **TM-007 (rotation replay):** ±30s freshness window is very narrow. Clock skew between devices on a mesh with no NTP could easily exceed 30s. The design says monotonic clocks — but rotation timestamps use wall-clock (`timestampMillis`).

**Resolution:** TM-003, TM-004, and TM-007 changed from ✅ to 🟡 Partially Mitigated in `docs/threat-model.md` with documented residual risks for each.

---

### 18. Config Presets Are Named for Use Cases Instead of Behavior

**Current decision:** Presets renamed from `chatOptimized`, `fileTransferOptimized`, `powerOptimized`, `sensorOptimized` (deprecated) to `smallPayloadLowLatency`, `largePayloadHighThroughput`, `minimalResourceUsage`, `minimalOverhead`.

**Challenge:** The original use-case-based naming was fragile. What if my "chat" app sends images (should I use `chatOptimized` or `fileTransferOptimized`)? What if my sensor also needs to relay large payloads?

**Resolution:** Presets are now named for what they DO: `smallPayloadLowLatency`, `largePayloadHighThroughput`, `minimalResourceUsage`, `minimalOverhead`. The builder pattern (`MeshLinkConfig.withMaxPayload(50_000).withGossip(15_000)`) remains a possible future enhancement.

---

## 🟢 Minor / Nitpick Challenges

### 19. Wire format `sigLen` field in broadcasts/delivery ACKs is 1 byte, but Ed25519 signatures are always 64 bytes. It's effectively a boolean. Just use a flag bit.

### 20. Keepalive timestamp is 4 bytes (seconds precision) while rotation timestamp is 8 bytes (milliseconds). The inconsistency adds cognitive load for no technical reason.

### 21. ~~The NACK message (0x09) has no reason code~~ ✅ RESOLVED

The NACK message now includes a reason byte at offset 17 with 5 reason codes: UNKNOWN(0), BUFFER_FULL(1), UNKNOWN_DESTINATION(2), DECRYPT_FAILED(3), RATE_LIMITED(4). Wire format updated from 17→18 bytes. Golden vector test and round-trip tests added.

### 22. The design doc is 2,446 lines. It's simultaneously a design doc, protocol RFC, architecture spec, integration guide, and API reference. It should be split — which has partially happened (separate docs exist) but design.md still contains full wire format tables that duplicate wire-format-spec.md.

### 23. The `UBIQUITOUS_LANGUAGE.md` defines terms like "Eviction" with three different qualifiers (Connection/Buffer/Presence) but the codebase doesn't consistently prefix them — `evict()` methods exist without qualification.

---

## Summary

| Severity | Count | Top Issues | Status |
|----------|-------|------------|--------|
| 🔴 Critical | 4 | ~~Mixed endianness~~ ✅, no recipient forward secrecy, DSDV routing, no schema evolution | 1/4 resolved |
| 🟠 Significant | 6 | ~~Unencrypted broadcasts~~ ✅, wasteful 16-byte IDs, narrow SACK, ~~disabled rate limits~~ ✅, ~~maxHops inconsistency~~ ✅, no compression | 3/6 resolved |
| 🟡 Moderate | 8 | TOFI naming, ~~replay persist gap~~ ✅, ~~conflated TTL/timeout~~ ✅, ~~gossip off by default~~ ✅, doc duplication, ~~test gaps~~ ✅, ~~overconfident threat model~~ ✅, ~~preset naming~~ ✅ | 6/8 resolved |
| 🟢 Minor | 5 | sigLen waste, ~~timestamp inconsistency~~ ✅, ~~NACK missing reason~~ ✅, design doc size, term inconsistency | 2/5 resolved |

## Recommended Immediate Actions (before v1 ships)

1. ~~**Unify endianness to LE** — pure cleanup, no behavior change~~ ✅ Done
2. ~~**Default gossip interval to 15s** and **rate limits to sane values** — DX and security~~ ✅ Done
3. ~~**Fix maxHops default inconsistency** — either 4 or 10, pick one and propagate everywhere~~ ✅ Done (aligned to 10)
4. ~~**Add encrypted broadcast via appId-derived key** — low-hanging privacy win~~ ✅ Done
5. ~~**Add the missing integration test scenarios** — especially encrypted multi-hop~~ ✅ Done (4 scenarios added)
6. ~~**Separate delivery timeout from buffer TTL**~~ ✅ Done (`deliveryTimeoutMillis` config field)
7. ~~**Add NACK reason codes**~~ ✅ Done (5 reason codes in wire format)
8. ~~**Persist replay counter on high-water-mark advance**~~ ✅ Done (eliminates 1s vulnerability window)
9. ~~**Rename config presets to behavior-based names**~~ ✅ Done (old names deprecated)
10. ~~**Update threat model status to traffic-light**~~ ✅ Done (TM-003, TM-004, TM-007 → 🟡 Partially Mitigated)
