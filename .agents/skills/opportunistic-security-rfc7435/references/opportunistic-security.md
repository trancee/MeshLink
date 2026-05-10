# Opportunistic Security RFC 7435 — Principles, TOFU, and Design Guidelines

<overview>
## What RFC 7435 Defines

Opportunistic Security (OS): Some Protection Most of the Time. IETF Informational (December 2014). Author: Viktor Dukhovni (Two Sigma).

Defines the concept of Opportunistic Security for communications protocols. Core insight: when the choice is "authenticated encryption or nothing," most traffic ends up cleartext. OS uses encryption even when authentication isn't available, and authentication when possible — removing barriers to widespread encryption.

### The Shift in Perspective
**Old view:** Full protection (authenticated + encrypted) is default; anything less is "degraded security" or "fallback."

**OS view:** Without specific knowledge of peer capabilities, the baseline is **no protection**. Anything more is an **improvement**. Cleartext is the default; encryption and authentication are negotiated and applied when available.
</overview>

<terminology>
## Key Definitions (§2)

**Trust on First Use (TOFU):** Accept and store a public key or credential on first contact without authenticating the assertion. Subsequent communication authenticated via the cached key is secure against MiTM — *if* no attack succeeded during the vulnerable first contact. SSH in its common deployment uses TOFU. Synonym: "leap of faith."

**Authenticated, encrypted communication:** Encrypted with at least initiator authenticating the acceptor's identity. Protects against both passive and active attacks.

**Unauthenticated, encrypted communication:** Encrypted without identity verification. Protects against passive monitoring but NOT MiTM attacks.

**Perfect Forward Secrecy (PFS):** Compromise of long-term keys does not compromise past session keys.

**OS protocol:** A protocol that follows the opportunistic approach — uses maximum available protection per peer without requiring universal capability.
</terminology>

<design_principles>
## Design Principles (§3)

### 1. Coexist with Explicit Policy
- Explicit security policies **always preempt** OS
- OS is employed when one might otherwise settle for cleartext
- Security administrators may require authenticated encryption, overriding OS defaults
- Many applications and data types are too sensitive for OS; traditional designs are appropriate there

### 2. Prioritize Communication
- Primary goal: **do not impede communication** while maximizing usable security
- Deployable incrementally — each peer configured independently
- Communication still possible even when some peers support encryption and others don't
- No "your security may be degraded, click OK" dialogs — negotiated protection is as good as can be expected

### 3. Maximize Security Peer by Peer
- Use encryption when mutually supported
- Enforce authentication when an authenticated out-of-band channel provides keys/credentials
- Prefer PFS to protect past recorded communication from future key compromise
- Communication outcomes (per session): authenticated+encrypted, unauthenticated+encrypted, or cleartext

### 4. No Misrepresentation of Security
- Unauthenticated encrypted communication MUST NOT be presented as equivalent to authenticated encrypted communication
- Applies to user-facing UI and application logs of non-interactive applications

### Capability Discovery
Peer capabilities discovered via:
- **Out-of-band:** DANE DNS records, cached TOFU keys, manual configuration (downgrade-resistant)
- **In-band:** Protocol negotiation (vulnerable to MiTM downgrade)

### Authentication is "Expected" When
Determined via a **downgrade-resistant method**:
- Validated DANE DNS records
- Existing TOFU identity information
- Manual configuration

When authentication is expected, OS protocols SHOULD enforce it. When only encryption (not authentication) is possible, authentication checks must soft-fail — don't downgrade to cleartext just because authentication failed.

### Hard-Fail on Broken Advertisements
OS protocols MAY hard-fail with peers whose advertised security capabilities don't actually work. Advertised capabilities must match deployed reality. Broken advertisements look like active attacks.

### Legacy Compatibility
- With unauthenticated encryption, more liberal algorithm/version settings are acceptable
- Compatibility with legacy systems avoids cleartext fallback
- With authenticated sessions, enforce stricter cryptographic parameters
- Broken algorithms only used with peers that can't do better; goal is to transition away
</design_principles>

<smtp_example>
## Example: Opportunistic TLS in SMTP (§4)

SMTP STARTTLS is a real-world OS deployment:
- MTAs negotiate TLS when server announces STARTTLS support
- Initial ESMTP negotiation is NOT cryptographically protected → vulnerable to MiTM downgrade
- Common fallback to cleartext when TLS handshake fails (reasonable: STARTTLS only protects passive attacks)

### Anti-Patterns (NOT consistent with OS)
- Abandoning TLS and falling back to cleartext when server **fails authentication** — encryption is clearly possible, so don't downgrade
- Accepting self-signed certs but rejecting expired certs then falling back to cleartext — needless cleartext when encryption works

### Proper OS-SMTP Design
- "Opportunistic TLS": encrypt when possible, don't require authentication
- "Opportunistic DANE TLS": enforce authenticated encryption when DANE records exist; fall back to Opportunistic TLS otherwise
</smtp_example>

<security_and_operations>
## Security Considerations (§6)

### What OS Provides
- OS does NOT reduce security below what would exist without it — the baseline is cleartext
- Explicit policies always take precedence
- OS is strictly an improvement over "no security when authentication impossible"

### Effect on Attackers
- Large-scale passive monitoring can no longer just collect everything
- Attackers must be more selective and/or mount active attacks
- Active attacks on everyone all the time are much more likely to be noticed

### Limitations
- Peers on the path can mount downgrade attacks to cleartext
- But pervasive downgrades for surveillance are detectable
- Specific detection/mitigation of active attacks without authentication is out of scope

### Operational Considerations (§5)
- Minimize possibility of failure of negotiated security
- Fallback to cleartext only when protection is only against passive attacks AND encryption fails (likely interop problem, not active attack)
- Non-OS explicit policies can be counter-productive if too restrictive — users may disable security entirely
</security_and_operations>
