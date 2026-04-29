# Noise Protocol Framework — Processing Rules

<crypto_functions>
## Crypto Function Signatures (§4)

### Notation
- `||` concatenates byte sequences
- `byte()` constructs a single byte

### DH Functions (§4.1)

- **`GENERATE_KEYPAIR()`** → `(public_key, private_key)`. `public_key` is a byte sequence of length `DHLEN`.
- **`DH(key_pair, public_key)`** → byte sequence of length `DHLEN`. Performs DH between private key in `key_pair` and `public_key`. **Gap-DH problem must be unsolvable.** Invalid public keys: either return output purely a function of the public key (not depending on private key), or signal error. Implementations SHOULD handle invalid keys consistently.
- **`DHLEN`** ≥ 32 bytes. Size of public keys and DH outputs.

### Cipher Functions (§4.2)

- **`ENCRYPT(k, n, ad, plaintext)`** → ciphertext. Key `k` = 32 bytes. Nonce `n` = 8-byte unsigned integer, **must be unique per key**. AEAD mode with associated data `ad`. Ciphertext = plaintext + 16 bytes auth data. **Ciphertext must be indistinguishable from random** if key is secret.
- **`DECRYPT(k, n, ad, ciphertext)`** → plaintext, or error on auth failure.
- **`REKEY(k)`** → new 32-byte key. Default: first 32 bytes of `ENCRYPT(k, 2^64-1, zerolen, zeros)` where `zeros` = 32 zero bytes.

### Hash Functions (§4.3)

- **`HASH(data)`** → `HASHLEN` bytes. Collision-resistant.
- **`HASHLEN`** = 32 or 64.
- **`BLOCKLEN`** = internal block size (needed for HMAC).
- **`HMAC-HASH(key, data)`** = HMAC per RFC 2104 using `HASH()`. Only called within HKDF.
- **`HKDF(chaining_key, input_key_material, num_outputs)`**:
  - `chaining_key` = `HASHLEN` bytes. `input_key_material` = 0, 32, or `DHLEN` bytes.
  - `temp_key = HMAC-HASH(chaining_key, input_key_material)`
  - `output1 = HMAC-HASH(temp_key, byte(0x01))`
  - `output2 = HMAC-HASH(temp_key, output1 || byte(0x02))`
  - If `num_outputs == 2`: return `(output1, output2)`
  - `output3 = HMAC-HASH(temp_key, output2 || byte(0x03))`
  - Return `(output1, output2, output3)`
  - All outputs are `HASHLEN` bytes.
  - This is HKDF from RFC 5869 with `chaining_key` as salt and zero-length info.
</crypto_functions>

<cipherstate>
## The CipherState Object (§5.1)

Variables:
- **`k`** — 32-byte cipher key (may be `empty` = not yet initialized)
- **`n`** — 8-byte (64-bit) unsigned integer nonce

Functions:
- **`InitializeKey(key)`** — Sets `k = key`, `n = 0`.
- **`HasKey()`** — Returns true if `k` is non-empty.
- **`SetNonce(nonce)`** — Sets `n = nonce`. For out-of-order transport (§11.4).
- **`EncryptWithAd(ad, plaintext)`** — If `k` non-empty: return `ENCRYPT(k, n++, ad, plaintext)`. Else: return `plaintext`.
- **`DecryptWithAd(ad, ciphertext)`** — If `k` non-empty: return `DECRYPT(k, n++, ad, ciphertext)`. On auth failure: `n` is NOT incremented, error signaled. Else: return `ciphertext`.
- **`Rekey()`** — Sets `k = REKEY(k)`.

**Max nonce:** 2^64-1 is reserved. If incrementing `n` would reach 2^64-1, EncryptWithAd/DecryptWithAd signal error. Application must terminate session.
</cipherstate>

<symmetricstate>
## The SymmetricState Object (§5.2)

Contains a CipherState plus:
- **`ck`** — chaining key, `HASHLEN` bytes
- **`h`** — hash output, `HASHLEN` bytes

Functions:

- **`InitializeSymmetric(protocol_name)`**:
  - If `protocol_name` ≤ `HASHLEN` bytes: `h = protocol_name` zero-padded to `HASHLEN`.
  - Else: `h = HASH(protocol_name)`.
  - `ck = h`.
  - `InitializeKey(empty)`.

- **`MixKey(input_key_material)`**:
  - `ck, temp_k = HKDF(ck, input_key_material, 2)`.
  - If `HASHLEN == 64`: truncate `temp_k` to 32 bytes.
  - `InitializeKey(temp_k)`.

- **`MixHash(data)`**: `h = HASH(h || data)`.

- **`MixKeyAndHash(input_key_material)`** (for PSK, §9):
  - `ck, temp_h, temp_k = HKDF(ck, input_key_material, 3)`.
  - `MixHash(temp_h)`.
  - If `HASHLEN == 64`: truncate `temp_k` to 32 bytes.
  - `InitializeKey(temp_k)`.

- **`GetHandshakeHash()`** — Returns `h`. Call only after Split(). Used for channel binding (§11.2).

- **`EncryptAndHash(plaintext)`**:
  - `ciphertext = EncryptWithAd(h, plaintext)`.
  - `MixHash(ciphertext)`.
  - Return `ciphertext`. (If `k` empty, ciphertext = plaintext.)

- **`DecryptAndHash(ciphertext)`**:
  - `plaintext = DecryptWithAd(h, ciphertext)`.
  - `MixHash(ciphertext)`. (Note: hashes ciphertext, not plaintext.)
  - Return `plaintext`.

- **`Split()`** — Returns `(c1, c2)` CipherState pair for transport:
  - `temp_k1, temp_k2 = HKDF(ck, zerolen, 2)`.
  - If `HASHLEN == 64`: truncate both to 32 bytes.
  - `c1.InitializeKey(temp_k1)`, `c2.InitializeKey(temp_k2)`.
  - `c1` = initiator→responder. `c2` = responder→initiator.
</symmetricstate>

<handshakestate>
## The HandshakeState Object (§5.3)

Contains a SymmetricState plus:
- **`s`** — local static key pair (may be empty)
- **`e`** — local ephemeral key pair (may be empty)
- **`rs`** — remote static public key (may be empty)
- **`re`** — remote ephemeral public key (may be empty)
- **`initiator`** — boolean role
- **`message_patterns`** — remaining patterns to process, each a sequence of tokens from `{e, s, ee, es, se, ss, psk}`

### Initialize(handshake_pattern, initiator, prologue, s, e, rs, re)

1. Derive `protocol_name` from pattern + crypto names (§8). Call `InitializeSymmetric(protocol_name)`.
2. `MixHash(prologue)`.
3. Set `initiator`, `s`, `e`, `rs`, `re` from arguments.
4. `MixHash()` for each pre-message public key (initiator's first, then responder's; within each, in listed order).
5. Set `message_patterns` from handshake_pattern.

### WriteMessage(payload, message_buffer)

Fetch and delete next message pattern. For each token:

- **`"e"`**: `e = GENERATE_KEYPAIR()`. Append `e.public_key` to buffer. `MixHash(e.public_key)`.
- **`"s"`**: Append `EncryptAndHash(s.public_key)` to buffer.
- **`"ee"`**: `MixKey(DH(e, re))`.
- **`"es"`**: Initiator → `MixKey(DH(e, rs))`. Responder → `MixKey(DH(s, re))`.
- **`"se"`**: Initiator → `MixKey(DH(s, re))`. Responder → `MixKey(DH(e, rs))`.
- **`"ss"`**: `MixKey(DH(s, rs))`.

Then append `EncryptAndHash(payload)`.

If no more message patterns: return `Split()` → `(c1, c2)`.

### ReadMessage(message, payload_buffer)

Fetch and delete next message pattern. For each token:

- **`"e"`**: `re` = next `DHLEN` bytes. `MixHash(re)`.
- **`"s"`**: If `HasKey()`: read `DHLEN + 16` bytes. Else: read `DHLEN` bytes. `rs = DecryptAndHash(temp)`.
- **`"ee"`**: `MixKey(DH(e, re))`.
- **`"es"`**: Initiator → `MixKey(DH(e, rs))`. Responder → `MixKey(DH(s, re))`.
- **`"se"`**: Initiator → `MixKey(DH(s, re))`. Responder → `MixKey(DH(e, rs))`.
- **`"ss"`**: `MixKey(DH(s, rs))`.

`DecryptAndHash()` on remaining bytes → `payload_buffer`.

If no more message patterns: return `Split()` → `(c1, c2)`.
</handshakestate>

<prologue>
## Prologue (§6)

Arbitrary data hashed into `h` during Initialize. If parties provide different prologue data → decryption failure on first encrypted payload.

**Use case:** Confirm identical negotiation views (e.g., Alice and Bob hash the list of supported protocols to prevent MITM editing).

**Not mixed into encryption keys.** For secret data that should strengthen encryption, use PSK mode (§9) instead.
</prologue>

<transport_phase>
## Transport Phase

After handshake completes:
- **Initiator→Responder**: encrypt with `c1.EncryptWithAd(zerolen, plaintext)` (zero-length AD).
- **Responder→Initiator**: encrypt with `c2.EncryptWithAd(zerolen, plaintext)`.
- Decryption: `DecryptWithAd(zerolen, ciphertext)`.
- On `DECRYPT` failure: discard message. Application may terminate or continue.
- On nonce exhaustion: **must** terminate session.
- Preserve `h` value for channel binding; delete HandshakeState and SymmetricState.
</transport_phase>
