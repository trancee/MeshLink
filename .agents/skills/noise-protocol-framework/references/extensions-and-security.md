# Noise Protocol Framework — Extensions and Security

<psk_mode>
## Pre-Shared Symmetric Keys (§9)

PSK mode supports protocols where both parties share a **32-byte secret key**.

### Crypto Changes (§9.1)
- PSK is mixed via `MixKeyAndHash(psk)` — feeds into both encryption keys and `h`.
- Uses `HKDF(..., 3)`: third output → `k` value (allows skipping `k` calculation if unused).

### Handshake Tokens (§9.2)
- `"psk"` token appears in message patterns (not pre-messages). Processed by calling `MixKeyAndHash(psk)`.
- **In PSK handshakes**: every `"e"` token (in pre-message or message) is followed by `MixKey(e.public_key)` in addition to the normal `MixHash(e.public_key)`. This ensures PSK-derived keys are randomized by ephemeral nonces.

### Validity Rule (§9.3)
**A party must not send encrypted data after processing `"psk"` unless it has previously sent an ephemeral (`"e"` token), before or after the `"psk"`.** This prevents catastrophic key reuse of PSK-derived `k` without ephemeral randomization.

### Pattern Modifiers (§9.4)
- `psk0` → `"psk"` at beginning of first handshake message
- `psk1` → `"psk"` at end of first handshake message
- `psk2` → `"psk"` at end of second message
- `pskN` → `"psk"` at end of Nth message

#### Recommended PSK Patterns

**One-way:**
```
Npsk0:                   Kpsk0:                   Xpsk1:
  <- s                     -> s                     <- s
  ...                      <- s                     ...
  -> psk, e, es            ...                      -> e, es, s, ss, psk
                           -> psk, e, es, ss
```
Note: `Xpsk1` (not `psk0`) because responder needs to decrypt initiator's static key first to determine the pairwise PSK.

**Interactive (common):**
```
NNpsk0:  -> psk, e / <- e, ee
NNpsk2:  -> e / <- e, ee, psk
NKpsk0:  <- s ... -> psk, e, es / <- e, ee
NKpsk2:  <- s ... -> e, es / <- e, ee, psk
NXpsk2:  -> e / <- e, ee, s, es, psk
XNpsk3:  -> e / <- e, ee / -> s, se, psk
XKpsk3:  <- s ... -> e, es / <- e, ee / -> s, se, psk
XXpsk3:  -> e / <- e, ee, s, es / -> s, se, psk
KNpsk0:  -> s ... -> psk, e / <- e, ee, se
KNpsk2:  -> s ... -> e / <- e, ee, se, psk
KKpsk0:  -> s <- s ... -> psk, e, es, ss / <- e, ee, se
KKpsk2:  -> s <- s ... -> e, es, ss / <- e, ee, se, psk
KXpsk2:  -> s ... -> e / <- e, ee, se, s, es, psk
INpsk1:  -> e, s, psk / <- e, ee, se
INpsk2:  -> e, s / <- e, ee, se, psk
IKpsk1:  <- s ... -> e, es, s, ss, psk / <- e, ee, se
IKpsk2:  <- s ... -> e, es, s, ss / <- e, ee, se, psk
IXpsk2:  -> e, s / <- e, ee, se, s, es, psk
```

**Combinable:** Any PSK modifier can be applied to any pattern. E.g. `IKpsk0`, `KKpsk1`, `XXpsk0+psk3`. Additional placements (e.g., mid-message `psk`) are out of scope.
</psk_mode>

<compound_protocols>
## Compound Protocols (§10)

### Rationale (§10.1)
Bob may need to switch protocols after Alice's first message:
- Alice chose unsupported cipher/DH/pattern
- Alice's zero-RTT message used outdated static key or PSK

Switching reverses roles — Bob becomes initiator of new protocol.

### The `fallback` Modifier (§10.2)
Converts Alice-initiated pattern to Bob-initiated by making Alice's first message a pre-message:

```
XX:                          XXfallback:
  -> e                         -> e           ← becomes pre-message
  <- e, ee, s, es             ...
  -> s, se                    <- e, ee, s, es
                               -> s, se
```

`fallback` can only apply when Alice's first message is `"e"`, `"s"`, or `"e, s"` (valid as pre-message).

### Zero-RTT Structure (§10.3)
Three protocols needed:
1. **Full protocol** — no prior knowledge, or don't want zero-RTT
2. **Zero-RTT protocol** — encrypts initial message
3. **Switch protocol** — fallback if Bob can't decrypt zero-RTT

Distinguish via negotiation data (e.g., type byte before each message).

### Noise Pipes (§10.4)
```
Full:        XX    -> e / <- e, ee, s, es / -> s, se
Zero-RTT:    IK    <- s ... -> e, es, s, ss / <- e, ee, se
Switch:      XXfallback  -> e ... <- e, ee, s, es / -> s, se
```

- **XX** for first contact (no cached key). Alice caches Bob's static key afterward.
- **IK** for subsequent connections (zero-RTT using cached key).
- **XXfallback** if Bob's key changed and IK decryption fails.

### Handshake Indistinguishability (§10.5)
To hide handshake type from eavesdroppers:
- Pad all three first messages to constant size with random bytes
- Bob attempts IK decryption; falls back to XXfallback on failure
- Alice uses trial decryption to distinguish IK vs XXfallback response
- Full handshake: Alice sends ephemeral + random padding, uses XXfallback
- Ephemerals look like random DH values; use Elligator for indistinguishability from true random
</compound_protocols>

<advanced_features>
## Advanced Features (§11)

### Dummy Keys (§11.1)
Simulate optional authentication: always execute XX, send dummy static key if auth not requested. Hides which option was chosen from message size/timing. Similarly, **dummy PSKs** (all zeros) for optional PSK support.

### Channel Binding (§11.2)
After handshake: `GetHandshakeHash()` returns `h` — **unique session identifier**. Sign it, hash with password, etc. for application-layer auth with channel binding (token can't be replayed to different session).

### Rekey (§11.3)
`Rekey()` updates `k` via one-way function. Application decides when:
- **Continuous rekey** — after every transport message (simple, strong protection for old ciphertexts)
- **Periodic rekey** — after N messages
- **Signaled rekey** — explicit rekey message

**Does NOT reset `n`** — still need new handshake after 2^64 messages. Reasons: simplicity, avoids key cycles from repeated rekey, bounds total encryptions.

### Out-of-Order Transport (§11.4)
For UDP/lossy transport: send `n` alongside each message. Recipient calls `SetNonce(n)` before decrypting. **Must track received nonces and reject duplicates** to prevent replay. Many other concerns (out-of-order handshake messages, DoS) are out of scope.

### Half-Duplex (§11.5)
For strictly-alternating protocols: use only `c1` from Split(), discard `c2`. Both directions use same CipherState. Small optimization (one key derivation, one stored CipherState). **EXTREME CAUTION:** If protocol isn't strictly alternating → catastrophic nonce reuse.
</advanced_features>

<concrete_algorithms>
## Concrete Algorithms (§12)

### DH Functions

| Name | Function | DHLEN | Invalid Key Handling |
|------|----------|-------|---------------------|
| `25519` | X25519 (Curve25519, RFC 7748) | 32 | Output all zeros (error detection allowed but discouraged) |
| `448` | X448 (Curve448, RFC 7748) | 56 | Output all zeros (error detection allowed but discouraged) |

### Cipher Functions

| Name | Algorithm | Nonce Format (96-bit) |
|------|-----------|----------------------|
| `ChaChaPoly` | AEAD_CHACHA20_POLY1305 (RFC 7539/8439) | 32 bits zeros + **little-endian** `n` |
| `AESGCM` | AES-256-GCM (NIST SP 800-38D) + 128-bit tag | 32 bits zeros + **big-endian** `n` |

### Hash Functions

| Name | Algorithm | HASHLEN | BLOCKLEN |
|------|-----------|---------|----------|
| `SHA256` | SHA-256 (FIPS 180-4) | 32 | 64 |
| `SHA512` | SHA-512 (FIPS 180-4) | 64 | 128 |
| `BLAKE2s` | BLAKE2s digest-32 (RFC 7693) | 32 | 64 |
| `BLAKE2b` | BLAKE2b digest-64 (RFC 7693) | 64 | 128 |

### Recommended Combinations
- **`25519`** recommended for typical use. `448` for extra EC security margin.
- `448` should use 512-bit hash (`SHA512` or `BLAKE2b`).
- `25519` may use 256-bit hash (`SHA256`, `BLAKE2s`) but 512-bit offers margin.
- `AESGCM` is hard to implement with high speed and constant time in software.
</concrete_algorithms>

<application_responsibilities>
## Application Responsibilities (§13)

1. **Extensible payloads** — use JSON, Protocol Buffers, etc. for future fields.
2. **Padding** — encrypted payloads should support padding to hide message sizes.
3. **Session termination** — explicit length fields or termination signals inside transport payloads (Noise messages can be truncated by attacker).
4. **Length fields** — 16-bit big-endian recommended before each Noise message (max 65535 bytes).
5. **Negotiation data** — version info, protocol identifiers before handshake messages. Introduces rollback attack risk (see security considerations).
</application_responsibilities>

<security_considerations>
## Security Considerations (§14)

| Topic | Rule |
|-------|------|
| **Authentication** | Application determines if remote static key is acceptable (certs, pinning, preconfigured lists). |
| **Rollback** | If protocol negotiation not included in prologue → rollback attack possible. Critical for compound protocols. |
| **Static key reuse** | Single hash algorithm only. Don't use outside Noise or with multiple hashes. OK across Noise protocols if same hash. |
| **PSK reuse** | Single hash algorithm only. Don't use outside Noise. |
| **Ephemeral key reuse** | **NEVER.** Fresh ephemeral before any encrypted data. Violation → catastrophic key reuse. |
| **Public keys ≠ secrets** | Invalid pubkeys may cause predictable DH output. Don't assume pre-message pubkey knowledge authenticates. Use PSK for shared-secret auth. |
| **Channel binding** | Use `h` (handshake hash), NOT `ck`. `h` is unique per session even with invalid DH values causing predictable output. |
| **Nonce management** | No wrapping. Max 2^64-1 messages. Reuse = catastrophic. |
| **Protocol name uniqueness** | Must uniquely identify pattern + crypto for every key used with it. |
| **PSK entropy** | 256 bits of entropy required. |
| **AESGCM volume** | Max 2^56 bytes per key (birthday bound on 2^52 AES blocks → <1 in 10^6 attack probability). |
| **Hash collisions** | Collision on prologue or handshake hash → transcript collision attack. Use collision-resistant hashes. |
| **Implementation fingerprinting** | For anonymous settings, implementations must behave identically for all inputs (including invalid DH keys). |
</security_considerations>

<design_rationales>
## Design Rationales (§15)

**256-bit cipher keys/PSKs:** Conservative margin for cryptanalysis, time/memory tradeoffs, multi-key attacks, quantum. Fixed PSK length deters low-entropy passwords.

**64-bit nonces:** Salsa20 compatibility, original ChaCha20 used 64-bit, easy integer increment, 96-bit nonces confuse random-nonce acceptability.

**128-bit auth tag:** GCM loses security when truncated. Noise used in varied contexts with rapid feedback. Single fixed length is simpler.

**Ciphertext indistinguishable from random:** Enables random padding, censorship-resistant protocols, steganography.

**Rekey via ENCRYPT at maxnonce:** Efficient for AES-GCM and ChaCha20-Poly1305 (skip auth tag computation). Rekey doesn't reset `n`: simplicity, avoids key cycles, bounds total encryptions.

**AESGCM 2^56 byte limit:** 2^52 AES blocks. Birthday collision probability for ruling out plaintext guesses < 1 in 10^6 at (2^52 × 2^52) / 2^128.

**Nonce endianness:** ChaChaPoly uses LE (matching ChaCha20 internal LE counter). AESGCM uses BE (matching GCM internal BE counter).

**HKDF for MixKey:** Well-known, published analysis, multi-layer hashing mitigates hash weakness.

**HMAC for all hash functions:** Required by HKDF analysis, widely used with Merkle-Damgard, SHA3 candidates designed for HMAC compatibility, consistent cryptanalysis, easy to build on hash interface.

**MixHash vs MixKey:** MixHash is more efficient. Produces non-secret `h` useful for channel binding.

**h hashes ciphertext not plaintext:** Non-secret value safe for channel binding. Stronger malleability guarantees.

**No explicit random nonces:** Ephemeral keys make them unnecessary. Random nonces enable ephemeral reuse (more complex, less secure, tiny optimization). Nonces increase message size. Nonces enable RNG backdoors.

**Session termination left to application:** Noise termination signal doesn't help much — app must use it correctly. Second signal is confusing.
</design_rationales>
