# What documentation architecture serves humans and AI agents?

Type: grilling
Status: open
Blocked by: 11

## Question

Decide Diátaxis layout, generated reference (Dokka, protocol, config, diagnostics), AI-optimized layer (AGENTS.md, scoped guidance, llms.txt, machine-readable specs), example checking (O5), and docs gates (O6).

## Scope additions (from decisions)

- From the [toolchain baseline](11-toolchain-baseline.md) (ADR 0005): Dokka 2.2.0 generates the API reference. `docs/reference/dependencies.md` is a reference page that must stay in sync with `gradle/libs.versions.toml` through a CI check; decide where that check and the other docs gates live.
