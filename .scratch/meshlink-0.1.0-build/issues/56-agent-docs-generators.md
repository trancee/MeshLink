# Generate the agent docs and check the dependency register

Status: ready-for-agent
Type: AFK
Blocked by: 54

## Behavior

An agent opens `docs/agents/index.md` and finds every document with its type, what it is authoritative for, and its status. It reads the public API one line per declaration in a generated contract reference. `docs/adr/README.md` is generated from the ADR headers. `./gradlew check` fails when any generated page is stale, and when the version catalog, the workflows and the dependency register disagree. A scheduled, non-required job reports newer stable releases of every registered dependency and tool, and the first stable Zensical release.

## Spec and ADR references

- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md#decision) decisions 2 (authority order), 3 (one source; CI fails on a difference), 4 (agent layer: generated `docs/agents/index.md`, contract reference, diagnostic index), 9 (generated `docs/adr/README.md`) and 11 (`checkDependencyRegister`), and the 2026-11-13 [amendment](../../../docs/adr/0006-documentation-architecture.md#amendments): the freshness check reports the Zensical release. `llms.txt` is not generated, because 0.1.0 publishes no prose site.
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#decision) decision 6: docs and governance tasks live in `build-logic/`.
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md#decision), "Dependency register" and "CI": one catalog, one register row per entry, actions pinned by SHA with a version comment.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md#decision) decision 12: the dependency freshness check is a scheduled job.
- Constitution O8 and E6 in [`CONSTITUTION.md`](../../../CONSTITUTION.md).
- [Backlog spine](../spec.md#scheduled-jobs): this ticket adds the "Dependency freshness report" job.

## Acceptance criteria

- [ ] A `build-logic/` task generates `docs/agents/index.md`: every tracked Markdown document under `docs/` and the root governance files, with its Diátaxis type or role, its authority from ADR 0006 decision 2, and its status (an ADR's status line, "generated", or "current"). Nothing in it is hand-maintained.
- [ ] The same task family generates `docs/agents/contract.md` from the committed KGP ABI dumps, one line per public declaration, and `docs/adr/README.md` from the ADR headers. The ADR index matches what `tools/check_adrs.py` reads.
- [ ] `checkAgentDocs` is wired into `check` and fails with the differing file named when a generated page is stale. `./gradlew updateReference` (from ticket 53) also rewrites these pages.
- [ ] `checkDependencyRegister` is wired into `check`. It fails when `gradle/libs.versions.toml` and the register's catalog sections differ row for row, and when a workflow `uses:` action or pinned CI tool differs from the "CI tools" section in name, version or version comment.
- [ ] Each check has tests in `build-logic/` that prove a stale page, a missing row, an extra row and a version mismatch each fail (T1, ADR 0006 risks). The tests need no network.
- [ ] A scheduled workflow, `.github/workflows/freshness.yml`, runs weekly and on `workflow_dispatch`. It is never a required check. It runs `tools/check_freshness.py` (standard library only, no new dependency) and lists every catalog entry, CI action and tool with a newer stable release, plus the first stable Zensical release. It writes the list to the job summary and fails when the list is not empty, so GitHub notifies the owner. It changes no file and opens no pull request.
- [ ] Every action in the new workflow is pinned to the latest stable release by commit SHA with a version comment, verified on the day it is written, and has a register row. `actionlint` passes.
- [ ] `docs/README.md` links the generated index, contract reference and diagnostic index from "For AI agents".

## Proof commands

```sh
./gradlew check
./gradlew updateReference && git diff --exit-code docs
python3 tools/check_freshness.py
actionlint
python3 tools/check_adrs.py
npx --yes markdownlint-cli2@0.24.1 "**/*.md"
typos
lychee --offline .
```

`python3 tools/check_freshness.py` is new: this ticket creates it and adds it to the spine. It needs the network, so it is never a required gate.

## Docs to update

- `docs/agents/index.md`, `docs/agents/contract.md` and `docs/adr/README.md` (generated; new).
- [`docs/README.md`](../../../docs/README.md): "For AI agents" and the ADR list point to the generated pages.
- [`docs/reference/dependencies.md`](../../../docs/reference/dependencies.md): rows for the new workflow's actions; the text says the register is now checked by `checkDependencyRegister`.
- [`PROJECT.md`](../../../PROJECT.md): the new check tasks and `tools/check_freshness.py`.

## Devices needed

None.
