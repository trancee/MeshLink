---
name: hmac-rfc2104
description: RFC 2104 — HMAC (Keyed-Hashing for Message Authentication) reference. Covers the HMAC construction H((K' XOR opad) || H((K' XOR ipad) || text)) with ipad=0x36 and opad=0x5C. Key handling (min L bytes, keys > B hashed, random, periodic refresh), precomputation optimization (cache padded key hash state), truncation rules (min half hash output, min 80 bits). Security analysis (collision resistance with secret IV, birthday attack 2^(L/2) impractical, hash collision attacks don't break HMAC, transient effect). Test vectors. Use when implementing HMAC, understanding inner/outer hash, key requirements, precomputation, truncation, or any RFC 2104 question.
---

<essential_principles>

**RFC 2104** defines HMAC — keyed message authentication using any iterative hash function. IETF Informational (February 1997). By Krawczyk, Bellare, Canetti.

### What an Implementer Must Know

**Algorithm:** `HMAC(K, text) = H((K' ⊕ opad) ∥ H((K' ⊕ ipad) ∥ text))` where `ipad = 0x36 × B`, `opad = 0x5C × B`, B = block size of H (64 for SHA-256, 128 for SHA-512), L = output length of H.

**Key handling:** If `len(K) > B`, first hash: `K = H(K)`. Then zero-pad to B bytes. Min recommended key length: L bytes (hash output length).

**Two hash passes:** inner hash = `H([K' ⊕ ipad] ∥ message)`, outer hash = `H([K' ⊕ opad] ∥ inner_result)`.

**Precomputation:** Cache hash state after processing `K' ⊕ ipad` and `K' ⊕ opad` blocks — saves 2 compression calls per message. Treat cached state as secret.

**Truncation:** Output may be truncated to t leftmost bits. Min t = max(L/2, 80 bits). Notation: HMAC-H-t (e.g., HMAC-SHA1-80).

**Security:** Birthday attack (strongest known) needs 2^(L/2) online MACs with the same key — impractical. Hash collision attacks do NOT break HMAC (relies on weaker assumptions). Keys must be random and periodically refreshed.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Everything (algorithm with ipad/opad/two-pass structure, parameters B/L/K table, step-by-step computation, visual structure diagram, key handling with hashing long keys and minimum length, precomputation optimization caching intermediate hash state, truncation rules and notation, security analysis with birthday attack bound and distinction from hash collision attacks, transient effect of MAC vs encryption, essential security practices, HMAC-MD5 test vectors) | `references/hmac.md` |

</routing>

<reference_index>

**hmac.md** — overview (keyed-hashing for message authentication, IETF February 1997, Krawczyk/Bellare/Canetti, works with any iterative hash unmodified, design goals: use existing hash functions preserve performance simple keys well-analyzed easy replacement), algorithm §2 (parameters: H hash function, B block size 64 for MD5/SHA-1/SHA-256 128 for SHA-512, L output length 16/20/32/64, K secret key, ipad=0x36×B, opad=0x5C×B; formula HMAC=H((K' XOR opad)||H((K' XOR ipad)||text)); step-by-step: if len(K)>B set K=H(K), zero-pad K to B bytes, XOR with ipad, hash with message=inner_hash, XOR with opad, hash with inner_hash=output; two hash invocations: inner processes ipad-keyed block+message, outer processes opad-keyed block+inner result), key handling §3 (any length, >B hashed first, min recommended L bytes, <L strongly discouraged, >L no significant extra strength but helps with weak randomness, must be random or crypto PRNG, periodic refresh), precomputation §4 (cache intermediate hash state after first B-byte block K'⊕ipad and K'⊕opad, saves 2 compression calls per message, significant for short messages, cached state must be protected as secret, no interop effect), truncation §5 (t leftmost bits, min t≥L/2 and t≥80 bits, notation HMAC-H-t e.g. HMAC-SHA1-80, tradeoffs: less info to attacker but fewer bits to predict), security §6 (relies on collision resistance with secret random IV + compression function MAC property — weaker than general collision resistance, hash collision attacks like MD5 do NOT break HMAC-MD5, birthday attack strongest known: ~2^(L/2) messages with same key online not parallelizable, L=16 2^64 msgs "250000 years at 1Gbps", L=20 2^80 L=32 2^128, contrast with offline hash collision 2^64 parallelizable approaching feasibility, transient effect: breaking MAC doesn't compromise past authentications unlike encryption, essential practices: correct implementation random keys secure exchange frequent refresh secrecy protection), test vectors appendix (HMAC-MD5: test1 key=0x0b×16 data="Hi There" digest=9294727a3638bb1c13f48ef8158bfc9d, test2 key="Jefe" data="what do ya want for nothing?" digest=750c783e6ab0b503eaa86e310a5db738, test3 key=0xAA×16 data=0xDD×50 digest=56be34521d144c88dbb8c733f0e8b3f6)

</reference_index>
