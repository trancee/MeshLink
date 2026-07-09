---
name: wycheproof
description: Project Wycheproof (C2SP/wycheproof) reference — 340+ JSON test vector files covering 30+ crypto algorithms, detecting 80+ attack categories. Covers AEAD (AES-GCM, ChaCha20-Poly1305, AES-CCM, AES-SIV in both AEAD and deterministic forms, AEGIS, ASCON, MORUS, AES-GMAC, etc.), signatures (ECDSA across NIST/brainpool/Koblitz/weak curves, EdDSA, DSA, RSA PKCS#1/PSS, ML-DSA), key exchange (ECDH across NIST/brainpool/binary curves, X25519, X448, ML-KEM), MACs (HMAC, CMAC, KMAC, SipHash, VMAC), KDFs (HKDF, PBKDF2, PBES2), RSA encryption (incl. multi-prime OAEP), key wrap, FPE, BLS, primality, JWC/JWE/JWS/JOSE content encryption. JSON format (algorithm, testGroups, tests with valid/invalid/acceptable results, notes with bugType/CVEs, optional/inconsistent generatorVersion). Data types (HexBytes, BigInt, Asn, Der, Pem). Use when writing tests against Wycheproof vectors, integrating crypto test vectors into CI, choosing vector files for an algorithm, or validating crypto implementations.
---

<essential_principles>

**Project Wycheproof** — community-managed (C2SP) repository of JSON test vectors for validating crypto implementations against known attacks, spec edge cases, and implementation bugs. 340+ test vector files, 30+ algorithms, 80+ attack categories.

### Setup

```bash
# Git submodule (recommended)
git submodule add https://github.com/C2SP/wycheproof.git tests/wycheproof

# Go module (provides go:embed access)
import "github.com/C2SP/wycheproof"
```

Vectors live in `testvectors_v1/` (the old `testvectors/` directory was merged into `testvectors_v1/`). Schemas live in `schemas/`. Prefer the schema files over `doc/` as the authoritative format reference — schemas are tested in CI.

### Integration Pattern

1. **Load JSON** — parse the test vector file for the target algorithm
2. **Iterate testGroups** — each group shares parameters (key, curve, hash, key/iv/tag sizes)
3. **Iterate tests** — each test case has inputs, expected output, and a result verdict
4. **Assert on result:**
   - `"valid"` → implementation **MUST accept** and produce expected output
   - `"invalid"` → implementation **MUST reject** (throw/return error)
   - `"acceptable"` → implementation **MAY accept or reject** — check `flags` for guidance
5. **Triage failures by bugType** — notes dictionary maps flag names to severity and CVE links

### Result Semantics

| Result | Meaning | Test Assertion |
|--------|---------|----------------|
| `valid` | Correct input/output pair | Must succeed and match expected output |
| `invalid` | Malformed, corrupted, or attack input | Must be rejected |
| `acceptable` | Gray area — legacy compat, BER, weak params | Either accept or reject is fine |

For `acceptable`: use `flags` to decide based on your security policy (strict DER → reject BER; ≥112-bit → reject WEAK_PARAMS; backward compat → accept LEGACY).

### Bug Type Severity

| Severity | Bug Types | Action |
|----------|-----------|--------|
| 🔴 Critical | `CONFIDENTIALITY`, `AUTH_BYPASS`, `KNOWN_BUG` | Fix immediately |
| 🟠 High | `MISSING_STEP`, `WRONG_PRIMITIVE`, `MODIFIED_PARAMETER` | High priority |
| 🟡 Medium | `SIGNATURE_MALLEABILITY`, `CAN_OF_WORMS`, `EDGE_CASE`, `MALLEABILITY` | Investigate |
| 🟢 Low | `BER_ENCODING`, `LEGACY`, `FUNCTIONALITY`, `WEAK_PARAMS`, `BASIC`, `DEFINED` | Policy decision |

### Data Types

| Type | Format | Example |
|------|--------|---------|
| HexBytes | Hex-encoded byte array | `"5b9604fe14eadba931b0ccf34843dab9"` |
| BigInt | Twos-complement hex, big-endian | `"0103"` = 259, `"ff40"` = -192, `"00ff"` = 255 |
| Asn | Hex-encoded ASN.1 (may be invalid) | `"3082..."` |
| Der | Valid DER as hex | `"3082..."` |
| Pem | PEM-encoded key string | `"-----BEGIN PUBLIC KEY-----\n..."` |

### Common Pitfalls

- **Tag size**: many AEAD groups use non-standard sizes (32, 64 bit). Read `tagSize` from group, don't hardcode.
- **Hex empty strings**: `""` = zero-length data, not null. Always decode.
- **BigInt leading zeros**: `"00ff"` = 255, not -1. The `"00"` is significant.
- **Non-standard nonces**: GCM vectors include non-96-bit IVs. Skip those groups if your API only supports 12-byte.
- **Acceptable ≠ valid**: don't assert acceptable must pass — your library may correctly reject it.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Full algorithm coverage (all AEAD, signature, key exchange, MAC, KDF, RSA, key wrap, FPE, PQC, BLS algorithms with file names and test types), JSON structure (root fields, testGroup/test hierarchy, notes dictionary), all test group types and their fields, format conventions (hash names, curve names with OIDs and JWK equivalents), key encoding formats | `references/algorithms-and-format.md` |
| Kotlin/JVM integration pattern (deserialize with kotlinx.serialization, JCA/JCE API mapping, test runner structure), generic test runner pattern, CI/CD integration, algorithm-specific testing patterns (AEAD, signatures, key exchange, MAC, KDF, RSA), failure triage checklist | `references/integration-patterns.md` |

</routing>

<reference_index>

**algorithms-and-format.md** — Complete algorithm coverage table mapping every test vector file to its algorithm, test type, and what it tests. All AEAD files (AEAD-AES-SIV-CMAC nonce-based vs AES-SIV-CMAC deterministic DaeadTest — two distinct files, AES-GCM, AES-GCM-SIV, AES-EAX, AES-CCM, ChaCha20-Poly1305, XChaCha20-Poly1305, AEGIS-128/128L (capital L in filename)/256, ASCON incl. 128a/80pq variants, MORUS640/1280, AES-GMAC as MacWithIvTest, AES-CBC-PKCS5, AES-XTS, ARIA, Camellia, SEED, SM4). All signature files (ECDSA across NIST/brainpool/binary-Koblitz/weak curves with ASN and P1363 encodings, Bitcoin variant secp256k1, EdDSA Ed25519/Ed448, DSA, RSA PKCS#1v1.5 verify and sig_gen, RSA-PSS, ML-DSA/Dilithium). Key exchange (ECDH 28 files spanning NIST/brainpool/binary-Koblitz curve families for ASN/PEM/ecpoint/webcrypto, X25519, X448, ML-KEM/Kyber with encaps/keygen_seed/semi_expanded_decaps variants). MAC/KDF (HMAC incl. SM3, AES-CMAC, KMAC, SipHash, VMAC as MacWithIvTest, HKDF, PBKDF2, PBES2 password-based encryption for JOSE). RSA encryption (OAEP incl. multi-prime, PKCS#1). Other (AES key wrap, AES-FF1, BLS-12-381, primality, JSON web crypto/encryption/key/signature, JOSE AES-CBC-HMAC content encryption, EC point/pubkey/prime-order-curve validation). JSON structure (algorithm, schema, generatorVersion now optional and inconsistent across files when present, numberOfTests, header, notes with bugType/description/effect/links/cves, testGroups with per-type fields, tests with tcId/comment/flags/result). Test group type definitions (AeadTestGroup fields: ivSize/keySize/tagSize; EcdhTestGroup: curve/encoding/public/private/shared; EcdsaTestGroup: key in DER/PEM/JWK + sha; EddsaTestGroup; HkdfTestGroup: keySize + ikm/salt/info/size/okm; MacTestGroup: keySize/tagSize + key/msg/tag; MacWithIvTestGroup: ivSize/keySize/tagSize + key/iv/msg/tag; PbeTestGroup: password/salt/iterationCount/iv/msg/ct; XdhTestGroup/XdhAsnComp/XdhJwkComp; MlkemTestGroup; MldsaTestGroup; RsaesOaepDecrypt; KeywrapTestGroup; IndCpaTestGroup; PrimalityTestGroup; DaeadTestGroup). Format naming conventions: hash functions (SHA-1, SHA-224, SHA-256, SHA-384, SHA-512, SHA3-224/256/384/512, SHA-512/224, SHA-512/256, SHAKE128, SHAKE256, KECCAK-224/256/384/512), elliptic curves with OIDs and JWK names across three families — NIST prime (secp256r1=P-256, secp384r1=P-384, secp521r1=P-521, secp256k1, secp224r1), brainpool (brainpoolP224r1/t1 through brainpoolP512r1/t1), and binary/Koblitz over GF(2^m) (sect283k1/r1, sect409k1/r1, sect571k1/r1, ASN-only) — plus curve25519, curve448, edwards25519=Ed25519, edwards448=Ed448; weak curves (secp160k1/r1/r2, secp192k1/r1, secp224k1) are deliberately tested for ECDSA, not universally excluded.

**integration-patterns.md** — Kotlin/JVM test runner using kotlinx.serialization for JSON and JCA APIs. Generic algorithm-specific test patterns: AEAD (encrypt then verify ct+tag, decrypt valid, reject invalid), signatures (load pubkey from group DER/PEM, verify message+sig, valid must verify, invalid must not), key exchange (compute shared secret from private+public, check valid matches expected, invalid rejects or produces all-zeros for low-order), MAC (compute tag from key+msg, compare with expected, respect tagSize truncation), KDF (HKDF: ikm/salt/info/size→okm; PBKDF2: password/salt/iterationCount/dkLen→dk), RSA encryption (decrypt ct with private key, valid→plaintext matches, invalid→reject, Bleichenbacher/Manger attack vectors). CI/CD (git submodule in checkout, run as part of test suite, update periodically for new vectors). Failure triage (check result field, check flags in notes, check bugType severity, check CVEs, check comment for attack description).

</reference_index>
