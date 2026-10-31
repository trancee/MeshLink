# 0003: Installation identity key model

Status: accepted (2026-10-31; ticket "What handshake and crypto suite does each security profile use?"). Version: 1.

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
