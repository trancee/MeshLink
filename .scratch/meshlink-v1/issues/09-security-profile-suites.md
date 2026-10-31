# What handshake and crypto suite does each security profile use?

Type: grilling
Status: open
Blocked by: 03, 08

## Question

Decide per profile: handshake pattern, primitives, identity/key model and rotation, session lifetime, hop-by-hop vs end-to-end layering, and whether profiles share one framework. Must meet the threat model, fit BLE MTU, and have conformance vectors. Produces ADRs.

## Scope additions (from research, 2026-10-31)

- Is the post-quantum profile iOS 26+ only (CryptoKit ML-KEM/X-Wing), or bundled for iOS 15–25? Android 26–32 lacks platform X25519, so classical needs a bundled provider there too.
- PQ authentication: is classical authentication with a hybrid KEM an acceptable claim, or are PQ static keys / ML-DSA required?
- Published Noise patterns only (XX/IK, hfs) vs a cairn-style pay-once pairing + resume (needs spec, formal model, expert review).
- Do broadcasts carry origin signatures, and what does that cost under the PQ profile?
- Is FIPS-approved-algorithm-only required (excludes ChaCha20-Poly1305)?
- No Wycheproof vectors exist for Noise hfs/PQNoise/X-Wing: plan our own cross-implementation vectors.
- Bounds and DoS limits for unauthenticated, fragmented 1.2 KB+ handshake messages.
([crypto findings](03-crypto-options.md))
- With Xcode 16.4 fixed (iOS 18.5 SDK), CryptoKit ML-KEM/X-Wing can't be compiled against: iOS post-quantum must use bundled crypto ([device lab](06-device-lab-readiness.md)).
