# SHA-2 RFC 6234 — Algorithm Specification

<overview>
## What RFC 6234 Defines

US Secure Hash Algorithms (SHA-2 family) plus SHA-based HMAC and HKDF. IETF Informational (May 2011). Obsoletes RFC 4634. Updates RFC 3174 (SHA-1).

Specifies: SHA-224, SHA-256, SHA-384, SHA-512. Includes open-source C implementation and HMAC/HKDF code. Based on FIPS 180-2.

### Algorithm Family

| Algorithm | Word Size | Block Size | Digest Size | Max Input | Rounds | Constants |
|-----------|-----------|------------|-------------|-----------|--------|-----------|
| **SHA-224** | 32-bit | 512-bit (64 bytes) | 224 bits (28 bytes) | < 2^64 bits | 64 | 64 × 32-bit |
| **SHA-256** | 32-bit | 512-bit (64 bytes) | 256 bits (32 bytes) | < 2^64 bits | 64 | 64 × 32-bit |
| **SHA-384** | 64-bit | 1024-bit (128 bytes) | 384 bits (48 bytes) | < 2^128 bits | 80 | 80 × 64-bit |
| **SHA-512** | 64-bit | 1024-bit (128 bytes) | 512 bits (64 bytes) | < 2^128 bits | 80 | 80 × 64-bit |

### Relationships
- SHA-224 and SHA-256 use **identical processing** — differ only in H(0) and output truncation
- SHA-384 and SHA-512 use **identical processing** — differ only in H(0) and output truncation
- SHA-224 = SHA-256 with different IV, output first 7 words (224 bits)
- SHA-384 = SHA-512 with different IV, output first 6 words (384 bits)

### Byte Order
**Big-endian** throughout. Most significant bit/byte first in all words and blocks.
</overview>

<padding>
## Message Padding (§4)

### SHA-224 / SHA-256 (§4.1)
Pad message of L bits to a multiple of 512 bits:
1. Append bit `1`
2. Append K zero bits, where K is smallest non-negative solution to: `(L + 1 + K) mod 512 = 448`
3. Append 64-bit big-endian representation of L

### SHA-384 / SHA-512 (§4.2)
Pad message of L bits to a multiple of 1024 bits:
1. Append bit `1`
2. Append K zero bits, where K is smallest non-negative solution to: `(L + 1 + K) mod 1024 = 896`
3. Append 128-bit big-endian representation of L

### Example (for SHA-256)
Message "abcde" (40 bits):
```
61626364 65800000 00000000 00000000
00000000 00000000 00000000 00000000
00000000 00000000 00000000 00000000
00000000 00000000 00000000 00000028
```
The `0x80` byte is the `1` bit + 7 zero bits. Final word `0x00000028` = 40 decimal (message length in bits).
</padding>

<functions_256>
## Functions and Constants: SHA-224/SHA-256 (§5.1)

Six logical functions on 32-bit words:

```
CH(x, y, z)    = (x AND y) XOR ((NOT x) AND z)
MAJ(x, y, z)   = (x AND y) XOR (x AND z) XOR (y AND z)
BSIG0(x)       = ROTR²(x)  XOR ROTR¹³(x) XOR ROTR²²(x)
BSIG1(x)       = ROTR⁶(x)  XOR ROTR¹¹(x) XOR ROTR²⁵(x)
SSIG0(x)       = ROTR⁷(x)  XOR ROTR¹⁸(x) XOR SHR³(x)
SSIG1(x)       = ROTR¹⁷(x) XOR ROTR¹⁹(x) XOR SHR¹⁰(x)
```

64 constant 32-bit words K₀..K₆₃ — first 32 bits of fractional parts of cube roots of first 64 primes:
```
428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5
d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174
e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da
983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967
27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85
a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070
19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3
748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2
```
</functions_256>

<functions_512>
## Functions and Constants: SHA-384/SHA-512 (§5.2)

Same logical function structure, different rotation amounts, 64-bit words:

```
CH(x, y, z)    = (x AND y) XOR ((NOT x) AND z)
MAJ(x, y, z)   = (x AND y) XOR (x AND z) XOR (y AND z)
BSIG0(x)       = ROTR²⁸(x) XOR ROTR³⁴(x) XOR ROTR³⁹(x)
BSIG1(x)       = ROTR¹⁴(x) XOR ROTR¹⁸(x) XOR ROTR⁴¹(x)
SSIG0(x)       = ROTR¹(x)  XOR ROTR⁸(x)  XOR SHR⁷(x)
SSIG1(x)       = ROTR¹⁹(x) XOR ROTR⁶¹(x) XOR SHR⁶(x)
```

80 constant 64-bit words K₀..K₇₉ — first 64 bits of fractional parts of cube roots of first 80 primes. (First 16 shown):
```
428a2f98d728ae22 7137449123ef65cd b5c0fbcfec4d3b2f e9b5dba58189dbbc
3956c25bf348b538 59f111f1b605d019 923f82a4af194f9b ab1c5ed5da6d8118
d807aa98a3030242 12835b0145706fbe 243185be4ee4b28c 550c7dc3d5ffb4e2
72be5d74f27b896f 80deb1fe3b1696b1 9bdc06a725c71235 c19bf174cf692694
```
(Full 80 constants in RFC §5.2)
</functions_512>
