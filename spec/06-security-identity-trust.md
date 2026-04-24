# 06 — Security: Identity, Trust & Replay Protection

> **Covers:** §6.5–6.11 (Identity Management, Key Rotation, TOFU, Replay Protection, Crypto Requirements, Pre-Handshake Gate, Metadata Protection)
> **Dependencies:** `01-vision-and-domain-model.md` (Identity, Key Hash, TOFU, Key Pinning), `05-security-encryption.md` (Noise XX/K)
> **Key exports:** Identity lifecycle, rotation protocol, TOFU trust model, replay guard, pre-handshake gate, metadata protections
> **Changes from original:** S7 (Soft Re-pin removed), S2 (ratchet-related crypto reqs removed), M4 (safety number Ed25519 only), A3 (concurrent rotation), A9 (counter gap on stop). Review fixes: pre-handshake gate blocks Routed Message (requires Noise XX), pseudonym rotation staggered per-peer, safety number entropy tradeoff documented.

---

## 1. Identity Management

**Two independent static keypairs** generated on first launch:
1. **Ed25519** — broadcast signing, delivery ACK auth, rotation signing
2. **X25519** — Noise XX key agreement, Noise K E2E encryption

Independently generated (not derived from each other). Compromise of one does not expose the other.

**Peer ID (Key Hash):** `SHA-256(Ed25519Pub ‖ X25519Pub)[0:12]` — binds both identity components. Rotation of either key changes the peer ID.

### Secure Storage

| Platform | `actual` class |
|----------|---------------|
| Android | `AndroidSecureStorage` (`EncryptedSharedPreferences`, Android Keystore master key) |
| iOS | `IosSecureStorage` (Keychain Services) |

`sodium_memzero()` erases key material from memory (not reliant on GC).

### Key Storage Failure Recovery
1. Retry 3× with exponential backoff (100ms, 500ms, 2s)
2. `IOException` → retry; `SecurityException` → immediate failure
3. Exhausted → `Result.failure(IllegalStateException)`
4. **Never silently regenerate identity** — loss must be explicit

### Crypto Initialization
`start()` validates `CryptoProvider` available when `requireEncryption = true` (default). Missing → failure, state → `TERMINAL`.

## 2. Key Rotation

```kotlin
rotateIdentity(): Result<Unit>
```

**Single-flighted:** Concurrent calls → `Result.failure`. Nonce uses atomic CAS.

**Atomic rollback:** If any step fails after generating new keys → old keys retained, new keys erased, `ROTATION_FAILED` diagnostic. Nonce NOT incremented on failure.

### Rotation Sequence
1. Generate new Ed25519 + X25519 keypairs (independently)
2. Increment persisted `rotationNonce` (monotonic uint64)
3. Sign Rotation Announcement with **OLD** Ed25519 key (includes new nonce)
4. Broadcast to all connected neighbors
5. Accept messages encrypted with old key until old sessions torn down
6. Tear down old-key Noise XX sessions (hard timeout: 75s)
7. Securely erase old private key
8. Re-establish sessions with new key

### Rotation During Active Transfers
- **Sender side:** In-flight chunks on old session continue (up to 75s grace). E2E ciphertext keyed to old X25519 (still accepted). After teardown, resume via `resume_request` on new session.
- **Receiver side:** Incoming chunks on old sessions accepted during grace.
- **Relay traffic:** Existing cut-through completes; new messages use new sessions.
- **TOFU STRICT mode:** Sender must `repinKey()` before new session completes. Transfer paused (chunks buffered) until repin.

### Concurrent Rotation
When Peer A and Peer B both rotate simultaneously, each processes the other's Rotation Announcement independently. The single-flight lock prevents local races (only one `rotateIdentity()` call at a time per peer). The sequence:
1. A generates new keys, broadcasts announcement
2. B generates new keys, broadcasts announcement
3. A receives B's announcement → pins B's new key (per trust mode)
4. B receives A's announcement → pins A's new key (per trust mode)
5. Both tear down old sessions and re-establish with new keys

No special handling needed — the existing announcement verification (nonce check) and trust mode (STRICT/PROMPT) apply independently per peer direction.

### Multi-Hop Key Rotation Propagation
Rotation Announcements (`0x02`) are sent only to **directly connected neighbors** — they do not flood the mesh. Multi-hop peers learn about rotated keys through Babel Updates, which carry the destination's current Ed25519 + X25519 public keys.

**Key propagation path:** A rotates → A's neighbors process the Rotation Announcement and update their local key store → when those neighbors send Babel Updates to *their* neighbors, the Updates carry A's new public keys → keys propagate hop-by-hop via routing protocol.

**TOFU interaction for distant peers:** Keys from Babel Updates are **routing candidates only, not trusted for TOFU** (§3). If peer C (2+ hops away) has a TOFU pin for A from a prior direct Noise XX session, and A rotates:
1. C's routing table is updated with A's new keys (via Babel Update propagation)
2. C attempts to send to A, sealing with A's **old** pinned X25519 key
3. A cannot decrypt after the 75s grace period → responds with NACK (`decryptFailed`)
4. On receiving `decryptFailed`, C checks the routing table for a newer key for A
5. If the routing table key differs from the TOFU-pinned key → trigger the TOFU key-change flow per trust mode:
   - **STRICT:** Emit `KeyChangeEvent`; app must call `repinKey()` before retry
   - **PROMPT:** Emit `KeyChangeEvent` with callback; delivery paused until resolution
6. After repin, C re-seals with the new key and retries

**Latency:** Multi-hop TOFU repin is not instant — it depends on Babel Update propagation time (seconds to minutes depending on power modes and hop count) plus the failed-send round-trip. For latency-sensitive deployments, Consuming Apps should prefer PROMPT mode to handle key changes without manual intervention.

### Rotation Announcement Verification
Recipients MUST:
- Verify nonce strictly greater than last-seen for that peer's old key
- Persist new nonce before applying key change
- Reject stale/equal nonces regardless of timestamp

**Grace period interaction:** Power mode downgrade during rotation → connections get longer of eviction grace and rotation grace.

## 3. Trust Model: TOFU

Key pinning occurs **only after successful Noise XX handshake** — NOT on route discovery. Keys from Babel Updates are routing candidates only, not trusted for TOFU.

| Mode | Key Change Behavior |
|------|---------------------|
| `STRICT` (default) | Reject; emit `KeyChangeEvent`; app must call `repinKey()` |
| `PROMPT` | Pause delivery; emit `KeyChangeEvent` with callback; app must `acceptKeyChange()` or `rejectKeyChange()` within timeout (default 60s). Timeout → **hold** (continue pausing). Max 3 pending per peer, 10 global. Auto-reject after `3 × keyChangeTimeoutMillis`. |

> Two modes only. "Silent accept" was deliberately excluded — it's equivalent to no TOFU in adversarial environments.

### Safety Number Verification
`peerFingerprint(peerIdHex)` → 12-digit symmetric number from:
```
SHA-256(sorted(localEd25519Key, peerEd25519Key))
```
Uses **Ed25519 keys only** (not X25519). Both sides compute the same value. The safety number does not change when X25519 keys rotate independently, making out-of-band verification less confusing for users.

**Entropy tradeoff:** 12 digits ≈ 40 bits. Birthday collision at ~2²⁰ ≈ 1M peers. For BLE mesh (practical mesh sizes <1,000 peers), this is safe. Shorter numbers are easier to verify verbally or visually — Signal uses 60 digits but has billions of users. If MeshLink scales beyond BLE-range meshes in future, increase to 20+ digits.

## 4. Replay Protection

Replay counter is **inside** the E2E-encrypted payload (Noise K sealed), not in cleartext envelope. Eliminates relay replay-counter manipulation attack.

### E2E Payload Layout
```
[replayCounter: 8B LE][application payload...]
```

Counter only visible after decryption at final destination. Cleartext envelope uses `messageId` for relay-level dedup.

### 64-Counter Sliding Window (DTLS-style, RFC 9147 §4.5.3)

Track highest-seen counter N + 64-bit bitmap for [N-63, N]:
- Counter > N → accept, advance window
- Counter in [N-63, N] with unset bit → accept, set bit
- Counter in [N-63, N] with set bit → reject (replay)
- Counter < N-63 → reject (too old)

### Persistence
- **Outbound:** Pre-increment persist — written before send. Crash-safe. On `stop(Duration.ZERO)`, the pre-incremented counter for any unsent message is "wasted" — the gap is harmless (uint64 space is effectively infinite) but means the recipient's window advances by 1 without a corresponding message.
- **Inbound:** Batch-persisted every `replayPersistIntervalMillis` (default 5000ms) and on `stop()`/`pause()`. 50× reduction in SecureStorage writes. On crash: bitmask → all-ones (conservative — may drop in-flight messages from before crash). Safety-critical: set to `0` for immediate persistence.
- **Store:** 1,000 entries LRU, 30-day inactivity threshold.

## 5. Cryptographic Implementation Requirements

| ID | Requirement | Rationale |
|----|-------------|-----------|
| R1 | Reject all-zero X25519 shared secret (constant-time) | Prevents low-order key attack (RFC 7748 §6.1) |
| R2 | Constant-time comparison for all security bytes | Prevents timing side-channel |
| R3 | `sodium_memzero()` key material after use | Reduces cold-boot/core-dump exposure |
| R4 | Reject replay counter zero when encryption active | Prevents trivial replay |
| R5 | Emit `DECRYPTION_FAILED` on all failure paths | Silent passthrough masks injection |
| R6 | HMAC-protect persisted trust store index | Detects tampering |
| R7 | Redact peer IDs in diagnostics when configured | Prevents traffic analysis from logs |
| R8 | Guard nonce overflow (`< ULong.MAX_VALUE`) | Nonce reuse breaks ChaCha20-Poly1305 |
| R9 | Libsodium = sole crypto provider everywhere | Consistent constant-time guarantees |
| R10 | 96-bit Key Hash: strong adversarial collision resistance | ~2⁴⁸ birthday bound is infeasible on commodity hardware |

## 6. Pre-Handshake Message Gate

Messages from peers without completed Noise XX:

| Allowed | Blocked |
|---------|---------|
| Handshake (0x00), Keepalive (0x01), Rotation (0x02), Broadcast (0x09) | Hello (0x03), Update (0x04), Chunk (0x05), Chunk ACK (0x06), NACK (0x07), Resume (0x08), Routed Message (0x0A), Delivery ACK (0x0B) |

Prevents unauthenticated route injection and pre-auth resource exhaustion. Routed Messages require hop-by-hop Noise XX decryption — they cannot be processed without a completed session. Broadcasts are Ed25519-signed (not hop-by-hop encrypted) and are processed independently.

## 7. Metadata Protection

### Implemented (v1)
- **Safety numbers:** `peerFingerprint()` for out-of-band verification
- **Advertisement pseudonym rotation:** Key Hash replaced with `HMAC-SHA256(keyHash, epoch_counter)[0:12]`, rotating every 15 min. `epoch_counter` is encoded as 8-byte little-endian uint64 for HMAC input. The rotation epoch is **staggered per peer:** `epoch_counter = floor((unix_epoch_secs + peer_offset) / 900)` where `peer_offset = uint16_LE(keyHash[0:2]) mod 900`. This spreads rotation events across the 15-minute window, preventing all peers from changing pseudonym simultaneously. Connected peers verify via HMAC. New peers discover real identity during Noise XX. Real Key Hash **never** broadcast.
- **Delivery ACK jitter:** 50–500ms random delay (uniform) to prevent hop-distance estimation. Configurable via `ackJitterMaxMillis`.

### Accepted v1 Exposures
- **Relay metadata:** Origin/destination Key Hashes visible in cleartext Routed Message headers → social graph
- **Communication timing:** GATT write activity reveals send/receive timing
- **Power mode signal:** 2-bit power mode in ads reveals approximate battery/charging state
- **Chunk count fingerprinting:** Number of chunks reveals approximate message size

### Post-v1 Candidates
See `14-future.md` for: sealed sender headers, onion routing, constant-rate cover traffic, traffic-analysis-resistant padding.
