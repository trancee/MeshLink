# Security Policy

## Supported Versions

MeshLink has not yet reached a stable public release. There are no versioned artifacts currently published to Maven Central.

| Version | Supported |
|---------|-----------|
| pre-release (main branch) | ✅ |

Once stable releases are published, this table will be updated to reflect the supported version range.

## Reporting a Vulnerability

**Please do not report security vulnerabilities via public GitHub issues.**

Use one of these private channels:

- **GitHub Private Vulnerability Reporting** (preferred): [Report a vulnerability](../../security/advisories/new) via the Security tab of this repository.
- **Email**: If you cannot use the above, open a GitHub issue asking for a private contact channel and we will respond with an email address.

Include as much of the following as possible:

- Description of the vulnerability and its potential impact
- Affected component (e.g. Noise handshake, TrustStore, ReplayGuard, wire codec, BLE transport)
- Steps to reproduce or a proof-of-concept
- Your assessment of severity (and your reasoning)
- Whether you have a proposed fix

You will receive an acknowledgement within **72 hours**. We aim to triage and respond with a timeline within **7 days**.

## Scope

MeshLink is a cryptographic networking library. We treat the following as in scope:

- **Noise XX / Noise K handshake implementation** — authentication bypass, key confusion, replay, downgrade
- **ChaCha20-Poly1305 AEAD usage** — nonce reuse, tag truncation, incorrect AAD
- **Ed25519 / X25519 key handling** — scalar clamping, point validation, key material leakage
- **ReplayGuard** — sliding-window bypass, integer overflow, sequence number manipulation
- **TrustStore (TOFU / Prompt)** — key pinning bypass, TOFU fixation attacks
- **Key rotation** — impersonation via rotation announcement forgery
- **Wire codec / InboundValidator** — malformed-message crashes, memory exhaustion, buffer overflows
- **L2CAP frame codec** — stream parsing vulnerabilities
- **libsodium JNI bridge (Android)** — unsafe native interop, memory safety issues
- **SecureStorage implementations** — key material stored or transmitted insecurely

The following are generally **out of scope**:

- Vulnerabilities in the BLE stack of the underlying OS (Android / iOS)
- Issues requiring physical device access that are unrelated to the library's crypto or protocol logic
- Bugs in third-party dependencies that are already publicly disclosed upstream (please report those to the upstream project)
- Build tooling, CI configuration, or benchmark code

## Cryptographic Design

MeshLink's security relies on:

| Primitive | Usage |
|-----------|-------|
| `Noise_XX_25519_ChaChaPoly_SHA256` | Mutual-authenticated BLE link setup |
| `Noise_K_25519_ChaChaPoly_SHA256` | Unicast sealed messages to known peers |
| Ed25519 | Identity signatures and key-rotation announcements |
| X25519 | Diffie-Hellman within Noise handshakes |
| ChaCha20-Poly1305 | AEAD encryption on all transport frames |
| RFC 9147 §4.5.3 sliding-window replay guard | 64-bit sequence number replay protection |
| libsodium 1.0.20 | Underlying crypto primitives (Android JNI + iOS cinterop) |

If you identify a flaw in how any of these are composed or implemented, please report it privately.

## Disclosure Policy

We follow a **coordinated disclosure** model:

1. Reporter submits privately.
2. We acknowledge within 72 hours.
3. We assess, reproduce, and fix. For critical issues we target a patch within **30 days**.
4. We publish a GitHub Security Advisory at or before the patch release.
5. We credit reporters in the advisory unless they prefer to remain anonymous.

We will not take legal action against researchers acting in good faith under this policy.
