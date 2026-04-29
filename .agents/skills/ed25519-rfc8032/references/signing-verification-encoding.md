# EdDSA RFC 8032 — Signing, Verification & Encoding

<signing>
## Signing

### Ed25519 (§5.1.6)

Inputs: private key (32 bytes), message M (arbitrary). For Ed25519ctx/Ed25519ph: also context C (≤255 bytes) and flag F (0 or 1).

1. Hash private key with SHA-512 → `h`. Extract secret scalar `s` (pruned lower half) and `prefix` = `h[32..63]`
2. Derive public key `A` = encode([s]B)
3. Compute `r = SHA-512(dom2(F,C) ‖ prefix ‖ PH(M))` — interpret 64-byte digest as little-endian integer
4. Compute `R = [r]B` (reduce r mod L first for efficiency). Encode R → `Rs` (32 bytes)
5. Compute `k = SHA-512(dom2(F,C) ‖ Rs ‖ A ‖ PH(M))` — interpret as little-endian integer
6. Compute `S = (r + k·s) mod L`
7. Signature = `Rs ‖ little-endian(S)` (64 bytes total; top 3 bits of final byte always zero)

**Key insight:** The nonce `r` is derived deterministically from the private key's prefix and the message. No external randomness needed during signing. This eliminates an entire class of RNG-failure attacks.

### Ed448 (§5.2.6)

Same structure but:
- Hash: SHAKE256(x, 114) instead of SHA-512
- Domain: dom4(F, C) instead of dom2(F, C)
- Prefix: `h[57..113]`
- R encoding: 57 bytes, S encoding: 57 bytes (top 10 bits of final byte always zero)
- Signature: 114 bytes total
</signing>

<verification>
## Verification

### Ed25519 (§5.1.7)

Inputs: public key A (32 bytes), message M, signature (64 bytes). For Ed25519ctx/Ed25519ph: context C, flag F.

1. Split signature into `Rs` (first 32 bytes) and `S` (last 32 bytes)
2. Decode `Rs` → point R. Decode `S` as little-endian integer. Decode `A` → point A'.
3. **Reject** if any decoding fails or **S ≥ L** (critical for malleability protection)
4. Compute `k = SHA-512(dom2(F,C) ‖ Rs ‖ A ‖ PH(M))` — interpret as little-endian integer
5. Check group equation: **[8][S]B = [8]R + [8][k]A'**

Alternatively (sufficient but not required): check **[S]B = R + [k]A'** (without cofactor multiplication).

### Ed448 (§5.2.7)

Same structure but cofactor is 4:
- Check: **[4][S]B = [4]R + [4][k]A'**
- Or: **[S]B = R + [k]A'**

### Cofactor Multiplication (§8.8)
Multiplying by the cofactor (8 or 4) is not strictly necessary for security. However, without it, different implementations may disagree on the exact set of valid signatures, which could enable fingerprinting attacks. The cofactored check accepts signatures where R or A are not in the prime-order subgroup.
</verification>

<encoding>
## Point Encoding and Decoding

### Encoding (§5.1.2, §5.2.2)
All integers are **little-endian**. A point (x, y) is encoded as:
1. Encode y-coordinate as little-endian bytes (32 bytes for Ed25519, 57 for Ed448)
2. Copy LSB of x-coordinate to MSB of the final byte

### Decoding Ed25519 (§5.1.3)

1. Interpret 32 bytes as little-endian integer. Bit 255 = `x_0` (sign bit). Clear it to get y.
2. If y ≥ p → **fail**
3. Recover x: compute `u = y² − 1`, `v = d·y² + 1`
4. Candidate x = `(u/v)^((p+3)/8)` using the trick: `x = u·v³ · (u·v⁷)^((p−5)/8) mod p`
5. Three cases:
   - `v·x² = u` → x is correct
   - `v·x² = −u` → set `x = x · 2^((p−1)/4)` (multiply by √(−1))
   - Otherwise → **no square root exists, fail**
6. If `x = 0` and `x_0 = 1` → **fail**. If `x_0 ≠ x mod 2` → set `x = p − x`
7. Return (x, y)

### Decoding Ed448 (§5.2.3)

1. Interpret 57 bytes. Bit 455 = `x_0`. Clear to get y. If y ≥ p → **fail**
2. `u = y² − 1`, `v = d·y² − 1` (note: **minus** 1, not plus — untwisted curve, a=1)
3. Candidate x = `(u/v)^((p+1)/4)` via trick: `x = u³·v · (u⁵·v³)^((p−3)/4) mod p`
4. If `v·x² = u` → x is correct. Otherwise → **fail** (simpler than Ed25519: p ≡ 3 mod 4)
5. Sign-bit correction same as Ed25519

### Modular Arithmetic Tips
- **Inversion:** Use Fermat's little theorem: `x⁻¹ = x^(p−2) mod p`
- **Square roots (Ed25519, p ≡ 5 mod 8):** candidate `x = a^((p+3)/8)`, then check/adjust with `√(−1) = 2^((p−1)/4)`
- **Square roots (Ed448, p ≡ 3 mod 4):** candidate `x = a^((p+1)/4)`, direct check
</encoding>

<point_arithmetic>
## Point Arithmetic

### Extended Coordinates (Ed25519, §5.1.4)
Point (x, y) → (X, Y, Z, T) where `x = X/Z`, `y = Y/Z`, `x·y = T/Z`.
Neutral element: (0, Z, Z, 0) for any non-zero Z.

**Addition** (complete, a = −1):
```
A = (Y1−X1)·(Y2−X2)     B = (Y1+X1)·(Y2+X2)
C = T1·2d·T2             D = Z1·2·Z2
E = B−A                  F = D−C
G = D+C                  H = B+A
X3 = E·F    Y3 = G·H    T3 = E·H    Z3 = F·G
```

**Doubling** (optimized):
```
A = X1²      B = Y1²     C = 2·Z1²
H = A+B      E = H−(X1+Y1)²
G = A−B      F = C+G
X3 = E·F    Y3 = G·H    T3 = E·H    Z3 = F·G
```

### Projective Coordinates (Ed448, §5.2.4)
Point (x, y) → (X, Y, Z) where `x = X/Z`, `y = Y/Z`.
Neutral element: (0, Z, Z) for any non-zero Z.

**Addition** (complete, a = 1):
```
A = Z1·Z2    B = A²      C = X1·X2
D = Y1·Y2    E = d·C·D   F = B−E
G = B+E      H = (X1+Y1)·(X2+Y2)
X3 = A·F·(H−C−D)    Y3 = A·G·(D−C)    Z3 = F·G
```

**Doubling** (optimized):
```
B = (X1+Y1)²    C = X1²    D = Y1²
E = C+D         H = Z1²    J = E−2·H
X3 = (B−E)·J    Y3 = E·(C−D)    Z3 = E·J
```

All formulas are **complete** — no special cases for identity, doubling, or any input.
</point_arithmetic>
