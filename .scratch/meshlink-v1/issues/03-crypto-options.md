# Which crypto options fit each security profile?

Type: research
Status: resolved

## Question

Compare handshakes (Noise XX/IK/KK, PQNoise, cairn-style pay-once PQ pairing + resume), algorithms (X25519/Ed25519, AES-GCM vs ChaCha20-Poly1305, X-Wing / ML-KEM-768 hybrid, ML-DSA), and providers (aws-lc-rs via Rust+UniFFI, Apple CryptoKit incl. any ML-KEM support, Android Conscrypt/BoringSSL/Tink, pure-Kotlin libraries). Score each against E3 {need, maintenance, license, security, size, transitives}, availability on Android 26+/iOS 15+, conformance vectors (Wycheproof, ACVP), and bytes-on-air vs BLE MTU.

## Answer

Platform crypto alone covers neither secured profile at the baselines. Android 26 has no platform X25519 (API 33+) and no ML-KEM; ChaCha20-Poly1305 arrives at API 28. On iOS, CryptoKit's ML-KEM-768 and X-Wing need iOS 26+. `classical` fits standard Noise (XX first contact, IK/KK pinned; X25519, AES-256-GCM or ChaChaPoly, SHA-256). Every message is 48–96 B, which fits one ≤512 B attribute, and Wycheproof plus cacophony/snow vectors exist. `post-quantum` candidates are hybrid X25519 + ML-KEM-768 with classical authentication:

- Noise `hfs`: ~2.4 KB per link handshake; the spec is unofficial and has no vectors.
- cairn-style X-Wing pay-once plus a 106 B resume: a novel composition, so S6 expert review applies.

PQNoise (~6.9 KB) and ML-DSA (≥2.4 KB signatures) look too heavy. Every PQ handshake message exceeds 512 B, so fragmentation is mandatory. X-Wing is still draft-11 and has no Wycheproof vectors.

Provider candidates:

- libsodium 1.0.22: ISC; its 2026-04 release adds ML-KEM-768 and X-Wing, so one C library covers both platforms. Its AES-GCM is hardware-gated, and the KMP wrapper lacks the KEM API.
- A Rust core on aws-lc-rs or RustCrypto via UniFFI: needs the Rust ticket. aws-lc-rs has no X-Wing, and AWS-LC's jitter entropy needs a workaround.
- A platform/Conscrypt/BouncyCastle split: several implementations, and iOS 15–25 PQ still needs bundling.

cryptography-kotlin 0.6.0 has no PQ algorithms. Conscrypt 2.8.0 exposes ML-KEM and X-Wing only through HPKE on old Android.

Findings: [`docs/research/2026-10-31-crypto-options.md`](../../../docs/research/2026-10-31-crypto-options.md) (merged into `main` from `research/crypto-options` on 2026-11-01).
