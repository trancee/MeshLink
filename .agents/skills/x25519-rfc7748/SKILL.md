---
name: x25519-rfc7748
description: Complete reference for RFC 7748 — Elliptic Curves for Security. Specifies Curve25519 (p=2^255-19, A=486662, cofactor 8, ~128-bit security) and Curve448 (p=2^448-2^224-1, A=156326, cofactor 4, ~224-bit security) in Montgomery form, the X25519 and X448 scalar multiplication functions with Montgomery ladder algorithm, scalar clamping (clear low bits for cofactor, set high bit for constant-time), u-coordinate encoding (little-endian, mask MSB for X25519, accept non-canonical values), Diffie-Hellman key agreement protocol, equivalent Edwards curves (edwards25519 for Ed25519 with birational maps, edwards448/Goldilocks for Ed448 with 4-isogeny maps), constant-time cswap, side-channel considerations (constant operation sequence, no data-dependent branches or memory accesses), security properties (no contributory behaviour with small-order points, all-zero output check, equivalent public keys, implementation fingerprinting), deterministic curve generation (nothing-up-my-sleeve with Frobenius trace, MOV degree, CM discriminant checks), and test vectors (point-to-point and iterated for both curves, plus ECDH vectors). Use when implementing X25519 or X448 key exchange, working with Curve25519/Curve448 scalar multiplication, understanding scalar clamping, mapping between Montgomery and Edwards forms, debugging DH key agreement, or any question about RFC 7748.
---

<essential_principles>

**RFC 7748** defines Curve25519, Curve448, and their X25519/X448 scalar multiplication functions for Diffie-Hellman key agreement. IRTF Informational (Jan 2016).

### What an Implementer Must Know

**Two curves:** Curve25519 (p=2^255−19, ~128-bit, cofactor 8, base u=9, 32-byte keys) and Curve448 (p=2^448−2^224−1, ~224-bit, cofactor 4, base u=5, 56-byte keys). Montgomery form v²=u³+Au²+u.

**Scalar clamping:** X25519: clear bottom 3 bits (÷8 cofactor), clear bit 255, set bit 254. X448: clear bottom 2 bits (÷4 cofactor), set bit 447. This ensures constant-time scalar multiplication and prime-order subgroup membership.

**X25519/X448 function:** Takes scalar k + u-coordinate → output u-coordinate. Montgomery ladder processes bits top-to-bottom with constant-time cswap. Final inversion via Fermat (z^(p−2)). All little-endian. X25519 MUST mask bit 255 of input u. MUST accept non-canonical values (≥p), reducing mod p.

**Diffie-Hellman:** Alice sends X25519(a, 9), Bob sends X25519(b, 9). Shared secret K = X25519(a, K_B) = X25519(b, K_A). MAY check for all-zero output (small-order input eliminates other party's contribution). Use KDF including K, K_A, K_B.

**Edwards equivalents:** edwards25519 (twisted, a=−1, d=−121665/121666) is birationally equivalent to Curve25519 — used by Ed25519. edwards448 (untwisted, a=1, d=−39081) is 4-isogenous to Curve448 — used by Ed448.

**Constant-time:** Same field operations for every scalar value. cswap via bitwise mask. No data-dependent branches, jumps, or memory accesses.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Curve parameters (Curve25519 p/A/cofactor/order/base, Curve448 same), Montgomery and Edwards equations, birational maps (Curve25519↔edwards25519), 4-isogeny maps (Curve448↔edwards448), X25519/X448 functions (scalar clamping, u-coordinate decoding with MSB mask and non-canonical acceptance, Montgomery ladder algorithm with a24 constant, output encoding), Diffie-Hellman protocol (key generation, exchange, shared secret, all-zero check, KDF), ECDH test vectors | `references/curves-functions-dh.md` |
| Side-channel considerations (constant-time cswap implementation, memory access patterns, arithmetic indistinguishability, clamping for side channels), security (levels ~128/~224, no contributory behaviour with cofactors, equivalent public keys, implementation fingerprinting from non-canonical/twist rejection), test vectors (X25519 2 point-to-point + iterated 1/1K/1M, X448 2 point-to-point + iterated 1/1K/1M), deterministic curve generation (Frobenius trace, MOV degree, CM discriminant, minimal A with (A−2)÷4, cofactor selection by p mod 4, minimal base point) | `references/security-and-test-vectors.md` |

</routing>

<reference_index>

**curves-functions-dh.md** — overview (two curves for DH key agreement, IRTF Jan 2016, Montgomery form for constant-time), comparison table (Curve25519 p=2^255-19 A=486662 a24=121665 cofactor=8 base=9 32-byte vs Curve448 p=2^448-2^224-1 A=156326 a24=39081 cofactor=4 base=5 56-byte), key properties (exception-free, side-channel resistant, deterministic generation), Montgomery curve equation (v²=u³+Au²+u), edwards25519 twisted Edwards (−x²+y²=1+dx²y² with a=−1, d=−121665/121666, birational maps u=(1+y)/(1−y) x=√(−486664)·u/v), edwards448 untwisted Edwards (x²+y²=1+dx²y² with a=1 d=−39081, 4-isogeny maps), X25519 function (§5: scalar+u-coordinate→u-coordinate, little-endian encoding), scalar clamping (X25519: k[0]&=248 k[31]&=127 k[31]|=64 → 2^254+8·value, X448: k[0]&=252 k[55]|=128 → 2^447+4·value), u-coordinate decoding (X25519 MUST mask bit 255, MUST accept non-canonical ≥p reducing mod p), Montgomery ladder (full pseudocode: x1=u x2=1 z2=0 x3=u z3=1, bit-by-bit with cswap, AA·BB/E·(AA+a24·E) formulas, final z^(p−2) inversion), output encoding (little-endian, MSB zero for X25519), DH protocol (§6: generate random bytes, compute X(k,base), exchange, shared secret K=X(a,K_B)=X(b,K_A), all-zero check by OR all bytes, KDF with K+K_A+K_B), ECDH test vectors (X25519 Alice/Bob private+public+shared, X448 same)

**security-and-test-vectors.md** — side channels (§5.1: constant operation sequence eliminates common leakage, cswap via mask=0−swap with XOR, memory access patterns must not depend on scalar, arithmetic must not leak via timing, clamping ensures fixed doubling count), security (§7: Curve25519 slightly under 128-bit acceptable, Curve448 ~224-bit hedge against analytical advance, both broken by quantum, no contributory behaviour due to cofactors 8/4 — small-order input eliminates other party's key contribution — detectable via all-zero check — many implementations DON'T check, equivalent public keys vulnerability if used as identifier without including in KDF, implementation fingerprinting from non-conforming generic EC libraries rejecting twist/non-canonical), X25519 test vectors (2 point-to-point with scalar+u+output hex, iterated starting k=u=9 after 1/1000/1000000 iterations), X448 test vectors (2 point-to-point, iterated starting k=u=5), deterministic generation (Appendix A: Frobenius trace ∉{0,1}, MOV degree >(order−1)/100, CM discriminant >2^100, minimal positive A>2 with (A−2)%4==0, p≡1 mod 4 cofactors {8,4} for twist safety, p≡3 mod 4 cofactors {4,4}, base point minimal u in prime-order subgroup, Sage scripts for reproduction)

</reference_index>
