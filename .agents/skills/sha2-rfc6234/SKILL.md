---
name: sha2-rfc6234
description: Complete reference for RFC 6234 — US Secure Hash Algorithms (SHA and SHA-based HMAC and HKDF). Covers SHA-224, SHA-256, SHA-384, and SHA-512 from the SHA-2 family. Includes the algorithm family comparison (word sizes, block sizes, digest sizes, round counts, max input lengths), message padding (append 1-bit + zeros + length for 512-bit or 1024-bit alignment), six logical functions per variant (CH, MAJ, BSIG0, BSIG1, SSIG0, SSIG1 with all rotation constants), round constants (cube roots of primes), initial hash values (square roots of primes, nothing-up-my-sleeve), message schedule expansion (W array from 16 to 64 or 80 words), 64-round compression for SHA-224/256 (T1=h+BSIG1(e)+CH(e,f,g)+Kt+Wt, T2=BSIG0(a)+MAJ(a,b,c), rotate working variables), 80-round compression for SHA-384/512, hash value update (add working variables back), output truncation (SHA-224 first 7 words, SHA-384 first 6 words), HMAC construction (RFC 2104), HKDF extract-and-expand (RFC 5869), and ASN.1 OIDs. Use when implementing SHA-256 or SHA-512, understanding the compression function, working with HMAC-SHA256, debugging hash computation, or any question about RFC 6234 or the SHA-2 family.
---

<essential_principles>

**RFC 6234** specifies SHA-224, SHA-256, SHA-384, SHA-512 (the SHA-2 family), plus HMAC and HKDF based on them. IETF Informational (May 2011). Obsoletes RFC 4634.

### What an Implementer Must Know

**SHA-256:** 32-bit words, 512-bit blocks, 64 rounds. State = 8 × 32-bit words. Message schedule: first 16 words from block, expand to 64 via `Wt = SSIG1(W(t-2)) + W(t-7) + SSIG0(W(t-15)) + W(t-16)`. Each round: `T1 = h + BSIG1(e) + CH(e,f,g) + Kt + Wt`, `T2 = BSIG0(a) + MAJ(a,b,c)`, then rotate a-h. Add working variables back to state. All mod 2^32. Padding: 1-bit + zeros + 64-bit length, aligned to 512 bits. Output: all 8 words (256 bits).

**SHA-512:** Same structure as SHA-256 but 64-bit words, 1024-bit blocks, 80 rounds. Different rotation constants. All mod 2^64. Padding: 1-bit + zeros + 128-bit length, aligned to 1024 bits.

**SHA-224 = SHA-256 with different IV, output first 7 words.** SHA-384 = SHA-512 with different IV, output first 6 words.

**Initial values:** fractional parts of square roots of primes (first 8 for 256/512, 9th-16th for 224/384). **Round constants:** fractional parts of cube roots of primes (64 for 256, 80 for 512).

**Six functions:** `CH(x,y,z) = (x AND y) XOR ((NOT x) AND z)`, `MAJ(x,y,z) = (x AND y) XOR (x AND z) XOR (y AND z)`, plus BSIG0/BSIG1 (big sigma with rotations) and SSIG0/SSIG1 (small sigma with rotations+shift). Rotation amounts differ between SHA-256 (2/13/22, 6/11/25, 7/18/3, 17/19/10) and SHA-512 (28/34/39, 14/18/41, 1/8/7, 19/61/6).

**Big-endian** byte order throughout (unlike ChaCha20/Ed25519 which are little-endian).

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Algorithm overview (family comparison table, relationships SHA-224=SHA-256 truncated etc.), message padding (512-bit and 1024-bit alignment, 1-bit + zeros + length), logical functions and rotation constants for SHA-224/256 (CH, MAJ, BSIG0/1, SSIG0/1 with 32-bit rotations), logical functions for SHA-384/512 (64-bit rotations), all 64 round constants K for SHA-256, first 16 of 80 K for SHA-512 | `references/algorithm-specification.md` |
| SHA-256 processing (message schedule W expansion 16→64, working variable init, 64-round compression loop with T1/T2, hash update), SHA-512 processing (80-round variant, mod 2^64), initial hash values for all four variants (SHA-256/224/512/384 H(0) with hex), nothing-up-my-sleeve derivation (square roots for H(0), cube roots for K), output rules (truncation for 224/384), HMAC construction (RFC 2104 formula), HKDF extract-and-expand (RFC 5869), ASN.1 OIDs | `references/processing-and-initialization.md` |

</routing>

<reference_index>

**algorithm-specification.md** — overview (SHA-2 family with HMAC/HKDF, IETF May 2011, obsoletes 4634, based on FIPS 180-2), algorithm family table (SHA-224: 32-bit words 512-bit blocks 224-bit digest 64 rounds <2^64 input; SHA-256: same but 256-bit digest; SHA-384: 64-bit words 1024-bit blocks 384-bit digest 80 rounds <2^128 input; SHA-512: same but 512-bit digest), relationships (SHA-224=SHA-256 different IV+truncated, SHA-384=SHA-512 different IV+truncated), big-endian byte order, message padding §4 (SHA-224/256: append 1-bit + K zeros where (L+1+K) mod 512=448 + 64-bit length; SHA-384/512: append 1-bit + K zeros where (L+1+K) mod 1024=896 + 128-bit length; worked example for "abcde"), SHA-256 functions §5.1 (CH=(x AND y) XOR ((NOT x) AND z), MAJ=(x AND y) XOR (x AND z) XOR (y AND z), BSIG0=ROTR2 XOR ROTR13 XOR ROTR22, BSIG1=ROTR6 XOR ROTR11 XOR ROTR25, SSIG0=ROTR7 XOR ROTR18 XOR SHR3, SSIG1=ROTR17 XOR ROTR19 XOR SHR10), all 64 SHA-256 round constants K (428a2f98..c67178f2), SHA-512 functions §5.2 (BSIG0=ROTR28/34/39, BSIG1=ROTR14/18/41, SSIG0=ROTR1/8 SHR7, SSIG1=ROTR19/61 SHR6), first 16 of 80 SHA-512 round constants

**processing-and-initialization.md** — SHA-256 processing §6.2 (step 1: message schedule W for t=0-15 direct copy then t=16-63 SSIG1(W(t-2))+W(t-7)+SSIG0(W(t-15))+W(t-16); step 2: init a-h from H(i-1); step 3: 64-round loop T1=h+BSIG1(e)+CH(e,f,g)+Kt+Wt T2=BSIG0(a)+MAJ(a,b,c) then h=g g=f f=e e=d+T1 d=c c=b b=a a=T1+T2; step 4: add a-h back to H(i-1) all mod 2^32; output SHA-256 all 8 words SHA-224 first 7), SHA-512 processing §6.4 (same structure but 80 rounds 64-bit words mod 2^64 different rotations, output SHA-512 all 8 words SHA-384 first 6), initial hash values (SHA-256 H(0): 6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19 from square roots of first 8 primes; SHA-224 H(0) from primes 23-53; SHA-512 H(0) 64-bit values; SHA-384 H(0) from primes 23-53), nothing-up-my-sleeve derivation (K from cube roots, H(0) from square roots, different prime ranges prevent overlap), HMAC §7.1 (H((K' XOR opad)||H((K' XOR ipad)||text)) per RFC 2104), HKDF §7.2 (extract PRK=HMAC(salt,IKM) then expand T(i)=HMAC(PRK,T(i-1)||info||i) per RFC 5869), ASN.1 OIDs (sha224=2.16.840.1.101.3.4.2.4, sha256=...2.1, sha384=...2.2, sha512=...2.3)

</reference_index>
