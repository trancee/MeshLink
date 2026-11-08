# What is the diagnostics code catalog?

Type: grilling
Status: resolved

## Question

Decide which diagnostic codes and fields v1 emits (discovery, links, handshakes, routing, holding, limits), their privacy, and the field troubleshooting tooling. How codes are documented is decided (ADR 0006).

## Context

- Codes already promised: Unsecured warning (ADR 0007), density switches (ADR 0008), background-degraded discovery and stale addresses (ADR 0010), handshake DoS drops (ADR 0002), expiry far from local time (ADR 0012).

## Answer

ADR 0020.

- **Codes:** `ML` plus four digits, in ranges by area, never reused. 40 v1 codes covering lifecycle, discovery, links, security, routing, store-and-forward and certificates, limits and platform; every code earlier ADRs promised is included. The list may change until 0.1.0, and is add-only after that.
- **Severities:** Debug (opt-in), Info, Warning and Error.
- **Privacy:** peers appear as a per-start keyed `PeerRef`, or as `LinkRef` before authentication. Events never contain keys, payloads, tags, advertisement identifiers, salts or certificates; platform status numbers are allowed.
- **Flood control:** repeats within 10 s are coalesced; the buffer holds 256 events and overflow emits a summary.
- **Tooling:** `diagnosticsSnapshot()` with counters; `exportDiagnostics()`, a JSON report that is never uploaded; opt-in Logcat or `os_log` output that the lab scripts read.
- **Troubleshooting:** pages per symptom, generated with the code.

ADR 0007 is amended to v7.
