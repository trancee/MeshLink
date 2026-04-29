# EdDSA RFC 8032 — Security Considerations & Test Vectors

<security>
## Security Considerations (§8)

### Side-Channel Attacks (§8.1)
- Implementation MUST execute the same instruction sequence and memory accesses regardless of private key value
- Modulo p arithmetic must avoid data-dependent branches (e.g., carry propagation)
- Scalar multiplication needs constant-time conditional assignment + binary algorithm examining one bit at a time
- Edwards curves help: complete addition formulas eliminate special-case branches
- **The RFC's Python examples are NOT side-channel resistant** — they are for illustration only

### Deterministic Signing (§8.2)
- EdDSA signatures are **deterministic** — no per-signature randomness
- Protects against bad-RNG attacks (which can leak the entire private key in other schemes)
- Private key generation still requires randomness, but the hash-before-use design tolerates a few missing entropy bits
- Basic verification is deterministic. Batch verification speedups may use randomness.

### Context Usage (§8.3)
- Contexts separate protocol uses (hard to do otherwise)
- Context SHOULD be a **constant string** specified by the protocol — not variable message elements
- SHOULD NOT be used opportunistically (error-prone)
- Contexts percolate out of APIs — may not be available through intermediate protocols

### Signature Malleability (§8.4)
- Ed25519/Ed448 signatures are **not malleable** due to the `S < L` check
- Without this check, an attacker could add multiples of L to S and still pass verification
- Implementations MUST verify `S < L` during signature decoding

### PureEdDSA vs HashEdDSA (§8.5)
- **Ed25519ph/Ed448ph SHOULD NOT be used** — more vulnerable to hash weaknesses
- PureEdDSA provides collision resilience: even if the hash function has collisions, signatures remain secure
- HashEdDSA loses this property — a hash collision directly yields a signature forgery
- Ed25519ph/Ed448ph exist mainly for legacy API interop (single-pass interfaces)
- PureEdDSA requires **two passes** over the message (once for nonce, once for challenge)

### Mixing Prehashes (§8.6)
- It IS safe to use the same key pair for Ed25519, Ed25519ctx, and Ed25519ph
- The domain separation strings ("SigEd25519 no Ed25519 collisions") prevent cross-scheme forgery
- The string is chosen so it does not decode as a valid curve point

### Large Messages (§8.7)
- Receiver MUST buffer the entire message before processing — cannot verify incrementally
- An Initialize-Update-Finalize (IUF) interface for signing is dangerous — any error is catastrophic
- Do NOT modify Ed25519/Ed448 signing to use IUF with constant buffering

### Cofactor in Verification (§8.8)
- Multiplying by the cofactor (8 for Ed25519, 4 for Ed448) is not strictly needed for security
- But without it, implementations may disagree on valid signature sets → fingerprinting attacks
- The cofactored equation `[8S]B = [8]R + [8k]A'` accepts strictly more signatures than `[S]B = R + [k]A'`

### SHAKE256 for Ed448 (§8.9)
- SHAKE256 is an XOF, not formally a hash function
- Shorter outputs are prefixes of longer ones — acceptable because output lengths are fixed
- 256-bit collision resistance is sufficient for 224-bit elliptic curve security
</security>

<test_vectors>
## Test Vectors (§7)

### Ed25519 Test Vectors (§7.1)

**TEST 1 — Empty message:**
```
SECRET KEY: 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60
PUBLIC KEY: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
MESSAGE:    (empty)
SIGNATURE:  e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b
```

**TEST 2 — Single byte (0x72):**
```
SECRET KEY: 4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb
PUBLIC KEY: 3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c
MESSAGE:    72
SIGNATURE:  92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00
```

**TEST 3 — Two bytes (0xaf82):**
```
SECRET KEY: c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7
PUBLIC KEY: fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025
MESSAGE:    af82
SIGNATURE:  6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a
```

**TEST SHA(abc) — Pre-hashed message (64 bytes):**
```
SECRET KEY: 833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42
PUBLIC KEY: ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf
MESSAGE:    ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f
SIGNATURE:  dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704
```

### Additional Test Vector Sets in RFC
- Ed25519ctx: 4 test vectors with contexts "foo" and "bar"
- Ed25519ph: 1 test vector (message "abc")
- Ed448: 6 test vectors (empty, 1 byte, 1 byte+context, 11 bytes, 12 bytes, 13 bytes, 64 bytes, 256 bytes, 1023 bytes)
- Ed448ph: 3 test vectors

### Implementation Notes for Test Vectors
- Private key and public key are each separate — public key is NOT appended to private key
- Signature does NOT include the message
- All octets are hex-encoded
- Ed25519/Ed25519ctx/Ed25519ph: keys 32 bytes, signatures 64 bytes
- Ed448/Ed448ph: keys 57 bytes, signatures 114 bytes
</test_vectors>

<implementation_notes>
## Implementation Considerations

### Bit/Byte Ordering
All encoding is **little-endian**. Bit strings → octet strings: bits packed from LSB to MSB of each byte.

### Point Comparison
Compare in extended/projective coordinates by cross-multiplying to avoid division:
```
x1/z1 == x2/z2  ⟺  x1·z2 == x2·z1
```

### Scalar Multiplication
- Binary (double-and-add) algorithm is simplest
- Must be constant-time for signing (private scalar)
- Can use variable-time for verification (public values)
- Reduce r mod L before computing [r]B for efficiency (r can be up to 2^(2b))

### Python Reference Implementation
RFC includes complete Python 3.2+ implementations:
- §6: Ed25519-specific (~80 lines)
- Appendix A: Generic library supporting both Ed25519 and Ed448 (~400 lines)
- Appendix B: Test driver
- **NOT for production** — no side-channel protection, not verified for all inputs
</implementation_notes>
