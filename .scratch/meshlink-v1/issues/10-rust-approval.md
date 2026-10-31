# Which crypto provider is approved, and is Rust part of it?

Type: grilling
Status: resolved
Blocked by: 03, 05

## Question

Using the crypto-options and toolchain findings, decide whether Rust is used (e.g. crypto core via aws-lc-rs + UniFFI), its scope, binding tool, build/CI cost and binary-size impact, or whether platform/pure-Kotlin providers suffice. Requires explicit user approval.

## Scope additions (from research, 2026-10-31)

- Broadened: decide the crypto provider, not just Rust: libsodium 1.0.22 via own JNI/cinterop (C), Rust aws-lc-rs core via UniFFI, or a platform split with bundled fallback. Open facts to verify inside this ticket: javax.crypto.KEM on Android, snow's ML-KEM support, native library sizes, ML-KEM code audit status, maintained KMP Noise libraries.
- From the [suite decision](09-security-profile-suites.md): the provider must supply X25519, Ed25519, ChaCha20-Poly1305, SHA-256/HKDF, ML-KEM-768, X-Wing and HPKE with X-Wing (bundled on iOS too, because of Xcode 16.4). Verify HPKE+X-Wing standardization status and provider support. Benchmark X-Wing/ML-KEM/X25519 operations on lab phones. A Noise implementation must support `hfs` and `psk` (own or library).

## Answer

Agreed with the user in two grilling rounds on 2026-11-01 and recorded in `docs/adr/0004-crypto-provider-libsodium.md`.

- **Provider:** libsodium 1.0.22, behind an internal `CryptoProvider`. It covers X25519, Ed25519, ChaChaPoly, SHA-256/HKDF, ML-KEM-768 and X-Wing. Our own thin JNI/cinterop binding; built from pinned, verified source; linked statically with symbols hidden.
- **Rust:** not shipped. Approved only as a test-only reference implementation (rust-hpke, RustCrypto `ml-kem`, dalek, `chacha20poly1305`/`sha2`/`hkdf`) that generates the second, independent vector set.
- **Kotlin common:** Noise (all six patterns), HPKE base mode and the identity certificate.
- **X-Wing pin:** `draft-ietf-hpke-pq-05` MLKEM768-X25519 (0x647a). The Appendix A.5 vectors are a provider gate, and a failure goes back to the user.
- **Gates:** native code ≤ 1 MB per ABI; microbenchmarks on the oldest lab phones as T9 evidence. Swapping the provider never changes the wire format.
- **Upstream:** the user asked about libsodium. X-Wing is already in 1.0.22. HPKE is open on libsodium's roadmap: after our HPKE passes the vectors, we draft an "Ideas" Discussions thread offering it upstream, and posting needs the user's explicit approval. MeshLink never depends on it landing. Noise is not upstreamed (a protocol framework, and `hfs` is unofficial); whether to publish it standalone goes to the modules ticket.
- **Fact sources:** the libsodium 1.0.22 source and ChangeLog, [roadmap](https://doc.libsodium.org/roadmap), [issue #1044](https://github.com/jedisct1/libsodium/issues/1044), `draft-ietf-hpke-pq-05`, `draft-connolly-cfrg-xwing-kem-11`, crates.io, Android API reference and the Conscrypt 2.8.0 artifacts. All are listed in the ADR.
