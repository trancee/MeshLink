# MeshLink documentation

MeshLink is in its design phase. No library code exists yet, so this page lists what you can read today. The layout and rules for all docs are in [ADR 0006](adr/0006-documentation-architecture.md).

## Understand the design

- [Threat model](explanation/threat-model.md): who MeshLink defends against, and why each security profile promises what it does.

## Look up a contract

| Topic | Source |
|---|---|
| What each security profile guarantees | [Security profile guarantees](reference/security-profile-guarantees.md) |
| Dependencies and tools, with their rationale | [Dependency register](reference/dependencies.md) |
| Terms such as node, peer and security profile | [Glossary](../GLOSSARY.md) |
| Engineering rules | [Constitution](../CONSTITUTION.md) and [Kotlin guidance](../KOTLIN.md) |
| Reporting a vulnerability | [Security policy](../SECURITY.md) |

When two documents disagree, this order decides: Constitution, protocol spec and vectors, public API dump, ADRs, generated reference, then everything else (ADR 0006).

## Why decisions were made

The [ADRs](adr/) record each hard decision with its alternatives and risks:

1. [Threat model and profile guarantees](adr/0001-threat-model-and-profile-guarantees.md)
2. [Noise suites and handshake patterns](adr/0002-noise-suites-and-handshake-patterns.md)
3. [Installation identity key model](adr/0003-installation-identity-key-model.md)
4. [libsodium as the crypto provider](adr/0004-crypto-provider-libsodium.md)
5. [Toolchain and dependency baseline](adr/0005-toolchain-and-dependency-baseline.md)
6. [Documentation architecture](adr/0006-documentation-architecture.md)

The dated [research notes](research/) are historical background. They may describe rejected designs; never treat them as a source of truth.

## For AI agents

Start at [`AGENTS.md`](../AGENTS.md). The agent layer under [`docs/agents/`](agents/) grows with the code: a generated docs index, contract reference and diagnostic index (ADR 0006).
