# Crypto Primitives Reference

## CryptoProvider Interface

Internal interface backed by libsodium on each platform. All operations are constant-time where relevant.

| Method | Primitive | Key Size | Input | Output |
|--------|-----------|----------|-------|--------|
| `generateEd25519KeyPair()` | Ed25519 (RFC 8032) | — | — | KeyPair(pub: 32B, priv: 64B) |
| `sign(privateKey, message)` | Ed25519 | 64B priv | message | 64B signature |
| `verify(publicKey, message, signature)` | Ed25519 | 32B pub | message + sig | Boolean |
| `generateX25519KeyPair()` | X25519 (RFC 7748) | — | — | KeyPair(pub: 32B, priv: 32B) |
| `x25519SharedSecret(privateKey, publicKey)` | X25519 DH | 32B each | priv + pub | 32B shared secret |
| `aeadEncrypt(key, nonce, plaintext, aad)` | ChaCha20-Poly1305 (RFC 8439) | 32B key, 12B nonce | plaintext + AAD | ciphertext + 16B tag |
| `aeadDecrypt(key, nonce, ciphertext, aad)` | ChaCha20-Poly1305 | 32B key, 12B nonce | ciphertext + AAD | plaintext (throws on tag failure) |
| `sha256(input)` | SHA-256 (RFC 6234) | — | input | 32B digest |
| `hmacSha256(key, data)` | HMAC-SHA-256 (RFC 2104) | arbitrary | data | 32B MAC |
| `hkdfSha256(salt, ikm, info, length)` | HKDF-SHA-256 (RFC 5869) | arbitrary salt | IKM + info | length bytes (max 8160) |

---

## Platform Implementations

| Platform | Class | Backend |
|----------|-------|---------|
| Android | `AndroidCryptoProvider` | JNI → libsodium C (prebuilt `.so` for arm64-v8a, armeabi-v7a, x86_64) |
| iOS | `IosCryptoProvider` | Kotlin/Native cinterop → libsodium C (statically linked) |
| JVM (test) | `JvmCryptoProvider` | Pure-Kotlin reference implementation (not for production) |

---

## Identity

Each node has a persistent identity containing:

```kotlin
internal data class Identity(
    val ed25519KeyPair: KeyPair,   // Signing (broadcasts, rotation announcements)
    val x25519KeyPair: KeyPair,    // DH (Noise handshakes)
    val keyHash: ByteArray,        // SHA-256(ed25519Pub || x25519Pub)[0:20] — the peer ID
)
```

The `keyHash` (20 bytes, hex-encoded as `PeerIdHex`) is the canonical peer identifier used in routing tables, trust stores, and the public API.

---

## Noise Protocol Usage

### Transport Encryption: Noise XX

Full pattern name: `Noise_XX_25519_ChaChaPoly_SHA256`

```
Initiator                          Responder
    |-- msg1: e -->                   |
    |<-- msg2: e, ee, s, es ---------|
    |-- msg3: s, se -->               |
```

After completion: two CipherState instances (one per direction) for symmetric transport.

### E2E Encryption: Noise K

Full pattern name: `Noise_K_25519_ChaChaPoly_SHA256`

Used inside `RoutedMessage` payloads. The sender seals the message to the recipient's known static X25519 key. Only the final destination can decrypt — relay nodes cannot read the content.

---

## Key Derivation

### Pseudonym Rotation

```
pseudonym = HMAC-SHA-256(keyHash, epochCounter)[0:12]
```

Rotates every 15 minutes. The `epochCounter` is `currentTimeMillis / 900_000`.

### Mesh Hash

```
meshHash = FNV-1a(appId) & 0xFFFF   // 16-bit
```

Two bytes in the advertisement payload, little-endian. Different appIds produce different meshes — peers ignore advertisements with non-matching mesh hash.

### Session Keys (via Noise)

Noise XX handshake derives transport keys internally via HKDF chains. The `CipherState.k` values are 32-byte ChaCha20-Poly1305 keys with 8-byte nonces (incremented per message).

---

## Replay Protection

Each Noise transport session maintains:
- 64-bit monotonic nonce (incremented per encrypted frame)
- Receiver maintains a sliding bitmap window (64 slots) anchored at the highest seen nonce
- Frames with nonce ≤ (highest - 64) are silently dropped
- Frames with nonce in the window are checked against the bitmap
- Duplicates emit `REPLAY_REJECTED` diagnostic

---

## Constant-Time Operations

| Operation | Guarantee |
|-----------|-----------|
| AEAD tag comparison | `ConstantTimeEquals.compare()` — no early-exit on first differing byte |
| Ed25519 verify | libsodium internal constant-time |
| X25519 DH | Montgomery ladder (constant-time by construction) |
| Key hash comparison | `ConstantTimeEquals.compare()` in TrustStore lookups |

---

## Key Formats

| Key Type | Size | Encoding | Storage |
|----------|------|----------|---------|
| Ed25519 public | 32 bytes | Raw compressed Y-coordinate | Identity, TrustStore |
| Ed25519 private | 64 bytes | seed ‖ public (libsodium convention) | SecureStorage only |
| X25519 public | 32 bytes | Raw U-coordinate | Identity, TrustStore |
| X25519 private | 32 bytes | Clamped scalar | SecureStorage only |
| ChaCha20 key | 32 bytes | Raw | CipherState (ephemeral, never persisted) |
| Nonce | 12 bytes (AEAD) / 8 bytes (Noise) | Little-endian counter | CipherState |
| Key hash (peer ID) | 20 bytes | SHA-256 truncated | Everywhere |
