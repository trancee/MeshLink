# X25519/X448 RFC 7748 — Curves, Functions & Diffie-Hellman

<overview>
## What RFC 7748 Defines

Two elliptic curves and their scalar multiplication functions for use in Diffie-Hellman key agreement. IRTF Informational (January 2016), Crypto Forum Research Group.

### Two Curves, Two Security Levels

| Property | Curve25519 / X25519 | Curve448 / X448 |
|----------|---------------------|-----------------|
| Security | ~128-bit | ~224-bit |
| Prime p | 2^255 − 19 | 2^448 − 2^224 − 1 |
| p mod 4 | 1 | 3 |
| Montgomery A | 486662 | 156326 |
| a24 = (A−2)/4 | 121665 | 39081 |
| Cofactor | 8 | 4 |
| Base point u | 9 | 5 |
| Scalar size | 32 bytes (256 bits) | 56 bytes (448 bits) |
| Output size | 32 bytes | 56 bytes |
| Group order | 2^252 + 0x14def9dea2f79cd65812631a5cf5d3ed | 2^446 − 0x8335dc163bb124b65129c96fde933d8d723a70aadc873d6d54a7bb0d |

### Key Properties
- **Montgomery form** enables efficient, constant-time scalar multiplication (Montgomery ladder)
- **Exception-free** — no special cases in the arithmetic
- **Resistant to side-channel attacks** — constant-time by design (same field operations for all scalar values)
- **Deterministic generation** — curves produced by objective procedure to eliminate backdoor concerns
- Birationally equivalent to Edwards curves used for signatures (EdDSA/RFC 8032)
</overview>

<curve_equations>
## Curve Equations

### Montgomery Form
```
v² = u³ + A·u² + u     (mod p)
```

### Equivalent Edwards Forms

**edwards25519** (twisted Edwards, used by Ed25519):
```
−x² + y² = 1 + d·x²·y²    (a = −1)
```
d = 37095705934669439343138083508754565189542113879843219016388785533085940283555

**Birational maps (Curve25519 ↔ edwards25519):**
```
(u, v) → (x, y):   x = √(−486664)·u/v,    y = (u−1)/(u+1)
(x, y) → (u, v):   u = (1+y)/(1−y),        v = √(−486664)·u/x
```

**edwards448** (untwisted Edwards, used by Ed448):
```
x² + y² = 1 + d·x²·y²    (a = 1)
```
d = −39081

**Note:** edwards448 is 4-isogenous to (not birationally equivalent to) the curve448 Montgomery curve. The isogeny maps are more complex than birational maps.

**4-isogeny maps (curve448 ↔ edwards448):**
```
(u, v) → (x, y):  x = 4v(u²−1)/(u⁴−2u²+4v²+1)
                   y = −(u⁵−2u³−4uv²+u)/(u⁵−2u²v²−2u³−2v²+u)
(x, y) → (u, v):  u = y²/x²
                   v = (2−x²−y²)·y/x³
```
</curve_equations>

<x25519_function>
## The X25519 and X448 Functions (§5)

Functions take a scalar k (32 or 56 bytes) and a u-coordinate (32 or 56 bytes) and return a u-coordinate. All encoding is **little-endian**.

### Scalar Decoding (Clamping)

**X25519 (decodeScalar25519):**
```
k[0]  &= 248    // Clear lowest 3 bits (multiple of cofactor 8)
k[31] &= 127    // Clear bit 255 (MSB)
k[31] |= 64     // Set bit 254
```
Result: integer of form 2^254 + 8·(value between 0 and 2^251 − 1).

**X448 (decodeScalar448):**
```
k[0]  &= 252    // Clear lowest 2 bits (multiple of cofactor 4)
k[55] |= 128    // Set bit 447
```
Result: integer of form 2^447 + 4·(value between 0 and 2^445 − 1).

### u-Coordinate Decoding

- Little-endian byte array to integer
- **X25519 only:** MUST mask bit 255 (MSB of last byte) to zero. Preserves compatibility with formats reserving sign bit.
- MUST accept **non-canonical values** (≥ p) and reduce mod p

### Montgomery Ladder (§5)

```
x_1 = u
x_2 = 1;  z_2 = 0
x_3 = u;  z_3 = 1
swap = 0

For t = bits−1 down to 0:
    k_t = (k >> t) & 1
    swap ^= k_t
    (x_2, x_3) = cswap(swap, x_2, x_3)
    (z_2, z_3) = cswap(swap, z_2, z_3)
    swap = k_t

    A  = x_2 + z_2       AA = A²
    B  = x_2 − z_2       BB = B²
    E  = AA − BB
    C  = x_3 + z_3       D  = x_3 − z_3
    DA = D · A            CB = C · B
    x_3 = (DA + CB)²
    z_3 = x_1 · (DA − CB)²
    x_2 = AA · BB
    z_2 = E · (AA + a24 · E)

(x_2, x_3) = cswap(swap, x_2, x_3)
(z_2, z_3) = cswap(swap, z_2, z_3)

Return x_2 · z_2^(p−2)    // Inversion via Fermat's little theorem
```

bits = 255 for X25519, 448 for X448. a24 = 121665 (X25519) or 39081 (X448). All arithmetic mod p.

### Output Encoding
Encode result as 32 or 56 bytes, little-endian. For X25519, MSB of last byte MUST be zero.
</x25519_function>

<diffie_hellman>
## Diffie-Hellman Protocol (§6)

### X25519 ECDH
1. Alice generates 32 random bytes `a`, computes K_A = X25519(a, 9) → sends to Bob
2. Bob generates 32 random bytes `b`, computes K_B = X25519(b, 9) → sends to Alice
3. Alice computes K = X25519(a, K_B). Bob computes K = X25519(b, K_A).
4. Shared secret: K = X25519(a, X25519(b, 9)) = X25519(b, X25519(a, 9))
5. Both MAY check if K is all-zero and abort (small-order point input)
6. Derive symmetric key from K, K_A, K_B using a KDF

### X448 ECDH
Same procedure, but 56 random bytes and base point u = 5.

### All-Zero Check (§7)
The X25519/X448 functions return all-zero if the input is a small-order point (order divides cofactor). This eliminates the other party's contribution to the shared secret. **Check by ORing all bytes and testing for zero** (constant-time).

### ECDH Test Vectors

**X25519:**
```
Alice private:  77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a
Alice public:   8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a
Bob private:    5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb
Bob public:     de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f
Shared secret:  4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742
```

**X448:**
```
Alice private:  9a8f4925d1519f5775cf46b04b5800d4ee9ee8bae8bc5565d498c28dd9c9baf574a9419744897391006382a6f127ab1d9ac2d8c0a598726b
Alice public:   9b08f7cc31b7e3e67d22d5aea121074a273bd2b83de09c63faa73d2c22c5d9bbc836647241d953d40c5b12da88120d53177f80e532c41fa0
Bob private:    1c306a7ac2a0e2e0990b294470cba339e6453772b075811d8fad0d1d6927c120bb5ee8972b0d3e21374c9c921b09d1b0366f10b65173992d
Bob public:     3eb7a829b0cd20f5bcfc0b599b6feccf6da4627107bdb0d4f345b43027d8b972fc3e34fb4232a13ca706dcb57aec3dae07bdc1c67bf33609
Shared secret:  07fff4181ac6cc95ec1c16a94a0f74d12da232ce40a77552281d282bb60c0b56fd2464c335543936521c24403085d59a449a5037514a879d
```
</diffie_hellman>
