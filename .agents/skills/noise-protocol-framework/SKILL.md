---
name: noise-protocol-framework
description: Noise Protocol Framework (Revision 34) reference — crypto protocols based on Diffie-Hellman key agreement. Covers handshake state machine (CipherState, SymmetricState, HandshakeState), message tokens (e, s, ee, es, se, ss, psk), crypto functions (DH, AEAD, HASH, HMAC-HASH, HKDF). All handshake patterns (3 one-way N/K/X, 12 fundamental interactive, 23 deferred variants), naming convention, payload security properties (authentication 0-2, confidentiality 0-5), identity hiding (9 levels). PSK mode, compound protocols (fallback, Noise Pipes), advanced features (Rekey, channel binding, half-duplex). Concrete algorithms (25519/448, ChaChaPoly/AESGCM, SHA256/BLAKE2). Security considerations. Use when implementing Noise handshakes, choosing patterns, understanding security properties, or any Noise Framework question.
---

<essential_principles>

**The Noise Protocol Framework** (Revision 34, 2018-07-11) by Trevor Perrin. A framework for DH-based crypto protocols supporting single-message through multi-message interactive handshakes.

### Core Concept

Two parties exchange **handshake messages** containing DH public keys. Each message processes **tokens** that trigger DH operations, hashing results into shared secret keys. After the handshake, parties use derived keys for encrypted **transport messages**.

### State Machine

Each party maintains:
- **`s, e`** — local static and ephemeral key pairs
- **`rs, re`** — remote static and ephemeral public keys
- **`h`** — handshake hash (hashes all sent/received data, used as AEAD associated data)
- **`ck`** — chaining key (hashes all DH outputs, derives transport keys via Split)
- **`k, n`** — encryption key (32 bytes, may be empty) and 64-bit nonce (new DH → new ck, k; n resets to 0)

### Three-Layer Object Hierarchy

1. **CipherState** — holds `k`, `n`. EncryptWithAd/DecryptWithAd using AEAD. If `k` empty, passes through plaintext. Nonce incremented on each encrypt/decrypt. Max nonce 2^64-1 reserved → error on exhaustion.
2. **SymmetricState** — holds CipherState + `ck` + `h`. MixKey (HKDF into new ck, k), MixHash (hash into h), EncryptAndHash/DecryptAndHash (encrypt with h as AD, then hash ciphertext into h). Split → two CipherStates for transport.
3. **HandshakeState** — holds SymmetricState + DH variables + message patterns. Initialize, WriteMessage, ReadMessage. Final message → Split → (c1, c2) for initiator→responder and responder→initiator transport.

### Token Processing

| Token | WriteMessage | ReadMessage |
|-------|-------------|-------------|
| `"e"` | Generate ephemeral, write pubkey, MixHash(pubkey) | Read DHLEN bytes into re, MixHash(re) |
| `"s"` | EncryptAndHash(s.public_key), append | DecryptAndHash(next DHLEN+16 or DHLEN bytes) into rs |
| `"ee"` | MixKey(DH(e, re)) | MixKey(DH(e, re)) |
| `"es"` | Initiator: MixKey(DH(e, rs)); Responder: MixKey(DH(s, re)) | Same |
| `"se"` | Initiator: MixKey(DH(s, re)); Responder: MixKey(DH(e, rs)) | Same |
| `"ss"` | MixKey(DH(s, rs)) | MixKey(DH(s, rs)) |

After all tokens: EncryptAndHash(payload) appended. If last message pattern → Split() returns (c1, c2).

### Message Format

All messages ≤ 65535 bytes. No type or length fields in Noise itself. Transport message = AEAD ciphertext (payload + 16-byte auth tag). Handshake message = DH public keys + payload. Static keys and payloads are cleartext before first DH, AEAD ciphertext after.

### Protocol Naming

`Noise_<pattern>_<DH>_<cipher>_<hash>` — e.g. `Noise_XX_25519_ChaChaPoly_SHA256`. Max 255 bytes ASCII. Pattern modifiers appended with `+` separator (e.g. `XXfallback+psk0`).

### Critical Security Rules

- **Ephemeral keys MUST be fresh** — reuse causes catastrophic key reuse
- **Nonces MUST NOT wrap** — max 2^64-1 transport messages per direction
- **Static keys: single hash algorithm** — don't reuse across hash functions or outside Noise
- **AESGCM volume limit: 2^56 bytes** per key (birthday bound on AES blocks)
- **Public keys are NOT secrets** — don't assume knowledge of a pre-message pubkey authenticates; use PSK instead
- **Channel binding: use `h`** (handshake hash), not `ck` — h is unique per session even with invalid DH values

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Crypto function signatures (DH GENERATE_KEYPAIR/DH/DHLEN, cipher ENCRYPT/DECRYPT/REKEY, hash HASH/HASHLEN/BLOCKLEN/HMAC-HASH/HKDF), processing rules (CipherState InitializeKey/HasKey/SetNonce/EncryptWithAd/DecryptWithAd/Rekey, SymmetricState InitializeSymmetric/MixKey/MixHash/MixKeyAndHash/GetHandshakeHash/EncryptAndHash/DecryptAndHash/Split, HandshakeState Initialize/WriteMessage/ReadMessage), prologue | `references/processing-rules.md` |
| All handshake patterns (one-way N/K/X, fundamental NN/NK/NX/KN/KK/KX/XN/XK/XX/IN/IK/IX, pattern naming convention, pre-messages, validity rules, Alice/Bob vs initiator/responder, deferred patterns with numeral-1 naming), payload security properties (source 0-2, destination 0-5, full table for all fundamental patterns), identity hiding properties (levels 0-9, full table) | `references/handshake-patterns.md` |
| PSK mode (MixKeyAndHash, psk token, validity rule, psk0/psk1/psk2+ modifiers, recommended PSK patterns for all one-way and interactive base patterns), compound protocols (fallback modifier, zero-RTT patterns, Noise Pipes XX/IK/XXfallback, handshake indistinguishability), advanced features (dummy keys, channel binding, Rekey, out-of-order transport, half-duplex), concrete algorithms (25519/448 DH, ChaChaPoly/AESGCM cipher, SHA256/SHA512/BLAKE2s/BLAKE2b hash), application responsibilities, security considerations, design rationales | `references/extensions-and-security.md` |

</routing>

<reference_index>

**processing-rules.md** — crypto function signatures (§4: DH with GENERATE_KEYPAIR/DH/DHLEN≥32, Gap-DH hardness, invalid pubkey handling; cipher ENCRYPT/DECRYPT with 32-byte key + 8-byte nonce + AD → ciphertext=plaintext+16, REKEY default ENCRYPT at maxnonce with zeros; hash HASH/HASHLEN 32 or 64/BLOCKLEN, HMAC-HASH per RFC 2104, HKDF 2 or 3 outputs with chaining_key as salt and zero-length info per RFC 5869), processing objects (§5: CipherState k/n with InitializeKey/HasKey/SetNonce/EncryptWithAd n++/DecryptWithAd n++ only if auth passes/Rekey, max nonce 2^64-1 reserved; SymmetricState ck/h with InitializeSymmetric protocol_name padding or hashing to HASHLEN then ck=h, MixKey via HKDF-2 truncate if HASHLEN=64, MixHash h=HASH(h||data), MixKeyAndHash via HKDF-3 for PSK, GetHandshakeHash returns h, EncryptAndHash/DecryptAndHash using h as AD then MixHash ciphertext, Split via HKDF-2 on zerolen → c1 c2; HandshakeState s/e/rs/re/initiator/message_patterns with Initialize deriving protocol_name and MixHash prologue and pre-message pubkeys, WriteMessage/ReadMessage token processing for e/s/ee/es/se/ss with role-dependent DH swapping, final message returns Split pair), prologue (§6: hashed into h for identical-view confirmation, not mixed into encryption keys)

**handshake-patterns.md** — pattern basics (§7.1: message pattern = token sequence, pre-message pattern = e/s/e,s/empty, handshake pattern = initiator pre-message + responder pre-message + message sequence, alternating direction starting with initiator), one-way patterns (§7.4: N no sender static/K known/X transmitted, N=DH pubkey encryption, K/X add sender auth), 12 fundamental interactive patterns (§7.5: two-char naming, first=initiator N/K/X/I second=responder N/K/X, full pattern listings for NN/NK/NX/KN/KK/KX/XN/XK/XX/IN/IK/IX, XX most generically useful, K-ending patterns enable zero-RTT, all allow half-RTT, K/I-starting responder has weak forward secrecy until first transport from initiator), validity rules (§7.3: only DH with possessed keys, no duplicate e/s sends, no duplicate DH operations, after se/ss must also have ee/es before encrypting — prevents catastrophic key reuse and ensures ephemeral contribution), Alice/Bob notation (§7.2: canonical=Alice-initiated, Bob-initiated reverses arrows and swaps es↔se), deferred patterns (§7.6: numeral 1 after first/second char defers auth DH, 23 total deferred variants, motivation: avoid 0-RTT data/improve identity hiding/enable future signature or KEM replacement), payload security properties (§7.7: source 0=no auth, 1=KCI-vulnerable ss-based, 2=KCI-resistant es/se-based; destination 0=cleartext, 1=ephemeral-only forward secrecy, 2=static-only no forward secrecy replay-vulnerable, 3=weak forward secrecy unverified ephemeral, 4=weak if sender compromised, 5=strong forward secrecy; full table for all one-way and fundamental patterns), identity hiding (§7.8: 9 levels from cleartext to encrypted-with-FS-to-authenticated-party, full table including NK1/XK1/IK1 deferred variants with different properties)

**extensions-and-security.md** — PSK mode (§9: 32-byte shared secret, psk token calls MixKeyAndHash, in PSK handshakes all e tokens followed by MixKey(e.public_key), validity rule: no encrypted data after psk unless ephemeral already sent, psk0/psk1/psk2+ modifiers with placement rules, recommended PSK patterns for all one-way and interactive bases, combinable e.g. XXpsk0+psk3), compound protocols (§10: rationale for protocol switching, fallback modifier converts Alice-initiated to Bob-initiated with first message as pre-message, zero-RTT structure full/zero-RTT/switch, Noise Pipes = XX full + IK zero-RTT + XXfallback switch, handshake indistinguishability via padding and trial decryption and Elligator for ephemeral keys), advanced features (§11: dummy keys for optional authentication simulating NX via XX with dummy static, dummy PSKs with all-zeros, channel binding via GetHandshakeHash for post-handshake signatures/passwords, Rekey one-way function on k for forward secrecy without resetting n, out-of-order transport via SetNonce with replay tracking, half-duplex using only first CipherState from Split with extreme caution against same-nonce catastrophe), concrete algorithms (§12: 25519 DH DHLEN=32 all-zeros on invalid discourage error, 448 DH DHLEN=56 same policy, ChaChaPoly 96-bit nonce = 32-bit zeros + LE n, AESGCM 96-bit nonce = 32-bit zeros + BE n + 128-bit tag, SHA256 HASHLEN=32 BLOCKLEN=64, SHA512 HASHLEN=64 BLOCKLEN=128, BLAKE2s HASHLEN=32 BLOCKLEN=64, BLAKE2b HASHLEN=64 BLOCKLEN=128), application responsibilities (§13: crypto function choice 25519 recommended with 256 or 512 bit hash, extensible payloads, padding for length hiding, session termination signals, 16-bit BE length fields, negotiation data with rollback risk), security considerations (§14: authentication via certs/pinning/continuity, rollback if negotiation not in prologue, static key single hash only, PSK single hash only, ephemeral reuse catastrophic, public keys not secrets, channel binding use h not ck, nonce increment no wrap, protocol name uniqueness, PSK 256-bit entropy, AESGCM 2^56 byte limit, hash collision transcript attacks, implementation fingerprinting), design rationales (§15: 256-bit keys for quantum margin, 64-bit nonces for Salsa20 compat, 128-bit auth tag fixed, ciphertext indistinguishable from random, rekey via ENCRYPT at maxnonce efficient for AES/ChaCha, rekey doesn't reset n to bound total encryptions and avoid key cycles, AESGCM limit from birthday on 2^52 blocks, BE nonces for AESGCM / LE for ChaCha matching internal counters, HKDF for MixKey well-analyzed, HMAC uniform across all hash functions, MixHash more efficient than MixKey for non-secret data, h hashes ciphertext not plaintext for non-secret channel binding and malleability resistance)

</reference_index>
