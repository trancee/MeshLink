# HKDF RFC 5869 — Algorithm, Usage, and Test Vectors

<overview>
## What RFC 5869 Defines

HMAC-based Extract-and-Expand Key Derivation Function (HKDF). IETF Informational (May 2010). Author: Hugo Krawczyk (IBM Research).

A simple, general-purpose KDF built on HMAC. Two-stage design:
1. **Extract** — concentrate possibly-dispersed entropy into a fixed-length pseudorandom key (PRK)
2. **Expand** — derive one or more output keys of arbitrary length from PRK

Already used in IKEv2, PANA, EAP-AKA, TLS 1.3, and many other protocols.
</overview>

<extract>
## Step 1: Extract (§2.2)

```
HKDF-Extract(salt, IKM) -> PRK
```

| Parameter | Description |
|-----------|-------------|
| **Hash** | Hash function (e.g., SHA-256); HashLen = output length in octets |
| **salt** | Optional non-secret random value. If not provided, set to HashLen zero bytes |
| **IKM** | Input keying material (the secret) |
| **PRK** | Output pseudorandom key (HashLen octets) |

### Computation
```
PRK = HMAC-Hash(salt, IKM)
```

**Note the argument order:** salt is the HMAC key, IKM is the HMAC input — not the other way around. This is deliberate: the extract stage is designed to work even when the salt has less entropy than IKM (salt acts as a randomness extractor key).
</extract>

<expand>
## Step 2: Expand (§2.3)

```
HKDF-Expand(PRK, info, L) -> OKM
```

| Parameter | Description |
|-----------|-------------|
| **PRK** | Pseudorandom key of at least HashLen octets (usually from extract) |
| **info** | Optional context/application-specific information (can be zero-length) |
| **L** | Desired output length in octets. **Maximum: 255 × HashLen** |
| **OKM** | Output keying material (L octets) |

### Computation
```
N = ceil(L / HashLen)
T(0) = empty string (zero length)
T(1) = HMAC-Hash(PRK, T(0) | info | 0x01)
T(2) = HMAC-Hash(PRK, T(1) | info | 0x02)
T(3) = HMAC-Hash(PRK, T(2) | info | 0x03)
...
T(N) = HMAC-Hash(PRK, T(N-1) | info | N)

OKM = first L octets of (T(1) | T(2) | ... | T(N))
```

The counter byte (0x01, 0x02, ...) is a **single octet**, limiting N to 255 maximum. With SHA-256 (HashLen=32), max output = 255 × 32 = 8,160 bytes. With SHA-1 (HashLen=20), max output = 255 × 20 = 5,100 bytes.
</expand>

<usage_guidance>
## Usage Guidance (§3)

### Salt (§3.1)
- Salt adds **significantly** to HKDF strength
- Salt is **non-secret** and **can be reused** across multiple IKM values
- Ideal salt: random or pseudorandom, HashLen bytes
- Even shorter or low-entropy salt still helps
- A secret salt (rare) provides even stronger guarantees
- Example: in IKEv2, salt derived from authenticated public nonces

### The `info` Input (§3.2)
- Binds derived keys to application/context-specific information
- Prevents same IKM from producing same keys in different contexts
- Typical contents: protocol number, algorithm IDs, user identities, key length L
- **MUST be independent of IKM**
- Not providing `info` is valid but loses domain separation

### When to Skip Extract (§3.3)
- If IKM is already a cryptographically strong pseudorandom key (e.g., TLS RSA premaster secret), extract can be skipped — use IKM directly as PRK in expand
- **Diffie-Hellman values g^{xy} are NOT pseudorandom** — extract SHOULD NOT be skipped; always apply extract (preferably with salt)
- Even when PRK could be used directly as OKM (L ≤ HashLen), this is NOT RECOMMENDED — expand should still run to incorporate `info`

### Independence (§3.4)
- Different IKM samples must produce essentially independent OKM values
- Salt values must be independent of IKM
- Salt must not be chosen or manipulated by an attacker
- When salt is derived from protocol nonces, those nonces must be authenticated

### Not Suitable For
- **Password-based key derivation** — HKDF cannot amplify entropy; it only concentrates existing entropy. Use PKCS#5/bcrypt/argon2 instead (they include intentional slowdown against dictionary attacks).
</usage_guidance>

<test_vectors>
## Test Vectors (Appendix A)

### Test Case 1: SHA-256, Basic
```
Hash = SHA-256
IKM  = 0x0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b (22 octets)
salt = 0x000102030405060708090a0b0c (13 octets)
info = 0xf0f1f2f3f4f5f6f7f8f9 (10 octets)
L    = 42

PRK  = 0x077709362c2e32df0ddc3f0dc47bba63
       90b6c73bb50f9c3122ec844ad7c2b3e5 (32 octets)
OKM  = 0x3cb25f25faacd57a90434f64d0362f2a
       2d2d0a90cf1a5a4c5db02d56ecc4c5bf
       34007208d5b887185865 (42 octets)
```

### Test Case 2: SHA-256, Longer Inputs/Outputs
```
Hash = SHA-256
IKM  = 0x000102...4f (80 octets)
salt = 0x606162...af (80 octets)
info = 0xb0b1b2...ff (80 octets)
L    = 82

PRK  = 0x06a6b88c5853361a06104c9ceb35b45c
       ef760014904671014a193f40c15fc244 (32 octets)
OKM  = 0xb11e398dc80327a1c8e7f78c596a4934
       4f012eda2d4efad8a050cc4c19afa97c
       59045a99cac7827271cb41c65e590e09
       da3275600c2f09b8367793a9aca3db71
       cc30c58179ec3e87c14c01d5c1f3434f
       1d87 (82 octets)
```

### Test Case 3: SHA-256, Zero-Length Salt and Info
```
Hash = SHA-256
IKM  = 0x0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b (22 octets)
salt = (0 octets)
info = (0 octets)
L    = 42

PRK  = 0x19ef24a32c717b167f33a91d6f648bdf
       96596776afdb6377ac434c1c293ccb04 (32 octets)
OKM  = 0x8da4e775a563c18f715f802a063c5a31
       b8a11f5c5ee1879ec3454e5f3c738d2d
       9d201395faa4b61a96c8 (42 octets)
```

### Test Case 4: SHA-1, Basic
```
Hash = SHA-1
IKM  = 0x0b0b0b0b0b0b0b0b0b0b0b (11 octets)
salt = 0x000102030405060708090a0b0c (13 octets)
info = 0xf0f1f2f3f4f5f6f7f8f9 (10 octets)
L    = 42

PRK  = 0x9b6c18c432a7bf8f0e71c8eb88f4b30baa2ba243 (20 octets)
OKM  = 0x085a01ea1b10f36933068b56efa5ad81
       a4f14b822f5b091568a9cdd4f155fda2
       c22e422478d305f3f896 (42 octets)
```

### Test Case 7: SHA-1, Salt Not Provided (Defaults to HashLen Zeros)
```
Hash = SHA-1
IKM  = 0x0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c (22 octets)
salt = not provided (defaults to HashLen zero octets)
info = (0 octets)
L    = 42

PRK  = 0x2adccada18779e7c2077ad2eb19d3f3e731385dd (20 octets)
OKM  = 0x2c91117204d745f3500d636a62f64f0a
       b3bae548aa53d423b0d1f27ebba6f5e5
       673a081d70cce7acfc48 (42 octets)
```

(RFC also includes Test Cases 5 and 6: SHA-1 with long inputs/outputs and SHA-1 with zero-length salt/info.)
</test_vectors>
