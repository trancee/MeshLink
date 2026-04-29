# MeshLink Export Control Documentation

Version: 0.1.0  
Date: 2026-04-29

This document provides a factual inventory of the cryptographic algorithms used by MeshLink,
their classification under the US Export Administration Regulations (EAR), and an analysis of
the open-source exemption under TSU §740.13(e). It is intended as input for an integrator's
legal and compliance team.

---

## Cryptographic Algorithm Inventory

| Algorithm | Key Size | Purpose | Standard Reference |
|-----------|----------|---------|-------------------|
| X25519 | 256-bit | Ephemeral Diffie-Hellman key exchange (Noise XX handshake) | RFC 7748 |
| Ed25519 | 256-bit | Digital signatures (identity authentication, broadcast signing) | RFC 8032 |
| ChaCha20-Poly1305 | 256-bit | Authenticated encryption with associated data (AEAD, payload confidentiality + integrity) | RFC 8439 |
| HMAC-SHA-256 | 256-bit | Message authentication code; pseudonym rotation keying | RFC 2104, FIPS 198-1 |
| HKDF-SHA-256 | 256-bit | Key derivation (session keys from shared secrets) | RFC 5869 |

All cryptographic primitives are provided by **libsodium** (via JNI on JVM/Android, cinterop on
iOS/macOS). MeshLink does not implement custom cryptographic algorithms.

---

## ECCN 5D002 Classification

MeshLink is classified under **ECCN 5D002** ("Information Security" — software that uses or
performs cryptographic functionality) of the Commerce Control List (CCL), Supplement No. 1 to
Part 774 of the EAR.

**Rationale:** The library employs symmetric encryption (ChaCha20-Poly1305, 256-bit key) and
asymmetric cryptography (X25519, Ed25519, 256-bit keys) with key lengths exceeding the
56-bit symmetric / 512-bit asymmetric de minimis thresholds defined in ECCN 5A002 Note 3.

The encryption is integral to the library's mesh-networking function — it cannot operate in a
reduced-security mode without cryptographic protections.

---

## TSU §740.13(e) Open-Source Exemption Analysis

License Exception TSU (Technology and Software Unrestricted) at EAR §740.13(e) provides an
exemption for publicly available encryption source code, subject to the following conditions:

### Eligibility Criteria

| Criterion | MeshLink Status |
|-----------|----------------|
| Source code is publicly available (e.g. on a public repository) | ✅ Published on GitHub under an open-source license |
| No restrictions on further distribution of the source code | ✅ License permits redistribution without restriction |
| Not subject to an express agreement for the payment of a licensing fee or royalty | ✅ No commercial licensing gates on the source code |
| Notification filed with BIS and NSA per §740.13(e) | ⚠️ Required before first public release |

### Notification Requirements

Before making the source code publicly available, the following notification must be sent:

1. **Email to BIS:** `crypt@bis.doc.gov`  
2. **Email to NSA:** `enc@nsa.gov`  
3. **Content:** Internet URL where the source code is publicly accessible, or a copy of the
   publicly available source code.

> **Note:** This notification is a one-time filing per product/version. It is distinct from
> a formal SNAP-R classification request or an Encryption Registration Notification (ERN).

### Ongoing Obligations

- If the repository URL changes, a new notification should be filed.
- TSU does not cover object code (compiled binaries) distributed to embargoed destinations
  (Country Group E:1/E:2) — integrators distributing pre-built binaries must verify their
  own compliance obligations.

---

## Disclaimer

This document is **factual technical documentation** describing the cryptographic capabilities
of the MeshLink library. It is provided solely as reference material for an integrator's legal
and compliance team.

**This document does not constitute legal advice.** Export control regulations are complex,
jurisdiction-specific, and subject to change. Integrators should consult qualified export
control counsel to determine their specific obligations under the EAR, EU Dual-Use Regulation
(EC 428/2009), Wassenaar Arrangement, or other applicable export control regimes before
distributing MeshLink or products incorporating MeshLink.

---

## References

- [EAR Part 740 — License Exceptions](https://www.ecfr.gov/current/title-15/subtitle-B/chapter-VII/subchapter-C/part-740)
- [EAR Part 774, Supplement 1 — Commerce Control List](https://www.ecfr.gov/current/title-15/subtitle-B/chapter-VII/subchapter-C/part-774)
- [BIS Encryption FAQ](https://www.bis.doc.gov/index.php/policy-guidance/encryption)
- [RFC 7748 — X25519](https://datatracker.ietf.org/doc/html/rfc7748)
- [RFC 8032 — Ed25519](https://datatracker.ietf.org/doc/html/rfc8032)
- [RFC 8439 — ChaCha20-Poly1305](https://datatracker.ietf.org/doc/html/rfc8439)
