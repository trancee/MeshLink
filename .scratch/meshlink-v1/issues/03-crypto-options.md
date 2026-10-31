# Which crypto options fit each security profile?

Type: research
Status: open

## Question

Compare handshakes (Noise XX/IK/KK, PQNoise, cairn-style pay-once PQ pairing + resume), algorithms (X25519/Ed25519, AES-GCM vs ChaCha20-Poly1305, X-Wing / ML-KEM-768 hybrid, ML-DSA), and providers (aws-lc-rs via Rust+UniFFI, Apple CryptoKit incl. any ML-KEM support, Android Conscrypt/BoringSSL/Tink, pure-Kotlin libraries). Score each against E3 {need, maintenance, license, security, size, transitives}, availability on Android 26+/iOS 15+, conformance vectors (Wycheproof, ACVP), and bytes-on-air vs BLE MTU.
