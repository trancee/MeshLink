# 0006: Documentation architecture for humans and agents

Status: accepted (2026-11-01; ticket "What documentation architecture serves humans and AI agents?"). Version: 3 (amended 2026-11-09, ADR headers and pre-push privacy gate; 2026-11-13, docs site for 0.1.0).

## Context

- **Constitution:** O4 (Diátaxis), O5 (examples must compile), O6 (docs checks), O8 (a human layer and an agent layer from one source) and C10 (the spec is the authority) govern MeshLink's docs. MeshLink ships for Kotlin (Android) and Swift (iOS) callers.
- **MeshLink-template** (studied only): its root `SPEC.md`, YAML catalogs, ADR tables and reference pages repeated the same values and drifted apart. Its spec validator failed, and its catalog check only tested that two files mentioned a name (`docs/explanation/template-design-review.md` there).
- **cairn:** a task-oriented `docs/README.md`, the protocol spec in `docs/spec/` as the authority, and `docs/agents/lessons.md` for agents.

Facts verified 2026-11-01 from primary sources (release pages, project docs):

- **`llms.txt`** (llmstxt.org) is an independent proposal, now v2 (2026-08-10), which adds `.md` page mirrors. Mintlify generates it, Lighthouse audits it, and OpenAI, Anthropic and Gemini publish one. No standards body has adopted it.
- **`AGENTS.md`** (agents.md) is stewarded by the Agentic AI Foundation (Linux Foundation). GitHub Copilot, Codex, Cursor and Claude Code (from v2.1.277) all read it, and the nearest nested file takes precedence.
- **Site generators:**
  - Material for MkDocs 9.8.0 is in maintenance mode, with fixes ending 2027-05-05.
  - Zensical 0.0.74 is alpha; MkDocs 1.6.1 is unmaintained.
  - Starlight 0.42.8 is active but 0.x; Docusaurus 3.10.2 is active.
  - Writerside was sunset in 2025.
- **Dokka 2.2.0:**
  - HTML is its stable format; GFM, Jekyll and Javadoc output are alpha, and there is no JSON output.
  - `@sample` only embeds a function body. Since 2.2.0 samples are not runnable.
- **kotlinx-knit 0.5.1** is experimental and built against Kotlin 1.9.21; its Kotlin 2.4 compatibility is unverified.
- **DocC** can't document a SwiftPM binary target (swift-docc-plugin#32, open).
- **SwiftPM `Snippets/`** build for the host, which would need a macOS framework slice that MeshLink doesn't build.
- **Docs checks:** lychee 0.24.2 (links; has an offline mode), markdownlint-cli2 0.24.0, typos 1.51.1 (Rust, offline, low false-positive rate) and Vale 3.26.0.
- **Protocol spec formats:** BCP 14 (RFC 2119/8174) makes only UPPERCASE keywords binding. Wycheproof vectors are JSON with a JSON Schema per file and `tcId`/`result`/`flags` fields.

## Decision

1. **Layout** (Diátaxis, O4):
   - `docs/tutorials/`, `docs/how-to/`, `docs/reference/`, `docs/explanation/`.
   - `docs/adr/`.
   - `docs/research/`: dated, historical, never authoritative.
   - `docs/spec/`: normative protocol, one file per layer; vectors in `docs/spec/vectors/`.
   - `docs/agents/`.
   - `docs/README.md` is organized by reader task.
2. **Authority order when documents conflict:**
   1. Constitution;
   2. spec and vectors (C10);
   3. public API dump;
   4. ADRs (rationale only, never restating values);
   5. generated reference;
   6. everything else.

   A conflict is a defect, fixed in the same change (O1).
3. **One source per fact.** Each fact appears in one source; every other copy is generated from it, and CI fails on a difference (`git diff --exit-code` after regeneration).
   - **Protocol facts** (limits, constants, wire codes) live in data files under `docs/spec/`. Kotlin tests check the implementation against them, which keeps the spec independent of the implementation.
   - **API facts** (configuration options with defaults and bounds, diagnostic codes, error types) live in Kotlin source: closed hierarchies plus KDoc. A JVM test generates `docs/reference/*.md` and the agent JSON.
   - No hand-maintained catalog that repeats code.
4. **Agent layer (O8):**
   - `AGENTS.md` is the only agent instruction file. No `CLAUDE.md`, `.github/copilot-instructions.md` or `.cursor/rules` copies. Nested `AGENTS.md` files appear only once module code exists.
   - `docs/agents/index.md` is generated: every document with its type, what it is authoritative for, and its status.
   - **Contract reference:** the KGP ABI dump, plus a generated one-line-per-declaration summary.
   - **Diagnostic index:** stable code → meaning → likely cause → fix, in Markdown and JSON (decision 3).
   - `docs/agents/lessons.md` is created with the first device-lab lessons.
   - `llms.txt` and `.md` page mirrors are generated only when the docs are published as a site, from the same source as `docs/agents/index.md`. They are a convenience, not a contract.
5. **Kotlin and Swift side by side.** Every tutorial, how-to and API sample shows Kotlin and Swift. Both are compiled and run (O5):
   - **Kotlin:** `@sample` functions live in `commonTest`. Tutorial and how-to code is copied from marked regions of compiled test files, and a build check fails if the Markdown and the region differ. No kotlinx-knit.
   - **Swift:** examples are XCTest files in the SwiftPM consumer test package, which runs on the simulator, kept in sync by the same region check. No DocC and no SwiftPM `Snippets/`.
6. **Troubleshooting is built on diagnostic codes.**
   - Each code has a stable ID, a stable anchor, its cause, its remedy, and the related setting or platform limit.
   - `docs/how-to/troubleshoot-*.md` pages group codes by symptom: peers not found, messages not delivered, high battery use.
   - Humans and agents read the same generated entries.
7. **Protocol spec:** Markdown with BCP 14 keywords. Vectors are Wycheproof-style JSON with a JSON Schema per file and `tcId`/`result`/`flags` fields, consumed by the Kotlin conformance tests and the Rust reference. Use the IETF formats (xml2rfc/kramdown-rfc) only for an IETF submission.
8. **API reference:** Dokka HTML only (version pinned by ADR 0005), published on GitHub Pages. No site generator for the prose until the first release. The prose Markdown renders on GitHub. Re-evaluate then: Zensical if it is stable, otherwise Starlight.
9. **ADRs:**
   - One flat numbered list, `docs/adr/NNNN-*.md`, with a status line and a version.
   - A replaced ADR is marked "superseded by NNNN" and never deleted.
   - `docs/adr/README.md` is generated from the ADR headers.
10. **Docs checks (O6), run in CI and locally:**
    - lychee: repository links on every PR, external links weekly.
    - markdownlint-cli2.
    - typos, configured for US English, which is the docs' spelling standard.
    - Vale is deferred.
11. **Dependency register check:** a Gradle task `checkDependencyRegister` in build logic, wired into `check`. It fails if `gradle/libs.versions.toml` and `docs/reference/dependencies.md` don't match row for row. CI tools have their own register section, checked against the workflow files.

## Alternatives

- **One root `SPEC.md` plus hand-written catalogs** (template): drifted in practice.
- **YAML-first diagnostics catalog that generates Kotlin:** a common pattern and language-neutral. Rejected because Kotlin with KDoc is already written once; YAML would add a schema and validator to maintain.
- **Material for MkDocs or Zensical now:** end-of-life and alpha respectively. **Docusaurus, Starlight or VitePress now:** add a Node toolchain before there is anything to publish.
- **Dokka GFM output as the agent API index:** alpha, and needs a custom plugin. The ABI dump is already a gated artifact.
- **kotlinx-knit:** experimental, with no verified Kotlin 2.4 support.
- **DocC, or SwiftPM `Snippets/`:** binary targets are unsupported, and snippets need a macOS slice.
- **Per-tool agent files** (`CLAUDE.md`, Copilot and Cursor rules): duplicates that diverge. All four agents read `AGENTS.md`.
- **cspell:** noisier and needs Node. **Vale now:** prose-style rules before there is prose to style.

## Risks

- `llms.txt` may be replaced by another convention. It is generated, so dropping it is cheap.
- The region-sync check is MeshLink's own code, so it needs its own tests.
- Generated reference relies on KDoc quality. Dokka's `reportUndocumented` and the golden-file diff surface gaps.
- No site until the first release means Material's end-of-life (2027-05-05) may arrive before a choice is made. The choice is re-evaluated at release.

## Migration

- Existing ADRs and reference pages already fit the layout.
- British spellings changed to US English on 2026-11-01.
- `docs/README.md` is added now.
- The generators (`docs/agents/index.md`, `docs/adr/README.md`, reference pages, the diagnostic index) and the region and register checks arrive with the first build scaffolding.

## Amendments

2026-11-09, ADR headers and the pre-push privacy gate (retro after ticket 35):

- **Version rule (decision 9):** an ADR's version is 1 plus the number of amendment blocks. An amendment block is a line under `## Amendments` that starts with a date and a comma (`YYYY-MM-DD,`). The status line's `(amended …)` names every block date and no other date.
- **ADR header check (decision 10):** `tools/check_adrs.py` enforces the version rule in the Docs workflow's lint job. Its first run found 4 drifted headers (ADRs 0002, 0003, 0007 and 0010), which were corrected.
- **Link check retry (decision 10):** the lychee step runs a second time only if the first fails, which absorbs a transient download failure of the lychee binary. A real broken link fails both runs.
- **Pre-push privacy gate:** the tracked hook `.githooks/pre-push` runs `tools/check_private.py --history` on the commits being pushed. Each clone enables it once with `git config core.hooksPath .githooks`.

2026-11-13, docs site for 0.1.0 (0.1.0 map ticket "Does 0.1.0 publish a prose docs site, and with which generator?"; [research](https://github.com/trancee/MeshLink/blob/research/docs-site-generator/docs/research/2026-11-13-docs-site-generator.md)):

- **No prose site in 0.1.0 (decision 8):** the re-evaluation at the first release found neither preferred generator stable: Zensical is 0.1.1 beta (stable expected around 2027-02-05) and Starlight is 0.43. E1–E3 allow only stable releases, so GitHub Pages publishes only the Dokka reference. The prose stays on GitHub, linked from the README and from Dokka's module page.
- **URL layout (decision 8):** Dokka lives at `https://trancee.github.io/MeshLink/api/` from the first release, so a later prose site can take the root without breaking API links. The root is a small hand-written `index.html` linking to the API and to `docs/README.md` on GitHub. Pages holds only the latest release (ADR 0019).
- **Next re-evaluation (decision 8):** at Zensical's first stable release or at 1.0.0, whichever comes first: Zensical if it is stable then, otherwise Starlight. The scheduled dependency freshness check (ADR 0017) reports the Zensical release.
- No new tool: Dokka is already in the [dependency register](../reference/dependencies.md).
