---
name: opportunistic-security-rfc7435
description: Complete reference for RFC 7435 — Opportunistic Security (OS), defining the design philosophy of "some protection most of the time" for communications protocols. Covers the key insight that when protocols demand all-or-nothing authenticated encryption, most traffic ends up cleartext. OS uses encryption even without authentication and authentication when possible. Includes the four design principles (coexist with explicit policy — OS never preempts; prioritize communication — don't impede, deploy incrementally; maximize security peer by peer — encrypt when mutual, authenticate when out-of-band channel available, prefer PFS; no misrepresentation — unauthenticated encryption must not be shown as equivalent to authenticated). Defines TOFU (Trust on First Use — accept and cache public key on first contact, secure against future MiTM if first contact wasn't compromised, SSH model), capability discovery (out-of-band via DANE/TOFU/config is downgrade-resistant; in-band negotiation is vulnerable to MiTM), when authentication is "expected" (validated DANE, existing TOFU identity, manual config), hard-fail on broken advertisements, legacy compatibility with liberal algorithms for unauthenticated sessions. Includes SMTP STARTTLS example (anti-patterns: cleartext fallback when auth fails but encryption works; proper: Opportunistic TLS + Opportunistic DANE TLS). Security analysis: passive monitoring defeated, active attacks must be mounted per-target and are detectable at scale. Use when designing security negotiation, implementing TOFU trust models, building connection upgrade logic, deciding authentication vs encryption tradeoffs, or any question about RFC 7435 and opportunistic security principles.
---

<essential_principles>

**RFC 7435** defines Opportunistic Security — use cleartext as baseline, negotiate encryption and authentication when available. IETF Informational (December 2014).

### What a Protocol Designer Must Know

**Core insight:** "All or nothing" security leads to mostly nothing. OS starts from cleartext baseline and improves per peer.

**Three possible outcomes per session:** (1) authenticated + encrypted, (2) unauthenticated + encrypted, (3) cleartext. Each is the best available for that peer.

**Four principles:**
1. **Coexist with explicit policy** — OS never preempts; explicit security requirements override
2. **Prioritize communication** — don't impede; incremental deployment; no scary dialogs
3. **Maximize security per peer** — encrypt when mutual; authenticate when downgrade-resistant evidence exists (DANE, TOFU, config); prefer PFS
4. **No misrepresentation** — unauthenticated encryption ≠ authenticated encryption in UI or logs

**TOFU:** Accept key on first contact, cache it, authenticate future sessions. Vulnerable to first-contact MiTM only. SSH model. Makes key rotation hard to distinguish from attack.

**Downgrade-resistant auth sources:** DANE DNS records, cached TOFU keys, manual configuration. In-band negotiation is NOT downgrade-resistant.

**Anti-pattern:** Falling back to cleartext when authentication fails but encryption is possible. OS: keep encryption, soft-fail authentication.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Everything (OS definition and perspective shift, TOFU definition and SSH model, authenticated vs unauthenticated vs cleartext communication, PFS, four design principles with rationale, capability discovery out-of-band vs in-band, when authentication is "expected" via downgrade-resistant methods, hard-fail on broken advertisements, legacy algorithm compatibility, SMTP STARTTLS example with anti-patterns and proper OS-DANE design, security analysis of passive monitoring defeat and active attack detectability, operational fallback guidance) | `references/opportunistic-security.md` |

</routing>

<reference_index>

**opportunistic-security.md** — overview (Opportunistic Security: some protection most of the time, IETF December 2014, Dukhovni Two Sigma, core insight: all-or-nothing security→mostly cleartext; OS: cleartext baseline with encryption+auth negotiated when available), perspective shift §1.2 (old: full protection default anything less is degraded; new: no protection default anything more is improvement, three outcomes per session: authenticated+encrypted or unauthenticated+encrypted or cleartext, no degradation dialogs, OS never preempts explicit policy), terminology §2 (TOFU: accept+store public key on first contact without authenticating, future sessions secure if first contact not compromised, SSH model, synonym "leap of faith"; authenticated encrypted: at least initiator authenticates acceptor, protects passive+active; unauthenticated encrypted: no identity verification, passive only; PFS: past sessions safe after long-term key compromise), design principles §3 (1. coexist with explicit policy — OS never displaces, administrators may require authenticated encryption; 2. prioritize communication — primary goal don't impede, incremental deployment per peer, no scary dialogs; 3. maximize security per peer — encrypt when mutual, authenticate when out-of-band channel available, prefer PFS, three-tier outcome; 4. no misrepresentation — unauthenticated encrypted must not be shown as authenticated in UI or logs), capability discovery (out-of-band: DANE records cached TOFU keys manual config — downgrade-resistant; in-band: protocol negotiation — vulnerable to MiTM downgrade, encryption without auth only mitigates passive so downgrade risk is consistent), authentication "expected" when (validated DANE, existing TOFU identity, manual config — all downgrade-resistant), hard-fail on broken advertisements (advertised capabilities must match reality, broken looks like active attack, unreliable capabilities treated as absent), legacy compatibility (liberal algorithms for unauthenticated sessions, stricter for authenticated, broken algorithms only with peers that can't do better, goal to transition away), SMTP STARTTLS example §4 (STARTTLS negotiation not cryptographically protected→MiTM downgrade possible, common cleartext fallback on TLS failure is reasonable for passive-only protection, anti-patterns: cleartext fallback when auth fails but encryption works or rejecting expired certs→cleartext, proper: Opportunistic TLS encrypt when possible + Opportunistic DANE TLS enforce auth when DANE records exist), operational §5 (minimize failure of negotiated security, fallback only when passive-only protection and encryption fails likely interop not attack, restrictive non-OS policies can cause users to disable security entirely), security §6 (OS does not reduce security below status quo, explicit policies preempt, passive monitoring defeated, attackers must mount active per-target attacks, pervasive downgrades detectable, specific active attack mitigation out of scope)

</reference_index>
