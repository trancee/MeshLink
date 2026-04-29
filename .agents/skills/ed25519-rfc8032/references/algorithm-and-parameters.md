# EdDSA RFC 8032 — Algorithm, Parameters & Key Operations

<overview>
## What EdDSA Is

Edwards-curve Digital Signature Algorithm (EdDSA) — a variant of Schnorr's signature system on (twisted) Edwards curves. RFC 8032 is Informational (IRTF, January 2017), product of the Crypto Forum Research Group.

### Five Instantiations

| Variant | Curve | Hash | Prehash | Context | Security | Sizes (key/sig) |
|---------|-------|------|---------|---------|----------|-----------------|
| **Ed25519** | edwards25519 | SHA-512 | Identity (PureEdDSA) | Empty only | ~128-bit | 32 / 64 bytes |
| **Ed25519ctx** | edwards25519 | SHA-512 | Identity | Required (SHOULD NOT be empty) | ~128-bit | 32 / 64 bytes |
| **Ed25519ph** | edwards25519 | SHA-512 | SHA-512 (HashEdDSA) | Optional | ~128-bit | 32 / 64 bytes |
| **Ed448** | edwards448 | SHAKE256(x,114) | Identity (PureEdDSA) | Optional (default empty) | ~224-bit | 57 / 114 bytes |
| **Ed448ph** | edwards448 | SHAKE256(x,114) | SHAKE256(x,64) | Optional | ~224-bit | 57 / 114 bytes |

**Recommendation:** Use Ed25519 if 128-bit security suffices. Use Ed448 otherwise. Ed25519ph/Ed448ph SHOULD NOT be used (more vulnerable to hash weaknesses).

### Key Advantages
1. High performance on varied platforms
2. **Deterministic signing** — no per-signature randomness needed
3. More resilient to side-channel attacks
4. Small keys and signatures
5. **Complete formulas** — valid for all curve points, no special cases, no expensive point validation needed
6. **Collision resilience** (PureEdDSA only) — hash collisions don't break the scheme
</overview>

<ed25519_parameters>
## Ed25519 Parameters (§5.1)

| Parameter | Value |
|-----------|-------|
| **p** (field prime) | 2^255 − 19 |
| **b** (bits) | 256 |
| **Encoding** | 255-bit little-endian of {0, ..., p−1} |
| **H(x)** | SHA-512(dom2(phflag, context) ‖ x) |
| **c** (cofactor log₂) | 3 (cofactor = 8) |
| **n** | 254 |
| **d** | −121665/121666 mod p |
| **a** | −1 |
| **B** (base point) | See RFC; y = 4/5 mod p |
| **L** (group order) | 2^252 + 27742317777372353535851937790883648493 |
| **PH(x)** | Identity (Ed25519), SHA-512 (Ed25519ph) |

### Curve Equation (Twisted Edwards)
```
a·x² + y² = 1 + d·x²·y²     (a = −1)
```

### dom2 Function
- **Ed25519:** dom2(f, c) = empty string (no domain separation)
- **Ed25519ctx/Ed25519ph:** `"SigEd25519 no Ed25519 collisions" ‖ OCTET(f) ‖ OCTET(OLEN(c)) ‖ c`
  - f = 0 for Ed25519ctx, f = 1 for Ed25519ph
  - The string prefix is 32 ASCII octets
  - Context c: max 255 octets
</ed25519_parameters>

<ed448_parameters>
## Ed448 Parameters (§5.2)

| Parameter | Value |
|-----------|-------|
| **p** | 2^448 − 2^224 − 1 |
| **b** | 456 |
| **Encoding** | 455-bit little-endian of {0, ..., p−1} |
| **H(x)** | SHAKE256(dom4(phflag, context) ‖ x, 114) |
| **c** (cofactor log₂) | 2 (cofactor = 4) |
| **n** | 447 |
| **d** | −39081 |
| **a** | 1 (untwisted Edwards) |
| **L** | 2^446 − 13818066809895115352007386748515426880336692474882178609894547503885 |
| **PH(x)** | Identity (Ed448), SHAKE256(x, 64) for Ed448ph |

### Curve Equation (Untwisted Edwards)
```
x² + y² = 1 + d·x²·y²     (a = 1)
```

### dom4 Function
Always used (even for plain Ed448):
`"SigEd448" ‖ OCTET(f) ‖ OCTET(OLEN(c)) ‖ c`
- f = 0 for Ed448, f = 1 for Ed448ph
- "SigEd448" is 8 ASCII octets
</ed448_parameters>

<key_generation>
## Key Generation

### Ed25519 (§5.1.5)

1. Private key: **32 bytes** of cryptographically secure random data
2. Hash with SHA-512 → 64-byte buffer `h`
3. **Prune** lower 32 bytes:
   - Clear lowest 3 bits of `h[0]` (multiple of cofactor 8)
   - Clear highest bit of `h[31]` (bit 255)
   - Set second-highest bit of `h[31]` (bit 254)
4. Interpret as little-endian integer → secret scalar `s`
5. Compute `A = [s]B` (fixed-base scalar multiplication)
6. Encode A: little-endian y-coordinate (32 bytes), copy LSB of x to MSB of final byte

**The upper 32 bytes `h[32..63]` are the "prefix" — used during signing, never as a scalar.**

### Ed448 (§5.2.5)

1. Private key: **57 bytes** of cryptographically secure random data
2. Hash with SHAKE256(x, 114) → 114-byte buffer `h`
3. **Prune** lower 57 bytes:
   - Clear lowest 2 bits of `h[0]` (multiple of cofactor 4)
   - Clear all 8 bits of `h[56]` (last byte)
   - Set highest bit of `h[55]` (bit 447)
4. Secret scalar `s`, compute `A = [s]B`
5. Encode A: little-endian y-coordinate (57 bytes), copy LSB of x to MSB of final byte

### Pruning Rationale
- **Clear low bits:** scalar is a multiple of the cofactor, ensuring the public key is in the prime-order subgroup
- **Set high bit:** constant-time scalar multiplication — fixed number of doublings
- **Clear top bit (Ed25519):** keeps scalar < 2^255, preventing overflow during computation
</key_generation>
