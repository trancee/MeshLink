# HMAC RFC 2104 — Algorithm, Keys, Security, and Test Vectors

<overview>
## What RFC 2104 Defines

HMAC: Keyed-Hashing for Message Authentication. IETF Informational (February 1997). Authors: Krawczyk, Bellare, Canetti.

A mechanism for message authentication using any iterative cryptographic hash function (MD5, SHA-1, SHA-256, etc.) combined with a secret shared key. Works with the hash function unmodified — no changes to H needed.

### Design Goals
- Use available hash functions without modification
- Preserve original hash function performance
- Simple key handling
- Well-understood cryptographic analysis
- Easy replaceability of the underlying hash function
</overview>

<algorithm>
## HMAC Definition (§2)

### Parameters
| Symbol | Meaning |
|--------|---------|
| **H** | Cryptographic hash function |
| **B** | Block size of H in bytes (64 for MD5/SHA-1/SHA-256, 128 for SHA-512) |
| **L** | Output length of H in bytes (16 for MD5, 20 for SHA-1, 32 for SHA-256, 64 for SHA-512) |
| **K** | Secret key |
| **ipad** | Byte `0x36` repeated B times |
| **opad** | Byte `0x5C` repeated B times |

### Formula

```
HMAC(K, text) = H((K' XOR opad) || H((K' XOR ipad) || text))
```

Where K' is K zero-padded to B bytes (or hashed first if K > B bytes).

### Step-by-Step

1. If `len(K) > B`: set `K = H(K)` (hash the key down to L bytes)
2. Pad K with zeros on the right to create B-byte string K'
3. Compute `K' XOR ipad` → inner key pad (B bytes)
4. Concatenate: `(K' XOR ipad) || text`
5. Hash: `inner_hash = H((K' XOR ipad) || text)` → L bytes
6. Compute `K' XOR opad` → outer key pad (B bytes)
7. Concatenate: `(K' XOR opad) || inner_hash`
8. Hash: `HMAC = H((K' XOR opad) || inner_hash)` → L bytes

### Structure Visually
```
HMAC = H( [K' ⊕ opad] || H( [K' ⊕ ipad] || message ) )
       \___ outer hash ___/    \___ inner hash ___/
```

Two hash invocations: inner hash processes (ipad-keyed block + message), outer hash processes (opad-keyed block + inner result).
</algorithm>

<keys>
## Key Handling (§3)

- Key can be **any length** (keys > B bytes are first hashed to L bytes via H)
- **Minimum recommended: L bytes** (hash output length). Less than L bytes "strongly discouraged"
- Keys > L bytes are acceptable but extra length does not significantly increase strength (a longer key may help if randomness is weak)
- Keys MUST be chosen at random or from a cryptographically strong PRNG
- Keys SHOULD be periodically refreshed (good practice, limits damage from exposure)
</keys>

<performance>
## Implementation Notes (§4)

### Precomputation Optimization
The intermediate hash state after processing the first block can be cached:

1. Precompute `H_partial(K' XOR ipad)` — hash state after processing the ipad-keyed block
2. Precompute `H_partial(K' XOR opad)` — hash state after processing the opad-keyed block
3. Store these intermediate results (treat as secret — same protection as keys)
4. For each message: resume from cached state, process message, finalize inner; resume from opad state, process inner result, finalize outer

**Saves two compression function calls per message** (significant for short messages). No effect on interoperability — purely local optimization.
</performance>

<truncation>
## Truncated Output (§5)

HMAC output may be truncated to t leftmost bits.

**Recommended minimums:**
- t ≥ L/2 (half the hash output) — matches birthday attack bound
- t ≥ 80 bits — practical lower bound on attacker prediction difficulty

**Notation:** HMAC-H-t — e.g., `HMAC-SHA1-80` = HMAC with SHA-1, output truncated to 80 bits. If t not specified (e.g., `HMAC-MD5`), all L bits are output.

Truncation has tradeoffs: less information available to attacker, but also fewer bits to predict.
</truncation>

<security>
## Security (§6)

### Assumptions
HMAC security relies on two properties of H:
1. **Collision resistance** with secret random IV (weaker than general collision resistance)
2. **MAC property** of H's compression function on single blocks (blocks are partially unknown to attacker)

These are weaker assumptions than general collision resistance — HMAC remains secure even against hash collision attacks (e.g., MD5 collision attacks do NOT break HMAC-MD5).

### Birthday Attack (Strongest Known Attack)
- Requires ~2^(L/2) messages authenticated with the **same key**
- For L=16 (MD5): 2^64 messages — "250,000 years at 1 Gbps continuous"
- For L=20 (SHA-1): 2^80 messages — even more impractical
- For L=32 (SHA-256): 2^128 messages — completely infeasible
- This is **online** (requires real MACs from the key holder) — not parallelizable offline

### Key Distinction
Regular hash collision attacks (2^64 offline for 128-bit hash) are approaching feasibility. HMAC birthday attacks (2^64 **online** with same key) are totally impractical — the attacker needs the legitimate key holder to compute each MAC.

### Transient Effect
Message authentication has a "transient" property: breaking the MAC scheme leads to replacement but does not compromise past authenticated messages (unlike encryption, where past ciphertexts become readable).

### Essential Practices
- Correct implementation of the construction
- Random or cryptographically pseudorandom keys
- Secure key exchange mechanism
- Frequent key refreshment
- Good secrecy protection of keys
</security>

<test_vectors>
## Test Vectors (Appendix — HMAC-MD5)

(Trailing '\0' of character strings NOT included)

### Test 1
```
key      = 0x0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b (16 bytes)
data     = "Hi There" (8 bytes)
digest   = 0x9294727a3638bb1c13f48ef8158bfc9d
```

### Test 2
```
key      = "Jefe" (4 bytes)
data     = "what do ya want for nothing?" (28 bytes)
digest   = 0x750c783e6ab0b503eaa86e310a5db738
```

### Test 3
```
key      = 0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA (16 bytes)
data     = 0xDD repeated 50 times (50 bytes)
digest   = 0x56be34521d144c88dbb8c733f0e8b3f6
```
</test_vectors>
