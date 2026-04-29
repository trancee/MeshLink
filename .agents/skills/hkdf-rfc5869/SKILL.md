---
name: hkdf-rfc5869
description: Complete reference for RFC 5869 — HMAC-based Extract-and-Expand Key Derivation Function (HKDF). Covers the two-stage KDF built on HMAC. Extract stage concentrates possibly-dispersed entropy into a fixed-length pseudorandom key (PRK = HMAC-Hash(salt, IKM) — note salt is the HMAC key, IKM is the input). Expand stage derives arbitrary-length output keying material via chained HMAC with context info and single-byte counter (T(i) = HMAC-Hash(PRK, T(i-1) | info | i), max 255 iterations, max output 255×HashLen bytes). Includes usage guidance (salt adds significant strength, is non-secret and reusable; info binds keys to context preventing cross-protocol collisions; Diffie-Hellman values are NOT pseudorandom so extract MUST NOT be skipped; HKDF cannot amplify entropy so NOT suitable for password-based KDF — use PKCS#5/bcrypt/argon2 instead), independence requirements (salt must be independent of and not manipulable by attacker), and 7 test vectors for SHA-256 and SHA-1 (basic, long inputs, zero-length salt/info, salt-not-provided default). Use when implementing HKDF, deriving keys from DH shared secrets, understanding extract-then-expand, debugging key derivation, or any question about RFC 5869.
---

<essential_principles>

**RFC 5869** defines HKDF — a two-stage KDF built on HMAC. IETF Informational (May 2010). By Hugo Krawczyk.

### What an Implementer Must Know

**Extract:** `PRK = HMAC-Hash(salt, IKM)` — salt is the HMAC key (not IKM). If no salt, use HashLen zero bytes. Output: HashLen octets.

**Expand:** Chained HMAC producing arbitrary-length output.
```
T(0) = ""
T(i) = HMAC-Hash(PRK, T(i-1) | info | i)    // i is single byte 0x01..0xFF
OKM  = first L bytes of T(1) | T(2) | ...
```
Max output: 255 × HashLen bytes (8,160 for SHA-256, 5,100 for SHA-1).

**Critical rules:**
- Salt is non-secret, can be reused, but MUST NOT be attacker-controlled
- `info` provides domain separation — different contexts MUST use different info
- DH values g^{xy} are NOT pseudorandom — always run extract (preferably with salt)
- HKDF concentrates entropy but cannot amplify it — NOT for password-based KDF
- Even when L ≤ HashLen, expand should still run (to incorporate info)

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Everything (extract algorithm with HMAC argument order, expand algorithm with chained T(i) and single-byte counter, max output calculation, salt guidance and reuse rules, info for domain separation, when to skip extract, DH values not pseudorandom, independence requirements, password KDF exclusion, 7 test vectors for SHA-256 and SHA-1 covering basic/long/zero-length/no-salt cases) | `references/hkdf.md` |

</routing>

<reference_index>

**hkdf.md** — overview (HMAC-based extract-and-expand KDF, IETF May 2010, Krawczyk, used in IKEv2/PANA/EAP-AKA/TLS 1.3), extract §2.2 (PRK = HMAC-Hash(salt, IKM) where salt is HMAC key and IKM is HMAC input — deliberate order for randomness extraction, if no salt use HashLen zeros, output is HashLen octets), expand §2.3 (T(0)="" T(i)=HMAC-Hash(PRK, T(i-1)|info|i) where i is single byte 0x01-0xFF, OKM=first L bytes of T(1)|...|T(N), N=ceil(L/HashLen), max N=255, max output 255×HashLen: 8160 for SHA-256 5100 for SHA-1), salt guidance §3.1 (adds significant strength, non-secret, reusable across IKM values, ideal=random HashLen bytes, even low-entropy helps, secret salt provides stronger guarantee, example IKEv2 public nonces), info guidance §3.2 (binds keys to context: protocol number algorithm IDs user identities, prevents same IKM producing same keys in different contexts, must be independent of IKM), skip-extract §3.3 (ok if IKM already pseudorandom like TLS RSA premaster, MUST NOT skip for DH g^{xy} which is NOT pseudorandom, even when L≤HashLen still run expand to incorporate info), independence §3.4 (different IKM samples→independent OKM, salt must be independent of IKM, salt must not be attacker-chosen, protocol nonces used as salt must be authenticated), not suitable for password KDF (cannot amplify entropy, use PKCS#5/bcrypt/argon2), test vectors appendix A (case 1: SHA-256 basic IKM=22B salt=13B info=10B L=42 with PRK+OKM hex, case 2: SHA-256 long 80B each L=82, case 3: SHA-256 zero-length salt+info, case 4: SHA-1 basic, case 7: SHA-1 salt-not-provided defaults to HashLen zeros, plus cases 5-6 SHA-1 long and zero-length)

</reference_index>
