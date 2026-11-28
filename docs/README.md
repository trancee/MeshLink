# MeshLink documentation

MeshLink is being built toward its first release, 0.1.0. This page lists the documentation by what you want to do. The layout and rules for all docs are in [ADR 0006](adr/0006-documentation-architecture.md).

## Learn

- [Get started](tutorials/get-started.md): build an app whose two phones find each other and exchange an encrypted message.

## Do a task

- [Troubleshoot peers that aren't found](how-to/troubleshoot-peers-not-found.md)
- [Troubleshoot messages that aren't delivered](how-to/troubleshoot-messages-not-delivered.md), or arrive late
- [Troubleshoot high battery use](how-to/troubleshoot-high-battery-use.md)
- [Set up the self-hosted macOS CI runner](how-to/set-up-self-hosted-runner.md) and [maintain the Fedora runners](how-to/maintain-fedora-runners.md)

## Look up a contract

| Topic | Source |
|---|---|
| Bytes on the wire, derivations, limits and conformance vectors (protocol 1, Draft) | [Protocol spec](spec/README.md) |
| Every configuration setting, its type, default and allowed values | [Configuration](reference/configuration.md) (generated) |
| What each preset sets | [Presets](reference/presets.md) (generated) |
| What each diagnostic code means, its likely cause and fix | [Diagnostics](reference/diagnostics.md) (generated; [JSON for agents](agents/diagnostics.json)) |
| What each security profile guarantees | [Security profile guarantees](reference/security-profile-guarantees.md) |
| What relays and neighbors learn from routing messages | [What each observer learns](reference/observer-exposure.md) |
| Swift overlay: the `MeshNode` protocol, its Swift value types and the `MeshLinkTesting` fake | [`swift/`](../swift/) ([ADR 0007](adr/0007-public-api-shape.md) amendment of 2026-11-28) |
| Kotlin API of the latest release | `https://trancee.github.io/MeshLink/api/`, from the first release on; locally `./gradlew dokkaGeneratePublicationHtml` writes it to `build/dokka/html/` |
| Dependencies and tools, with their rationale | [Dependency register](reference/dependencies.md) |
| Terms such as node, peer and security profile | [Glossary](../GLOSSARY.md) |
| Engineering rules | [Constitution](../CONSTITUTION.md) and [Kotlin guidance](../KOTLIN.md) |
| Reporting a vulnerability | [Security policy](../SECURITY.md) |

When two documents disagree, this order decides: Constitution, protocol spec and vectors, public API dump, ADRs, generated reference, then everything else (ADR 0006).

## Understand the design

- [Threat model](explanation/threat-model.md): who MeshLink defends against, and why each security profile promises what it does.
- [Finding iPhones in the background](explanation/background-iphone-discovery.md): how Android finds a backgrounded iPhone through Apple's overflow area, and what that bit can and cannot tell.

### Why decisions were made

The [ADR index](adr/README.md) lists every architecture decision record with its status and version. Each ADR records a hard decision with its alternatives and risks.

The dated [research notes](research/) are historical background. They may describe rejected designs; never treat them as a source of truth.

## For AI agents

Start at [`AGENTS.md`](../AGENTS.md). Before radio, device-lab or proof work, read the [lessons](agents/lessons.md). The generated agent layer under [`docs/agents/`](agents/) (ADR 0006):

- [Documentation index](agents/index.md): every document with its type, authority and status.
- [Contract reference](agents/contract.md): one line per public declaration, from the klib ABI dumps.
- [Diagnostic index](agents/diagnostics.json): every diagnostic code with its severity, cause and fix, as JSON.

`./gradlew updateReference` rewrites them; `./gradlew check` fails when one is stale.
