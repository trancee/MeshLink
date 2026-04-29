# ChaCha20-Poly1305 RFC 8439 — AEAD Construction & Security

<aead_construction>
## AEAD_CHACHA20_POLY1305 (§2.8)

Combines ChaCha20 encryption with Poly1305 authentication into an Authenticated Encryption with Associated Data (AEAD) scheme.

### Inputs
- **Key:** 256 bits (32 bytes) — same key used for both encryption and Poly1305 key generation
- **Nonce:** 96 bits (12 bytes) — MUST be different for each invocation with the same key
- **Plaintext:** arbitrary length (max ~256 GB)
- **AAD:** arbitrary length additional authenticated data (authenticated but NOT encrypted)

### Parameters (per RFC 5116 §4)
| Parameter | Value |
|-----------|-------|
| K_LEN (key length) | 32 octets |
| N_MIN = N_MAX (nonce length) | 12 octets |
| P_MAX (max plaintext) | 274,877,906,880 bytes (~256 GB) |
| A_MAX (max AAD) | 2^64 − 1 octets |
| C_MAX (max ciphertext) | P_MAX + 16 (tag) |
| Tag length | 16 octets (128 bits) |

### Encryption Procedure

```
chacha20_aead_encrypt(aad, key, iv, constant, plaintext):
    nonce = constant | iv                           // 4 + 8 = 12 bytes
    otk = poly1305_key_gen(key, nonce)              // block counter 0
    ciphertext = chacha20_encrypt(key, 1, nonce, plaintext)  // counter starts at 1
    mac_data = aad | pad16(aad)
              | ciphertext | pad16(ciphertext)
              | num_to_8_le_bytes(aad.length)
              | num_to_8_le_bytes(ciphertext.length)
    tag = poly1305_mac(mac_data, otk)
    return (ciphertext, tag)
```

### Poly1305 MAC Input Construction

```
+-------------------+----------+
| AAD               | variable |
+-------------------+----------+
| padding1 (zeros)  | 0-15     |  ← pad to 16-byte boundary
+-------------------+----------+
| Ciphertext        | variable |
+-------------------+----------+
| padding2 (zeros)  | 0-15     |  ← pad to 16-byte boundary
+-------------------+----------+
| AAD length        | 8 bytes  |  ← 64-bit little-endian
+-------------------+----------+
| Ciphertext length | 8 bytes  |  ← 64-bit little-endian
+-------------------+----------+
```

`pad16(x)`: zero bytes to make total length a multiple of 16. If already aligned, zero-length.

### Decryption

1. Generate Poly1305 one-time key from (key, nonce) with counter 0
2. Compute Poly1305 tag over AAD + ciphertext (same construction as encryption)
3. **Compare calculated tag with received tag in constant time**
4. If tags match → decrypt ciphertext using ChaCha20 with counter starting at 1
5. If tags don't match → reject; do NOT decrypt

**Poly1305 always runs on the ciphertext, not the plaintext** — even during decryption.

### Key Design Decisions
- Counter 0 is reserved for Poly1305 key generation; encryption starts at counter 1
- This means the same key+nonce serves both encryption and authentication safely
- The AAD and ciphertext lengths at the end prevent length-extension-style attacks
</aead_construction>

<security>
## Security Considerations (§4)

### ChaCha20 Security
- 256-bit security level
- All operations (add, XOR, fixed rotate) are trivially constant-time
- No table lookups → no cache-timing side channels
- State accesses and operation count don't depend on key value

### Poly1305 Security
- Tag forgery probability: ≤ n/(2^102) for 16n-byte message, even after 2^64 messages
- **Constant-time implementation required:** Don't use generic bignum libraries (dynamic allocation, variable-time). Use dedicated implementations like poly1305-donna.
- Naive 288-bit integer arithmetic suffices (product of (acc+block) × r < 2^288)
- **Tag comparison MUST be constant-time** — C's `memcmp()` is NOT acceptable

### Nonce Uniqueness (CRITICAL)
**Most important security requirement.** Nonce MUST be unique per (key, nonce) pair.

**If a nonce repeats:**
- Identical keystream → XOR of plaintexts revealed (XOR of ciphertexts = XOR of plaintexts)
- Identical Poly1305 one-time key → authentication compromised

Acceptable nonce generation: counters, LFSRs, DES-encrypted counters. **NOT random generation** (birthday bound too tight at 96 bits). **NOT truncated 128/256-bit block cipher output** (may repeat quickly).

### Tag Handling
- **MUST include complete 128-bit tag** — tag truncation MUST NOT be done
- Nonces and AAD are "not used up" until valid message received → attacker can try multiple tags
- Without constant-time comparison, prefix timing leak reduces attack from 2^128 to much less

### Implementation
- Poly1305: use constant-time dedicated implementations, not OpenSSL bignum
- ChaCha20: copy state, work on copy, increment counter on original (saves ~5.5%)
</security>

<aead_test_vector>
## AEAD Test Vector (§2.8.2)

```
Plaintext: "Ladies and Gentlemen of the class of '99: If I could
           offer you only one tip for the future, sunscreen would be it."
           (114 bytes)

AAD:   50:51:52:53:c0:c1:c2:c3:c4:c5:c6:c7 (12 bytes)
Key:   80:81:82:...:9f (32 bytes)
IV:    40:41:42:43:44:45:46:47 (8 bytes)
Fixed: 07:00:00:00 (4 bytes)
Nonce: 07:00:00:00:40:41:42:43:44:45:46:47 (12 bytes)

Poly1305 one-time key (from counter=0):
  7b ac 2b 25 2d b4 47 af 09 b6 7a 55 a4 e9 55 84
  0a e1 d6 73 10 75 d9 eb 2a 93 75 78 3e d5 53 ff

Ciphertext (114 bytes):
  d3 1a 8d 34 64 8e 60 db 7b 86 af bc 53 ef 7e c2
  a4 ad ed 51 29 6e 08 fe a9 e2 b5 a7 36 ee 62 d6
  3d be a4 5e 8c a9 67 12 82 fa fb 69 da 92 72 8b
  1a 71 de 0a 9e 06 0b 29 05 d6 a5 b6 7e cd 3b 36
  92 dd bd 7f 2d 77 8b 8c 98 03 ae e3 28 09 1b 58
  fa b3 24 e4 fa d6 75 94 55 85 80 8b 48 31 d7 bc
  3f f4 de f0 8e 4b 7a 9d e5 76 d2 65 86 ce c6 4b
  61 16

Tag: 1a:e1:0b:59:4f:09:e2:6a:7e:90:2e:cb:d0:60:06:91
```
</aead_test_vector>
