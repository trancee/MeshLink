# What documentation architecture serves humans and AI agents?

Type: grilling
Status: resolved
Blocked by: 11

## Question

Decide Diátaxis layout, generated reference (Dokka, protocol, config, diagnostics), AI-optimized layer (AGENTS.md, scoped guidance, llms.txt, machine-readable specs), example checking (O5), and docs gates (O6).

## Scope additions (from decisions)

- From the [toolchain baseline](11-toolchain-baseline.md) (ADR 0005): Dokka 2.2.0 generates the API reference. `docs/reference/dependencies.md` is a reference page that must stay in sync with `gradle/libs.versions.toml` through a CI check; decide where that check and the other docs gates live.

## Answer

Recorded in [ADR 0006](../../../docs/adr/0006-documentation-architecture.md); every recommendation from both grilling rounds was accepted.

- **Layout:** Diátaxis folders plus `docs/spec/` (normative, per layer), `docs/adr/`, `docs/research/` (historical) and `docs/agents/`. `docs/README.md` is task-oriented.
- **Authority:** Constitution > spec and vectors > API dump > ADRs (rationale only) > generated reference > the rest.
- **One source per fact:**
  - protocol data in spec files, checked by Kotlin tests;
  - API facts (config, diagnostics, errors) in Kotlin with KDoc;
  - generated reference and agent JSON, CI-diffed;
  - no YAML catalogs.
- **Agents:**
  - `AGENTS.md` is the only agent file;
  - generated `docs/agents/index.md`;
  - ABI dump plus a one-line summary;
  - diagnostic index in Markdown and JSON;
  - `lessons.md` arrives with the bake-off;
  - `llms.txt` only with a published site.
- **Examples:** Kotlin and Swift side by side, from compiled tests (`@sample` in `commonTest`, XCTest in the SwiftPM consumer), with a region-sync check. No knit, DocC or Snippets.
- **Troubleshooting:** symptom-grouped how-to pages on stable diagnostic codes.
- **Spec:** BCP 14 Markdown plus Wycheproof-style JSON vectors with a JSON Schema.
- **Publishing:** Dokka HTML on Pages; no site generator until release.
- **Docs CI:** lychee, markdownlint-cli2, typos (US English); Vale deferred. Plus `checkDependencyRegister`.
- **ADRs:** flat and numbered; superseded ADRs are kept; generated index.
- **Applied now:** `docs/README.md`, a "CI tools" register section, and US spelling across docs and tracker. `CONSTITUTION.md` keeps the British spelling of "labeled" in T9 until its next amendment.
