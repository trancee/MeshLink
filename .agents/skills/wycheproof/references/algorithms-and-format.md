# Project Wycheproof — Algorithms and Format Reference

<algorithm_coverage>
## Algorithm Coverage

### Symmetric Encryption & AEAD

| Algorithm | Test File Pattern | Test Type |
|-----------|------------------|-----------|
| AEAD-AES-SIV-CMAC (nonce-based) | `aead_aes_siv_cmac_test.json` | AeadTest |
| AES-GCM | `aes_gcm_test.json` | AeadTest |
| AES-GCM-SIV | `aes_gcm_siv_test.json` | AeadTest |
| AES-EAX | `aes_eax_test.json` | AeadTest |
| AES-CCM | `aes_ccm_test.json` | AeadTest |
| AES-SIV-CMAC (deterministic, RFC 5297) | `aes_siv_cmac_test.json` | DaeadTest |
| ChaCha20-Poly1305 | `chacha20_poly1305_test.json` | AeadTest |
| XChaCha20-Poly1305 | `xchacha20_poly1305_test.json` | AeadTest |
| AEGIS-128 / 128L / 256 | `aegis128_test.json`, `aegis128L_test.json` (capital L), `aegis256_test.json` | AeadTest |
| ASCON | `ascon128_test.json`, `ascon128a_test.json`, `ascon80pq_test.json` | AeadTest |
| MORUS | `morus640_test.json`, `morus1280_test.json` | AeadTest |
| AES-GMAC | `aes_gmac_test.json` | MacWithIvTest |
| AES-CBC-PKCS5 | `aes_cbc_pkcs5_test.json` | IndCpaTest |
| AES-XTS | `aes_xts_test.json` | AeadTest |
| ARIA | `aria_*_test.json` | Various |
| Camellia | `camellia_*_test.json` | Various |
| SEED | `seed_*_test.json` | Various |
| SM4 | `sm4_*_test.json` | Various |

**Two distinct SIV files, don't conflate them:** `aead_aes_siv_cmac_test.json` (algorithm `AEAD-AES-SIV-CMAC`) is a nonce-based `AeadTest`; `aes_siv_cmac_test.json` (algorithm `AES-SIV-CMAC`) is the fully deterministic `DaeadTest` with no nonce at all.

### Digital Signatures

| Algorithm | Test File Pattern | Test Type |
|-----------|------------------|-----------|
| ECDSA (ASN.1 encoded) | `ecdsa_secp256r1_sha256_test.json`, etc. (73 files across curves/hashes) | EcdsaVerify |
| ECDSA (P1363 encoded) | `ecdsa_secp256r1_sha256_p1363_test.json`, etc. | EcdsaP1363Verify |
| ECDSA (Bitcoin, secp256k1) | `ecdsa_secp256k1_sha256_bitcoin_test.json` | EcdsaBitcoinVerify |
| EdDSA | `ed25519_test.json`, `ed448_test.json` | EddsaVerify |
| DSA (ASN.1) | `dsa_*_test.json` (8 files) | DsaVerify |
| DSA (P1363) | `dsa_*_p1363_test.json` | DsaP1363Verify |
| RSA PKCS#1 v1.5 | `rsa_signature_*_test.json` | RsassaPkcs1Verify |
| RSA-PSS | `rsa_pss_*_test.json` | RsassaPssVerify |
| ML-DSA (Dilithium) | `mldsa_*_test.json` (9 files) | MldsaVerify / MldsaSign |

### Key Exchange

| Algorithm | Test File Pattern | Test Type |
|-----------|------------------|-----------|
| ECDH (ASN encoded, NIST + brainpool + binary/Koblitz curves) | `ecdh_secp256r1_test.json`, `ecdh_brainpoolP256r1_test.json`, `ecdh_sect283k1_test.json`, etc. (28 files) | EcdhTest |
| ECDH (PEM encoded) | `ecdh_secp256r1_pem_test.json`, etc. | EcdhPemTest |
| ECDH (ecpoint) | `ecdh_secp256r1_ecpoint_test.json`, etc. | EcdhEcpointTest |
| ECDH (webcrypto/JWK) | `ecdh_secp256r1_webcrypto_test.json`, etc. | EcdhWebcryptoTest |
| X25519 | `x25519_test.json` | XdhComp |
| X25519 (ASN) | `x25519_asn_test.json` | XdhAsnComp |
| X25519 (JWK) | `x25519_jwk_test.json` | XdhJwkComp |
| X25519 (PEM) | `x25519_pem_test.json` | XdhPemComp |
| X448 | `x448_test.json`, `x448_asn_test.json`, etc. | XdhComp / XdhAsnComp |
| ML-KEM (Kyber) | `mlkem_{512,768,1024}_test.json` + `_encaps_test.json` / `_keygen_seed_test.json` / `_semi_expanded_decaps_test.json` per size (12 files) | MlkemTest / MlkemEncapsTest / MlkemKeygenSeedTest / MlkemSemiExpandedDecapsTest |

ECDH covers three curve families, not just NIST prime curves: NIST P-curves (secp224r1/256r1/384r1/521r1), brainpool curves (P224r1/256r1/320r1/384r1/512r1), and binary/Koblitz curves over GF(2^m) (sect283k1/r1, sect409k1/r1, sect571k1/r1) — the last group has no PEM/ecpoint/webcrypto variants, ASN only.

### MACs & KDFs

| Algorithm | Test File Pattern | Test Type |
|-----------|------------------|-----------|
| HMAC (incl. SM3) | `hmac_sha*_test.json` (11 files), `hmac_sm3_test.json` | MacTest |
| AES-CMAC | `aes_cmac_test.json` | MacTest |
| KMAC128 / KMAC256 | `kmac128_no_customization_test.json`, `kmac256_no_customization_test.json` | MacTest |
| SipHash | `siphash_*_test.json`, `siphashx_*_test.json` (5 files) | MacTest |
| VMAC | `vmac_64_test.json`, `vmac_128_test.json` | MacWithIvTest |
| AES-GMAC | `aes_gmac_test.json` | MacWithIvTest |
| HKDF | `hkdf_sha*_test.json` (4 files) | HkdfTest |
| PBKDF2 | `pbkdf2_hmacsha*_test.json` (5 files) | PbkdfTest |
| PBES2 (password-based encryption, JOSE) | `pbes2_hmacsha{1,224,256,384,512}_aes_{128,192,256}_test.json` (15 files) | PbeTest |

### RSA Encryption

| Algorithm | Test File Pattern | Test Type |
|-----------|------------------|-----------|
| RSA-OAEP | `rsa_oaep_*_test.json` | RsaesOaepDecrypt |
| RSA-OAEP (multi-prime) | `rsa_three_primes_oaep_*_test.json` | RsaesOaepDecrypt |
| RSA PKCS#1 v1.5 Encrypt | `rsa_pkcs1_*_test.json` | RsaesPkcs1Decrypt |
| RSA PKCS#1 v1.5 Sign (key generation edge cases) | `rsa_pkcs1_*_sig_gen_test.json` | RsassaPkcs1Generate |

### Other

| Algorithm | Test File Pattern | Test Type |
|-----------|------------------|-----------|
| AES Key Wrap (KW) | `aes_wrap_test.json` | KeywrapTest |
| AES Key Wrap Pad (KWP) | `aes_kwp_test.json` | KeywrapTest |
| AES-FF1 (FPE) | `aes_ff1_*_test.json` (17 files, base/radix variants) | (no schema yet) |
| BLS-12-381 | `bls_*_test.json` (4 files) | Various |
| DH (finite field) | `dh_test.json` | DhTest |
| DHIES | `dhies_test.json` | DhiesTest |
| ECIES | `ecies_*_test.json` | EciesTest |
| Primality Testing | `primality_test.json` | PrimalityTest |
| JSON Web Crypto/Encryption/Key/Signature | `json_web_*.json` (4 files) | Various |
| JOSE AES-CBC-HMAC content encryption | `a128cbc_hs256_test.json`, `a192cbc_hs384_test.json`, `a256cbc_hs512_test.json` | AeadTest |
| EC Point validation | `ec_point_test.json` | EcPointTest |
| EC Public Key import | `ec_pubkey_test.json` | EcPublicKeyVerify |
| EC prime-order curve properties | `ec_prime_order_curves_test.json` | EcCurveTest |
</algorithm_coverage>

<json_structure>
## JSON Test Vector Structure

Every file follows this hierarchy:

```
Root
├── algorithm: string          // e.g. "AES-GCM", "ECDSA"
├── schema: string             // JSON schema filename in schemas/
├── generatorVersion: string   // OPTIONAL — many current files omit it entirely; when present, values vary ("0.9", "0.9rc5", "1", ...). Don't assume it exists or has a fixed value — check before reading it.
├── numberOfTests: int         // total test case count
├── header: string[]           // description/documentation
├── notes: {                   // dictionary of flag descriptions
│     "FlagName": {
│       "bugType": "AUTH_BYPASS|CONFIDENTIALITY|EDGE_CASE|...",
│       "description": "...",
│       "effect": "...",
│       "links": ["..."],
│       "cves": ["CVE-..."]
│     }
│   }
└── testGroups: [              // array of test groups
      {
        "type": "AeadTest",    // test type identifier
        ... group params ...,  // shared for all tests in group
        "tests": [
          {
            "tcId": 1,
            "comment": "description",
            "flags": ["Ktv"],
            ... test inputs ...,
            "result": "valid|invalid|acceptable"
          }
        ]
      }
    ]
```

**Conventions:**
- Header and testGroup data are always well-formed; only individual test inputs may be malformed
- Groups often provide keys in multiple formats (DER, PEM, JWK, raw) — use whichever your API accepts
- File naming: `<algorithm>_<parameters>_test.json`
</json_structure>

<test_group_types>
## Test Group Type Reference

### AeadTestGroup
For authenticated encryption with additional data.

| Field | Type | Description |
|-------|------|-------------|
| ivSize | int | IV size in bits (always multiple of 8) |
| keySize | int | Key size in bits |
| tagSize | int | Expected tag size in bits (test vectors may have different tag sizes — those are always invalid) |
| type | str | `"AeadTest"` |
| tests | AeadTestVector[] | Each has: `key`, `iv`, `aad`, `msg`, `ct`, `tag` (all HexBytes) |

**Note on tag placement:** Most algorithms append tag to ciphertext (ct‖tag). Exception: AES-SIV-CMAC (RFC 5297) prepends the SIV as tag‖ct.

### DaeadTestGroup
For deterministic AEAD (e.g. AES-SIV-CMAC). Like AeadTestGroup but no `ivSize` — the IV is derived from the input. Tests have: `key`, `aad`, `msg`, `ct` (ct includes tag).

### EcdhTestGroup (and variants)
Key exchange. Four encoding variants exist:

| Variant | Type | Key Format |
|---------|------|-----------|
| EcdhTestGroup | `EcdhTest` | ASN.1 DER public key + BigInt private |
| EcdhPemTestGroup | `EcdhPemTest` | PEM public + PEM private |
| EcdhEcpointTestGroup | `EcdhEcpointTest` | Raw EC point + BigInt private |
| EcdhWebcryptoTestGroup | `EcdhWebcryptoTest` | JWK public + JWK private |

All share: `curve`, tests with `public`, `private`, `shared` (expected shared secret).

**Important:** Some invalid test vectors contain a shared secret computed using the curve of the private key (not the public key), to distinguish implementations that ignore vs use the public key's curve info.

### XdhTestGroup (X25519, X448)
Three variants:

| Type | Encoding |
|------|----------|
| `XdhComp` | Raw 32/56-byte public + private |
| `XdhAsnComp` | ASN.1 DER encoded |
| `XdhJwkComp` | JWK encoded |
| `XdhPemComp` | PEM encoded |

Tests have: `public`, `private`, `shared`.

### EcdsaTestGroup (and variants)
Signature verification. Three signature encodings:

| Variant | Type | Signature Format |
|---------|------|-----------------|
| EcdsaTestGroup | `EcdsaVerify` | ASN.1 DER (r,s) |
| EcdsaP1363TestGroup | `EcdsaP1363Verify` | IEEE P1363 (fixed-width r‖s) |
| EcdsaBitcoinTestGroup | `EcdsaBitcoinVerify` | ASN.1 + Bitcoin strictness |

Group fields: `key` (EcPublicKey), `keyDer`, `keyPem`, `sha` (hash name), optionally `jwk`.
Tests have: `msg`, `sig`.

### EddsaTestGroup
EdDSA (Ed25519, Ed448). Group has `key`, `keyDer`, `keyPem`, `jwk`. Tests have `msg`, `sig`.

### DsaTestGroup
Like ECDSA but with DSA public keys (p, q, g, y). ASN.1 and P1363 variants.

### HkdfTestGroup
Tests have: `ikm` (input key material), `salt`, `info`, `size` (output length in bytes), `okm` (expected output).

### MacTestGroup
Tests have: `key`, `msg`, `tag`. Group specifies `keySize` and `tagSize` in bits. Truncated MACs are common.

### MacWithIvTestGroup
For MACs that take an IV/nonce (AES-GMAC, VMAC). Adds `ivSize` (bits) alongside `keySize`/`tagSize`. Tests have: `key`, `iv`, `msg`, `tag`.

### PbeTestGroup
Password-based encryption (PBES2, used by JOSE). Type `PbeTest`. Tests have: `password`, `salt`, `iterationCount`, `iv`, `msg`, `ct` — no group-level shared fields beyond `type`/`tests`.

### MlkemTestGroup (ML-KEM / Kyber)
Post-quantum key encapsulation. Tests cover encapsulation and decapsulation.

### MldsaTestGroup (ML-DSA / Dilithium)
Post-quantum signatures. Separate verify and sign test types.

### RsaesOaepDecrypt / RsaesPkcs1Decrypt
RSA decryption. Group has private key. Tests have `msg` (expected plaintext), `ct` (ciphertext). Tests for Bleichenbacher and Manger attacks.

### KeywrapTestGroup
AES key wrap. Tests have `key`, `msg` (key material), `ct` (wrapped key).

### IndCpaTestGroup
Symmetric encryption without integrity (e.g. AES-CBC). Tests have `key`, `iv`, `msg`, `ct`. No tag.

### PrimalityTestGroup
Tests have `value` (BigInt) and result indicates whether the number is prime.
</test_group_types>

<naming_conventions>
## Format Naming Conventions

### Hash Functions
`SHA-1`, `SHA-224`, `SHA-256`, `SHA-384`, `SHA-512`, `SHA3-224`, `SHA3-256`, `SHA3-384`, `SHA3-512`, `SHA-512/224`, `SHA-512/256`, `SHAKE128`, `SHAKE256`. SHA-3 variants: `KECCAK-224`, `KECCAK-256`, `KECCAK-384`, `KECCAK-512`.

### Elliptic Curves

| Curve Name | JWK Name | OID | Notes |
|-----------|----------|-----|-------|
| secp256r1 | P-256 | 1.2.840.10045.3.1.7 | NIST P-256 |
| secp384r1 | P-384 | 1.3.132.0.34 | NIST P-384 |
| secp521r1 | P-521 | 1.3.132.0.35 | NIST P-521 |
| secp256k1 | secp256k1 | 1.3.132.0.10 | Bitcoin curve |
| secp224r1 | — | 1.3.132.0.33 | |
| brainpoolP256r1 | — | 1.3.36.3.3.2.8.1.1.7 | RFC 5639 |
| brainpoolP384r1 | — | 1.3.36.3.3.2.8.1.1.11 | RFC 5639 |
| brainpoolP512r1 | — | 1.3.36.3.3.2.8.1.1.13 | RFC 5639 |
| curve25519 | — | — | Montgomery form, X25519 |
| curve448 | — | — | Montgomery form, X448 |
| edwards25519 | Ed25519 | — | Twisted Edwards, Ed25519 |
| edwards448 | Ed448 | — | Edwards, Ed448 |

Brainpool also has twisted (`t1`) variants. Binary/Koblitz curves over GF(2^m) also appear (`sect283k1`, `sect283r1`, `sect409k1`, `sect409r1`, `sect571k1`, `sect571r1`) for ECDH — a distinct curve family from the prime-field curves above.

**Weak curves are not universally excluded** — `secp160k1`, `secp160r1`, `secp160r2`, `secp192k1`, `secp192r1`, `secp224k1` (all below the 112-bit security floor) have dedicated ECDSA test files specifically to verify that implementations correctly flag or reject them; they are not resurrected in the newer ECDH file set. Don't assume "weak curve → no test file exists" — check the actual file list for the algorithm you're testing.
</naming_conventions>

<bug_types>
## Bug Types — Complete Reference

| Bug Type | Meaning | Severity |
|----------|---------|----------|
| `BASIC` | Sanity check — basic test with no special cases | Low |
| `AUTH_BYPASS` | Invalid integrity check accepted | High |
| `CONFIDENTIALITY` | Potential plaintext leakage (e.g. invalid curve attack) | Critical |
| `EDGE_CASE` | Special mathematical edge case | Medium |
| `SIGNATURE_MALLEABILITY` | Modified signature still validates | Medium-High |
| `MALLEABILITY` | Modified ciphertext decrypts to same plaintext | Medium |
| `BER_ENCODING` | BER encoding accepted where DER expected | Low-Medium |
| `CAN_OF_WORMS` | Small bug that can cascade to vulnerability | Medium |
| `MISSING_STEP` | Implementation skips a required step | High |
| `KNOWN_BUG` | Tests for a previously discovered vulnerability | High |
| `WRONG_PRIMITIVE` | Wrong algorithm/hash accepted | High |
| `MODIFIED_PARAMETER` | Tampered algorithm parameter not detected | High |
| `LEGACY` | Legacy/compatibility behavior (neither accept nor reject is a failure) | Low |
| `FUNCTIONALITY` | Uncommon but valid parameter sizes | Low |
| `WEAK_PARAMS` | Below NIST 112-bit security recommendation | Medium |
| `DEFINED` | Edge case with defined behavior | Low |

The notes dictionary in each file maps flag names to these bug types with descriptions, effects, links, and CVEs.
</bug_types>
