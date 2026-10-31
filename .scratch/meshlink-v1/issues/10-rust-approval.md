# Which crypto provider is approved, and is Rust part of it?

Type: grilling
Status: open
Blocked by: 03, 05

## Question

Using the crypto-options and toolchain findings, decide whether Rust is used (e.g. crypto core via aws-lc-rs + UniFFI), its scope, binding tool, build/CI cost and binary-size impact, or whether platform/pure-Kotlin providers suffice. Requires explicit user approval.

## Scope additions (from research, 2026-10-31)

- Broadened: decide the crypto provider, not just Rust: libsodium 1.0.22 via own JNI/cinterop (C), Rust aws-lc-rs core via UniFFI, or a platform split with bundled fallback. Open facts to verify inside this ticket: javax.crypto.KEM on Android, snow's ML-KEM support, native library sizes, ML-KEM code audit status, maintained KMP Noise libraries.
- From the [suite decision](09-security-profile-suites.md): the provider must supply X25519, Ed25519, ChaCha20-Poly1305, SHA-256/HKDF, ML-KEM-768, X-Wing and HPKE with X-Wing (bundled on iOS too, because of Xcode 16.4). Verify HPKE+X-Wing standardization status and provider support. Benchmark X-Wing/ML-KEM/X25519 operations on lab phones. A Noise implementation must support `hfs` and `psk` (own or library).
