# ChaCha20-Poly1305 RFC 8439 — ChaCha20 Cipher

<overview>
## What RFC 8439 Defines

Three algorithms for IETF protocols. IRTF Informational (June 2018), Crypto Forum Research Group. Obsoletes RFC 7539.

1. **ChaCha20** — stream cipher (256-bit key, 96-bit nonce, 32-bit counter)
2. **Poly1305** — one-time MAC (256-bit one-time key → 128-bit tag)
3. **AEAD_CHACHA20_POLY1305** — authenticated encryption with associated data combining both

### Why ChaCha20 Over AES
- ~3× faster than AES in **software-only** implementations (no AES-NI hardware)
- Not vulnerable to cache-collision timing attacks (unlike table-based AES)
- Serves as "standby cipher" if AES weakness discovered
- All operations (add, XOR, fixed rotate) are trivially constant-time
</overview>

<chacha20_state>
## ChaCha20 State (§2.3)

The state is a 4×4 matrix of 32-bit unsigned integers (512 bits total):

```
cccccccc  cccccccc  cccccccc  cccccccc     ← Constants
kkkkkkkk  kkkkkkkk  kkkkkkkk  kkkkkkkk     ← Key (words 4-7)
kkkkkkkk  kkkkkkkk  kkkkkkkk  kkkkkkkk     ← Key (words 8-11)
bbbbbbbb  nnnnnnnn  nnnnnnnn  nnnnnnnn     ← Counter + Nonce
```

**Words 0-3 (constants):** `0x61707865 0x3320646e 0x79622d32 0x6b206574` ("expand 32-byte k" in ASCII)

**Words 4-11 (key):** 256-bit key as 8 little-endian 32-bit integers

**Word 12 (counter):** 32-bit block counter (little-endian). Limits: 2^32 blocks × 64 bytes = 256 GB per (key, nonce)

**Words 13-15 (nonce):** 96-bit nonce as 3 little-endian 32-bit integers. Word 13 = first 32 bits, word 15 = last 32 bits. **MUST NOT repeat for the same key.**

### Nonce Note
Original ChaCha used 64-bit nonce + 64-bit counter. RFC 8439 changed to 96-bit nonce + 32-bit counter per RFC 5116 recommendations. For protocols with 64-bit nonces: first 32 bits of nonce word set to a constant (typically zero; different per sender in multi-sender protocols).
</chacha20_state>

<quarter_round>
## The Quarter Round (§2.1)

The fundamental operation. Operates on four 32-bit unsigned integers (a, b, c, d):

```
a += b;  d ^= a;  d <<<= 16;
c += d;  b ^= c;  b <<<= 12;
a += b;  d ^= a;  d <<<= 8;
c += d;  b ^= c;  b <<<= 7;
```

Where `+` = addition mod 2^32, `^` = XOR, `<<<` = left rotate.

### Application to State

`QUARTERROUND(x, y, z, w)` applies the quarter round to state indices x, y, z, w.

**Column rounds** (4 quarter rounds):
```
QUARTERROUND(0, 4,  8, 12)
QUARTERROUND(1, 5,  9, 13)
QUARTERROUND(2, 6, 10, 14)
QUARTERROUND(3, 7, 11, 15)
```

**Diagonal rounds** (4 quarter rounds):
```
QUARTERROUND(0, 5, 10, 15)
QUARTERROUND(1, 6, 11, 12)
QUARTERROUND(2, 7,  8, 13)
QUARTERROUND(3, 4,  9, 14)
```

**ChaCha20 = 20 rounds** = 10 iterations of (column round + diagonal round) = 80 quarter rounds total.
</quarter_round>

<block_function>
## The ChaCha20 Block Function (§2.3)

Inputs: 256-bit key, 32-bit counter, 96-bit nonce. Output: 64 bytes of keystream.

```
chacha20_block(key, counter, nonce):
    state = constants | key | counter | nonce
    initial_state = state
    for i = 1 to 10:
        inner_block(state)      // column round + diagonal round
    state += initial_state      // add mod 2^32 word-by-word
    return serialize(state)     // little-endian
```

**Critical: The original state is added back after the rounds.** This makes the function non-invertible (attacker can't recover key from output alone).

### Optimization
Copy state before rounds; do rounds on the copy. Then for next block, only increment counter on original — saves ~5.5% of cycles.
</block_function>

<encryption>
## ChaCha20 Encryption (§2.4)

Inputs: 256-bit key, 32-bit initial counter, 96-bit nonce, plaintext (arbitrary length).
Output: ciphertext (same length).

```
chacha20_encrypt(key, counter, nonce, plaintext):
    for j = 0 to floor(len(plaintext)/64) - 1:
        key_stream = chacha20_block(key, counter+j, nonce)
        encrypted_message += plaintext[j*64 .. j*64+63] XOR key_stream
    if len(plaintext) % 64 != 0:         // last partial block
        j = floor(len(plaintext)/64)
        key_stream = chacha20_block(key, counter+j, nonce)
        encrypted_message += plaintext[j*64 ..] XOR key_stream[0 .. remainder-1]
    return encrypted_message
```

**Decryption is identical** — XOR ciphertext with same keystream. No padding needed; extra keystream from last block is discarded.
</encryption>

<test_vector_block>
## ChaCha20 Block Test Vector (§2.3.2)

```
Key:     00:01:02:...:1f (32 bytes, sequential)
Nonce:   00:00:00:09:00:00:00:4a:00:00:00:00
Counter: 1

State after setup:
  61707865  3320646e  79622d32  6b206574
  03020100  07060504  0b0a0908  0f0e0d0c
  13121110  17161514  1b1a1918  1f1e1d1c
  00000001  09000000  4a000000  00000000

Serialized output (first 48 bytes):
  10 f1 e7 e4 d1 3b 59 15 50 0f dd 1f a3 20 71 c4
  c7 d1 f4 c7 33 c0 68 03 04 22 aa 9a c3 d4 6c 4e
  d2 82 64 46 07 9f aa 09 14 c2 d7 05 d9 8b 02 a2
```
</test_vector_block>
