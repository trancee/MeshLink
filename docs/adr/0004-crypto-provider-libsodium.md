# 0004: libsodium as the crypto provider; Rust only as a test-only reference

Status: accepted (2026-11-01; ticket "Which crypto provider is approved, and is Rust part of it?"). Version: 3 (amended 2026-11-03, iPhone SE (2016) benchmark; 2026-11-14, native gates).

## Context

[ADR 0002](0002-noise-suites-and-handshake-patterns.md) and [ADR 0003](0003-installation-identity-key-model.md) need these primitives: X25519, Ed25519, ChaCha20-Poly1305, SHA-256/HKDF, ML-KEM-768, X-Wing, and HPKE with X-Wing. They must work at Android minSdk 26 and iOS 15, built with Xcode 16.4.

Facts verified 2026-11-01:

- **Platform crypto falls short.**
  - On Android, X25519 and Ed25519 need API 33, and ChaCha20-Poly1305 needs API 28.
  - Android has no ML-KEM, and `javax.crypto.KEM` is not in the public SDK.
  - Android's platform HPKE (API 37) offers only DHKEM-X25519.
  - Unbundled Conscrypt 2.8.0 has X-Wing, but adds 2.79 MB per arm64 ABI.
  - CryptoKit's ML-KEM and X-Wing need the iOS 26 SDK, which Xcode 16.4 doesn't have.
- **libsodium 1.0.22** (2026-04-09, ISC license) covers every primitive above:
  - `crypto_scalarmult`, `crypto_sign`, `crypto_aead_chacha20poly1305_ietf`, `crypto_hash_sha256` and `crypto_kdf_hkdf_sha256`;
  - `crypto_kem_mlkem768_*` and `crypto_kem_xwing_*`, each with deterministic variants for known-answer tests.
  
  Its X-Wing keeps a 32-byte seed, expands it with SHAKE256 and uses the SHA3-256 combiner. It has no HPKE and no Noise. HPKE is an open item on the [libsodium 1.0.x roadmap](https://doc.libsodium.org/roadmap). In [issue #1044](https://github.com/jedisct1/libsodium/issues/1044), the maintainer's conditions for accepting HPKE were: no heap allocation, capped inputs, and keys that can be exported.
- **Rust options:**
  - rust-hpke 0.15.1 tracks `draft-ietf-hpke-pq-04` and X-Wing draft-10.
  - RustCrypto `ml-kem` 0.3.4 and `x-wing` 0.1.1 (draft-06) are unaudited.
  - libcrux-ml-kem's formal verification covers its portable and AVX2 code, not the ARM NEON path.
  - aws-lc-rs has no X-Wing and needs a jitter-entropy workaround (cairn ADR 0024).
- **Noise libraries:** no maintained Kotlin Multiplatform Noise library exists. snow's `hfs` supports only Kyber1024, and clatter implements PQNoise rather than `hfs`.
- **HPKE draft:** `draft-ietf-hpke-pq-05` defines MLKEM768-X25519 (codepoint 0x647a) and ships test vectors in Appendix A.5. X-Wing itself is `draft-connolly-cfrg-xwing-kem-11`, an Independent Submission.
- **Cost:** ML-KEM-768 decapsulation takes about 140k cycles on a Cortex-A55 ([mlkem-native benchmarks](https://pq-code-package.github.io/mlkem-native/dev/bench/)), so speed doesn't decide the provider.

## Decision

- **Shipped provider: libsodium 1.0.22**, through an internal `CryptoProvider` interface in common code.
- **Binding:** a thin binding of our own: a JNI shim on Android and cinterop on iOS. It exposes only the functions MeshLink calls.
- **Build:**
  - Built from the pinned 1.0.22 source, with checksum and signature verification, using the upstream `dist-build` scripts.
  - Linked statically with symbols hidden, so it cannot clash with a host app's own copy of libsodium.
- **Protocol code in Kotlin common:**
  - Noise (`XX`, `XXpsk3`, `IK`, `XXhfs`, `XXhfspsk3`, `IKhfs`), HPKE base mode and the identity certificate are written once, over `CryptoProvider`.
  - No per-platform protocol logic.
- **Rust is not part of the shipped library.** It is approved only as a **test-only reference implementation**, never published or linked into an artifact. That implementation produces the second, independent set of vectors that ADR 0002 requires for `hfs`, the certificate and the nested seal. It uses:
  - rust-hpke;
  - RustCrypto `ml-kem`;
  - `x25519-dalek` and `ed25519-dalek`;
  - `chacha20poly1305`, `sha2` and `hkdf`.

  The toolchain baseline pins exact versions and records an E3 rationale for each crate.
- **X-Wing pin:** MLKEM768-X25519 (0x647a) as specified in `draft-ietf-hpke-pq-05`. Its Appendix A.5 vectors are a conformance gate for the provider. A failure goes back to the project owner rather than being patched around.
- **Gates:**
  - Native code is at most 1 MB per ABI.
  - Microbenchmarks of the primitives on the oldest Android and the oldest iPhone in the lab, recorded as T9 evidence.
  - Wycheproof, cacophony and hpke-pq vectors, plus MeshLink's own two-implementation vectors.
- **Provider swaps stay internal.** CryptoKit post-quantum on iOS 26+, a FIPS-validated module or a later signature suite may replace libsodium behind `CryptoProvider`. A swap never changes the wire format.
- **Upstream:**
  - **HPKE:** MeshLink implements HPKE itself and never waits for libsodium. After our HPKE passes the hpke-pq vectors, we draft an offer to contribute HPKE base mode to libsodium: X-Wing 0x647a, ChaCha20-Poly1305, capped inputs, no allocation. It goes in libsodium's Discussions as an "Ideas" thread, and posting needs the project owner's explicit approval. If libsodium releases HPKE, it replaces ours behind `CryptoProvider`.
  - **Noise:** not upstreamed. It is a protocol framework, `hfs` is unofficial, and it does not match libsodium's planned key-exchange API. Whether to publish MeshLink's Noise as a standalone artifact is decided with the module layout.
  - **X-Wing:** already in libsodium, so there is nothing to upstream.

## Dependency rationale (E3): libsodium

| Field | Rationale |
|---|---|
| Need | X25519, Ed25519, ChaCha20-Poly1305, SHA-256/HKDF, ML-KEM-768 and X-Wing on Android 26+ and iOS 15+ from one implementation |
| Maintenance | Active since 2013. Point release 1.0.22 came out 2026-04, plus frequent stable maintenance releases. Builds for Android and Apple XCFrameworks are maintained upstream. |
| License | ISC |
| Security | Third-party audit of the 1.0.x core (roadmap: "Security audit by a 3rd party [DONE]"). The ML-KEM and X-Wing code is new and unaudited, as it is in every candidate. Coverage comes from Wycheproof and hpke-pq vector gates plus the independent Rust reference. |
| Size | About 283 KB for an older arm64 build. The 1.0.22 size is unverified; the gate is at most 1 MB per ABI. |
| Transitives | None |
| Why platform or existing fails | Platform floors (above). The existing Kotlin libsodium wrapper lacks the KEM API and exposes the whole libsodium surface. |

## Alternatives

- **Rust core (RustCrypto + rust-hpke) through UniFFI:**
  - Pro: ready-made HPKE.
  - Rejected for the shipped library because of its costs: a Rust toolchain, UniFFI and cargo-ndk, a second language for contributors, larger artifacts and cairn-level binding proofs.
  - It stays as the test-only reference.
- **aws-lc-rs:** no X-Wing, and a jitter-entropy abort on real phones without a workaround.
- **Platform split with a bundled fallback:** two to three implementations to keep in agreement, plus Conscrypt's 2.79 MB.
- **libcrux:** verified ML-KEM, but its X-Wing tracks draft-06 and it publishes no size numbers.
- **Existing Kotlin wrappers** (cryptography-kotlin 0.6.0 has no ML-KEM; the libsodium Kotlin wrapper has no KEM API): coverage gaps.

## Consequences

- We own the JNI and cinterop glue, the HPKE code and the Noise state machine. They need their own tests, fuzzing and S6 review.
- The libsodium build joins the toolchain baseline: NDK/CMake, cinterop definitions and XCFramework packaging.
- Results from the X-Wing vector gate can reopen this decision.

## Amendments

2026-11-03, iPhone SE (2016) benchmark ([evidence](../evidence/2026-11-se2016-bench/README.md)):

- **Hidden symbols need an explicit export list.** A plain static link does not hide them: the benchmark's `MeshLinkKit` framework exported 228 libsodium symbols. The XCFramework and the Android shared library therefore build with an export allow-list (`-exported_symbols_list` on Apple, a version script on Android) that exports only MeshLink's own API. A CI gate inspects the built binaries with `nm` and fails if any `sodium_` or `crypto_` symbol is exported.
- **Speed on the slowest supported iPhone (T9):** on the SE 2016 with iOS 15.8, all crypto of one post-quantum link handshake (`XXhfs`) took under 0.8 ms median per side, and a post-quantum first message (`IKhfs` plus HPKE X-Wing, 1 KB) under 0.8 ms per side. ChaCha20-Poly1305 took 1.8 µs for 244 B. The X-Wing and hpke-pq vectors matched on the device. Crypto cost is negligible against the radio budgets of ADR 0008.

2026-11-14, native gates (0.1.0 backlog ticket "Hash, generate identities and draw randomness through a minimal `CryptoProvider`"):

- **What "1 MB" measures.** 1,000,000 bytes, the stricter decimal reading.
  - Android: the stripped JNI shim per ABI, the whole native payload MeshLink adds to an app. Measured 2026-11-14: arm64-v8a 144,632 B, armeabi-v7a 188,252 B, x86_64 229,008 B.
  - iOS: the sum of every member's segments (`size`, `dec` column) in each slice's `libsodium.a`. Dead-code stripping keeps a framework's share smaller, so this is an upper bound that doesn't change as `:meshlink` starts calling crypto. Measured 2026-11-14: `ios-arm64` 235,397 B, `ios-arm64-simulator` 247,105 B.
- **Symbol rule.** A binary fails if it exports a symbol starting with `sodium_`, `crypto_` or `randombytes_` (an optional leading `_` for Mach-O), or exports or imports a symbol matching `aes`, `aegis` or `ipcrypt` at the start or after `_`, ignoring case. `randombytes_` widens the 2026-11-03 rule: it is libsodium API too.
- **Where the gates run.** `check` runs the Android shim gates on Linux CI with the NDK's `llvm-nm`. The macOS job checks the iOS archives' size and the release `MeshLinkKit` slices' symbols with `xcrun nm`. The framework's export allow-list is `meshlink/MeshLinkKit.exports`: its Objective-C classes and Swift module symbols only.
- **Klib ABI dump.** `:meshlink`'s iOS klibs now need the libsodium cinterop, which runs only on a Mac. `meshlink/gradle.properties` sets `kotlin.native.enableKlibsCrossCompilation=false` for that module only, so off-Mac hosts infer the iOS dump from the committed one instead of writing an empty dump. `:meshlink-testing`, whose main code depends on `:meshlink`, sets the same in `meshlink-testing/gradle.properties`. The macOS job runs `checkKotlinAbi` against the real klibs.
