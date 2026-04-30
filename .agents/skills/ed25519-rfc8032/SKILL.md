---
name: ed25519-rfc8032
description: RFC 8032 — Edwards-Curve Digital Signature Algorithm (EdDSA) reference. Covers all five instantiations (Ed25519, Ed25519ctx, Ed25519ph, Ed448, Ed448ph), key generation with scalar pruning (cofactor clearing, high-bit clamping), deterministic signing via SHA-512/SHAKE256, verification with cofactor multiplication, point encoding/decoding (compressed y + x sign bit, little-endian), extended/projective coordinate arithmetic, domain separation (dom2/dom4), PureEdDSA vs HashEdDSA trade-offs, security considerations (constant-time, malleability S<L check, nonce determinism), and test vectors. Use when implementing EdDSA signing/verification, key generation, point encoding, scalar clamping, or any RFC 8032 question.
---

<essential_principles>

**RFC 8032** defines EdDSA — Edwards-curve Digital Signature Algorithm. IRTF Informational (Jan 2017). Five instantiations: Ed25519, Ed25519ctx, Ed25519ph, Ed448, Ed448ph.

### What an Implementer Must Know

**Ed25519 is the recommended variant** (~128-bit security, 32-byte keys, 64-byte signatures, SHA-512). Use Ed448 only if ~224-bit security is needed. Ed25519ph/Ed448ph SHOULD NOT be used.

**Key generation:** Hash private key (32 random bytes) with SHA-512. Prune lower half: clear bottom 3 bits (cofactor), clear bit 255, set bit 254. This is the secret scalar s. Public key A = [s]B, compressed as y-coordinate + x sign bit. Upper half of hash = prefix (used in signing, never as scalar).

**Signing is deterministic:** Nonce r = H(prefix ‖ M), not random. R = [r]B. S = (r + H(R ‖ A ‖ M)·s) mod L. Signature = R ‖ S. No per-signature RNG needed.

**Verification:** Decode R, S, A. Reject if S ≥ L (malleability). Check [8S]B = [8]R + [8k]A' where k = H(R ‖ A ‖ M). Cofactor multiply (×8) recommended but not strictly required.

**Point encoding:** Little-endian y-coordinate, x sign bit in MSB of last byte. Decoding recovers x via square root of (y²−1)/(dy²+1).

**Complete formulas:** Edwards addition works for ALL point pairs, no exceptions. Extended homogeneous coordinates (X,Y,Z,T) for Ed25519; projective (X,Y,Z) for Ed448.

**Constant-time required for signing:** Same instructions and memory accesses regardless of key value. Verification can be variable-time (public inputs).

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Generic EdDSA algorithm (11 parameters), Ed25519 parameters (p, b, c, n, d, a, B, L, curve equation), Ed448 parameters, domain separation (dom2/dom4), key generation with pruning rationale, five variant comparison table | `references/algorithm-and-parameters.md` |
| Signing procedure (Ed25519 + Ed448, deterministic nonce derivation), verification (group equation with cofactor, S<L check), point encoding/decoding (compressed y + x sign bit, square root recovery for both curves), modular arithmetic tips (Fermat inversion, Tonelli-Shanks), point arithmetic (extended homogeneous for Ed25519, projective for Ed448, complete addition and doubling formulas) | `references/signing-verification-encoding.md` |
| Security considerations (constant-time implementation, deterministic signing, context usage, signature malleability, PureEdDSA vs HashEdDSA, mixing prehashes, large messages, cofactor semantics, SHAKE256 properties), test vectors (Ed25519 4 vectors with hex, Ed25519ctx 4 vectors, Ed25519ph 1 vector, Ed448 6+ vectors, Ed448ph 3 vectors), implementation notes (endianness, point comparison, scalar multiplication, Python reference) | `references/security-and-test-vectors.md` |

</routing>

<reference_index>

**algorithm-and-parameters.md** — what EdDSA is (Schnorr variant on Edwards curves, IRTF Jan 2017), five instantiations table (Ed25519/Ed25519ctx/Ed25519ph/Ed448/Ed448ph with curve/hash/prehash/context/security/sizes), six key advantages (deterministic, side-channel resilient, small, complete formulas, collision resilient), Ed25519 parameters (p=2^255-19, b=256, c=3 cofactor=8, n=254, d=-121665/121666, a=-1, L=2^252+27742..., SHA-512, twisted Edwards equation), dom2 function (empty for Ed25519, 32-byte prefix string for ctx/ph), Ed448 parameters (p=2^448-2^224-1, b=456, c=2 cofactor=4, n=447, d=-39081, a=1, SHAKE256, untwisted Edwards equation), dom4 function (always used, 8-byte prefix), key generation Ed25519 (32 random bytes, SHA-512, prune: clear low 3 bits + clear bit 255 + set bit 254, scalar multiply, compress), key generation Ed448 (57 random bytes, SHAKE256, prune: clear low 2 bits + clear byte 56 + set bit 447), pruning rationale (cofactor clearing, constant-time high bit, overflow prevention)

**signing-verification-encoding.md** — Ed25519 signing (§5.1.6: hash private key, extract scalar+prefix, r=H(prefix‖M) deterministic nonce, R=[r]B, k=H(R‖A‖M), S=(r+k·s) mod L, 64-byte signature), Ed448 signing (§5.2.6: SHAKE256, dom4, 114-byte signature), Ed25519 verification (§5.1.7: decode R+S+A, reject S≥L, k=H(R‖A‖M), check [8S]B=[8]R+[8k]A'), Ed448 verification (cofactor 4), cofactor multiplication semantics (§8.8: not strictly required but prevents fingerprinting), point encoding (little-endian y + x sign bit in MSB), Ed25519 decoding (§5.1.3: extract sign bit, recover y, compute u=y²-1 v=dy²+1, candidate root via combined inversion+sqrt trick, three cases with √(-1) adjustment, sign correction), Ed448 decoding (§5.2.3: v=dy²-1 minus not plus, simpler sqrt for p≡3 mod 4), modular arithmetic (Fermat inversion x^(p-2), Ed25519 sqrt x^((p+3)/8) with √(-1)=2^((p-1)/4), Ed448 sqrt x^((p+1)/4)), Ed25519 extended homogeneous coordinates (X,Y,Z,T with x=X/Z y=Y/Z xy=T/Z, complete addition 8M+0S, optimized doubling), Ed448 projective coordinates (X,Y,Z with x=X/Z y=Y/Z, complete addition, optimized doubling)

**security-and-test-vectors.md** — side channels (§8.1: constant-time arithmetic, no data-dependent branches, complete formulas help, RFC examples NOT side-channel safe), deterministic signing (§8.2: no per-signature randomness, protects against bad-RNG attacks, key generation still needs randomness), context usage (§8.3: constant protocol-specified strings, not variable, error-prone if opportunistic, API percolation problem), signature malleability (§8.4: S<L check prevents adding multiples of L, implementations MUST verify), PureEdDSA vs HashEdDSA (§8.5: ph variants SHOULD NOT be used, PureEdDSA has collision resilience, HashEdDSA loses it, ph exists for legacy single-pass APIs), mixing prehashes safe (§8.6: same keypair OK for Ed25519+ctx+ph due to domain separation), large messages (§8.7: must buffer entire message, IUF verification interface prone to misuse), cofactor (§8.8: cofactored accepts more signatures, without it implementations may disagree), SHAKE256 (§8.9: XOF properties acceptable with fixed output lengths), Ed25519 test vectors (4 tests: empty/1-byte/2-byte/1023-byte messages with full hex key+sig), Ed25519ctx test vectors (4 tests with contexts), Ed25519ph test vector (1 test), Ed448 test vectors (6+ tests including context variant), Ed448ph test vectors (3 tests), implementation notes (little-endian everywhere, point comparison via cross-multiply, constant-time scalar mul for signing, Python reference in §6 and Appendix A ~400 lines NOT for production)

</reference_index>
