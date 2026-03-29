# Security Policy

## Security Features

MeshLink provides:

- **Two-layer Noise encryption** — Noise XX (hop-by-hop) + Noise K (end-to-end)
- **Ed25519 identity pinning** — TOFI (Trust-On-First-Discover) with strict and soft re-pin modes
- **Replay protection** — Per-peer replay guards with persisted counters and 64-counter sliding window
- **Sybil mitigation** — Handshake rate limiting (1/sec per unknown peer) + per-neighbor routing table cap (30%)
- **Platform CSPRNG** — All randomness sourced from platform-native secure random (`SecureRandom`, `SecRandomCopyBytes`)

See [docs/threat-model.md](docs/threat-model.md) for the full threat analysis.

## Supported Versions

| Version | Supported |
| --- | --- |
| Latest `main` branch | ✅ |
| Latest tagged release | ✅ |
| Older releases | ❌ |

## Reporting a Vulnerability

Please **do not** report security vulnerabilities in public GitHub issues.

Use GitHub's private vulnerability reporting flow:
- Go to the repository **Security** tab.
- Click **Report a vulnerability**.

Include as much detail as possible:
- affected module(s) (`android/`, `ios/`, test harness, etc.)
- impacted Noise pattern(s) or protocol flow
- reproduction steps or proof-of-concept
- expected vs. actual behavior
- suggested fix (if available)

## Disclosure Process

After receiving a report, maintainers will:

1. **Triage** severity within 48 hours
2. **Develop a fix** and validate against test vectors
3. **Coordinate a release** once users can update safely
4. **Credit the reporter** in release notes (unless anonymity requested)

Target response times by severity:

| Severity | Examples | Target |
|----------|---------|--------|
| Critical | Key compromise, encryption bypass, RCE | < 7 days |
| High | DoS via protocol abuse, replay bypass | < 14 days |
| Medium | Information leak, performance degradation | < 30 days |
| Low | Protocol deviation, edge-case misbehavior | Next release |

## Dependency Policy

Dependencies are pinned to patched versions in the root `build.gradle.kts`
(see CVE comments in the file). Security advisories are monitored via GitHub
Dependabot alerts.
