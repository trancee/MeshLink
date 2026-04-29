---
name: chacha20-poly1305-rfc8439
description: Complete reference for RFC 8439 — ChaCha20 and Poly1305 for IETF Protocols. Covers the ChaCha20 stream cipher (256-bit key, 96-bit nonce, 32-bit block counter, quarter round operation with add/XOR/rotate, column and diagonal rounds, 20 rounds = 80 quarter rounds, state initialization with "expand 32-byte k" constants, block function with add-back, stream encryption via XOR), the Poly1305 one-time MAC (256-bit one-time key split into clamped r and s, accumulator loop with mod 2^130-5, 128-bit tag, 0x01 byte padding per block, forgery probability), Poly1305 key generation from ChaCha20 block 0, and the AEAD_CHACHA20_POLY1305 construction (key gen at counter 0, encrypt at counter 1+, MAC over AAD|pad|ciphertext|pad|lengths, 128-bit tag, decrypt-then-verify). Includes security considerations (nonce uniqueness critical — repeat reveals XOR of plaintexts, constant-time tag comparison, constant-time Poly1305 arithmetic, no tag truncation), implementation advice, and test vectors for all components. Use when implementing ChaCha20 encryption, Poly1305 authentication, AEAD_CHACHA20_POLY1305, understanding the quarter round, debugging nonce handling, or any question about RFC 8439.
---

<essential_principles>

**RFC 8439** defines ChaCha20, Poly1305, and their AEAD combination. IRTF Informational (June 2018). Obsoletes RFC 7539.

### What an Implementer Must Know

**ChaCha20:** Stream cipher. 256-bit key, 96-bit nonce, 32-bit block counter. State = 4×4 matrix of uint32 (constants + key + counter + nonce). Quarter round: `a+=b; d^=a; d<<<16; c+=d; b^=c; b<<<12; a+=b; d^=a; d<<<8; c+=d; b^=c; b<<<7`. Twenty rounds = 10×(column round + diagonal round). **Add original state back after rounds** (non-invertible). Output 64-byte keystream block. XOR with plaintext. Decryption = same operation.

**Poly1305:** One-time MAC. 32-byte key = (r, s), each 128 bits. Clamp r: top 4 bits of bytes 3,7,11,15 clear; bottom 2 bits of bytes 4,8,12 clear. For each 16-byte block: append 0x01 byte, add to accumulator, multiply by r, reduce mod P=2^130−5. Finally add s (no mod), output low 128 bits. **Key MUST be one-time.** Forgery ≤ n/2^102 for 16n-byte message.

**AEAD_CHACHA20_POLY1305:** Generate Poly1305 key from ChaCha20 block at counter=0. Encrypt plaintext starting at counter=1. MAC input = AAD | pad16 | ciphertext | pad16 | len(AAD) as 8-byte LE | len(ciphertext) as 8-byte LE. Output = ciphertext + 128-bit tag. Decrypt: verify tag first (on ciphertext, not plaintext), then decrypt.

**Nonce uniqueness is CRITICAL.** Repeat → identical keystream + identical Poly1305 key → XOR of plaintexts revealed + auth compromised. Use counters, not random generation.

**Constant-time:** ChaCha20 is trivially constant-time (add/XOR/rotate only). Poly1305 requires careful implementation — no generic bignum. Tag comparison MUST be constant-time (not memcmp). Tag MUST NOT be truncated.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| ChaCha20 cipher (state layout with constants/key/counter/nonce, 96-bit vs 64-bit nonce, quarter round operation, column and diagonal rounds, block function with add-back and optimization, stream encryption/decryption, block function test vector) | `references/chacha20.md` |
| Poly1305 MAC (key structure r+s, clamping r with mask, accumulator loop mod 2^130−5, 0x01 byte per block, final s addition without mod, security properties SUF-CMA, key generation from ChaCha20 block 0 with test vector, full MAC computation example with intermediate values) | `references/poly1305.md` |
| AEAD construction (inputs/parameters per RFC 5116, encryption procedure, MAC input layout AAD+pad+ciphertext+pad+lengths, decryption with verify-before-decrypt, counter 0 for key gen / counter 1+ for encryption), security (nonce repeat consequences, constant-time tag comparison, Poly1305 implementation advice, tag truncation prohibition), AEAD test vector with full hex | `references/aead-and-security.md` |

</routing>

<reference_index>

**chacha20.md** — overview (three algorithms: ChaCha20 cipher + Poly1305 MAC + AEAD, IRTF June 2018, obsoletes 7539, why over AES: 3× faster in software, no cache timing, standby cipher), state layout (§2.3: 4×4 uint32 matrix, words 0-3 constants "expand 32-byte k", words 4-11 key as 8 LE uint32, word 12 counter limiting 256GB per key+nonce, words 13-15 nonce as 3 LE uint32, 96-bit vs original 64-bit nonce), quarter round (§2.1: a+=b d^=a d<<<16 c+=d b^=c b<<<12 a+=b d^=a d<<<8 c+=d b^=c b<<<7), column rounds (QR on 0,4,8,12 / 1,5,9,13 / 2,6,10,14 / 3,7,11,15), diagonal rounds (QR on 0,5,10,15 / 1,6,11,12 / 2,7,8,13 / 3,4,9,14), 20 rounds = 10 iterations of column+diagonal = 80 quarter rounds, block function (§2.3: initialize state, copy, 10× inner_block, add original state back mod 2^32, serialize LE, add-back makes non-invertible), optimization (copy state first, work on copy, increment counter on original, saves ~5.5%), encryption (§2.4: successive blocks with incrementing counter, XOR keystream with plaintext, decryption identical, no padding needed, discard extra keystream), block function test vector (key 00..1f, nonce 00000009 0000004a 00000000, counter 1, full serialized output)

**poly1305.md** — algorithm (§2.5: 32-byte one-time key split into r first 16 bytes + s last 16 bytes, r clamped: bytes 3,7,11,15 &=0x0f bytes 4,8,12 &=0xfc, equivalent mask 0x0ffffffc0ffffffc0ffffffc0fffffff, prime P=2^130-5, accumulator loop: read block as LE integer, add 2^(8×blocklen) as 0x01 byte, acc += block, acc = (acc × r) mod P, finally acc += s without mod reduction, output low 128 bits as 16 LE bytes), block processing detail (0x01 byte prevents zero-padding attacks), security properties (one-time key mandatory, forgery ≤ n/2^102, SUF-CMA, NOT suitable as PRF — biased + no key reuse), key generation (§2.6: chacha20_block with counter=0, return first 32 bytes, first 128→r second 128→s, discard rest, nonce must be unique not random), key generation test vector (key 80..9f nonce 000000000001020304050607 output 32 bytes), full MAC test vector (§2.5.2: key material, message "Cryptographic Forum Research Group", 3 blocks with intermediate accumulator values, final tag a8061dc1305136c6c22b8baf0c0127a9)

**aead-and-security.md** — AEAD construction (§2.8: inputs key 256-bit + nonce 96-bit + plaintext + AAD, parameters K_LEN=32 N_MIN=N_MAX=12 P_MAX=~256GB A_MAX=2^64-1 tag=16 bytes), encryption procedure (nonce = constant|iv, otk from poly1305_key_gen at counter 0, encrypt at counter 1, MAC input = AAD|pad16|ciphertext|pad16|len_aad_8LE|len_ct_8LE, tag from poly1305_mac), pad16 (zero bytes to 16-byte boundary), decryption (generate OTK, compute tag on ciphertext not plaintext, constant-time compare, decrypt only if match), design decisions (counter 0 for key gen, counter 1+ for encryption, lengths at end prevent extension attacks), security (§4: ChaCha20 256-bit security trivially constant-time, Poly1305 forgery bound, nonce uniqueness CRITICAL — repeat reveals XOR of plaintexts and compromises auth, acceptable nonce gen: counters LFSRs DES-encrypted counters NOT random NOT truncated block cipher, constant-time tag comparison mandatory not memcmp, tag truncation MUST NOT be done, Poly1305 must use constant-time arithmetic not generic bignum), implementation advice (§3: constant-time poly1305-donna, naive 288-bit integers sufficient, copy-state optimization for ChaCha20), AEAD test vector (§2.8.2: plaintext "Ladies and Gentlemen..." 114 bytes, AAD 12 bytes, key 80..9f, nonce 07000000 40..47, full OTK + ciphertext + tag hex)

</reference_index>
