# Research: crypto options per security profile

Status: research findings, not a decision. Any adoption needs an ADR
(Constitution O3), an E3 dependency rationale with user approval, and, for any
new protocol composition, a spec, expert review and conformance vectors (S6, S7).
Ticket: `.scratch/meshlink-v1/issues/03-crypto-options.md`.
Access date for every URL below: **2026-10-31** unless stated otherwise.

## 1. Question

Which handshakes (Noise XX/IK/KK, PQNoise, Noise `hfs`, cairn-style pay-once
PQ pairing + resume), algorithms (X25519/Ed25519, AES-GCM vs
ChaCha20-Poly1305, X-Wing / ML-KEM-768, ML-DSA) and providers (aws-lc-rs via
Rust+UniFFI, Apple CryptoKit, Android platform JCA / Conscrypt / Tink,
BouncyCastle, libsodium, pure-Kotlin/KMP libraries) fit each MeshLink security
profile (`none`, `classical`, `post-quantum`; one per mesh, no negotiation, no
downgrade)? Score them against E3 `{need, maintenance, license, security,
size, transitives}`, availability on Android 26+ / iOS 15+, conformance vectors
(Wycheproof, ACVP, Noise vectors) and bytes-on-air vs BLE ATT limits.

## 2. Key findings (short)

1. **iOS 15 has no platform ML-KEM/X-Wing.** CryptoKit `MLKEM768` and
   `XWingMLKEM768X25519` are iOS **26.0+** [A1, A2]. Classical primitives are
   iOS 13+ (HKDF iOS 14+) [A3]. A `post-quantum` profile on iOS 15–25 needs a
   bundled implementation, or the PQ profile's minimum iOS must be raised.
2. **Android 26 has no platform X25519 or ML-KEM.** Platform JCA: `XDH`
   KeyAgreement/KeyPairGenerator API **33+**, `Ed25519` Signature API 33+,
   `ChaCha20/Poly1305` API **28+**, `AES/GCM/NoPadding` API 10+ (AES_256/GCM
   26+), no HKDF, no ML-KEM; Keystore `KEY_ALGORITHM_ML_DSA*` API **37** [B1–B4].
   Even the `classical` profile therefore needs a bundled provider on
   Android 26–32.
3. **libsodium 1.0.22 (2026-04-09) now has ML-KEM-768 and X-Wing**
   (`crypto_kem_mlkem768_*`, `crypto_kem_xwing_*`, pk 1216 B / ct 1120 B),
   plus SHA-3 [C1, C2]. Together with its existing X25519, Ed25519,
   ChaCha20-Poly1305 and HMAC-SHA-256/512, it is the only single ISC-licensed
   C library found that covers *both* secured profiles on both platforms. Its
   AES-256-GCM requires ARM Crypto Extensions and has no software fallback
   [C3]. The KMP wrapper `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings`
   0.9.5 (2025-11-22) predates 1.0.22, so it lacks the KEM API; its Android
   build uses JNA [C4].
4. **Conscrypt 2.8.0 (bundled `conscrypt-android`, minSdk 21, AAR 4.99 MB)**
   has X25519, ChaCha20-Poly1305, XChaCha20-Poly1305, ML-KEM and X-Wing key
   generation, and HPKE suites `MLKEM_768/…` and `XWING/…`. Its raw JCA
   `KEM.ML-KEM*` and `KDF.HKDF-*` registrations happen only when the
   `javax.crypto.KEM` / `javax.crypto.KDF` classes exist [D1, D2]. On Android
   26 that leaves no raw KEM encaps/decaps, only one-shot HPKE. **Unverified:**
   whether any shipped Android API level exposes `javax.crypto.KEM`.
5. **cryptography-kotlin 0.6.0** (whyoleg, Apache-2.0, released 2026-04-02,
   last push 2026-10-31) has no ML-KEM, ML-DSA or X-Wing provider code on
   `main`. On Android its JDK provider uses platform JCA, so XDH needs API 33
   [E1–E3].
6. **X-Wing** is still an Internet-Draft: draft-connolly-cfrg-xwing-kem-**11**,
   23 Sep 2026, Informational, CFRG [F1]. Apple cites draft-06 [A2] and
   BouncyCastle draft-07 [G2]. cairn's source-level review found no
   construction change from -05 to -11 [P3]. Wycheproof has **no** X-Wing
   vectors [H1].
7. **Noise** rev 34 (2018-07-11) is current; max message 65 535 B [I1]. The
   `hfs` modifier (Perrin, "unofficial/unstable", 2018-11-17) adds a KEM to
   classical patterns [I2]. **PQNoise** (CCS 2022, ePrint 2022/539) replaces DH
   with KEMs and has proofs [I3]. Noise vectors cover XX/IK/KK with 25519
   (cacophony has 24 such vectors) [I4], but snow's vector file has **zero
   `hfs`/KEM patterns** [I5].
8. **Bytes-on-air:** every classical Noise message fits one ≤512 B attribute.
   No PQ handshake message (each ≥1 152 B) fits, so `post-quantum` forces
   handshake fragmentation whatever the handshake shape (§6).

## 3. Sources

Prior art (study only): cairn ADR 0001, `docs/research/2026-10-05-pqc-over-ble-findings.md`,
`2026-10-05-fips-validated-mlkem-modules.md`, `2026-10-08-x-wing-draft-11-vectors.md`,
`2026-10-08-gobley-maintenance.md`, `docs/explanation/compact-pqc-over-ble.md`,
`docs/agents/lessons.md`; MeshLink-template `docs/decisions/crypto/*`,
`pq-hybrid-candidate-matrix.md`, `docs/explanation/template-design-review.md`.
The template's PQ matrix reports "+184 bytes" for a hybrid. That cannot carry
a 1 184 B ML-KEM-768 key, as the template's own design review says, so it is
**not used as evidence**.

| Id | Source |
|---|---|
| A1 | <https://developer.apple.com/documentation/cryptokit/mlkem768.md> (availability iOS 26.0) |
| A2 | <https://developer.apple.com/documentation/cryptokit/xwingmlkem768x25519.md> (iOS 26.0; "draft-connolly-cfrg-xwing-kem-06") |
| A3 | `https://developer.apple.com/documentation/cryptokit/{curve25519/keyagreement,chachapoly,aes/gcm,sha384,hmac}.md` (iOS 13.0), `hkdf.md` (iOS 14.0) |
| A4 | <https://developer.apple.com/documentation/cryptokit/mldsa65.md> (iOS 26.0) |
| B1 | <https://developer.android.com/reference/javax/crypto/KeyAgreement> (XDH 33+) |
| B2 | <https://developer.android.com/reference/javax/crypto/Cipher> (AES GCM 10+, AES_256 GCM 26+, ChaCha20/Poly1305 28+) |
| B3 | <https://developer.android.com/reference/java/security/Signature> (Ed25519 33+), …/KeyPairGenerator (XDH 33+) |
| B4 | <https://developer.android.com/reference/android/security/keystore/KeyProperties> (KEY_ALGORITHM_ML_DSA* added API 37; no ML-KEM constant) |
| B5 | <https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int)> (Android 14+ requests ATT MTU 517) |
| C1 | <https://github.com/jedisct1/libsodium/releases/tag/1.0.22-RELEASE> |
| C2 | <https://github.com/jedisct1/libsodium/blob/master/src/libsodium/include/sodium/crypto_kem_xwing.h> |
| C3 | <https://github.com/jedisct1/libsodium-doc/blob/master/secret-key_cryptography/aes-256-gcm.md> |
| C4 | <https://github.com/ionspin/kotlin-multiplatform-libsodium> (README, CHANGELOG 0.9.5) |
| D1 | <https://github.com/google/conscrypt/releases> (2.8.0 2026-10-20, 2.7.0 2026-08-31) |
| D2 | <https://github.com/google/conscrypt/blob/2.8.0/common/src/main/java/org/conscrypt/OpenSSLProvider.java>; `android/build.gradle` (minSdk 21); Maven Central `conscrypt-android-2.8.0.aar` (4 985 129 B) |
| E1 | <https://github.com/whyoleg/cryptography-kotlin> (README, releases, repo tree) |
| E2 | <https://github.com/whyoleg/cryptography-kotlin/blob/main/cryptography-providers/cryptokit/build.gradle.kts> (Swift interop module) |
| E3 | GitHub issue search `repo:whyoleg/cryptography-kotlin ML-KEM OR kyber OR post-quantum` (no ML-KEM issue; #160 SHAKE256 open; #159 Android EC import bug open) |
| F1 | <https://datatracker.ietf.org/doc/draft-connolly-cfrg-xwing-kem/> and <https://www.ietf.org/archive/id/draft-connolly-cfrg-xwing-kem-11.txt> |
| G1 | Maven Central `org.bouncycastle:bcprov-jdk18on` 1.86 (2026-09-11; jar 7 224 011 B) |
| G2 | <https://github.com/bcgit/bc-java/tree/main/core/src/main/java/org/bouncycastle/pqc/crypto/xwing> (draft-07) |
| G3 | <https://github.com/tink-crypto/tink-java> (v1.24.0 2026-10-16; X-Wing HPKE via Conscrypt); Maven `tink-android-1.24.0.jar` 3 508 938 B |
| G4 | crates.io API: aws-lc-rs 1.19.0 (2026-10-28, `ISC AND (Apache-2.0 OR ISC)`), ml-kem 0.3.3, x-wing 0.1.1, libcrux-ml-kem 0.0.11, libcrux-kem 0.0.10, x25519-dalek 3.0.0, aes-gcm 0.11.1, chacha20poly1305 0.11.0, hkdf 0.13.0, snow 0.10.0 (2025-07-19), clatter 2.3.0 |
| G5 | <https://github.com/aws/aws-lc-rs/blob/main/aws-lc-rs/src/kem.rs> (ML-KEM-512/768/1024); aws/aws-lc tree (ML-KEM, no X-Wing file found) |
| G6 | <https://github.com/jmlepisto/clatter> (Noise rev 34 + PQNoise; "has not received any formal audit") |
| H1 | <https://github.com/C2SP/wycheproof/tree/main/testvectors_v1> |
| H2 | <https://github.com/usnistgov/ACVP-Server/tree/master/gen-val/json-files> |
| I1 | <https://noiseprotocol.org/noise.html> (rev 34, 2018-07-11) |
| I2 | <https://github.com/noiseprotocol/noise_hfs_spec/blob/master/noise_hfs.md> |
| I3 | <https://eprint.iacr.org/2022/539> (doi 10.1145/3548606.3560577) |
| I4 | <https://github.com/haskell-cryptography/cacophony/blob/master/vectors/cacophony.txt> |
| I5 | <https://github.com/mcginty/snow/blob/main/tests/vectors/snow.txt> |
| J1 | Bluetooth Core 6.0, Vol 3 Part F (ATT) §3.2.9: "The maximum length of an attribute value shall be 512 octets." <https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-60/out/en/host/attribute-protocol--att-.html> |
| P1–P3 | cairn ADR 0001; cairn PQC-over-BLE findings (airtime model, sizes from FIPS 203); cairn X-Wing draft-11 vectors note (2026-10-08) |

## 4. Options per profile

### 4.1 `none`

- No key exchange, no AEAD. Plaintext, unauthenticated, loudly labeled.
- Crypto dependency: none. Integrity against radio corruption is a framing
  concern, not a security one: cairn found a controller that corrupted 512 B
  writes while the link-layer CRC still passed, and added a per-fragment CRC
  (cairn lessons, issue 59).
- Open: the profile identifier cannot be authenticated in `none`. An admission
  rule must stop an attacker who relabels frames from pulling a `none` node
  into a secured mesh.

### 4.2 `classical`

| Component | Candidates | Notes |
|---|---|---|
| Link handshake | Noise `XX` (no prior key), `IK` (initiator knows responder static), `KK` (both pinned) | Standard, well analyzed; vectors exist (§7). XX = 3 msgs/1.5 RTT, IK/KK = 2 msgs/1 RTT. |
| DH | X25519 | iOS CryptoKit 13+; Android platform 33+ only, so bundle a provider on 26–32. |
| AEAD | AES-256-GCM (`AESGCM`) or ChaCha20-Poly1305 (`ChaChaPoly`) | AES-GCM is platform-native on both (Android 26 AES_256/GCM; iOS 13). ChaCha is platform-native on iOS 13 but on Android only from 28. libsodium AES-GCM needs ARM CE [C3]. |
| Hash/KDF | SHA-256 or SHA-512 HMAC-HKDF (Noise) | Platform-native on both. Android has no HKDF class, but Noise defines its HKDF over HMAC [I1]. |
| Signatures | Ed25519, only if mesh-broadcast origin authentication or signed routing adverts need it | iOS 13; Android platform 33+. 64 B sig / 32 B pk. Noise itself needs no signatures. |

Candidate suite names: `Noise_XX_25519_AESGCM_SHA256`,
`Noise_IK_25519_ChaChaPoly_SHA256` and so on (protocol-name rules [I1]).

### 4.3 `post-quantum`

Every candidate below is hybrid (X25519 + ML-KEM-768) for confidentiality.
Pure ML-KEM saves ~32–64 B but loses the classical safety net (cairn ADR 0001
alternatives). ML-KEM-512 is smaller with a thinner margin; ML-KEM-1024 is
what CNSA 2.0 requires (sizes from FIPS 203 via cairn P2).

| Shape | Description | Status/evidence | Main cost |
|---|---|---|---|
| PQ-a. Noise `hfs` + ML-KEM-768 | Classical pattern plus `e1`/`ekem1` tokens; KEM output mixed into the chaining key | Spec unofficial/unstable [I2]; no public `hfs` vectors found [I5]; snow supports `hfs` (with an older Kyber; **unverified** whether ML-KEM) | ~2.4 KB per link handshake (§6) |
| PQ-b. PQNoise (KEM-only or hybrid patterns) | DH replaced by KEMs, including static KEM keys | Peer-reviewed proofs [I3]; only Rust impl found is clatter (unaudited) [G6]; no standard vectors | ~6.9 KB for pqXX; 4 messages |
| PQ-c. cairn-style pay-once | X-Wing pairing once (+SAS/OOB/TOFU), then a symmetric ratchet + X25519 resume (57+49 B), with amortized ML-KEM-768 refresh carried in data padding | Novel composition (S6: spec + expert review + formal model); cairn has Tamarin work but no MeshLink-equivalent proof | 2.3 KB once per peer pair; 106 B per resume; per-pair state |
| PQ-d. Classical Noise + one-shot X-Wing HPKE-style payload | X-Wing ct carried in the Noise payload, ss mixed in through a PSK-like slot | Not a published construction, so S6 applies as a new composition. Listed only because Conscrypt/Tink expose X-Wing *only* as HPKE [D2, G3] | ~1.1–2.3 KB |

Authentication options in `post-quantum`:

1. Classical (X25519 statics / Ed25519). Harvest-now-decrypt-later threatens
   confidentiality, not authentication, and cairn ADR 0001 also chose "no
   signatures".
2. PQ statics via KEM (PQNoise `skem`), at +1.1–2.3 KB.
3. ML-DSA signatures: ML-DSA-44 sig 2 420 B; ML-DSA-65 pk 1 952 B / sig
   3 309 B (FIPS 204 via cairn P1/P2; ML-DSA-44 pk 1 312 B from FIPS 204,
   **not re-fetched**). Platform ML-DSA: iOS 26+ [A4], Android Keystore API
   37+ [B4]. Signing a mesh broadcast with ML-DSA-44 would add ≥5
   attribute-sized fragments to every broadcast for the signature alone.

## 5. E3 scoring per provider

Scale per cell: ✔ good, ~ caveat, ✘ fails for this use. "Need / coverage"
names the profiles this provider alone can fully serve on Android 26+ **and**
iOS 15+.

| Provider | Need / coverage | Maintenance | License | Security | Size | Transitives |
|---|---|---|---|---|---|---|
| Platform split: Android JCA + iOS CryptoKit | ~ classical only on iOS 13+/Android 33+; ✘ X25519 on Android 26–32; ✘ PQ on iOS<26 and on all Android (no ML-KEM) [A1–A3, B1–B4] | ✔ OS vendor | ✔ OS | ✔ vendor-maintained, hardware AES; CryptoKit is Swift-only, so needs a Swift bridge | ✔ 0 B | ✔ none (Swift interop shim) |
| cryptography-kotlin 0.6.0 (JDK + CryptoKit/Apple/OpenSSL3 providers) | ~ same Android gaps as the platform split (JDK provider); ✘ no ML-KEM/X-Wing/ML-DSA [E1–E3] | ✔ active (push 2026-10-31); 1 maintainer; pre-1.0 (0.x API) | ✔ Apache-2.0 | ~ thin wrapper, so security = underlying provider; open Android EC import bug #159 | ✔ core 302 KB + jdk provider 337 KB (JVM jars) | ✔ kotlinx only (full tree **unverified**) |
| Conscrypt-android 2.8.0 (bundled, Android only) | ✔ classical on Android 26; ~ PQ only through HPKE, no raw KEM on API 26 [D2] | ✔ Google; release 2026-10-20 | ✔ Apache-2.0 | ✔ BoringSSL core | ✘ 4.99 MB AAR (all ABIs, before ABI split) | ✔ none (native BoringSSL inside) |
| Tink-android 1.24.0 (Android only) | ✘ high-level primitives only; X-Wing only as HPKE through Conscrypt [G3] | ✔ Google; 2026-10-16 | ✔ Apache-2.0 | ✔ misuse-resistant API, but not a Noise primitive set | ~ 3.5 MB jar (pre-R8) | ~ protobuf (+ Conscrypt for X-Wing) (exact tree **unverified**) |
| BouncyCastle bcprov-jdk18on 1.86 (Android only) | ✔ X25519, Ed25519, AES-GCM, ChaCha, HKDF, ML-KEM, X-Wing (draft-07), ML-DSA in the lightweight API [G1, G2] | ✔ active (push 2026-10-31) | ✔ MIT | ~ pure Java; ART/JVM give no constant-time guarantee (per-primitive claims **unverified**) | ✘ 7.2 MB jar pre-R8 | ✔ none |
| libsodium 1.0.22 (C, own JNI + cinterop) | ✔ classical + PQ on both platforms (X25519, Ed25519, ChaCha20-Poly1305, HMAC-SHA-256/512, ML-KEM-768, X-Wing); ~ AES-GCM only with ARM CE [C1–C3] | ✔ active (push 2026-10-31); single lead maintainer; KEM API new (2026-04) | ✔ ISC | ✔ long-audited core; ~ ML-KEM/X-Wing code is young (audit status **unverified**) | ~ ionspin AAR 0.77 MB (older version, all ABIs, without JNA); own build **unmeasured** | ~ none if bound directly; the ionspin wrapper adds JNA and lacks KEM [C4] |
| Rust core: aws-lc-rs 1.19.0 + UniFFI (Ubique) | ✔ X25519, AES-GCM, ChaCha, HKDF, ML-KEM-768 [G5]; ✘ no X-Wing, so the combiner is hand-built (new code under S6) | ✔ AWS; 2026-10-28 | ✔ ISC AND (Apache-2.0 OR ISC) | ✔ FIPS lineage (no mobile OE certificate, cairn P2); ~ AWS-LC jitter entropy aborted the app on a Kirin phone, so it needs `AWS_LC_SYS_NO_JITTER_ENTROPY=1` (cairn lessons, issue 51) | ~ per-ABI native size **unmeasured** | ✘ Rust toolchain + UniFFI + Ubique plugin + cmake/NASM build deps; needs the Rust-approval ticket |
| Rust core: RustCrypto (ml-kem 0.3.3, x-wing 0.1.1, x25519-dalek 3.0.0, aes-gcm, chacha20poly1305, hkdf) or libcrux 0.0.x | ✔ full set incl. X-Wing | ~ active, but pre-1.0 versions | ✔ MIT/Apache-2.0 (dalek BSD-3) | ~ per-crate audit status **unverified**; libcrux's formally verified ML-KEM claim **unverified** here | ~ **unmeasured** | ✘ as above (Rust ticket) |
| Apple CryptoKit PQ (MLKEM768, XWing, MLDSA65) | ✘ iOS 26+ only [A1, A2, A4] | ✔ Apple | ✔ OS | ✔ | ✔ 0 B | ✔ |
| Noise libraries: snow 0.10.0, clatter 2.3.0 (Rust) | ~ protocol layer only; clatter has PQNoise | ~ snow's last release 2025-07-19; clatter is small (45★) | ✔ Apache/MIT; MIT | ~ clatter unaudited [G6] | n/a | Rust |

`why_stdlib/existing_fails` (E3): the Kotlin stdlib has no crypto. Platform
providers lack X25519 on Android 26–32 and PQ on iOS 15–25 and all Android.
**No maintained KMP Noise library was found; the search was not exhaustive
(unverified).**

## 6. Handshake comparison (bytes-on-air)

Assumptions: empty payloads; X25519 32 B; AEAD tag 16 B; ML-KEM-768 pk
1 184 B / ct 1 088 B; X-Wing pk 1 216 B / ct 1 120 B [F1, C2]; tokens
encrypted per the Noise/`hfs` rules [I1, I2]. Counts **exclude** MeshLink
framing, fragment headers, CRCs and L2CAP/ATT headers. The PQNoise pqXX
token order (`-> e; <- ekem, s; -> skem, s; <- skem`) is reconstructed from
the paper's recipe, **not checked against its tables** (± ~16 B/msg). Chunk
columns = number of ATT payloads at ATT_MTU 23 (20 B), at 185 (182 B; a
commonly reported iOS value, **unverified**) and at 512 B (the attribute
limit [J1]; Android 14+ requests MTU 517 [B5]).

| Handshake | Msgs | Bytes per msg | Total B | Every msg ≤512 B? | @20 B | @182 B | @512 B |
|---|---:|---|---:|---|---:|---:|---:|
| Noise NN (unauth) | 2 | 32 / 48 | 80 | yes | 5 | 2 | 2 |
| Noise KK | 2 | 48 / 48 | 96 | yes | 6 | 2 | 2 |
| Noise IK | 2 | 96 / 48 | 144 | yes | 8 | 2 | 2 |
| Noise XX | 3 | 32 / 96 / 64 | 192 | yes | 11 | 3 | 3 |
| Noise KKhfs + ML-KEM-768 | 2 | 1232 / 1152 | 2384 | no | 120 | 14 | 6 |
| Noise IKhfs + ML-KEM-768 | 2 | 1296 / 1152 | 2448 | no | 123 | 15 | 6 |
| Noise XXhfs + ML-KEM-768 | 3 | 1216 / 1200 / 64 | 2480 | no | 125 | 15 | 7 |
| PQNoise pqXX, ML-KEM-768 only (est.) | 4 | 1184 / 2304 / 2320 / 1120 | 6928 | no | 348 | 40 | 16 |
| cairn-style pairing, X-Wing pk+ct only | 2 | 1216 / 1120 | 2336 | no | 117 | 14 | 6 |
| cairn-style resume (S1+S2) | 2 | 57 / 49 | 106 | yes | 6 | 2 | 2 |

Prior-art airtime model (cairn `ble_airtime.py`: encrypted LL, DLE, no
retransmissions; P2): an X-Wing pairing of 2 376 B takes ≈ 13.7 ms on 2M PHY
and 24.3 ms on 1M; a 106 B resume takes ≈ 0.84 ms / 1.37 ms. Not re-run for
MeshLink.

Mesh-specific implications (reasoning, not measured):

- Per-link (hop) sessions: at high density (50–200 peers in range), PQ-a
  costs ~2.4 KB for every link re-handshake. PQ-c costs that once per peer
  pair and 106 B per reconnection, but needs persistent per-pair state (up
  to ~1 000 reachable nodes).
- End-to-end sessions to non-neighbors carry the handshake across every hop,
  multiplying airtime by hop count, so PQ handshake bytes would dominate
  routed traffic. Still open: whether E2E sessions are established pairwise,
  pre-keyed, or one-shot (an HPKE/X-Wing seal per message costs 1 120 B per
  message).
- Classical Noise fits every message in one ATT payload at MTU ≥ 99. PQ
  always needs the fragmentation/reassembly path during the handshake, so its
  bounded-memory and DoS behavior must be specified: the first messages are
  unauthenticated and 1.2 KB+ long.

## 7. Conformance vectors

| Primitive / protocol | Wycheproof [H1] | ACVP [H2] | Other |
|---|---|---|---|
| X25519 | `x25519_test.json` (+asn/jwk/pem) | none (not FIPS-approved) | RFC 7748 |
| Ed25519 | `ed25519_test.json` | `EDDSA-*-1.0` | RFC 8032 |
| AES-GCM | `aes_gcm_test.json` | AES-GCM (**file name not confirmed in listing**) | — |
| ChaCha20-Poly1305 / XChaCha | `chacha20_poly1305_test.json`, `xchacha20_poly1305_test.json` | none | RFC 8439 |
| HKDF / HMAC | `hkdf_sha256/384/512_test.json`, `hmac_sha256/384_test.json` | `KDA-HKDF-Sp800-56Cr1/r2` | RFC 5869 |
| ML-KEM-768 | `mlkem_768_{test,encaps,keygen_seed,semi_expanded_decaps}_test.json` | `ML-KEM-keyGen-FIPS203`, `ML-KEM-encapDecap-FIPS203(-tr1)` | — |
| ML-DSA | `mldsa_{44,65,87}_{sign_seed,sign_noseed,verify}_test.json` | `ML-DSA-{keyGen,sigGen,sigVer}-FIPS204` | — |
| X-Wing | **none** | none | draft-11 Appendix C vectors (the heading carries a TODO) [F1]; Conscrypt `xwing.txt` test resource [D2]; cairn: BoringSSL + CIRCL as two independent oracles, which diverge on low-order X25519 points (P3) |
| Noise XX/IK/KK 25519 | — | — | cacophony (24 XX/IK/KK·25519 vectors) [I4], snow [I5] |
| Noise `hfs`, PQNoise | — | — | **none found**: an S6/S7 gap that would need self-generated cross-implementation vectors |

Wycheproof and ACVP check primitive correctness on edge cases. They prove
neither protocol correctness nor constant-time behavior, as the template
design review also notes.

## 8. Recommendation candidates (not decisions)

1. **`classical` = standard Noise: X25519 + AES-256-GCM or ChaCha20-Poly1305
   - SHA-256, with XX for first contact and IK/KK for pinned peers.** This is
   the strongest S6/S7 position: no new primitive, plus Wycheproof and
   cacophony/snow vectors. The AEAD choice depends on the provider:
   platform-only stacks favor AESGCM (Android 26 lacks platform ChaCha),
   while libsodium favors ChaChaPoly (its AES-GCM is hardware-gated).
2. **`post-quantum` = hybrid X25519 + ML-KEM-768 confidentiality with
   classical authentication**, in one of two shapes for the grilling session:
   - (a) Noise `hfs` with ML-KEM-768: the simplest per-link story, ~2.4 KB
     per handshake, but an unofficial spec with no vectors.
   - (b) cairn-style X-Wing pay-once + 106 B resume + amortized ML-KEM
     refresh: the smallest steady-state airtime, but a novel composition
     that needs a spec, a formal model and expert review.

   PQNoise (pure KEM, ~6.9 KB) and ML-DSA signatures look too heavy for BLE
   at high density.
3. **Provider candidates:**
   - (i) **One bundled C library on both platforms: libsodium 1.0.22**, via
     its own JNI + cinterop bindings (no Rust). It covers both secured
     profiles on Android 26/iOS 15 with one implementation (no
     cross-provider divergence) under ISC. Risks: young KEM code, a single
     maintainer, and no maintained KMP binding with KEM.
   - (ii) **Rust core with aws-lc-rs (or RustCrypto) via UniFFI**, cairn's
     path. It needs the Rust-approval ticket, a hand-built X-Wing combiner
     (or RustCrypto `x-wing` 0.1.x) and the jitter-entropy workaround.
   - (iii) **Platform-native where available + bundled fallback** (CryptoKit
     on iOS, Conscrypt or BouncyCastle on Android). That means two or three
     implementations to keep interoperable, and iOS 15–25 still needs a
     bundled PQ implementation.

   The optional-module rule (map Notes) fits: ship `post-quantum` as a
   separate module only if its provider adds size beyond the classical one.

## 9. Open questions / fog

1. Is raising the `post-quantum` profile's minimum to iOS 26 (CryptoKit
   ML-KEM/X-Wing) acceptable, or must PQ work on iOS 15–25?
2. Is a Rust core acceptable (Rust-approval ticket), or should a C library
   (libsodium) or JVM/Swift-split providers be preferred?
3. Must `post-quantum` authentication be PQ (KEM statics or ML-DSA), or is
   classical authentication + hybrid confidentiality the product claim?
4. Hop-by-hop vs end-to-end encryption for routed messages, broadcast and
   store-and-forward; and does mesh broadcast need origin signatures
   (Ed25519 64 B vs ML-DSA ≥2.4 KB)?
5. Is a novel composition (cairn-style resume, PQ-d) acceptable given S6's
   expert-review requirement, or must v1 use only published patterns?
6. Is there a FIPS "approved algorithms" requirement? If yes,
   ChaCha20-Poly1305 and X25519-as-primary are out (cairn ADR 0001); if not,
   ChaChaPoly stays open.
7. Identity model: are installation identities X25519 static keys (native to
   Noise) or signing keys? Key storage is a separate ticket; the template
   review notes that the iOS 15 Secure Enclave doesn't provide X25519/Ed25519
   keys.
8. Does Android expose `javax.crypto.KEM` at any API level (which would
   enable Conscrypt's raw ML-KEM)? Unverified.
9. Does snow's `hfs` support ML-KEM-768, or only pre-standard Kyber?
   Unverified.

## 10. Limitations

- Sizes for aws-lc-rs, RustCrypto, libcrux and an own libsodium build were
  not measured; the artifact sizes above are Maven downloads (pre-R8, all
  ABIs).
- Audit and constant-time status of libsodium's ML-KEM/X-Wing, RustCrypto
  `ml-kem`/`x-wing`, libcrux and BouncyCastle was not verified.
- No code was built or run and no devices were measured; the airtime numbers
  are cairn's model, not MeshLink measurements.
- PQNoise byte counts are reconstructed, not taken from the paper's tables.
- The Bluetooth Core spec was read from the 6.0 HTML edition. ATT_MTU 517
  comes from the Android documentation; iOS MTU values were not re-verified.
- KMP Noise libraries and KMP crypto wrappers other than cryptography-kotlin
  and the ionspin libsodium wrapper were not searched exhaustively.
