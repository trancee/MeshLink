# 0003: Installation identity key model

Status: accepted (2026-10-31; ticket "What handshake and crypto suite does each security profile use?"). Version: 6 (amended 2026-11-02, ADR 0012; 2026-11-03, ADR 0014; 2026-11-08, ADR 0018; 2026-11-13, errata; 2026-11-15, identity storage).

## Context

An installation identity must authenticate link handshakes (Noise, X25519 static keys), end-to-end sessions, and mesh broadcast signatures (Ed25519), as required by [ADR 0001](0001-threat-model-and-profile-guarantees.md) and [ADR 0002](0002-noise-suites-and-handshake-patterns.md). In `post-quantum` it also needs a static X-Wing key for sealing first and held messages. These must all resolve to one identity, without reusing one key across algorithms.

## Decision

- The installation identity is one Ed25519 key pair. Its fingerprint is a hash of the Ed25519 public key.
- The identity key signs an identity certificate that binds the node's X25519 Noise static key and, in `post-quantum`, its X-Wing static key, with a validity period.
- The certificate travels inside the encrypted XX payload (+64 B signature, plus the public keys it binds) and is available to senders for end-to-end IK.
- Broadcast signatures and handshakes therefore resolve to the same identity.
- Static keys rotate under the same identity when their certificate expires. The rotation period is set by the store-and-forward design, so that held messages stay readable only until their sealing key expires.
- The Ed25519 identity changes only through an identity reset, which peers see as a key change (never accepted silently).
- Keys at rest are protected by the platform keystore (ADR 0001).

## Alternatives

- One key used as both Ed25519 and X25519 (converted): rejected to avoid cross-protocol key reuse.
- XEdDSA (signing with an X25519 key): rejected; non-standard for Ed25519 verifiers and adds a construction under rule S6.
- Two unbound keys identified jointly: rejected; an attacker could pair a victim's signing key with their own DH key.

## Risks

- The certificate format is new and needs its own conformance vectors (ADR 0002).
- Expired certificates on long-offline nodes can delay contact until refresh. The store-and-forward design must cover this case.

## Migration

None: greenfield.

## Amendments

2026-11-02, wire format (ADR 0012):

- **NodeId:** the full 32 B BLAKE2b-256 hash of the Ed25519 public key, with a domain-separation string fixed in the spec. Every profile has an installation identity key; in `unsecured` the NodeId is claimed, not proven.
- **Certificate encoding:** a fixed-layout binary record (version, profile, Ed25519 key, X25519 key, X-Wing key in post-quantum only, not-before and not-after as 4 B Unix seconds, then a 64 B Ed25519 signature over every preceding byte with a domain-separation prefix). No CBOR, ASN.1 or X.509.

2026-11-03, store-and-forward ([ADR 0014](0014-store-and-forward.md)):

- **Rotation period:** a certificate is valid for 24 h, and a new one is issued every 12 h. Its static private keys are deleted at its not-after plus 1 h plus 30 s.
- **Expired certificates (risk above):** a sender never seals to an expired certificate; nodes attach their current certificate to `IK` replies and receipts when the sender's copy is past half its validity.

2026-11-08, certificate acquisition ([ADR 0018](0018-certificate-acquisition.md)):

- **Certificate distribution:** besides links, `IK` first messages and piggybacked refreshes, senders get certificates through a salted, anonymous lookup. D or any node caching a valid certificate answers, sealed to the requester's ephemeral key. Expired certificates trigger a lookup instead of `Rejected(PeerKeyExpired)`.

2026-11-13, errata (0.1.0 backlog review):

- **Expired certificates (2026-11-03 amendment):** no longer names `Rejected(PeerKeyExpired)`, which ADR 0018 removed. A unicast to a node without a valid certificate stays `Queued` while MeshLink looks one up, then is sealed or becomes `Expired` (ADR 0007 and ADR 0013 amendments for ADR 0018).

2026-11-15, identity storage (0.1.0 backlog ticket "Keep the installation identity in the platform keystore"):

- **What is stored (amends decision 7):** the platform keystore wraps the 32 B Ed25519 seed rather than holding the key, because Android Keystore and the Secure Enclave cannot hold an Ed25519 key on every supported OS version, and libsodium needs the seed to sign (owner decision, 2026-11-13).
- **Android (amends decision 7):** a non-exportable AES-256-GCM key in Android Keystore, alias `ch.trancee.meshlink.identity`, with no user authentication, seals the seed. MeshLink asks for StrongBox first and falls back to the default Keystore when the device has none. The sealed blob is `0x01`, the 12 B nonce, then the 48 B ciphertext and tag, authenticated with the associated data `ch.trancee.meshlink.identity.v1`. It is written atomically to `ch.trancee.meshlink.identity` in `Context.getNoBackupFilesDir()`, which Android excludes from backups.
- **iOS (amends decision 7):** a generic-password Keychain item, service `ch.trancee.meshlink`, account `identity`, with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and `kSecAttrSynchronizable` false. Background Bluetooth can read it after the first unlock, and it never syncs or migrates to another device: Apple documents that such items are absent after restoring a backup of a different device.
- **Unreadable means blocked (Constitution S4 and S5):** MeshLink creates an identity only when nothing is stored. A blob of the wrong size or version, one that fails authentication, one whose Keystore key is missing, an item that is not a 32 B seed, and any read error that may be transient leave the stored data untouched and make `start()` report `Blocked(IdentityUnavailable)`. So does a failure to create or save a new identity. Every later `start()` reads again. Only the explicit `resetIdentity()` (ADR 0007 amendment of 2026-11-15) deletes the stored identity, so that the next `start()` creates a new one, which peers see as a key change.
- **Wiping (Constitution S1):** MeshLink zeroes the seed bytes it reads, and the `CFMutableData` it hands to `SecItemAdd`, once they are used. The immutable `CFData` that `SecItemCopyMatching` returns cannot be zeroed; Core Foundation frees it. The decrypted Ed25519 key pair stays in memory while the process runs.
- **Simulator and tests:** the `iosSimulatorArm64` build and the testing port keep the identity in memory, because a simulator test host has no Keychain (`errSecNotAvailable`).
