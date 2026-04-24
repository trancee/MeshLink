# 05 — Security: Encryption

> **Covers:** §6.1–6.4 (CryptoProvider, Two-Layer Encryption, Noise XX, Noise K)
> **Dependencies:** `01-vision-and-domain-model.md` (Identity, Key Hash, E2E/Hop-by-Hop encryption), `04-wire-format.md` (Handshake payload)
> **Key exports:** CryptoProvider interface, Noise XX handshake, Noise K seal, DH static-static cache
> **Changes from original:** S2 (session ratchet removed), S3 (Noise IK removed), S8 (ephemeral key pool removed), S5 (compression removed from pipeline). Review fixes: HKDF info string includes protocol version domain separator.

---

## 1. CryptoProvider Interface

All crypto goes through a platform-agnostic interface backed by libsodium:

| Operation | Algorithm | RFC | libsodium function |
|-----------|-----------|-----|-------------------|
| `generateEd25519KeyPair()` / `sign()` / `verify()` | Ed25519 | RFC 8032 | `crypto_sign_*` |
| `generateX25519KeyPair()` | X25519 keypair | RFC 7748 | `crypto_box_keypair` |
| `x25519SharedSecret()` | X25519 DH | RFC 7748 | `crypto_scalarmult` |
| `aeadEncrypt()` / `aeadDecrypt()` | ChaCha20-Poly1305 | RFC 8439 | `crypto_aead_chacha20poly1305_ietf_*` |
| `sha256()` | SHA-256 | RFC 6234 | `crypto_hash_sha256` |
| `hkdfSha256()` | HKDF-SHA-256 | RFC 5869 | `crypto_auth_hmacsha256` (to build HKDF) |

Platform `expect`/`actual` factory returns the provider per platform (see `02-architecture.md` §4).

## 2. Two-Layer Encryption Architecture

| Layer | Scheme | Scope | Purpose |
|-------|--------|-------|---------|
| **E2E** | Noise K (`Noise_K_25519_ChaChaPoly_SHA256`) | Sender → Final Recipient | Only recipient can read; sender authenticated |
| **Hop-by-Hop** | Noise XX (`Noise_XX_25519_ChaChaPoly_SHA256`) | Peer → Adjacent Peer | Transport encryption between neighbors |

**Pipeline order:**
```
E2E encrypt (Noise K) → chunk → hop-by-hop encrypt (Noise XX) → BLE transmit
```

Relays decrypt/re-encrypt hop-by-hop only. E2E ciphertext is **opaque to relays**.

> **Compression removed for v1.** CRIME/BREACH oracle risk with compression before encryption. Small BLE payloads (≤10KB) rarely benefit from DEFLATE. See `14-future.md`.

## 3. Hop-by-Hop: Noise XX

`Noise_XX_25519_ChaChaPoly_SHA256` — mutual authentication + session-level forward secrecy. **The only handshake pattern** (Noise IK removed for v1 — one code path, no wrong-key fallback race).

### 3-Message Handshake

| Message | Direction | Encrypted? | Contains |
|---------|-----------|------------|----------|
| Msg 1 (`→ e`) | Initiator → Responder | No | Ephemeral key only |
| Msg 2 (`← e, ee, s, es`) | Responder → Initiator | Yes | Handshake Payload (version, capability, PSM, optional Ed25519 key) |
| Msg 3 (`→ s, se`) | Initiator → Responder | Yes | Handshake Payload |

After Msg 3: mutual authentication complete, session transport keys established. All data encrypted with ChaCha20-Poly1305.

**Failure recovery:** Handshake failure (partial bytes, invalid message, 8s timeout) → discard all Noise state, disconnect GATT. No immediate retry, no blocklisting.

**Simultaneous handshake race:** If both peers initiate Noise XX simultaneously, the peer with the higher ephemeral public key (lexicographic unsigned byte comparison) stays as initiator. Loser drops initiator state and creates a fresh responder.

**Why Noise XX:** No server required, forward secrecy, simple spec, battle-tested (WireGuard, Lightning). SHA-256 for hardware acceleration on ARM64.

> **Post-v1 candidate:** Noise IK (2-message fast reconnect). See `14-future.md`.

## 4. End-to-End: Noise K (Full Seal Per Message)

Sender knows recipient's static X25519 public key (from Noise XX handshake or Babel Update).

### Seal Process

1. Generate ephemeral X25519 keypair (`e`)
2. `DH(e, rs)` — ephemeral × static recipient (forward secrecy)
3. `DH(s, rs)` — static sender × static recipient (sender authentication)
4. Concatenate both shared secrets + optional **session secret** (from Noise XX, for additional forward secrecy)
5. HKDF-SHA-256 → 44 bytes (key + nonce), info string `"MeshLink-v1-NoiseK-seal"`
6. Encrypt with ChaCha20-Poly1305 (AAD = ephemeral public key)
7. Output: `ephemeral_pubkey(32B) + ciphertext(N + 16B AEAD tag)`

Recipient decrypts by computing both DH operations and the session secret.

### Wire Format

```
[ephemeralPubkey: 32B][ciphertext: N + 16B AEAD tag]
```

48 bytes of overhead per sealed message (32B ephemeral pubkey + 16B AEAD tag). Every message gets a fresh ephemeral key → **full per-message forward secrecy**.

### Performance

Full Noise K seal cost: ~0.1–0.5ms with libsodium hardware-accelerated X25519. BLE transmission of even a 1KB message takes 50–200ms. The DH cost is <1% of total latency.

### DH Static-Static Cache

`DH(s, rs)` is deterministic for a given sender/recipient pair. Cached in memory (32 bytes per peer). Zeroized on key rotation. Eliminates 1 of 2 `scalarMult` operations from the seal, reducing cost to ~0.05–0.25ms.

> **Session ratchet removed for v1.** The ratchet saved ~µs per message but added: desync recovery, NACK reason codes, look-ahead windows, epoch tracking, re-key storms, old-chain retention. With hardware-accelerated DH at <1% of BLE latency, the complexity was not justified. Every message now gets full forward secrecy with no state machine.

### Security Properties

- **Confidentiality:** Recipient-only decryption
- **Sender authentication:** Via `DH(s, rs)` + optional session secret
- **Per-message forward secrecy:** Fresh ephemeral key per message
- **Recipient forward secrecy:** Session-key mixing (memory-only, deleted on restart)
- **No server/prekey distribution needed**

**Broadcasts do NOT use Noise K** — Ed25519-signed but unencrypted (see `09-messaging.md`).
